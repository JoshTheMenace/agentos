# Claude as a brain on the phone (spike, 2026-10-27)

**Verdict: it works on the phone up to the login.** Claude Code runs in Termux and connects to our phone tools. It sees only our tools, and it fits in RAM. The one thing left is a fresh login token from the founder (steps below). After that, `--provider claude` works with no other changes. Speed is not measured yet, because measuring needs the login.

## What already existed
agentd already has a Claude path (`agentd/providers/claude-cli.js`). It runs `claude -p` with stream-json and gives it **no built-in tools**. Its only tools are ours, served by agentd at `/mcp`, so every call goes through `runTool` → guard → broker `:7777`. `server.js` routes `{type:"ask", provider:"claude"}` to it, and `node cli.js --provider claude "…"` works. It had only been tested on the Mac. So no new `providers/claude.js` was needed. (That file name is already taken by the API-key fallback.)

Analogy: Claude Code is a contractor who shows up with an empty toolbox. agentd hands it only the phone tools, and each tool still has to pass the broker's front desk.

## What I tested on the phone (Moto G 5G, Android 13, Termux, Node 25.3)
| Check | Result |
|---|---|
| Claude Code install | 2.1.39 (Feb) was already in Termux. Upgraded to **2.1.112**: `npm i -g @anthropic-ai/claude-code@2.1.112`, 48 MB, `--version` in 2.0 s |
| Lock-down flags (`--tools ""`, `--strict-mcp-config`, `--permission-mode dontAsk`, `--setting-sources ""`, …) | All accepted |
| MCP over HTTP (`mcp-probe.js`, same flags as agentd) | `mcp=[{"name":"agentos","status":"connected"}]`, `tools=["mcp__agentos__phone_echo"]`. Our tool is the only one |
| Cold start (spawn → init message) | ~5 s the first time per connection (Node start ~2 s + MCP connect). Later turns reuse the process |
| RAM | Claude Code: **115 MB idle, 167 MB peak** (init + a failed call; no model traffic yet). Codex app-server: **87 MB** while active. The phone had ~1.1 GB available + swap, so both fit together |
| Auth | The Feb login is dead (`401 OAuth access token is invalid`). `CLAUDE_CODE_OAUTH_TOKEN` is honored: a fake token gives "401 invalid", no token gives "Not logged in" |

### Why version 2.1.112 exactly
From **2.1.113 on**, the npm package ships only native binaries (`bin/claude.exe` for linux glibc/musl). Android uses its own C library (bionic), so those don't run in Termux without a glibc shim. **2.1.112 is the last plain-JS release.** It runs on Termux's Node. The Claude Agent SDK (0.3.x) also spawns that native binary, so it has the same problem, and it would add nothing over the CLI path (the SDK talks the same stream-json). The CLI flags we use all exist in 2.1.112.

Risk: the version is pinned, so we get no updates. That's fine for a demo this week. To stop it from updating itself into the native build, agentd now sets `DISABLE_AUTOUPDATER=1` on the child. Long term, the options are a glibc runner in Termux or running Claude off-phone.

## Change made in agentd (small, Claude provider only)
`agentd/providers/claude-cli.js`:
- If `~/.agentos/claude.token` exists (the output of `claude setup-token`), it is passed as `CLAUDE_CODE_OAUTH_TOKEN`, with a clean `CLAUDE_CONFIG_DIR`. That way the stale Feb login in `~/.claude` (and the root-owned `~/.claude.json`) can't get in the way. With no file, behavior is unchanged (on the Mac, the CLI uses its own login).
- `DISABLE_AUTOUPDATER=1` and `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1` (no surprise updates, less background traffic on a slow phone).

**Not deployed to the phone's running agentd yet.** I didn't restart it because other agents depend on it. Redeploy it the usual way (copy files, `pkill -f "^node server.js"`, and the boot loop restarts it).

## What the founder must do (once, ~2 minutes)
1. **On the Mac**, in any terminal: `claude setup-token`. It opens the browser. Log in with the Claude subscription, and it prints a long token (`sk-ant-oat01-…`, valid ~1 year). Copy it. Don't paste it into a chat.
2. **On the Mac**, with the token still in the clipboard, save it into Termux on the phone:
   ```sh
   pbpaste | adb -s ZY22J3TK8S shell "su 10401 -c 'umask 077; cat > /data/data/com.termux/files/home/.agentos/claude.token'"
   ```
   Check it: `agentd/deploy/tx.sh 'wc -c ~/.agentos/claude.token'` should print about 100+ bytes.

That's all. Then an agent can run the steps below.

## After the token (for the next agent)
1. Probe: `agentd/deploy/tx.sh 'cd ~/agentos/spikes/claude-phone && CLAUDE_CODE_OAUTH_TOKEN=$(cat ~/.agentos/claude.token) CLAUDE_CONFIG_DIR=$HOME/.agentos/claude-cwd/config CLAUDE_CLI_MODEL=claude-sonnet-5-5 node mcp-probe.mjs'` should show a `tools/call` and `echo: hi`.
2. Redeploy agentd (above), then: `node cli.js --provider claude "What app is open?"` through `tx.sh`.
3. Bench: the bench agentd uses `~/.agentos-bench`, so copy the token there too (`cp ~/.agentos/claude.token ~/.agentos-bench/`). `agentd/bench/bench.js` sends no `provider` yet. It needs a one-line change: `provider: arg('provider')` in its `ask` message. Run 2 tasks (e.g. `alarm`) with `CLAUDE_CLI_MODEL=claude-sonnet-5-5`, then `claude-haiku-5-5`, and compare with the Codex numbers in `agentd/bench/results/`.
4. If the cold start hurts: spawn the `claude` process when the WebSocket connects, not on the first ask (saves ~5 s on the first turn).

## Recommendation for the demo
- **Keep Codex as the default brain.** Voice, reviewer and keyboard suggestions are all built on it and tuned.
- **Use Claude as a "swap the brain" moment**, if the token comes in tonight and the bench shows it's within ~1.5x of Codex: run the same real-app task with `provider: "claude"`. The point to make: the OS is model-agnostic, and the security doesn't change, because Claude gets the same broker, the same guard and the same fingerprint card, with no shell and no built-in tools.
- If the token doesn't arrive, or Claude is much slower, leave it out of the live demo and mention it in one line.
- Note: subscription tokens are fine for the founder's own phone and demo. A shipped product would use API keys (`providers/claude.js`, `ANTHROPIC_API_KEY`).

## Files
- `mcp-probe.js`: standalone probe (fake MCP tool + locked-down `claude -p`, prints tools, MCP status, timings, RSS). On the phone it's at `~/agentos/spikes/claude-phone/mcp-probe.mjs`.
