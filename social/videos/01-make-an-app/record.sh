#!/bin/sh
# How the raw takes of video 01 were driven. Run each phase inside the phone lock, from the repo root:
#   scripts/withphone sh social/videos/01-make-an-app/record.sh pre     # DND + touch dots on, remember the app list, Home
#   scripts/withphone sh social/videos/01-make-an-app/record.sh a a2    # take A -> raw/take-a2.mp4: type the ask, build, the app lands on Home
#   scripts/withphone sh social/videos/01-make-an-app/record.sh b b3    # take B -> raw/take-b3.mp4: open the new app and log cups (taps from b.taps)
#   scripts/withphone sh social/videos/01-make-an-app/record.sh post    # put DND/touch dots back; prints the new app id to delete
set -eu; cd "$(dirname "$0")"; N=${2:-$1}; S=${ANDROID_SERIAL:-ZY22J3TK8S}; A="adb -s $S"; ST=/tmp/social-01; mkdir -p "$ST" raw
ASK="make me a coffee tracker with a 3 cup daily limit and a weekly chart" # a new name: an old app id keeps its WebView storage
DT="node ../../kit/devtools.mjs"
apps() { $A shell "su -c 'ls /data/data/com.termux/files/home/.agentos/apps'" | tr -d '\r' | sort; }
tapq() { xy=$($DT "$1" "(r => r && r.x + ' ' + r.y)(rect($2))" | tr -d '"'); [ "$xy" != null ] || { echo "no $2" >&2; exit 1; }; $A shell input tap $xy; }
rec() { $A shell "screenrecord --time-limit 180 --bit-rate 12000000 /sdcard/soc-$1.mp4" & echo $! > "$ST/rec.pid"; sleep 1.5; }
stop() { $A shell pkill -INT screenrecord || true; wait "$(cat "$ST/rec.pid")" 2>/dev/null || sleep 2; sleep 1
  $A pull "/sdcard/soc-$1.mp4" "raw/take-$1.mp4" >/dev/null && $A shell rm -f "/sdcard/soc-$1.mp4"; echo "raw/take-$1.mp4"; }
type_slow() { first=1; for w in $1; do [ $first = 1 ] || $A shell input keyevent 62; first=0; $A shell input text "$(printf %s "$w" | sed "s/'/\\\\'/g")"; sleep 0.15; done; }

case "$1" in
pre)
  $A shell settings get global zen_mode > "$ST/zen"; $A shell settings get system show_touches > "$ST/touches"
  $A shell cmd notification set_dnd priority; $A shell settings put system show_touches 1
  apps > "$ST/apps-before"; $A shell "su -c 'tar -C /data/data/com.termux/files/home/.agentos -cf - apps'" > "$ST/apps-backup.tar" # in case the model edits an old app
  $A shell input keyevent HOME; echo "apps before: $(tr '\n' ' ' < "$ST/apps-before")" ;;
a)
  $A shell input keyevent HOME; $DT 'agentos\.home/index' 'location.reload()' >/dev/null || true; sleep 4; rec $N # reload: an empty ask sheet
  sleep 1.5; tapq 'agentos\.home/index' "'#cmd span'"; sleep 1.5
  type_slow "$ASK"; sleep 1.2; $A shell input keyevent 66; sleep 1; $A shell input keyevent BACK # hide the keyboard, show the build
  t=0; until [ "$(apps | comm -13 "$ST/apps-before" -)" ] || [ $t -ge 150 ]; do sleep 2; t=$((t+2)); done
  echo "built after ~${t}s: $(apps | comm -13 "$ST/apps-before" -)"
  until [ "$($DT 'agentos\.home/index' 'S.turn' || echo true)" = false ] || [ $t -ge 180 ]; do sleep 1; t=$((t+1)); done; sleep 4 # the answer lands
  $A shell input keyevent BACK; sleep 4; stop $N ;; # BACK closes the sheet: the new app is on Home
b) # b.taps: one step per line: "tap <x> <y>" | "text <word>" | "sleep <s>" | "home" (written after looking at the app)
  $A shell input keyevent HOME; sleep 1; rec $N; sleep 1.5
  tapq 'agentos\.home/index' "'#myapps > *'"; sleep 3
  while read -r op x y <&3; do case "$op" in tap) $A shell input tap "$x" "$y";; text) $A shell input text "$x";; sleep) sleep "$x";; home) $A shell input keyevent HOME;; esac; sleep 0.6; done 3< b.taps # fd 3: adb shell would eat stdin
  sleep 2; stop $N ;;
post)
  [ "$(cat "$ST/zen")" = 0 ] && $A shell cmd notification set_dnd off; t=$(tr -d "\r" < "$ST/touches"); if [ "$t" = null ]; then $A shell settings delete system show_touches; else $A shell settings put system show_touches "$t"; fi
  echo "new app ids (delete by exact id): $(apps | comm -13 "$ST/apps-before" - | tr '\n' ' ')" ;;
esac
