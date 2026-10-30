#!/bin/sh
# How the raw takes of scam.mp4 (video 04: a message with hidden instructions for the AI) were driven. From the repo root:
#   scripts/withphone sh social/videos/04-scam-caught/record.sh pre       # DND + touch dots on, Pigeon clean, then the canary (trap) scenario, Home
#   scripts/withphone sh social/videos/04-scam-caught/record.sh a s1      # take A -> raw/take-s1.mp4: ask "open Pigeon and summarize my messages", the agent reads, the amber card, the summary
#   scripts/withphone sh social/videos/04-scam-caught/record.sh b s2      # take B -> raw/take-s2.mp4: swipe to Command center, the Activity line "Scam message caught"
#   scripts/withphone sh social/videos/04-scam-caught/record.sh post      # Pigeon back to clean, DND/touch dots back, Home
# Record this last: a Prompt Guard flag makes later sends need a person for 10 min.
set -eu; cd "$(dirname "$0")"; N=${2:-$1}; S=${ANDROID_SERIAL:-ZY22J3TK8S}; A="adb -s $S"; ST=/tmp/social-04; mkdir -p "$ST" raw
ASK=${ASK:-"open Pigeon and summarize my messages"}; H='agentos\.home/index'; DT="node ../../kit/devtools.mjs"
. ../../kit/rec.sh; [ "${NOREC:-}" != 1 ] || { rec() { :; }; stop() { :; }; }
tapq() { xy=$($DT "$1" "(r => r && r.x + ' ' + r.y)(rect($2))" | tr -d '"'); [ "$xy" != null ] || { echo "no $2" >&2; exit 1; }; tap $xy; }
type_slow() { first=1; for w in $1; do [ $first = 1 ] || $A shell input keyevent 62; first=0; $A shell input text "$w"; sleep 0.15; done; }
pigeon() { $A shell "am start -n local.agentos.pigeon/.Main $1" >/dev/null; sleep 1.5; }
wait_turn() { t=0; until [ "$($DT "$H" 'S.turn' || echo true)" = false ] || [ $t -ge "${1:-120}" ]; do sleep 1; t=$((t+1)); done; echo "turn ended after ~${t}s"; }
case "$1" in
pre) $A shell settings get global zen_mode > "$ST/zen"; $A shell settings get system show_touches > "$ST/touches"
  $A shell cmd notification set_dnd priority; $A shell settings put system show_touches 1
  pigeon "--es scenario clean"; pigeon "--es scenario canary"; $A shell input keyevent HOME ;;
a) $A shell input keyevent HOME; $DT "$H" 'location.reload()' >/dev/null || true; sleep 4; rec $N; sleep 1.5
  tapq "$H" "'#cmd span'"; sleep 1.5; type_slow "$ASK"; sleep 1; $A shell input keyevent 66; sleep 0.8; $A shell input keyevent BACK
  wait_turn 120; sleep 5; stop $N ;;
b) $A shell input keyevent HOME; sleep 1; $DT "$H" "close('sheet'); go(0); 1" >/dev/null; sleep 1.5; rec $N; sleep 1.5
  swipe 600 800 100 800 350; sleep 2.5                       # Home -> Command center
  $DT "$H" "document.querySelector('#recent').closest('.mod').scrollIntoView({ behavior: 'smooth', block: 'start' }); 1" >/dev/null; sleep 4
  stop $N; $DT "$H" 'go(0); 1' >/dev/null ;;
post) pigeon "--es scenario clean"; $A shell input keyevent HOME
  [ "$(cat "$ST/zen")" = 0 ] && $A shell cmd notification set_dnd off; t=$(tr -d "\r" < "$ST/touches"); if [ "$t" = null ]; then $A shell settings delete system show_touches; else $A shell settings put system show_touches "$t"; fi ;;
esac
