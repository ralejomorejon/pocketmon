# PocketMon — Android app

Turns an Android phone into a live hardware monitor for a Windows PC running the PocketMon agent
(`../pc-agent`). Speaks the protocol in [`../protocol/PROTOCOL.md`](../protocol/PROTOCOL.md).

- Package `com.pcstats.monitor`, minSdk 26, targetSdk/compileSdk 36
- Kotlin 2.4.20 (AGP built-in Kotlin + Compose compiler plugin), AGP 9.5.1, Gradle 9.8.2
- Jetpack Compose (Material3, dark only), OkHttp WebSocket, kotlinx.serialization, DataStore Preferences

## Build

Requires the Android SDK (platform 36) and a JDK 17+ (Android Studio's bundled JBR works).
Create `local.properties` with `sdk.dir=...` if Android Studio hasn't already (it is git-ignored).

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug          # APK: app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat testDebugUnitTest      # JVM unit tests (protocol parsing, session stats, formatting)
.\gradlew.bat assembleRelease        # signed APK: app\build\outputs\apk\release\app-release.apk
```

Release signing reads `~/.pocketmon/keystore.properties` (or the file named by the `POCKETMON_KEYSTORE_PROPERTIES`
environment variable) with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Keep the keystore and that
file out of the repo, and back them up: Android only installs updates signed with the same key. Without the file,
`assembleRelease` produces an unsigned APK.

## Install

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.pcstats.monitor/.MainActivity
```

## Connecting

- **Wi-Fi:** phone and PC on the same network. The agent broadcasts a UDP beacon on port 28556; PCs
  appear in the list on the Connect screen, or type the PC's IP. Enter the 6-digit code shown in the
  PC tray and tap **Pair**. The token is saved, so later launches reconnect automatically.
- **USB:** enable USB debugging and plug the phone in. The agent runs `adb reverse tcp:28555 tcp:28555`
  automatically (or run `scripts/usb-connect.ps1`), and the app connects to `ws://127.0.0.1:28555/ws`.

Connection order: USB (127.0.0.1) → last saved Wi-Fi host → hosts discovered for the saved PC id.
Reconnects with exponential backoff (1 s → 10 s). While on Wi-Fi, USB is re-probed every 15 s
and preferred when available.

## Dashboard designs

Settings → **Dashboard design** switches between:

- **Modern** (default): full-screen panels with large temperature gauges for CPU/GPU (load, power, clock beside
  them), per-core load bars, VRAM bar, memory ring, storage list, network rings and a clock.
- **Classic**: the original tile grid with small arc gauges and sparklines.

Both layouts are drawn at a reference size and scaled to fit the screen, so nothing is cut off.

## OLED burn-in protection

- Pure black background everywhere, immersive fullscreen on the dashboard; static labels are dim gray.
- Pixel shift: the dashboard (and its overlay) drifts in a slow random walk within ±8 dp, stepping
  every 60 s with a 2 s animation (`Modifier.burnInShift`, configurable).
- No dimming: the dashboard keeps the screen on at your normal brightness (`FLAG_KEEP_SCREEN_ON`); pixel shifting is the burn-in protection.

## Layout

```
app/src/main/java/com/pcstats/monitor/
  MainActivity.kt, MainViewModel.kt
  protocol/Protocol.kt          wire models + JSON
  net/ConnectionManager.kt      connect/pair/reconnect/USB re-probe
  net/DiscoveryListener.kt      UDP 28556 listener (MulticastLock)
  net/WsSession.kt, Hosts.kt
  data/SettingsRepository.kt    DataStore: settings + saved PC
  burnin/                       PixelShiftWalker, Modifier.burnInShift
  ui/                           Connect, Dashboard, Settings screens, theme, gauges
  util/                         formatting, sample history
```
