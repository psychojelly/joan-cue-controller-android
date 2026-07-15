# Sync Patch Test Report — 2026-07-15

Tested by Dom (via Claude + MCP for Unity) on the Windows editor machine,
Unity 6000.0.26f1, scene `Dom_Testing`, following the procedure in `README.md`.

**TL;DR: the patch passes end-to-end. Nothing in any repo was modified —
there are no conflicts to merge.**

## Test results

| Check | Result |
|---|---|
| Patch files vs installed (`Assets/JoanAudio/Scripts/`) | All 4 **byte-identical** (MasterClock, OscCueReceiver, AudioSceneController, AudioStemPlayer) |
| Compilation | Clean — no errors. Only pre-existing CS0618 deprecation warnings (extOSC, JoanAudioPlayerUI) |
| `/clock/master` announce → `MasterClock: master -> <ip>` | ✅ |
| Clock sync → `MasterClock synced (offset …, rtt 1.4 ms)` | ✅ |
| Scheduled cue, `leadMs=400` → `Cue A_SQ201 scheduled in 392–394 ms` | ✅ (6–8 ms transit, as expected) |
| 3× redundant send dedupe | ✅ 3 sends → 1 trigger |
| Rollback: `UseScheduledSync` off → original immediate path | ✅ (note: with sync off but a sync-mode sender, the cue retriggers 3× — dedupe only runs on the scheduled path. Harmless, matches design.) |
| Full HTTP path (`POST /send` → server.py → OSC → Unity) | ✅ |

## ⚠️ One real gotcha found (not a patch bug)

A **stale `server.py` from the previous day was still running** and had
double-bound port 8765 (Windows allows it via SO_REUSEADDR). The old
pre-sync instance was swallowing `/send` requests and sending cues
**without** `playAt`. Symptom: cues fire normally but never schedule, and
no MasterClock lines appear — looks exactly like a patch failure.

**Suggested fix:** make `server.py` refuse to start (or kill the squatter)
when port 8765 is already bound — `allow_reuse_address = False` on the
ThreadingHTTPServer is probably enough on Windows. Worth doing before this
bites someone mid-rehearsal.

## State touched during testing (all reverted / transient)

Editor-only, nothing persisted:
- Entered/exited Play mode; scene `Dom_Testing` verified **not dirty** afterwards
- `UseScheduledSync` toggled off→on **in Play mode only** (runtime, not serialized)
- Unity Console cleared several times
- Cue `A_SQ201` fired ~8 times (audio played on the editor machine)

Processes:
- Killed stale `server.py` (PID 49200, running since 7/14) — see gotcha above
- Started + stopped a fresh `server.py` for the test; port 8765 is now free

Repo status after testing:
- `joan-cue-controller-android`: **clean** (this report file is the only addition)
- Unity project (`Downloads/joan-of-the-city-xreal-main`): untouched — note it is
  **not under version control**, which is worth fixing (an empty `joan-unity`
  repo already exists in Psychojelly_Git waiting for it)

## Diff vs the repo today (`annehiatt/joan-of-the-city-xreal`, main @ `a64a37f`, 2026-07-15)

The test machine's Unity project is a **~June 25 zip snapshot of `main`**.
Content-verified against today's `origin/main` (line-ending noise excluded):

### Local changes — the sync patch + test scene (everything else is untouched)

| File | Status | Change size |
|---|---|---|
| `Assets/JoanAudio/Scripts/OscCueReceiver.cs` | edited | 56 lines |
| `Assets/JoanAudio/Scripts/AudioSceneController.cs` | edited | 78 lines |
| `Assets/JoanAudio/Scripts/AudioStemPlayer.cs` | edited | 12 lines |
| `Assets/JoanAudio/Scripts/MasterClock.cs` | **added** | new file |
| `Assets/Dom_Testing.unity` | **added** | Dom's test scene, never in repo |

All four scripts are byte-identical to this `unity-patch/` folder.

### Merge risk: none

The three edited files were last touched upstream **May 4–20** (`audio
controller`, `fixes to cue system`, `ravens and csv controller update`) and
nobody has modified them since — today's `main` versions are exactly the
baseline the patch was written against. **The 4 files drop straight onto
current `main` with zero conflict.** The patch is not yet in the repo
(no `MasterClock.cs`, no `playAt` handling upstream).

### Where the snapshot lags main (upstream moved on — not local changes)

- **36 files added upstream** since June 25 (July 13–15 commits by David &
  Nina): Adirenne skin Mixamo rigs, Avatar Rig Swap Tool + new models,
  `Ch36_nonPBR` / `T-PoseVoiceHumanoid` FBXs, Polygonmaker floating
  animations, FloatVFXgraph binder + cue binder, main-scene animation
  chaining, a proper `.gitignore`
- **4 stale Shadow prefabs** still present locally (`Shadow`,
  `Shadow_WithAudio`, `Shadow_WithAudioParticles`, `ShadowFInal_06232026`)
  that upstream **deleted July 13** ("Testing the new audio reactivity
  script") — do not copy these anywhere
- Assorted upstream scene/animation modifications since June 25

### Recommended integration

Apply the 4 patch files to current `main`; treat the June snapshot as
disposable (3 weeks behind). Bring `Dom_Testing.unity` along only if the
test scene is worth keeping.
