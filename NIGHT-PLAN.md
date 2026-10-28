# Overnight plan (10-28, 1 AM → morning)

Limits: at most 3 Claude children at once. GPT-6.1 Sol children run alongside. If a provider hits its usage limit, pause that track until the limit resets. UI work goes to Fable.
Rules for every child: run state-changing adb steps through `scripts/withphone`. Never tap approval cards, never message or call real contacts, and keep existing approval rules intact. Run `node --check` on every changed agentd file before deploying, and leave the phone demo-ready after each step. Commit and push only your own files. Code freeze is at noon; judging is at 5 PM.
Boundaries: code-level injection (Frida/LSPosed) only into apps we own (ARCHITECTURE rule 4). Third-party apps get only supported surfaces: notifications, notification reply actions, official APIs and the accessibility screen. No private-API reverse engineering.

## Running / queue (top = next)
| # | Track | Who | State |
|---|---|---|---|
| 1 | Codex action speed hill-climb (cross-app tasks, screen format, round trips, tools-first) | Claude Opus | running |
| 2 | Island below the camera hole, then UI polish pass 1 | Claude Fable | done (8e56541) |
| 3 | Keyboard autocomplete latency hill-climb | Claude Opus | running |
| S1 | Live notifications + reply actions + official APIs | GPT-6.1 Sol | done (6928905, 660377b; reads ~20 ms, reply turn 32 s → handed to speed track) |
| S2 | Deep-mod engine research + design on our own apps | GPT-6.1 Sol | done (DEEP-MODS.md: cooperative signed spec, no injection or reboot) |
| 4 | Wake word (Porcupine): everything except the key | Claude | running |
| 5 | Voice/GPT Live latency hill-climb | GPT-6.1 Sol | running |
| 6 | Reviewer light polish | Claude | queued |
| 7 | Bug hunt across demo flows (incl. Home tracker card naming Duolingo; tools-client.py card tap spots moved +77 px) | Claude | queued |
| 8 | UI polish pass 2 | Claude Fable | queued |
| 9 | Research: impressive additions that fit existing demo beats | Claude | queued |
| S3 | Deep-mod Pigeon proof (cooperative spec, no injection or reboot) | GPT-6 Astra (Sol was at capacity) | running |

## Oversight
- `scripts/night-watch.py` (read-only) runs every 40 min from the orchestrator. It flags real-app sends/posts/replies, calendar adds/edits/deletes or shrinkage (baseline 1780 events at 01:03), calls, firewall/vault/plugin/auto-reply changes. Alerts accumulate in `/tmp/night-watch/alerts.log`.
- All children have hard limits: real apps are read-only, outward actions only in Pigeon/Beacon, test calendar items only, deleted by exact id.

## Incidents
- 01:16 near miss (no harm): the speed benchmark's "bench check" task t1f31e7a8 tried `alarms.delete` on "10:15 · Before standup" (looks like a real alarm). The approval card timed out, so it was denied and nothing was deleted. Steered the speed agent: test alarms only, labeled, deleted by exact match, and no approval-gated tasks in the benchmark. The 06:17 deletes were its own test alarm (also denied by timeout). Pigeon notification replies at 01:25–01:28 were the notifications agent's own test feed (OK).
