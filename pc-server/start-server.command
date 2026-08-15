#!/bin/bash
# ═══════════════════════════════════════════════════════════════════
#  Joan of the City — one-click cue server (macOS)
#  Serves the controller page (HTTP :8765), the clock master (UDP
#  :9001), the device debug listener (UDP :9002), and the QLab
#  bridge (UDP :53000).
#  Double-click me in Finder. Keep this window open during the show.
#
#  The macOS twin of start-server.bat. Same behaviour, because the
#  operator should not have to know which machine is running the show
#  — including the already-running guard, which is the part that
#  actually matters.
# ═══════════════════════════════════════════════════════════════════

# Resolve this script's folder so double-clicking works regardless of
# what Finder sets the working directory to.
cd "$(dirname "$0")" || exit 1

printf '\033]0;Joan Cue Server\007'        # window title

# ── Python ──────────────────────────────────────────────────────────
if ! command -v python3 >/dev/null 2>&1; then
  echo "[!] python3 not found."
  echo "    Install the Xcode command line tools:  xcode-select --install"
  echo "    ...or get Python from https://www.python.org/downloads/macos/"
  echo
  read -r -p "Press Return to close."
  exit 1
fi

# ── The one dependency ──────────────────────────────────────────────
if ! python3 -c "import pythonosc" >/dev/null 2>&1; then
  echo "Installing the one dependency (python-osc)..."
  # --user first. Recent Python on macOS (Homebrew, and python.org 3.12+)
  # marks the system environment "externally managed" and refuses a plain
  # install with a wall of text that reads like a broken machine rather
  # than a policy — so fall back explicitly instead of dying there.
  python3 -m pip install --user python-osc 2>/dev/null \
    || python3 -m pip install --user --break-system-packages python-osc 2>/dev/null

  if ! python3 -c "import pythonosc" >/dev/null 2>&1; then
    echo
    echo "[!] python-osc still missing. Install it by hand, then re-run:"
    echo "      python3 -m pip install --user python-osc"
    echo "    If macOS refuses, make a virtualenv instead:"
    echo "      python3 -m venv .venv && .venv/bin/pip install python-osc"
    echo "      .venv/bin/python server.py"
    echo
    read -r -p "Press Return to close."
    exit 1
  fi
fi

# ── Refuse to start on top of a running server ──────────────────────
# Without this the second instance HALF-starts: the web page works, but
# the clock master (UDP 9001) and debug listener (UDP 9002) fail to bind,
# so scheduled-cue timing and every device report break silently. That is
# considerably worse than not starting — it looks fine until the show.
#
# The UDP ports are checked too, and that is the part that matters. A
# stray server on a DIFFERENT web port still holds 9001/9002, so 8765
# looks free and this one starts straight into the broken state. That
# exact case cost an hour on 15 Aug: cues fired, the headset played them,
# and every report went to a dead process while the roster sat empty.
if lsof -nP -iTCP:8765 -sTCP:LISTEN >/dev/null 2>&1 \
   || lsof -nP -iUDP:9001 >/dev/null 2>&1 \
   || lsof -nP -iUDP:9002 >/dev/null 2>&1; then
  echo
  echo "  [!] A cue server is ALREADY running (port 8765, 9001 or 9002)."
  echo
  echo "      Starting a second one would half-work: the page loads, but"
  echo "      cue timing and device reporting break silently."
  echo
  echo "      Quit the other server window, then run this again. Note it"
  echo "      may be on a different web port and still hold UDP 9001/9002."
  echo "      Find it with:  lsof -nP -iUDP:9001 -iUDP:9002"
  echo "      Already-open controller: http://localhost:8765"
  echo
  read -r -p "Press Return to close."
  exit 1
fi

# ── Where to open the controller ────────────────────────────────────
echo
echo "  Open the controller at one of these addresses"
echo "  (use the one on the same network as your devices):"
echo
ifconfig 2>/dev/null | awk '
  /^[a-z0-9]+:/ { iface = substr($1, 1, length($1) - 1) }
  /^[[:space:]]*inet / {
    if ($2 !~ /^127\./ && $2 !~ /^169\.254\./)
      printf "     http://%s:8765   (%s)\n", $2, iface
  }'
echo "     http://localhost:8765   (this computer)"
echo
echo "  Driving it from QLab on this Mac? Point a Network cue at"
echo "  127.0.0.1 port 53000, message:  /joan/cue \"B_201\""
echo
echo "  First run: macOS will ask whether to allow incoming connections."
echo "  Say Allow, or other devices will not reach the server."
echo

# Open the page in the background so the server still starts below. The
# delay matters: opening instantly hits the port before it is listening
# and greets the operator with a connection error.
( sleep 4; open "http://localhost:8765/" >/dev/null 2>&1 ) &

echo "  Opening the controller in your browser..."
echo

# -u = unbuffered. Without it Python buffers stdout, so the 'OSC -> ...'
# lines do not appear as cues fire and the window looks dead while it is
# in fact working perfectly.
python3 -u server.py

echo
echo "[server stopped]"
read -r -p "Press Return to close."
