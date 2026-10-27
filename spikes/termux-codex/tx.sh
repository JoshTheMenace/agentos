#!/bin/sh
# Run a bash snippet ON the phone as the Termux app user, inside Termux's env.
# Usage: tx.sh 'codex --version'   (reads from stdin if no arg)
# The snippet is base64'd so adb/su never re-split or re-quote it.
S=${SERIAL:-ZY22J3TK8S}; P=/data/data/com.termux/files
B=$(printf '%s' "${1:-$(cat)}" | base64 | tr -d '\n')
exec adb -s "$S" shell "su 10401 -c 'export PREFIX=$P/usr HOME=$P/home TMPDIR=$P/usr/tmp PATH=$P/usr/bin LANG=en_US.UTF-8 LD_PRELOAD=$P/usr/lib/libtermux-exec.so; cd \$HOME; echo $B | base64 -d | $P/usr/bin/bash'"
