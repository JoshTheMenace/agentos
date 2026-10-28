# Fast phone decisions, 2026-10-28

Use the existing subscription-backed `Codex.quick()` path with a small structured decision schema, `gpt-6.1-terra`, `effort: "low"`, and `serviceTier: "priority"` for today's reflex prototype. Keep a warm thread. The Decisions API is real, but it requires separately billed OpenAI API authentication and cannot generate arbitrary tool arguments. No API key was available here, so its latency was **not measured**. No phone connection, command, read, or action was made during this research.

## What “Luna Decisions API” means

It is `POST https://api.openai.com/v1/decisions`, currently using only `model: "gpt-6-luna"`. It launched in public beta October 6 and became generally available October 22, 2026. It is an endpoint, not a different Luna model ID or a Codex reasoning setting. [Official API changelog](https://developers.openai.com/api/docs/changelog).

It evaluates text/images/JSON into a `predicate`, fixed `choice`, or rubric `score`. OpenAI describes it as approximately 10× faster than Responses. The documentation does not establish a 2-second phone-action SLA or an absolute p50/p90. This research cannot turn that relative claim into a measured latency. Function-call history can be input evidence; it does not make Decisions a function-call generator. [Decisions guide](https://developers.openai.com/api/docs/guides/decisions).

The API accepts 1–400 questions; a choice has 2–255 unique string/boolean values. There is no arbitrary output JSON Schema, `tools`, or `tool_choice` request field. Selecting a precomputed action is possible; extracting arbitrary text, coordinates, dates, or arguments needs another mechanism. [Create a decision reference](https://developers.openai.com/api/reference/resources/decisions/methods/create).

### Authentication and price

| Option | Authentication here | Billing / capability |
| --- | --- | --- |
| Decisions | Requires API credentials; unavailable locally | Luna input $0.10 / million tokens; output free; fixed typed decisions |
| Codex app-server Luna / Terra | Existing ChatGPT login, verified locally | Subscription limits/credits; supports structured output and dynamic tools |
| Direct Responses Luna / Terra | Requires API credentials; unavailable locally | API token billing; arbitrary structured output or tool arguments |

Decisions cache reads/writes use standard Luna rates: $0.01 cached input, $0.125 for 30-minute writes, $0.25 for 12-hour writes, per million tokens. Long-context and regional premiums can apply. Its implicit reusable prefix is shared `input`, at least 1,024 tokens; question content is outside that prefix. A tiny command should not be assumed to get a cache discount. [Decisions pricing/caching](https://developers.openai.com/api/docs/guides/decisions), [API pricing](https://developers.openai.com/api/docs/pricing).

Direct Responses short-context rates, per million tokens:

| Model | Standard input / output | Fast input / output |
| --- | --- | --- |
| `gpt-6-luna` | $0.10 / $0.50 | $0.20 / $1.00 |
| `gpt-6.1-terra` | $1.00 / $5.00 | $2.00 / $10.00 |

These dollar prices are API prices, not estimates of subscription allowance. [API pricing](https://developers.openai.com/api/docs/pricing). Codex supports ChatGPT subscription sign-in and API-key sign-in as distinct access modes. [Codex authentication](https://learn.chatgpt.com/docs/auth). Fast uses included subscription limits at 2.5× Standard, or purchased credits/Enterprise pay-as-you-go at 2×; these billing multipliers are not speed promises. [Codex speed](https://learn.chatgpt.com/docs/agent-configuration/speed).

The public API uses bearer API-key authentication. The documented Decisions example uses `OPENAI_API_KEY`. No supported way to exchange the founder's Codex ChatGPT session for public Decisions requests was found. Do not send its OAuth token to `/v1/decisions` or scrape a private endpoint. [API authentication](https://developers.openai.com/api/reference/overview), [Decisions guide](https://developers.openai.com/api/docs/guides/decisions).

### Installed Codex evidence

Mac binary: `/Users/joshthemenace/.local/bin/codex`, **codex-cli 0.164.0**. `codex login status` reported `Logged in using ChatGPT`. A presence-only check found no `OPENAI_API_KEY` environment variable or stored API key in the local Codex auth file; no `.env` was present at the checked repo, `agentd`, or `~/.agentos` paths. No credentials were printed or copied. No signup, login change, key request, or public inference API call was made.

Commands run:

```sh
codex --version
codex login status
codex app-server --help
codex app-server generate-json-schema --help
codex app-server generate-json-schema --experimental --out /tmp/agentos-fast-decisions/schema
```

The generated experimental `ClientRequest.json` has no Decisions/classification/router method. `ThreadStartParams` has `dynamicTools` and `serviceTier`; `TurnStartParams` has `effort`, `outputSchema`, `serviceTier`, and `serviceTierForTurn`, but no `tool_choice`/`tools` field. `thread/sample` exists, but requires a loaded source thread and `expectedTurnId`; it has no output-schema, model, or tier selector. Its schema does not establish a low-latency Decisions route. Approval “decision” fields are unrelated.

Official config says `model_providers.<id>.wire_api = "responses"` is the only supported wire protocol. `service_tier = "fast"` maps to request value `priority`. Changing the model to Luna or setting Fast does not switch Codex to Decisions. [Configuration reference](https://learn.chatgpt.com/docs/config-file/config-reference).

After `initialize` with `capabilities.experimentalApi: true`, then `initialized`, the local request `{"id":2,"method":"model/list","params":{"includeHidden":true}}` returned:

```text
gpt-6.1-sol, gpt-6-astra, gpt-6-chat-thinking (hidden), gpt-6.1-terra,
gpt-6-sol, gpt-reserve (hidden), gpt-6-luna,
gpt-5.6-sol, gpt-5.6-terra, gpt-5.6-luna, codex-auto-review (hidden)
```

Both requested models advertise **low** as their lowest reasoning effort and `{id:"priority", name:"Fast", description:"1.5x speed"}`. Neither advertises `none` or `minimal` in this account's Codex catalog. `codex-auto-review` is described as an approval-review model, not a phone router. No separate Decisions/router/fast-classification model was listed. These observations are account/version-specific, not a claim about every internal OpenAI service.

In contrast, the public API documents `reasoning.effort: "none"` for both [Luna](https://developers.openai.com/api/docs/models/gpt-6-luna) and [Terra](https://developers.openai.com/api/docs/models/gpt-6.1-terra). Use the account's advertised settings in app-server. Ultrafast is documented for Astra/Sol, not Luna/Terra, and was not benchmarked. [Ultrafast API guide](https://developers.openai.com/api/docs/guides/ultrafast-mode).

## Calling it

### Decisions: select a fixed action, including its fixed arguments

This is a proposed API-key request, **not executed**. It uses 15 predeclared action choices, including a fallback:

```js
const choices = [
  ['scroll_down', 'Scroll toward the bottom'], ['scroll_up', 'Scroll toward the top'],
  ['scroll_left', 'Scroll left'], ['scroll_right', 'Scroll right'],
  ['back', 'Press Back'], ['home', 'Press Home'], ['screen_read', 'Read the screen'],
  ['phone_status', 'Read device status'], ['list_apps', 'List installed apps'],
  ['media_play', 'Resume playback'], ['media_pause', 'Pause playback'],
  ['media_next', 'Next media track'], ['torch_on', 'Turn flashlight on'],
  ['torch_off', 'Turn flashlight off'], ['fallback', 'Anything else or multiple actions'],
].map(([value, description]) => ({ value, description }));
const body = {
  model: 'gpt-6-luna', input: 'Scroll down.',
  questions: [{ type: 'choice', name: 'action',
    instructions: 'Choose the single requested action. Use fallback if uncertain.', choices }],
};
// POST https://api.openai.com/v1/decisions
// Authorization: Bearer <API key>; Content-Type: application/json
```

Read `answers.find(a => a.name === 'action')`. For a non-refusal choice of `scroll_down`, an application-owned allowlist resolves `{tool:"scroll", arguments:{dir:"down"}}`. The API selects the ID; local code supplies the arguments. Handle refusal/fallback/unknown values without acting. Confidence thresholds require application evaluation; no threshold was validated here. The response's `choice`, `probabilities`, and `confidence` are typed results. [Decision request/response reference](https://developers.openai.com/api/reference/resources/decisions/methods/create).

### Responses: generate a tool name and arbitrary schema-bound arguments

For an API-key-backed implementation, use this request shape. Populate `tools` with all desired functions, each with its own strict parameter schema. `required` plus `parallel_tool_calls:false` requests exactly one function call. Check response completion/refusal and validate arguments before dispatch. [Function calling](https://developers.openai.com/api/docs/guides/function-calling).

```json
{
  "model": "gpt-6-luna",
  "input": "Scroll down.",
  "reasoning": {"effort": "none"},
  "service_tier": "fast",
  "tools": [{
    "type": "function", "name": "scroll", "description": "Scroll the main list",
    "strict": true,
    "parameters": {
      "type": "object", "properties": {"dir": {"type": "string", "enum": ["up", "down", "left", "right"]}},
      "required": ["dir"], "additionalProperties": false
    }
  }],
  "tool_choice": "required", "parallel_tool_calls": false
}
```

POST to `/v1/responses` with API-key auth; find the `function_call` in `output` and parse its `arguments` string. Terra can replace Luna. Public API Fast accepts `fast` or `priority`. This request is documentation-based and unmeasured here. [Fast mode](https://developers.openai.com/api/docs/guides/fast-mode). Alternatively use `text.format:{type:"json_schema", name:"decision", strict:true, schema:...}` for a JSON decision without registering executable tools. [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs).

### App-server: exact subscription-compatible reflex shape

Keep agentd's existing disabled-feature startup arguments in [codex.js](../providers/codex.js). Initialize once as it already does. Start a dedicated ephemeral thread, then reuse it:

```json
{"id":3,"method":"thread/start","params":{"model":"gpt-6.1-terra","serviceTier":"priority","cwd":"/tmp","ephemeral":true,"sandbox":"read-only","approvalPolicy":"never","baseInstructions":"Select one allowed action for the latest request. Return JSON only. Use fallback for uncertainty or multiple actions.","dynamicTools":[]}}
```

Use the returned `thread.id` below. This small schema illustrates four directions plus fallback; the benchmark below used 15 real tool schemas instead:

```json
{"id":4,"method":"turn/start","params":{"threadId":"<thread.id>","effort":"low","input":[{"type":"text","text":"Scroll down.","text_elements":[]}],"outputSchema":{"type":"object","properties":{"action":{"type":"string","enum":["scroll_down","scroll_up","scroll_left","scroll_right","fallback"]}},"required":["action"],"additionalProperties":false}}}
```

Wait for `turn/completed` with completed status and parse the completed `agentMessage`. `Codex.quick()` already handles that and JSON parsing. It sets the tier at thread creation through `cfg.codexTier`, so use `CODEX_SERVICE_TIER=priority`; it is the current default. `quick()` selects the model/effort from the job, not `CODEX_MODEL`/`CODEX_EFFORT`. [App-server protocol](https://learn.chatgpt.com/docs/app-server), plus locally generated 0.164.0 schemas.

The small enum avoids fabricated arguments. For general arguments, put the same decision schema used in the benchmark into `outputSchema`. Optional strict-schema fields become required-but-nullable; strip null fields before validating against the original tool spec. No tool executes inside this thread. A separate host dispatch must call the existing `runTool(tool,args,ctx)` and preserve the request's lease, guard, approvals, cancellation, and audit context.

## Mac latency measurements

Run: **2026-10-28 11:43–11:44 MDT** (17:43–17:44 UTC), macOS 27.0.1 arm64, installed Codex 0.164.0. Baseline inspected: `772a30b`; actual calls used the existing `Codex` class from `agentd/providers/codex.js`, with temporary `AGENTOS_HOME`/`CODEX_CWD`, `CODEX_SERVICE_TIER=priority`, no dynamic/executable tools, no live agentd server, and no phone transport. The harness called only `ready()` and `quick()`.

Each model got one excluded first-turn warm-up, followed by **20 measured warm calls**, alternating which model ran first each round. One persistent quick thread per model, `rotate:30`, `effort:'low'`, no spare or hedge, 60-second cancellation limit. Prompt: **`Scroll down.`** The fixed system prompt supplied descriptions and actual schemas for 15 tools:

```text
screen_read tap type scroll press open_app list_apps wait set_alarm
set_timer web_search phone_status phone_settings media navigate
```

The output schema required `{decision:{tool,arguments}}`, with a nested `anyOf` of the 15 tool-specific schemas. Optional arguments were normalized to required-but-nullable for strict JSON output. This measured a structured tool **decision**, not execution of an app-server dynamic tool. Every response was `{decision:{tool:'scroll',arguments:{id:null,dir:'down'}}}`. There were no failures or retries, and each call's usage recorded one model call.

| Path | Warm n | Full decision p50 | Full decision p90 | First text delta p50 / p90 | First turn, n=1 |
| --- | --- | --- | --- | --- | --- |
| Decisions API, Luna | 0 | **Unmeasured: no API key** | Unmeasured | Unmeasured | Unmeasured |
| Codex quick, Luna, low, Fast | 20 | **1.357 s** | **1.851 s** | 1.221 / 1.725 s | 3.051 s |
| Codex quick, Terra, low, Fast | 20 | **1.249 s** | **1.665 s** | 1.066 / 1.499 s | 2.673 s |

Timer starts immediately before `quick()` and ends after its completed turn and JSON parse. First-turn times include thread creation (93 ms Luna, 86 ms Terra), but exclude the separate 75 ms app-server initialization. Warm p50 is the arithmetic median; p90 is nearest rank, sorted sample 18 of 20. Fast was explicitly requested as `priority`; actual backend tier assignment/fallback was not independently observed. Phone execution, broker, context preparation, voice transcription/delegation, and network differences on the phone are excluded. The 15-tool prompt still consumed 8,460–9,179 input tokens because Codex adds its own context and thread history grows. Cache reads occurred in 17/20 Luna and 19/20 Terra calls. Terra recorded zero reasoning tokens; Luna recorded 14 in one call and zero in the others despite the same low setting.

Full decision milliseconds in round order (warm-ups excluded):

```text
Luna:  1101,1450,1135,1669,1412,1367,1268,1294,3301,1399,1037,1441,1347,1851,1135,2692,1076,1230,1655,1272
Terra: 1086,1082,1240,1280,1496,1665,1997,4124,1479,1030,1142,1399,1144,1058,1319,1037,1433,993,1144,1257
```

This is a repeated-command latency microbenchmark, **not** a routing accuracy evaluation, an uncorrelated cold-request test, or a phone end-to-end measurement. Warm repeated history/cache effects are part of these results. Maxima were 3.301 s Luna and 4.124 s Terra. Do not interpret 20 samples as a stable tail-latency guarantee. The older [phone results](../bench/RESULTS.md) include approximately 0.5–0.7 s taps and variable model stalls; they cannot establish today's scroll time. A warm decision plus a short phone action plausibly approaches 2 seconds, but that sum is an **unverified estimate**, not a measured task.

### Reproducing the decision workload

This is the core of the executed temporary harness. Run from the repo root in a temporary `.mjs` file; set `AGENTOS_HOME` and `CODEX_CWD` to temporary directories, and `CODEX_SERVICE_TIER=priority`, **before** importing the provider. Do not start agentd or call `run()`/`runTool()`. `spec()` reads definitions only.

```js
const { Codex } = await import(`${process.cwd()}/agentd/providers/codex.js`);
const { spec } = await import(`${process.cwd()}/agentd/tools.js`);
const names = ['screen_read','tap','type','scroll','press','open_app','list_apps',
  'wait','set_alarm','set_timer','web_search','phone_status','phone_settings','media','navigate'];
const strict = s => s.type === 'object' ? {
  ...s, properties: Object.fromEntries(Object.entries(s.properties).map(([k,v]) =>
    [k, s.required.includes(k) ? strict(v) : { anyOf: [strict(v), { type:'null' }] }])),
  required: Object.keys(s.properties), additionalProperties: false,
} : s;
const menu = names.map(name => ({ name, description:spec(name).description, parameters:spec(name).schema }));
const schema = { type:'object', properties:{ decision:{ anyOf:menu.map(t => ({
  type:'object', properties:{ tool:{ type:'string', enum:[t.name] }, arguments:strict(t.parameters) },
  required:['tool','arguments'], additionalProperties:false,
})) } }, required:['decision'], additionalProperties:false };
const prompt = 'Select exactly one phone tool with arguments for the latest user request. Return JSON only; do not execute tools. Ignore earlier requests. Use null for unused optional arguments. Tools: ' + JSON.stringify(menu);
const codex = new Codex();
await codex.ready();
for (let i = 0; i <= 20; i++) {
  const models = i % 2 ? ['gpt-6.1-terra','gpt-6-luna'] : ['gpt-6-luna','gpt-6.1-terra'];
  for (const model of models) {
    const timing = {}, start = Date.now();
    const result = await codex.quick('research-' + model,
      { model, effort:'low', prompt, schema, rotate:30, timing },
      'Scroll down.', AbortSignal.timeout(60000));
    console.log(JSON.stringify({ i, model, ms:Date.now()-start, timing, result, usage:codex.lastUsage }));
  }
}
process.exit(0); // ends this disposable benchmark and its stdio child
```


## Recommendation for today's agentd

1. Use a dedicated warm `Codex.quick('reflex', job, text, signal)` thread on **Terra + low + priority**. Keep a small enum of supported one-step actions with a fallback. Luna did not establish a compelling latency advantage here, while the existing [phone benchmarks](../bench/RESULTS.md) report Luna failures on general UI control. This short routing test does not retest that quality claim.
2. Warm the thread before interaction. Rotate it as `quick()` already supports; `spare:true` can prewarm replacements, but rollover behavior was not measured in this study. Serialize calls per reflex kind: `quick()` waits for an existing turn, so queue time adds to latency. Do not race or reuse a turn's result for a later request.
3. For a validated `scroll_down`, dispatch `runTool('scroll', {dir:'down'}, ctx)` once; finish from its actual success. Fall back to the regular agent for multi-step requests, ambiguity, dynamic arguments outside the allowlist, or failures. Avoid a second model call merely to say the action finished. This is a proposed integration; no runtime code was changed.
4. Exact typed commands such as “scroll down” can also map directly to that allowlisted call without inference. That is a local design option, not an OpenAI endpoint. Keep the same broker/context path. For paraphrases, the measured warm quick path is already near the required decision budget.
5. Evaluate Decisions later only if API billing is accepted and a key is available. Its fixed-choice format fits this narrow router; it is not a subscription switch and does not replace arbitrary tool calling. No claim that it achieves 2 seconds end to end is verified here.

Only this report is a deliverable. Bench harnesses, generated schemas, and downloaded docs stayed under `/tmp/agentos-fast-decisions`; no application code or phone state was changed. Prettier was not run. The requested code-golf skill was not found in the installed skill locations; no application-code cleanup was needed.
