#!/bin/sh
# Installs build/keys.apk, hands the IME its two bearer tokens (root copy into its private dir), and enables it.
# Usage: keyboard/install.sh [--set]      --set also makes it the active keyboard
#        keyboard/install.sh gboard       restore Gboard
# Holds the shared device lock while it runs (ARCHITECTURE.md rule 5).
set -eu
cd "$(dirname "$0")"
S=${ANDROID_SERIAL:-ZY22J3TK8S}
IME=local.agentos.keys/.Keys
GBOARD=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
until mkdir /tmp/agentos-device.lock 2>/dev/null; do echo "device busy (lock held), retrying..."; sleep 5; done
trap 'rmdir /tmp/agentos-device.lock' EXIT
if [ "${1:-}" = gboard ]; then adb -s "$S" shell ime set $GBOARD; exit; fi
adb -s "$S" install -r build/keys.apk
uid=$(adb -s "$S" shell pm list packages -U local.agentos.keys | grep "^package:local.agentos.keys " | sed "s/.*uid://" | tr -d '\r')
# Tokens: agentd's (Termux home) and the AgentOS tool server's (app private dir). Only root can read them; copy, chown, relabel.
D=/data/data/local.agentos.keys/files
adb -s "$S" shell "su -c 'mkdir -p $D && cp /data/data/com.termux/files/home/.agentos/agentd.token $D/agentd.token && cp /data/data/local.agentos/files/token $D/phone.token && chown -R $uid:$uid $D && chmod 600 $D/*.token && restorecon -RD /data/data/local.agentos.keys'"
until adb -s "$S" shell ime enable $IME | grep -q "now enabled"; do sleep 1; done   # the IME list refreshes a moment after install
[ "${1:-}" = --set ] && adb -s "$S" shell ime set $IME
echo "installed; uid $uid; current IME: $(adb -s "$S" shell settings get secure default_input_method)"
