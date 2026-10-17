package com.igris.companion.providers

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import uniffi.igris_protocol.ContactHitInfo
import uniffi.igris_protocol.ContactProvider

/**
 * Phonebook ContentProvider seam. Answers linked-device contact searches
 * (desktop dial pad) by name or number fragment. Results are capped and
 * carry only display name + callable number — nothing else leaves the phone.
 */
class ContactProviderImpl(private val appContext: Context) : ContactProvider {

    override fun searchContacts(query: String, limit: UInt): List<ContactHitInfo> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val cap = limit.coerceIn(1u, 25u).toInt()
        val out = LinkedHashMap<String, ContactHitInfo>()
        try {
            val cr = appContext.contentResolver ?: return emptyList()
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val proj = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            val sel = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?" +
                " OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            val like = "%$q%"
            cr.query(uri, proj, sel, arrayOf(like, like), null)?.use { c ->
                val ni = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pi = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext() && out.size < cap) {
                    val name = if (ni >= 0) c.getString(ni).orEmpty() else ""
                    val number = if (pi >= 0) c.getString(pi).orEmpty() else ""
                    if (number.isBlank()) continue
                    out.putIfAbsent(number, ContactHitInfo(name.ifBlank { number }, number))
                }
            }
        } catch (e: SecurityException) {
            Log.w("IGRIS", "contacts: READ_CONTACTS not granted")
        } catch (t: Throwable) {
            Log.w("IGRIS", "contacts search failed", t)
        }
        return out.values.toList()
    }
}
