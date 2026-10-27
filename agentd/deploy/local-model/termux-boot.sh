#!/data/data/com.termux/files/usr/bin/sh
# Termux:Boot entry for the on-phone model (install as ~/.termux/boot/agentos-local-model).
# OFF by default: the model costs ~0.5-0.6 GB of RAM. Turn on with `touch ~/.agentos/local-model.on`.
[ -f "${AGENTOS_HOME:-$HOME/.agentos}/local-model.on" ] || exit 0
sleep 30 # let agentd and Codex start first
exec sh "$HOME/agentos/agentd/deploy/local-model/llama.sh" start
