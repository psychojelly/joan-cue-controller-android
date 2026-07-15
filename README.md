# Joan Cues — Android Cue Controller & Performer Monitor

A native Android app with **two modes**. The choice is **remembered** — after
the first pick, the tablet boots straight into its role. To change it:
**Performer** → the ⚙ SWITCH MODE button; **Operator** → press **Back** (with
no page history) and confirm. Both paths confirm before switching, so a
mid-show mis-tap can't change a tablet's role.

- **🎛 Operator** — the standalone cue brain: hosts the cue controller webpage
  and fires OSC/UDP cues to the group. Show-grade replacement for
  `proxy.py`-in-Termux.
- **🎭 Performer** — the listening tablet: receives OSC on **:7000** exactly
  like a headset (`/audio/cue`, `/audio/seek|jump[/stem]`, `/audio/vol`),
  downloads + caches the stems and cue CSV from GitHub, and plays the audio
  in sync so the performer hears the show. A Kotlin port of the Unity
  **JoanAudio** semantics (cue groups `B_SQ201`→`B_201`, Hold / Loop /
  OneShot / Stop / StopAll, volume ramps; stems persist across cues).
  Unity's implementation remains the source of truth — if behavior ever
  differs, trust Unity's.

The mode picker also shows the tablet's **IP address** — that's what you add
to the operator's target list.

**What it is:** the existing cue-controller webpage, unchanged, inside a
WebView — backed by an embedded HTTP server (a direct Kotlin port of
`server.py`) that turns button presses into **OSC/UDP** packets to the
headsets. A **foreground service** with wake + Wi-Fi locks keeps it alive with
the screen off, which is the thing Termux can't guarantee.

```
┌─ Android app ────────────────────────────────┐
│  MainActivity (WebView)                      │
│    └── http://127.0.0.1:8765/  ──┐           │
│  CueServerService (foreground)   │           │
│    └── CueHttpServer :8765  ◄────┘           │
│          ├── serves assets/web/index.html    │
│          └── POST /send → OscEncoder → UDP ──┼──► headsets :7000
└──────────────────────────────────────────────┘
```

Other devices on the same Wi-Fi can ALSO open `http://<tablet-ip>:8765/`
(shown in the app's notification) — the tablet doubles as the hosted server
for thin-client phones/laptops.

---

## Dev environment setup (one time, ~30–45 min mostly downloads)

Nothing is currently installed on this machine — you need exactly one thing:

1. **Install Android Studio** from <https://developer.android.com/studio>
   (the default install bundles the JDK, Android SDK, build tools, and adb —
   no separate installs needed). Accept the licenses in the setup wizard.
2. **Open this folder** in Android Studio (`File → Open` →
   `joan-cue-controller-android`). It will read
   `gradle/wrapper/gradle-wrapper.properties`, download Gradle 8.9 and all
   dependencies, and sync. First sync takes a few minutes.
3. If Studio suggests installing a missing SDK platform (API 35), accept.

> **Windows gotcha:** if you hand-write `local.properties`, use forward slashes
> (`sdk.dir=C:/Android/sdk`). Backslashes get eaten by the Java properties
> parser and the build fails with a cryptic `Invalid file path`.
>
> **Command-line build (no Studio window needed):**
> ```
> set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
> C:\Android\gradle-8.9\bin\gradle assembleDebug
> ```
> APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

## Build & run on the tablet

1. On the tablet: **Settings → About → tap "Build number" 7×** to enable
   Developer Options, then **Developer Options → USB debugging → on**.
2. Plug the tablet into the computer (accept the "allow USB debugging" prompt).
3. In Android Studio: pick the tablet in the device dropdown → **Run ▶**.
   The app installs and launches: the cue controller appears full-screen and
   a "Joan cue server running" notification shows the tablet's IP.

**No Play Store involved** — this is the same sideload model as the headsets.
To hand the APK to someone: `Build → Build APK(s)`, the file lands in
`app/build/outputs/apk/debug/app-debug.apk`; copy it to any tablet and open it
(allow "install unknown apps").

## Testing plan

**Stage 1 — bench (no headsets):**
- Run an OSC monitor on a PC ([Protokol](https://hexler.net/protokol), free).
- In the app's controller, add the PC's IP as a target, port 7000.
- Fire cues; confirm Protokol shows `/cue <int>` and `/audio/cue` values, and
  that integers arrive as ints, cue ids like `B_VQ_L01` as strings.

**Stage 2 — the Android gauntlet (what actually matters):**
- Screen off 30 min → fire a cue → must arrive instantly.
- App backgrounded (home button) → cues from a thin client must still send.
- Battery saver ON → repeat.
- Multi-hour soak at show length; watch for thermal or Wi-Fi sleep issues.
- Wi-Fi drop → rejoin → cues resume without restarting the app.

**Stage 3 — real target:** fire at the Unity Editor in Play mode, then a
headset build. Confirm the cue map (1–5 visual; 6–8 unmapped — do not fire;
9–16 Shadow states) matches expectations.

## Updating the controller page

The webpage lives in `app/src/main/assets/web/index.html` (a copy of
`qlab-controller.html` from the Unity repo's `osc-cue-server` folder). When
the controller is updated there, copy the new file over this one and rebuild.
Settings (IPs, groups, audiences) persist in the WebView's localStorage across
app updates — use the controller's Export Settings for backups anyway.

## Project layout

| Path | What |
|---|---|
| `app/src/main/java/.../MainActivity.kt` | WebView shell, keep-screen-on, retry-on-boot |
| `app/src/main/java/.../CueServerService.kt` | Foreground service; wake + Wi-Fi locks; notification with IP |
| `app/src/main/java/.../CueHttpServer.kt` | NanoHTTPD port of `server.py` (`/send` contract identical) |
| `app/src/main/java/.../OscEncoder.kt` | Minimal OSC 1.0 binary encoder (i, f, s, T/F) |
| `app/src/main/java/.../ModePickerActivity.kt` | Launcher: Operator / Performer choice + device IP |
| `app/src/main/java/.../PerformerActivity.kt` | Performer status board: current cue, active stems, log |
| `app/src/main/java/.../PerformerService.kt` | Foreground OSC listener on :7000 → StemEngine |
| `app/src/main/java/.../StemEngine.kt` | Kotlin port of JoanAudio (CSV cues, stem download/cache, Loop/OneShot/Stop/StopAll, fades) |
| `app/src/main/java/.../AudioCueCsv.kt` | Port of AudioCueConfig.cs (CSV parse, group keys, action aliases) |
| `app/src/main/java/.../OscDecoder.kt` | OSC 1.0 decoder (receive side) |
| `app/src/main/assets/web/` | The controller HTML (copied from `osc-cue-server`) |

## Performer-mode parity notes

- The stem engine is a **port, not the original** — Unity's JoanAudio remains
  the source of truth. Before a performer relies on it, A/B the two against
  the same cue sequence.
- Stems are **file-cached but prepared at trigger time** (MediaPlayer), so
  first-start of a big stem can lag a beat behind Unity's preloaded clips.
  If that matters live, pre-prepared players are the next optimization.
- `/audio/vol` values are clamped to 0–1 here; confirm what range the
  controller actually sends.
- No spatial audio / DSP — performer mode is a musical monitor, not a
  replica of the headset mix.

## Known limitations / next steps

- **Stop button:** closing the app's task stops the service; there's no
  explicit "stop server" UI yet.
- **Release signing:** debug builds are fine for sideloading; add a signing
  key before wide distribution.
- **Kiosk mode:** consider Android screen pinning so a docent can't leave the
  app mid-show.
- The `/send` contract and value-typing rules are specified in the Unity
  repo's `osc-cue-server/HANDOFF.md` — that document is the source of truth
  for parity.
