# agentd

The waiter from `ARCHITECTURE.md`. UIs talk to it over a WebSocket. It passes requests to a model (Codex, Claude or a local model) and carries the model's tool calls to the Android tool server. agentd never performs a phone action itself, and it can never approve one.

```
UI ──ws://127.0.0.1:8787 (Bearer)──▶ server.js ──▶ providers/{codex,claude-cli,claude,local}.js
                                         │                 │ every tool call
                                         └──── tools.js ◀──┘ guard.js on every screen ──▶ POST :7777/rpc (Bearer)
```

Node 22+, ESM, one runtime dependency (`ws`). `@anthropic-ai/sdk` is optional and only used by the API-key fallback.

## Files
| File | What it does |
|---|---|
| `server.js` | WebSocket server, token check, one conversation per connection, provider router, `/health`, and `/mcp` (phone tools for Claude Code) |
| `tools.js` | The 10 phone tools (specs plus tier `read`/`act`/`outward`), the call to `:7777/rpc`, guard wrapping, and one `task.start` lease per turn |
| `guard.js` | `classify(text) -> {score, label}`. A regex placeholder; swap in Prompt Guard 2 behind the same function (it may be async) |
| `prompt.js` | The system prompt shared by all providers (phone agent, screen text is untrusted data, ask before outward actions) |
| `providers/codex.js` | `codex app-server` over stdio, with `dynamicTools`, streamed deltas, and every Codex approval declined |
| `providers/claude-cli.js` | `claude -p` (stream-json) with the user's subscription. Its only tools are ours, over MCP |
| `providers/claude.js` | Fallback when the `claude` CLI is missing: Messages API with `ANTHROPIC_API_KEY` |
| `providers/local.js` | Stub for an OpenAI-compatible `llama-server --jinja` |
| `mock-phone.js` | Fake `:7777` tool server: a small chat screen with one planted prompt injection |
| `cli.js` | Tiny client: `node cli.js [--provider codex\|claude\|local] "text"` |
| `deploy/` | `tx.sh` (run in Termux from the Mac), `codex-root.sh` (root Codex fallback), `termux-boot.sh` (Termux:Boot) |

## WebSocket protocol (from ARCHITECTURE.md)
Auth uses `Authorization: Bearer <token>`. A WebView can't set that header on a WebSocket, so it may instead send the subprotocol `agentos.bearer.<token>`. The token is in `~/.agentos/agentd.token` (mode 600, created on first start) or in `AGENTD_TOKEN`.

- client → agentd: `{type:"ask", text, provider?}` · `{type:"stop"}` · `{type:"suggest", context}` (returns empty `suggestions` for now) · `voice.*` (returns `unsupported`)
- agentd → client: `text.delta {text}` · `tool.request {name, args, tier}` · `tool.result {name, ok, decision, reason, ms}` · `approval.needed {summary, rule_of_two}` · `guard.alert {source_pkg, score, label, node, excerpt}` · `turn.done {provider, status, usage, ms}` · `error {code?, message, retryable}`

`approval.needed` is sent before an `outward` tool (`firewall_set`) and whenever the app answers `decision:"approval_pending"`. It is display-only: the fingerprint card on the phone is the real gate.

## Run on the Mac (no phone needed)
```sh
cd agentd && npm install
node mock-phone.js &            # :7777, writes ~/.agentos/phone.token
node server.js &                # :8787 (AGENTD_PORT=8788 if 8787 is taken)
node cli.js "Read my screen, tell me what Mom asked, then tap the message box"
node cli.js --provider claude "same thing"
```
To drive the **real phone** from the Mac, forward the port (`adb forward tcp:7777 tcp:7777`), then start agentd with `PHONE_TOKEN=...` or `PHONE_TOKEN_FILE=...`.

## Run on the phone (Termux, non-root)
Code lives in `~/agentos/agentd`. Copy it over with `tar` + `adb push`, then use `deploy/tx.sh`.
- **Preferred:** Termux's own Codex (`pkg install codex`, 0.164.0 is installed). It runs as the Termux user and needs no DNS proxy. **It still needs a one-time login by the user, inside Termux:** `codex login --device-auth`, then open the URL on any device and type the code. (Device-code login must first be enabled in ChatGPT → Settings → Security.)
- **Fallback (works today):** `CODEX_BIN=~/agentos/agentd/deploy/codex-root.sh` runs the signed-in root lab Codex 0.163 through Magisk `su` (uid 10401 is allowed). This needs `android-connect-proxy 18080`.
- **Boot:** `cp deploy/termux-boot.sh ~/.termux/boot/agentos-agentd`. It picks Termux Codex if it is logged in, otherwise the root fallback (and starts the proxy). It holds a wake lock and restarts agentd if it dies.
- The Android app's token is read once with `su -c cat /data/data/local.agentos/files/token`.

## Environment variables
`AGENTD_HOST/PORT/TOKEN/TOKEN_FILE`, `AGENTD_PROVIDER` (default `codex`), `PHONE_URL` (default `http://127.0.0.1:7777/rpc`), `PHONE_TOKEN`/`PHONE_TOKEN_FILE`, `CODEX_BIN`, `CODEX_MODEL`, `CODEX_EFFORT` (default `low`), `CLAUDE_BIN` (set to `none` to force the API path), `CLAUDE_CLI_MODEL`, `ANTHROPIC_API_KEY`, `CLAUDE_MODEL`, `CLAUDE_EFFORT`, `LOCAL_URL`, `AGENTD_DEBUG=1` (shows provider stderr), `AGENTOS_HOME` (default `~/.agentos`).

## How each model is boxed in
- **Codex:** started with `-c features.X=false` for shell_tool, unified_exec, apps, plugins, browser/computer use, image gen, multi_agent, view_image, hooks, memories, goals, skill_search and sleep_tool, plus `web_search="disabled"`, `sandbox_mode="read-only"` and `approval_policy="never"`. `baseInstructions` replaces Codex's coding prompt with ours. Approval requests get `decline`/`abort`/empty grants. Unknown server requests get a JSON-RPC error.
  - **Gotcha:** `shell_tool=false` alone does *not* remove everything. Even with all the flags above, the model still sees `exec`/`wait` (code mode, which can only call the tools listed here), `apply_patch` (rejected by the read-only sandbox, as tested), MCP resource readers (from your Codex config's MCP servers), `collaboration.*` (sub-agents, which do not get our dynamic tools) and `request_user_input` (agentd answers it with nothing). None of these reach a shell.
- **Claude Code:** `--tools ""` (no built-ins), `--strict-mcp-config` with only our `/mcp` server (per-session secret), `--allowedTools mcp__agentos__*`, `--permission-mode dontAsk` (anything else is denied), `--setting-sources ""`, `--disable-slash-commands`, `--no-session-persistence`. Tested: the model lists exactly our 10 tools.
