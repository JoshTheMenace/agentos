#!/bin/sh
# How the launch stills' raw shots (src/, not in git) were taken, one phase per image. From the repo root, inside the device lock:
#   scripts/withphone sh social/launch/shoot.sh pre | home | across | bar | keyboard | memory | charts | app | security | post
# Every ask goes through the launcher's own ask sheet (open('sheet') + sendUser over the WebView's DevTools socket), so the shots show
# exactly what the owner sees. "burst" takes a screenshot every ~0.7 s while the agent works, to catch the island mid-task.
set -eu; cd "$(dirname "$0")"; S=${ANDROID_SERIAL:-ZY22J3TK8S}; A="adb -s $S"; DT="node ../kit/devtools.mjs"; H='agentos\.home/index'; ST=/tmp/social-launch; mkdir -p src "$ST"
shot() { $A exec-out screencap -p > "src/$1.png"; echo "src/$1.png"; }
burst() { d=src/burst-$1; rm -rf "$d"; mkdir -p "$d"; e=$(( $(date +%s) + $2 )); n=0; while [ "$(date +%s)" -lt $e ]; do $A exec-out screencap -p > "$d/$(printf %03d $n).png"; n=$((n+1)); done; echo "$d: $n frames"; }
home() { $A shell input keyevent HOME; sleep 1; }
fresh() { home; $DT "$H" 'location.reload()' >/dev/null || true; sleep 4; } # a clean sheet: no old chats
ask() { $DT "$H" "open('sheet'); sendUser($(node -e 'console.log(JSON.stringify(process.argv[1]))' "$1")); 1" >/dev/null; }
wait_turn() { t=0; until [ "$($DT "$H" 'S.turn' || echo true)" = false ] || [ $t -ge "${1:-120}" ]; do sleep 1; t=$((t+1)); done; sleep 2; echo "turn ended after ~${t}s"; }
pigeon() { $A shell "am start -n local.agentos.pigeon/.Main $1" >/dev/null; sleep 1.5; }

case "$1" in
pre) $A shell settings get global zen_mode > "$ST/zen"; $A shell cmd notification set_dnd priority; home ;;
home) fresh; ask "what smart home devices do I have?"; wait_turn; shot 01-home ;;
across) pigeon "--es scenario clean"; pigeon "--es inject 'Lets meet at Kneaders in Orem at 6' --es from Alex"; fresh
  ask "open Pigeon and read the last message from Alex, then look the place up in the Google Maps app (just show it, do not start navigation)"; burst across 75; home ;;
bar) $A shell monkey -p com.linkedin.android -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 8
  $A shell su -c "am start-foreground-service -n local.agentos.home/.Bar -a local.agentos.home.BAR_OPEN" >/dev/null; sleep 2
  node ../../launcher/shots/assistant-bar/drive.mjs "ask(\"what's on my screen? help me reply\"); 1" >/dev/null; burst bar 45; home ;;
keyboard) pigeon "--es inject 'Are you free Thursday at 3?' --es from Sam"; shot 04-pigeon-list ;; # then tap the Sam chat and the field by hand (see the log)
memory) fresh; ask "what do I know about Sam?"; wait_turn; shot 05-know
  $DT "$H" "close('sheet'); setTimeout(() => go(2), 700); 1" >/dev/null; sleep 4; shot 05-graph; $DT "$H" 'go(0); 1' >/dev/null ;;
charts) fresh; ask "show me my sleep for the last 7 days"; wait_turn; shot 06-sleep
  fresh; ask "when should I turn on my christmas tree?"; wait_turn; shot 06-tree ;;
app) home; $DT "$H" "[...document.querySelectorAll('#myapps > *')].find((e) => e.dataset.id && /kana/i.test(e.innerText)).click(); 1" >/dev/null; sleep 4; shot 07-kana; home ;;
app-reset) # first-run again: wipe its storage without letting it re-save, close the activity (see videos/01-make-an-app/record-japanese.sh reset)
  home; $DT "$H" "[...document.querySelectorAll('#myapps > *')].find((e) => e.dataset.id && /kana/i.test(e.innerText)).click(); 1" >/dev/null; sleep 3
  $DT 'agentos\.app' 'Storage.prototype.setItem = () => {}; localStorage.clear(); setTimeout(() => AgentOSApp.close(), 50); 1' >/dev/null; sleep 2; home ;;
install) fresh; ask "open the Play Store and install Firefox Focus, I will approve it with my fingerprint"; burst install 60 ;; # never tap the card: stop the task instead (phase stop)
stop) $DT "$H" "send({ type: 'stop' }); 1" >/dev/null; sleep 3; $DT "$H" "rpc('task.stop', {})" ; home ;;
trap) pigeon "--es scenario clean"; pigeon "--es scenario canary"; fresh; ask "open Pigeon and summarize the messages on screen"; burst trap 60; home ;;
post) pigeon "--es scenario clean"; home; $A shell cmd notification set_dnd off; $A shell settings get global zen_mode ;;
esac
