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

# Round 2 (partial, 2026-10-27; stopped when the phone was needed elsewhere)

Same phone and tasks. The API was much slower than in round 1: "say ok" took 3–13 s (round 1: 2.4–3 s), from both the phone and the Mac, with GitHub issues reporting slow GPT-6.1 that day, and the phone's hotspot had a 140–540 ms ping. So each comparison is **interleaved**: `bench/climb.js` rotates the variants round by round. Totals below are per-task medians.

## 1. Codex "Fast" mode = `serviceTier: "priority"` (found, wired, wins)
- Codex 0.163 on the phone: `thread/start` and `turn/start` take `serviceTier` (`turn/start` also takes `serviceTierForTurn`). `model/list` shows `{id:"priority", name:"Fast"}`: "1.5x speed" on gpt-6.1-terra, "2x speed, increased usage" on gpt-6.1-sol and gpt-6-astra. The Mac's Codex already defaults to priority.
- agentd: `CODEX_SERVICE_TIER=priority` (config `codexTier`) is sent on every `thread/start` (tasks, reviewer, suggestions). **Default is still off** (not deployed to production; the founder should decide, because it uses more quota).
- Model only, 12 interleaved pairs at the real prompt size (~11k tokens): median per call **4.92 → 4.39 s** (−0.5 s), faster in 10 of 12 pairs.
- Bench, 3 interleaved rounds (`r2ab2-base` vs `r2ab2-fast`): alarm 10.1 → 7.6, timer 8.2 → 7.3, chrome 10.7 → 9.4, calc 15.2 → 14.7, reply 13.7 → 12.6 s; sum of medians **57.8 → 54.0 s (−7%)**. The one failed Fast run was Chrome's results check (see below), not the model.
- Catalog re-check (Fast tier on, 6 calls each): terra 5.3 s, gpt-6-astra (new) 7.5, gpt-5.6-terra 5.9, gpt-5.6-luna 4.6, gpt-6-luna 5.0. Nothing is faster than terra apart from luna-class models, and those failed phone control in round 1. There is no mini/nano/spark model in the catalog.

## 2. Computer-use research (web)
OpenAI's computer-use speed comes from **batched actions** (one tool call with an `actions[]` list; we already have `act`), the Fast tier and prompt caching (our ~11k prefix is cached after the first call). Running a batch's first actions while the model is still writing the rest is only a community feature request (Sep 2026), not shipped. App-server dynamic tools only arrive complete, so there's nothing more to adopt. No accessibility-tree computer-use tool is documented.

## 3. Dropping the last "tell the user" call (`CODEX_FINISH=1`, default off)
Finishing tools (set_alarm, set_timer, web_search, notifications_reply, act) get an optional `done` reply. If the call **succeeds** (the broker already approved and ran it), agentd shows `done` and calls `turn/interrupt`, so the turn ends with no extra model call. Nothing skips the broker: `done` is only read after `runTool` returns `success`.
- The field alone: the model used it in 3 of 8 alarm/timer runs, so no gain (57.7 s).
- With the prompt hint (doneHint, below): alarm and Chrome take **1 call**, Calculator 2, reply 3.

## 4. Phone side
- **Event-driven settle (return when the screen stops changing): tried, reverted.** open_app 1.40 → 1.30 s and taps the same (~0.53 s median; the worst tap 1.5 → 0.66 s). But web pages pause mid-load: search screens came back half-loaded (10–41 nodes instead of 47–62), and the model said "results weren't shown". The 2 s settle cap from round 1 didn't reproduce (`bench/settle.js` tap median ~0.5 s).
- **Cold-start fix (kept, in the tree and installed on the phone):** a cold `open_app` (after `pm clear`) returned after 1.1 s with *no app on screen* (`pkg ""`, 12 system nodes), so the model always spent a call on `screen_read`. `Broker.awaitApp` now waits (max 3 s) for the target app's window after `open_app`/`intent`, then settles as before. Cold Calculator: 2.0 s with the full keypad (40 nodes). Warm start unchanged (1.4 s). Calculator went `open_app → screen_read → act → answer` (4 calls) → `open_app → act → answer` (2–3 calls), ~12.6 s.
- Where a tap's ~0.5–0.7 s goes: settle ≥ 300 ms of quiet, plus the first read after a window change (~0.5 s: root FLAG_SECURE dumpsys + Prompt Guard; cached reads take 40 ms). Calculator's 7-tap `act` costs ~5 s on the phone. I left this alone: dropping the FLAG_SECURE re-check on click events would weaken the secure-screen check.

## 5. Hill-climbing (`bench/climb.js --climb`)
Variants are appended to the prompt through `AGENTD_PROMPT_PATCH` (base64 JSON, no code change), on top of Fast + finish. Interleaved 2 rounds × alarm/chrome/calc/reply. KEEP needs the same passes and a > 5 % lower sum of per-task medians.

| Candidate | Sum of medians (best → candidate) | Verdict |
|---|---|---|
| doneHint: "put your final reply in the call's `done`" | 96.5¹ → 43.6 s | KEEP (alarm/chrome 1 call, calc 2) |
| blindAct: open_app + steps named by `label` in ONE act | 48.9 → 99.2¹ s | drop (reply 2 calls but slower; calc label misses) |
| oneAct: "put every visible step into ONE act" | 42.2 → 40.0 s | KEEP (marginal, within noise) |
| terse: "one short sentence, no lists, no offers" | 41.4 → 37.1 s | KEEP |
| fewShot: two worked examples | 1 of 2 rounds: 37.7 → 49.2 s | aborted (looked worse) |

¹ Chrome check failures count as 60 s. **Winner so far: Fast + finish + doneHint + oneAct + terse** (`r2d-terse-climb-terse`): alarm 6.0, chrome 5.6, calc 12.6, reply 12.8 s (round-1 final on the old code, same day: alarm 10.1, chrome 10.7, calc 15.2, reply 13.7). None of it is a production default yet. To adopt it: `CODEX_SERVICE_TIER=priority CODEX_FINISH=1`, and move the three lines from `CANDIDATES` in climb.js into the prompt (doneHint only in codex.js, because Claude's tools have no `done`).

Also added: `act` steps can name a target by `label` (exact text/desc on the live screen at that step) and `type` without an id types into the focused/only field. Each step still goes through `runTool`, so the broker checks every one; a missing label stops the batch. The bench's Chrome check now polls up to 6 s and saves the screen text on failure, because a 1-call turn ends before Google draws its result tabs.

## Left to do
1. Finish fewShot, then one clean interleaved run: round-1 defaults vs the winner on all 6 tasks (incl. timer, multi) with ≥ 3 rounds.
2. Founder: turn Fast mode on by default? (more quota)
3. If it holds, make Fast + finish + the three prompt lines the defaults and deploy.
4. Reply still takes 3 calls (open_app, tap the chat, act type+Send). A label-based step could make it 2 once the Calculator label misses are fixed (Google Calculator's × is desc "multiply").

## Running bench.js / climb.js on an emulator
What is phone-specific today:
- **Serial and root.** `SERIAL` (default ZY22J3TK8S). bench.js, settle.js and up.sh use `su -c` for: the tokens (`/data/data/local.agentos/files/token`, `~/.agentos-bench/agentd.token` under Termux), `pm clear`, and `settings.json` (`chown`/`chcon`). On a Google APIs (non-Play) image, `adb root` + plain `adb shell` can replace `su -c`.
- **The tool server :7777 needs root as an app.** `Screen.refreshFocus` runs `dumpsys` through `su` for the FLAG_SECURE check and fails closed: without app-usable root every screen reads as secure and gets redacted. Use a Magisk-rooted AVD (e.g. rootAVD); then `android/install.sh` (Magisk grant + a11y service) works as on the phone.
- **agentd location.** On the phone it runs under Termux (uid 10401, `deploy/tx.sh`, `bench/up.sh` copies to `~/agentos/bench-agentd`) with root Codex (`deploy/codex-root.sh`, lab install + connect proxy). On an emulator, run agentd on the Mac instead: `adb forward tcp:17777 tcp:7777`, then `PHONE_URL=http://127.0.0.1:17777/rpc PHONE_TOKEN=… AGENTD_PORT=8788 AGENTOS_HOME=~/.agentos-bench node server.js --bench` with the Mac's `codex`. Then bench.js reads `TOKEN` from the Mac file, not the phone, and climb.js's `up()` restarts that local process with the variant's env instead of calling up.sh.
- **Lease.** Callers other than Termux (adb/Mac) never get the "own request = grant" path. The bench already turns the lease card off (`require_user_lease:false`), so it still works. Unlock: the broker refuses adb callers on a locked screen; settle.js wakes and swipes.
- **Apps and package names.** Tasks assume `com.google.android.deskclock`, `com.google.android.calculator`, Chrome, and Pigeon (`demo-apps/`). AOSP images ship `com.android.deskclock` and `com.android.calculator2`: install the Google apps or edit `TASKS`.
- **Numbers.** The emulator uses the Mac's network (no hotspot) and a faster CPU, so its times aren't comparable with the phone's. Compare variants on the same machine only.

# Round 3 (2026-10-28 night, Moto G, real phone)

## Bench (new task set, `node bench/bench.js --tasks r3`)
Founder limits for tonight: read-only in real apps; changes only to the torch (turned back off) and our own Pigeon/Beacon; **no task may delete anything, send anything or raise an approval card**. So the round-1 tasks that wiped Clock (`pm clear`) or sent replies are gone, and Calculator is reset with the CLEAR key instead of `pm clear`.

| Task | What it exercises |
|---|---|
| pigeon: "Open Pigeon and tell me Alex's last message." | UI read in our app (open_app → answer) |
| beacon: "Open Beacon and tell me the uid it shows." | UI read in our app |
| torch: "Turn on the flashlight." | direct API (`phone_settings`) |
| calendar: "When is the hackathon judging?" | memory/context, or `find_events` (read) |
| notifs: "Summarize my notifications in one sentence." | notifications (answer is stored only as its length) |
| calc: "Use the Calculator app to work out 234 times 17." | UI driving with a multi-step `act` |

Harness changes: the lock (same as `scripts/withphone`, pid inside) is held per task and released for 10 s between tasks so other agents interleave; `lockWait` per row marks runs that queued behind someone else. `bench/snap.sh` deploys the bench agentd from a clean HEAD snapshot (the worktree is shared, and copying it once crashed the bench on another agent's uncommitted import). `bench/ping.js` measures the model alone ("Say ok", no phone actions). `turn.done` now carries `prep` (agentd time before the model sees the request).

## What the night's numbers say
- **Network-affected window, 01:29–02:15:** the phone kept dropping Wi-Fi (the hotspot, then home Wi-Fi). Model calls then took 5–30 s and app-server kept reconnecting. Nothing below is based on runs from that window (r3-smoke, r3-base and the first pings are kept only as raw rows). `bench/usb-net.js` gives the bench Codex the Mac's internet over USB (`adb reverse`); `deploy/codex-bench.sh` puts the bench Codex on its own proxy port 18081, so this never touches the live agentd's 18080.
- **Network path, once Wi-Fi was back (03:00, 3 interleaved rounds × 4 pings, 17.1k-token prompt):** phone Wi-Fi + android-connect-proxy, median per round 2.65 / 3.11 / 2.46 s; Mac over USB 2.43 / 3.18 / 2.38 s. No real difference: the proxy and Wi-Fi aren't a lever while they're up.
- **agentd overhead is small:** `prep` (context block: notifications, memory lines, guard) 30–400 ms, ~1 s on the first request after a restart. thread/start per request ~0.2 s, except the first after an app-server start (~4 s, the model connection's warm-up; the keyboard track's `warmSuggest()` warms that connection at start).
- **Where a task's time goes now (r3ab-base, terra):** the first model call (to the first tool call) is 4.5–8.7 s (median 5.4 s) on a ~17k-token prompt; later calls are short. Phone side: cold `open_app` of Pigeon/Beacon 1.6–3.4 s, `phone_settings` ~0.9 s, each Calculator key tap ~0.73 s (7–9 taps = 5–7 s).
- Prompt grew from ~13.4k (round 2) to ~17.1k tokens with tonight's new tools (81 tools ≈ 8.9k tokens of schemas).

## Models and plugins mode (`climb.js --ab base,luna56,luna6,reserve,plugins --rounds 2 --prefix r3ab`, 03:10–03:40, all 12/12 each)
The catalog on the phone (models_cache.json, 10-28): gpt-6.1-sol, gpt-6-astra, gpt-6.1-terra, gpt-6-sol, gpt-6-luna, gpt-5.6-sol/terra/luna, and hidden `gpt-reserve` ("Fast and affordable agentic coding model"). Every listed one offers the priority (Fast) tier; `low` is still the lowest effort.

| Variant | Sum of per-task medians | Success | First call (median, p25–p75) | 1st-call tokens |
|---|---|---|---|---|
| base (gpt-6.1-terra, all tools) | 44.6 s | 12/12 | 5.4 s (4.5–8.7) | 17.1k |
| gpt-5.6-luna | 43.0 s | 12/12 | 5.7 s (4.1–8.7) | 15.0k |
| gpt-6-luna | 47.7 s | 12/12 | 6.2 s (4.9–7.7) | 17.1k |
| gpt-reserve | 41.0 s (−8 %) | 12/12 | 6.0 s (4.0–7.4) | 15.0k |
| AGENTD_TOOLS=plugins | 46.1 s | 12/12 | 5.7 s (4.0–6.8) | 10.0–12.4k |

All inside the noise (the same call ranges 1–18 s between runs); none beats terra by the 5 % KEEP bar on per-call time, and round 1 saw luna fail harder UI tasks. **Kept terra and `all`.** The one real difference was behavioural: gpt-5.6-luna and gpt-reserve typed `234*17` into Calculator's display in one step instead of tapping 9 keys, which is what the next experiment turns into a prompt line.

## Prompt climb (`climb.js --climb --only typeKeys,visible,ctx --rounds 2 --prefix r3c`, 03:45–04:30, all 12/12)
The same baseline scored 48.3, 51.9 and 34.6 s (sum of per-task medians) in its three comparisons, so the 5 % rule can't separate prompt lines tonight. Decisions use what doesn't depend on API noise: model calls per task and phone-side tool time.

| Candidate | Sum (cand. vs base) | What changed, deterministically | Verdict |
|---|---|---|---|
| typeKeys: "type numbers/text into the field in one step instead of tapping keypad keys" | 47.9 vs 48.3 s | Calculator's `act` = type `234*17` + "=" ≈ **1.4 s** on the phone vs 9 key taps ≈ **5.5–6 s**; same answer, same 2 calls (4/4 runs) | **KEEP** (fb9abad) |
| visible: "if the screen you have answers it, answer at once; don't open the item" | 44.5 vs 51.9 s | Pigeon 2 calls in 6/6 runs (baseline: 3 calls in ~1 of 5 runs, +2–4 s, it opened Alex's chat to re-check); notification summary 1 call in 6/6 | **KEEP** (d356423) |
| ctx: "answer notification questions from <phone_context>" | 42.2 vs 34.6 s | nothing beyond visible (notifs already 1 call) | drop |

## One-shot actions finish in the same call (`providers/codex.js` FINISH set, 04:35–04:50, `--label r3f-base|r3f-fin`, 2 interleaved rounds)
`phone_settings, media, play_music, navigate, open_url, add_event, schedule_task` join set_alarm/set_timer/web_search/notifications_reply/act as tools that take an optional `done` reply. As before, agentd shows `done` and ends the turn only after `runTool` returns success, i.e. after the broker allowed and ran the call; nothing skips the broker or the approval card.
- torch: **2 → 1 model call in 2/2 runs** (the "Flashlight is on." call is gone, ~1–3 s). pigeon and calc: same calls, 4/4 OK each. **KEEP**.
