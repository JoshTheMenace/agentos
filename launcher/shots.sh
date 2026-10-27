#!/bin/sh
# Takes the device lock, drives the home through its screens and saves screenshots into launcher/shots/.
# Usage: launcher/shots.sh [names...]   (default: home center) ; names: home center blockers perms drawer
set -eu; cd "$(dirname "$0")"; S=${ANDROID_SERIAL:-ZY22J3TK8S}; A="adb -s $S shell"
tapText() { $A uiautomator dump /sdcard/ui.xml >/dev/null; adb -s "$S" shell cat /sdcard/ui.xml | python3 -c "
import re,sys; x=sys.stdin.read(); m=re.search(r'text=\"$1\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]', x) or re.search(r'content-desc=\"$1\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]', x)
print((int(m[1])+int(m[3]))//2, (int(m[2])+int(m[4]))//2) if m else print(360, 800)" | xargs $A input tap; }
cap() { sleep "${2:-1.2}"; adb -s "$S" exec-out screencap -p > "shots/$1.png"; echo "shots/$1.png"; }
until mkdir /tmp/agentos-device.lock 2>/dev/null; do sleep 3; done; trap 'rmdir /tmp/agentos-device.lock' EXIT
$A input keyevent KEYCODE_HOME; sleep .8; $A input keyevent KEYCODE_HOME
for n in ${@:-home center}; do case $n in
  home)     cap home 5;;
  center)   $A input swipe 600 900 100 900 120; cap center;;
  blockers) tapText Blockers; cap blockers; $A input keyevent KEYCODE_BACK;;
  perms)    tapText Permissions; cap permissions; $A input keyevent KEYCODE_BACK;;
  drawer)   $A input keyevent KEYCODE_HOME; sleep .6; $A input swipe 360 1000 360 300 120; cap drawer; $A input keyevent KEYCODE_BACK;;
esac; done
$A input keyevent KEYCODE_HOME
