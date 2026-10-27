# Integration test on production agentd (:8787), 2026-10-27

Phone ZY22J3TK8S, agentd from this checkout (8310f8f + today's uncommitted work), the APK rebuilt with the Broker fixes below.
Timings run from `node cli.js` sending the request to `turn.done`, measured on the phone. Where a person would tap a card, an operator tapped it over adb (`input tap` on the card's blue button, ~1–4.5 s after it showed, to give the reviewer its turn first).

| # | Test | Result | Time | Notes |
|---|---|---|---|---|
| 0 | Known issue: "Open Clock, next alarm?" (before) | FAIL | 67 s | Lease card waited 60 s for a tap. The bench hid it with `require_user_lease:false` |
| 1a | Codex: "Open the Clock app and tell me what the next alarm is." | PASS | 10.5 s (10.6 s rerun) | No card (user's request = grant) |
| 1b | Codex: "Set an alarm for 7 AM, then delete it." | PASS | 14.7 s (18.6 s rerun) | Delete auto-approved by the reviewer (was a 60 s tap timeout before the reviewer fix) |
| 1c | Claude: same as 1a | PASS | 15.0 s | Was "provider unavailable": two phone fixes, see below |
| 1d | Claude: same as 1b | PASS | 22.9 s | Delete auto-approved |
| 2 | Trackers: "Open Spotify. Does this app have trackers?" → "Block them." → "Unblock Spotify's trackers." | PASS | 18.7 s + 9.3 s; unblock 16.1 s | 6 trackers found. The block review timed out (cold reviewer after a restart), so the operator tapped. Unblock: the reviewer asked for a tap (fine). Nothing left blocked |
| 3 | Notification "reply back": test chat from Sam, then "Reply back that I'm 10 minutes late." | PASS | 9.0 s | Card "Reply to Sam?" with the drafted text; a person must tap (all three Rule-of-Two lamps), the operator tapped. Thread ends with "I'm running 10 minutes late." |
| 4 | Home feed: suggestions + live task card on the real launcher | PASS | | `launcher/shots/live-1-task-card.png` (Starting + stop), `live-2-task-card.png`, `live-3-task-done.png` (answer). Nit: the status line under the clock follows the broker lease, not the worker card ("Ready" while the card says Starting) |
| 5 | Back-to-back tasks (step count) | PASS | | Next task: `carried_over:true, steps:0` (Broker fix) |
| 6 | Sensitive app still asks: "Open the Play Store." | PASS | 13.9 s | Lease card shown (`by_request:false`), operator tapped Allow |

## Fixed on the way
- **Broker.start:** the user's request is the lease grant (NEEDS-ANDROID.md #7), and the step count resets for a new task (#3).
- **reviewer.js:** deleting an alarm/timer/reminder the user asked to delete is low risk (it said "hard to undo" → 60 s timeout). The reviewer now sees the last 4 asks of the conversation, so "Block them." reads with the question before it. `reviewer-test.js` 19/19 (one new case).
- **Claude on the phone:** the boot loop had no `LD_PRELOAD` (termux-boot.sh), and the Claude install's SELinux label lacked the app's categories (chcon on the phone). `claude-cli.js` now logs why the CLI is unavailable.
- **cli.js:** exits on `busy`/`provider_unavailable` errors (it hung), and `--then` sends follow-ups in the same conversation.
- **Phone setup:** alarm volume 1 → 6. Clock's "Alarm volume is low…" banner was scored 0.98 by Prompt Guard and flagged every Clock task.

## Open
- ~~Prompt Guard false positives on real app text~~ fixed 10-27 (android/guard-tests/: 4 false flags → 0 on 31 cases, canary still flags).
- ~~Old alarm "Salt Lake City sunset" 6:29 PM~~ deleted 10-27.
