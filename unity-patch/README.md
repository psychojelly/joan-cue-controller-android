# Unity Patch — Audio Sync Phases 0+1 (Scheduled Cue Starts)

> **Update 2026-07-17:** the folder now also mirrors the real-hardware wave,
> all **merged to `main` of the Unity repo** (`1f5a637..5b39551`):
> `WifiLockManager.cs` (Wi-Fi low-latency lock — cut worst-case cue transit
> 674→65 ms, auto-on at start, `/debug/wifilock` toggle),
> `Assets/Plugins/Android/JoanAudio.androidlib/` (adds the `WAKE_LOCK`
> permission the lock needs), `DebugHudWorld.cs` (world-space in-glasses HUD —
> the OnGUI HUD never composited into XR stereo), `Snapshot.cs`
> (`/debug/snap` camera capture → HTTP upload; note the two hard-won fixes
> inside: URP render requests + System.Net upload), heartbeat vitals
> (fps/battery) in `DebugReporter.cs`. See `../DEBUG-MODE.md` for the full
> protocol, panel features, and measured results.
>
> **Update 2026-07-15 (evening):** this folder also carries the debug
> observability layer (D2+D3): `DebugReporter.cs` + `DebugHud.cs` (new), plus
> debug hooks in `OscCueReceiver.cs` and an `OffsetMs` accessor in
> `MasterClock.cs` (merged 2026-07-15, `8ef3ad2..b3d13ff`). A current checkout
> doesn't need this folder; it remains as the standalone-copy fallback and
> docs anchor. Controller/server side is D0+D1 in this repo.
> Enable from the controller's 🐞 panel: `/debug/enable 1`, `/debug/heartbeat 1`.
> Everything defaults OFF; with debug off, behavior is byte-identical.

**For David.** This folder contains the Unity-side implementation of the audio
sync design (`../AUDIO-SYNC-HANDOFF.md`). The sender side (tablet app +
`pc-server/server.py`) is already implemented and validated — this completes
the system.

## What it is

Four files, **C# scripts only — no scene, prefab, or asset changes**:

| File | Change |
|---|---|
| `MasterClock.cs` | **NEW.** NTP-style clock sync client. Learns the master's IP from the `/clock/master` OSC announce, pings its `:9001` responder every 5 s, keeps a filtered offset (median of the 4 lowest-RTT of 16 samples, slewed). Self-bootstrapping — no scene object needed. |
| `OscCueReceiver.cs` | `/audio/cue` now accepts an **optional second arg** `playAt:double` (master-clock seconds). Dedupes the sender's redundant 3× sends by `(cueId, playAt)`. Binds `/clock/master`. Adds the **`UseScheduledSync`** inspector toggle. |
| `AudioSceneController.cs` | New `TriggerCueScheduled(cueId, playAt)`: converts master time → `AudioSettings.dspTime`, starts stems via `PlayScheduled` (sample-accurate, audio-thread — immune to main-thread hitches). **Late arrival** → plays immediately but seeks in by the overshoot, landing mid-phrase in sync. Volume ramps begin at the scheduled start. `TriggerCue` untouched. |
| `AudioStemPlayer.cs` | One added method: `PlayScheduled(double dspTime)`. |

## Install

Copy the four files over `Assets/JoanAudio/Scripts/` in the Unity project.
They were written against the June snapshot of those files — if you've changed
them since, the diffs are small and additive; a quick merge should be easy.

## Rollback — three independent layers

1. **Operator toggle (no Unity change needed):** sync mode OFF in the
   controller → cues carry no `playAt` → the receiver runs the **original
   immediate code path**, byte-identical to before.
2. **Unity-side switch:** untick **`Use Scheduled Sync`** on the
   `OscCueReceiver` component (Inspector, works in Play mode) → timestamps
   are ignored even if senders still send them.
3. **Git:** everything is additive — revert the three edited files and delete
   `MasterClock.cs`.

Backwards compatibility is total: a cue without a timestamp behaves exactly
as today, so old senders keep working against the new receiver and vice versa.

## How the clock finds the master

The cue server (tablet app / `server.py`) sends `/clock/master [ip:string]`
alongside every scheduled cue. `OscCueReceiver` passes it to
`MasterClock.SetMaster()`, which starts pinging. **First-cue caveat:** the
clock can't be synced before the first announce arrives, so the very first
scheduled cue after app start falls back to play-now (logged as a warning).
Show practice: fire one throwaway cue during warm-up; everything after is
scheduled. (If you'd rather sync before any cue, have the server announce
periodically — one-line change on our side, say the word.)

## Testing (10 minutes, no headset needed)

1. Open the project, press **Play** (MainScene — the extOSC receiver must be live).
2. Start the cue server (`pc-server/server.py` or the tablet app) and open the
   controller; enable sync mode (it adds `leadMs` to audio cues).
3. Fire an audio cue. Console should show, in order:
   - `MasterClock: master -> <ip>`
   - `MasterClock synced to <ip> (offset X ms, rtt Y ms)`
   - `Cue A_SQ201 scheduled in ~400 ms: <notes>` *(first cue may log the
     unsynced fallback warning — expected, see above)*
4. Two-device test: Editor + a Performer-mode tablet, both targeted; record
   both outputs side by side; onsets should land within ~10 ms.
5. Flip `Use Scheduled Sync` off and confirm the old immediate behavior.

## Protocol reference

Full message contracts, algorithm parameters, and the phase plan are in
`../AUDIO-SYNC-HANDOFF.md`. New since that doc's first draft:
`/clock/master [ip]` (master announce) and `/audio/reload` (re-fetch cue CSV —
not yet handled in Unity; see the handoff table).
