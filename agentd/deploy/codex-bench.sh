#!/data/data/com.termux/files/usr/bin/sh
# CODEX_BIN for the BENCH agentd only (bench/up.sh BENCH_CODEX=codex-bench.sh): codex-root.sh, but Codex's proxy is
# 127.0.0.1:18081, so the bench can try another network path (bench/usb-net.js PHONE_PORT=18081 = the Mac's internet)
# without touching the live agentd's proxy on 18080. If nothing listens on 18081, a second phone proxy is started there.
su -c 'ss -ltn | grep -q 127.0.0.1:18081 || (cd /data/local/tmp/android-agent-lab && setsid ./android-connect-proxy 18081 > /data/local/tmp/bench-connect-proxy.log 2>&1 < /dev/null &)'
su -c 'sed s/18080/18081/g /data/local/tmp/agentos-codex-env.sh > /data/local/tmp/agentos-codex-env-bench.sh'
q=; for a; do q="$q '$(printf %s "$a" | sed "s/'/'\\\\''/g")'"; done
exec su -c "sh /data/local/tmp/agentos-codex-env-bench.sh$q"
