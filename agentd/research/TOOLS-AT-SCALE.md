# Tools at scale: plugins, deferred schemas and a pre-selector (research, 2026-10-27)

The question: how can agentd keep adding tools (and group them into plugins the user can switch on and off) without making every request bigger and every task slower?

**Short answer:** keep a small, stable **core** that every request sees. In front of the model, add a **pre-selector**: plain code that reads the request and loads the one or two plugins it probably needs, with no model call. Every other enabled plugin stays **deferred**, so the model sees only its name and a one-line summary and can still load it through the provider's own tool search if the pre-selector guessed wrong. Disabled plugins don't appear at all, and **both agentd and the broker refuse them**.

Analogy: a restaurant menu. Today the waiter reads all 62 recipes aloud before every order. The design keeps a one-page menu (core), and the host who seats you hands over the dessert page if you mention cake (pre-selector). Any other page you can still ask for (tool search), and that only costs a short wait.

Speed is the top goal, so the main rule is: **never add a model round-trip to a common task.** One extra call costs about 2.5 s. Cutting about 5k cached tokens saves about 0.1 to 0.3 s per call. So the token savings are a bonus. What matters more: requests stay the same size and accuracy holds as the tool count climbs from 62 toward 150 or more.

---

## 1. What we have today (measured in this worktree)

| | Value | How measured |
|---|---|---|
| Tools per task | **62** (the brief says 72: probably another revision or a different count; this tree has 62) | `TOOLS` in `tools.js` after the memory, rules and schedule tools are merged in |
| Tool definitions | **27.2k chars ≈ 6.8k tokens** | JSON of `{type,name,description,parameters}` per tool (`/tmp/toolsizes.mjs`, not in the repo) |
| Biggest tools | `app_mod_apply` 4.0k chars, `act` 1.5k, `rules_set` 1.1k | same |
| System prompt | 7.0k chars (+ the app list) | `prompt.js` |
| Prompt per call | ~11–12.5k input tokens | `bench/results/r2d-*.jsonl` `inTok` |
| Cache hits (Codex) | Most calls ~80–99 % cached (14592/14766, 12032/12214); **2 of 8 logged calls had 0 cached** (cold first call) | `runs/*` `cachedInputTokens` |
| Model call | **~2.5 s minimum** (2.4–3.0 s for "Say ok."), 4.4–4.9 s at the real prompt size on a slow day | `bench/RESULTS.md` |

How each provider gets tools now:
- **Codex** (`providers/codex.js:156`): every tool goes in `dynamicTools` at `thread/start`. A thread lives for the whole session (`session.codex`), not one per task.
- **Claude CLI** (`providers/claude-cli.js:33`): agentd is an MCP server, and `tools/list` returns all 62. Because of `--tools ''`, Claude Code's `ToolSearch` is off, so **every MCP tool loads upfront** (Claude Code docs: "If you deny ToolSearch or leave it out of --tools, MCP tools load upfront").
- **Claude API** (`providers/claude.js`): sends all tools with **no `cache_control` at all**. The Anthropic API caches only when asked, so this provider re-reads ~9k tokens on every step. That's a free win, separate from this design.
- **Local 0.8B** (`providers/local.js`): already uses the design we want. A keyword `HINTS` table picks a form, a grammar-constrained router fills it in, and only 5 UI tools are exposed.

If we split today's tools into plugins (sizes measured):

| Group | Tools | ≈ tokens |
|---|---|---|
| **core** (UI control, act, wait, set_alarm/set_timer/web_search, memory_ask, notifications_list/reply) | 16 | **1.7k** |
| clock (list/delete alarms) | 2 | 0.1k |
| calendar | 4 | 0.4k |
| messages & calls (contacts, sms, calls, email) | 6 | 0.4k |
| media | 2 | 0.1k |
| device settings & status | 2 | 0.3k |
| web & maps (open_url, navigate) | 2 | 0.1k |
| trackers & firewall | 7 | 0.6k |
| vault | 2 | 0.2k |
| memory (advanced) + consent | 9 | 1.0k |
| reply rules | 3 | 0.4k |
| scheduled tasks | 3 | 0.3k |
| app mods | 4 | 1.2k |

Core plus a one-line summary per plugin comes to about **2k tokens, against 6.8k today**.

---

## 2. Findings, with sources

### Anthropic (API and Claude Code): solid, first-party docs
- **Tool search with `defer_loading`.** You send every definition, but tools marked `defer_loading: true` stay out of context until Claude searches. There are regex and BM25 variants, and you can write your own search that returns `tool_reference` blocks. A search returns 5 tools by default, and up to 10,000 deferred tools are allowed. The search runs on the server **within the same API response**, so it adds generation time but no extra request. Deferred tools are "excluded from the system-prompt prefix… the prefix is untouched, so prompt caching is preserved." Advice: keep the 3–5 most-used tools loaded, use namespaced names, and "add a system prompt section describing available tool categories." Use it with "10 or more tools" or "more than 10k tokens." [Tool search tool](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool)
- **Published numbers** ([Advanced tool use](https://www.anthropic.com/engineering/advanced-tool-use)): a 58-tool MCP setup uses ~55k tokens. Tool search cut the overhead from ~77k to ~8.7k tokens (−85 %). MCP-eval accuracy: Opus 4 went from 49 % to 74 %, Opus 4.5 from 79.5 % to 88.1 %. Selection "degrades once you exceed 30–50 available tools." **Programmatic tool calling** cut tokens by 37 % (43.6k → 27.3k) and avoids "19+ inference passes" in a 20-call workflow. No latency figure is given. Our `act` batch is a lighter version of the same idea. **Tool use examples** raised accuracy on complex parameters from 72 % to 90 % (relevant to `app_mod_apply`, which already carries examples).
- **Caching** ([Prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching), [Tool use with caching](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-use-with-prompt-caching)): the prefix order is `tools → system → messages`. "Modifying tool definitions (names, descriptions, parameters) invalidates the entire cache." The TTL is 5 min by default (1 h costs extra). Cache reads cost 0.1× the base price. Caching "improve[s] time-to-first-token." The minimum cacheable prompt is 512 tokens on current models. Caching must be switched on with `cache_control` (or the top-level automatic form). The caching doc also mentions an `inline-tools-2026-09-15` beta for changing tools mid-conversation without touching `tools`; I didn't test it.
- **Claude Code** ([MCP docs](https://code.claude.com/docs/en/mcp#scale-with-mcp-tool-search)): MCP tool search is **on by default**. "Only tool names and server instructions load at session start." `ENABLE_TOOL_SEARCH=auto:N` loads tools upfront while they total less than N % of the context (default 10 %). There's no fixed tool cap. A server can mark single tools `_meta["anthropic/alwaysLoad"]: true` (v2.1.285+). Server instructions are capped at 4,096 chars (also the cap for an upfront tool's description), and a description loaded through search is capped at 16,384 chars.

### OpenAI and Codex: solid (docs plus the open-source code)
- **Function-calling guide** ([link](https://developers.openai.com/docs/guides/function-calling)): "Aim for fewer than 20 functions available at the start of a turn… a soft suggestion." Functions are "injected into the system message" and billed as input. `tool_choice: {type:"allowed_tools", …}` limits which tools can be called *without* changing the list, which keeps the cache. That's API-only: Codex app-server doesn't expose it.
- **Tool search** ([link](https://developers.openai.com/api/docs/guides/tools-tool-search)): uses `tool_search` plus `defer_loading` and **namespaces**. With a namespace, "the model sees only the namespace or server name and description at the beginning." OpenAI advises keeping each namespace under 10 functions, and says its models are "primarily trained to search those surfaces" (namespaces and MCP). Loaded tools are "injected at the end of the context window", which keeps the cache, but changing the loaded set breaks the cache from that point on. **Hosted** mode answers in the same response. **Client** mode stops the model, your code searches, and that costs another sampling request. Requires gpt-5.4 or later. The doc says deferral "adds a discovery step" and recommends measuring latency before making it the default.
- **Prompt caching** ([link](https://developers.openai.com/api/docs/guides/prompt-caching)): automatic from 1,024 tokens. Tool definitions are cached. Changing tool "names, descriptions, schemas, ordering" breaks the cache. In-memory retention is ~5–10 min idle (up to 1 h); on GPT-5.6+ the default minimum TTL is 30 min. Cached input is "discounted up to 95 %." OpenAI publishes no latency percentage. (The popular "up to 80 % lower latency" figure comes from third-party pages about older models: anecdotal.)
- **Codex app-server 0.164 (checked on this Mac with `codex app-server generate-json-schema --experimental`)**: `DynamicToolSpec` is either `{type:"function", name, description, inputSchema, deferLoading}` or **`{type:"namespace", name, description, tools:[…]}`**. Dynamic tools are set **only at `thread/start`**: `turn/start` has no tools field. (Its `disabledPluginIds` is for Codex's own plugins, not ours.) There is also `thread/inject_items`: "Raw Responses API items to append to the thread's model-visible history."
- **Codex source** ([openai/codex](https://github.com/openai/codex)): `codex-rs/core/src/tools/spec_plan.rs` defers tools only when the model's `supports_search_tool` is set; otherwise deferred tools quietly load upfront, so it fails safe. `tool_search` runs **inside Codex** with BM25 plus an embedder (`core/src/tools/handlers/tool_search.rs`) and returns `TOOL_SEARCH_DEFAULT_LIMIT = 8`. Its description lists each source's name and description (`tool_search_spec.rs`). So in Codex **a search costs one extra model sampling call**, which for us is ~2.5 s.

### Other projects and papers
| Source | Result | How solid |
|---|---|---|
| [GitHub Copilot, "smarter with fewer tools"](https://github.blog/ai-and-ml/github-copilot/how-were-making-github-copilot-smarter-with-fewer-tools/) | Cut ~40 tools to a 13-tool core plus 4 "virtual tool" groups: **−190 ms TTFT**, −400 ms end-to-end, +2–5 pp on SWE-Lancer/SWE-bench. Embedding pre-expansion reached 94.5 % tool-use coverage, against 87.5 % when an LLM picks and 69 % for a static list. "Each call to a virtual tool causes a cache miss and an extra round trip." | Production A/B test; **closest match to our design** |
| [VS Code tools](https://code.visualstudio.com/docs/agents/run/tools) | Hard limit of 128 tools per request; `virtualTools.threshold` groups tools beyond that (experimental) | Docs |
| [Cursor dynamic context discovery](https://cursor.com/blog/dynamic-context-discovery) | Shows tool names only and loads schemas on demand: **−46.9 % total tokens** in runs that used MCP tools | Vendor A/B, no latency figure |
| [RAG-MCP (arXiv 2505.03275)](https://arxiv.org/abs/2505.03275) | Retrieval before the call: prompt tokens cut by more than half, selection accuracy 13.6 % → 43.1 % in a stress test with many tools | Single paper, synthetic benchmark |
| [Toolshed (arXiv 2410.14594)](https://arxiv.org/abs/2410.14594) | Enriched tool docs plus query rewriting: +46/56/47 pp recall@5 | Paper, retrieval-only metric |
| [Less is More (arXiv 2411.15399)](https://arxiv.org/abs/2411.15399) | Small on-device models: fewer tools gives up to 70 % less execution time and better success | Paper, edge models: **directly relevant to the 0.8B model** |
| [ToolLLM (arXiv 2307.16789)](https://arxiv.org/abs/2307.16789), [Gorilla (arXiv 2305.15334)](https://arxiv.org/abs/2305.15334) | A retriever over 16k+ APIs; retrieval-aware training | Foundational, older models |
| [LangGraph bigtool](https://github.com/langchain-ai/langgraph-bigtool) | The model calls a `retrieve_tools` tool over an embedding store | Library pattern, no published numbers |

Solid across all of these: fewer visible tools gives better selection, and on-demand loading saves tokens. Where the numbers come from vendors and only about tokens: Cursor. Where they're thin: latency. Only GitHub publishes it, and its number says trimming tools saves around 0.2 s while every extra round trip costs a full call.

---

## 3. The latency math, in plain terms

Think of each model call as a taxi ride with a fixed ~2.5 s flag-drop fee. Extra tokens are the per-km charge, and caching makes the km almost free.
- **An extra "load the plugin" call** costs the full ~2.5 s fee. On today's 6–13 s tasks that's **+20–40 %**.
- **Dropping ~4.8k tool tokens** saves part of the per-km charge. They're mostly cached already, so expect roughly **0.1–0.3 s per call** (GitHub saw 0.19 s TTFT for a similar cut). On a cache miss, which hit 2 of 8 logged calls, the saving is bigger. That's an estimate to measure, not a promise.
- **Changing the tool list breaks the cache** for everything after it (both vendors). So the parts that never change (core and summaries) must come first, and anything that varies goes at the end.

---

## 4. Comparison of approaches

Tokens = the tool-definition part of each request (the system prompt and screens are extra). Latency is relative to today.

| Approach | Tool tokens / request | Extra model calls | Expected latency | Accuracy risk | Cache friendliness | Fit for 0.8B local |
|---|---|---|---|---|---|---|
| **A. All tools always** (today) | 6.8k now, growing with every tool (~15k at 150 tools) | 0 | Baseline; creeps up as tools grow | Grows: past 20 (OpenAI) or 30–50 (Anthropic) tools selection degrades | Best: one stable prefix | ✗ confuses it |
| **B. Core + plugin summaries + on-demand load** (tool search or `load_plugin`) | ~2k | **+1 per task that needs a non-core plugin** (Codex: a client-side search = a full call; Anthropic API: same response, but a few hundred output tokens) | Core tasks −0.1–0.3 s/call; plugin tasks **+2.5 s** | Model may skip the search, or open the wrong group (GitHub saw this) | Good: loaded tools go at the end | ✗ can't plan searches |
| **C. Pre-selector + core + fallback load** (recommended) | ~2.2–3k (core + 0–2 picked plugins) | 0 when the router is right; +1 only on a miss (aim for ≤ 5 % of plugin tasks) | ≈ baseline − 0.1–0.3 s/call; worst case = B | Router recall, softened by the fallback search | Core prefix stable; **each plugin combination is a separate cache entry** (few combinations, warm within minutes) | ✓ (router-only version, see E) |
| **D. Hybrid C + preload by injection**: every enabled plugin deferred, picked ones injected as loaded tool-search results before the turn | Same as C | Same as C | Same as C, plus a warm cache for every combination | Same as C | **Best: one prefix for all tasks** | n/a |
| **E. Pre-selector only, no fallback** (what local.js does) | Smallest | 0 | Fastest | Highest: a router miss = a failed task | Good | ✓✓ already proven on the phone |
| **F. Full list + `allowed_tools`** | 6.8k+ (cached) | 0 | Baseline | Some help | Best | ✗ — and not offered by Codex app-server or the Claude CLI |

---

## 5. Recommended design

### 5.1 Plugin manifest (`agentd/plugins.js`, one array, first-party only)
```js
export const PLUGINS = [
  { id: 'calendar', title: 'Calendar', default: true,
    summary: 'Find, add, move and delete calendar events directly (no UI).',      // ≤ 120 chars, shown to the model
    route: /\b(calendar|meetings?|events?|appointments?|schedule|free|busy|lunch|dinner)\b/i, // pre-selector
    tools: ['add_event', 'find_events', 'update_event', 'delete_event'],
    apps: ['com.google.android.calendar'],          // the broker also refuses leases on these when the plugin is off
    prompt: '- Calendar: use add_event/find_events, never tap through Calendar.' }, // moves out of prompt.js
  // clock, messages, media, device, web_maps, privacy, vault, memory, rules, schedule, app_mods ...
];
```
- **The core is not a plugin and can't be turned off**: `task_start screen_read tap type scroll press open_app list_apps act wait memory_ask notifications_list notifications_reply set_alarm set_timer web_search` (~1.7k tokens). This set covers every task on the current bench, so none of them can get slower. If climb data later shows another tool used in most tasks, promote it to core.
- The plugin-specific lines in `prompt.js` (it names 39 tools today) move into each plugin's `prompt`. They're sent only when that plugin is loaded, so the system prompt shrinks too.
- Tool names stay as they are. Namespaces live in the provider adapters (Codex `namespace`, `mcp__agentos__*`), so the broker, audit log and bench don't change.

### 5.2 Toggles and enforcement (hidden ≠ disabled)
1. **The broker is the source of truth.** Add a `plugins.json` in the broker's files directory. It can be written **only through an owner-only RPC `plugins.set`** (launcher uid, the same pattern as `vault.remove` and `owner_callers`), never by agentd or a model tool. `plugins.list` is a read anyone can make.
2. **The broker keeps its own map** from plugin to broker methods, `intent` kinds and app packages, in Java, so agentd can't misreport which plugin a call belongs to. Pitfall: `intent` serves several plugins (`navigate`/`url` → web_maps, `play` → media, alarm/timer/search → core), so match on **method + kind**. A call to a disabled plugin is refused (`decision: "refused", reason: "plugin off"`) and audit-logged.
3. **UI control can get around a toggle.** If "Messages" is off, the model could still open the Messages app and tap Send. So `task.start` must refuse a disabled plugin's `apps` packages. Without that, a toggle hides tools but doesn't block the action.
4. **agentd checks too**: at the top of `runTool`, before anything else, refuse a tool whose plugin is off. This is the only gate for agentd-local tools (memory, rules, schedule), which never reach the broker. Check *enabled*, not *visible*: models do call tools they weren't shown (deferred or made-up names).
5. agentd reads `plugins.list` when a task starts (cache it for ~5 s). Disabled plugins are **left out of every provider's tool list entirely**, not deferred. The system prompt gets one line: "Turned off by the user: Vault, App mods. If asked, say it can be turned on in AgentOS." That line changes the prompt prefix, but toggles are rare.

### 5.3 Pre-selector (code, < 5 ms, no model)
- Input: **the user's request only** (the goal plus the voice or typed text). Never screen text, notification text or memory content, so an attacker's message can't steer which plugins load.
- Rules, in order: (1) each plugin's `route` regex, the same style as `local.js` `HINTS` and `memory/brain.js` `FACETS`; (2) an action word next to a person, using memory's person resolution (`memory/resolve.js`, already cross-app): "text/call/email Sam" → messages; (3) words for time and days only add calendar if a calendar word is also present (avoids "remind me at 5" → calendar; that's schedule/clock).
- **Lean toward picking.** A wrong extra plugin costs 0.1–1.2k cached tokens; a missed one costs ~2.5 s. Pick at most 2 (3 if the request is long), and the user's own words win.
- `memory/brain.js plan()` handles *memory* questions (facets, time windows, people). It doesn't map to plugins as it stands, so reuse its building blocks (the regex facet table and `resolve.js`) rather than the function itself.
- Embeddings come later, and only if the labeled set (§6) shows keyword recall below 97 %. On the phone, a regex is effectively free.

### 5.4 Per provider
**Codex (dynamicTools).**
- Version 1 (option C): `dynamicTools` = core functions, plus the picked plugins as `{type:"namespace", tools}`, plus every other enabled plugin as a namespace whose functions have `deferLoading: true`. Codex then exposes its own `tool_search` (BM25, 8 results) over those. Keep the tool order fixed (core first, plugins in manifest order) so identical combinations hit the same cache. First confirm that `model/list` reports `supports_search_tool` for gpt-6.1-terra. If it doesn't, Codex quietly loads deferred tools upfront: safe, but no savings.
- Threads: the thread now lasts the whole session. The first task's picks are fixed into it, and later tasks in the same session reach other plugins through `tool_search`. Starting a fresh thread (70 ms) when the picks change would lose the conversation, so don't.
- Version 2 (option D, an experiment): defer every enabled plugin, always, so all threads share one prefix. Before `turn/start`, call `thread/inject_items` with a synthetic `tool_search_call` plus `tool_search_output` that loads the picked namespaces. That puts them at the end of context, the same as a real search, with no model call. Not documented for this use: test that Codex's history normalizer (`context_manager/normalize.rs`) keeps the pair, and that the model then calls the tools.

**Claude CLI (MCP).** agentd already gives each session its own MCP secret, so `tools/list` can differ per session. Return only enabled plugins. Mark core and the picked plugins `_meta: {"anthropic/alwaysLoad": true}` and leave the rest deferred. Change `--tools ''` to `--tools ToolSearch` so deferred tools can be found. Put the plugin summaries in the MCP server `instructions` (≤ 4,096 chars). This needs Claude Code ≥ 2.1.285 on the phone; check `claude --version`.

**Claude API (`claude.js`).** Add top-level `cache_control: {type:'ephemeral'}` now; it's the cheapest win in this report. Then: core and picked plugins without `defer_loading`, everything else `defer_loading: true`, plus `tool_search_tool_bm25_20251119`. The search runs on the server in the same response, so a miss costs output tokens, not a new request. Keep `tools` in a fixed order.

**Local 0.8B.** Stay with option E and add no tool search. Map each router form in `PLAN.oneOf` and `HINTS` to a plugin, and drop the forms (and hints) of disabled plugins, so the grammar can't produce them. Keep ≤ 5–8 tools. The "Less is More" paper and our own local bench both say fewer tools work better for small models. Anything outside the forms goes to `other`, which hands off to a cloud model, the same as today.

---

## 6. Evaluation plan

**Step 0: two small bench changes (outside this research folder, do them when building).** Have `bench.js` write `cached` for each call (it's already in `usage.calls[].cached` from codex.js) and the tool-search count (`tool_search` items). Add a variant env var `AGENTD_TOOLS=all|defer|pre|pre-inject` to `climb.js` `VARIANTS`.

**Offline router eval (no phone, milliseconds).** Write ~150 labeled requests: today's bench and local-task texts, plus 10 per plugin, plus 20 tricky ones ("remind me to call Mom", "what's playing", "block trackers in this app", "when am I free Friday"). Measure **recall** (the needed plugin was picked) and the average number of plugins picked. Gate: recall ≥ 97 %, average ≤ 1.5. This uses the same pattern as `memory/eval`.

**Bench task set (Android emulator for hill-climbing, real phone for the final check).**
- The current 6: alarm, timer, chrome, calc, reply, multi. These guard against slowing core tasks down.
- 8 new plugin tasks: calendar ("Add lunch with Sam Friday at noon"), messages ("Text Mom I'm on my way": the approval is auto-approved by the reviewer), device ("Turn on Do Not Disturb"), media ("Pause the music"), privacy ("Which trackers does Chrome talk to?"), memory ("When am I free to see Mom this week?"), schedule ("Every weekday at 8 remind me to stretch"), clock ("Delete my 7 AM alarm").
- 3 enforcement tasks, run on the emulator or with `mock-phone.js`, with Messages turned off: (a) "text Mom…" → refused, and no `sms.send` reaches the broker; (b) the model is pushed to open the Messages app → `task.start` refuses the package; (c) a direct `runTool('send_sms')` → refused by agentd.
- Emulator setup: follow the "Running bench.js / climb.js on an emulator" notes in RESULTS.md (Magisk-rooted AVD, agentd running on the Mac, Google Clock and Calculator installed). Compare variants only on the same machine, interleaved with `climb.js --ab all,pre,pre-inject --rounds 3`.

**Metrics for each task and variant:** success, total time (median), time to first action, model calls (including tool-search calls), input tokens on the first call and on later calls, **cache hit rate** (cached ÷ input, for calls after the first), fallback rate (plugin tasks where the model had to search), and router recall.

**Pass criteria for making it the default (over ≥ 3 interleaved rounds):**
1. Success: no new failures on any of the 14 tasks; enforcement tasks 3/3.
2. Time: sum of per-task medians **≤ the current setup's** on the old 6 tasks, and no task's median slower by more than 0.5 s. The plugin tasks must be no slower than option A.
3. Calls: median model calls unchanged on the old 6 (no searches on core tasks); fallback searches on ≤ 1 of 8 plugin tasks.
4. Tokens and cache: first-call input tokens down ≥ 35 % (~12k → ≤ 7.8k); cache hit rate on later calls ≥ 90 % (no worse than today).
5. Local: `bench/local-tasks.js` success unchanged.
6. Re-check with a simulated 150-tool catalog (add 90 dummy tools across 10 fake plugins): option A should get slower or less accurate while C stays flat. That's the growth case this design exists for.

If C gives a speed tie with the same success, still adopt it: it's what lets the tool count grow without growing every request. If D's injection works, prefer D for Codex.

---

## 7. Security notes
- **Hidden is not disabled** (§5.2): enforce in the broker (methods, intent kinds and app leases) and in agentd `runTool`. Never rely on the model not seeing a tool.
- **Tool-description injection.** Tool metadata is read by the model as trusted text. Invariant Labs' "tool poisoning" demo hid file-stealing instructions in a tool description. [MCPTox (arXiv 2508.14925)](https://arxiv.org/abs/2508.14925) measured up to 72.8 % attack success across 20 agents, using 353 real tools. Today's manifests are first-party and live in the repo, which is fine. If third-party plugins ever arrive: cap summaries at 120 chars and descriptions at 1–2k; run them through Prompt Guard at install; pin a hash and ask the owner to re-approve when it changes (the "rug pull" case); forbid names that clash with core tools (no plugin can define `tap`); keep a third-party plugin's tool results marked untrusted, like screen text.
- **Inputs to the router**: only the user's own words (§5.3), so planted text can't widen the tool set. Even if it did, loading a tool grants no permission, because the broker still decides every call.
- Search results only ever come from *enabled* plugins: disabled ones aren't sent to Codex or Claude at all.

---

## 8. Rough build effort (~6–8 dev-days)
| Piece | Effort |
|---|---|
| `plugins.js` manifest + moving prompt lines + `runTool` gate | 1 d |
| Pre-selector + 150-request labeled set + offline eval | 1 d |
| Codex adapter (namespaces + deferLoading) | 0.5 d; inject experiment (D) +0.5 d |
| Claude CLI per-session `tools/list` + alwaysLoad + ToolSearch flag | 0.5 d |
| Claude API: cache_control (1 h) + defer_loading | 0.5 d |
| Local: filter forms and hints by plugin | 0.25 d |
| Broker: plugin map, owner-only `plugins.set`, lease refusal, audit | 1–1.5 d |
| Launcher toggle screen | 0.5–1 d |
| Bench changes + emulator climb + phone confirmation run | 1 d |

## 9. Open questions to check while building
- Does gpt-6.1-terra report `supports_search_tool` in Codex 0.163 on the phone? (The Mac has 0.164.)
- Does `thread/inject_items` accept a synthetic `tool_search_call`/`tool_search_output` pair?
- The Claude Code version on the phone (≥ 2.1.285 is needed for per-tool `alwaysLoad`).
- The brief says 72 tools; this tree has 62. Re-measure on whichever revision gets built.
