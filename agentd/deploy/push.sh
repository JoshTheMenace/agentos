#!/bin/sh
# Copy agentd files to the phone's Termux checkout and restart agentd (the Magisk boot loop brings it back in ~5 s with the
# right capabilities; never start it through tx.sh, see README "Run on the phone"). Holds the shared device lock.
#   agentd/deploy/push.sh apps.js server.js          (paths relative to agentd/)
#   NORESTART=1 agentd/deploy/push.sh apps.js        copy only
set -eu; cd "$(dirname "$0")/.."
S=${SERIAL:-ZY22J3TK8S}; H=/data/data/com.termux/files/home/agentos/agentd; T=/data/local/tmp/agentd-push
until mkdir /tmp/agentos-device.lock 2>/dev/null; do sleep 3; done; trap 'rmdir /tmp/agentos-device.lock' EXIT
adb -s "$S" shell "rm -rf $T; mkdir -p $T"
L=$(adb -s "$S" shell "su -c 'stat -c %C $H/server.js'" | tr -d '\r') # the Termux app's own SELinux label (toybox chcon has no --reference)
for f in "$@"; do adb -s "$S" push "$f" "$T/$f" >/dev/null; adb -s "$S" shell "su -c 'mkdir -p $H/$(dirname $f) && cp $T/$f $H/$f && chown 10401:10401 $H/$f && chcon $L $H/$f'"; echo "pushed $f"; done
adb -s "$S" shell "rm -rf $T"
[ "${NORESTART:-}" = 1 ] && exit 0
adb -s "$S" shell "su -c 'pkill -f \"^node server.js\"'" || true
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do sleep 2; if adb -s "$S" shell "su 10401 -c 'curl -s -m 2 http://127.0.0.1:8787/health'" 2>/dev/null | grep -q ok; then echo "agentd restarted, /health ok"; exit 0; fi; done
echo "agentd did not come back within 24 s" >&2; exit 1
