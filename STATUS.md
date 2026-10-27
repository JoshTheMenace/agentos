# AgentOS status / handoff (2026-10-27)

Judging: Wed 2026-10-28, 5 PM. Read ARCHITECTURE.md first. Research is in research/. Pitch drafts are in pitch/.

## Decisions (rewritten 10-27 9:30 AM from founder direction; supersedes the older ones)
- **Product: an AI-native phone OS ("the Siri we should have"), built security-first.** The demo should be mostly "whoa" capability; security is a supporting thread, not the whole show.
- **Live demo uses real apps only** (no Pigeon/Beacon on stage; they stay as test fixtures). Real apps may be operated and, for the app-modification demo, modified on our own phone.
- **Demo order and content: the founder is writing it.** Old pitch/demo-script.md and pitch/rehearsal/RUN.md are out of date; don't polish them.
- **Approvals:** fingerprint ONLY for the most sensitive things (payments, using a vault password, and similar). Everything else is reviewer auto-approve or a tap. No red alert pop-ups: blocked/flagged actions go to a log (command center / settings). Island still shows what the agent is doing.
- **Voice is the main way to start tasks.** GPT Live doesn't do the task; it starts a background Codex thread (with a task system prompt), gets its status ("how's it going?"), and says when it's done.
- **Memory + notifications:** agents know recent notifications and cross-app context, so "reply back that I'm 10 min late" works without naming the person or app. Users can tell agents to handle notifications/replies for them.
- **Home screen:** no dummy data (no lamp, no heart rate, no "no active grants"). Show what agents are doing now and proactive suggestions.
- **Firewall demo:** in a real app, ask by voice "any trackers?", it finds them, "block them", it does; a blockers view lists what's blocked per app.
- **Speed is a top priority:** hill-climb task latency (screen format, fewer round trips, Codex computer-use ideas, faster models e.g. GPT-6.1 Terra if available).
- **Explore:** Claude Code on the phone as a provider (like Codex app-server), the Galaxy Watch, live-modifying a real app (e.g. calculator/camera: add a button), password vault + fingerprint fill (stretch).
- UI: **A2 · Calm Glass iOS Dark** (design/mockups/A2-calm-glass-ios-dark.html, tokens in design/A2-tokens.md). Minimal text, progressive disclosure. Say "Fingerprint", never "Touch ID". **All UI work goes to Fable agents** (model: fable).
- Founder is Utah-based with an LLC. No Duolingo. Code freeze Wed noon; judging Wed 5 PM.

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
- Claude on the phone: token installed 10-27 (`~/.agentos/claude.token`, works: `claude -p` ok in 10 s). Copied the new claude-cli.js; needs one agentd restart, then benchmark `node cli.js --provider claude`. Role: Codex is default; Claude is shown only as "you can switch brains".
- Watch: founder to enable Wi-Fi debugging on the Watch4 and send IP:port + pairing code (watch/README.md).
- Fingerprint e2e test at the phone: `python3 android/tools-client.py e2e --press-approve`.
- Smart plug brand.

## Rehearsal (10-27, pitch/rehearsal/)
All beats ran on the phone; shots, timings and the presenter checklist are in `pitch/rehearsal/RUN.md`, open issues in `ISSUES.md`. Pass: home, ask sheet, keyboard, Pigeon task + lease tap, auto-approve ✓, trap → alert, P3 block, fingerprint card + system prompt, firewall card + root fallback, local model in airplane mode, audit chain. Needs the presenter: speaking (voice), the finger (fingerprint, firewall). Order matters: a guard flag makes every send need a fingerprint for 10 min, so auto-approve runs before the canary is staged (`--es scenario clean`).

## Next up
1. Fingerprint press at the phone (beats 7, 8), and the open items in pitch/rehearsal/ISSUES.md. Update demo-script.md to the RUN.md order.
2. Watch app / plug (optional), the deck visuals (Fable) and a 2-minute backup video.

Device rule: `mkdir /tmp/agentos-device.lock` before state-changing adb, and `rmdir` only if your mkdir succeeded.
