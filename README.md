# igris-android

Native Android companion for the **IGRIS** device ecosystem — a local-first,
no-cloud mesh that syncs **clipboard**, **notifications**, and **files**
between your phone and your desktops over the LAN. The Kotlin app drives the
same Rust protocol core the desktop uses ([`igris-protocol`](../igris-protocol)),
via UniFFI, so both sides speak an identical wire protocol.

- Desktop app: [`igris-ecosystem`](../igris-ecosystem) (Rust/Dioxus, Windows/macOS/Linux)
- Shared protocol crate: [`igris-protocol`](../igris-protocol) (eco REST + FastSwap/LocalSend v2)
- Ports: eco HTTP **53327** / TLS **53328**, FastSwap HTTP **53317** / TLS **53318**

## Stack

| Layer | Choice |
|---|---|
| UI | Kotlin + Jetpack Compose, Material 3 |
| Core | `igris-protocol` Rust crate via UniFFI/JNA (`.so` for arm64-v8a, armeabi-v7a, x86_64, built with `cargo ndk`, NDK 29) |
| Build | AGP 8.7.3, Kotlin 2.1.21, Gradle 8.13, minSdk 26, targetSdk 35 |
| Persistence | DataStore (device identity), files (trust store, a11y diagnostics) |

## Features

### Clipboard sync (the hard part)
Android 10+ forbids background clipboard reads, and OEM skins patch around
it differently (HyperOS/MIUI even withholds the clip-changed callback and
text-selection events). Sync therefore works through **redundant triggers**,
any one of which is enough:

| Trigger | Confidence | Notes |
|---|---|---|
| Copy/Cut-labelled button tap (a11y event) | high — never throttled | Covers app menus **and** the system selection menu; localized word map (~45 languages/scripts) |
| "Copied" toast (a11y event) | high | Best timing — fires after the copy commits (Instagram, OTP auto-copy). Matches both `android.widget.Toast` and `Toast$TN` classes |
| Copy-menu popup + subtree change | high | Sefirah/XClipper heuristic |
| Long-press → nearby tap | low — throttled 1s | Fallback for icon-only, unlabelled copy buttons (WhatsApp toolbar) |
| Selection collapse (two selection events) | low | Works on skins that deliver selection events (stock, One UI, OxygenOS, ColorOS, Nothing OS…) |
| Quick Settings tile "Sync clipboard" | manual | User-initiated launch — the one trigger no OEM can withhold |
| Share → IGRIS (`ACTION_SEND`) | manual | No clipboard read at all |
| Opening the app | manual | 1s foreground poll |

All automatic triggers raise `ClipboardGrabActivity` — a transparent 1×1
activity (KDE Connect's approach) that claims focus, reads the clipboard
(450 ms settle delay + one late re-read ~1.1 s later for slow-committing
apps), pushes into the Rust fan-out, and finishes. Clips marked
`EXTRA_IS_SENSITIVE` (password managers) are never synced.

Undelivered pushes are retried at most every 10 s (rate-limited so an
offline peer can't cause a 1 Hz push loop).

### Discovery
- Periodic /24 subnet scan on eco ports across **all local IPv4 interfaces**
  (a single-interface guess — the old bug — goes blind on multi-homed hosts
  like Android rmnet-before-wlan0 or desktop VPN/vEthernet adapters).
- Self-discovery is prevented by skipping every local interface address.
- Private subnets scanned first; loopback, link-local and 192.0.0.0/24
  (Qualcomm rmnet internal) skipped; dedup by device id; max 4 subnets.
- Trust: two-way pair handshake via `/api/ecosystem/v1/pair/request`,
  persisted on both sides. The desktop persists its device id
  (`pkg/device_id`) so links survive desktop restarts.

### File transfer (FastSwap)
LocalSend v2-compatible: `register → prepare-upload → confirm-upload
(60 s approval) → upload`, 64 KB chunks, self-signed TLS, 10 GiB limit,
path sanitization, MediaStore/Downloads save on receive.

### Notification sync
`NotificationListenerService` mirrors notifications to linked peers
(`/notification/sync`) with reply forwarding (`/notification/reply`) and
dedup.

### Keep-alive
Foreground service (`connectedDevice`), tiered wake locks, AlarmManager
watchdog, boot receiver, OEM battery-optimization exemption.

## Build

```bash
./gradlew :app:assembleDebug     # cargo ndk (3 ABIs) + uniffi-bindgen + APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Post-install setup (one-time, per device)

```bash
adb shell pm grant com.igris.companion android.permission.READ_LOGS
adb shell appops set com.igris.companion SYSTEM_ALERT_WINDOW allow
adb shell settings put secure enabled_accessibility_services \
  com.igris.companion/.accessibility.IgrisClipboardAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell dumpsys deviceidle whitelist +com.igris.companion
```

Then: grant notification access in Settings, disable battery optimization
for IGRIS, and add the **Sync clipboard** Quick Settings tile.

### OEM quirks (field notes)

- **HyperOS / MIUI (Xiaomi, verified on POCO F6)**: silently **disables the
  accessibility service on every APK update** — re-enable it (adb command
  above or Settings → Accessibility). Delivers click/toast/content events
  but not text-selection events. Needs "display pop-up windows while
  running in background" for background grabs.
- **Funtouch/OriginOS (Vivo/iQOO, verified on iQOO I2501)**: delivers toast
  events; aggressive background-app killing — keep the battery whitelist.
- **General**: if sync stops, check (1) accessibility toggle, (2) duplicate
  desktop instances (a second instance silently binds 53329+ and becomes
  invisible to scanners), (3) both devices on the same subnet.

## Debugging

```bash
# a11y service log (triggers, event trace, per-event-type histogram)
adb shell run-as com.igris.companion cat files/a11y.log

# Rust core logs (discovery, clipboard fan-out, transfers)
adb logcat -s IGRIS
```

`a11y hist: {2048=…, 1=…}` lines show which event types the OEM actually
delivers; `a11y ev: …` lines show button labels (content description only,
never message text) so app-specific copy buttons can be identified and the
word map tuned.

## Attribution

- Copy-gesture heuristics adapted from
  [Sefirah-Android](https://github.com/shrimqy/Sefirah-Android), which adapts
  **XClipper** by Kaustubh Patange — **Apache License 2.0**.
- Transparent grab-activity technique mirrors KDE Connect's
  `ClipboardFloatingActivity` (concept only, clean-room).
- FastSwap speaks the LocalSend v2 protocol (protocol compatibility only).
