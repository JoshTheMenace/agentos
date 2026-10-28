#!/bin/sh
# Keyboard latency bench in Pigeon (keyboard/LATENCY.md). Run under the phone lock:
#   N=6 scripts/withphone keyboard/bench/lat.sh
# Each round: open a chat, focus the message field (prefetch on focus), then type a unique word (typing pause).
# Prints the keyboard's `lat` lines: total = trigger -> suggestions on screen, split into wait/queue/screen/agentd/render.
N=${N:-3}; S=${ANDROID_SERIAL:-ZY22J3TK8S}; a() { adb -s "$S" shell "$@"; }
if [ -n "${WARMUP:-}" ]; then   # one untimed focus first, so a cold agentd thread doesn't count (WARMUP=30 waits 30 s)
  a am start -S -W -n local.agentos.pigeon/.Main >/dev/null; sleep 0.8
  [ "$(a dumpsys window | grep -c 'mCurrentFocus.*pigeon')" = 1 ] || { echo "Pigeon not in front; stopping"; exit 1; }
  a input tap 280 385; sleep 1.2; a input tap 285 1465; sleep "$WARMUP"
fi
adb -s "$S" logcat -c
set -- Sure Haha Yes Sounds Can Okay Wait Thanks Love; i=0
for chat in 385 515 255 385 515 255 385 515 255; do
  [ $i -ge "$N" ] && break; i=$((i + 1))
  a am start -S -W -n local.agentos.pigeon/.Main >/dev/null; sleep 0.8   # fresh start = chat list (never touch other apps)
  [ "$(a dumpsys window | grep -c 'mCurrentFocus.*pigeon')" = 1 ] || { echo "Pigeon not in front; stopping"; break; }
  a input tap 280 $chat; sleep 1.2
  a input tap 285 1465; sleep ${PAUSE:-10}                              # focus -> prefetch
  a input text "$1"; shift; sleep ${PAUSE:-10}                         # typing pause -> completions (a new word each round: no cache)
  a input keyevent MOVE_END; for k in 1 2 3 4 5 6 7; do a input keyevent DEL; done
done
a input keyevent BACK
adb -s "$S" logcat -d -s AgentKeys | grep -E " lat |suggest via|suggest failed" | sed 's/.*AgentKeys: //'
