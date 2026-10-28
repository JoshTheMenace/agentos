# Automatic offline provider

**Done 10-28 2:45 AM (Fable):** the ask sheet header shows a teal "On device" tag and a finished Home card says "On device · 6.8 s" when any of these events carries `provider: "local"` (`turn.started`, `provider.selected`, `text.delta`, `tool.request`, `turn.done`, `task.update`). Not on the island (the broker doesn't know the provider; pass a label through `task.stop` if wanted).

Original ask: show a quiet "On device" label when `turn.started`, `provider.selected`, streamed events or `turn.done` has `provider: "local"`. Background `task.update` includes `task.provider` (also in `task.list`), so the ask sheet/island can keep the label through completion. Clear it on the next `provider: "codex"` turn. The user's selected model stays Codex; automatic fallback does not change their toggle.

Voice workers use the same fallback after delegation. GPT Live audio/transcription still needs internet; this does not add offline speech recognition.

# From the bug hunt (10-28 night; details in pitch/rehearsal/BUGHUNT-NIGHT.md)

- **Done (Fable, 2:45 AM):** memory graph (top 28 by weight, test/localhost hidden, labels on people first), plugin rows (first clause only), Activity deep-mod lines + "Finished" without "0 steps", firewall card title ("Block httpbin.org for Beacon?", `Broker.approve`, rebuilt + installed). Shots: `launcher/shots/polish-night-2/`.
- **Memory graph is a hairball.** 194 nodes with overlapping labels (shot: pitch/rehearsal/bughunt/memory-graph.png). It also shows tonight's test fixtures as people/groups ("AgentOS test notification feed 17931…", "Pigeon fixture 0/1", "Test Dana", "Test Founder", "AgentOS test mark read …") and sites like `127.0.0.1:8099` and `localhost:8765`. Suggest: draw only the top ~25 people by weight, label only the biggest nodes, and hide test/localhost nodes in the view. Deleting them from memory is the founder's call.
- **Plugins rows** leak tool names and cut off mid-sentence ("Delivered notifications; linkedin_unread also checks unread conversations in the…"). Use one short human line per plugin.
- **Activity** shows raw "App deepmod disable / preview / integrate" lines (no `auditLine` case) and "Finished · 0 steps".
- **Firewall card title** still reads "Firewall.set block httpbin.org?" (ISSUES #8). Suggested title: "Block httpbin.org for Beacon?" (`Broker.approve()`, then rebuild local.agentos).
