# Integration deploy #1 (10-28, 03:36–03:56)

## What went live
- agentd at 7e01c9a (git archive of HEAD, no uncommitted files): reviewer edc95bb/a333832, voice a16552e…5e00624 + "Done, in N seconds" (11b2cd7), catch-up ed01014/bb73767, calendar keyboard 787ac15, offline router b516bb5, notification tools, deep mods. 12 runtime files changed; package.json unchanged (no npm install). All 857 agentd `.js` files passed `node --check`.
- **One** agentd restart at **03:40:48** (pid 24595 → 28442, via `pkill` + Magisk boot loop). The bench agentd on :8788 was not touched.
- APKs: not reinstalled. Clean HEAD builds of launcher and keyboard are byte-identical (minus signatures) to the installed ones; local.agentos differs only in debug line numbers.

## Smoke tests (all on the live agentd, harmless inputs)
| Beat | Result |
|---|---|
| Home | OK |
| Ask sheet + stopwatch | "Done · 10.0 s", island matches |
| Voice (say → WAV → headless Chrome → :8787) | "On it" → task → spoken "Done, in 5 seconds — 100 percent and charging." |
| What did I miss? | OK with a Pigeon test notification (card row + answer); see issue 1 |
| Keyboard in Pigeon, calendar question | chips "Looks free…", "Thursday 3…", "Would Thur…"; nothing typed or sent |
| Trackers counter | Blockers ticked 1,534 → 1,582 with company names after opening Weather (read-only). Beacon part skipped, see issue 3 |
| Pigeon deep mod | agent preview navy/gold (native, auto-restored, nothing applied), then `app.deepmod.remove` over adb → vanilla, spec empty |
| Scheduled task + test alarm | scheduled 03:52, fired 03:53:25, alarm id 5 23:47 "AgentOS test"; deleted by id 5 via the agent (reviewer auto-approved, no tap). Alarms 1–4 untouched |
| Offline fallback | `~/.agentos/force-offline` → "Done · 3.0 s · On device"; flag removed; next ask back on Codex |

## Open issues
1. **Empty catch-up took 6.1 s** (model timed out just to say "Nothing new"). Fixed in a36a04c (skip the model when there are no cards). **Not deployed** (one restart per deploy); goes in with the next restart together with prompt.js from fb9abad. Repro before the fix: no unanswered notifications → ask "What did I miss?" → log `catchup: model interrupted, plain text … total 6103 ms`.
2. **Catch-up timing varies** while the bench runs: facts 72–107 ms and then 2,459 ms on the same single test message; first token 5,090 ms cold after the restart, 1,485 ms warm. Re-measure with the bench stopped before stage.
3. **Beacon tracker test skipped on purpose.** Restoring afterwards means removing Beacon's radar entry, which needs a root edit of `files/trackers.json` plus a broker restart (reinstall), and unblocking from adb raises a HUMAN card (`Broker.java:87`). That would have interrupted the running bench. The tracker code is unchanged since the 02:55 Beacon test (11b2cd7).
4. **The bench drives the phone while you hold the adb lock** (it pressed HOME and opened Calendar mid-test). Do multi-step UI checks as a single on-phone `adb shell '…; screencap -p /data/local/tmp/x.png'` burst.

## Preflight after the smoke tests (03:56)
GREEN: agentd, internet, Codex, local model warm, keyboard, home, battery, calendar, approval cards, tasks, Prompt Guard, audit chain (5182 lines, 8 marked breaks).
RED, all expected now: Deployed code (catchup.js + prompt.js committed after the deploy), wake word off (never toggled by agents), DND and demo reset (stage-time `--fix`), test alarms (needs a lease; Clock checked directly: ids 1–4 only), Chrome tabs (Chrome not running).
