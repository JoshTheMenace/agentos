#!/bin/sh
# Start a SEPARATE bench agentd on the phone (port 8788, own state dir) from this checkout, so the live one on 8787
# is never touched. Extra env (CODEX_MODEL=..., CODEX_EFFORT=...) is passed through: `CODEX_MODEL=gpt-6.1-terra agentd/bench/up.sh`.
# NOCOPY=1 restarts the copy already on the phone (e.g. to try another model on the same code).
# Then: adb forward tcp:18788 tcp:8788 (done here) and run bench.js from the Mac.
set -e
cd "${AGENTD_SRC:-$(dirname "$0")/..}" # AGENTD_SRC: deploy another copy (bench/snap.sh)
S=${SERIAL:-ZY22J3TK8S}
COPYFILE_DISABLE=1 tar --no-xattrs --exclude node_modules --exclude runs --exclude bench -czf /tmp/bench-agentd.tgz .
adb -s "$S" push /tmp/bench-agentd.tgz /data/local/tmp/bench-agentd.tgz >/dev/null
adb -s "$S" shell su -c 'chmod 644 /data/local/tmp/bench-agentd.tgz'
PT=$(adb -s "$S" shell su -c 'cat /data/data/local.agentos/files/token' | tr -d '\r\n')
VARS=""; for v in CODEX_MODEL CODEX_EFFORT AGENTD_SCREEN REVIEW_MODEL AGENTD_DEBUG CODEX_SERVICE_TIER CODEX_FINISH AGENTD_PROMPT_PATCH AGENTD_TOOLS VOICE_VISION VISION_MODEL; do eval "x=\${$v-}"; [ -n "$x" ] && VARS="$VARS $v=$x"; done
deploy/tx.sh "pkill -f 'node server.js --bench' ; sleep 1
D=~/agentos/bench-agentd; [ -n \"$NOCOPY\" ] || { rm -rf \$D; mkdir -p \$D; cd \$D; tar xzf /data/local/tmp/bench-agentd.tgz; ln -s ~/agentos/agentd/node_modules node_modules; }; cd \$D
export PHONE_TOKEN=$PT AGENTD_PORT=8788 AGENTOS_HOME=\$HOME/.agentos-bench AGENTD_CLIENT_PKGS= CODEX_BIN=\$D/deploy/codex-root.sh $VARS
setsid nohup node server.js --bench > \$HOME/bench-agentd.log 2>&1 < /dev/null &
sleep 6; tail -3 \$HOME/bench-agentd.log; echo vars:$VARS"
adb -s "$S" forward tcp:18788 tcp:8788 >/dev/null
