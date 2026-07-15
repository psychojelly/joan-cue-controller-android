@echo off
REM Launch the Joan test tablet (Android emulator).
REM The virtual tablet appears as a window; the Joan Cues app is installed on it.
set ANDROID_SDK_ROOT=C:\Android\sdk
start "" "C:\Android\sdk\emulator\emulator.exe" -avd joan_tablet -no-snapshot -no-boot-anim
