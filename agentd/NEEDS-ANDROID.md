# Requests for the android/ owner (from agentd)

None of these block the demo; agentd works around each one today.

1. **Carry the taint across `task.start`.** A new `task.start` replaces the lease and resets `untrusted`, so a caller could launder the Rule of Two by re-leasing after reading the screen. agentd re-reads the screen inside the new lease to re-taint it. Simpler and safer: keep `untrusted` (and the step count) when the same caller replaces a task within its TTL, or accept `task.start {..., untrusted:true}`.
2. **Tell the caller when the card goes up.** The call just blocks, so agentd *guesses* (`risk:"T3"`, `submit`, secure screen, firewall) to send `approval.needed` early. It can't predict the T2 "untrusted + private" case. Option: a `GET /events` stream (or `approval.list`) that reports `{task_id, tool, target, tier, rule_of_two, expires}` while a card is shown.
3. **The launcher WebView's token.** agentd now writes its WebSocket token to `/data/data/local.agentos/files/agentd.token` (owner = app uid, mode 600, app's SELinux label) at start-up. The launcher/island WebView can read it there and connect with the `agentos.bearer.<token>` subprotocol. The same goes for `local.agentos.keys`.
4. **`task.start` from a non-human caller.** Already listed as a known limit. When there's time, a one-tap "allow agent in Pigeon for 10 min" card would close it.
