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
- keyboard/ `local.agentos.keys`: A2 AI keyboard, set as the default IME (crash on hide fixed 10-27).
- demo-apps/: Pigeon (canary chat) and Beacon (tracker demo).
- agentd/memory: consent ledger, ingestion, graph.json.
- Local model: Qwen3.5-0.8B on the phone via llama-server :8081 (off by default; `agentd/deploy/local-model/llama.sh start`). Use it for the airplane-mode demo "open Pigeon and read me the last message from Mom" (12 s from the launcher in airplane mode). The old fix-ups are done (approval wait 60 s card / 90 s RPC; localUrl 8081).

## In progress when this was written (check each folder's README/NEEDS for state)
- launcher DONE and set as default home (launcher/README.md). Follow-ups done: home is in AGENTD_CLIENT_PKGS, agentd `rpc` passthrough (no :7777 token in the launcher), live state from agentd-forwarded broker events (no polling), inline typed approve buttons removed (the native card is the only gate). Left: memory consent toggle (launcher/NEEDS.md).
- android/ island + approval card restyle (Fable). The island now follows task state (working/step/pause/hide, auto-approved moment, guard alert), and Send cards show the drafted message. The back-to-back card bug is fixed (android/NEEDS-UI.md).
- Reviewer is DONE (agentd/reviewer.js, deployed), including `approval.escalate` to fingerprint. Retuned 10-27: a dictated send to the named contact is low risk → auto-approved ("Auto-approved ✓" seen on the phone, 2.0 s). Fingerprint-floor and flagged tasks are never approved. Tests: `node agentd/reviewer-test.js` (14/14).

## Waiting on the user
- Fingerprint e2e test at the phone: `python3 android/tools-client.py e2e --press-approve`.
- Smart plug brand.

## Rehearsal (10-27, pitch/rehearsal/)
All beats ran on the phone; shots, timings and the presenter checklist are in `pitch/rehearsal/RUN.md`, open issues in `ISSUES.md`. Pass: home, ask sheet, keyboard, Pigeon task + lease tap, auto-approve ✓, trap → alert, P3 block, fingerprint card + system prompt, firewall card + root fallback, local model in airplane mode, audit chain. Needs the presenter: speaking (voice), the finger (fingerprint, firewall). Order matters: a guard flag makes every send need a fingerprint for 10 min, so auto-approve runs before the canary is staged (`--es scenario clean`).

## Next up
1. Fingerprint press at the phone (beats 7, 8), and the open items in pitch/rehearsal/ISSUES.md. Update demo-script.md to the RUN.md order.
2. Watch app / plug (optional), the deck visuals (Fable) and a 2-minute backup video.

Device rule: `mkdir /tmp/agentos-device.lock` before state-changing adb, and `rmdir` only if your mkdir succeeded.
