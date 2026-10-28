#!/bin/sh
# Polish pass 2 screenshots (holds the phone lock through scripts/withphone). Usage: shoot.sh <prefix> [names...]
# names: home center center2 blockers perms drawer sheet island-working island-done card-approval card-lease card-auto
set -u; cd "$(dirname "$0")"; S=${ANDROID_SERIAL:-ZY22J3TK8S}; A="adb -s $S shell"; PFX=$1; shift
UI="am broadcast -a local.agentos.UI"
cap() { sleep "${2:-1.2}"; adb -s "$S" exec-out screencap -p > "$PFX-$1.png"; echo "$PFX-$1.png"; }
home() { $A input keyevent KEYCODE_HOME; sleep .7; $A input keyevent KEYCODE_HOME; sleep .9; }
home
for n in ${@:-home center}; do case $n in
  home)     cap home 2;;
  center)   home; $A input swipe 600 900 100 900 120; cap center 1.5;;
  center2)  home; $A input swipe 600 900 100 900 120; sleep 1; $A input swipe 360 1300 360 500 300; cap center2 1.2;;
  blockers|perms|plugins|passwords|mods|know|memory|remote) home; $A input swipe 600 900 100 900 120; sleep 1; $A input swipe 360 700 360 100 400; sleep .6; $A input swipe 360 1400 360 400 400; sleep 1
    case $n in blockers) xy="530 760";; passwords) xy="190 920";; mods) xy="530 920";; know) xy="190 1080";; memory) xy="530 1080";; plugins) xy="190 1240";; remote) xy="530 1240";; perms) xy="190 1400";; esac
    $A input tap $xy; cap $n 1.6; $A input keyevent KEYCODE_BACK;;
  drawer)   home; $A input swipe 360 1000 360 300 120; cap drawer 1.5; $A input keyevent KEYCODE_BACK;;
  sheet)    home; $A input tap 300 1240; cap sheet 1.6; $A input keyevent KEYCODE_BACK; sleep .4; $A input keyevent KEYCODE_BACK;;
  island-working) $A $UI --es island working --es label "'Typing in Pigeon'" --ei pct 35 --es goal "'Text Mom that I am late'"; cap island-working 1.4; $A $UI --es island hide;;
  island-done)    $A $UI --es island working --es label "'Typing in Pigeon'" --ei pct 35; sleep 1.2; $A $UI --es island done --es label "'Done · 6.8 s'"; cap island-done 1.3; sleep 3;;
  card-approval)  $A $UI --es card approval; cap card-approval 1.5; $A $UI --es island hide;;
  card-lease)     $A $UI --es card lease --ei min 10; cap card-lease 1.5; $A $UI --es island hide;;
  card-auto)      $A $UI --es island working --es label "'Typing in Pigeon'" --ei pct 35; sleep 1; $A $UI --es card auto; cap card-auto 1.2; sleep 3; $A $UI --es island hide;;
esac; done
home
