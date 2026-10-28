# From the bug hunt (10-28 night; details in pitch/rehearsal/BUGHUNT-NIGHT.md)

- **Memory graph is a hairball.** 194 nodes with overlapping labels (shot: pitch/rehearsal/bughunt/memory-graph.png). It also shows tonight's test fixtures as people/groups ("AgentOS test notification feed 17931…", "Pigeon fixture 0/1", "Test Dana", "Test Founder", "AgentOS test mark read …") and sites like `127.0.0.1:8099` and `localhost:8765`. Suggest: draw only the top ~25 people by weight, label only the biggest nodes, and hide test/localhost nodes in the view. Deleting them from memory is the founder's call.
- **Plugins rows** leak tool names and cut off mid-sentence ("Delivered notifications; linkedin_unread also checks unread conversations in the…"). Use one short human line per plugin.
- **Activity** shows raw "App deepmod disable / preview / integrate" lines (no `auditLine` case) and "Finished · 0 steps".
- **Firewall card title** still reads "Firewall.set block httpbin.org?" (ISSUES #8). Suggested title: "Block httpbin.org for Beacon?" (`Broker.approve()`, then rebuild local.agentos).
