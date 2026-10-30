#!/bin/sh
# How the raw takes of trackers.mp4 (video 08: tracker radar) were driven. From the repo root:
#   scripts/withphone sh social/videos/08-tracker-radar/record.sh pre       # DND + touch dots on, Home
#   scripts/withphone sh social/videos/08-tracker-radar/record.sh a t1      # take A -> raw/take-t1.mp4: Spotify opens (am start), a scroll, HOME
#   scripts/withphone sh social/videos/08-tracker-radar/record.sh b t2      # take B -> raw/take-t2.mp4: swipe to Command center, tap "N apps sent data to trackers", Blockers, a scroll
#   scripts/withphone sh social/videos/08-tracker-radar/record.sh post      # DND/touch dots back, Home
# Nobody taps Block: blocking is the owner's setting and the story is the names and the live counters.
set -eu; cd "$(dirname "$0")"; N=${2:-$1}; S=${ANDROID_SERIAL:-ZY22J3TK8S}; A="adb -s $S"; ST=/tmp/social-08; mkdir -p "$ST" raw
APP=${APP:-com.spotify.music}; H='agentos\.home/index'; DT="node ../../kit/devtools.mjs"
. ../../kit/rec.sh; [ "${NOREC:-}" != 1 ] || { rec() { :; }; stop() { :; }; }
tapq() { xy=$($DT "$1" "(r => r && r.x + ' ' + r.y)(rect($2))" | tr -d '"'); [ "$xy" != null ] || { echo "no $2" >&2; exit 1; }; tap $xy; }
case "$1" in
pre) $A shell settings get global zen_mode > "$ST/zen"; $A shell settings get system show_touches > "$ST/touches"
  $A shell cmd notification set_dnd priority; $A shell settings put system show_touches 1; $A shell input keyevent HOME ;;
a) $A shell input keyevent HOME; sleep 1; rec $N; sleep 1.5
  $A shell monkey -p "$APP" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 7
  swipe 360 1100 360 500 500; sleep 2.5; swipe 360 1100 360 600 500; sleep 2.5
  $A shell input keyevent HOME; sleep 2.5; stop $N ;;
b) $A shell input keyevent HOME; sleep 1; $DT "$H" 'go(0); 1' >/dev/null; sleep 1; rec $N; sleep 1.5
  swipe 600 800 100 800 350; sleep 2.5                       # Home -> Command center
  tapq "$H" "'#watch .watch'"; sleep 7                        # "N apps sent data to trackers" -> Blockers; counters refresh every 2 s
  swipe 360 1250 360 650 600; sleep 3; swipe 360 1250 360 700 600; sleep 3
  stop $N; $DT "$H" "close('blockers'); go(0); 1" >/dev/null ;;
post) [ "$(cat "$ST/zen")" = 0 ] && $A shell cmd notification set_dnd off; t=$(tr -d "\r" < "$ST/touches"); if [ "$t" = null ]; then $A shell settings delete system show_touches; else $A shell settings put system show_touches "$t"; fi
  $A shell input keyevent HOME ;;
esac
