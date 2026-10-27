# Task speed: before / after (Moto G 5G, 2026-10-27)

Measured on the real phone with real apps, from the request to `turn.done`. Bench agentd on :8788 (`bench/up.sh`), root Codex 0.163, effort `low`. Run with `node bench/bench.js --runs 2`; raw rows are in `results/*.jsonl`; this table comes from `node bench/bench.js --summary <labels>`.
The lease card (a human tap) is switched off per task, so it isn't counted. Approval waits are counted: the reviewer's auto-approve on "reply" takes 2.5 to 4.6 s.

| Task | Before | After | Speed-up | Model calls before → after |
|---|---|---|---|---|
| "Set an alarm for 7 AM" (Clock) | 33.0 s | **7.5 s** | 4.4× | 8 → 2 |
| "Start a 5 minute timer" (Clock) | 26.5 s | **6.8 s** | 3.9× | 8 → 2 |
| "Search Chrome for the weather in Salt Lake City" | 32.8 s | **9.9 s** | 3.3× | 7 → 2 |
| "Use the Calculator app to work out 234 × 17" | 27.7 s (failed) | **15.1 s** | 1.8× | 8 → 3 |
| "Reply to Mom in Pigeon that I'm running 10 minutes late" | 21.1 s¹ | **13.8 s** | 1.5× | 7 → 4 |
| "Look up today's sunset in Chrome, then set an alarm for it" | – | **19.9 s** | | – → 3 |
| **Median** | **~30 s** | **~11 s** | **~2.7×** | 8 → 2–3 |

Time to first action: 5.6–9.5 s → **3.1–3.6 s**. After the changes: 15 of 16 runs passed. The one failure was a reply whose approval wasn't auto-approved and timed out (60 s) during a 32 s model stall.
¹ Baseline "reply" timed out on approval (a Prompt Guard false positive, fixed below), so this is the same old code on terra, which passed.

## What each change bought (one at a time)
| Step | Setup | Median | Notes |
|---|---|---|---|
| 0 | old code, default model (gpt-6.1-sol) | ~30 s | list_apps → task_start → open_app → screen_read before the first tap. ~1.9k tokens per screen |
| 1 | + model **gpt-6.1-terra** | 21.1 s | Same calls, faster ones. gpt-6-luna: 0/5 (wrong taps, 30 s stalls). sol: 1/5 in a noisy run, 4/5 on the new code (18.1 s, more fumbling) |
| 2 | + app list in the instructions, `open_app` starts the lease, "don't re-read after an action", compact screen lines, `act` batches | 16.6 s, 10/10 | The first action moves from ~7 s to ~3 s. Tokens per screen ~1.9k → ~0.35k. Calculator: 7 digit taps in one `act` |
| 3 | + direct intents `set_alarm` / `set_timer` / `web_search` | 12.1 s (11/12) | Alarm, timer and search take 2 calls: act, then say it's done |
| 4 | + Prompt Guard false-positive fix | reply 13.1–16.0 s, 0 flags | Calculator no longer flags the task, so sends get auto-approved again |

## Where the time goes now
- **A model call costs ~2.5 s, however small it is.** "Say ok." on terra: 2.4–3.0 s to first token, both from the phone and from the Mac. The network isn't the problem, and starting a thread costs only 70 ms (pre-warming isn't worth it). So the only big lever is fewer calls.
- **Phone side, per action:** `open_app` ~2 s, a tap ~0.7–1 s (`settle()` often runs to its 2 s cap in Clock and Chrome), a send's auto-review ~3 s. See NEEDS-ANDROID.md #6.
- Effort: `low` is the lowest effort the phone's catalog offers (there is no `minimal`).

## Not done / tried
- Diffs instead of full screens: compact lines already brought a screen down to ~0.35k tokens, and calls cost latency more than tokens.
- Streaming: turns already stream. The final sentence is ~1 s of the last call.
- Other projects (droidrun, mobile-use, AppAgent, Mobile-Agent): from what I know, not from new research. The common speed tricks are an accessibility tree with indexed elements instead of screenshots (we do this), direct intents/shortcuts, and batched actions. Multi-agent planners (Mobile-Agent-v3) add calls, not speed.

## Security
Nothing skips the broker. `act` calls `runTool` once per step: lease, approval card, Rule of Two and the audit log apply to each step. A step whose target isn't clearly on the new screen stops the batch. `intent` is an allow-list in `Actions.java` (alarm, timer, Google search only; no URLs, SMS, call or share intents). It goes through the same lease and tier checks as a tap; a search is T2 and goes through the P3 secret-leak check.

## Bugs found on the way
- **Fixed, codex.js:** a superseded turn left its stall watchdog running, so app-server got killed every 5 s, forever. The live agentd log shows 369 of these restarts.
- **Fixed, PromptGuard.java:** text-less button labels ("History mode off", "delete", "all clear") scored 0.95–0.99. Calculator's first-run screen joined into one text scored 0.57. Now short button labels aren't scored, and the joined screen counts only at ≥ 0.9. The planted Pigeon canary still flags at 0.967.
- **Open, Broker:** the step count carries over between tasks (max_steps 40 within 10 min). See NEEDS-ANDROID.md #3.
