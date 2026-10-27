# watch/ needs from other owners

## agentd (owner: agentd agent)
1. **Reach agentd without the Mac (most useful).** agentd listens only on 127.0.0.1, so today the watch reaches it through an adb tunnel on the Mac. Ask: an optional second listener, e.g. `AGENTD_WATCH_HOST=<wlan0 or hotspot IP>:8788`, that
   - accepts only a separate watch token (`~/.agentos/watch.token`, made like `agentd.token`), never the main token;
   - allows only `ask` and `stop` in, and forwards the broadcast events out (no `rpc`, no `suggest`, no voice);
   - binds to one interface (the hotspot), not 0.0.0.0. Analogy: a side door that only opens to the watch's key and only leads to the lobby.
   The watch app already reads `files/agentd.addr` (`host:port`), so no watch code changes.
2. **Approve on the wrist.** Add a `watch` channel: the broker puts it in `allowed_channels` for ordinary (tap-tier) approvals, and agentd accepts `{type:"approve", approval_id, decision}` from the watch connection only, passing `channel:"watch"`. Deny is always allowed. Fingerprint-floor approvals stay phone-only.
3. **(Pitch-grade, larger) Watch as a second factor.** Watch app makes an EC P-256 key in its own Keystore; phone pins the public key; for sensitive actions the broker sends `{id, summary, sha256(action), nonce, expires}` and runs the action only with a valid watch signature. Design: research/06-devices-integrations.md, "Level 2". About 3-4 h across watch + broker.

## Nice to have
- A tiny `status` snapshot on connect (current task goal + pending approval), so a watch that connects mid-task shows the goal instead of "Working".
