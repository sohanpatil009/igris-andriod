package com.igris.companion.accessibility

import android.view.accessibility.AccessibilityEvent

/**
 * Copy/cut gesture detection from accessibility events.
 *
 * Adapted from Sefirah (github.com/shrimqy/Sefirah-Android), which adapts
 * XClipper by Kaustubh Patange — both Apache License 2.0.
 *
 * Android 10+ denies clipboard reads to background apps and some OEM skins
 * (HyperOS/MIUI notably) withhold the clip-changed callback and even
 * text-selection events, so "the clipboard changed" is inferred from HOW the
 * user copies instead:
 *  1. A tap on anything labelled "Copy"/"Cut" (localized) — the context-menu
 *     or toolbar button (WhatsApp, Telegram, Signal, Messenger, WeChat 复制,
 *     LINE コピー, KakaoTalk 복사, …).
 *  2. A text selection that collapses right after existing (the user picked
 *     an action on the selection menu) — works on skins that deliver
 *     selection events (stock, One UI, OxygenOS, ColorOS, Nothing OS…).
 *  3. A "copied to clipboard" toast (localized).
 *  4. A subtree content-change right after a copy-labelled window appeared.
 *  5. A long-press followed by a nearby tap — the fallback for icon-only
 *     copy buttons with no accessibility label.
 *
 * The gesture only TRIGGERS the grab — the clipboard is read later from the
 * focused grab activity, never from the a11y service.
 */
class ClipboardDetection(
    private val copyWords: Set<String>,
    private val toastKeywords: Regex = DEFAULT_TOAST_KEYWORDS,
) {

    private var lastSelectionEvent: AEvent? = null
    private var previousEvent: AEvent? = null
    private var lastLongPressMs: Long = 0

    /**
     * @return null when the event is not a copy gesture, otherwise a reason
     *   tag: "label" (a tap on a view explicitly labelled Copy/Cut, or a
     *   copied-toast — high confidence, callers should not throttle these)
     *   or "gesture" (inferred patterns — throttle these).
     */
    fun copyGestureReason(event: AccessibilityEvent?): String? {
        if (event == null) return null
        val e = AEvent.from(event)
        val now = System.currentTimeMillis()

        // Long-press on text followed by a nearby tap: the user brought up
        // the text-action menu (WhatsApp et al. render a custom toolbar with
        // icon-only buttons, so no Copy label to match) and picked an action.
        // A grab when nothing was copied is a harmless deduped no-op.
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            lastLongPressMs = now
        }
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
            now - lastLongPressMs in 1..LONG_PRESS_WINDOW_MS
        ) {
            lastLongPressMs = 0
            return "gesture"
        }

        val result = detectAppropriateEvents(e) ?: detectViaWindowChange(e) ?: detectViaToast(e)
        previousEvent = e
        return result
    }

    private fun detectAppropriateEvents(e: AEvent): String? {
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            val first = lastSelectionEvent
            lastSelectionEvent = e
            if (first != null) {
                // A real selection that collapses on the same node: the user
                // acted on the selection menu (copy/cut).
                if (e.fromIndex == e.toIndex &&
                    first.packageName == e.packageName &&
                    first.fromIndex != first.toIndex &&
                    first.className == e.className &&
                    first.text.toString() == e.text.toString()
                ) {
                    return "gesture"
                }
            }
            return null
        }

        // Tap on the Copy/Cut button itself. Downside (inherited from
        // XClipper): tapping any text containing the word can false-positive.
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
            (e.contentDescription.matchesWord() || e.text.matchesWord())
        ) {
            return "label"
        }
        return null
    }

    private fun detectViaWindowChange(e: AEvent): String? {
        // A subtree content change right after a copy-labelled window (the
        // text-action popup) appeared: the menu's click is consumed by the
        // popup window itself and never surfaces as TYPE_VIEW_CLICKED.
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            e.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
        ) {
            val prev = previousEvent ?: return null
            if (prev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                prev.text?.size == 1 &&
                (prev.text.toString().matchesWord() || prev.contentDescription.matchesWord())
            ) {
                return "label"
            }
        }
        return null
    }

    private fun detectViaToast(e: AEvent): String? {
        // OEMs/apps emit either the Toast class or its inner TN class on the
        // notification event (Instagram: "android.widget.Toast").
        if (e.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            (e.className == "android.widget.Toast" ||
                e.className == "android.widget.Toast\$TN") &&
            e.text?.toString()?.contains(toastKeywords) == true
        ) {
            return "label"
        }
        return null
    }

    private fun CharSequence?.matchesWord(): Boolean {
        if (isNullOrEmpty() || length > MAX_WORD_LENGTH) return false
        val s = toString()
        return copyWords.any { s.contains(it, ignoreCase = true) }
    }

    private fun List<CharSequence?>?.matchesWord(): Boolean =
        this?.any { it.matchesWord() } == true

    private data class AEvent(
        val eventType: Int,
        val packageName: CharSequence?,
        val className: CharSequence?,
        val text: List<CharSequence?>?,
        val contentDescription: CharSequence?,
        val contentChangeTypes: Int,
        val fromIndex: Int,
        val toIndex: Int,
    ) {
        companion object {
            fun from(event: AccessibilityEvent) = AEvent(
                eventType = event.eventType,
                packageName = event.packageName,
                className = event.className,
                text = ArrayList(event.text ?: emptyList()),
                contentDescription = event.contentDescription,
                contentChangeTypes = event.contentChangeTypes,
                fromIndex = event.fromIndex,
                toIndex = event.toIndex,
            )
        }
    }

    companion object {
        private const val MAX_WORD_LENGTH = 30
        private const val LONG_PRESS_WINDOW_MS = 3500
        val DEFAULT_TOAST_KEYWORDS = Regex("(?i)(copied|clipboard)")

        /**
         * Localized "Copy"/"Cut" menu labels for the click heuristic. Apps
         * render their text-action menus in the system locale, so matching
         * the label covers every messaging app (WhatsApp, Telegram, Signal,
         * Messenger, WeChat, LINE, KakaoTalk, Zalo, QQ, Google Messages,
         * Teams, Slack, …) in that locale. Matching is `contains`, so short
         * forms (复制 / コピー / 복사) also match longer menu entries.
         */
        fun wordsForLocale(language: String, country: String = ""): Set<String> {
            // Chinese: simplified (CN/SG) vs traditional (TW/HK/MO).
            if (language == "zh") {
                return if (country in setOf("TW", "HK", "MO")) {
                    setOf("複製", "剪下")
                } else {
                    setOf("复制", "剪切", "拷贝")
                }
            }
            return when (language) {
                "hi" -> setOf("कॉपी करें", "कॉपी", "काटें")
                "bn" -> setOf("কপি করুন", "কপি", "কাটুন")
                "mr" -> setOf("कॉपी करा", "कॉपी", "कट करा")
                "ta" -> setOf("நகலெடு", "நகல்", "வெட்டு")
                "te" -> setOf("కాపీ చేయి", "కాపీ", "కత్తిరించు")
                "gu" -> setOf("કૉપિ કરો", "કૉપિ", "કાપો")
                "kn" -> setOf("ಪ್ರತಿಮಾಡಿ", "ಪ್ರತಿ", "ಕತ್ತರಿಸಿ")
                "ml" -> setOf("പകർത്തുക", "പകർപ്പ്", "മുറിക്കുക")
                "pa" -> setOf("ਕਾਪੀ ਕਰੋ", "ਕਾਪੀ", "ਕੱਟੋ")
                "ur" -> setOf("کاپی کریں", "کاپی", "کاٹیں")
                "ar" -> setOf("نسخ", "قص")
                "fa" -> setOf("کپی", "برش")
                "he" -> setOf("העתק", "גזור")
                "ru" -> setOf("Копировать", "Копировать ссылку", "Вырезать")
                "uk" -> setOf("Копіювати", "Вирізати")
                "be" -> setOf("Капіраваць", "Выразаць")
                "kk" -> setOf("Көшіру", "Кесу")
                "pl" -> setOf("Kopiuj", "Wytnij")
                "cs" -> setOf("Kopírovat", "Vyjmout")
                "sk" -> setOf("Kopírovať", "Vystrihnúť")
                "hu" -> setOf("Másolás", "Kivágás")
                "ro" -> setOf("Copiază", "Decupează")
                "bg" -> setOf("Копиране", "Изрязване")
                "sr", "hr", "bs" -> setOf("Kopiraj", "Iseci")
                "sl" -> setOf("Kopiraj", "Izreži")
                "mk" -> setOf("Копирај", "Исечи")
                "de" -> setOf("Kopieren", "Ausschneiden")
                "nl" -> setOf("Kopiëren", "Knippen")
                "sv" -> setOf("Kopiera", "Klipp ut")
                "da" -> setOf("Kopiér", "Klip")
                "nb", "nn" -> setOf("Kopier", "Klipp ut")
                "fi" -> setOf("Kopioi", "Leikkaa")
                "is" -> setOf("Afrita", "Klippa")
                "fr" -> setOf("Copier", "Couper")
                "es" -> setOf("Copiar", "Cortar")
                "ca" -> setOf("Copia", "Retalla")
                "eu" -> setOf("Kopiatu", "Ebaki")
                "gl" -> setOf("Copiar", "Cortar")
                "pt" -> setOf("Copiar", "Cortar")
                "it" -> setOf("Copia", "Taglia")
                "el" -> setOf("Αντιγραφή", "Αποκοπή")
                "tr" -> setOf("Kopyala", "Kes")
                "az" -> setOf("Kopyala", "Kəs")
                "id" -> setOf("Salin", "Potong")
                "ms" -> setOf("Salin", "Potong")
                "vi" -> setOf("Sao chép", "Sao chép", "Cắt")
                "th" -> setOf("คัดลอก", "ตัด")
                "ja" -> setOf("コピー", "切り取り")
                "ko" -> setOf("복사", "잘라내기")
                "my" -> setOf("ကူးယူ", "ဖြတ်")
                "km" -> setOf("ចម្លង", "កាត់")
                "lo" -> setOf("ສຳເນົາ", "ຕັດ")
                "sw" -> setOf("Nakili", "Kata")
                "am" -> setOf("ገልብጥ", "ቁረጥ")
                "uz" -> setOf("Nusxalash", "Kesish")
                "fil" -> setOf("Kopyahin", "Gupitin")
                else -> setOf("Copy", "Cut")
            }
        }

        /**
         * Toast text apps show after a successful copy ("Text copied",
         * "已复制", "コピーしました"…). Toasts are high-confidence triggers
         * for apps whose copy action has no visible button event (auto-copy
         * of OTP/verification codes, tap-to-copy in Telegram bots, etc.).
         */
        fun toastKeywordsForLocale(language: String, country: String = ""): Regex {
            if (language == "zh") {
                return if (country in setOf("TW", "HK", "MO")) {
                    Regex("已複製|複製|剪貼簿")
                } else {
                    Regex("已复制|复制|剪贴板")
                }
            }
            val pattern = when (language) {
                "ja" -> "コピーしました|コピー|クリップボード"
                "ko" -> "복사됨|복사|클립보드"
                "hi" -> "कॉपी किया गया|कॉपी|क्लिपबोर्ड"
                "bn" -> "কপি করা হয়েছে|কপি|ক্লিপবোর্ড"
                "ta" -> "நகலெடுக்கப்பட்டது|நகல்|கிளிப்போர்டு"
                "te" -> "కాపీ చేయబడింది|కాపీ|క్లిప్‌బోర్డ్"
                "mr" -> "कॉपी केले|कॉपी|क्लिपबोर्ड"
                "gu" -> "કૉપિ થયું|કૉપિ|ક્લિપબોર્ડ"
                "kn" -> "ಪ್ರತಿ ಮಾಡಲಾಗಿದೆ|ಪ್ರತಿ|ಕ್ಲಿಪ್‌ಬೋರ್ಡ್"
                "ml" -> "പകർത്തി|പകർപ്പ്|ക്ലിപ്പ്ബോർഡ്"
                "pa" -> "ਕਾਪੀ ਕੀਤੀ|ਕਾਪੀ|ਕਲਿੱਪਬੋਰਡ"
                "ur" -> "کاپی ہو گیا|کاپی|کلپ بورڈ"
                "ar" -> "تم النسخ|نسخ|الحافظة"
                "fa" -> "کپی شد|کپی|کلیپ‌بورد"
                "he" -> "הועתק|העתק|לוח"
                "ru" -> "скопировано|Скопировано|буфер обмена|Буфер"
                "uk" -> "скопійовано|Скопійовано|буфер обміну"
                "be" -> "скапіравана|буфер абмену"
                "pl" -> "skopiowano|Skopiowano|schowek"
                "cs" -> "zkopírováno|Zkopírováno|schránka"
                "sk" -> "skopírované|Skopírované|schránka"
                "hu" -> "kimásolva|Kimásolva|vágólap"
                "ro" -> "copiat|Copiat|clipboard"
                "bg" -> "копирано|Копирано|клипборд"
                "sr", "hr", "bs" -> "копирано|Копирано|kopirano|Kopirano"
                "de" -> "kopiert|Kopiert|Zwischenablage"
                "nl" -> "gekopieerd|Gekopieerd|klembord"
                "sv" -> "kopierad|Kopierad|urklipp"
                "da" -> "kopieret|Kopieret|udklipsholder"
                "nb", "nn" -> "kopiert|Kopiert|utklippstavlen"
                "fi" -> "kopioitu|Kopioitu|leikepöytä"
                "fr" -> "copié|copiée|copié dans le presse-papiers|presse-papiers"
                "es" -> "copiado|Copiado|portapapeles"
                "ca" -> "copiat|Copiat|porta-retalls"
                "pt" -> "copiado|Copiado|área de transferência"
                "it" -> "copiato|Copiato|appunti"
                "el" -> "αντιγράφηκε|Αντιγράφηκε|πρόχειρο"
                "tr" -> "kopyalandı|Kopyalandı|pano"
                "id" -> "disalin|Disalin|papan klip"
                "ms" -> "disalin|Disalin|papan keratan"
                "vi" -> "đã sao chép|Đã sao chép|bộ nhớ đệm"
                "th" -> "คัดลอกแล้ว|คัดลอก|คลิปบอร์ด"
                "sw" -> "imenakiliwa|kunakili"
                "fil" -> "nakopya|Nakopya|clipboard"
                else -> "copied|Copied|clipboard"
            }
            return Regex("(?i)($pattern)")
        }
    }
}
