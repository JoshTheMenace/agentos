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
| 5 | Voice/GPT Live latency hill-climb | GPT-6.1 Sol | done (a16552e…5e00624: warm session ready ~9 ms, task start ~1.7 s, no duplicate/missed handoffs). NOT deployed; goes in #11 |
| 6 | Reviewer light polish | GPT-6.1 Sol | done (a333832; 2.27 → 1.86 s, 27/28; NOT yet deployed to phone) |
| 7 | Bug hunt across demo flows | Claude Opus | done (91b0fa8; 8 fixes, BUGHUNT-NIGHT.md; Wi-Fi pinned, undo `cmd wifi reset-connected-score`) |
| 13 | "What did I miss?" catch-up (DEMO-WOW #6) | Claude Opus | done (ed01014, bb73767; ~1.7 s median; NOT deployed, goes in #11) |
| 17 | Draft 5-slide deck + one-pager PDF (real numbers, A2 look) | Claude Fable | done (7f968d4, 78a3d0b, 5b4a80c; pitch/deck/, pitch/one-pager.pdf) |
| 18 | UI polish pass 3: on-stage feel (motion, legibility, recordings) | Claude Fable | running |
| 8 | UI polish pass 2 + "Done · 6.8 s" stopwatch (DEMO-WOW #4) | Claude Fable | done (2f7f170, 69ed541) |
| 15 | Live tracker counter with company names (DEMO-WOW #7) + spoken "Done, in 7 seconds" | Claude Fable | done (11b2cd7; voice line NOT deployed, goes in #11) |
| 9 | Research: impressive additions that fit existing demo beats | Claude Opus | done (agentd/research/DEMO-WOW.md) |
| 10 | Auto on-device fallback when offline (DEMO-WOW #5) | GPT-6.1 Sol | done (b516bb5, deployed; warm offline ask 2.3 s, cold 24 s, so keep llama warm before stage; label handoff in POLISH-TODO) |
| 14 | Review all overnight commits (security + crash) | GPT-6 Astra | done (edc95bb: reviewer rechecks live flags after grading [high]; voice.heard routing [medium]; REVIEW-NIGHT.md) |
| 16 | Stage preflight script (check + safe --fix) | GPT-6 Astra | done (3467473; `scripts/preflight.sh [--fix warm,ime,home,…]`) |
| 12 | Keyboard replies that know your calendar (DEMO-WOW #8) | GPT-6 Astra | done (787ac15; empty-draft calendar replies 4 ms, typed +177 ms; backend in #11) |
| 11 | Morning integration deploy (scheduled to start 6:43 AM): push all agentd changes incl. reviewer, one restart, smoke test every demo beat, update STATUS.md | Claude | queued (after others finish) |
| S3 | Deep-mod Pigeon proof (cooperative spec, no injection or reboot) | GPT-6 Astra | done (3430c2e; navy/gold + promoted rows removed, vanilla pixel-identical) |

## Oversight
- `scripts/night-watch.py` (read-only) runs every 40 min from the orchestrator. It flags real-app sends/posts/replies, calendar adds/edits/deletes or shrinkage (baseline 1780 events at 01:03), calls, firewall/vault/plugin/auto-reply changes. Alerts accumulate in `/tmp/night-watch/alerts.log`.
- All children have hard limits: real apps are read-only, outward actions only in Pigeon/Beacon, test calendar items only, deleted by exact id.

## Incidents
- 02:55 and ~03:15 (trackers track, Fable): local.agentos reinstalled twice (per-domain blocked counters in `trackers.list`, island "Blocked · …" moment) and the launcher reinstalled; the broker re-bound each time, audit chain checked OK after. agentd was NOT restarted (voice.js duration line goes in with the morning deploy). Beacon was pointed at google-analytics.com for the counter test, then put back to httpbin.org and stopped.
- 02:50 the speed bench's 6:17 AM test alarm (Clock id 3, unlabeled, created by "Set an alarm for 6:17 AM, then delete it") was still on, because its delete timed out at the approval card. The orchestrator turned it OFF (not deleted; reversible) via alarms.delete off:true. The 10:15 "Before standup" alarm (id 4) was left alone.
- 02:15 phone offline: Android kept dropping home Wi-Fi "BathroomCamera4" for a low connection score (blocklisted 01:29, 02:05), so Codex DNS failed and the voice tests stalled. Fix: `settings put global captive_portal_mode 0` (undo: `settings delete global captive_portal_mode`) plus a Wi-Fi off/on, after which it reconnected and chatgpt.com was reachable. The watchdog now also checks connectivity.
- 01:16 near miss (no harm): the speed benchmark's "bench check" task t1f31e7a8 tried `alarms.delete` on "10:15 · Before standup" (looks like a real alarm). The approval card timed out, so it was denied and nothing was deleted. Steered the speed agent: test alarms only, labeled, deleted by exact match, and no approval-gated tasks in the benchmark. The 06:17 deletes were its own test alarm (also denied by timeout). Pigeon notification replies at 01:25–01:28 were the notifications agent's own test feed (OK).
