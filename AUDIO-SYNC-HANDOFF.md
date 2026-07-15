# Audio Sync — Design & Implementation Handoff

**For:** David (Lead Developer) · **From:** Dom (Technical PM)
**Goal:** all devices (headsets + performer tablets) start and stay in audio
sync, driven from the operator cue tablet.
**Written to be implemented with Claude Code** — each phase ends with a
suggested prompt. Phases are independently shippable, in order.

> **Status update:** Phases 0 + 1 are now **implemented in the tablet/web
> stack** as a test version — `server.py` + controller (sync-mode toggle in
> the OSC settings) and this app (operator sends scheduled cues; performer
> mode syncs its clock, dedupes the 3× sends, schedules starts, seeks in when
> late). **The Unity receiver is the remaining piece** — the contracts below
> are now demonstrated, not just proposed. Toggle OFF = old behavior
> everywhere.

---

## 1. The problem, simply

Today a cue means *"play the moment the packet happens to arrive."* So every
source of arrival wobble becomes an audio wobble:

- Wi-Fi delivery varies per device (jitter, retries)
- Each device's audio pipeline takes a different time to start
- If a stem isn't preloaded, the device loads it **at cue time** (the big one)
- Once started, device clocks drift apart over long stems

**Measured side by side: devices are 25 ms to 1 s apart.** The ~25 ms floor is
jitter + pipeline variance. The 1 s outliers are loading/stalls at cue time.

Why it matters: each audience member only hears their own earbuds, but the
**live singer** performs in real air against what everyone hears — so the
accompaniment must match her monitor within ~10–20 ms.

## 2. The solution, simply

Three layers, built in order. Each rides the existing OSC path — no new
infrastructure, only new message shapes and receiver logic.

1. **Shared clock** — every device learns the master's time to within a few ms
   (a 30-line NTP-style ping).
2. **Scheduled start** — cues say *"play at master time T"* (T ≈ now + 400 ms).
   Every device fires at the same instant regardless of when its packet
   arrived. Fixes the onset. 25 ms → ~5 ms.
3. **Position servo** — the master broadcasts *"stem S should be at position P
   as of master time T"* 1–2×/sec. Devices nudge themselves back into line.
   Fixes drift on long stems AND lets a rebooted/reconnected device join
   mid-stem in sync.

What none of this fixes: a stem still **loading** at cue time. Preload
discipline (wait for "Audio loaded and cached", warm up every cue pre-show)
is the foundation under all of it.

```
master time →  |----------------|-----------●
               cue sent      packets      playAt: ALL devices
                             arrive at    start this exact moment
                             different
                             times (don't care)
```

## 3. Who is the clock master?

**The device that runs the cue server** — either an **operator tablet**
(Joan Cues app in Operator mode) or a **PC** (`server.py`). **Which one the
show uses is TBD** — the protocol and code paths are identical either way,
so nothing here depends on that decision. Master time = that device's
monotonic clock. The master itself needs no sync — everyone else syncs to it.

## 4. Message contracts (the whole protocol)

All OSC over the existing UDP :7000 path, except pings which use :9001 on the
master. Times are **OSC doubles ('d'), seconds** on the master's monotonic
clock (float32 loses ms precision over a long show; extOSC and python-osc both
support doubles).

| Message | Direction | Args | Meaning |
|---|---|---|---|
| `/clock/ping` | device → master :9001 | `seq:int` | "what time is it?" |
| `/clock/pong` | master → device (reply to sender) | `seq:int, masterTime:double` | timestamped reply |
| `/audio/cue` | master → devices :7000 | `cueId:string [, playAt:double]` | **playAt absent = play now (today's behavior — full backwards compat)** |
| `/sync` | master → devices :7000 | `stem:string, position:double, masterTime:double` | servo: where this stem should be |
| `/audio/reload` | master → devices :7000 | `1:int` (ignored) | re-fetch the cue CSV + preload any new stems. Implemented in the tablet app (Performer) and fired by the controller's **⟳ CSV → ALL** button; **Unity should add the same handler** (re-run the CSV load + preload routine). |

**Offset estimation (device side):** note local send time `t0`, receive
`masterTime ts` at local `t1`. One sample: `offset = ts − (t0 + t1)/2`,
quality = round trip `t1 − t0`. Ping every ~5 s, keep a window of ~16 samples,
**use the offset from the lowest-RTT samples (median of best 4)** — moving
devices produce noisy samples and this filter rejects them. Slew the applied
offset (don't jump). `masterNow() = localMonotonic + offset`. The offset stays
valid through Wi-Fi dropouts (clocks don't jump); drift is ppm-level, which
the periodic ping absorbs.

**Idempotency + redundancy:** receivers dedupe `/audio/cue` by
`(cueId, playAt)` — which makes it safe for the sender to transmit every cue
**3× at 50 ms spacing**. That, not multicast, is the loss insurance (Wi-Fi
unicast gets link-layer retries; multicast doesn't — stay unicast).

## 5. Implementation steps

### Phase 0 — Clock sync (prerequisite, no audible change)

**Master (this repo, `CueServerService.kt`; and `osc-cue-server/server.py`):**
- Open UDP :9001; on `/clock/ping [seq]` reply `/clock/pong [seq, monotonicSeconds]`
  to the sender's address. (Kotlin: `System.nanoTime()/1e9`; Python:
  `time.monotonic()`.)

**Unity (`Assets/JoanAudio/` — new `MasterClock.cs`):**
- Static class: pings the master (its IP = wherever cues come from; simplest is
  a serialized field / the OSC sender's source address), maintains the filtered
  offset as above, exposes `MasterClock.Now` and `MasterClock.IsSynced`.
- Use `Time.realtimeSinceStartupAsDouble` as the local monotonic base.

**This app, Performer mode (`PerformerService.kt` + new `MasterClock.kt`):**
same algorithm in Kotlin.

*Test:* log `MasterClock.Now` on two devices side by side while walking around
the room — values should agree within ~5 ms and stay stable.

> **Claude Code prompt:** *"In the Unity project, add
> Assets/JoanAudio/MasterClock.cs: a static NTP-style clock sync client. It
> sends /clock/ping [seq:int] over UDP to a configurable master IP on port
> 9001 every 5 seconds, receives /clock/pong [seq:int, masterTime:double],
> computes offset = masterTime − (t0+t1)/2 per sample using
> Time.realtimeSinceStartupAsDouble, keeps the last 16 samples, applies the
> median offset of the 4 lowest-RTT samples, and slews toward it. Expose
> static double Now and bool IsSynced. Use extOSC or a raw UdpClient —
> whichever is cleaner given extOSC's transmitter API. Also add the :9001
> ping responder to server.py in the osc-cue-server folder and to
> CueServerService.kt in the Android app."*

### Phase 1 — Scheduled start (the audible win)

**Sender (this repo: `assets/web/index.html` `sendOsc()`, `CueHttpServer.kt`;
and `server.py` + `qlab-controller.html` for the PC path):**
- Extend the `/send` contract to accept `values: [...]` (array) in addition to
  the single `value` — needed for multi-argument OSC.
- When firing an audio cue: append `playAt = masterNow + 0.4` (double).
  Master's own `masterNow` is just its monotonic clock. Lead time
  configurable; use 0.5–1.0 s for far/moving route legs.
- Send each cue **3× at 50 ms intervals** (dedupe makes this safe).

**Unity (`OscCueReceiver.cs` → `AudioSceneController.cs` →
`AudioStemPlayer.cs`):**
- `OnCue`: if a second arg (playAt) exists and `MasterClock.IsSynced`:
  dedupe `(cueId, playAt)` (keep a small recent set), then schedule.
- Convert: `delay = playAt − MasterClock.Now`;
  `dspAt = AudioSettings.dspTime + delay`.
- In `EnsurePlayingAndApply`: replace `PlayFromStart()` with
  `AudioSource.PlayScheduled(dspAt)` when a schedule is present (clip is
  already preloaded in the happy path). Volume ramp timing keys off the same
  moment.
- **Late-arrival rule:** if `delay <= 0` (stall or missed window), start
  immediately but seek in: `source.time = −delay`, so the device lands
  mid-phrase in sync instead of permanently behind.
- No playAt arg → exactly today's immediate behavior.

**This app, Performer mode (`StemEngine.kt`):** same logic; scheduling via
`Handler.postAtTime` against the synced clock is adequate for a monitor
(±10 ms); seek-in on late arrival identical.

*Test:* two devices side by side, fire 20 cues, record both (clap-test).
Acceptance: every onset within **±10 ms**, zero outliers over 50 ms. Then
repeat while deliberately stalling one device's main thread (fire a heavy
visual cue first) — scheduled starts should be unaffected.

> **Claude Code prompt:** *"Implement scheduled audio cue start. Contract:
> /audio/cue now optionally carries a second double arg playAt (master-clock
> seconds from MasterClock). In OscCueReceiver.OnCue parse it; in
> AudioSceneController dedupe by (cueId, playAt) with a 30-entry recent set,
> convert playAt to dspTime via AudioSettings.dspTime + (playAt −
> MasterClock.Now), and use AudioSource.PlayScheduled instead of Play for
> stems whose clips are already cached. If playAt is in the past, Play
> immediately and set source.time to the overshoot. Keep full backwards
> compatibility when the arg is absent. On the sender side, extend server.py
> and CueHttpServer.kt /send to accept a values array, and update the
> controller HTML's audio-cue send to append playAt = masterNow + leadSeconds
> (configurable, default 0.4) and to send each cue 3 times 50 ms apart."*

### Phase 2 — Position servo (drift + self-healing)

**Master (this app / server.py):** keep a **show clock** per running stem:
on firing a cue, record `(stem, startedAtMasterTime)`. Broadcast to all target
IPs 1–2×/sec: `/sync [stem, expectedPosition, masterNow]`.

**Unity (`AudioSceneController` — it already has the transport hooks):**
- On `/sync`: `expectedNow = position + (MasterClock.Now − masterTime)`;
  `error = expectedNow − source.time`.
- `|error| < 30 ms` → do nothing (hysteresis).
- `30 ms – 250 ms` → rate-nudge: `source.pitch = 1 ± 0.01` until the error
  closes, then restore 1.0 (inaudible glide).
- `> 250 ms` (or stem not playing but should be) → hard-correct: seek — or
  **start the stem at that position** (this is the late-joiner/reboot
  recovery).

**This app, Performer mode:** same via `seekTo` (skip rate-nudge; MediaPlayer
rate changes are audible — hard-seek at a higher threshold, e.g. 150 ms).

*Test:* start a long stem on two devices, force one to drift (seek it +200 ms
manually) — it should glide back within a few seconds. Then reboot one device
mid-stem — it should rejoin at the right position within one sync period.

> **Claude Code prompt:** *"Add a position servo. Master side (CueServerService
> and server.py): track startedAt master time per playing stem and broadcast
> /sync [stem:string, position:double, masterTime:double] to every target IP
> once per second. Unity side (AudioSceneController): on /sync compute the
> age-adjusted expected position; if the stem is playing, apply hysteresis
> (<30 ms ignore, 30–250 ms rate-nudge via source.pitch ±0.01, >250 ms seek);
> if the stem is not playing but the config says this stem loops, start it at
> the expected position (late-join recovery). Mirror in the Android app's
> StemEngine with seek-only correction at a 150 ms threshold."*

### Phase 3 — Operational discipline (no code)

- Launch apps early; **wait for "Audio loaded and cached" on every device.**
- **Warm-up ritual:** fire every cue once (on/off) before doors — pre-pays
  shader/VFX/FlipBook first-activation cost so no main-thread hitch can
  swallow a cue moment (scheduled start also protects here, but cheap is cheap).
- One Wi-Fi 6 travel router **per Joan group, carried with the group** — the
  network moves with the headsets, keeping delay small and stable.

## 6. Build order

| Phase | Scope |
|---|---|
| 0 — clock sync | new small class ×3 targets |
| 1 — scheduled start | receiver + sender changes |
| 2 — servo | master show-clock + receiver servo |
| 3 — ops | checklist only |

Each phase is useful alone; nothing blocks a show if later phases slip.

## 7. Key repo facts (for whoever implements)

- Unity repo: `annehiatt/joan-of-the-city-xreal` — audio code in
  `Assets/JoanAudio/Scripts/` (`OscCueReceiver.cs`, `AudioSceneController.cs`,
  `AudioStemPlayer.cs`, `AudioStemLoader.cs`). OSC via **extOSC**, receiver on
  UDP :7000. `PreloadAll` is ON in MainScene; loader mode is Remote (GitHub
  `dlobser/opera-audio`, disk-cached).
- Cue senders: `Assets/ThirdParty/OSC_Controller/` (HTML + `proxy.py`), the
  fused `osc-cue-server/server.py`, and this Android app (Operator mode:
  `CueHttpServer.kt`).
- Performer tablet: this app's Performer mode (`StemEngine.kt`,
  `PerformerService.kt`) — a Kotlin port of JoanAudio semantics; keep it in
  parity with any Unity changes.
- Visual cues (`/cue` ints, `SimpleMessageReceiver.cs`) are **toggles — do
  not blind-resend them**; the dedupe-by-(id, playAt) trick applies only to
  the scheduled audio messages (and is the model to migrate visuals toward:
  state-based, idempotent).
