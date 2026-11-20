# PocketMon Agent (Windows)

Tray app that reads hardware sensors (LibreHardwareMonitor) and FPS (Intel PresentMon) and streams them to the
Android app over WebSocket, implementing [protocol/PROTOCOL.md](../protocol/PROTOCOL.md).

- WebSocket `ws://<pc>:28555/ws`, plus `GET /health` → `ok`
- Web app (PWA) at `http://<pc>:28555/`, served from `wwwroot` next to the exe (copied from [../web](../web) on build)
- UDP discovery beacon every 2 s on port 28556 (each interface's broadcast address and 255.255.255.255)
- USB: the agent runs `adb reverse tcp:28555 tcp:28555` for each newly attached device every 3 s
  (or run [`scripts/usb-connect.ps1`](../scripts/usb-connect.ps1) manually)

## Requirements

- Windows 10/11 x64, .NET SDK 10 (`global.json` pins 10.0.301, rolling forward to later feature bands)
- Administrator rights for full functionality (CPU temps/power, PresentMon ETW tracing, firewall rules).
  The exe's manifest requests elevation.
- The **PawnIO** driver for CPU temperature, power and voltage. Since 0.9.6, LibreHardwareMonitor reads CPU MSRs
  through it instead of WinRing0. Install it with `winget install namazso.PawnIO` (or **Install PawnIO driver…**
  in the tray menu), then restart the agent. Without it the agent falls back to Windows performance counters:
  clock from `% Processor Performance` (like Task Manager), package power from the RAPL `Energy Meter`, and
  temperature from the ACPI thermal zone (less precise). Voltage is unavailable.

## Build, test, run

```powershell
# Optional but needed for FPS: download the PresentMon console exe into PcStats.Agent\tools\
..\scripts\get-presentmon.ps1

dotnet build PcStats.sln
dotnet test PcStats.sln

# Tray mode (UAC prompt)
.\PcStats.Agent\bin\Debug\net10.0-windows\PocketMon.exe

# Headless, logs to the console (prints the pairing code). Running the dll via `dotnet` skips the
# UAC manifest, which is handy for testing without elevation (temps/power/FPS will then be null).
dotnet .\PcStats.Agent\bin\Debug\net10.0-windows\PocketMon.dll --console
```

Release build: `dotnet publish PcStats.Agent -c Release -r win-x64 --self-contained false`.

### Arguments

| Arg | Effect |
| --- | --- |
| `--console` | Run headless (no tray) and log to the console. Ctrl+C exits. |
| `--no-elevation-check` | Don't try to relaunch elevated when started without admin rights. |

## Pairing

The tray shows a 6-digit code (also logged as `Pairing code: NNNNNN`). It rotates every 5 minutes and after every
successful pairing. Pair attempts are limited to 5 per IP per minute. A successful `pair` returns a 32-byte random
token (base64url) that the phone stores; the agent stores only its SHA-256 hash. **Revoke all phones** in the tray
clears all tokens and disconnects connected phones.

## Tray menu

Status lines (connected phones, pairing code, LAN IPs, elevation), **Show pairing code**, **Open on phone (QR code)…**
(QR code for the web app with the pairing code filled in; pick the address if the PC has several), **Revoke all phones**,
**Start with Windows** (creates/deletes the `PcStatsAgent` Task Scheduler logon task with highest privileges, so it
starts elevated without a UAC prompt), **Open config folder**, **Exit**.

## Files

- `%AppData%\PcStatsAgent\config.json`: `pcId`, `authorizedTokenHashes`, `intervalMs` (default per-client interval)
- `%AppData%\PcStatsAgent\logs\agent-yyyyMMdd.log`: logs (kept 14 days)
- `%AppData%\PcStatsAgent\logos\<id>.png`: downloaded game logos. Drop your own PNG here to override or add one.
- `%AppData%\PcStatsAgent\games.json` (optional): your own game entries, same format as the bundled `games.json`.

## Game logos

When PresentMon reports FPS for the foreground app, the agent identifies the game and adds its name and logo to the
snapshot (the phone shows them next to the FPS counter). It identifies games in this order:

1. **`games.json`**: exe name, e.g. `dota2.exe` → Dota 2, `hookshot.exe` → Ocarina of Time (Switch 2 remake logo). Ships
   with ~20 popular games. An entry can name a `logoUrl` (https PNG/WebP/JPEG) for games not on Steam.
2. **Any Steam game**: the exe's `...\steamapps\common\<folder>` is matched to that library's `appmanifest_*.acf`.
3. **Emulators**: for the emulators listed in `games.json` (Project64, RetroArch, Dolphin…), the window title is
   matched against each game's `title` regex. For example, Ocarina of Time in Project64 or Ship of Harkinian (`soh.exe`).

Logos for Steam games are downloaded from the Steam CDN (`https://cdn.cloudflare.steamstatic.com/steam/apps/<appid>/logo.png`)
into the cache: the catalog's games in the background at startup, any other Steam game the first time it's detected.
They are not shipped with PocketMon because they're the games' own artwork. The bundled `logos/zelda-oot.png` is derived from a
public-domain Wikimedia Commons file (see THIRD-PARTY-NOTICES.md). Logos are served at `GET /logos/<id>.png`.

## Notes

- Sensors refresh every ~500 ms on a background thread. Each client gets snapshots at its own `intervalMs`
  (default 1000, `setInterval` clamps to 200–5000). Missing sensors are sent as `null`.
- FPS is for the foreground window's process: frames in the last second, `null` if it presented nothing in the
  last 2 s. PresentMon is started with
  `--output_stdout --stop_existing_session --session_name PcStatsAgent --v2_metrics --no_console_stats`, restarted
  with backoff if it exits, and killed (via a kill-on-close job object) when the agent exits. Without elevation
  PresentMon fails with "access denied" unless the user is in the *Performance Log Users* group.
- Firewall: when elevated, the agent ensures inbound `PocketMon Agent` rules for TCP 28555 and UDP 28556
  (profiles private and domain). Phones on a *public* network profile are still blocked.
- The `tools\**` folder is copied to the build output; the agent looks for `PresentMon*.exe` next to itself or in `tools\`.

## Layout

```
PcStats.Agent/
  Program.cs, AgentHost.cs     entry point, args, component wiring
  Protocol/Messages.cs         wire DTOs, JSON options (camelCase, nulls kept)
  Sensors/                     LHM reader, pure SnapshotMapper, network rates, sampler
  Fps/                         FpsTracker (CSV parsing + FPS math), PresentMonRunner
  Server/StatsServer.cs        Kestrel, /health, /ws handshake and per-client send loops
  Pairing/PairingService.cs    pairing code, token hashes, rate limiting
  Net/                         discovery beacon, LAN addresses, firewall rules
  Usb/UsbHelper.cs             adb discovery and adb reverse loop
  Tray/                        NotifyIcon UI and Start-with-Windows task
  Infrastructure/              file logger, elevation, P/Invoke, process helper
PcStats.Agent.Tests/           xUnit tests (mapping fallbacks, FPS, pairing, protocol JSON, helpers)
```
