#!/data/data/com.termux/files/usr/bin/sh
# Termux:Boot entry (install as ~/.termux/boot/agentos-agentd). Runs as the Termux app user, NOT root.
# Starts agentd at boot and restarts it if it dies.
L="$TMPDIR/agentd-boot.lock"   # one loop only (Termux:Boot can queue the script more than once)
mkdir "$L" 2>/dev/null || { kill -0 "$(cat "$L/pid" 2>/dev/null)" 2>/dev/null && exit 0; }
echo $$ > "$L/pid"
termux-wake-lock 2>/dev/null
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
