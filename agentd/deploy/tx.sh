#!/bin/sh
# Run a bash snippet ON the phone as the Termux user (uid 10401) inside Termux's env, from the Mac.
# Like spikes/termux-codex/tx.sh, plus the inet group (3003): without it Android blocks the process's network.
# Usage: agentd/deploy/tx.sh 'codex --version'   (reads stdin if no arg)
S=${SERIAL:-ZY22J3TK8S}; U=${TERMUX_UID:-10401}; P=/data/data/com.termux/files
B=$(printf '%s' "${1:-$(cat)}" | base64 | tr -d '\n')
exec adb -s "$S" shell "su $U -g $U -G 3003 -c 'export PREFIX=$P/usr HOME=$P/home TMPDIR=$P/usr/tmp PATH=$P/usr/bin LANG=en_US.UTF-8 LD_PRELOAD=$P/usr/lib/libtermux-exec.so; cd \$HOME; echo $B | base64 -d | $P/usr/bin/bash'"
