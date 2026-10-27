# What the launcher needs from other tracks

## agentd
1. **Add `local.agentos.home` to `AGENTD_CLIENT_PKGS`** (default list) so the token is provisioned at start. Until then `launcher/install.sh` copies it as root.
2. **Approvals by chat/voice.** On `approval.needed` the sheet shows Approve/Deny and sends `{type:"ask", text:"Yes, send it."}` / `"No, don’t."` as a normal message. Today agentd answers `error busy` while a turn is running (the launcher hides that error). It needs to accept a message during a pending approval and route it to the broker.
3. **A tool-call passthrough** `{type:"rpc", method, params}` → `{type:"rpc.result", ...}` for `audit.tail`, `firewall.list/set`, `task.pause/stop`, so the launcher doesn't need the phone token. Today it calls :7777 directly (same pattern as the keyboard).
4. **Memory consent from the UI:** `consent_grant/revoke` have no approval UI; the Permissions screen shows sources read-only. A `{type:"consent", source, action}` message gated by fingerprint would let the toggle work.
5. The provider switch sends `provider:"codex"|"local"`; `local` needs llama-server running (`providers/local.js start`).

## android (tool server)
1. **`task.status {}`** → `{task_id, goal, apps, expires, steps, max_steps, paused}`. The home derives the active lease from `audit.tail` and assumes the default 600 s TTL for the countdown; the real `expires` would make the trust pill exact.
2. **`/events` stream** (mentioned as coming, with `allowed_channels`): the launcher will subscribe for approval/lease changes instead of polling `audit.tail` every 20 s.
3. `firewall.list` rule objects: the launcher treats any rule for a package as "blocked"; if allow-lists land, add `mode` per package.

## keyboard / island
- Shared tokens are in `web/theme.css` and `design/A2-tokens.md`; reuse `.glass`, `.mod`, `.orb`, `.chip`, `.btn`, the spring/soft/ease curves and the state colors.
