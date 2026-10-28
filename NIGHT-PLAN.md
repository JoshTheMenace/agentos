# Overnight plan (10-28, 1 AM → morning)

Limits: at most 3 Claude children at once. GPT-6.1 Sol children run alongside. If a provider hits its usage limit, pause that track until the limit resets. UI work goes to Fable.
Rules for every child: run state-changing adb steps through `scripts/withphone`. Never tap approval cards, never message or call real contacts, and keep existing approval rules intact. Run `node --check` on every changed agentd file before deploying, and leave the phone demo-ready after each step. Commit and push only your own files. Code freeze is at noon; judging is at 5 PM.
Boundaries: code-level injection (Frida/LSPosed) only into apps we own (ARCHITECTURE rule 4). Third-party apps get only supported surfaces: notifications, notification reply actions, official APIs and the accessibility screen. No private-API reverse engineering.

## Running / queue (top = next)
| # | Track | Who | State |
|---|---|---|---|
| 1 | Codex action speed hill-climb (cross-app tasks, screen format, round trips, tools-first) | Claude Opus | running |
| 2 | Island below the camera hole, then UI polish pass 1 | Claude Fable | running |
| 3 | Keyboard autocomplete latency hill-climb | Claude Opus | running |
| S1 | Live notifications + reply actions + official APIs (fresh messages, Slack/IG/LinkedIn) | GPT-6.1 Sol | running |
| S2 | Deep-mod engine research + design on our own apps (no device changes yet) | GPT-6.1 Sol | running |
| 4 | Wake word (Porcupine): everything except the key | Claude | queued |
| 5 | Voice/GPT Live latency hill-climb | Claude | queued |
| 6 | Reviewer light polish | Claude | queued |
| 7 | Bug hunt across demo flows | Claude | queued |
| 8 | UI polish pass 2 | Claude Fable | queued |
| 9 | Research: impressive additions that fit existing demo beats | Claude | queued |
| S3 | Deep-mod implementation on our own apps (LAST; may need Zygisk/reboot) | GPT-6.1 Sol | queued, last |
