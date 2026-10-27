#!/data/data/com.termux/files/usr/bin/sh
# Fallback CODEX_BIN for the phone: run the signed-in root lab Codex (research/00) through Magisk su.
# Root Codex reaches the network through android-connect-proxy on 127.0.0.1:18080, so start it if it isn't up
# (agentd re-runs this script whenever app-server restarts). Every argument is single-quoted so `su -c` can't re-split it.
su -c 'ss -ltn | grep -q 127.0.0.1:18080 || (cd /data/local/tmp/android-agent-lab && setsid ./android-connect-proxy 18080 > /data/local/tmp/agentos-connect-proxy.log 2>&1 < /dev/null &)'
q=; for a; do q="$q '$(printf %s "$a" | sed "s/'/'\\\\''/g")'"; done
exec su -c "sh /data/local/tmp/agentos-codex-env.sh$q"
