# Third-party notices

PocketMon uses or bundles the following open-source components.

| Component | Used in | License |
|---|---|---|
| [LibreHardwareMonitor](https://github.com/LibreHardwareMonitor/LibreHardwareMonitor) (LibreHardwareMonitorLib) | PC agent — sensors | MPL-2.0 |
| [PresentMon](https://github.com/GameTechDev/PresentMon) (console exe, bundled in `tools/`) | PC agent — FPS | MIT, © Intel Corporation — see `tools/PresentMon.LICENSE.txt` |
| [QRCoder](https://github.com/codebude/QRCoder) | PC agent — tray QR code | MIT |
| ASP.NET Core / .NET runtime | PC agent | MIT |
| [NoSleep.js](https://github.com/richtr/NoSleep.js) (bundled in `web/vendor/`) | Web app — keep screen on | MIT, © Rich Tibbett — see `web/vendor/NoSleep.LICENSE` |
| AndroidX, Jetpack Compose | Android app | Apache-2.0 |
| [OkHttp](https://github.com/square/okhttp) | Android app | Apache-2.0 |
| kotlinx.serialization, kotlinx.coroutines | Android app | Apache-2.0 |

[PawnIO](https://github.com/namazso/PawnIO) is not bundled; it is installed separately (e.g. `winget install namazso.PawnIO`) and is used by LibreHardwareMonitor to read CPU sensors.

The "Arial Black" font was used only to render the logo and icon images (`scripts/generate-branding.ps1`); no font files are distributed.
