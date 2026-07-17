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
| `/debug/hud` | `0\|1\|2` | In-headset HUD, **independent of reporting**: 0 off · 1 message feed · 2 margin line-graph (recent scheduled-cue margins, unsynced samples discarded) |
| `/debug/wifilock` | `0\|1` | Android Wi-Fi low-latency lock on the device. 1 pins the radio at full power (kills periodic power-save transit spikes); 0 releases. Defaults ON at app start (`WifiLockOnStart`). A/B it against the delay graph. Glasses/Unity feature branch: `wifi-lock`. |
| `/debug/snap` | `1` | Capture the scene camera's view (mono, 1280×720 JPEG, includes the world HUD if on) and HTTP-POST it to the cue server: `POST :8765/debug/snapshot?id=<device>`. Server keeps the latest per device (`GET` same URL) and announces `[id, bytes]` on the feed; the panel's 📸 button + SNAPSHOTS strip drive it. One-frame hitch on the device — tech tool, not mid-show. Both servers: server.py and the Kotlin tablet server (CueHttpServer). |
| `/audio/test` | `1` (+ `playAt` appended in sync mode) | Generated triple beep, outside the cue system |
| `/audio/mute` | `0\|1` | Master mute via `AudioListener.volume` |
| `/audio/reload` | `1` (+ `playAt` in sync mode, deduped) | Re-fetch the cue CSV live; loads new/version-bumped stems without touching playback ("⟳ CSV → ALL" button) |

### Reports — devices → server :9002 (only while enabled)

| Message | Args | Purpose |
|---|---|---|
| `/debug/hello` | `id, kind` | Announce on enable (`headset` / `editor` / `performer`) |
| `/debug/rx` | `id, cueId, recvMaster:d, playAt:d` | Cue received; controller shows margin `(playAt−recvMaster)`; `playAt=0` = immediate path |
| `/debug/hb` | `id, masterTime:d, lastCue, stems:i, offsetMs:d[, fps:f, batt:f, charging:i]` | Heartbeat: liveness + clock health + what's playing (`offsetMs=−1` when unsynced). Trailing vitals (additive, new headset builds only): smoothed render fps, battery % (−1 unsupported), charging flag. Rate: 1 Hz active, 5 s safety mode |
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
   no scene changes); `DebugHud.cs` draws the in-glasses overlay, **controlled
   separately from reporting via `/debug/hud`** — mode 1 is the message feed
   (device id, master IP, sync state, heartbeat, recent cue/log lines), mode 2
   is a **line graph of recent scheduled-cue margins** ("how much headroom
   does THIS headset have," visible in the glasses). A stagehand can have the
   HUD on with zero debug network traffic, or the operator can report without
   cluttering the glasses. Immediate-mode GUI: no Canvas/TMP dependency.
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

### Unity repo (`annehiatt/joan-of-the-city-xreal`) — **merged to `main`** (2026-07-15, `8ef3ad2..b3d13ff`)

| Commits | Contents |
|---|---|
| `9accf8d` | Sync Phases 0+1: `MasterClock.cs` (new), `playAt` handling + dedupe in `OscCueReceiver.cs`, `TriggerCueScheduled` in `AudioSceneController.cs`, `PlayScheduled` in `AudioStemPlayer.cs` |
| `776d754`, `3b021ed` | D2+D3 + test utils: `DebugReporter.cs` (new), `DebugHud.cs` (new), `TestTone.cs` (new), debug binds + mute/test handlers in `OscCueReceiver.cs`, `OffsetMs` accessor in `MasterClock.cs` |
| `b3d13ff` | `/audio/reload`, always-on 5 s safety heartbeat (inspector kill-switch), duplicate-AudioListener fix in MainScene |

(The `audio-sync-patch` / `debug-observability` branches point at the same
commits and can be deleted whenever.)

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

- **DEVICE FILTER + TARGETING** — the per-device checkbox filter now sits
  above both views and filters the log in `log` view too (control lines
  always show). Next to the toggles, a **→ target dropdown** ("audience
  (all)" or "only <device>") scopes every control — reporting, heartbeat,
  HUD, test tone, mute — to one device's IP, so you can isolate a single
  headset: turn its debug on, watch its lines, beep only it. SENT lines
  label the target: `SENT /debug/hud 2 (graph) → USER (192.168.1.41)`.
- **HUD select** — `HUD: off | feed | graph` dropdown drives `/debug/hud`.
- **DELAY GRAPH** — a second view in the log area (VIEW: `log` | `graph`).
  Line chart of each device's **heartbeat transit delay** over the last 90 s:
  device's master-clock send time vs the server's master-clock receive time,
  so the Y axis is real network delay in ms (plus residual clock error —
  on loopback it can read slightly negative; clamped to the 0 line). One
  colored series per device, fed at the heartbeat rate (1 Hz active / 5 s
  safety). **Device checkboxes** toggle series on/off, and the **log below
  filters to the selected devices** (control lines always show). Latest
  value is labeled at the line's tip.

- **CUE ACKS** — per-cue delivery rollup: `✔ cue A_SQ201 → 3/3 received
  (401 / 399 / 404ms)`. Scheduled cues group by their exact `playAt`
  (3× sends collapse to one row); immediate cues bucket by cueId in a 2 s
  window. Amber `…` until every roster device has reported.
- **Roster clock health** — instead of the raw epoch-relative `offsetMs`
  (meaningless to read), the roster shows `clock ✓ Δ0.2ms` (offset drift
  between heartbeats — small = stable) or `clock ⚠ unsynced`. Dot thresholds
  account for the 5 s safety rate: 🟢 <7 s, 🟡 <15 s, 🔴 beyond.

## Known notes / small follow-ups

- ~~Tablet safety-heartbeat parity~~ — **done + emulator-verified**
  (2026-07-15 night): `PerformerService` runs one heartbeat thread for both
  modes, 1 s active / 5 s safety once a master is known;
  `SAFETY_HEARTBEAT = false` in the file restores strictly-zero traffic.
  Verified end-to-end on the `joan_tablet` emulator: cue in via UDP redir →
  master learned → clock synced against the host → hb every 5.0 s at the
  host's `/debug/events` with debug off.
- ~~APK build check~~ — **done**: `assembleDebug` passes; notification
  **Stop server** action verified on the emulator (tap → server torn down,
  service destroyed, notification dismissed). Note: shell/other apps cannot
  fire the stop intent (service not exported) — only the notification can.
- ~~offsetMs roster display~~ · ~~"2 audio listeners" spam~~ ·
  ~~always-on heartbeat~~ · ~~/audio/reload in Unity~~ · ~~per-cue ack
  summary~~ — **all done 2026-07-15 evening.**
- Remaining: **multi-device verification on real headsets** (scheduled).
  Sync **Phase 2 (position servo) is rejected as designed** for this
  production (short stems re-anchor at every cue start; pitch-based rate
  nudges are audible on sustained sung material) — see the decision note in
  `AUDIO-SYNC-HANDOFF.md`. An optional recovery-only "Phase 2-lite" (silent
  device restarts its stem at the aged position; playing audio never
  touched) awaits the real-headset miss-rate data from the CUE ACKS panel.
