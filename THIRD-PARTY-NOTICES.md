# Third-party notices

PocketMon uses or bundles the following open-source components.

| Component | Used in | License |
|---|---|---|
| [LibreHardwareMonitor](https://github.com/LibreHardwareMonitor/LibreHardwareMonitor) (LibreHardwareMonitorLib) | PC agent — sensors | MPL-2.0 |
| [PresentMon](https://github.com/GameTechDev/PresentMon) (console exe, bundled in `tools/`) | PC agent — FPS | MIT, © Intel Corporation — see `tools/PresentMon.LICENSE.txt` |
| [QRCoder](https://github.com/codebude/QRCoder) | PC agent — tray QR code | MIT |
| ASP.NET Core / .NET runtime | PC agent | MIT |
| [NoSleep.js](https://github.com/richtr/NoSleep.js) (bundled in `web/vendor/`) | Web app — keep screen on | MIT, © Rich Tibbett — see `web/vendor/NoSleep.LICENSE` |
| [Orbitron](https://github.com/theleagueof/orbitron) font (`web/fonts/`, Android `res/font/`) | FPS counter | SIL Open Font License 1.1 — see `web/fonts/Orbitron-OFL.txt` |
| AndroidX, Jetpack Compose | Android app | Apache-2.0 |
| [OkHttp](https://github.com/square/okhttp) | Android app | Apache-2.0 |
| kotlinx.serialization, kotlinx.coroutines | Android app | Apache-2.0 |

[PawnIO](https://github.com/namazso/PawnIO) is not bundled; it is installed separately (e.g. `winget install namazso.PawnIO`) and is used by LibreHardwareMonitor to read CPU sensors.

The "Arial Black" font was used only to render the logo and icon images (`scripts/generate-branding.ps1`); no font files are distributed.

## Game logos

- `pc-agent/PcStats.Agent/logos/zelda-oot.png` is adapted from
  [The Legend of Zelda Ocarina of Time 3D.png](https://commons.wikimedia.org/wiki/File:The_Legend_of_Zelda_Ocarina_of_Time_3D.png)
  (Wikimedia Commons, public domain as a text logo). The "3D" was removed and the black text recoloured for dark backgrounds.
  *The Legend of Zelda* is a trademark of Nintendo.
- The Ocarina of Time (Switch 2 remake) logo, used when Hookshot (`hookshot.exe`) is running, is not distributed; the agent
  downloads Nintendo's official logo from zeldaxtreme.com's press art on the user's PC for display only.
- Other game logos are not distributed with PocketMon. The agent downloads them from the Steam CDN on the user's PC
  for display only. All game names and logos are trademarks of their respective owners; PocketMon is not affiliated with them.
