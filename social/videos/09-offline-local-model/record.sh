#!/bin/sh
# How the raw takes of offline.mp4 (video 09: airplane mode, the phone's own model answers) were driven. From the repo root:
#   scripts/withphone sh social/videos/09-offline-local-model/record.sh pre     # DND + touch dots on, Home (warm the local model first: scripts/preflight.sh --fix warm)
#   scripts/withphone sh social/videos/09-offline-local-model/record.sh a o1    # take -> raw/take-o1.mp4: airplane tile on, ask "turn on the flashlight", the On-device answer, tiles off again
#   scripts/withphone sh social/videos/09-offline-local-model/record.sh post    # airplane off if still on, Wi-Fi check, DND/touch dots back, Home
# The quick-settings grid is opened with `cmd statusbar expand-settings` (no notification shade on screen), the tiles are tapped on camera.
set -eu; cd "$(dirname "$0")"; N=${2:-$1}; S=${ANDROID_SERIAL:-ZY22J3TK8S}; A="adb -s $S"; ST=/tmp/social-09; mkdir -p "$ST" raw
ASK=${ASK:-"turn on the flashlight"}; H='agentos\.home/index'; DT="node ../../kit/devtools.mjs"
. ../../kit/rec.sh; [ "${NOREC:-}" != 1 ] || { rec() { :; }; stop() { :; }; }
AIR="529 840"; TORCH="190 840" # quick-settings tiles (Airplane mode, Flashlight) in the full grid
tapq() { xy=$($DT "$1" "(r => r && r.x + ' ' + r.y)(rect($2))" | tr -d '"'); [ "$xy" != null ] || { echo "no $2" >&2; exit 1; }; tap $xy; }
type_slow() { first=1; for w in $1; do [ $first = 1 ] || $A shell input keyevent 62; first=0; $A shell input text "$w"; sleep 0.15; done; }
qs() { $A shell cmd statusbar expand-settings; sleep 1.6; }
wait_turn() { t=0; until [ "$($DT "$H" 'S.turn' || echo true)" = false ] || [ $t -ge "${1:-60}" ]; do sleep 1; t=$((t+1)); done; echo "turn ended after ~${t}s"; }
case "$1" in
pre) $A shell settings get global zen_mode > "$ST/zen"; $A shell settings get system show_touches > "$ST/touches"
  $A shell cmd notification set_dnd priority; $A shell settings put system show_touches 1; $A shell input keyevent HOME ;;
a) $A shell input keyevent HOME; $DT "$H" 'location.reload()' >/dev/null || true; sleep 4; rec $N; sleep 1.5
  qs; tap $AIR; sleep 2.5; $A shell cmd statusbar collapse; sleep 1.5              # airplane mode on
  tapq "$H" "'#cmd span'"; sleep 1.5; type_slow "$ASK"; sleep 1; $A shell input keyevent 66; sleep 0.8; $A shell input keyevent BACK # ask; hide the keyboard
  wait_turn 60; sleep 3.5
  qs; sleep 1.5; tap $TORCH; sleep 1.2; tap $AIR; sleep 2.5; $A shell cmd statusbar collapse; sleep 2; stop $N ;; # flashlight off, airplane off
post) [ "$($A shell settings get global airplane_mode_on | tr -d '\r')" = 0 ] || $A shell cmd connectivity airplane-mode disable
  sleep 6; $A shell "dumpsys wifi | grep -m1 'mWifiInfo' | cut -c1-80"; $A shell "ping -c1 -W3 8.8.8.8 | tail -1"
  [ "$(cat "$ST/zen")" = 0 ] && $A shell cmd notification set_dnd off; t=$(tr -d "\r" < "$ST/touches"); if [ "$t" = null ]; then $A shell settings delete system show_touches; else $A shell settings put system show_touches "$t"; fi
  $A shell input keyevent HOME ;;
esac
