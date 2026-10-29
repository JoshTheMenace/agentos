#!/bin/sh
# How the raw takes of japanese.mp4 (video 01, second cut: a Japanese learning app) were driven. Same shape as record.sh; run from the repo root:
#   scripts/withphone sh social/videos/01-make-an-app/record-japanese.sh pre        # DND + touch dots on, remember the app list, Home
#   NOREC=1 scripts/withphone sh social/videos/01-make-an-app/record-japanese.sh a  # a test build (no recording): see what the request makes, then delete it
#   scripts/withphone sh social/videos/01-make-an-app/record-japanese.sh a j1       # take A -> raw/take-j1.mp4: type the ask, build, the app lands on Home
#   scripts/withphone sh social/videos/01-make-an-app/record-japanese.sh reset      # the new app back to first-run (its WebView storage), before every take B
#   scripts/withphone sh social/videos/01-make-an-app/record-japanese.sh b j2       # take B -> raw/take-j2.mp4: open the new app, steps from japanese.steps
#   scripts/withphone sh social/videos/01-make-an-app/record-japanese.sh post       # put DND/touch dots back; prints the new app ids
# Every tap is logged with its time into take-<N>.taps (kit/rec.sh), which render.mjs reads to zoom the camera toward it.
set -eu; cd "$(dirname "$0")"; N=${2:-$1}; S=${ANDROID_SERIAL:-ZY22J3TK8S}; A="adb -s $S"; ST=/tmp/social-01jp; mkdir -p "$ST" raw
ASK="make me an app to learn Japanese, hiragana first, with quizzes and a streak"
NAME=${NAME:-'japan|hiragana|kana|nihongo'} # which My apps tile is the new one (its title, case-insensitive)
DT="node ../../kit/devtools.mjs"; . ../../kit/rec.sh # rec/stop/tap/swipe, with the tap log
[ "${NOREC:-}" != 1 ] || { rec() { :; }; stop() { :; }; }
apps() { $A shell "su -c 'ls /data/data/com.termux/files/home/.agentos/apps'" | tr -d '\r' | sort; }
tapq() { xy=$($DT "$1" "(r => r && r.x + ' ' + r.y)(rect($2))" | tr -d '"'); [ "$xy" != null ] || { echo "no $2" >&2; exit 1; }; tap $xy; }
SB=76 # status-bar height: devtools rects are in the app window, which starts below it
# pickxy right|wrong -> "x y" of a vowels-quiz choice (the prompt is a kana or its romaji; choices are .choice buttons)
pickxy() { $DT 'agentos\.app' "(w => { const M = {あ:'a',い:'i',う:'u',え:'e',お:'o'}, R = Object.fromEntries(Object.entries(M).map(([k, v]) => [v, k]));
  const q = (document.querySelector('.view.on .card')?.innerText || '').trim().split('\\n')[0].trim(), ans = M[q] || R[q];
  const b = [...document.querySelectorAll('.view.on button.choice')].find((c) => (c.innerText.trim() === ans) === w); const r = rect(b); return r && r.x + ' ' + (r.y + $SB); })($([ "$1" = right ] && echo true || echo false))" | tr -d '"'; }
# labelxy <text> -> "x y" of a button by its label, scrolled gently into view first (the nav bar covers the bottom of a view)
labelxy() { $DT 'agentos\.app' "(async () => { const b = [...document.querySelectorAll('button')].find((e) => e.offsetParent && e.innerText.trim().replace(/\\s+/g, ' ') === '$1'); if (!b) return null;
  b.scrollIntoView({ behavior: 'smooth', block: 'center' }); await new Promise((ok) => setTimeout(ok, 700)); const r = rect(b); return r.x + ' ' + (r.y + $SB); })()" | tr -d '"'; }
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
  until [ "$($DT 'agentos\.home/index' 'S.turn' || echo true)" = false ] || [ $t -ge 170 ]; do sleep 1; t=$((t+1)); done; sleep 4 # the answer lands
  $A shell input keyevent BACK; sleep 4; stop $N ;; # BACK closes the sheet: the new app is on Home (a consent card may sit under it; nobody taps it)
reset) # first-run state for take B: wipe the app's localStorage without letting it re-save (it writes on every change), then close its
  # activity (HOME keeps it alive, and reload/navigation are blocked in a micro-app WebView), so the next tile tap loads a fresh page.
  $A shell input keyevent HOME; sleep 1; tapq 'agentos\.home/index' "[...document.querySelectorAll('#myapps > *')].find((e) => e.dataset.id && /$NAME/i.test(e.innerText))"; sleep 3
  $DT 'agentos\.app' 'Storage.prototype.setItem = () => {}; localStorage.clear(); setTimeout(() => AgentOSApp.close(), 50); 1' >/dev/null; sleep 2; $A shell input keyevent HOME ;;
b) # japanese.steps, one step per line: "tap <x> <y>" | "swipe <x1> <y1> <x2> <y2> [ms]" | "label <button text>" (scrolled gently into view, then
   # tapped where it is now) | "pick right|wrong" (a vowels-quiz answer, read off the app's DOM) | "sleep <s>" | "home". Written after looking at the app.
  $A shell input keyevent HOME; sleep 1; rec $N; sleep 1.5
  tapq 'agentos\.home/index' "[...document.querySelectorAll('#myapps > *')].find((e) => e.dataset.id && /$NAME/i.test(e.innerText))"; sleep 3 # the tile, not the consent card
  while read -r op rest <&3; do case "$op" in # fd 3: adb shell would eat stdin
    tap) tap $rest;; swipe) swipe $rest;; sleep) sleep $rest;; home) $A shell input keyevent HOME;;
    label) xy=$(labelxy "$rest"); [ "$xy" != null ] || { echo "no button $rest" >&2; exit 1; }; tap $xy;;
    pick) xy=$(pickxy "$rest"); [ "$xy" != null ] || { echo "no $rest answer" >&2; exit 1; }; tap $xy;;
  esac; sleep 0.6; done 3< japanese.steps
  sleep 2; stop $N ;;
post)
  [ "$(cat "$ST/zen")" = 0 ] && $A shell cmd notification set_dnd off; t=$(tr -d "\r" < "$ST/touches"); if [ "$t" = null ]; then $A shell settings delete system show_touches; else $A shell settings put system show_touches "$t"; fi
  echo "new app ids (delete by exact id): $(apps | comm -13 "$ST/apps-before" - | tr '\n' ' ')" ;;
esac
