# Joan of the City — Fused OSC Cue Server

This folder is a **self-contained cue controller**. One small program
(`server.py`) both **serves the controller web page** and **sends the cues out
over OSC/UDP** to the headsets. You run one thing and open one URL — on a PC or
an Android tablet.

> Why is a server needed at all? A web browser can't send UDP (it's sandboxed),
> and the show's cues go out as OSC over UDP. So `server.py` does the sending on
> the browser's behalf. This "fused" version just bundles the page and the
> sender into one process. See **HANDOFF.md** for the full picture.

---

## What's in this folder
| File | What it is |
|------|-----------|
| `server.py` | The fused server: serves the page **and** bridges button presses to OSC. |
| `index.html` | The QLab-style controller page (served at `/`). |
| `csv-editor.html` | Optional audio-cue CSV editor (served at `/csv-editor.html`). |
| `start-tablet.sh` | One-tap Android launcher (Termux) — checks deps, starts the server, opens the browser. |
| `README.md` | This file. |
| `README-TABLET.md` | Android/Termux setup guide, incl. the home-screen one-tap shortcut. |
| `HANDOFF.md` | How it works, for developers. |

---

## Requirements
- **Python 3.9 or newer**
- The one dependency: **`python-osc`**

---

## Run it on a PC (Windows / Mac / Linux)

```bash
pip install python-osc        # one time
python server.py              # (macOS/Linux: python3 server.py)
```

You'll see:
```
Joan of the City - Fused OSC Cue Server
  Controller : http://localhost:8765/
  From LAN   : http://<this-device-ip>:8765/
```

Then open **http://localhost:8765/** in a browser. Leave the terminal open —
closing it stops the server. Stop with **Ctrl+C**.

---

## Run it on an Android tablet (Termux)

A tablet can be the whole "brain": it serves the page to its own browser **and**
sends the OSC. After a ~10-minute one-time setup you get a **one-tap home-screen
button** (`start-tablet.sh` via Termux:Widget) that starts the server and opens
the controller — the Android equivalent of the `.bat`.

**→ Full steps: [README-TABLET.md](README-TABLET.md)**

> Pydroid 3 (a Python app for Android) also works if you prefer a GUI to Termux,
> but it has no home-screen shortcut story.

---

## Using the controller
1. In the page, add your **target headset IPs** and set the **UDP port** (default `7000`).
2. Fire cues. Each press is sent to **every** IP in the active list.
3. Use **Export / Import Settings** to save your IPs, groups, and layout, or move
   them to another device — the page stores them per-browser in `localStorage`.

Reach it from **another device** on the same network at
`http://<the-server-device-ip>:8765/`.

---

## Troubleshooting
| Symptom | Fix |
|---|---|
| `ModuleNotFoundError: pythonosc` | Run `pip install python-osc` again in the same environment. |
| Page loads but cues do nothing | The page and the server must be the same one — open the URL the server printed, not a saved `.html` file. Check the terminal prints `OSC -> …` when you fire. |
| Can't reach it from another device | Same Wi-Fi network, no client isolation on the router, and the device's firewall must allow port 8765. |
| Android kills it after a while | `termux-wake-lock` + disable battery optimization for Termux. |

---

## Notes
- This is a **launcher/convenience** layer. It does **not** change how cues are
  delivered — they're still fire-and-forget OSC/UDP with no acknowledgements.
  See HANDOFF.md for the reliability discussion.
- These files are a **working copy**. Fold them into the real project repo so
  they're version-controlled rather than living in Downloads.
