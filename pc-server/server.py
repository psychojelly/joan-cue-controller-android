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
CLOCK_PORT = 9001                               # UDP: /clock/ping -> /clock/pong
DEBUG_PORT = 9002                               # UDP: /debug/* reports from devices
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
            devs = _adb(["devices"], 10).stdout or ""
            usb = [ln.split()[0] for ln in devs.splitlines()[1:]
                   if ln.strip().endswith("device") and ":" not in ln.split()[0]]
            if usb:
                _server_log("info", f"enabling adb-over-WiFi via USB ({usb[0]})…")
                _adb(["-s", usb[0], "tcpip", "5555"], 10)
                time.sleep(2)
                r = _adb(["connect", serial], 10)
            if "connected" not in (r.stdout or ""):
                _server_log("error",
                    f"record: can't reach {serial} — plug the device into USB once "
                    f"per boot so I can enable wireless adb, then retry")
                return

        # Pick the display: with glasses attached a second physical display
        # appears — record that one; otherwise the primary (phone screen).
        out = _adb(["-s", serial, "shell", "dumpsys", "SurfaceFlinger",
                    "--display-id"], 10).stdout or ""
        ids = re.findall(r"Display (\d+)", out)
        use_glasses = len(ids) > 1
        display = ids[-1] if use_glasses else (ids[0] if ids else None)
        label = "glasses display" if use_glasses else "primary display (no glasses detected)"
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
        while len(_debug_events) > DEBUG_RING:
            _debug_events.pop(0)


def debug_listener():
    """UDP :9002 - collect /debug/* reports from headsets & performer tablets."""
    from pythonosc import dispatcher, osc_server

    disp = dispatcher.Dispatcher()

    def handle(client_address, addr, *args):
        _debug_add(addr, args, client_address[0])

    disp.set_default_handler(handle, needs_reply_address=True)
    try:
        server = osc_server.BlockingOSCUDPServer(("0.0.0.0", DEBUG_PORT), disp)
        server.serve_forever()
    except Exception as e:
        print(f"  debug listener error: {e}")


def clock_responder():
    """UDP :9001 — answer /clock/ping [seq] with /clock/pong [seq, masterTime]."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("", CLOCK_PORT))
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


def main():
    print("Joan of the City - Fused OSC Cue Server")
    print(f"  Controller : http://localhost:{PORT}/")
    print(f"  From LAN   : http://<this-device-ip>:{PORT}/")
    print(f"  OSC bridge : POST /send  ->  UDP to the headsets")
    print(f"  Clock sync : UDP :{CLOCK_PORT}  /clock/ping -> /clock/pong")
    print(f"  Serving    : {ROOT}")
    print("  Press Ctrl+C to stop.\n")
    print(f"  Debug feed : UDP :{DEBUG_PORT}  /debug/* -> GET /debug/events")
    threading.Thread(target=clock_responder, daemon=True).start()
    threading.Thread(target=debug_listener, daemon=True).start()
    ThreadingHTTPServer(("", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
