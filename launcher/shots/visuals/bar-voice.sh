#!/bin/sh
# Assistant bar (compact chart, tap to expand) and voice mode (chart under the transcript) with one real ask each. Run inside scripts/withphone.
cd "$(dirname "$0")"; O=$PWD; B=../assistant-bar/drive.mjs; D=../polish-night-3/drive.mjs; Q='how many steps did I take this week?'
adb shell input keyevent HOME; sleep 1; adb shell am start -n com.android.chrome/com.google.android.apps.chrome.Main >/dev/null; sleep 2.5
adb shell su -c "am start-foreground-service -n local.agentos.home/.Bar -a local.agentos.home.BAR_OPEN" >/dev/null; sleep 2.5
node $B "ask('$Q'); 'sent'"
for i in $(seq 1 30); do sleep 2; [ "$(node $B 'turn')" = "false" ] && break; done; sleep 1.5
adb exec-out screencap -p > $O/bar-compact.png; echo "bar done ~$((i*2)) s"
node $B "document.querySelector('.viz')?.click(); 'tap'" >/dev/null; sleep 1.5; adb exec-out screencap -p > $O/bar-expanded.png
node $B "bar.close(); 1" >/dev/null; sleep 1; adb shell input keyevent HOME; sleep 1.5; [ -n "$BAR_ONLY" ] && exit 0
node $D "open('voice'); 1" >/dev/null; sleep 3
node $D "send({type:'voice.delegate', item_id:'viz-'+Date.now(), text:'$Q'}); 'delegated'"
for i in $(seq 1 30); do sleep 2; s=$(node $D "[...S.tasks.values()].sort((a,b)=>b.created-a.created)[0]?.state||''"); case $s in '"done"'|'"failed"'|'"stopped"') break;; esac; done; sleep 2
adb exec-out screencap -p > $O/voice.png; echo "voice task $s ~$((i*2)) s"
node $D "document.querySelectorAll('#vviz .viz').length" ; node $D "close('voice'); 1" >/dev/null
