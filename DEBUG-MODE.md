# Debug Mode — Observability for Joan of the City

**Status: implemented and verified in-editor (2026-07-15).** This documents the
debug/observability layer built on top of the audio sync system
(`AUDIO-SYNC-HANDOFF.md`): devices report back to the operator, live.

Everything is **opt-in and OFF by default** — until an operator flips the
toggle there are no log hooks and no verbose traffic. **One deliberate
exception:** a low-rate **safety heartbeat** (one small packet per 5 s per
device, once a master is known) keeps the roster answering "is headset 3
alive?" even with every toggle off. Untick **Always On Safety Heartbeat** on
the `OscCueReceiver` inspector for strictly-zero-traffic behavior.

## What it solves

Data used to flow one way (controller → devices) and nothing reported back.
Debug mode adds the return path:

```
Devices ──/debug/*──▶ cue server :9002 (UDP listener)
                          │ ring-buffers + tags with seq
Operator page ◀── GET /debug/events?since=<seq> ── server (1s poll)
```

All timestamps are **master-clock seconds** (the sync system's MasterClock),
so the controller computes true send→receive margins per device per cue.

## Protocol

### Control — controller → devices (existing :7000 cue port)

| Message | Args | Meaning |
|---|---|---|
| `/debug/enable` | `0\|1` | Verbose reporting + in-headset HUD |
| `/debug/heartbeat` | `0\|1` | 1 Hz roster beacon (independent toggle — roster without log spam) |
| `/audio/test` | `1` (+ `playAt` appended in sync mode) | Generated triple beep, outside the cue system |
| `/audio/mute` | `0\|1` | Master mute via `AudioListener.volume` |
| `/audio/reload` | `1` (+ `playAt` in sync mode, deduped) | Re-fetch the cue CSV live; loads new/version-bumped stems without touching playback ("⟳ CSV → ALL" button) |

### Reports — devices → server :9002 (only while enabled)

| Message | Args | Purpose |
|---|---|---|
| `/debug/hello` | `id, kind` | Announce on enable (`headset` / `editor` / `performer`) |
| `/debug/rx` | `id, cueId, recvMaster:d, playAt:d` | Cue received; controller shows margin `(playAt−recvMaster)`; `playAt=0` = immediate path |
| `/debug/hb` | `id, masterTime:d, lastCue, stems:i, offsetMs:d` | Heartbeat: liveness + clock health + what's playing (`offsetMs=−1` when unsynced). Rate: 1 Hz active, 5 s safety mode |
| `/debug/log` | `id, masterTime:d, level, msg` | Warnings/errors + `[JoanAudio]` logs, rate-limited 10/s/device |

## The three surfaces

1. **Operator page — 🐞 panel** (`pc-server/index.html`, mirrored in the tablet
   app assets): reporting + heartbeat toggles, device roster (🟢<3s / 🟡<10s /
   🔴 liveness dot, clock offset, last cue, last-seen age), color-coded message
   log with master-clock stamps, per-cue rx lines with margins, and the
   **SYNC TEST** row (test tone + mute buttons).

   **Timestamps — sent *and* reply, one scale.** Every line carries an `mt`
   (master-clock seconds). Control messages the operator fires log a
   `SENT …` line stamped with the server's send time (the server is the clock
   master, so it returns `sentAt` — and `playAt` for scheduled sends — in the
   `/send` response); each device's reply logs its own `RX …`/`log` line at the
   time the server received it. So a single cue reads as one `SENT` line
   followed by each device's `RX` a few ms later, directly comparable:

   ```
   mt 462403.218  SENT /audio/test → audience  playAt 462403.618 (+400ms)
   mt 462403.218  RX USER  cue TEST-TONE  margin 401ms
   ```
2. **Unity headsets**: `DebugReporter.cs` sends the reports (self-bootstrapping,
   no scene changes); `DebugHud.cs` draws the in-glasses overlay — device id,
   master IP, sync state + offset, heartbeat status, recent cue/log feed.
   Immediate-mode GUI: no Canvas/TMP dependency. Exists only while enabled.
3. **Performer tablets**: `PerformerService.kt` sends the same `/debug/*`
   reports, so headsets and tablets share one roster.

## Sync test utilities

- **🔊 Test tone** (`/audio/test`): procedurally generated triple beep
  (3 × 60 ms @ 1760 Hz, 150 ms apart — `TestTone.cs`, no audio asset). Plays
  *outside* the cue system: no cue CSV, no stems, no transport state. In sync
  mode it is scheduled on the master clock exactly like a real cue and deduped
  against the 3× sends — firing it at every device at once is an audible sync
  check: **one clean beep-beep-beep = in sync; a flam = trouble.** Appears in
  the logger as cue `TEST-TONE` with its margin.
- **🔇 Mute** (`/audio/mute`): sets `AudioListener.volume` on every targeted
  device. Playback continues silently underneath, so unmuting drops straight
  back into sync (pausing would drift). Idempotent — safe under 3× sends.

## Operator runbook (tech rehearsal)

1. Start the cue server; open the controller page; open the 🐞 panel.
2. Tick **heartbeat** → roster fills in; every targeted device should go 🟢.
3. Tick **reporting** → devices say hello; HUDs appear in the glasses.
4. Press **test tone** with all devices targeted → listen for one beep.
5. Fire real cues → watch per-device rx margins in the log.
6. **Mute** to talk; unmute drops back in sync.
7. Untick both before the show. (Off = zero debug traffic.)

## Where everything lives (handoff)

### Unity repo (`annehiatt/joan-of-the-city-xreal`) — branches, merge in order

| Branch | Commits | Contents |
|---|---|---|
| `audio-sync-patch` | `9accf8d` | Sync Phases 0+1: `MasterClock.cs` (new), `playAt` handling + dedupe in `OscCueReceiver.cs`, `TriggerCueScheduled` in `AudioSceneController.cs`, `PlayScheduled` in `AudioStemPlayer.cs` |
| `debug-observability` | `776d754`, `3b021ed` | D2+D3 + test utils: `DebugReporter.cs` (new), `DebugHud.cs` (new), `TestTone.cs` (new), debug binds + mute/test handlers in `OscCueReceiver.cs`, `OffsetMs` accessor in `MasterClock.cs` |

### Cue-controller repo (`psychojelly/joan-cue-controller-android`) — on `main`

| Commit | Contents |
|---|---|
| `95b24ac` | D0+D1: `:9002` UDP debug listener + `/debug/events` endpoint (`server.py`), Kotlin equivalents (`CueHttpServer.kt`, `CueServerService.kt`, `MasterClock.kt`, `PerformerService.kt`), 🐞 logger panel + roster in both controller HTML copies |
| `1078470` | SYNC TEST row (test tone + mute buttons) in both HTML copies; unity-patch mirror |
| `unity-patch/` | Mirror of all Unity-side files + `TEST-REPORT-2026-07-15.md` (sync verification) |

### Verification summary (in-editor, MainScene, against the live server)

- Scheduled cues land 388–394 ms into a 400 ms lead (≈6–12 ms transit+jitter)
- `/debug/hello`, `/debug/hb` (1 Hz, with last cue + stem count), `/debug/rx`
  (margin matched the Unity console to the ms), `/debug/log` all arrive on
  `/debug/events`
- HUD renders over the scene (screenshot-verified)
- 3× dedupe: one trigger / one beep per send
- Mute round-trip: `AudioListener.volume` 0 → 1
- **Debug off: a fired cue produced zero debug events** (the rollback guarantee)

## Panel views (D1, extended 2026-07-15 evening)

- **CUE ACKS** — per-cue delivery rollup: `✔ cue A_SQ201 → 3/3 received
  (401 / 399 / 404ms)`. Scheduled cues group by their exact `playAt`
  (3× sends collapse to one row); immediate cues bucket by cueId in a 2 s
  window. Amber `…` until every roster device has reported.
- **Roster clock health** — instead of the raw epoch-relative `offsetMs`
  (meaningless to read), the roster shows `clock ✓ Δ0.2ms` (offset drift
  between heartbeats — small = stable) or `clock ⚠ unsynced`. Dot thresholds
  account for the 5 s safety rate: 🟢 <7 s, 🟡 <15 s, 🔴 beyond.

## Known notes / small follow-ups

- Tablet parity for the safety heartbeat: `PerformerService.kt` still only
  heartbeats while debug is enabled — mirror the Unity 5 s safety-net there.
- The Kotlin/Gradle changes (notification Stop action, signing scaffold) were
  made without a local Gradle toolchain — one `assembleDebug` in Android
  Studio to confirm before shipping a build.
- ~~offsetMs roster display~~ · ~~"2 audio listeners" spam~~ ·
  ~~always-on heartbeat~~ · ~~/audio/reload in Unity~~ · ~~per-cue ack
  summary~~ — **all done 2026-07-15 evening.**
