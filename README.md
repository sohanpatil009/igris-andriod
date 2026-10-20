# igris-android

Native Android companion for the **IGRIS** device ecosystem — a local-first,
no-cloud mesh that syncs **clipboard**, **notifications**, **files**,
**calls**, and **SMS** between your phone and your desktops over the LAN.
The Kotlin app drives the same Rust protocol core the desktop uses
([`igris-protocol`](../igris-protocol)) via UniFFI, so both sides speak an
identical wire protocol.

- Desktop app: [`igris-ecosystem`](../igris-ecosystem) (Rust/Dioxus, Windows/macOS/Linux)
- Shared protocol crate: [`igris-protocol`](../igris-protocol) (eco REST + FastSwap native v1 + LAN audio)
- Ports: eco HTTP **53327** / TLS **53328**, FastSwap HTTP **53317** / TLS **53318**, LAN call audio TCP **53329** (the phone listens)

## Stack

| Layer | Choice |
|---|---|
| UI | Kotlin + Jetpack Compose, Material 3 (tabs: Devices, Calls, Clipboard, Notifications, Transfers, Settings) |
| Core | `igris-protocol` Rust crate via UniFFI/JNA (`.so` for arm64-v8a, armeabi-v7a, x86_64, built with `cargo ndk`, NDK 29) |
| Build | AGP 8.7.3, Kotlin 2.1.21, Gradle 8.13, minSdk 26, targetSdk 35 |
| Persistence | DataStore (device identity), files (trust store, a11y diagnostics) |

The Gradle build compiles the Rust core for all three ABIs and regenerates
the Kotlin UniFFI bindings on every build (`cargoNdkBuild` → `copyRustLibs`
+ `generateUniffi` → `preBuild`).

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
| Copy-menu popup + subtree change | high | Popup-subtree heuristic |
| Long-press → nearby tap | low — throttled 1s | Fallback for icon-only, unlabelled copy buttons (WhatsApp toolbar) |
| Selection collapse (two selection events) | low | Works on skins that deliver selection events (stock, One UI, OxygenOS, ColorOS, Nothing OS…) |
| Quick Settings tile "Sync clipboard" | manual | User-initiated launch — the one trigger no OEM can withhold |
| Share → IGRIS (`ACTION_SEND`) | manual | No clipboard read at all |
| Opening the app | manual | 1s foreground poll |

All automatic triggers raise `ClipboardGrabActivity` — a transparent 1×1
activity (the KDE Connect approach) that claims focus, reads the clipboard
(450 ms settle delay + one late re-read ~1.1 s later for slow-committing
apps), pushes into the Rust fan-out, and finishes. Clips marked
`EXTRA_IS_SENSITIVE` (password managers) are never synced. The reverse
direction uses `ClipboardSetActivity` — a transparent activity that makes
background clipboard writes legal on Android 10+.

Undelivered pushes are retried at most every 10 s (rate-limited so an
offline peer can't cause a 1 Hz push loop). Media clipboard (base64) is
supported behind the config flag.

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

### File transfer (FastSwap native v1)
Igris native: `register → prepare-upload → confirm-upload
(60 s approval) → upload`, 64 KB chunks, self-signed TLS, 10 GiB limit, path
sanitization, MediaStore/Downloads save on receive, per-session progress and
cancellation. Legacy `/api/localsend/v2/*` route aliases keep older peers
interoperable.

### Notification sync
`NotificationListenerService` mirrors notifications to linked peers
(`/notification/sync`) with reply forwarding (`/notification/reply`),
remote action buttons (`/notification/action`), and dismiss mirroring
(`/notification/dismiss`). Notifications are keyed by
`StatusBarNotification.key` (updates dedupe in place) and carry base64
app icons (≤96 px) with MD5 hash-skips. Incoming mirrored notifications are
posted as system notifications with inline reply where supported
(`MirrorReplyReceiver`).

### Calls & SMS (enterprise, opt-in)
- `CallSmsBridge` observes phone state (`TelephonyCallback` on Android 12+,
  `PhoneStateListener` below) and pushes call events
  (ringing / dialing / talking / ended) into the core, which fans them out
  to linked peers.
- Remote call control from the desktop sails back over `/call/action`:
  accept, decline, mute, end, dial — executed against the telecom stack.
- Call history is mirrored to the desktop each time the service starts (`/call/log/sync`, sent once the node finishes booting).
- SMS: the desktop asks the phone to transmit via its SIM
  (`onSmsSendRequested` → `SmsManager`, multipart-aware); mirrored SMS from
  peers surface through `onSmsReceived`.
- Contacts: `ContactProvider` exposes the phonebook
  (`READ_CONTACTS`, opt-in) so the desktop dial pad can search it.

### LAN call audio
The phone runs the **listener** role of the raw duplex audio link
(`AudioEngine`): `AudioRecord` captures 48 kHz mono s16 in 10 ms frames and
pushes them into the Rust node; peer frames arrive through
`onAudioFrame` and play on a media-routed `AudioTrack` with a bounded
jitter buffer (250 frames, drop-on-full). Sessions open/close on the node's
`onAudioStreamState`. Trust-gated: only linked peers' IPs are accepted.
On the desk the same link runs in the initiator role (cpal/rodio).

### Keep-alive
Foreground service (`connectedDevice` FGS — no Android 14 6 h/day cap,
restartable from boot/background on Android 15), tiered wake locks
(CPU + WiFi only while ≥1 mobile peer is online), AlarmManager watchdog,
boot receiver (`RestartReceiver`), and OEM battery-optimization exemption
request.

## Build

```bash
./gradlew :app:assembleDebug     # cargo ndk (3 ABIs) + uniffi-bindgen + APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires `ANDROID_HOME`/`ANDROID_SDK_ROOT` and NDK 29
(`ANDROID_NDK_HOME`, defaulting to `$ANDROID_SDK_ROOT/ndk/29.0.14206865`).

## Post-install setup (one-time, per device)

The first-run onboarding screen (and the Settings tab afterwards) walks
through accessibility, **display over other apps**, notifications and
battery in-app — no adb required for a normal install. **Display over other
apps** is the one that's easy to miss manually: without it, the phone can
receive a peer's clipboard push over the network but silently fails to
actually write it (the OS drops the background activity launch with no
exception), so sync looks one-way (phone → PC only). The equivalent adb
incantations remain useful for headless/bulk provisioning:

```bash
adb shell pm grant com.igris.companion android.permission.READ_LOGS
adb shell appops set com.igris.companion SYSTEM_ALERT_WINDOW allow
adb shell settings put secure enabled_accessibility_services \
  com.igris.companion/.accessibility.IgrisClipboardAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell dumpsys deviceidle whitelist +com.igris.companion
```

Then: grant notification access in Settings, disable battery optimization
for IGRIS, and add the **Sync clipboard** Quick Settings tile. Calls/SMS
features additionally need the opt-in runtime grants (phone state,
call log, contacts, SMS) — the app requests these from the Calls tab /
Settings, and everything works without them if you only want
clipboard/notifications/files.

### OEM quirks (field notes)

- **HyperOS / MIUI (Xiaomi, verified on POCO F6)**: silently **disables the
  accessibility service on every APK update** — re-enable it (adb command
  above or Settings → Accessibility). Delivers click/toast/content events
  but not text-selection events. Needs "display pop-up windows while
  running in background" for background grabs.
- **Funtouch/OriginOS (Vivo/iQOO, verified on iQOO I2501)**: delivers toast
  events; aggressive background-app killing — keep the battery whitelist.
- **General**: if sync stops, check (1) accessibility toggle, (2) duplicate
  desktop instances (the desktop now focuses its running window instead of
  launching a second, silent instance), (3) both devices on the same subnet.

## Debugging

```bash
# a11y service log (triggers, event trace, per-event-type histogram)
adb shell run-as com.igris.companion cat files/a11y.log

# Rust core logs (discovery, clipboard fan-out, transfers, calls, audio)
adb logcat -s IGRIS
```

`a11y hist: {2048=…, 1=…}` lines show which event types the OEM actually
delivers; `a11y ev: …` lines show button labels (content description only,
never message text) so app-specific copy buttons can be identified and the
word map tuned.

## Attribution

- Igris native enterprise protocol (no external wire-protocol dependencies).
- Clipboard grab-activity technique (clean-room Igris implementation).
