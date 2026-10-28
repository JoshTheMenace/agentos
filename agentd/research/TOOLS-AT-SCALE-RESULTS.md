# Tool plugins: what was built and how it measured (2026-10-27)

Follows the design in `TOOLS-AT-SCALE.md`, with the founder decision that a plugin switch gates the **API tools only**, never the app itself.

**Recommendation: keep `AGENTD_TOOLS=all` as the default through the code freeze.** The switches and their enforcement are already live in both modes (deployed to the phone). `plugins` mode works and is as accurate as `all`. It cuts first-call tokens by about 30 %, but it isn't measurably faster on today's 62 tools, it misses the prompt cache more often, and the pre-selector's recall on phrasings it wasn't written for is only 60 %. It passes none of the report's default gates except success. Switch it on after the freeze, once the tool count grows (at 152 tools, `all` reads 22k tokens per call against a flat ~9.7k) and the two fixes under "Next" are done.

## What was built

| Piece | Where | What it does |
|---|---|---|
| Plugin manifest | `agentd/plugins.js` | Core = 16 tools, always on: `task_start screen_read tap type scroll press open_app list_apps act wait memory_ask notifications_list notifications_reply set_alarm set_timer web_search`. 12 plugins: Calendar, Messages & calls, Alarms, Device settings, Media, Maps & links, Trackers & firewall, Passwords, Memory tools, Reply rules, Scheduled tasks, App mods. Each has an id, a name, a one-line summary, its tools, route words for the pre-selector, its prompt lines (moved out of `prompt.js`) and a default `on`. |
| Switches (source of truth) | `android/.../Plugins.java`, `Broker.java`, `Server.java` | `files/plugins.json {"off":[ids]}` in the broker. `plugins.list` is readable by anyone. `plugins.set {id, on}` works only from the launcher's uid (the owner line) or adb, and it's audited and announced as `plugins.changed`. The broker keeps **its own** method → plugin map (`intent` is matched by kind: `navigate`/`url` → Maps & links, `play` → Media, `email` → Messages; alarm/timer/search stay core). While a plugin is off, its methods are refused (`decision:"refused"`, audited) for every caller except the owner's own screens and adb. There is no app or lease refusal (founder decision). |
| agentd gate | `agentd/tools.js` `runTool` | Before anything else: a tool whose plugin is off is refused. That's the only gate for the agentd-local plugins (memory, rules, schedule). It checks *enabled*, not *visible*: `plugin_call` and `load_plugin` go through it too. agentd reads `plugins.list` with a 5 s cache and drops the cache on `plugins.changed`. A switched-off plugin is left out of every provider's tool list, and the prompt names it ("you may still do it on screen, or say it can be turned on"). |
| Pre-selector | `plugins.js` `pick(goal)` | Plain regexes over the user's own request only (never screen or notification text). It picks at most 2 plugins (3 for long requests), earliest mention first. It takes about 2–14 µs. |
| Feature flag | `config.js` `tools` | `AGENTD_TOOLS=all` (default): every enabled tool upfront, the same as before, minus switched-off plugins. `AGENTD_TOOLS=plugins`: core + picked plugins upfront, the other enabled plugins deferred. |
| Codex | `providers/codex.js` | Native `deferLoading` **does not work** with gpt-6.1-terra (below), so deferred plugins use the fallback: `load_plugin {id}` returns the plugin's schemas, then `plugin_call {tool, args}` runs one through `runTool`. Tools are fixed at `thread/start`; a changed switch starts a new thread. |
| Claude Code CLI | `providers/claude-cli.js` | ≥ 2.1.285 (the Mac, 2.1.310): every enabled tool is listed over MCP, core + picked tools carry `_meta["anthropic/alwaysLoad"]`, and the CLI runs with `--tools ToolSearch` (native deferral). Older (the phone pins 2.1.112): core + picked + `load_plugin`/`plugin_call`. `tools/list` is now per session. |
| Claude API | `providers/claude.js` | **Prompt caching on** (top-level `cache_control: {type:"ephemeral"}`). Core + picked tools upfront, the rest with `defer_loading: true`, plus `tool_search_tool_bm25_20251119`, in a fixed order. |
| Local 0.8B | `providers/local.js` | No tool search. A switched-off plugin's router forms and hints are dropped from the grammar, so the model can't produce them. The router prompt stays the same, so its cache holds. |
| Launcher | `web/app.js`, `web/index.html` | Command center › **Plugins**: name, summary and toggle per plugin, plus an "Always on" note for the core (`launcher/shots/plugins.png`). The list comes from agentd (`{type:"plugins.list"}`, read-only); a toggle goes over the owner line to the broker. Mods and the Network toggles now use the owner line first, so the owner's own screens keep working while their plugin is off. A refused call shows as "Blocked" in Activity. |
| Bench | `bench/plugins-eval.js`, `mock-phone.js`, `bench.js`, `climb.js`, `up.sh` | Router recall (labeled + held-out), a Mac-side A/B against an extended mock broker (same plugin map, a log of what reached it), and enforcement tests. `bench.js` now records `cached`, `plugins` and `load_plugin` counts. `climb.js` has `all` and `plugins` variants. |

## Codex `deferLoading` with gpt-6.1-terra: doesn't work
Probe on the Mac (Codex 0.164, agentd's exact app-server flags): core only = 7,793 input tokens; core + every other tool as namespaced `deferLoading` functions = **7,793** as well, exactly. So the deferred tools are dropped and no `tool_search` is offered. Asked to add a calendar event, the model called `list_apps` and then **claimed it had added the event**. `models_cache.json` lists terra as `supports_search_tool: true` but `tool_mode: "code_mode_only"`; in that mode, Codex exposes deferred tools only through the code-mode host (`tools.tool_search(...)` in JS), which agentd doesn't enable. So the Codex adapter uses the `load_plugin` fallback. Enabling deferred tools would also have been unsafe: a silent false "done".

## Numbers

### 1. Pre-selector (offline, no model)
| Set | Recall (needed plugin picked) | Avg plugins picked | Time |
|---|---|---|---|
| Labeled set, 109 requests (bench + local tasks, ~8 per plugin, tricky ones) | 109/109 = **100 %** | 1.01 | 14 µs |
| **Held out**, 30 requests written after the routes and never tuned on | 18/30 = **60 %** | 0.67 | 2 µs |

The labeled set was written alongside the route words, so 100 % is optimistic; the held-out 60 % is the honest number. Misses are paraphrases with no keyword: "ping Sam", "ring my wife", "book a haircut", "nuke my 5:30 alarm", "is this app sending my data", "get me to the gas station". A miss doesn't fail the task: it costs one `load_plugin` call (or a tool search on Claude). Gate in the report: ≥ 97 %. **Not met.**

### 2. Mac agentd + mock broker, real gpt-6.1-terra (Fast tier), 12 tasks × 3 interleaved rounds
`node bench/plugins-eval.js --ab all,plugins --rounds 3`. Rows are in `bench/results/pl-*.jsonl`. "Calls" undercounts finishing-tool turns (alarm/timer/search end with `done`, and the interrupted turn reports no usage), the same in both modes.

| | all | plugins |
|---|---|---|
| Success | 36/36 | 36/36 |
| Sum of per-task medians | 51.2 s | 47.9 s |
| Sum on the 4 core tasks (alarm, timer, search, reply) | 13.1 s | 13.6 s |
| First-call input tokens (median) | 14,336 | **10,146 (−29 %)** |
| First-call cache hit (cached ÷ input) | 83 % (4/30 cold) | **37 % (18/30 cold)** |
| Later-call cache hit | 95 % | 91 % |
| Median model calls | 2 | 2 |
| Fallback loads | 0 | 3, all on the deliberately unrouted "Am I running low on juice?" (+1 call, 4.4 → 5.2 s median) |

### 3. Real phone (Moto G, bench agentd on :8788, Codex 0.163), 5 tasks × 2 interleaved rounds
`node bench/climb.js --ab all,plugins --rounds 2 --tasks chrome,calc,reply,battery,trackers --prefix plph`. Battery (Device settings) and trackers (Trackers & firewall) are plugin tasks and read-only. Alarm and timer were left out because their setup wipes Clock (`pm clear`) and they'd leave a real alarm behind.

| Task | all: ok / median s / 1st-call tokens | plugins: ok / median s / 1st-call tokens |
|---|---|---|
| chrome | 2/2 · 5.9 · 13,428 | 2/2 · 6.6 · 9,103 |
| calc | 2/2 · 13.0 · 13,589 | 2/2 · 11.8 · 9,264 |
| reply (Pigeon, auto-approved) | 2/2 · 12.0 · 13,500 | 2/2 · 12.9 · 9,175 |
| battery | 2/2 · 4.1 · 13,425 | 2/2 · 4.1 · 9,323 |
| trackers | 2/2 · 6.4 · 13,425 | 2/2 · 6.7 · 9,568 |
| **Sum of medians** | **41.4 s** | **42.1 s** |
| First-call cache hit | 78 % (1/10 cold) | 46 % (3/10 cold) |
| Later-call cache hit | 97 % | 93 % |

Speed is a tie: differences are inside the run-to-run noise (all/chrome ran 27.6 s once because the page was slow). Tokens: **−31 %** on every task. The report's gate was ≥ 35 %. Both picked plugins were right (battery → device, trackers → privacy); there were no fallbacks.

### 4. Growth check (simulated 152 tools: 62 + 90 dummy tools in 10 fake plugins)
Probe straight against Codex/terra, 3 requests × 3 reps:

| | Tools sent | First-call tokens | Correct tool | Median / mean s |
|---|---|---|---|---|
| all, 62 tools | 62 | 13,829 | 9/9 | 4.2 / 4.4 |
| all, 152 tools | 152 | **21,965** | 9/9 | 4.6 / 4.5 |
| plugins | 18–22 | **9,504–9,842** | 9/9 | 3.8 / 4.0 |

Accuracy held even at 152 tools with terra, and latency moved only a little. The token cost grows with every tool in `all` and stays flat in `plugins`. This is the case the design exists for.

### 5. Claude providers
- **Claude Code CLI** (Mac, 2.1.310, native ToolSearch + `alwaysLoad`; 2 tasks × 2 rounds): a 2-call turn read ~15.7k tokens in `plugins` vs ~30.4k in `all` (**−48 %**, almost all cache reads). The unrouted "low on juice" request found `device.status` by tool search with no `load_plugin`: +1 turn, 3.2–4.5 s vs 1.8–3.1 s. All 8 runs were correct. The phone's Claude Code (2.1.112) gets the `load_plugin` fallback. That path is the same code as Codex's, but it wasn't run on the phone.
- **Claude API**: no API key here, so not run live. Checked with a stubbed transport: the request carries `cache_control: {type:"ephemeral"}`, 16 core + 4 Calendar tools upfront, 42 tools `defer_loading`, and `tool_search_tool_bm25_20251119` last.
- **Local 0.8B**: checked offline with a stubbed llama-server. Device off → the grammar drops `setting`/`status` and the "flashlight" hint; Calendar+Media off → `add_event`, `play_music` and `media` are gone. Not run on the phone (the model is off by default).

## Enforcement tests
| Test | Where | Result |
|---|---|---|
| Calendar off, the model is asked to add an event | Mac agentd + mock broker, real model | **PASS**: no `calendar.*` reached the broker; the reply says the Calendar plugin can be turned on in Command center › Plugins |
| `runTool('add_event')` with Calendar off (a model calling a tool it wasn't shown) | Mac, and on the phone in `~/agentos/agentd` | **PASS** both: `decision:"refused"` from agentd, 0 calls reached the broker |
| `plugin_call {tool:"add_event"}` / `load_plugin {id:"calendar"}` with Calendar off | Mac | **PASS**: refused |
| Broker refuses agentd's uid | Phone: Calendar off via adb; `calendar.find` and `calendar.add` ("AgentOS plugin test (delete me)") called from Termux uid 10401 | **PASS**: both `refused: plugin calendar is turned off by the owner`, audit-logged; no event created |
| The model can't flip a switch | Mac + phone | **PASS**: no model tool exists for it; agentd's `rpc` passthrough → `method not allowed`; `plugins.set` from Termux uid → `only from the Plugins screen or adb, not from com.termux:10401`; asked in plain words, the model said it couldn't and the switch stayed off |
| The owner can | Phone: Command center › Plugins › App mods toggle off, then on | **PASS**: `plugins.list` showed `off:["mods"]`, then `[]`; audit: `plugins.set … by the owner (Plugins screen)` twice |
| Audit chain after all of the above | `tools-client.py audit` | OK, 3,187 lines, 8 marked breaks (the old ones), no new break |

All switches were left **on**.

## Checks after deploying (phone)
- Broker, launcher and agentd deployed; agentd restarted by the Magisk watchdog (pid 17746 → 29658). Default mode `all`.
- Typed timer task through `cli.js`: one model call, 9.4 s, timer started. The `DISMISS_TIMER` intent did **not** cancel it, so it rang a minute later; I stopped it with the notification's Stop button. No Clock notification is left.
- `node agentd/reviewer-test.js`: **18/19** on the first run. The miss was "buy, not asked": the reviewer model said `tap`, the test wants `fingerprint|block`. `reviewer.js` wasn't changed, so this looks like model variance. A second run collided with a ~15-minute Mac sleep/network stall (16/19; every miss was "No review in time").
- `python3 android/tools-client.py audit`: chain OK. Not run: `e2e`, `approvals` and `notifs`, because they need a lease or approval card answered (a person at the phone, or `--press-approve` taps), which this task's rules forbid.

## Next (before making `plugins` the default)
1. **Cache misses from per-combination prefixes.** Each plugin combination is its own prefix, so first calls were cold 3–4× as often. Two options: test the report's option D (`thread/inject_items` with a synthetic tool-search pair, so every thread shares one prefix), or keep a small set of fixed combinations.
2. **Router recall.** Add synonyms from the held-out misses and a cheap person-action rule (`memory/resolve.js`: "ping/ring <person>" → messages), then re-measure on a *new* held-out set. Embeddings only if that stays under 97 %.
3. Run the same A/B on the phone with `--provider claude`, and the local bench with a plugin switched off.
