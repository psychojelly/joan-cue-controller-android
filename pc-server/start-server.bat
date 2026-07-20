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

python server.py
echo.
echo [server stopped]
pause
