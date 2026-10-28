# Overnight plan (10-28, 1 AM → morning)

Limits: at most 3 Claude children at once. GPT-6.1 Sol children run alongside. If a provider hits its usage limit, pause that track until the limit resets. UI work goes to Fable.
Rules for every child: run state-changing adb steps through `scripts/withphone`. Never tap approval cards, never message or call real contacts, and keep existing approval rules intact. Run `node --check` on every changed agentd file before deploying, and leave the phone demo-ready after each step. Commit and push only your own files. Code freeze is at noon; judging is at 5 PM.
Boundaries: code-level injection (Frida/LSPosed) only into apps we own (ARCHITECTURE rule 4). Third-party apps get only supported surfaces: notifications, notification reply actions, official APIs and the accessibility screen. No private-API reverse engineering.

## Running / queue (top = next)
| # | Track | Who | State |
|---|---|---|---|
| 1 | Codex action speed hill-climb (cross-app tasks, screen format, round trips, tools-first) | Claude Opus | running |
| 2 | Island below the camera hole, then UI polish pass 1 | Claude Fable | done (8e56541) |
| 3 | Keyboard autocomplete latency hill-climb | Claude Opus | done (fd5fb1b; typing pause median 7.3 s → 3.5 s) |
| S1 | Live notifications + reply actions + official APIs | GPT-6.1 Sol | done (6928905, 660377b; reads ~20 ms, reply turn 32 s → handed to speed track) |
| S2 | Deep-mod engine research + design on our own apps | GPT-6.1 Sol | done (DEEP-MODS.md: cooperative signed spec, no injection or reboot) |
| 4 | Wake word: Porcupine ready (needs key; Picovoice free tier ended, 7-day trial) + offline Vosk "Hey Agent" fallback | Claude | done (4df084d; switch off) |
| 5 | Voice/GPT Live latency hill-climb | GPT-6.1 Sol | running |
| 6 | Reviewer light polish | GPT-6.1 Sol | done (a333832; 2.27 → 1.86 s, 27/28; NOT yet deployed to phone) |
| 7 | Bug hunt across demo flows (incl. Duolingo on Home, tap spots +77 px) | Claude Opus | running |
| 8 | UI polish pass 2 + "Done · 6.8 s" stopwatch (DEMO-WOW #4) | Claude Fable | running |
| 9 | Research: impressive additions that fit existing demo beats | Claude Opus | done (agentd/research/DEMO-WOW.md) |
| 10 | Auto on-device fallback when offline for ask/voice (DEMO-WOW #5) | GPT-6.1 Sol | running |
| 11 | Morning integration deploy (~8 AM): push all agentd changes incl. reviewer, one restart, smoke test every demo beat, update STATUS.md | Claude | queued (after others finish) |
| S3 | Deep-mod Pigeon proof (cooperative spec, no injection or reboot) | GPT-6 Astra | done (3430c2e; navy/gold + promoted rows removed, vanilla pixel-identical) |

## Oversight
- `scripts/night-watch.py` (read-only) runs every 40 min from the orchestrator. It flags real-app sends/posts/replies, calendar adds/edits/deletes or shrinkage (baseline 1780 events at 01:03), calls, firewall/vault/plugin/auto-reply changes. Alerts accumulate in `/tmp/night-watch/alerts.log`.
- All children have hard limits: real apps are read-only, outward actions only in Pigeon/Beacon, test calendar items only, deleted by exact id.

## Incidents
- 02:15 phone offline: Android kept dropping home Wi-Fi "BathroomCamera4" for a low connection score (blocklisted 01:29, 02:05), so Codex DNS failed and the voice tests stalled. Fix: `settings put global captive_portal_mode 0` (undo: `settings delete global captive_portal_mode`) plus a Wi-Fi off/on, after which it reconnected and chatgpt.com was reachable. The watchdog now also checks connectivity.
- 01:16 near miss (no harm): the speed benchmark's "bench check" task t1f31e7a8 tried `alarms.delete` on "10:15 · Before standup" (looks like a real alarm). The approval card timed out, so it was denied and nothing was deleted. Steered the speed agent: test alarms only, labeled, deleted by exact match, and no approval-gated tasks in the benchmark. The 06:17 deletes were its own test alarm (also denied by timeout). Pigeon notification replies at 01:25–01:28 were the notifications agent's own test feed (OK).
