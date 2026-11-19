# PocketMon protocol v1

Transport: WebSocket, `ws://<host>:28555/ws`, UTF-8 JSON text frames. All field names camelCase.
Any numeric field may be `null` when the sensor is unavailable.

- Wi-Fi: phone connects to the PC LAN IP (found via UDP discovery or entered manually).
- USB: `adb reverse tcp:28555 tcp:28555`, phone connects to `ws://127.0.0.1:28555/ws`.

## Discovery (UDP)
PC broadcasts every 2 s to `255.255.255.255:28556`:
```json
{"app":"pcstats","v":1,"name":"MY-PC","port":28555,"id":"<stable-guid>"}
```

## Handshake
Client must send first message within 5 s, otherwise the server closes the socket.

Client → server, already paired:
```json
{"type":"hello","v":1,"token":"<long-token>","clientName":"Pixel 8"}
```
Client → server, pairing with the 6-digit code shown in the PC tray:
```json
{"type":"pair","v":1,"code":"123456","clientName":"Pixel 8"}
```
Server → client on success (`token` included only in response to `pair`; client must persist it):
```json
{"type":"welcome","v":1,"pcName":"MY-PC","pcId":"<guid>","token":"<long-token>",
 "cpuName":"AMD Ryzen 7 7800X3D","gpuName":"NVIDIA GeForce RTX 4080","intervalMs":1000}
```
Server → client on failure, then close:
```json
{"type":"error","code":"unauthorized|bad_code|bad_request","message":"..."}
```

## Snapshots
Server → client every `intervalMs`:
```json
{
  "type":"snapshot",
  "ts":1731970000000,
  "cpu":{"usage":23.5,"temp":61.0,"tempLimit":100,"clockMhz":4850,"powerW":45.2,"voltage":1.236,"fanRpm":1640,
         "coreLoads":[12.5,80.1,3.0,44.0]},
  "gpu":{"usage":97.0,"temp":70.0,"hotspotTemp":82.0,"clockMhz":2610,"powerW":280.4,"voltage":1.075,"fanRpm":2210,
         "vramUsedMb":9120,"vramTotalMb":16376},
  "ram":{"usedMb":18234,"totalMb":32768},
  "net":{"upBps":12000,"downBps":3400000,"adapter":"Ethernet","sentBytes":1932735283,"receivedBytes":19541180006},
  "fans":[{"name":"CPU Fan","rpm":1200},{"name":"GPU Fan 1","rpm":1650}],
  "disks":[{"name":"C:","model":"Samsung SSD 990 PRO 2TB","freeMb":736256,"totalMb":1907729,"temp":44.0}],
  "fps":{"app":"cyberpunk2077.exe","value":143.7}
}
```
`fps` is `null` when no game/3D app is presenting in the foreground or PresentMon is unavailable.
`usage` values are 0–100 percent; temps in °C; `*Bps` in bytes per second.
`tempLimit` is the CPU throttle temperature (TjMax) when reported; `coreLoads` has one entry per physical core.
`sentBytes`/`receivedBytes` are Windows interface counters (since the adapters came up). `disks` lists fixed drives
and is refreshed every ~5 s. Clients must ignore unknown fields.

## Client → server (optional, after welcome)
```json
{"type":"setInterval","intervalMs":500}
```
Allowed range 200–5000 ms; applies to that client only.
