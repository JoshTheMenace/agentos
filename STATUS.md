# AgentOS status / handoff (2026-10-27)

Judging: Wed 2026-10-28, 5 PM. Read ARCHITECTURE.md first. Research is in research/. Pitch drafts are in pitch/.

## Decisions
- UI: **A2 · Calm Glass iOS Dark** (design/mockups/A2-calm-glass-ios-dark.html, tokens in design/A2-tokens.md). Keep text minimal and use progressive disclosure. Permissions go on their own screen. Keep the Watch/plug widgets small. Say "Fingerprint", never "Touch ID".
- **All UI work goes to Fable agents** (model: fable).
- Approvals have three levels: the reviewer model auto-approves low-risk actions with no taint (spikes/codex-reviewer). A real human **tap** handles normal outward actions. A **fingerprint** is required for risky ones (guard flag, copied-from-screen args, money/delete/install/firewall, or all 3 Rule-of-Two lamps). Voice "send it" is optional. There's no typed yes.
- Demo providers: Codex (ChatGPT Pro on phone) plus a local model. Claude is mentioned only as pluggable.
- Must-haves: phone control + broker, fingerprint approvals, AI keyboard, Prompt Guard, firewall, GPT-live voice, A2 UI. Cut order: watch → plug → memory graph.
- Founder is Utah-based with an LLC. Real apps (e.g. Messages) may be operated. Frida/inspection only on our own apps. No Duolingo.

## Done (on the phone, committed)
- android/ `local.agentos`: tools, broker, leases, Rule of Two, fingerprint/tap/voice/reviewer approvals, audit chain, firewall, on-device Prompt Guard 2, /events SSE.
- agentd/: Codex app-server on the phone (root wrapper, 0.163), dynamicTools, guard, memory, voice relay (GPT live via subscription works), suggest, events, voice approvals, reviewer hook.
- keyboard/ `local.agentos.keys`: A2 AI keyboard (Gboard is currently re-selected as default).
- demo-apps/: Pigeon (canary chat) and Beacon (tracker demo).
- agentd/memory: consent ledger, ingestion, graph.json.
- Local model: Qwen3.5-0.8B on the phone via llama-server :8081 (off by default; `agentd/deploy/local-model/llama.sh start`). Use it for the airplane-mode demo "open Pigeon and read me the last message from Mom". Fix-ups for the agentd owner: tools.js task-approval timeout is 10 s (needs ≥75 s), config.js localUrl default should be 8081.

## In progress when this was written (check each folder's README/NEEDS for state)
- launcher/ `local.agentos.home` (Fable): home, command center, permissions, voice, memory graph.
- android/ island + approval card restyle (Fable). Known bug: a card that opens within about 2 s of the last one closing is lost (android/NEEDS-UI.md).
- Reviewer is DONE (agentd/reviewer.js, deployed). Broker still needs `approval.escalate` (agentd/NEEDS-ANDROID.md).
- Pigeon IME send-action build isn't installed yet: `adb install -r demo-apps/pigeon/build/pigeon.apk`.

## Waiting on the user
- Fingerprint e2e test at the phone: `python3 android/tools-client.py e2e --press-approve`.
- Smart plug brand.

## Next up
1. Integrate everything on the phone, then run the full demo-script.md rehearsal.
2. screen.read should skip views not visible to the user (keyboard/NEEDS.md).
3. Watch app / plug (optional), the deck visuals (Fable) and a 2-minute backup video.

Device rule: `mkdir /tmp/agentos-device.lock` before state-changing adb, and `rmdir` only if your mkdir succeeded.
