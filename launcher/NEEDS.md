# What the launcher needs from other tracks

## agentd
1. ~~Add `local.agentos.home` to `AGENTD_CLIENT_PKGS`~~ done.
2. ~~Approvals by chat~~ dropped: there is no typed yes. Approvals are the native card (tap / fingerprint), voice, or the reviewer; the sheet only shows a one-line status from `approval.shown/resolved`.
3. ~~`rpc` passthrough~~ done (`audit.tail`, `approval.list`, `firewall.list/set`, `task.pause/resume/stop`). The launcher makes no `:7777` calls.
4. **Memory consent from the UI:** `consent_grant/revoke` have no approval UI; the Permissions screen shows sources read-only. A `{type:"consent", source, action}` message gated by fingerprint would let the toggle work.
5. The provider switch sends `provider:"codex"|"local"`; `local` needs llama-server running (`providers/local.js start`).

## android (tool server)
1. ~~`task.status`~~ covered by `task.state` events + `approval.list`'s `task` (real `expires`).
2. ~~`/events`~~ done: agentd forwards `task.state`, `audit.append`, `approval.*`, `guard.flag`; no more polling.
3. `firewall.list` rule objects: the launcher treats any rule for a package as "blocked"; if allow-lists land, add `mode` per package.

## keyboard / island
- Shared tokens are in `web/theme.css` and `design/A2-tokens.md`; reuse `.glass`, `.mod`, `.orb`, `.chip`, `.btn`, the spring/soft/ease curves and the state colors.
