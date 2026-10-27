# Requests for the android/ owner (from agentd)

None of these block the demo; agentd works around each one today.

1. **Approval API: the shapes agentd is built against** (stub: `agentd/mock-phone.js`; please match it or tell me the differences):
   - `GET /events` with `Authorization: Bearer`, `text/event-stream`. **Send the headers at once** (and a `:` keep-alive comment every ~15 s); agentd only counts as connected once headers arrive. Each event is `event: <type>` + one `data: <json>` line, and the JSON also has `type`.
   - `approval.shown {approval_id, tool, pkg, target, text, tier, task_id, rule_of_two, expires, allowed_channels:["fingerprint","tap","voice","reviewer"...]}` · `approval.resolved {approval_id, decision:"approved"|"denied", channel, reason}` · `guard.flag {...}` · `task.state {...}` (forwarded to UIs as-is).
   - RPC `approval.list {}` → `{ok, approvals:[<approval.shown payload>]}`; `approval.confirm {approval_id, channel, utterance?, rationale?}` and `approval.deny {approval_id, channel, utterance?}` → `{ok, reason?}`. If deny is meant to be `approval.confirm {..., decision:"deny"}` instead, it's a one-line change in `events.js`.
   - Channels agentd uses: `voice` (the user's own spoken yes/no), `tap` (the launcher's mirrored Approve button; accept it only if you trust the launcher), `reviewer` (hook, not built). agentd never sends `chat`.
2. **Carry the taint across `task.start`.** A new `task.start` replaces the lease and resets `untrusted`, so a caller could launder the Rule of Two by re-leasing after reading the screen. agentd re-reads the screen inside the new lease to re-taint it. Simpler and safer: keep `untrusted` (and the step count) when a task is replaced within its TTL, or accept `task.start {..., untrusted:true}`.
3. **The launcher WebView's token.** agentd writes its WebSocket token to `/data/data/local.agentos/files/agentd.token` (owner = app uid, mode 600, app's SELinux label) at start-up. The launcher/island WebView can read it there and connect with the `agentos.bearer.<token>` subprotocol. The same goes for `local.agentos.keys`.
4. **`task.start` from a non-human caller.** Already listed as a known limit. A one-tap "allow agent in Pigeon for 10 min" card would close it.
