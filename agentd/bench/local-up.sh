#!/bin/sh
# Start a SEPARATE test agentd for the local model on the phone (port 8790, own state dir), from this checkout.
# The live agentd (8787) and the speed bench (8788) are never touched. OLD=1 uses the committed providers/local.js (baseline).
# Then run bench/local-tasks.js from the Mac (it forwards tcp:18790).
set -e
cd "$(dirname "$0")/.."
S=${SERIAL:-ZY22J3TK8S}; T=/tmp/local-agentd; rm -rf $T; mkdir -p $T
COPYFILE_DISABLE=1 tar --no-xattrs --exclude node_modules --exclude runs --exclude bench -cf - . | tar -xf - -C $T
[ -z "$OLD" ] || git show HEAD:agentd/providers/local.js > $T/providers/local.js
COPYFILE_DISABLE=1 tar --no-xattrs -czf /tmp/local-agentd.tgz -C $T .
adb -s "$S" push /tmp/local-agentd.tgz /data/local/tmp/local-agentd.tgz >/dev/null
adb -s "$S" shell su -c 'chmod 644 /data/local/tmp/local-agentd.tgz'
PT=$(adb -s "$S" shell su -c 'cat /data/data/local.agentos/files/token' | tr -d '\r\n')
deploy/tx.sh "pkill -f 'node server.js --localbench' ; sleep 1
D=~/agentos/local-agentd; rm -rf \$D; mkdir -p \$D; cd \$D; tar xzf /data/local/tmp/local-agentd.tgz; ln -s ~/agentos/agentd/node_modules node_modules
export PHONE_TOKEN=$PT AGENTD_PORT=8790 AGENTOS_HOME=\$HOME/.agentos-local AGENTD_CLIENT_PKGS= AGENTD_PROVIDER=local REVIEW_MODEL=off ${LOCAL_MODEL:+LOCAL_MODEL=$LOCAL_MODEL} ${AGENTD_SCREEN:+AGENTD_SCREEN=$AGENTD_SCREEN}
setsid nohup node server.js --localbench > \$HOME/local-agentd.log 2>&1 < /dev/null &
sleep 5; tail -2 \$HOME/local-agentd.log"
adb -s "$S" forward tcp:18790 tcp:8790 >/dev/null
