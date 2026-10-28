# Remote MCP: Grok and Dots run tasks on the phone (2026-10-27)

**Short version.** Outside assistants (xAI's Grok connectors and OpenAI Dots / ChatGPT plugins) talk to the phone through one MCP
server, `agentd/mcp-remote.js` (`REMOTE_MCP=1`, off by default). The phone dials **out** to a tiny relay on an always-on server
(`agentd/deploy/relay.js`), and the relay is the public HTTPS address. Remote assistants are **untrusted**. Every task is
labelled "From Grok: …", the phone asks the owner before the agent uses any app, and sends, calls, payments and passwords still
need the owner's tap, voice or fingerprint. A remote assistant can never approve anything.

Think of a mail slot in the front door. A paired assistant can push a request in and read the reply. It never gets a key.
The owner can tape the slot shut (kill switch) or change the lock (revoke).

## 1. What each one accepts (research)

| | Grok (grok.com/connectors, Grok Bot) | OpenAI Dots / ChatGPT plugins |
|---|---|---|
| Transport | Remote only, public HTTPS. Streamable HTTP or SSE. No localhost or private IPs. Stdio servers run on the Bot's cloud computer, never on your device [1][2][3] | "SSE and streaming HTTP" [5]. Or OpenAI's **Secure MCP Tunnel** (outbound `tunnel-client`, no public URL) for ChatGPT, Codex and the API. Dots is not mentioned, and it is not for public plugins [6] |
| Auth | grok.com connectors: **OAuth only** (staff, 09-17) [1]. Grok Bot can also store an API-key header [2]. xAI API remote MCP: an `authorization` bearer field [4] | No auth, OAuth, or mixed. OAuth client by static credentials, **DCR**, or CIMD [5] |
| Tool limits | No per-server cap found. A Bot can use up to 200 skills (0.71.2, 10-14) [1]. The xAI API puts every tool into context unless `allowed_tools` is set [4] | No cap stated. `instructions`: keep the first 512 characters self-contained. Tools without `readOnlyHint` count as writes and need confirmation [5] |
| Timeouts | One call: 1 min before 0.71.2, now **2 min** ("longer while the connector reports progress") [1] | Not stated for plugins. The tunnel has a 120 s deadline, and progress does **not** extend it. 10 MiB messages, 600 requests/min [6] |
| Long tasks | Nothing documented, so we stay under 60 s per call (see below) | **MCP Events** (Dots, Work): `events/subscribe` with an HTTPS webhook, Standard Webhooks HMAC, MCP 2.0 `2026-07-28` [7]. Polling works everywhere |
| Gotchas | If `initialize` answers 200 without a token, clients decide "no sign-in needed" and never run OAuth [8]. Some requests leave out `MCP-Protocol-Version` (Grok Bot, open bug) [1]. OAuth `redirect_uri not allowed` with https-only providers [1] | Custom MCP servers in **Dots**: not confirmed. Dots use "the supported plugins installed and enabled on your account" [9] |

**Where sources disagree.** (a) Grok header auth: Composio says the Bot stores a header [2], but the Grok Bot wiki quotes staff
saying connectors are "only through OAuth" [1]. Both can be true (Bot vs grok.com connectors). We support both. (b) The wiki quotes
"Cursor staff" and a `cursor://` callback, so it may mix up products. It says itself that it tested nothing. (c) Whether Dots can use
a server you added yourself: Stacktree and AdaptlyPost could not confirm it [9]. OpenAI documents custom MCP for ChatGPT plugins
[5] and MCP Events for Dots [7], but not "custom server in a Dot". (d) Most Grok facts come from third-party guides. We found no
official xAI page for grok.com connectors.

**How we report long tasks.** `run_task` returns a `task_id` within milliseconds. `task_result {task_id, wait_s≤50}` then waits
up to 50 s and returns `done:false` if the task is still going, so the assistant calls again. That stays under every timeout
above (60 s, 120 s), needs no streaming, and works the same through the relay. While the task waits for the owner, the reply
includes `owner_action_needed: "The owner must approve on the phone…"`, so Grok tells the user to look at their phone. MCP
Events (a push to Dots when a task finishes) is a later add-on.

## 2. Architecture: the phone dials out to a relay

```
Grok / Dots ──HTTPS──▶ Caddy (TLS) ─▶ relay.js on the VPS ◀══ wss, opened BY the phone ══ agentd mcp-remote.js ─▶ tasks.js ─▶ broker (:7777)
                         public name       dumb pipe: no secrets           (reconnects on every network change)       same tools, same approvals
```

| Option | Verdict |
|---|---|
| **Relay on the founder's VPS, phone connects out over a WebSocket** (chosen) | The phone needs no inbound port, so CGNAT, mobile data and Wi-Fi changes don't matter: the phone just redials. The URL never changes. Only Node and `ws`, no new app on the phone, and no account beyond the VPS. The relay is a dumb pipe: OAuth, the pairing code, tokens, rate limits and approvals are all checked **on the phone** |
| Cloudflare named tunnel (`cloudflared` in Termux) | Good **plan B**: also outbound-only, with a stable URL, but it needs a Cloudflare account **and a domain on Cloudflare**. Quick tunnels (`trycloudflare.com`) need no account, but the URL changes on every restart, which breaks the Grok connector. Demos and tests only |
| Tailscale Funnel from the phone | Funnel is set up with the `tailscale` CLI. The Android app takes the phone's only VPN slot, and we found no Funnel switch in it. Another agent on the phone, more moving parts |
| VPS + Tailscale to the phone | Works, but needs the Tailscale app on the phone (VPN slot, battery, one more thing to break). The WebSocket does the same job with less |
| OpenAI Secure MCP Tunnel | ChatGPT/Codex/API only (not Grok), needs a Platform org with tunnel permissions, and it's not for public plugins [6]. Skip |

**Why it stays up.** agentd already runs under the Magisk watchdog (restarted within 5 s, outside lmkd's reach, wake lock held).
The relay pings every 15 s and drops a dead line within 30 s. The phone redials with backoff of 1 → 8 s and treats 45 s of
silence as a dead line. One line at a time: a new connection from the phone replaces the old one. If the phone is offline, callers
get `503` + `Retry-After`. The phone's state (clients, token hashes) is in `~/.agentos/remote.json`, so restarts and reboots keep
everyone paired.

## 3. What was built

| File | What |
|---|---|
| `agentd/mcp-remote.js` | MCP over Streamable HTTP (JSON replies, `GET` → 405, protocol 2025-03-26/06-18/11-25) + a small OAuth 2.1 server: RFC 9728 resource metadata, RFC 8414 server metadata, RFC 7591 DCR, `/authorize` (pairing-code page), `/token` (PKCE S256 required, refresh rotation with a 2 min grace period). Static `aos_…` keys for header-only clients. Owner CLI. Two doors: `127.0.0.1:8790` and the relay line |
| `agentd/deploy/relay.js` | The relay for the VPS (about 60 lines): passes HTTP requests through the phone's WebSocket, `/relay-health` |
| `agentd/remote-test.js` | 24-check end-to-end test (discovery → DCR → wrong/right/reused code → bad/good PKCE → MCP calls → task → refresh → static key → kill switch → revoke) |
| `tasks.js` | `start(goal, {remote:"Grok"})`: goal becomes "From Grok: …", turn `untrusted`, its own brief (no money, passwords, installs or settings; stop if denied) |
| `reviewer.js` | Never auto-approves a remote task's card ("request from Grok, not the owner") |
| `server.js` | `REMOTE_MCP=1` starts it. `{type:"remote", action}` on the WebSocket: `status` for anyone; `pair`, `key`, `enable`, `reads`, `unpair`, `revoke` for the **owner only** (launcher uid, same check as memory consent) |
| `deploy/termux-boot.sh` | Reads optional `~/.agentos/agentd.env` (where `REMOTE_MCP=1` goes) |

**Tools** (small on purpose): `run_task {request}`, `task_result {task_id, wait_s}`, `task_status {task_id?}`, plus two reads the
owner can switch off (`node mcp-remote.js reads off`): `phone_brief` (latest threads and notifications, the same guarded block
the agent sees: codes masked, injections withheld) and `memory_lookup {query}` (FTS, phone numbers and emails as `[P:n]`).
There's no direct calendar read, because the broker needs a lease for calendar API calls. Ask through `run_task` (one lease tap).

## 4. Security model

- **Remote assistants are untrusted principals.** A remote task goes to the broker with `user_asked:false, untrusted:true`, so:
  - the lease card always shows ("Allow agent in Clock — From Grok: …");
  - the Rule of Two `untrusted` lamp is lit;
  - the reviewer may not auto-approve;
  - the broker won't unlock a locked phone for it;
  - `schedule_task` refuses it (a schedule could later unlock the phone).

  Outward actions, money and passwords work exactly as today: the owner's tap, voice or fingerprint.
- **Nothing remote can approve.** The MCP server has no approval tool, and the broker only takes approvals from the card, the
  owner's voice and the reviewer. A remote caller can't reach any of them.
- **Pairing is owner-only and on the phone.** The phone makes an 8-character code from a 31-character alphabet (about 40 bits). It
  lasts 10 min, allows 5 tries and works once. The owner types it into Grok's or ChatGPT's sign-in page. A static key (`node mcp-remote.js key grok`) is shown once and
  stored as a SHA-256 hash. Tokens are also stored only as hashes (mode 600). Access tokens last 8 h, refresh tokens 90 days.
- **Revocable and scoped.** `unpair <grant>` drops one assistant. `revoke` drops everyone, and the next call returns 401. `off` is
  the kill switch: every call is refused, and running remote tasks are stopped within about 2 s. The island stop button works as
  always. Each assistant sees only the tasks it started.
- **Rate limits:** 60 MCP calls/min per assistant, 10 `run_task`/hour, 2 open tasks at once, 30 registrations/hour. A full client
  list evicts only unpaired clients.
- **Audit.** The broker's chain logs every remote task with its "From Grok: …" goal (seen on the phone:
  `task.start · From Grok: Open the Clock app… · denied · timed out`), so Activity shows it. Direct reads (`phone_brief`,
  `memory_lookup`) go only to agentd's log and a `remote.read` event for now (follow-up: a broker audit line).
- **The relay** sees requests in plain text (TLS ends at Caddy). A hacked VPS gets what a paired assistant gets: untrusted
  tasks that the owner must approve. It can't approve, and it can't read the pairing code's hash. Pin `REMOTE_PUBLIC_URL` so a
  forged `Host` header can't change the issuer.
- **Prompt injection through Grok.** Grok may have read a malicious web page. That's why its words are treated like screen text:
  they're data, and a person approves the outward step.

## 5. Tests (2026-10-27)

| Test | Result |
|---|---|
| `remote-test.js`, Mac, direct port, mock phone | **20/20** |
| `remote-test.js` through `relay.js`, real Codex task against the mock phone | **24/24**. "Read my screen…": answer in 7 s, the planted injection was withheld |
| Mock broker received | `task.start {goal:"From Grok: …", untrusted:true, user_asked:false, origin:"remote"}` |
| **Official MCP TypeScript SDK 1.32.2** client with its own OAuth (401 → discovery → DCR → PKCE → token) through the relay | Connected (protocol 2025-11-25), 5 tools, task done in 7.4 s |
| **Real phone, public HTTPS** (Cloudflare quick tunnel → relay on the Mac → the phone dialing out over its own Wi-Fi; separate test agentd on :8796, live one untouched) | SDK OAuth with a code made **on the phone**: OK. `phone_brief`: OK. "Which app is open?": "AgentOS Home is open." in **7.1 s** |
| Real phone, task needing an app | "Open the Clock app…": `waiting · Waiting for your OK to use the app · owner must approve`. Nobody tapped, so it was **denied after 60 s**, and the reply was "The phone's approval timed out, so I couldn't check the next alarm." |
| Network change (phone has no cell service: Wi-Fi off → on) | Relay saw it offline in 23 s, and callers got `503`. The phone reconnected **7 s after Wi-Fi came back** (28 s before the backoff cap was lowered to 8 s) |

Not tested: Grok and ChatGPT themselves (they need the founder's accounts), and a switch from Wi-Fi to cell data (no SIM service).
The test agentd, its state (it had synced a memory copy) and the relay key were deleted from the phone afterwards.

## 6. Founder setup (about 15 min)

**A. Relay on the VPS** (any Linux box with a public IP; Node 22+):
```sh
sudo mkdir -p /opt/agentos-relay && cd /opt/agentos-relay && sudo npm i ws    # then copy agentd/deploy/relay.js here
openssl rand -hex 32 | sudo tee /etc/agentos-relay.key >/dev/null && sudo chmod 600 /etc/agentos-relay.key
printf 'RELAY_KEY=%s\n' "$(sudo cat /etc/agentos-relay.key)" | sudo tee /etc/agentos-relay.env >/dev/null && sudo chmod 600 /etc/agentos-relay.env
sudo tee /etc/systemd/system/agentos-relay.service >/dev/null <<'EOF'
[Unit]
After=network-online.target
[Service]
EnvironmentFile=/etc/agentos-relay.env
ExecStart=/usr/bin/node /opt/agentos-relay/relay.js
Restart=always
DynamicUser=yes
[Install]
WantedBy=multi-user.target
EOF
sudo systemctl enable --now agentos-relay
```
TLS with Caddy (`apt install caddy`). Without your own domain, use `<ip with dashes>.sslip.io`, e.g. `203-0-113-7.sslip.io`:
```
# /etc/caddy/Caddyfile
203-0-113-7.sslip.io {
    reverse_proxy 127.0.0.1:8080
}
```
`sudo systemctl reload caddy`. Then check `https://<host>/relay-health` → "phone offline" (the phone isn't connected yet).

**B. Phone** (from the Mac; use `scripts/withphone`). Copy the relay key without printing it, then turn it on:
```sh
ssh vps 'sudo cat /etc/agentos-relay.key' | adb shell "su 10401 -c 'umask 077; cat > /data/data/com.termux/files/home/.agentos/relay.key'"
agentd/deploy/tx.sh 'printf "REMOTE_MCP=1\nREMOTE_RELAY=wss://203-0-113-7.sslip.io/agentos-link\nREMOTE_PUBLIC_URL=https://203-0-113-7.sslip.io\n" > ~/.agentos/agentd.env'
```
Copy `mcp-remote.js` and the changed agentd files (`tasks.js`, `server.js`, `reviewer.js`, `events.js`, `tools.js`,
`deploy/termux-boot.sh`) into `~/agentos/agentd/` as usual, then restart the boot loop **once** so it reads the env file:
`adb shell su -c 'kill $(ps -A -o PID,ARGS | grep -E "termux-boot.sh|node server.js" | grep -v grep | cut -c1-6)'` (explicit PIDs: `pkill -f` inside `su -c` matches and kills its own shell; the Magisk watchdog starts it again within 3 s). The log should
show `remote: relay connected <host>`, and `/relay-health` should say "phone connected".

**C. Pair Grok.**
1. On the Mac, make a code: `agentd/deploy/tx.sh 'cd ~/agentos/agentd && node mcp-remote.js pair'`. It lasts 10 minutes. Later the Command center will have a Remote tile for this.
2. In Grok, go to grok.com/connectors → New Connector → Custom. Use `https://<host>/mcp`, then Connect.
3. Our sign-in page opens. Type the code, pick "Grok", then Connect.
4. In Grok, ask "use AgentOS to …".
5. For **Grok Bot**, if it asks for a header instead, make a key with `node mcp-remote.js key grok` and tell the bot "header `Authorization`, value `Bearer aos_…`".

**D. Pair Dots / ChatGPT.**
1. In ChatGPT, open Settings → Plugins (Developer mode), then Add custom MCP server.
2. Use the URL `https://<host>/mcp` and auth **OAuth**. Same pairing page; pick "Dots".
3. If your plan offers Dots, enable the plugin for the Dot. Whether Dots accept a custom server is unconfirmed (section 1), so test it once.

**Owner controls**, run on the phone through `tx.sh … node mcp-remote.js <cmd>`:

| Command | What it does |
|---|---|
| `status` | List what's paired |
| `off` / `on` | Kill switch |
| `reads off` | Turn off `phone_brief` and `memory_lookup` |
| `unpair <grant>` | Drop one assistant |
| `revoke` | Drop all of them |

The same actions are on the launcher WebSocket as `{type:"remote", action}` (owner only). The Command center tile still needs to
be built (UI work goes to Fable).

**Plan B, no VPS.**
- Cloudflare named tunnel on the phone: install `cloudflared` (arm64) in Termux, then run `cloudflared tunnel run --url http://127.0.0.1:8790 <name>`, from the boot script too.
- Set `REMOTE_MCP=1` and `REMOTE_PUBLIC_URL=https://<name>.<your-domain>`, and leave out `REMOTE_RELAY`.
- For a quick demo only: `cloudflared tunnel --url http://127.0.0.1:8790` gives a random `trycloudflare.com` URL that dies on restart.

## 7. 30-second demo

1. (Before going on stage: paired, phone unlocked, on the home screen, Grok open on the laptop.)
2. Say: "My phone isn't just reachable from its own assistant. Here's Grok, a different company's AI."
3. Type into Grok: **"Use AgentOS: what's my next alarm?"**
4. Grok calls `run_task`. The phone shows the island and the card **"Allow agent in Clock — From Grok: …"**.
5. Say: "Grok can ask, but it can't approve. Only I can." Tap Allow.
6. About 10 s later, Grok answers with the alarm time. Show the **Activity** line, "Started · From Grok: what's my next alarm?".
7. Optional closer: "Text Sam I'm on my way." → the Send card on the phone → tap. Or don't tap, and Grok says it wasn't approved.

## 8. Follow-ups

- **Command center › Remote tile (Fable).** Pair button (shows the code big, once), paired assistants with Unpair, on/off switch,
  reads switch, relay status. Use the `remote` WebSocket messages above.
- **A broker audit line for direct reads.** Today only agentd logs `phone_brief` and `memory_lookup`.
- **MCP Events for Dots** (push "task done" instead of polling). This needs webhook delivery, HMAC and persistent subscriptions [7].
- Test against real Grok and ChatGPT once the founder has paired them, and a Wi-Fi → cellular switch with a SIM.

## Sources
1. Grok Bot wiki, plugins/connectors/MCP and known issues (community, untested): https://www.grokbotwiki.com/guides/grok-bot-plugins-mcp
2. Composio, "How to Add MCP Servers to Grok Bot" (2026-09-03): https://composio.dev/content/how-to-add-mcp-servers-to-grok-bot
3. MCP Nest, connect any MCP server to Grok: https://mcpnest.dev/guides/add-mcpnest-to-grok
4. xAI docs, Remote MCP Tools: https://docs.x.ai/developers/tools/remote-mcp
5. OpenAI, Add custom MCP server: https://developers.openai.com/api/docs/guides/custom-mcp-server
6. OpenAI, Secure MCP Tunnel: https://developers.openai.com/api/docs/guides/secure-mcp-tunnels
7. OpenAI, MCP Events: https://developers.openai.com/plugins/build/mcp-events
8. imogen-server issue #27 (unauthenticated `initialize` made Grok skip OAuth): https://github.com/ergofobe/imogen-server/issues/27
9. Stacktree, "What are ChatGPT Dots?": https://stacktr.ee/blog/what-are-chatgpt-dots · AdaptlyPost on Dots plugins: https://adaptlypost.com/blog/openai-dots-plugin-social-media-posting
