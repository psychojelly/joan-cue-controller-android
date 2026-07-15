# Running the Cue Server on an Android Tablet

Goal: tap **one button on the tablet's home screen** → the cue server starts and
the controller opens in the browser. The Android equivalent of the `.bat`.

There's no native "double-click a script" on Android, so we use **Termux**
(a terminal app) plus **Termux:Widget** (home-screen buttons that run scripts).
One-time setup is ~10 minutes; after that it's one tap.

---

## One-time setup

### 1. Install the apps (from F-Droid, NOT the Play Store)
The Play Store version of Termux is abandoned and broken. Install from
[F-Droid](https://f-droid.org):

- **Termux** — the terminal
- **Termux:Widget** — home-screen shortcuts that run Termux scripts
- **Termux:API** *(optional but recommended)* — lets the script open the browser
  and take a wake lock cleanly (`pkg install termux-api` inside Termux too)

### 2. First Termux run
Open Termux and run:

```bash
pkg update -y
pkg install -y python termux-api
pip install python-osc
termux-setup-storage        # grant file access when prompted
```

### 3. Copy this folder onto the tablet
Get the whole `osc-cue-server` folder onto the tablet (USB copy, Drive, etc.) —
for example into `Download/`. Then in Termux, move it somewhere stable:

```bash
cp -r /sdcard/Download/osc-cue-server ~/osc-cue-server
```

### 4. Wire up the one-tap shortcut
Termux:Widget runs anything in `~/.shortcuts/`:

```bash
mkdir -p ~/.shortcuts
cp ~/osc-cue-server/start-tablet.sh ~/.shortcuts/JoanCues
chmod +x ~/.shortcuts/JoanCues ~/osc-cue-server/start-tablet.sh
```

> Note: the copy in `~/.shortcuts/` must still point at the real folder — it
> does, because the script `cd`s to its own location. If you copied it, edit the
> first `cd` line to `cd ~/osc-cue-server` instead. Simplest reliable version:
>
> ```bash
> echo -e '#!/data/data/com.termux/files/usr/bin/bash\nexec ~/osc-cue-server/start-tablet.sh' > ~/.shortcuts/JoanCues
> chmod +x ~/.shortcuts/JoanCues
> ```

Then long-press the home screen → **Widgets** → add a **Termux:Widget** — your
`JoanCues` button appears.

### 5. Stop Android killing the show (important)
Android suspends background apps aggressively:

- Settings → Apps → **Termux** → Battery → **Unrestricted / Don't optimize**
- The script also calls `termux-wake-lock` automatically.

---

## Every show day

1. Tap **JoanCues** on the home screen.
2. The server starts (or is detected already running) and the controller opens
   in the browser at `http://localhost:8765/`.
3. Fire cues. Other devices on the same Wi-Fi can also open
   `http://<tablet-ip>:8765/`.

**Stop it:** open Termux → `pkill -f server.py` (and `termux-wake-unlock`).

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| Widget button does nothing | Script not executable: `chmod +x ~/.shortcuts/JoanCues`. |
| `pythonosc` not found | `pip install python-osc` inside Termux (not Pydroid). |
| Browser can't reach `localhost:8765` | Check the server is running: `pgrep -f server.py`; look at `~/osc-cue-server/server.log`. |
| Cues stop after screen sleeps a while | Battery optimization not disabled for Termux (step 5). |
| Other devices can't reach the tablet | Same Wi-Fi, router "client isolation" must be OFF. |

---

## Honest limits of this approach

- **Termux is workable but not bulletproof for live shows.** Android can still
  reclaim the process under memory pressure. Always confirm the server is up
  before doors (tap the widget again — it detects an already-running server).
- **No auto-start on boot** without the extra Termux:Boot app.
- For a permanent tablet-as-brain design, the robust path is a **native Android
  wrapper app** (WebView UI + native UDP) — no Termux, no Python. See HANDOFF.md.
