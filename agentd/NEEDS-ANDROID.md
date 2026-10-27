# Requests for the android/ owner (from agentd)

None of these block the demo. Done since the first version: taint carry-over and the lease card (thanks).

1. **Approval escalation for the reviewer: DONE (2026-10-27).** `approval.escalate {approval_id, to:"fingerprint", rationale}` (see android/README.md). `reviewer.js` calls it on a `fingerprint` verdict, and skips the re-sent `approval.shown` (`escalated: true`). Tested on the phone in a real turn.
   - agentd matches the shipped API (Broker.java/Events.java): `hello` on connect, `approval.confirm` with `voice`+`utterance` or `reviewer`+`rationale`, `approval.deny`, and `allowed_channels` to tell ordinary from fingerprint-only approvals.
2. **The launcher WebView's token.** agentd writes its WebSocket token to `/data/data/local.agentos/files/agentd.token` (owner = app uid, mode 600, app's SELinux label) at start-up. The launcher/island WebView can read it there and connect with the `agentos.bearer.<token>` subprotocol. The same goes for `local.agentos.keys`.
