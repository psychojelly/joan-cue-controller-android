#!/data/data/com.termux/files/usr/bin/bash
# Joan of the City — one-tap launcher for the fused OSC cue server (Termux).
#
# The Android equivalent of the .bat: checks deps, starts the server,
# keeps Android from killing it, and opens the controller in the browser.
#
# Setup (one time): see README-TABLET.md in this folder.

# Resolve this script's folder so it can run from anywhere (incl. Termux:Widget).
cd "$(dirname "$(realpath "$0")")" || exit 1

# --- First-run setup: install deps only if missing (like the .bat) ---------
command -v python >/dev/null 2>&1 || { echo "Installing Python..."; pkg install -y python; }
python -c "import pythonosc" 2>/dev/null || { echo "Installing python-osc..."; pip install python-osc; }

# --- Keep Android from suspending Termux mid-show ---------------------------
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock

# --- Start the server (skip if it's already running) ------------------------
if ! pgrep -f "python .*server.py" >/dev/null 2>&1; then
  echo "Starting cue server..."
  nohup python server.py >server.log 2>&1 &
  sleep 2
else
  echo "Cue server already running."
fi

# --- Open the controller page in the tablet's default browser ---------------
if command -v termux-open-url >/dev/null 2>&1; then
  termux-open-url "http://localhost:8765/"
else
  am start -a android.intent.action.VIEW -d "http://localhost:8765/" >/dev/null 2>&1
fi

echo ""
echo "Controller: http://localhost:8765/  (this tablet)"
echo "From other devices: http://<this-tablet-ip>:8765/"
echo "Log: $(pwd)/server.log — stop with:  pkill -f server.py"
