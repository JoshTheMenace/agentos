#!/bin/sh
# One real ask in the launcher's ask sheet, then a screenshot once the turn ends. Usage: ask.sh "<ask>" out.png  (run inside scripts/withphone)
cd "$(dirname "$0")"; D=../polish-night-3/drive.mjs
adb shell input keyevent HOME; sleep 1.2
node $D "open('sheet'); body.replaceChildren(); sendUser($(printf '%s' "$1" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))')); 'sent'"
for i in $(seq 1 45); do sleep 2; [ "$(node $D 'S.turn')" = "false" ] && break; done
adb shell input keyevent HOME; sleep 1; node $D "open('sheet'); 1" >/dev/null; sleep 1.3; adb exec-out screencap -p > "$2"; echo "turn ended after ~$((i*2)) s -> $2"
node $D "[...body.querySelectorAll('.viz')].map(v=>v.className+' '+(v.querySelector('.k')?.textContent||'')).join('|')+' ; words='+(body.querySelector('.msg.ai')?.textContent.split(' ').length||0)"
