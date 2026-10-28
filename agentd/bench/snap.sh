#!/bin/sh
# The worktree is shared with other agents: deploy the bench agentd from a clean snapshot = HEAD's agentd/ plus only the
# files named in MINE (space-separated paths under agentd/), so their uncommitted work never skews a measurement.
#   MINE="providers/codex.js tools.js" agentd/bench/snap.sh [env for up.sh...]   (REV=<commit>: an older agentd)
set -e
R=$(git -C "$(dirname "$0")" rev-parse --show-toplevel); D=/tmp/agentd-snap
rm -rf $D; mkdir -p $D; git -C "$R" archive ${REV:-HEAD} agentd | tar -x -C $D
for f in $MINE; do cp "$R/agentd/$f" "$D/agentd/$f"; done
AGENTD_SRC=$D/agentd exec sh "$R/agentd/bench/up.sh"
