# Requests for the android/ owner (from agentd)

None of these block the demo. Done since the first version: taint carry-over and the lease card (thanks).

1. **Approval escalation for the reviewer.** When agentd's reviewer says `fingerprint` for an ordinary (tap-tier) approval, it can't raise the card, so it only shows advice. Please add `approval.escalate {approval_id, channel:"reviewer", to:"fingerprint", rationale}`: switch that pending approval to fingerprint-only (`allowed_channels:["fingerprint"]`, `fingerprint_because: "reviewer: <rationale>"`) and re-emit `approval.shown`. It can only make things stricter, so any allowed reviewer caller may call it. agentd will call it as soon as it exists (one line in `reviewer.js`).
   - agentd matches the shipped API (Broker.java/Events.java): `hello` on connect, `approval.confirm` with `voice`+`utterance` or `reviewer`+`rationale`, `approval.deny`, and `allowed_channels` to tell ordinary from fingerprint-only approvals.
2. **The launcher WebView's token.** agentd writes its WebSocket token to `/data/data/local.agentos/files/agentd.token` (owner = app uid, mode 600, app's SELinux label) at start-up. The launcher/island WebView can read it there and connect with the `agentos.bearer.<token>` subprotocol. The same goes for `local.agentos.keys`.
