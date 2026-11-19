<p align="center"><img src="branding/pocketmon-logo.png" alt="PocketMon" width="560"></p>

<p align="center"><b>Your PC's hardware stats, live on your phone.</b><br>
CPU · GPU · temperatures · clocks · power · VRAM · RAM · network · storage · FPS — over Wi-Fi or USB, with OLED burn-in protection.</p>

<p align="center"><a href="../../releases/latest"><b>⬇ Download the latest release</b></a></p>

---

## Install

PocketMon has two parts: the **agent** on your Windows PC, which reads the sensors, and a **phone app** that shows them.

### 1. Windows PC (required)

1. Download `PocketMon-Agent-win-x64-<version>.zip` from [Releases](../../releases/latest) and extract it anywhere
   (for example `C:\Program Files\PocketMon` or your Documents folder).
2. Run `PocketMon.exe` and accept the administrator prompt (it needs admin rights to read temperatures and FPS).
   A red **PM** icon appears in the tray.
3. For CPU temperature, power and voltage, install the PawnIO driver once:
   `winget install namazso.PawnIO` (or tray menu → **Install PawnIO driver…**), then restart PocketMon.
4. Optional: tray menu → **Start with Windows**.

No .NET install is needed; the agent is self-contained. Windows Firewall rules for the local network are added automatically.

### 2a. Android

1. Download `PocketMon-<version>.apk` from [Releases](../../releases/latest) on the phone and open it
   (allow "Install unknown apps" for your browser when asked).
2. Open PocketMon. The PC shows up automatically on the same Wi-Fi; enter the 6-digit code from the tray
   (**Show pairing code**).
3. **USB:** enable USB debugging on the phone and plug it in. The agent sets up the connection automatically
   (needs [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) / `adb` on the PC).

### 2b. iPhone / iPad (and any other device)

The phone app for iOS is a web app served by the agent itself, with no App Store needed.

1. On the PC: tray menu → **Open on phone (QR code)…**
2. Scan the QR code with the iPhone camera and tap **Pair**.
3. In Safari: **Share → Add to Home Screen**. PocketMon now opens fullscreen like an app.
4. Tap the dashboard once to keep the screen on.
5. **USB:** connect the cable, turn on **Personal Hotspot** and pick its `172.20.10.x` address in the QR window.

The same web app also works on Android, tablets or any browser (`http://<pc-ip>:28555`).

## Features

- Two dashboard designs (**Modern** and **Classic**), portrait and landscape, scaled to fill any screen
- CPU and GPU temperature gauges with load, power and clock, per-core load, hotspot, VRAM
- Memory, storage (free space and temperature), network (rate, peaks, totals), FPS of the foreground game
- Wi-Fi with automatic discovery, or USB; pairing code + per-device tokens
- OLED-friendly: pure black background, dim static labels, slow pixel shifting; the screen stays on at normal brightness

## Repository

- [pc-agent/](pc-agent/): Windows tray agent (.NET). Reads sensors through LibreHardwareMonitor and FPS through PresentMon, serves them over WebSocket, and hosts the web app.
- [android/](android/): Android app (Kotlin + Jetpack Compose).
- [web/](web/): web app (PWA) for iPhone, iPad and other devices, served by the agent at `http://<pc-ip>:28555/`.
- [protocol/PROTOCOL.md](protocol/PROTOCOL.md): the wire protocol.
- [scripts/](scripts/): `usb-connect.ps1` (manual `adb reverse`), `get-presentmon.ps1`, `generate-branding.ps1` (logo and icons).

See each folder's README for build instructions. Third-party components are listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
