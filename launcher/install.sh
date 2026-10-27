#!/bin/sh
# Installs build/home.apk, hands it the agentd token (root copy into its private dir), grants mic + calendar.
# Usage: launcher/install.sh [--set]   --set also makes it the default home (root, no chooser)
#        launcher/install.sh moto      restore Moto's launcher
#        launcher/install.sh dev [url] live-reload from the Mac: adb reverse + flag file (default http://127.0.0.1:5173/index.html)
#        launcher/install.sh nodev     back to the bundled page
# Holds the shared device lock while it runs (ARCHITECTURE.md rule 5).
set -eu
cd "$(dirname "$0")"
S=${ANDROID_SERIAL:-ZY22J3TK8S}; P=local.agentos.home
MOTO=com.motorola.launcher3/com.android.launcher3.CustomizationPanelLauncher
until mkdir /tmp/agentos-device.lock 2>/dev/null; do echo "device busy (lock held), retrying..."; sleep 5; done
trap 'rmdir /tmp/agentos-device.lock' EXIT
case "${1:-}" in
  moto)  adb -s "$S" shell cmd package set-home-activity $MOTO; adb -s "$S" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME; exit;;
  dev)   adb -s "$S" reverse tcp:5173 tcp:5173; adb -s "$S" shell "echo '${2:-http://127.0.0.1:5173/index.html}' > /data/local/tmp/agentos-home.dev"
         adb -s "$S" shell am force-stop $P; adb -s "$S" shell am start -n $P/.Home; echo "serve with: python3 -m http.server 5173 -d web"; exit;;
  nodev) adb -s "$S" shell rm -f /data/local/tmp/agentos-home.dev; adb -s "$S" shell am force-stop $P; adb -s "$S" shell am start -n $P/.Home; exit;;
esac
adb -s "$S" install -r build/home.apk
uid=$(adb -s "$S" shell pm list packages -U $P | grep "^package:$P " | sed "s/.*uid://" | tr -d '\r')
# agentd's token (Termux home; agentd also re-provisions it at start) and the broker's :7777 token (phone.token: the "owner line",
# used only for Blockers taps and the Passwords list/delete; the broker recognises this app by its uid). Only root can read them; copy, chown, relabel.
D=/data/data/$P/files
adb -s "$S" shell "su -c 'mkdir -p $D && cp /data/data/com.termux/files/home/.agentos/agentd.token $D/agentd.token && cp /data/data/local.agentos/files/token $D/phone.token && chown -R $uid:$uid $D && chmod 600 $D/*.token && restorecon -RD /data/data/$P'"
adb -s "$S" shell pm grant $P android.permission.RECORD_AUDIO
adb -s "$S" shell pm grant $P android.permission.READ_CALENDAR
[ "${1:-}" = --set ] && adb -s "$S" shell cmd package set-home-activity $P/.Home
adb -s "$S" shell am start -n $P/.Home
echo "installed; uid $uid; home: $(adb -s "$S" shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME | grep packageName | head -1)"
