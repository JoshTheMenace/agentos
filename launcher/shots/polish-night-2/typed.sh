#!/bin/sh
# Typed ask from the sheet (no screen driving): header count-up while thinking, then "Done · x s". Run inside scripts/withphone.
S=${ANDROID_SERIAL:-ZY22J3TK8S}; A="adb -s $S shell"; cd "$(dirname "$0")"
cap() { sleep "$2"; adb -s "$S" exec-out screencap -p > "$1.png"; echo "$1.png"; }
$A input keyevent KEYCODE_HOME; sleep 1; $A input tap 300 1240; sleep 1.2
$A input text 'How%smuch%sbattery%sdo%sI%shave?'; sleep .3; $A input keyevent 66
cap after-sheet-working 1.6; cap after-sheet-done 9
$A input keyevent KEYCODE_BACK; sleep .4; $A input keyevent KEYCODE_BACK; sleep .3; $A input keyevent KEYCODE_HOME
