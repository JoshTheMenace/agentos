# 01 — Inference runtime on the phone (Codex / Claude / T3 / local models)

Researched 2026-10-26. Target: Moto G 5G (2023) XT2313, Snapdragon 480+ (2x Cortex-A76 @2.2 GHz + 6x A55 @1.8 GHz), Adreno 619, **4 GB LPDDR4X** (~3.5 GB usable), Android 13, Magisk. I did not touch the phone. Every number marked *est.* is an estimate to confirm on the device.

## TL;DR

1. **Codex works on the phone, and Termux now ships it officially.** `pkg install codex` installs a native Android build of Codex **0.164.0**, the same version as upstream's latest release on 2026-10-23. Because it uses Android's own libc, it avoids the DNS and file-lock bugs that break the upstream Linux binary on Android. Run `codex app-server` over **stdio**, log in with `account/login/start {type:"chatgptDeviceCode"}`, and expose phone actions as **`dynamicTools`**. Turn Codex's own shell tool off.
2. **Put one small bridge in front of every model ("agentd").** It runs Node in Termux and listens on a WebSocket at `127.0.0.1` with a bearer token. It hosts the provider adapters (Codex, Claude, local) **and the permission gate**. The launcher only talks to agentd. The same code runs on the Mac, so the fallback is just a URL change (`adb reverse tcp:7777 tcp:7777`, or Tailscale).
3. **Claude on the phone: use an API key, not the subscription.** Anthropic publishes no Android build (issue #50270 is still open). The community workarounds patch the binary or need a ~2 GB Ubuntu proot. Anthropic's terms also forbid third-party apps from offering Claude.ai login or routing requests through Pro/Max credentials. Use the Messages API with a Console key. If you need the subscription for the demo, run the **unmodified** `claude` on the Mac.
4. **Local models: small and narrow.** Run `llama-server` (Termux `pkg install llama-cpp`) with **Qwen3.5-0.8B Q4_0 (507 MB)**. Expect about 8–15 tok/s *est.* Use it for routing, redaction, keyboard rewrites and offline mode, not for driving the phone. Gemma 4 E2B (2.8 GB GGUF, about 1.4–1.7 GB RAM) is too heavy next to everything else on 4 GB.
5. **The terms differ by provider.** OpenAI officially supports ChatGPT-plan usage through Codex app-server for open-source and locally hosted apps. Paid or remotely hosted apps must go through an interest form. Anthropic does not allow subscription login in third-party apps; those must use API keys.

---

## 1. Codex app-server on Android

### What app-server is (simply)
Think of app-server as Codex's "engine without the dashboard". The terminal UI and the VS Code extension are both clients of it. You send it newline-delimited JSON (JSON-RPC 2.0 without the `"jsonrpc"` field) on stdin, and it streams events back on stdout. Docs: https://learn.chatgpt.com/docs/app-server (moved from developers.openai.com/codex/app-server). Source: https://github.com/openai/codex/tree/main/codex-rs/app-server

Key protocol facts (from the docs above):
- **Transports:** `--listen stdio://` is the default and the supported one. `--listen ws://IP:PORT` is "experimental and unsupported"; on non-loopback addresses it is unauthenticated by default unless you pass `--ws-auth capability-token --ws-token-file /abs/path` and the client sends `Authorization: Bearer`. `--listen unix://PATH` also exists.
- **Handshake:** first `initialize` with `clientInfo {name,title,version}` (add `capabilities.experimentalApi=true` if you use dynamic tools), then the `initialized` notification. Any request sent earlier gets "Not initialized".
- **Login:**
  - `account/read`, then `account/login/start` with one of `apiKey | chatgpt | chatgptDeviceCode | chatgptAuthTokens` (the last is experimental).
  - Device-code login returns `{verificationUrl:"https://auth.openai.com/codex/device", userCode}`; the server later sends the `account/login/completed` and `account/updated` notifications.
  - Device-code login is **beta and must be enabled in ChatGPT → Security settings** first (https://learn.chatgpt.com/docs/auth).
- **Conversation:**
  - `thread/start {model, cwd, approvalPolicy, sandbox, dynamicTools?}` starts a thread; `turn/start {threadId, input:[{type:"text",text}]}` starts a turn.
  - The server then streams `item/started`, `item/agentMessage/delta`, `item/completed` and finally `turn/completed`. Check that `turn.status=="completed"`.
- **Approvals:** server→client requests `item/commandExecution/requestApproval`, `item/fileChange/requestApproval`, `item/permissions/requestApproval`.
- **Our tools:** with `dynamicTools` (experimental) on `thread/start`, the server sends an `item/tool/call` request to us; we run the tool and reply. This is how "tap", "type", "open app" and similar get to the model **while our own code keeps control**.
- **Sandbox:** `sandboxPolicy.type` is one of `readOnly | workspaceWrite | dangerFullAccess | externalSandbox`. `externalSandbox` means "I already sandbox you, skip yours".
- **Exact types:** run `codex app-server generate-ts --out ./schemas` (or `generate-json-schema`) instead of guessing the payload shapes (for example the `item/tool/call` response's `contentItems`).

### Three ways to get the binary onto the phone (best first)

| Option | What it is | Status / gotchas |
|---|---|---|
| **A. `pkg install codex` (Termux)** | Native **aarch64-linux-android** (Bionic) build, packaged by Termux, v0.164.0 (bumped 2026-10-24). Depends on `git, libc++, openssl`. https://github.com/termux/termux-packages/blob/master/packages/codex/build.sh | **Recommended.** Bionic's DNS goes through Android's resolver, which fixes the DNS failure. The build uses Rust ≥1.98, whose std supports Android file locks, which fixes the `lock() not supported` failure (#26277). It also carries the patches `android-daemon-auto-start.patch` and `android-mxc-sdk-unsupported.patch`. Needs **Termux from F-Droid/GitHub**, not the Play Store build. |
| B. Upstream static musl binary | Release assets `codex-aarch64-unknown-linux-musl.tar.gz` (106 MB) and `codex-app-server-aarch64-unknown-linux-musl.tar.gz` (83 MB). https://github.com/openai/codex/releases/tag/rust-v0.164.0 | Runs (`--version` and `app-server --stdio` work, per #49402), but **DNS breaks**: musl reads `/etc/resolv.conf`, which Android doesn't have, so it falls back to `127.0.0.1:53` (#11809). Fixes: add `system/etc/resolv.conf` with a Magisk module, or run it inside `proot -b $PREFIX/etc/resolv.conf:/etc/resolv.conf`. Also set `SSL_CERT_FILE=$PREFIX/etc/tls/cert.pem` (Codex honours `SSL_CERT_FILE`/`SSL_CERT_DIR`). The interactive TUI dies with "Bad system call" on Android (#55986); app-server over stdio is a different code path, but that's still a risk. |
| C. proot Debian/Ubuntu | Linux userland inside Termux | Login, git and sockets work (#47402), but the Codex sandbox (bwrap) fails under proot. It's heavier on RAM and disk. Use it only if A and B both fail. |

Known landmines (all from github.com/openai/codex issues):
- **#49402:** `--listen unix://` needs `/tmp`, which Android doesn't have. Closed, but **use stdio** anyway.
- **#26277 and #11809:** file locks and DNS on Android targets. Option A should fix both; **verify on the device**.
- **#55986:** "Bad system call" (seccomp/sandbox syscalls). Avoid Codex's Linux sandbox entirely: use `sandbox:"readOnly"` (or `externalSandbox`) **and disable the shell tool** with `-c features.shell_tool=false`. The `shell_tool` flag exists in `codex-rs/features/src/lib.rs`, stable and on by default; confirm the `-c` override works on 0.164.

### Smoke test (about 30 min, run inside Termux)
```sh
pkg update && pkg install -y codex nodejs   # nodejs = 26.x in Termux
codex --version                             # expect 0.164.0
codex login --device-auth                   # or do it via JSON-RPC below
printf '%s\n' \
 '{"id":1,"method":"initialize","params":{"clientInfo":{"name":"agentos","title":"AgentOS","version":"0.1.0"},"capabilities":{"experimentalApi":true}}}' \
 '{"method":"initialized","params":{}}' \
 '{"id":2,"method":"account/read","params":{}}' \
 | codex app-server -c features.shell_tool=false
```
Then `thread/start` and `turn/start` with "say hi", and check that `item/agentMessage/delta` events arrive.

### Terms (OpenAI), factually
- Logging Codex itself in with your ChatGPT plan is the normal, documented Codex use.
- OpenAI also documents **"ChatGPT plan usage" for third-party open-source and locally hosted apps**, including a recipe that runs `codex app-server` with a user's OAuth token: https://developers.openai.com/siwc/token-sharing-open-source/codex-app-server. Details:
  - The app sends `clientInfo.name` for attribution, and it is a **preview**.
  - "If you're interested in offering it in a paid or remotely hosted app, complete the interest form" (https://developers.openai.com/siwc/token-sharing-open-source).
  - Usage limits apply. Watch for the errors `subscription_sharing_usage_limit_exceeded` and `subscription_sharing_usage_unavailable`.
- Pitch line: "BYO ChatGPT plan, on-device, open-source" fits the documented program. A hosted or paid AgentOS would need OpenAI's approval.

## 2. Claude Code / Claude Agent SDK on Termux

- **No official Android build.** `@anthropic-ai/claude-code@2.1.311` and `@anthropic-ai/claude-agent-sdk@0.3.311` ship native binaries for linux/darwin/win32 (x64 and arm64, plus musl variants), **not android**. Upstream issue https://github.com/anthropics/claude-code/issues/50270 ("v2.1.113+ broken on Termux/Android: native binary requires glibc") is still **open** (updated 2026-10-25).
- Community workarounds (https://github.com/ferrumclaudepilgrim/claude-code-android):
  - **Path A** patches the ELF interpreter of the linux-arm64 binary to run under Termux's `glibc-runner`. It also forces Google DNS for Claude's own lookups. Each binary is a few hundred MB.
  - **Path B** runs the official installer inside proot Ubuntu (~2 GB).
  - The project lists Pixel and Galaxy phones as tested; the older Moto G7 needed a pinned old version. **Our Moto is untested.**
- The Agent SDK can point at an external binary with `pathToClaudeCodeExecutable` (https://code.claude.com/docs/en/agent-sdk/typescript.md). It still needs a working `claude` on the device.
- **Terms, quoted from https://code.claude.com/docs/en/legal-and-compliance:**
  - "Developers building products or services that interact with Claude's capabilities, including those using the Agent SDK, should use API key authentication… Anthropic does not permit third-party developers to offer Claude.ai login into their own applications, or to route requests through Free, Pro, or Max plan credentials on behalf of their users."
  - Carve-out: an end user may sign in to **the unmodified Claude Code binary** with their own subscription.
  - Also: "The Claude Code binary must not be modified" (that clause is for products embedding Claude Code). Path A's patching sits awkwardly next to it.
  - The Agent SDK overview says the same: https://code.claude.com/docs/en/agent-sdk/overview
- **Practical answer:** the Claude adapter in agentd calls the **Messages API with a Console API key** (pure JS `@anthropic-ai/sdk`, which runs fine on Termux Node, about 0 extra RAM). For a "my own subscription" demo, run unmodified `claude` / Agent SDK **on the Mac** and route to it. Don't present Claude-subscription-inside-AgentOS as a product feature.

## 3. T3 Code server on Termux

- `t3` (https://github.com/pingdotgg/t3code, v0.1.2, 2026-10-22) is a Node server (`engines: node ^22.16 || >=24.10`). It has native addons (`node-pty`, `@napi-rs/keyring`, `@ff-labs/fff-node`) and spawns the provider CLIs. The `install.sh` script only knows `darwin|linux` with glibc-style archives, so on Termux you would need `npm i -g t3` and native addon builds. **No Termux reports exist in its tracker.**
- **Verdict: don't run it on the phone.** It adds a second agent orchestrator on top of ours and uses RAM we don't have.
- It is a strong **fallback**: run `t3 serve` on the Mac, and T3 already has an official Android app with LAN, Tailscale and T3 Connect pairing (docs/user/remote-access.md). It does not integrate with our launcher, though.

## 4. Memory on ~3.5 GB

Android 13 with GMS and SystemUI typically leaves about 1.2–1.8 GB *est.* for our stuff. Budget:

| Process | RSS (est. — measure with `ps -o rss,cmd`) |
|---|---|
| Launcher + island overlay (Kotlin) | 120–200 MB |
| agentd (Node 26) | 50–90 MB |
| codex app-server (Rust + embedded V8) | 80–200 MB *(unknown, measure)* |
| llama-server + Qwen3.5-0.8B Q4_0, 4k ctx | ~600–750 MB |
| claude native binary (if ever on the phone) | 250–450 MB, which is why we skip it |
| Gemma 4 E2B (LiteRT-LM CPU, per model card) | 1.36–1.73 GB, which is why we skip it |

Fits: launcher + agentd + Codex + 0.8B local model ≈ 0.9–1.2 GB. Load the local model **on demand**, and unload it (kill llama-server) when Codex is busy, if LMK gets aggressive.

Keep-alive gotchas:
- Android 12+ has a **phantom process killer**. Run `settings put global settings_enable_monitor_phantom_procs false` (via root) or use Developer options → "Disable child process restrictions". See https://github.com/termux/termux-app/issues/2366.
- Run `termux-wake-lock`.
- Exempt Termux from battery optimization.
- Optional root step: `echo -800 > /proc/<pid>/oom_score_adj` for agentd and Codex.

## 5. How the launcher talks to the runtime

Analogy: the launcher is the dashboard, agentd is the mechanic, and Codex, Claude and llama are engines. The dashboard never touches an engine directly.

```
Launcher/Island (Kotlin, OkHttp WS)
   │  ws://127.0.0.1:7777  + Authorization: Bearer <256-bit token>
   ▼
agentd (Node, Termux UID)  ── policy gate + audit log + router
   ├─ codex app-server  (child, stdio JSONL)       ChatGPT plan
   ├─ Anthropic Messages API (HTTPS, API key)      Claude
   └─ llama-server 127.0.0.1:8080 (OpenAI-compatible, --jinja tools)  local
Phone actions: agentd → launcher's AccessibilityService (over the same WS, as "tool requests") → result
```

- **Start and pairing:**
  - The launcher generates the token, keeps it in Android Keystore, and starts agentd through **Termux's `RUN_COMMAND` intent**. That needs `allow-external-apps=true` in `~/.termux/termux.properties` and the launcher must declare `com.termux.permission.RUN_COMMAND` (https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent).
  - The token is passed as an env var, so nothing goes on disk in shared storage.
- **Why a header token:**
  - Every app on the phone, and every web page in Chrome, can reach `127.0.0.1`.
  - Browsers cannot set an `Authorization` header on a WebSocket, so header auth blocks drive-by web pages, and the token blocks other apps.
  - Also reject any request that has an `Origin` header.
  - Hardening (stretch): use root to map the TCP peer to a UID through `/proc/net/tcp6` and allow only the launcher's UID.
- **Keep Codex on stdio.** Never expose app-server's experimental WS listener. agentd is the only thing that holds the Codex pipe.
- **Event schema**, the same for all providers:
  - `text.delta`, `tool.request {id, name, args, risk}`, `tool.result`, `approval.needed`, `turn.done {usage}`, `error {code, retryable}`.
- **Stretch (no Termux):** ship the static musl `codex-app-server` inside the APK as `jniLibs/arm64-v8a/libcodexappserver.so` (with `useLegacyPackaging=true`) and spawn it from `nativeLibraryDir`. That is the only place Android 10+ lets an app exec its own files. You'd need the Magisk `resolv.conf` fix and `SSL_CERT_FILE`. **Untested.**

## 6. Local models on this phone class

- **Runtimes:**
  - **llama.cpp**: Termux package `llama-cpp` tracks upstream `v0.7.0` (2026-10-15) and is built with Vulkan and OpenCL. On an Adreno 619, CPU is probably faster than GPU (*unverified*). Use `llama-server` for an OpenAI-compatible endpoint with tool calls (`--jinja`).
  - **LiteRT-LM** (https://github.com/google-ai-edge/LiteRT-LM, v0.18.0): the in-app Kotlin option.
  - **MediaPipe LLM Inference** is "in maintenance-only mode" and Google points to LiteRT-LM (https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android). It's also "optimized for high-end devices (Pixel 8 / S23+)".
  - MLC LLM: not researched in depth; it has no advantage here over llama.cpp on the CPU.
- **Speed anchor:** Gemma 4 E2B on a **Raspberry Pi 5 CPU** (4x A76 @2.4 GHz, similar LPDDR4X bandwidth) gets **133 tok/s prefill and 7.6 tok/s decode, using 1.5 GB** (https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm). Our phone has only 2 big cores.

| Model (GGUF) | File | Decode *est.* | Prefill *est.* | Use |
|---|---|---|---|---|
| **Qwen3.5-0.8B Q4_0** (unsloth) | 507 MB | 8–15 tok/s | 100–200 tok/s | **Default local**: intent/route classification, PII redaction, short rewrites |
| Qwen3.5-2B Q4_0 | 1.2 GB | 3–6 tok/s | 40–80 tok/s | "Offline mode" only, alone in RAM |
| Gemma 4 E2B (LiteRT-LM / GGUF Q4_0 2.8 GB) | 2.6–2.8 GB | 3–5 tok/s | 50–70 tok/s | Skip on 4 GB |

- Measure on day one: `llama-bench -m Qwen3.5-0.8B-Q4_0.gguf -t 2,4 -p 256 -n 64`. Try pinning to the big cores with `taskset`; which CPU numbers those are, cpu6–7, is *unverified*.
- Run: `llama-server -m … -c 4096 -t 4 --jinja --host 127.0.0.1 --port 8080`
- A 300-token system prompt costs about 2–3 s of prefill on 0.8B, so **keep local prompts tiny** and cache the system prompt (`--cache-reuse`).

## 7. Provider-router design

The idea: one plug shape, many engines.

```ts
interface Provider { id: 'codex'|'claude'|'local'; health(): Promise<Health>;
  run(req: {threadId?, input, tools: ToolSpec[], policy: Policy}): AsyncIterable<AgentEvent>; }
```

- **The Codex adapter** maps:
  - our `tools` to `dynamicTools`
  - `item/tool/call` to `tool.request`, which goes to the gate and then the reply
  - `item/agentMessage/delta` to `text.delta`
  - `turn/completed` to `turn.done`
  - Codex's own approval requests are always answered **decline**, because Codex has no shell and nothing to approve.
- **The Claude adapter** uses Messages API `tools` with a manual tool loop.
- **The local adapter** calls `/v1/chat/completions` with tools.
- **Routing rules** (simple table in agentd, user-overridable from the island UI):
  - Keyboard text and notification content go to **local first**, which redacts them before any cloud call.
  - Multi-step phone control goes to **Codex**, with Claude as the alternate.
  - Offline, or a usage-limit/429 error, fails over to the next provider. Show it in the UI ("Switched to Claude: ChatGPT limit reached").
- **Security centrepiece:** the permission gate lives in agentd, **outside every model**.
  - Each tool has a risk class: read, tap or type, send/pay/delete.
  - Grants are scoped per app and per session ("allow once / for this app / deny").
  - Every call is written to an append-only audit log.
  - Providers never get a shell. Prompt injection from a screen can at most *ask*.

## Recommended plan (36 h)

1. **H0–2:** Install Termux (F-Droid) on the phone, run `pkg install codex nodejs llama-cpp`, do the device-code login and the stdio smoke test. In parallel, run the same thing on the Mac.
2. **H2–8:** agentd v0 with the Codex adapter, WS + token, normalized events, dynamicTools → gate → launcher round-trip. **Develop it on the Mac first** and point the phone at it via `adb reverse tcp:7777 tcp:7777`.
3. **H8–11:** Claude adapter (API key) and the router UI toggle.
4. **H11–14:** llama-server + Qwen3.5-0.8B, benchmark, redaction/routing calls.
5. **H14–16:** move agentd onto the phone (RUN_COMMAND start, wake lock, phantom-process fix), measure RSS.

**Fallback:** if Codex misbehaves on Android, agentd and codex stay on the Mac. The phone reaches them by `adb reverse` over USB (no network needed, still localhost on both sides) or by Tailscale (`ws://100.x.y.z:7777` + token; the tailnet is WireGuard-encrypted). The launcher code doesn't change, only the URL.

**Estimate:** about 14–18 engineer-hours for this track; the phone-hosting part is about 3–5 h of that. Add about 4 h of buffer for Android surprises.

## Risks
- **Codex on Android is community-packaged, not officially supported by OpenAI.** It's version-churny: open issues #55986 and #11809, and #49402 was a regression. Mitigate by pinning the working version (`pkg hold codex`) and keeping the Mac fallback warm.
- **`dynamicTools` and `chatgptAuthTokens` are experimental APIs** and may change shape. Generate the TS schema from the installed binary.
- **Device-code login must be enabled in ChatGPT settings.** Fallback: copy `~/.codex/auth.json` from the Mac.
- **The token sits in plaintext** in `~/.codex/auth.json`, which any root process can read. For the security pitch, consider `cli_auth_credentials_store="ephemeral"` and/or the experimental `chatgptAuthTokens` fed from the launcher's Keystore.
- **LMK/OOM on 4 GB with a local model loaded.** Load it on demand.
- **Thermal throttling** during long local generations.
- **Venue Wi-Fi may block Tailscale or UDP.** USB `adb reverse` avoids that.
- **Terms:** Claude-subscription routing inside our app isn't permitted. A paid or hosted product using ChatGPT plans needs OpenAI approval.
- **All tok/s and RSS numbers here are estimates** until we measure them on the device.

## Sources
- Codex app-server docs: https://learn.chatgpt.com/docs/app-server · auth: https://learn.chatgpt.com/docs/auth
- Codex release v0.164.0 assets: https://github.com/openai/codex/releases/tag/rust-v0.164.0
- Termux codex package: https://github.com/termux/termux-packages/tree/master/packages/codex
- Codex Android issues: https://github.com/openai/codex/issues/11809, /26277, /49402, /55986, /47402, /44840
- ChatGPT plan usage for OSS apps: https://developers.openai.com/siwc/token-sharing-open-source , …/codex-app-server , …/preview-limitations
- Claude legal/auth: https://code.claude.com/docs/en/legal-and-compliance · SDK: https://code.claude.com/docs/en/agent-sdk/overview
- Claude Android issue: https://github.com/anthropics/claude-code/issues/50270 · community: https://github.com/ferrumclaudepilgrim/claude-code-android
- T3 Code: https://github.com/pingdotgg/t3code (README, docs/user/remote-access.md, apps/server/package.json)
- Gemma 4 E2B LiteRT-LM benchmarks: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm · LiteRT-LM: https://github.com/google-ai-edge/LiteRT-LM
- GGUF sizes: https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF , https://huggingface.co/unsloth/Qwen3.5-2B-GGUF , https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF
- Device specs: https://www.91mobiles.com/moto-g-5g-2023-price-in-india?ty=specs (SD480+, 4 GB LPDDR4X; spec sites conflate it with the 4G "Moto G (2023)" — confirm with `getprop ro.soc.model` on device)
- Termux: https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent , https://github.com/termux/termux-app/issues/2366
