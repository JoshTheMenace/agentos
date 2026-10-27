#!/data/data/com.termux/files/usr/bin/sh
# The on-phone model server: llama-server (OpenAI-compatible API, tool calls via --jinja) on 127.0.0.1:8081.
# Usage: llama.sh start|stop|status          (agentd runs this too: `node providers/local.js start|stop|status`)
# Env: LOCAL_MODEL (gguf path), LOCAL_PORT (8081), LOCAL_CTX (4096), LOCAL_THREADS (2), LLAMA_SERVER
# Prefers our native build (~/agentos/llama/bin, from build.sh: uses the CPU's dot-product instructions);
# Termux's `pkg install llama-cpp` works too but is built for baseline ARMv8 and prefill is ~3x slower.
B=${LLAMA_SERVER:-$HOME/agentos/llama/bin/llama-server}; [ -x "$B" ] || B=llama-server
M=${LOCAL_MODEL:-$HOME/models/Qwen3.5-0.8B-Q4_0.gguf}; P=${LOCAL_PORT:-8081}
PIDF=${TMPDIR:-/tmp}/llama-server.pid; LOG=${TMPDIR:-/tmp}/llama-server.log
up() { curl -sf "http://127.0.0.1:$P/health" >/dev/null 2>&1; }
pid() { p=$(cat "$PIDF" 2>/dev/null) && kill -0 "$p" 2>/dev/null && echo "$p"; }
case "$1" in
start)
  up && { echo "already up on :$P"; exit 0; }
  [ -n "$(pid)" ] || { # 1 slot, 4096 ctx, --no-repack: ~220 MB anon + ~490 MB of reclaimable mmap'd model file. Repacking the
    # weights for ARM is ~25% faster but copies them into anon RAM (~650 MB), and on this 3.5 GB phone low-memory kills then take
    # Termux (agentd too) down. The router prompt is read once and cached, so a warm ask only reads ~20 tokens (1.3 s).
    cd "${PREFIX:-/usr}/lib" # Termux's build only finds its CPU backend (libggml-cpu.so) from here
    # 2 threads pinned to the 2 big cores (cpu6-7) beat 4-8 threads: the little cores only slow the big ones down
    nohup $(command -v taskset >/dev/null && echo taskset c0) "$B" -m "$M" --host 127.0.0.1 --port "$P" -c "${LOCAL_CTX:-4096}" -np 1 -t "${LOCAL_THREADS:-2}" \
      --jinja --no-webui -b 512 -ub 256 --cache-ram 128 --no-repack >"$LOG" 2>&1 &
    echo $! >"$PIDF"; }
  i=0; until up; do i=$((i + 1)); [ $i -gt 120 ] || [ -z "$(pid)" ] && { echo "failed, see $LOG"; tail -5 "$LOG"; exit 1; }; sleep 0.5; done
  echo "up on :$P (pid $(pid), $(awk '/VmRSS/{print $2/1024 " MB RSS"}' /proc/$(pid)/status))" ;;
stop) p=$(pid || pgrep -f "llama-server.*--port $P") && kill $p && echo "stopped $p" || echo "not running"; rm -f "$PIDF" ;; # pgrep: a stale pid file must not leave the port taken
status) up && echo "up on :$P (pid $(pid), $(awk '/VmRSS/{print $2/1024 " MB RSS"}' /proc/$(pid)/status 2>/dev/null))" || { echo "down"; exit 1; } ;;
*) echo "usage: $0 start|stop|status"; exit 2 ;;
esac
