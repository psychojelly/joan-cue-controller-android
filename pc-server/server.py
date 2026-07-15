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

        # --- NEW way (sync mode): schedule audio cues on the master clock. ---
        # If the page sends leadMs and the address is an audio one, we append
        # playAt (master monotonic seconds, as an OSC double) and send the
        # message 3x at 50 ms spacing. Receivers dedupe by (cueId, playAt), so
        # the repeats are pure loss insurance. Without leadMs -> OLD way,
        # byte-identical to previous behavior.
        lead_ms = body.get("leadMs")
        schedule = lead_ms is not None and str(addr).startswith("/audio/")

        try:
            if schedule:
                # Announce the master's IP so receivers (Unity) know where to
                # send /clock/ping — the tablet app auto-learns from the packet
                # source, but extOSC can't see sender addresses.
                my_ip = local_ip_for(host)
                if my_ip:
                    send_osc(host, port, "/clock/master", [my_ip])
                play_at = master_now() + float(lead_ms) / 1000.0
                for i in range(3):
                    send_osc(host, port, addr, [value, play_at])
                    if i < 2:
                        time.sleep(0.05)
                print(f"  OSC -> {host}:{port}  {addr}  {value!r}  playAt=+{lead_ms}ms x3")
            else:
                udp_client.SimpleUDPClient(host, port).send_message(addr, value)
                print(f"  OSC -> {host}:{port}  {addr}  {value!r}")
            code, payload = 200, b'{"ok":true}'
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
    threading.Thread(target=clock_responder, daemon=True).start()
    ThreadingHTTPServer(("", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
