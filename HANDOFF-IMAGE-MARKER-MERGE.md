# Handoff — merging the audio-sync / debug stack into the image-marker build

**For:** David · **From:** Dom · **Date:** 2026-07-17
**Task:** bring the audio sync + observability work (all merged to `main` of
`annehiatt/joan-of-the-city-xreal`) into your image-marker build.

Everything below is **hardware-verified** (Beam Pro X4000 + XREAL glasses,
private WiFi) — measured results in `DEBUG-MODE.md` → "Verification summary —
real hardware". Headline: the Wi-Fi low-latency lock cut worst-case cue
transit **674 ms → 65 ms** (21% → 0% spikes), so a **~200 ms scheduling lead**
is show-safe.

---

## 1. What you're merging (the short version)

The whole feature set is **additive and scripts-only** — no scene, prefab, or
ProjectSettings changes were committed. Two asset folders carry everything:

| Path | Contents |
|---|---|
| `Assets/JoanAudio/` | All audio + debug scripts (details below) |
| `Assets/Plugins/Android/JoanAudio.androidlib/` | Manifest fragment adding `android.permission.WAKE_LOCK` (required by the Wi-Fi lock — without it the lock silently no-ops) |

If your image-marker build lives in a branch of the same repo: **merge `main`**
(`84562e0` at time of writing) and you get all of it plus Nina's/your latest
scene work. If it's a separate project: copy those two folders, plus the
scene wiring in §3.

`unity-patch/` in this repo mirrors every file as a standalone-copy fallback.

## 2. Script inventory (`Assets/JoanAudio/Scripts/`)

| File | Role | New/Modified |
|---|---|---|
| `MasterClock.cs` | NTP-style clock sync vs the cue server (learns master from `/clock/master`, pings :9001, median-filtered offset, slewing) | existing |
| `OscCueReceiver.cs` | Binds every OSC address (cues + debug + utilities); scheduled-cue dedupe; inspector toggles (`UseScheduledSync`, `WifiLockOnStart`, `AlwaysOnSafetyHeartbeat`) | modified |
| `AudioSceneController.cs` / `AudioStemPlayer.cs` / `AudioTransport.cs` etc. | Stem playback; `TriggerCueScheduled` starts sample-accurately via `PlayScheduled`; live CSV reload | modified |
| `WifiLockManager.cs` | Android `WIFI_MODE_FULL_LOW_LATENCY` lock — **the latency fix**. Auto-acquired on start; `/debug/wifilock 0|1` toggles live | **new** |
| `DebugReporter.cs` | Device→server reports (hello/rx/hb/log), heartbeat with **fps + battery vitals**, safety heartbeat, self-bootstrapping hidden host | modified |
| `DebugHudWorld.cs` | **In-glasses HUD** — world-space canvas parented to the XR camera (OnGUI never composites into XR stereo). Feed + margin-graph modes, vitals in header | **new** |
| `DebugHud.cs` | OnGUI HUD, now editor-only | modified |
| `TestTone.cs` | Procedural triple beep, scheduled like a real cue — the audible sync check | existing |
| `Snapshot.cs` | `/debug/snap` → renders the camera to JPEG → HTTP-POST to the server. **Two hard-won fixes inside**: URP requires `RenderPipeline.SubmitRenderRequest` (manual `Camera.Render()` is unsupported), and upload uses `System.Net` on a worker thread (UnityWebRequest rejects plain http in non-development builds) | **new** |

Dependency: `extOSC` (already in the project). Protocol contracts: `DEBUG-MODE.md`
(this repo) — single source of truth; also `AUDIO-SYNC-HANDOFF.md` for the
sync design itself.

## 3. Scene requirements (what the scripts expect)

- An `OSCReceiver` (extOSC) on port **7000** with `OscCueReceiver` wired to it
  (`Controller` → `AudioSceneController`, `Transport` → `AudioTransport`) —
  same wiring MainScene already has; nothing new to add if you merge the repo.
- `Camera.main` must resolve (MainCamera tag on the XR camera) — the world
  HUD parents to it and Snapshot renders it.
- **Exactly one enabled AudioListener** (we disabled a stray one on the
  VoxelBounds camera in MainScene — watch for regressions if the image-marker
  scene has extra cameras).
- Nothing else: DebugReporter/HUD/WifiLock all self-bootstrap from code.

## 4. Merge-conflict surface with an image-marker build

Expected: **near zero.** This work touches only `Assets/JoanAudio/` +
`Assets/Plugins/Android/JoanAudio.androidlib/`. Image-marker/tracking code
(image targets, marker anchors) has no file overlap. The only shared-resource
considerations:

- **Camera**: HUD + Snapshot use `Camera.main` — fine with marker-driven
  camera rigs as long as the tag survives.
- **Update-loop cost**: debug reporting is negligible (heartbeat 1 Hz; HUD
  refresh 3 Hz, only while on). Snapshot costs one extra scene render when
  triggered — a one-frame hitch; tech tool, not for mid-show.
- **Network**: everything is UDP/HTTP on the LAN — no conflict with marker
  tracking. The Wi-Fi lock only pins the radio's power state.

## 5. Build & signing notes

- **Manifest**: the androidlib fragment must survive your build — verify with
  `adb shell dumpsys package com.joanofthecity.xreal | grep WAKE_LOCK`
  (must show `granted=true`).
- **HTTP**: no PlayerSettings change is needed for the snapshot upload (it
  deliberately bypasses UnityWebRequest) — don't "fix" it back.
- **Signing**: the three test devices (X4000 Beam Pro, X4200 Beam Pro,
  Samsung S25) currently run a **debug-keystore** build from Dom's machine.
  Your differently-signed build will need uninstall→install over it (data
  loss is fine — stems re-cache). Long-term these should be your release-key
  builds (also removes the XREAL dev watermark, which needs the release
  license).
- All three devices have wireless adb armed (`tcpip 5555`, resets on reboot)
  — used by the operator panel's 🎥 recording feature.

## 6. Post-merge verification checklist (10 minutes)

1. Build to a device, launch — log shows
   `[JoanAudio] Wi-Fi low-latency lock ACQUIRED` (if instead it warns about
   the lock, the WAKE_LOCK manifest didn't merge — see §5).
2. Cue server + controller page up (`pc-server/server.py`, this repo) →
   device's roster dot goes **green within ~10 s, no cue needed** (the page
   announces the clock master every 5 s).
3. 🐞 panel → reporting ON → fire a **test tone**: one clean triple beep,
   `RX TEST-TONE` with a sane margin in the log.
4. HUD: `/debug/hud` feed from the panel → panel visible **in the glasses**
   (head-locked, lower-center), header shows fps/battery/wifi-lock.
5. Roster 📸 → thumbnail of the glasses' view appears in the panel.
6. Delay graph flat around tens of ms; toggle **Wi-Fi lock off** → spikes
   return within a minute; toggle back on. (That's the A/B that proved the
   fix — see the measured numbers.)
7. Lead check: with the lock verified, set sync lead ≈ **200 ms** in the
   controller ⚙ settings; `scripts/measure-latency.py` (docs-site repo)
   prints the distribution if you want venue-specific numbers.

## 7. Known constraints to carry forward

- **Scene performance**: the current MainScene runs **17–25 fps** on the Beam
  Pro (XREAL target 60–72). Audio *playback* is audio-thread-exact
  regardless, but cue processing gains up to a frame (~60 ms) of jitter —
  worth optimizing, and image-marker tracking will add its own load. The
  operator panel's FRAME RATE graph shows it live per device.
- Wi-Fi lock needs `WAKE_LOCK` (§5). Lock state is visible in the HUD header
  and toggleable per-device from the panel.
- Phase 2 (position servo) remains **rejected as designed** for this
  production — rationale in `AUDIO-SYNC-HANDOFF.md`. Don't re-add rate
  nudging on sung material.
- Nebula on the S25 hijacks the glasses on plug-in (regular-phone problem,
  not on Beam Pros) — disable its auto-launch or the app never gets the
  display.

## 8. Repo state at handoff

| Repo | `main` | Notes |
|---|---|---|
| `annehiatt/joan-of-the-city-xreal` | `84562e0` | All Unity work merged; `wifi-lock` branch = same content, can be deleted |
| `psychojelly/joan-cue-controller-android` | `4759313`+ | Server, panel, Kotlin app all current; `video-capture` branch merged |
| Docs site (`Joan_of_the_City_PJ_Documentation`) | current | Client-facing conceptual page only — contracts live here in `DEBUG-MODE.md` |

Questions → Dom. The 🐞 panel's log + graphs are the fastest way to see what
any device is actually doing — turn on reporting before debugging anything.
