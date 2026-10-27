#!/bin/sh
# Stand-in for `codex` that runs the PHONE's app-server (root lab install, 0.163) over adb stdio.
# Usage: CODEX_BIN=spikes/voice/phone-codex.sh node spikes/voice/relay.mjs   (args from relay are ignored)
exec adb -s "${SERIAL:-ZY22J3TK8S}" shell -T "su -c 'sh /data/local/tmp/agentos-codex-env.sh app-server -c features.shell_tool=false -c features.apps=false'"
