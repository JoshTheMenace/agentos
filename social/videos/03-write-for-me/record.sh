#!/bin/sh
# How the raw takes of write.mp4 (video 03: ✨ Write for me in Gmail) were driven. Same shape as 01-make-an-app/record-japanese.sh; from the repo root:
#   scripts/withphone sh social/videos/03-write-for-me/record.sh pre        # DND + touch dots on, Home
#   NOREC=1 scripts/withphone sh social/videos/03-write-for-me/record.sh a  # dry run (no recording): check the compose screen and the key positions
#   scripts/withphone sh social/videos/03-write-for-me/record.sh a w1       # take -> raw/take-w1.mp4: blank compose, tap the body, ✨, type the intent key by key, Shorter, Insert
#   scripts/withphone sh social/videos/03-write-for-me/record.sh discard    # ⋮ › Discard: nothing stays in Drafts (run after every take)
#   scripts/withphone sh social/videos/03-write-for-me/record.sh post       # DND/touch dots back, Home
# The keyboard's ✨ box only takes its own on-screen keys (adb `input text` would go to Gmail's field), so the intent is typed by tapping
# each key; every tap is logged into take-<N>.taps (kit/rec.sh) and the camera follows the finger. To stays empty; nothing is ever sent.
set -eu; cd "$(dirname "$0")"; N=${2:-$1}; S=${ANDROID_SERIAL:-ZY22J3TK8S}; A="adb -s $S"; ST=/tmp/social-03; mkdir -p "$ST" raw
ASK=${ASK:-"tell the team the demo went great and thank them for the late nights"}
. ../../kit/rec.sh; [ "${NOREC:-}" != 1 ] || { rec() { :; }; stop() { :; }; }
# Key centres (720 px wide, density 1.75: 10 keys of 60.8 px at a 71.3 px pitch, rows 94.5 px apart, bottom row at 1457)
keyxy() { case "$1" in
  q) echo 39 1173;; w) echo 110 1173;; e) echo 182 1173;; r) echo 253 1173;; t) echo 324 1173;; y) echo 396 1173;; u) echo 467 1173;; i) echo 538 1173;; o) echo 609 1173;; p) echo 681 1173;;
  a) echo 75 1268;; s) echo 146 1268;; d) echo 217 1268;; f) echo 289 1268;; g) echo 360 1268;; h) echo 431 1268;; j) echo 503 1268;; k) echo 574 1268;; l) echo 645 1268;;
  z) echo 146 1362;; x) echo 217 1362;; c) echo 289 1362;; v) echo 360 1362;; b) echo 431 1362;; n) echo 503 1362;; m) echo 574 1362;;
  ' ') echo 358 1457;; esac; }
type_keys() { printf %s "$1" | fold -w1 | while IFS= read -r ch; do [ -n "$ch" ] || ch=' '; tap $(keyxy "$ch"); sleep 0.05; done; }
compose() { $A shell am start -a android.intent.action.SENDTO -d mailto: -p com.google.android.gm --activity-clear-task >/dev/null; sleep 4; }
case "$1" in
pre) $A shell settings get global zen_mode > "$ST/zen"; $A shell settings get system show_touches > "$ST/touches"
  $A shell cmd notification set_dnd priority; $A shell settings put system show_touches 1; $A shell input keyevent HOME ;;
a) $A shell input keyevent HOME; sleep 1; compose; $A shell input tap 360 823; sleep 2.5 # the body, before recording: a fresh compose focuses To and shows real contacts
  rec $N; sleep 1.5
  tap 622 1094; sleep 1.2           # ✨ Write for me
  type_keys "$ASK"; sleep 0.8
  tap 604 1457; sleep 6             # return: agentd writes the email (~2.5 s after a screen read)
  tap 75 1003; sleep 4.5            # Shorter
  tap 652 1003; sleep 2.5           # Insert: the email lands in the body
  stop $N ;;
discard) $A shell input keyevent BACK; sleep 0.8; $A shell input tap 673 133; sleep 1.2; $A shell input tap 445 483; sleep 1.5 # ⋮ › Discard
  $A exec-out screencap -p > "$ST/after-discard.png"; $A shell input keyevent HOME; echo "check $ST/after-discard.png" ;;
post) [ "$(cat "$ST/zen")" = 0 ] && $A shell cmd notification set_dnd off; t=$(tr -d "\r" < "$ST/touches"); if [ "$t" = null ]; then $A shell settings delete system show_touches; else $A shell settings put system show_touches "$t"; fi
  $A shell input keyevent HOME ;;
esac
