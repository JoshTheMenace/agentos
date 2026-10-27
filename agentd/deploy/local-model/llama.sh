#!/data/data/com.termux/files/usr/bin/sh
# The on-phone model server: llama-server (OpenAI-compatible API, tool calls via --jinja) on 127.0.0.1:8081.
# Usage: llama.sh start|stop|status          (agentd runs this too: `node providers/local.js start|stop|status`)
# Env: LOCAL_MODEL (gguf path), LOCAL_PORT (8081), LOCAL_CTX (8192 = 2 slots x 4096), LOCAL_THREADS (4)
M=${LOCAL_MODEL:-$HOME/models/Qwen3.5-0.8B-Q4_0.gguf}; P=${LOCAL_PORT:-8081}
PIDF=${TMPDIR:-/tmp}/llama-server.pid; LOG=${TMPDIR:-/tmp}/llama-server.log
up() { curl -sf "http://127.0.0.1:$P/health" >/dev/null 2>&1; }
pid() { p=$(cat "$PIDF" 2>/dev/null) && kill -0 "$p" 2>/dev/null && echo "$p"; }
case "$1" in
start)
  up && { echo "already up on :$P"; exit 0; }
  [ -n "$(pid)" ] || { # 2 slots: one for the agent, one for keyboard suggestions, so neither evicts the other's cache
    nohup llama-server -m "$M" --host 127.0.0.1 --port "$P" -c "${LOCAL_CTX:-8192}" -np 2 -t "${LOCAL_THREADS:-4}" \
      --jinja --no-webui --cache-reuse 256 >"$LOG" 2>&1 &
    echo $! >"$PIDF"; }
  i=0; until up; do i=$((i + 1)); [ $i -gt 120 ] || [ -z "$(pid)" ] && { echo "failed, see $LOG"; tail -5 "$LOG"; exit 1; }; sleep 0.5; done
  echo "up on :$P (pid $(pid), $(awk '/VmRSS/{print $2/1024 " MB RSS"}' /proc/$(pid)/status))" ;;
stop) p=$(pid) && kill "$p" && echo "stopped $p" || echo "not running"; rm -f "$PIDF" ;;
status) up && echo "up on :$P (pid $(pid), $(awk '/VmRSS/{print $2/1024 " MB RSS"}' /proc/$(pid)/status 2>/dev/null))" || { echo "down"; exit 1; } ;;
*) echo "usage: $0 start|stop|status"; exit 2 ;;
esac
