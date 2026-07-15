# Debug Observability — Protocol & Status

Toggleable debug layer: devices report receive-timestamps, heartbeats, and
logs back to the cue server; the controller visualizes them in a logger panel.
**Default OFF — zero traffic and zero overhead unless the operator enables it.**

## Status

| Phase | Scope | Status |
|---|---|---|
| D0 — plumbing | `:9002` UDP debug listener + `GET /debug/events` feed in the cue server (app `CueHttpServer`/`CueServerService` + both `server.py` copies) | ✅ done, validated on emulator |
| D1 — logger UI | 🐞 Debug panel in the controller (all 4 copies): reporting + heartbeat toggles, device roster, timestamped log | ✅ done |
| D1.5 — tablet reporter | Performer mode reports `hello`/`rx`/`hb` | ✅ done, validated |
| **D2 — Unity reporter** | `DebugReporter.cs`: same reports from headsets | ⬜ **build in the Unity MCP session** |
| **D3 — in-headset HUD** | TMP overlay showing recent messages + offset, gated by the same enable | ⬜ **build in the Unity MCP session** |

## Ports

| Port | What |
|---|---|
| `:7000` UDP (existing) | control messages TO devices (`/debug/enable`, `/debug/heartbeat`) |
| `:9002` UDP on the **master** | debug reports FROM devices |
| `GET /debug/events?since=<seq>` on `:8765` | the controller's poll feed (JSON) |

All timestamps are **master-clock seconds** (`MasterClock.Now` on devices;
`masterNow()` on the server) so latencies are directly comparable.

## Messages

**Control — controller → devices on :7000** (fan out via the normal audience send):

| Address | Args | Meaning |
|---|---|---|
| `/debug/enable` | `on:int` (1/0) | verbose reporting on/off |
| `/debug/heartbeat` | `on:int` (1/0) | 1 Hz heartbeat on/off (independent toggle) |

**Reports — devices → master :9002** (only when enabled):

| Address | Args | When |
|---|---|---|
| `/debug/hello` | `id:string, kind:string` (`headset` / `performer`) | on enable |
| `/debug/rx` | `id:string, cueId:string, recvMaster:double, playAt:double` (`0.0` if immediate-mode) | every `/audio/cue` received |
| `/debug/hb` | `id:string, masterTime:double, lastCue:string, stems:int, offsetMs:double` | 1 Hz while heartbeat on |
| `/debug/log` | `id:string, masterTime:double, level:string, msg:string` | errors/warnings/info worth surfacing |

`id` convention: `<device-model>-<last-IP-octet>` (e.g. `sdk_gphone64_x86_64-15`).

**Feed — controller polls `GET /debug/events?since=<seq>`:**
```json
{ "seq": 17, "now": 25116.88,
  "events": [ { "seq": 7, "t": 25112.411, "addr": "/debug/rx",
                "args": ["Fire8-23", "A_SQ201", 25112.390, 25113.383],
                "from": "10.0.1.41" }, ... ] }
```
The panel derives **margin = (playAt − recvMaster)** for rx events (how much
lead remained on arrival; negative = late) and a roster with liveness dots
(green <3 s since last heartbeat), clock offset, last cue, stem count.

## D2 — what the Unity side must implement (for the MCP session)

New `Assets/JoanAudio/Scripts/DebugReporter.cs` (+ two one-line hooks):

1. Bind `/debug/enable` and `/debug/heartbeat` on the extOSC receiver
   (mirror how `OscCueReceiver` binds `/clock/master`).
2. Reports go over a plain `UdpClient` to **`MasterClock.MasterIp` : 9002**
   (background-thread-safe; timestamps from `MasterClock.Now`, guard on
   `MasterClock.IsSynced` — send `-1.0` when unsynced, like the tablet does).
3. `/debug/hello [id, "headset"]` on enable.
4. Hook `OscCueReceiver.OnCue`: after parsing, if enabled →
   `/debug/rx [id, cueId, MasterClock.Now, playAt-or-0]`.
5. Heartbeat coroutine (1 s): `/debug/hb [id, MasterClock.Now,
   controller.LastCueId ?? "", controller.ActivePlayers.Count,
   clock offset ms]` — expose the offset from `MasterClock` if not already.
6. `id`: `SystemInfo.deviceModel` (spaces→`_`) + `-` + last IP octet.
7. **Everything gated on the enable flag; default off.** No behavior change
   when disabled — same rollback philosophy as the sync patch.

D3 (optional after D2): a small TMP overlay toggled by the same
`/debug/enable` showing the last ~8 log lines + offset + last cue, so a tech
wearing the headset sees what the operator sees.

Reference implementation: `PerformerService.kt` in this repo (search
`sendDebug` / `setHeartbeat`) — the Unity port is a direct translation.

## Validated behavior (emulator, single-device loopback)

Heartbeats at 1 Hz with offset visibly converging (−13.2 → −4.4 ms over 10 s),
stems count updating on cue fire, and an rx report showing 993 ms of a
1000 ms lead remaining on arrival.
