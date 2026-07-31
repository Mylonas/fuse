#!/usr/bin/env bash
# Installs the debug APK on the running emulator, plays it with synthetic touch
# input, and captures screenshots, logcat and the frame rate the game reports.
#
#   play.sh <package> [drag|hold]
set -uo pipefail

PKG="$1"
STYLE="${2:-drag}"
OUT="artifacts"
mkdir -p "$OUT/shots"

shot() { adb exec-out screencap -p > "$OUT/shots/$1.png"; echo "  shot $1"; }

echo "== waiting for the device to settle =="
adb wait-for-device
adb shell input keyevent 82 >/dev/null 2>&1 || true

# Suppress Android's one-time "Viewing full screen" overlay. It covers the top
# of every screenshot, and on a loaded emulator its window ANRs waiting for a
# focus event, which looks alarming and has nothing to do with the game.
adb shell settings put secure immersive_mode_confirmations confirmed

echo "== installing =="
adb install -r -t app/build/outputs/apk/debug/app-debug.apk

SIZE=$(adb shell wm size | tail -1 | tr -d '\r' | sed 's/.*: *//')
W=${SIZE%x*}
H=${SIZE#*x}
CX=$((W / 2))
echo "== screen ${W}x${H} =="

adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"
sleep 6
shot 01-title

echo "== playing ($STYLE) =="
# start the run
adb shell input tap $CX $((H * 3 / 4))
sleep 2
shot 02-run-started

if [ "$STYLE" = "hold" ]; then
  # Tether: alternate long holds (grapple + reel) with short gaps (flight).
  for i in $(seq 1 14); do
    adb shell input swipe $CX $((H / 2)) $CX $((H / 2)) $((400 + RANDOM % 700))
    sleep 0.3
    if [ "$i" = "3" ]; then shot 03-mid-climb; fi
    if [ "$i" = "8" ]; then shot 04-later; fi
  done
else
  # Fuse: swipe to spin the shield, tap to swap the colours.
  for i in $(seq 1 14); do
    DIR=$((RANDOM % 2))
    if [ "$DIR" = "0" ]; then
      adb shell input swipe $((CX - 300)) $((H * 2 / 3)) $((CX + 300)) $((H * 2 / 3)) 250
    else
      adb shell input swipe $((CX + 300)) $((H * 2 / 3)) $((CX - 300)) $((H * 2 / 3)) 250
    fi
    adb shell input tap $CX $((H * 2 / 3))
    if [ "$i" = "3" ]; then shot 03-mid-run; fi
    if [ "$i" = "8" ]; then shot 04-later; fi
    sleep 0.4
  done
fi

echo "== letting the run play out =="
sleep 25
shot 05-after-25s

# whatever state we are in, one more tap should be a legal input
adb shell input tap $CX $((H * 3 / 4))
sleep 3
shot 06-after-tap

echo "== collecting =="
adb logcat -d > "$OUT/logcat.txt"   # session log, captured before the baseline run
adb shell dumpsys meminfo "$PKG" > "$OUT/meminfo.txt" 2>&1 || true

echo
echo "== frame rate reported by the game =="
grep -h "fps=" "$OUT/logcat.txt" | tail -20 | tee "$OUT/fps.txt" || echo "(no fps lines)"

echo
echo "== crash check =="
FAIL=0
if grep -qE "FATAL EXCEPTION|AndroidRuntime: .*Exception" "$OUT/logcat.txt"; then
  echo "CRASH DETECTED:"
  grep -A 25 -E "FATAL EXCEPTION" "$OUT/logcat.txt" | head -60
  FAIL=1
fi
if grep -q "ANR in $PKG" "$OUT/logcat.txt"; then
  echo "ANR DETECTED IN THE GAME"
  grep -A 10 "ANR in $PKG" "$OUT/logcat.txt" | head -30
  FAIL=1
fi
# A system_server ANR means we starved the whole emulator, which is worth
# seeing even though it is not our process.
if grep -q "ANR in " "$OUT/logcat.txt"; then
  echo "NOTE: system-wide ANR observed:"
  grep "ANR in " "$OUT/logcat.txt" | head -5
fi

PID=$(adb shell pidof "$PKG" | tr -d '\r')
if [ -z "$PID" ]; then
  echo "PROCESS IS GONE - the app died during the run"
  FAIL=1
else
  echo "process alive (pid $PID) after the full session"
fi

# Anything the game itself logged as a problem
grep -iE "$PKG.*(error|failed)" "$OUT/logcat.txt" | head -10 || true

echo
echo "== baseline: identical loop, background only =="
# Attributes the frame time: if clearing the screen alone costs the same as a
# full frame, the cost is the rasterizer, not the game's drawing.
adb shell am force-stop "$PKG"
sleep 2
adb logcat -c
adb shell am start -n "$PKG/.MainActivity" --ez baseline true >/dev/null
sleep 14
adb logcat -d | grep "fps=" | tail -4 | tee "$OUT/fps-baseline.txt"
adb shell am force-stop "$PKG"

exit $FAIL
