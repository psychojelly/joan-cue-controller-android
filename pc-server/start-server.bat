@echo off
REM ═══════════════════════════════════════════════════════════════════
REM  Joan of the City — one-click cue server
REM  Serves the controller page (HTTP :8765), the clock master (UDP
REM  :9001), and the device debug listener (UDP :9002).
REM  Double-click me. Keep this window open while running the show.
REM ═══════════════════════════════════════════════════════════════════
title Joan Cue Server
cd /d "%~dp0"

where python >nul 2>nul
if errorlevel 1 (
  echo [!] Python not found. Install from python.org and tick "Add Python to PATH".
  pause
  exit /b 1
)

python -c "import pythonosc" >nul 2>nul
if errorlevel 1 (
  echo Installing the one dependency ^(python-osc^)...
  pip install python-osc
  python -c "import pythonosc" >nul 2>nul
  if errorlevel 1 (
    echo.
    echo [!] python-osc still missing. Install it by hand, then re-run:
    echo       pip install python-osc
    pause
    exit /b 1
  )
)

REM Refuse to start on top of a server that is already running. Without this the
REM second instance HALF-starts: the web page works, but the clock master
REM ^(UDP 9001^) and debug listener ^(UDP 9002^) fail to bind, so scheduled-sync
REM timing and device reporting break silently - much worse than not starting.
REM
REM Checks the UDP ports as well as 8765, and that is the important part. A
REM stray server started on a DIFFERENT http port still holds 9001/9002, so
REM 8765 looks free and this one starts straight into the broken state. That
REM exact case cost an hour on 15 Aug: cues fired, the headset played them,
REM and every report went to a dead process while the roster sat empty.
powershell -NoProfile -Command "if ((Get-NetTCPConnection -LocalPort 8765 -State Listen -ErrorAction SilentlyContinue) -or (Get-NetUDPEndpoint -LocalPort 9001 -ErrorAction SilentlyContinue) -or (Get-NetUDPEndpoint -LocalPort 9002 -ErrorAction SilentlyContinue)) { exit 1 } else { exit 0 }"
if errorlevel 1 (
  echo.
  echo  [!] A cue server is ALREADY running ^(port 8765, 9001 or 9002^).
  echo.
  echo      Starting a second one would half-work: the page loads, but cue
  echo      timing and device reporting break silently.
  echo.
  echo      Close the other server window, then run this again. Note it may
  echo      be on a different web port and still be holding UDP 9001/9002.
  echo      Find it with:  netstat -ano ^| findstr ":900"
  echo      Already-open controller: http://localhost:8765
  echo.
  pause
  exit /b 1
)

echo.
echo  Open the controller at one of these addresses
echo  ^(use the one on the same network as your devices^):
echo.
powershell -NoProfile -Command "(Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '169.*' -and $_.IPAddress -ne '127.0.0.1' }) | ForEach-Object { '     http://' + $_.IPAddress + ':8765   (' + $_.InterfaceAlias + ')' }"
echo      http://localhost:8765   (this computer)
echo.
echo  Tablet app download: same address + /joan-cues.apk
echo  If other devices can't connect, allow the ports once in an ADMIN
echo  PowerShell:
echo    netsh advfirewall firewall add rule name="Joan TCP" dir=in action=allow protocol=TCP localport=8765
echo    netsh advfirewall firewall add rule name="Joan UDP" dir=in action=allow protocol=UDP localport=9001-9002
echo.

REM Open the controller automatically, in a side process so the server still
REM starts below. Waits a few seconds first: opening instantly would hit the
REM page before the server is listening and show a connection error.
start "" /b cmd /c "timeout /t 4 /nobreak >nul & start "" http://localhost:8765/"

echo  Opening the controller in your browser...
echo.

REM -u = unbuffered. Without it Python buffers stdout, so the 'OSC -^> ...' lines
REM do not appear as cues fire and the window looks dead while it is working.
python -u server.py
echo.
echo [server stopped]
pause
