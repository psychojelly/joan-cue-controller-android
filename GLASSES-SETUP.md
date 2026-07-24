# Glasses & Beam Pro — device setup and operations

Operational notes for running the Joan app on XREAL Air 2 Ultra glasses,
driven by an XREAL Beam Pro (or a supported phone). Learned the hard way
during setup/testing; captured so the next person doesn't re-discover it.

## Auto-launching the Joan app when the glasses connect

The auto-launch behavior is handled by the **XREAL Glasses Control app** —
it is the **SDK server / launcher** and must be **downloaded separately from
the app store** (it does not ship pre-installed on every device). This is
the app that manages what launches when the glasses are connected.

**To stop Nebula from opening by default** (so it doesn't grab the display
before/over the Joan app): in the Glasses Control app, **turn on "Default
AirCast."** With that enabled, Nebula stops auto-opening as the default.

> Note: "Nebula" is a **system app** (`ArLauncher`, in `/system/app`) — it
> does not appear as a tappable "Nebula" icon in the app drawer; it *is* the
> in-glasses launcher/home environment. That's why you won't find a "Nebula"
> app to configure — the relevant setting lives in **Glasses Control**.

### ⚠️ Timing caveat before you rely on auto-launch
The Joan app currently **self-quits (~18 s) if it launches before the
glasses finish negotiating into stereo (3840×1080) mode** — the XREAL
session aborts on a display-mode mismatch. Auto-launch fires at connect,
which is the riskiest moment. Until the app's init waits/retries for stereo
(a code fix — see "Known issues"), auto-launch may crash-loop. Verify a
stable manual launch first.

## The manual launch ritual (reliable today)

1. Power the Beam Pro; make sure it's **charged** (a low host under-feeds the
   glasses' USB-C power and the link drops under load).
2. Plug the glasses in and **wait until the launcher/Nebula renders in the
   glasses in 3D** — that confirms the display negotiated into stereo.
3. **Then** launch the Joan app.
4. Don't plug/unplug the glasses while the app runs. If anything glitches:
   quit app → replug glasses → wait for stereo → relaunch. Worst case,
   reboot the Beam Pro to clear a wedged display state
   ("display mode mismatch 12").

## Beam Pro USB ports (bit us repeatedly)

The Beam Pro has **two USB-C ports and they are not equal**: one is **data**,
the other is **power-only**. A PC cable in the power-only port charges fine
but is **completely invisible to adb** — the classic "device won't show up."
Use the **data port** (the one the glasses normally use) for PC/adb work.

## adb / installs (for updating the app)

- The build's applicationId is currently **`com.joanofthecity.tester`**
  (a dev rename from `.xreal`). `.tester` and `.xreal` are *different apps*
  to Android — a `.tester` build installs **alongside** an old `.xreal`
  build rather than updating it, so you can end up with two Joan icons.
  Remove the stale one to avoid confusion.
- USB debugging must be **on** (Developer options). File-transfer mode alone
  is not enough for adb.
- `adb install` may report **Success** but the package "not appear" — if the
  applicationId changed, you're querying the wrong package name, not a failed
  install. Confirm with `aapt2 dump packagename <apk>`.
- **Wireless adb**: `adb tcpip 5555` while on USB, then `adb connect <ip>:5555`
  — lets you install/inspect/record over WiFi with the glasses occupying the
  USB port. Resets on device reboot (re-arm via USB once per boot).

## Known issues / for the dev team

- **Launch-order self-quit** (above) — the biggest one. The XREAL session
  should wait/retry for stereo instead of aborting.
- **Dev-license validation**: dev-signed builds phone home to `api.xreal.com`
  and throw a login/activation screen if the show network has no internet.
  A **release license** removes this (and the "debug mode" watermark).
- **Scene performance**: ~17–25 fps on the Beam Pro vs the 60–72 target;
  heavy load can also stress the glasses' USB link. Optimization pending.
- **Air 2 Ultra + phone (S25) compatibility**: works on the Beam Pros;
  phones showed display/SDK errors — verify Air 2 Ultra support on the
  specific phone model before relying on it.
