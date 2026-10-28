#!/data/data/com.termux/files/usr/bin/sh
# The agentd loop. Runs as the Termux user, NOT root. Started by Magisk (magisk-service.sh, outside the Termux app, so
# lmkd killing Termux can't take agentd with it); Termux:Boot (~/.termux/boot/agentos-agentd) is the old way.
# Starts agentd and restarts it if it dies.
L="$TMPDIR/agentd-boot.lock"   # one loop only (Termux:Boot can queue the script more than once)
mkdir "$L" 2>/dev/null || { kill -0 "$(cat "$L/pid" 2>/dev/null)" 2>/dev/null && exit 0; }
echo $$ > "$L/pid"
[ -n "$AGENTOS_NATIVE" ] || termux-wake-lock 2>/dev/null   # under Magisk the boot script holds a kernel wake lock
export LD_PRELOAD="$PREFIX/lib/libtermux-exec.so" # Termux:Boot leaves it out; without it "#!/usr/bin/env node" (the claude CLI) can't start
export TZ="${TZ:-$(/system/bin/getprop persist.sys.timezone)}" # started outside the Termux app, node would run on UTC ("7 PM" schedules 6 h off)
cd "$HOME/agentos/agentd" || exit 1
if codex login status >/dev/null 2>&1; then
  export CODEX_BIN=codex                 # preferred: Termux's own Codex (non-root, no DNS proxy needed)
else
  export CODEX_BIN="$PWD/deploy/codex-root.sh"   # fallback: signed-in root lab Codex (starts its DNS proxy itself)
fi
while true; do
  node server.js >> "$TMPDIR/agentd.log" 2>&1
  sleep 5
done
