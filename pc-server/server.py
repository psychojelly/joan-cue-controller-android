#!/usr/bin/env python3
"""
Joan of the City - Fused OSC Cue Server
=======================================
One process that does BOTH jobs the show needs:

  1. Serves the controller web page  (so any browser - the same tablet, a PC,
     or another device on the network - can open it at a URL).
  2. Bridges the page's button presses to OSC/UDP out to the headsets
     (the one thing a browser cannot do on its own).

Because of #2 you always need this small server running somewhere: browsers are
sandboxed and cannot send raw UDP. This script just puts the page-serving and
the UDP-sending in the SAME process, so you launch one thing and open one URL.

Runs on a PC (Windows/Mac/Linux) or an Android tablet (via Termux / Pydroid 3).

Quick start:
    pip install python-osc
    python server.py
    # then open  http://localhost:8765/  in a browser on this device,
    # or         http://<this-device-ip>:8765/  from another device.

See README.md (how to run) and HANDOFF.md (how it works / for developers).
"""

import json
import os
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, unquote

from pythonosc import udp_client
from pythonosc.osc_message_builder import OscMessageBuilder

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
PORT = int(os.environ.get("PORT", 8765))        # HTTP port for page + /send
# Overridable so a SECOND instance can run beside a live one for testing.
# The show never needs this - it exists because the start-up guard (rightly)
# refuses to run two servers on the same UDP ports, which otherwise makes it
# impossible to try a change without stopping the rehearsal.
CLOCK_PORT = int(os.environ.get("CLOCK_PORT", 9001))   # UDP: /clock/ping -> pong
DEBUG_PORT = int(os.environ.get("DEBUG_PORT", 9002))   # UDP: /debug/* from devices
ROOT = os.path.dirname(os.path.abspath(__file__))
DEFAULT_PAGE = "index.html"                     # served at "/"

CONTENT_TYPES = {
    ".html": "text/html; charset=utf-8",
    ".htm":  "text/html; charset=utf-8",
    ".css":  "text/css; charset=utf-8",
    ".js":   "application/javascript; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".csv":  "text/csv; charset=utf-8",
    ".png":  "image/png",
    ".jpg":  "image/jpeg",
    ".jpeg": "image/jpeg",
    ".svg":  "image/svg+xml",
    ".ico":  "image/x-icon",
}


# ---------------------------------------------------------------------------
# Sync additions (Phase 0+1 of AUDIO-SYNC-HANDOFF.md)
# ---------------------------------------------------------------------------
def local_ip_for(host):
    """The IP this machine uses to reach `host` — what receivers ping for clock sync."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect((host, 9))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return None


def master_now():
    """Master clock = this process's monotonic time, in seconds (double)."""
    return time.monotonic()


def send_osc(host, port, address, args):
    """Send one OSC message; floats go as 64-bit doubles ('d') for ms precision."""
    b = OscMessageBuilder(address=address)
    for a in args:
        if isinstance(a, bool):
            b.add_arg(a)
        elif isinstance(a, float):
            b.add_arg(a, OscMessageBuilder.ARG_TYPE_DOUBLE)
        else:
            b.add_arg(a)
    udp_client.UDPClient(host, port).send(b.build())


# ---------------------------------------------------------------------------
# Debug observability (D0): devices send /debug/* OSC here; the controller
# page polls GET /debug/events?since=<seq> to visualize them.
# ---------------------------------------------------------------------------
_debug_lock = threading.Lock()
_debug_events = []          # ring buffer of dicts
_debug_seq = 0
DEBUG_RING = 500
DEBUG_LOG_RING = 120        # of those, at most this many forwarded device logs


# ---- headset snapshots (POSTed as JPEG over HTTP — too big for OSC/UDP) ----
# Latest snapshot per device id, kept in memory (a few hundred KB each).
_snapshot_lock = threading.Lock()
_snapshots = {}             # id -> {"jpg": bytes, "t": master_time, "n": count}

# ---- glasses video (adb screenrecord over WiFi) -----------------------------
# The panel's per-device 🎥 asks this server to drive `adb screenrecord` on the
# device wirelessly (adb connect <ip>:5555). Recording writes to the device's
# own storage — zero WiFi traffic during capture, so cue latency is untouched;
# the MP4 is pulled afterward. One-time setup per boot: TCP debugging must be
# enabled via `adb tcpip 5555` while on USB — done automatically below whenever
# the device is seen on USB. Note: adb-over-WiFi means anyone on the LAN can
# reach the device's debugger; fine on a private show network, don't leave it
# on on public WiFi (it resets on device reboot anyway).
import re
import shutil
import subprocess

ADB = (os.environ.get("ADB")
       or shutil.which("adb")
       or r"C:\Android\sdk\platform-tools\adb.exe")

_video_lock = threading.Lock()
_video = None               # {"mp4": bytes, "ip": str, "sec": int, "display": str}
_video_busy = False


def _adb(args, timeout):
    try:
        return subprocess.run([ADB] + args, capture_output=True, text=True,
                              timeout=timeout)
    except Exception as e:
        class R:  # minimal failed-result stand-in
            returncode = 1
            stdout = ""
            stderr = str(e)
        return R()


def _server_log(level, msg):
    """Surface server-side record progress/errors in the panel's log."""
    _debug_add("/debug/log", ["SERVER", master_now(), level, msg], "server")


def _record_thread(ip, sec):
    global _video, _video_busy
    try:
        serial = f"{ip}:5555"
        r = _adb(["connect", serial], 10)
        if "connected" not in (r.stdout or ""):
            # Maybe TCP mode isn't enabled — flip it if the device is on USB.
            # NOTE: `adb tcpip` restarts adbd, which drops the device back to
            # `unauthorized` until someone taps "Allow USB debugging" ON THE
            # HEADSET. So this cannot recover unattended; wait for the tap and
            # say so, rather than reporting a bare connection failure.
            devs = _adb(["devices"], 10).stdout or ""
            usb = [ln.split()[0] for ln in devs.splitlines()[1:]
                   if ln.strip().endswith("device") and ":" not in ln.split()[0]]
            if usb:
                _server_log("info", f"enabling adb-over-WiFi via USB ({usb[0]})…")
                _adb(["-s", usb[0], "tcpip", "5555"], 10)
                time.sleep(2)
                r = _adb(["connect", serial], 10)
                # Poll for the on-device authorisation prompt to be accepted.
                for _ in range(12):                      # ~30 s
                    if "connected" in (r.stdout or ""):
                        state = _adb(["devices"], 10).stdout or ""
                        if any(ln.startswith(serial) and ln.strip().endswith("device")
                               for ln in state.splitlines()):
                            break
                        _server_log("info",
                            "waiting — TAP 'Allow USB debugging' ON THE HEADSET "
                            "(tick 'Always allow from this computer')")
                    time.sleep(2.5)
                    r = _adb(["connect", serial], 10)
            if "connected" not in (r.stdout or ""):
                _server_log("error",
                    f"record: can't reach {serial}. Wireless adb resets every time "
                    f"the device reboots. Plug it into USB once, accept the "
                    f"'Allow USB debugging' prompt, then retry.")
                return

        # Pick the display: with glasses attached a second physical display
        # appears — record that one; otherwise the primary (phone screen).
        out = _adb(["-s", serial, "shell", "dumpsys", "SurfaceFlinger",
                    "--display-id"], 10).stdout or ""
        ids = re.findall(r"Display (\d+)", out)
        use_glasses = len(ids) > 1
        display = ids[-1] if use_glasses else (ids[0] if ids else None)
        label = "glasses display" if use_glasses else "primary display (no glasses detected)"
        if not use_glasses:
            # Worth shouting about: this silently captures the Beam Pro's own
            # screen instead of the show, and you only discover it afterwards.
            _server_log("warn",
                "record: NO GLASSES DISPLAY FOUND — capturing the phone screen, "
                "not the show. Plug the glasses in and wait for stereo first.")
        _server_log("info", f"recording {ip} {label} for {sec}s…")

        cmd = ["-s", serial, "shell", "screenrecord", "--time-limit", str(sec)]
        if use_glasses:
            cmd += ["--display-id", display]
        cmd += ["/sdcard/joan-rec.mp4"]
        r = _adb(cmd, sec + 20)
        if r.returncode != 0:
            _server_log("error", f"record failed: {(r.stderr or r.stdout or '?').strip()[:160]}")
            return

        rec_dir = os.path.join(ROOT, "recordings")
        os.makedirs(rec_dir, exist_ok=True)
        path = os.path.join(rec_dir, f"glasses-{int(time.time())}.mp4")
        r = _adb(["-s", serial, "pull", "/sdcard/joan-rec.mp4", path], 60)
        _adb(["-s", serial, "shell", "rm", "/sdcard/joan-rec.mp4"], 10)
        if r.returncode != 0 or not os.path.isfile(path):
            _server_log("error", f"record: pull failed: {(r.stderr or '?').strip()[:160]}")
            return
        with open(path, "rb") as f:
            mp4 = f.read()
        with _video_lock:
            _video = {"mp4": mp4, "ip": ip, "sec": sec,
                      "display": "glasses" if use_glasses else "primary"}
        _debug_add("/debug/video",
                   [ip, len(mp4), sec, "glasses" if use_glasses else "primary"],
                   "server")
    finally:
        _video_busy = False


def _debug_add(addr, args, src_ip):
    global _debug_seq
    with _debug_lock:
        _debug_seq += 1
        _debug_events.append({
            "seq": _debug_seq,
            "t": master_now(),          # server master-clock receive time
            "addr": addr,
            "args": list(args),
            "from": src_ip,
        })
        # Forwarded device logs get their own quota inside the ring.
        #
        # One ring shared by everything means a chatty engine warning evicts the
        # events you are actually watching. That is not hypothetical: a PrimeTween
        # warning about a tween on an inactive GameObject arrived often enough to
        # fill 451 of 500 slots, and pushed out the cue reports being tested -
        # the noisiest signal silently destroying the most important one.
        #
        # So logs are trimmed against their own budget first, leaving cue traffic,
        # acks, drops and heartbeats to compete only with each other.
        logs = [e for e in _debug_events if e["addr"] == "/debug/log"]
        if len(logs) > DEBUG_LOG_RING:
            drop = set(id(e) for e in logs[:len(logs) - DEBUG_LOG_RING])
            _debug_events[:] = [e for e in _debug_events if id(e) not in drop]
        while len(_debug_events) > DEBUG_RING:
            _debug_events.pop(0)


# ---- dropped-cue detection --------------------------------------------------
# A cue that never arrives is the failure that matters most in a show, and it
# is invisible from here: UDP has no delivery report, so "sent" always looks
# like success. The device side already acks every cue it receives with
# /debug/rx, which turns the question into a timeout: register what we expect
# each device to ack, and anything still unacked past its deadline is a drop.
#
# Deadline is the cue's own playAt plus a grace period rather than a fixed
# delay from send: a cue scheduled 4 s out legitimately acks late, and judging
# it on send time would report false drops on every lead-in.
_pending_lock = threading.Lock()
_pending = []                   # {"ip","cue","sent","due"}
DROP_GRACE_S = 1.5              # after playAt (or after send, immediate cues)


def _expect_ack(ip, cue, sent_at, play_at):
    """Record that <ip> owes us a /debug/rx for <cue>."""
    due = (play_at if play_at is not None else sent_at) + DROP_GRACE_S
    with _pending_lock:
        _pending.append({"ip": ip, "cue": str(cue), "sent": sent_at, "due": due})


def _ack_seen(ip, cue):
    """Clear expectations satisfied by an incoming /debug/rx."""
    cue = str(cue)
    with _pending_lock:
        _pending[:] = [p for p in _pending
                       if not (p["ip"] == ip and p["cue"] == cue)]


def _drop_reaper():
    """Emit /debug/drop for expectations that timed out unacked."""
    while True:
        time.sleep(0.5)
        now = master_now()
        overdue = []
        with _pending_lock:
            keep = []
            for p in _pending:
                (overdue if now > p["due"] else keep).append(p)
            _pending[:] = keep
        for p in overdue:
            # src "server" so the page can tell an inference from a device report.
            _debug_add("/debug/drop", [p["cue"], p["ip"], p["sent"]], "server")
            print(f"  DROP  {p['cue']}  ->  {p['ip']}  (no ack after "
                  f"{DROP_GRACE_S}s)")


# ---- object transforms pulled off a headset ---------------------------------
# The in-headset Object Ctrl panel saves the positions it has been nudged to,
# but only into that device's own storage - so a placement dialled in on one
# pair of glasses is invisible everywhere else and dies with the app. This
# fetches the file over the same wireless adb link the video recorder uses, so
# the numbers can be read, kept, and eventually written into the show.
#
# The package name is not hardcoded: the show, debug and tablet builds each
# have their own, and guessing wrong would report "no positions saved" for a
# file that exists.
TRANSFORM_FILE = "ObjectTransforms.json"


def pull_object_transforms(ip):
    """Return (json_text, source_path) from the headset, or (None, reason)."""
    serial = f"{ip}:5555"
    r = _adb(["connect", serial], 10)
    if "connected" not in (r.stdout or ""):
        return None, (f"cannot reach {serial} over wireless adb - it resets on "
                      f"every device reboot, so plug into USB once and re-enable")
    found = _adb(["-s", serial, "shell",
                  f"ls /sdcard/Android/data/*/files/{TRANSFORM_FILE} 2>/dev/null"], 15)
    paths = [ln.strip() for ln in (found.stdout or "").splitlines()
             if ln.strip().endswith(TRANSFORM_FILE)]
    if not paths:
        return None, ("no saved positions on that device yet - open Object Ctrl "
                      "on the headset and press Save first")
    # Newest wins if several builds are installed side by side.
    src = paths[-1]
    got = _adb(["-s", serial, "shell", "cat", src], 20)
    text = (got.stdout or "").strip()
    if not text:
        return None, f"found {src} but it was empty"
    return text, src


# ---- shared controller settings ---------------------------------------------
# The IP list, audience groups and port live in each browser's localStorage,
# which means a second operator station starts life pointed at 127.0.0.1 and
# silently cues nothing. Holding a copy HERE gives a fresh browser something
# correct to adopt, so a new machine is useful immediately instead of after
# somebody remembers to export and import a file.
#
# Deliberately a copy, not the authority: a station may legitimately differ,
# for instance when one operator drives only audience B. The page adopts this
# when it has no settings of its own, and otherwise only on request.
SETTINGS_FILE = os.path.join(ROOT, "controller-settings.json")
_settings_lock = threading.Lock()


def read_shared_settings():
    try:
        with open(SETTINGS_FILE, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return None
    except Exception as e:
        print(f"  controller-settings.json unreadable ({e}) - ignoring")
        return None


def write_shared_settings(data):
    tmp = SETTINGS_FILE + ".tmp"
    with _settings_lock:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
        os.replace(tmp, SETTINGS_FILE)     # atomic: a crash cannot truncate it


# ---- audio reactivity presets ----------------------------------------------
# Per-cue reactive intensity, kept HERE rather than on the headsets.
#
# A value discovered on one headset is useless to the other five, and a value
# that lives only in a device's memory dies with the app. Holding the map on the
# server means every device gets it, it survives restarts, it applies whether
# the show is driven from the controller page or from QLab, and it is a plain
# file that can be committed and handed to someone else.
#
# Applied at send time: when a cue goes out and a preset exists for it, the
# intensity is set on that device immediately before the cue itself, so the
# effect is already correct on the frame the cue lands.
PRESETS_FILE = os.path.join(ROOT, "audio-presets.json")
_last_cue = ""              # most recently fired cue id — what "pin" attaches to
_presets_lock = threading.Lock()
_presets = {}


def load_presets():
    global _presets
    try:
        with open(PRESETS_FILE, encoding="utf-8") as f:
            data = json.load(f)
        with _presets_lock:
            _presets = {str(k): float(v) for k, v in data.get("cues", {}).items()}
        print(f"  Audio      : {len(_presets)} cue preset(s) from audio-presets.json")
    except FileNotFoundError:
        pass
    except Exception as e:
        print(f"  audio presets unreadable ({e}) — starting empty")


def save_presets():
    with _presets_lock:
        data = {"_comment": "Per-cue audio reactivity, percent. 100 = as authored.",
                "cues": dict(sorted(_presets.items()))}
    tmp = PRESETS_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
    os.replace(tmp, PRESETS_FILE)      # atomic, so a crash cannot truncate it


def preset_for(cue):
    with _presets_lock:
        return _presets.get(str(cue))


# ---- QLab bridge -----------------------------------------------------------
# QLab speaks OSC; the controller page speaks HTTP. This listener lets QLab (or
# anything else that sends OSC) fire a cue through exactly the same path the
# page uses, so scheduling, the 3x redundancy, the ack roster and drop detection
# all still apply.
#
# QLab is deliberately NOT pointed straight at the headsets. A QLab network cue
# sends one message, once, with no timestamp — which would lose both the
# redundancy and the scheduled playAt that keeps multiple headsets in sync with
# each other. Sending to this server instead keeps QLab as the operator surface
# while the server stays the delivery engine.
QLAB_PORT = int(os.environ.get("QLAB_PORT", 53000))

# Where to send. The page holds the IP list in browser storage, so the server
# has to work it out: any device that has reported in recently is live and
# wants cues. A devices.txt beside this file (one IP per line) overrides that,
# for the case where nothing has reported yet — a cold start with no page open
# would otherwise have an empty roster and silently cue nobody.
DEVICES_FILE = os.path.join(ROOT, "devices.txt")
DEVICE_SEEN_SECONDS = 30.0


def _configured_devices():
    try:
        with open(DEVICES_FILE) as f:
            return [l.strip() for l in f
                    if l.strip() and not l.strip().startswith("#")]
    except Exception:
        return []


def _live_devices():
    """IPs that have sent us any /debug/* traffic recently."""
    cutoff = master_now() - DEVICE_SEEN_SECONDS
    with _debug_lock:
        return sorted({e["from"] for e in _debug_events
                       if e["t"] >= cutoff and e["from"] not in ("server",)})


def qlab_targets():
    cfg = _configured_devices()
    return cfg if cfg else _live_devices()


def qlab_listener():
    """UDP :53000 — let QLab drive the show.

    Accepts the cue id three ways, because which one is convenient depends on
    how the operator built the QLab cue, and a test that fails purely on
    message shape teaches nothing:

        /joan/cue  "B_201"      argument
        /joan/cue/B_201         in the address, no argument
        /audio/cue "B_201"      identical to what the headsets themselves take
    """
    from pythonosc import dispatcher, osc_server

    def fire(addr, *args):
        cue = None
        if args and str(args[0]).strip():
            cue = str(args[0]).strip()
        elif addr.count("/") >= 3:
            cue = addr.rsplit("/", 1)[-1]        # /joan/cue/B_201
        if not cue:
            _server_log("warn", f"QLab sent {addr} with no cue id")
            return

        targets = qlab_targets()
        if not targets:
            _server_log("error", "QLab cue ignored — no devices known. Open the "
                                 "controller page once, or add IPs to devices.txt")
            return

        port = 7000
        lead_ms = 400
        for host in targets:
            try:
                sent_at = master_now()
                globals()["_last_cue"] = str(cue)
                _expect_ack(host, cue, sent_at, sent_at + lead_ms / 1000.0)
                pct = preset_for(cue)
                if pct is not None:
                    send_osc(host, port, "/debug/audiogain", [int(pct)])
                my_ip = local_ip_for(host)
                if my_ip:
                    send_osc(host, port, "/clock/master", [my_ip])
                sent_at = master_now()
                play_at = sent_at + lead_ms / 1000.0
                for i in range(3):
                    send_osc(host, port, "/audio/cue", [cue, play_at])
                    if i < 2:
                        time.sleep(0.05)
            except Exception as e:
                _server_log("error", f"QLab fan-out to {host} failed: {e}")
        print(f"  QLAB -> {cue}  to {len(targets)} device(s): {', '.join(targets)}")
        _debug_add("/debug/log", ["QLAB", master_now(), "info",
                                  f"cue {cue} -> {len(targets)} device(s)"], "server")

    disp = dispatcher.Dispatcher()
    disp.map("/joan/cue", fire)
    disp.map("/joan/cue/*", fire)
    disp.map("/audio/cue", fire)
    disp.set_default_handler(lambda a, *x: _server_log(
        "warn", f"QLab sent an unhandled address: {a}"))
    try:
        srv = osc_server.BlockingOSCUDPServer(("0.0.0.0", QLAB_PORT), disp)
        srv.serve_forever()
    except Exception as e:
        print(f"  QLab listener error: {e}")


def build_debug_listener():
    """UDP :9002 - collect /debug/* reports from headsets & performer tablets.

    BUILDS the server rather than running it, so the bind happens in the main
    thread where a failure can stop startup. See _die_port_in_use for why that
    matters more than it looks.
    """
    from pythonosc import dispatcher, osc_server

    disp = dispatcher.Dispatcher()

    def handle(client_address, addr, *args):
        ip = client_address[0]
        # /debug/rx is [deviceId, cueId, recvMaster, playAt] — the ack that
        # settles a pending expectation before the reaper can call it a drop.
        if addr == "/debug/rx" and len(args) >= 2:
            _ack_seen(ip, args[1])
        _debug_add(addr, args, ip)

    disp.set_default_handler(handle, needs_reply_address=True)
    return osc_server.BlockingOSCUDPServer(("0.0.0.0", DEBUG_PORT), disp)


def clock_responder(sock):
    """UDP :9001 — answer /clock/ping [seq] with /clock/pong [seq, masterTime].

    Takes an already-bound socket for the same reason as the debug listener.
    """
    while True:
        try:
            data, addr = sock.recvfrom(512)
            if b"/clock/ping" not in data:
                continue
            # Grab the seq int (last 4 bytes of a ,i message) without a full parser.
            seq = int.from_bytes(data[-4:], "big", signed=True)
            reply = OscMessageBuilder(address="/clock/pong")
            reply.add_arg(seq)
            reply.add_arg(master_now(), OscMessageBuilder.ARG_TYPE_DOUBLE)
            sock.sendto(reply.build().dgram, addr)
        except Exception as e:
            print(f"  clock responder error: {e}")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):               # silence the default access log
        pass

    # ---- CORS (harmless; also lets the page work if opened as a file://) ----
    def _cors(self):
        self.send_header("Access-Control-Allow-Origin",  "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")

    def do_OPTIONS(self):
        self.send_response(200)
        self._cors()
        self.end_headers()

    # ---- 1) Serve the controller page + any static file in this folder ----
    def do_GET(self):
        path = unquote(urlparse(self.path).path)

        # Debug event feed for the controller's logger panel.
        if path == "/object-transforms":
            q = urlparse(self.path).query
            ip = ""
            for part in q.split("&"):
                if part.startswith("ip="):
                    ip = unquote(part[3:])
            if not ip:
                self.send_error(400, "need ?ip=<device>")
                return
            text, info = pull_object_transforms(ip)
            if text is None:
                _server_log("error", f"object positions: {info}")
                payload = json.dumps({"ok": False, "error": info}).encode()
                code = 404
            else:
                _server_log("info", f"object positions pulled from {ip} ({info})")
                payload = json.dumps({"ok": True, "source": info,
                                      "device": ip, "transforms": text}).encode()
                code = 200
            self.send_response(code); self._cors()
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers(); self.wfile.write(payload)
            return

        if path == "/settings":
            data = read_shared_settings()
            payload = json.dumps(data if data is not None else {}).encode()
            self.send_response(200); self._cors()
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers(); self.wfile.write(payload)
            return

        if path == "/audio-presets":
            with _presets_lock:
                payload = json.dumps({"cues": dict(sorted(_presets.items()))}).encode()
            self.send_response(200); self._cors()
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers(); self.wfile.write(payload)
            return

        if path == "/debug/events":
            try:
                qs = urlparse(self.path).query
                since = 0
                for kv in qs.split("&"):
                    if kv.startswith("since="):
                        since = int(kv.split("=", 1)[1] or 0)
                with _debug_lock:
                    events = [e for e in _debug_events if e["seq"] > since]
                    seq = _debug_seq
                payload = json.dumps({"seq": seq, "now": master_now(),
                                      "events": events}).encode("utf-8")
            except Exception as e:
                payload = json.dumps({"seq": 0, "events": [], "error": str(e)}).encode("utf-8")
            self.send_response(200)
            self._cors()
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return

        # Latest snapshot for one device: /debug/snapshot?id=<device>
        if path == "/debug/snapshot":
            dev = ""
            for kv in urlparse(self.path).query.split("&"):
                if kv.startswith("id="):
                    dev = unquote(kv.split("=", 1)[1])
            with _snapshot_lock:
                snap = _snapshots.get(dev)
            if not snap:
                self.send_error(404, "No snapshot for that device yet")
                return
            self.send_response(200)
            self._cors()
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(snap["jpg"])))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(snap["jpg"])
            return

        # Latest glasses recording (see _record_thread).
        if path == "/debug/video":
            with _video_lock:
                vid = _video
            if not vid:
                self.send_error(404, "No recording yet")
                return
            self.send_response(200)
            self._cors()
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Length", str(len(vid["mp4"])))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(vid["mp4"])
            return

        if path in ("/", ""):
            path = "/" + DEFAULT_PAGE

        target = os.path.normpath(os.path.join(ROOT, path.lstrip("/")))
        # Never serve anything outside this folder.
        if not target.startswith(ROOT) or not os.path.isfile(target):
            self.send_error(404, "Not found")
            return

        ext = os.path.splitext(target)[1].lower()
        with open(target, "rb") as f:
            body = f.read()
        self.send_response(200)
        self.send_header("Content-Type", CONTENT_TYPES.get(ext, "application/octet-stream"))
        self.send_header("Content-Length", str(len(body)))
        # Never let a browser cache the controller. A stale page is genuinely
        # dangerous here: it can keep talking to an old origin/port and return
        # 501s, or run last week's cue logic, while looking perfectly healthy.
        # This is why "hard-refresh" used to be needed — now it isn't.
        if ext in (".html", ".htm", ".js", ".css"):
            self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
            self.send_header("Pragma", "no-cache")
            self.send_header("Expires", "0")
        self.end_headers()
        self.wfile.write(body)

    # ---- 2) Bridge one button press to one OSC/UDP message ----
    def do_POST(self):
        # Headsets upload /debug/snap captures here as raw JPEG bodies.
        if urlparse(self.path).path == "/debug/snapshot":
            dev = "device"
            for kv in urlparse(self.path).query.split("&"):
                if kv.startswith("id="):
                    dev = unquote(kv.split("=", 1)[1]) or dev
            try:
                length = int(self.headers.get("Content-Length", 0))
                if length <= 0 or length > 8_000_000:
                    self.send_error(400, "Bad snapshot size")
                    return
                jpg = self.rfile.read(length)
            except Exception:
                self.send_error(400, "Bad snapshot body")
                return
            with _snapshot_lock:
                n = _snapshots.get(dev, {}).get("n", 0) + 1
                _snapshots[dev] = {"jpg": jpg, "t": master_now(), "n": n}
            # Announce on the debug feed so the panel refreshes its thumbnail.
            _debug_add("/debug/snapshot", [dev, len(jpg)],
                       self.client_address[0] if self.client_address else "?")
            payload = json.dumps({"ok": True, "bytes": len(jpg)}).encode("utf-8")
            self.send_response(200)
            self._cors()
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return

        # Start a wireless screen recording of one device (adb screenrecord).
        if urlparse(self.path).path == "/settings":
            try:
                length = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(length) or b"{}")
            except Exception:
                self.send_error(400, "Bad JSON")
                return
            if not isinstance(body, dict) or not body.get("ipList"):
                # Refuse to store a settings blob with no devices in it. That
                # is the one shape guaranteed to be useless, and it would be
                # handed to every new station as if it were correct.
                payload = json.dumps({"ok": False,
                                      "error": "refusing to save settings with an empty ipList"}).encode()
                self.send_response(400); self._cors()
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers(); self.wfile.write(payload)
                return
            write_shared_settings(body)
            n = len(body.get("ipList") or [])
            _server_log("info", f"controller settings saved - {n} device IP(s)")
            payload = json.dumps({"ok": True, "ipCount": n}).encode()
            self.send_response(200); self._cors()
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers(); self.wfile.write(payload)
            return

        if urlparse(self.path).path == "/audio-presets":
            try:
                length = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(length) or b"{}")
            except Exception:
                self.send_error(400, "Bad JSON")
                return
            global _presets
            cue = body.get("cue") or (_last_cue if "pin" in body else None)
            if "pin" in body or body.get("cue"):
                # Pin one value against one cue. Refuse rather than guess: a
                # value silently attached to the wrong cue is worse than a
                # value not saved at all, because nobody finds out until the
                # effect fires in the wrong place during a show.
                if not cue:
                    payload = json.dumps({"ok": False,
                                          "error": "no cue has been fired yet — nothing to pin to"}).encode()
                    self.send_response(409); self._cors()
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers(); self.wfile.write(payload)
                    return
                pct = float(body.get("pin", body.get("pct", 100)))
                with _presets_lock:
                    if pct == 100:
                        _presets.pop(str(cue), None)   # 100% IS as-authored: store nothing
                    else:
                        _presets[str(cue)] = pct
                    n = len(_presets)
                save_presets()
                _server_log("info", f"audio {pct:.0f}% pinned to {cue} ({n} cue(s) saved)")
                payload = json.dumps({"ok": True, "cue": cue, "pct": pct, "count": n}).encode()
                self.send_response(200); self._cors()
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers(); self.wfile.write(payload)
                return
            with _presets_lock:
                _presets = {str(k): float(v) for k, v in (body.get("cues") or {}).items()}
                n = len(_presets)
            save_presets()
            _server_log("info", f"audio presets saved — {n} cue(s)")
            payload = json.dumps({"ok": True, "count": n}).encode()
            self.send_response(200); self._cors()
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers(); self.wfile.write(payload)
            return

        if urlparse(self.path).path == "/debug/record":
            global _video_busy
            try:
                length = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(length) or b"{}")
                ip = str(body.get("ip", "")).strip()
                sec = max(2, min(180, int(body.get("sec", 10))))
            except Exception:
                self.send_error(400, "Bad JSON")
                return
            if not ip:
                self.send_error(400, "Missing device ip")
                return
            with _video_lock:
                if _video_busy:
                    payload = json.dumps({"ok": False, "error": "already recording"}).encode()
                else:
                    _video_busy = True
                    threading.Thread(target=_record_thread, args=(ip, sec),
                                     daemon=True).start()
                    payload = json.dumps({"ok": True, "recording": sec}).encode()
            self.send_response(200)
            self._cors()
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return

        if urlparse(self.path).path != "/send":
            self.send_error(404, "Not found")
            return

        try:
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length) or b"{}")
        except Exception:
            self.send_error(400, "Bad JSON")
            return

        host = body.get("host",    "127.0.0.1")
        port = int(body.get("port", 7000))
        addr = body.get("address", "/cue")
        raw  = body.get("value",   1)

        # Preserve the JSON value's type so OSC data stays correct:
        #   bool/int/float pass through; a numeric-looking string becomes an int
        #   (so cue numbers reach IntValue receivers in Unity); any other string
        #   stays a string (so ids like "VQ101" travel as OSC strings).
        if isinstance(raw, (bool, int, float)):
            value = raw
        elif isinstance(raw, str):
            s = raw.strip()
            value = int(s) if s.lstrip("-").isdigit() else s
        else:
            value = str(raw)

        # "/clock/master" with value "auto": substitute this server's LAN IP.
        # The page announces the master to configured devices every few
        # seconds (roster bootstrap — devices can't heartbeat or clock-sync
        # until they know the master's address, which previously arrived
        # only with the first scheduled cue). The browser doesn't know the
        # server's LAN address; this fills it in per target.
        if addr == "/clock/master" and str(value).strip().lower() == "auto":
            value = local_ip_for(host)
            if not value:
                payload = json.dumps({"ok": False, "error": "no route to host"}).encode()
                self.send_response(200)
                self._cors()
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
                return

        # --- NEW way (sync mode): schedule audio cues on the master clock. ---
        # If the page sends leadMs and the address is an audio one, we append
        # playAt (master monotonic seconds, as an OSC double) and send the
        # message 3x at 50 ms spacing. Receivers dedupe by (cueId, playAt), so
        # the repeats are pure loss insurance. Without leadMs -> OLD way,
        # byte-identical to previous behavior.
        lead_ms = body.get("leadMs")
        schedule = lead_ms is not None and str(addr).startswith("/audio/")

        try:
            play_at = None
            sent_at = master_now()   # master-clock send time (for the debug logger)

            # Register the expectation BEFORE the packet goes out. A headset on
            # the same LAN acks in a couple of milliseconds, while the send path
            # below sleeps 100ms between its three repeats — so registering
            # afterwards means the ack arrives first, finds nothing to clear,
            # and the expectation is then created already orphaned. That raced
            # every single time, reporting a drop for every cue that in fact
            # arrived perfectly.
            if str(addr).startswith("/audio/"):
                globals()["_last_cue"] = str(value)
                lead_s = (float(lead_ms) / 1000.0) if schedule else 0.0
                _expect_ack(host, value, sent_at, sent_at + lead_s if schedule else None)
                # Reactive intensity for this cue, if one has been set. Sent
                # before the cue so the effect is already right on the frame it
                # lands, rather than snapping a moment later.
                pct = preset_for(value)
                if pct is not None:
                    send_osc(host, port, "/debug/audiogain", [int(pct)])

            if schedule:
                # Announce the master's IP so receivers (Unity) know where to
                # send /clock/ping — the tablet app auto-learns from the packet
                # source, but extOSC can't see sender addresses.
                my_ip = local_ip_for(host)
                if my_ip:
                    send_osc(host, port, "/clock/master", [my_ip])
                sent_at = master_now()
                play_at = sent_at + float(lead_ms) / 1000.0
                for i in range(3):
                    send_osc(host, port, addr, [value, play_at])
                    if i < 2:
                        time.sleep(0.05)
                print(f"  OSC -> {host}:{port}  {addr}  {value!r}  playAt=+{lead_ms}ms x3")
            else:
                udp_client.SimpleUDPClient(host, port).send_message(addr, value)
                if addr != "/clock/master":   # periodic announces would drown the log
                    print(f"  OSC -> {host}:{port}  {addr}  {value!r}")
            # sentAt/playAt are master-clock seconds so the controller's debug
            # log can stamp sent messages on the same scale as device replies.
            resp = {"ok": True, "sentAt": sent_at}
            if play_at is not None:
                resp["playAt"] = play_at
            code, payload = 200, json.dumps(resp).encode("utf-8")
        except Exception as e:
            print(f"  OSC send FAILED ({host}:{port}): {e}")
            code, payload = 500, b'{"ok":false}'

        self.send_response(code)
        self._cors()
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def _die_port_in_use(port, what, err):
    """Stop dead when a listener port is taken, instead of carrying on deaf.

    Every listener used to bind inside its own daemon thread and print a single
    line on failure, which scrolled away behind the startup banner. What that
    produced was a server that served the controller page perfectly while being
    unable to hear anything: cues went OUT fine, so the show appeared to work,
    but no heartbeat, no acknowledgement and no device ever reached it. The
    roster sat empty and the drop detector blamed the headsets for a fault that
    was entirely on this machine.

    That is worse than refusing to start, because every visible signal says the
    system is healthy. It cost an hour to find once. Mid-show it would cost the
    show. So: refuse, and say exactly what to do about it.
    """
    print()
    print("=" * 70)
    print(f"  CANNOT START - UDP port {port} is already in use.")
    print()
    print(f"  That port carries {what}.")
    print()
    print("  Almost always this is another cue server still running - an old")
    print("  window, or one started on a different HTTP port so it did not")
    print("  look like a conflict. Close it, then start this one again.")
    print()
    print("  Find it:")
    print("    Windows   netstat -ano | findstr \":900\"")
    print("    macOS     lsof -nP -iUDP:9001 -iUDP:9002")
    print()
    print(f"  ({err})")
    print("=" * 70)
    print()
    raise SystemExit(1)


def main():
    print("Joan of the City - Fused OSC Cue Server")
    print(f"  Controller : http://localhost:{PORT}/")
    print(f"  From LAN   : http://<this-device-ip>:{PORT}/")
    print(f"  OSC bridge : POST /send  ->  UDP to the headsets")
    print(f"  Clock sync : UDP :{CLOCK_PORT}  /clock/ping -> /clock/pong")
    print(f"  Serving    : {ROOT}")
    print("  Press Ctrl+C to stop.\n")
    print(f"  Debug feed : UDP :{DEBUG_PORT}  /debug/* -> GET /debug/events")
    print(f"  Drop watch : cue unacked {DROP_GRACE_S}s after playAt -> /debug/drop")
    _shared = read_shared_settings()
    if _shared:
        print(f"  Settings   : sharing {len(_shared.get('ipList') or [])} device IP(s) with new browsers")
    print(f"  QLab       : UDP :{QLAB_PORT}  /joan/cue <id>  -> scheduled fan-out")
    # Bind the two ports the SHOW depends on before anything else, so a
    # conflict is a refusal rather than a silent deafness.
    clock_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        clock_sock.bind(("", CLOCK_PORT))
    except OSError as e:
        _die_port_in_use(CLOCK_PORT, "clock sync - without it, scheduled cues "
                                     "do not land together across headsets", e)
    try:
        debug_srv = build_debug_listener()
    except OSError as e:
        _die_port_in_use(DEBUG_PORT, "every report coming back from the devices: "
                                     "the roster, heartbeats, and the cue "
                                     "acknowledgements the drop detector needs", e)

    threading.Thread(target=clock_responder, args=(clock_sock,), daemon=True).start()
    threading.Thread(target=debug_srv.serve_forever, daemon=True).start()
    threading.Thread(target=_drop_reaper, daemon=True).start()
    load_presets()
    threading.Thread(target=qlab_listener, daemon=True).start()

    try:
        ThreadingHTTPServer(("", PORT), Handler).serve_forever()
    except OSError as e:
        # The HTTP port is the one people already understand, so this stays a
        # plain message rather than the full banner above.
        print()
        print(f"  CANNOT START - HTTP port {PORT} is already in use ({e}).")
        print(f"  A controller is probably already open at http://localhost:{PORT}/")
        print()
        raise SystemExit(1)


if __name__ == "__main__":
    main()
