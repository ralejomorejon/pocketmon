# PocketMon web app (PWA)

The phone dashboard as a web app, served by the PC agent at `http://<pc-ip>:28555/`. It works on iPhone, iPad,
Android or any browser, with no app store, Mac or developer account. It has the same two designs as the native
Android app (Modern and Classic), uses the same pairing and protocol ([../protocol/PROTOCOL.md](../protocol/PROTOCOL.md)),
and has the same OLED pixel shifting.

## Use it

1. On the PC, open the tray menu → **Open on phone (QR code)…** and scan it with the phone camera. The QR code
   contains the PC address and the current pairing code, so pairing is one tap.
2. Add it to the home screen so it opens fullscreen like an app:
   - **iPhone/iPad (Safari):** Share → *Add to Home Screen*.
   - **Android (Chrome):** menu → *Install app* / *Add to Home screen*.
3. Tap the dashboard once. That enables keep-screen-on (browsers only allow it after a tap) and shows the
   Settings / Fullscreen buttons.

### USB

- **Android:** with USB debugging on, the agent runs `adb reverse` automatically. Open `http://localhost:28555`.
  `localhost` counts as a secure context, so the native Wake Lock and offline shell work.
- **iPhone:** connect the cable and turn on *Personal Hotspot*. The PC gets a `172.20.10.x` address; pick it in
  the QR dialog. If Windows marks that network as *Public*, the firewall rule (Private/Domain only) blocks it;
  switch the network to Private in Windows settings.

## Notes and limitations

- **Keep screen on:** uses the Screen Wake Lock API when available (HTTPS or localhost). Over plain HTTP on the
  LAN it falls back to [NoSleep.js](vendor/NoSleep.LICENSE) (MIT), which plays a tiny silent looping video.
- **No auto-discovery:** browsers can't receive the agent's UDP broadcasts, so you open the PC's address
  (the QR code does this).
- **Background:** phones suspend web pages when locked or in the background. The app reconnects when it's
  visible again.
- **Settings and the pairing token** are stored in the browser's `localStorage` for that PC's address.

## Develop

There is no build step: plain ES modules, HTML, CSS and SVG. The agent copies this folder to
`wwwroot` next to `PocketMon.exe` when it builds (`test/` and this README are excluded). To try changes
against a running agent, copy the files into its `wwwroot` folder and reload.

```
node --test test/format.test.mjs   # formatting / session helpers
```

| File | Purpose |
|---|---|
| `index.html`, `style.css` | Shell, pairing and settings screens, layout |
| `js/app.js` | Connection, pairing, screens, scale-to-fit, pixel shift, keep-awake |
| `js/dashboards.js` | Modern and Classic dashboard markup |
| `js/gauges.js` | SVG gauges, rings, core bars, sparklines |
| `js/format.js` | Value formatting (mirrors the Android `Format`) |
| `sw.js`, `manifest.webmanifest`, icons | Installability (home screen, fullscreen) |
