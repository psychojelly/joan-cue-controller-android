# Handoff — Fused OSC Cue Server

**Audience:** developers (David / whoever maintains the cue system).
**Purpose:** explain what this folder is, how it works, exactly what changed
from the original `proxy.py`, and how to extend or productionize it.

---

## 1. Why this exists

The cue controller is a **web page**. Browsers are sandboxed and **cannot send
UDP**, but the show's cues travel as **OSC over UDP** to the headsets (Unity +
extOSC, listening on `:7000`). So a small native helper has to do the actual
UDP sending on the page's behalf.

Originally that was two separate things:
- `proxy.py` — a local HTTP server (`:8765`) that received a POST and sent one
  OSC/UDP message.
- `qlab-controller.html` — the UI, opened as a file, POSTing to `localhost:8765`.

**This folder fuses them:** `server.py` now *also serves the page*, so there's
**one process and one URL**. That's what makes it portable to an Android tablet
(serve to the tablet's own browser) and reachable from other devices by URL.

```
            ┌─────────────────────── one process (server.py) ───────────────────────┐
 Browser ──▶│  GET  /            → serves index.html (the controller UI)             │
 (any       │  POST /send {…}    → sends one OSC/UDP message to a headset            │──▶ Headsets
  device)   └───────────────────────────────────────────────────────────────────────┘     (Unity/extOSC :7000)
```

---

## 2. The request contract (unchanged from `proxy.py`)

The page fans out a cue by POSTing **once per target IP** to `/send`:

```
POST /send
Content-Type: application/json
{ "host": "10.0.1.32", "port": 7000, "address": "/cue", "value": 5 }
```

The server sends exactly one OSC message: `address value` to `host:port` over UDP.

**Value typing is preserved** (this matters — don't "simplify" it):
- `bool` / `int` / `float` → passed through as-is.
- a numeric-looking **string** (`"5"`, `"-3"`) → converted to **int**, so cue
  numbers reach `IntValue` receivers in Unity.
- any other string (`"VQ101"`, `"stopall"`) → sent as an **OSC string**.

Response: `200 {"ok":true}` on success, `500 {"ok":false}` if the UDP send throws.
CORS headers are included so the page still works if opened as a `file://`.

---

## 3. What changed vs. the original `proxy.py`

| | Original `proxy.py` | This `server.py` |
|---|---|---|
| POST `/send` → OSC | ✅ (identical logic) | ✅ (same value-typing, CORS) |
| Serves the HTML page | ❌ (page opened as a file) | ✅ **new** — `GET /` → `index.html`, plus any static file in this folder |
| Threading | single-threaded `HTTPServer` | `ThreadingHTTPServer` (page loads don't block sends) |
| Path safety | n/a | `GET` is restricted to this folder (no `..` traversal) |

**Page change:** in `index.html`, `sendOsc()` now targets a **relative** URL when
served over http(s), and only falls back to `http://127.0.0.1:8765/send` when
opened as a `file://`:

```js
const PROXY_URL = location.protocol.startsWith('http') ? '/send' : 'http://127.0.0.1:8765/send';
```

That one line is the only functional edit to the controller. Everything else
(cue model, IP/audience management, CSV audio, Export/Import) is untouched.

---

## 4. How the cues relate to Unity

The server is only the **sender**. The **receiver** is Unity:
- Visual cues: `/cue <int>` → `SimpleMessageReceiver` toggles `cue_1`…`cue_8`
  (mapped in the Inspector; 6–8 may be unassigned). Controller cue numbers
  **9–16** drive Shadow animation states via a separate receiver.
- Audio cues: `/audio/*` → the `JoanAudio` stem engine, whose cue list comes from
  a **CSV on GitHub** (`dlobser/opera-audio`) that both Unity and the controller
  read. `csv-editor.html` edits that CSV.

Sender and receiver are kept in sync **only by the cue number** for visual cues.
Adding a visual cue is a two-place change (Unity Inspector/script **and** the
controller). Audio cues sync via the shared CSV.

---

## 5. Deployment notes

**PC (recommended for a single shared brain):** `python server.py`, done.

**Android tablet (per-Joan brain):** run under Termux/Pydroid. Caveats:
- Android aggressively suspends/kills background processes → use
  `termux-wake-lock` and disable battery optimization.
- No auto-start on boot without extra setup (Termux:Boot).
- This is workable but fragile for a live show. For a permanent tablet-as-brain
  design, the robust path is a **native Android wrapper** (a Kotlin app with a
  WebView for the UI and native UDP sending) — no Python, no Termux, one tap.

**Network:** the server binds to all interfaces (`("", 8765)`), so it's reachable
at `http://<device-ip>:8765/`. On a show network, that's fine; don't expose it
beyond the private network (anyone who can reach it can fire cues).

---

## 6. What this does NOT change (open items)

- **Transport reliability is unchanged.** Cues are still fire-and-forget UDP with
  no acknowledgement. A dropped packet = a silently missed cue. Hosting the UI
  doesn't fix delivery. (Options discussed elsewhere: OSC-over-TCP or MQTT, plus
  making cues idempotent/state-based instead of toggles.)
- **No multi-operator state sync.** IP lists / cue layout live in each browser's
  `localStorage`. If two operators load the page, they don't share state. For
  true multi-operator, move shared state server-side.
- **No feedback / health.** Nothing reports back from the headsets. A natural
  next step is a `/ack` endpoint here + headsets transmitting an OSC ack on
  receipt, surfaced as a "cue N → k/N headsets" readout in the page.

---

## 7. Suggested next steps (in rough priority)

1. Fold this folder into the real repo (it's currently a working copy in Downloads).
2. Decide PC-brain vs tablet-brain per Joan — it changes the network/reliability plan.
3. If tablet-brain is permanent: build the native wrapper app.
4. Add the ACK/health readout (small, high value for operator confidence).
5. Revisit transport reliability (TCP/MQTT + state-based cues) if drops are seen.

---

## Related: audio sync design

A full design + phased implementation handoff for cross-device audio sync
(shared clock, scheduled starts, position servo) lives in the Android app
repo: `joan-cue-controller-android/AUDIO-SYNC-HANDOFF.md`. It includes
ready-to-use Claude Code prompts per phase.
