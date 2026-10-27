# Local model on the phone: facts, before/after, default (2026-10-27)

## Facts
| | |
|---|---|
| Phone | Moto G 5G (2023), Snapdragon 480+ class (SM4350 "holi"): 2× Cortex-A76 (Kryo Gold) 2.2 GHz + 6× A55 1.9 GHz, ARMv8.2 + dotprod + fp16. RAM 3.5 GB usable (MemTotal 3,588,792 kB), 2.6 GB zram swap; 0.9–1.2 GB free with everything running |
| Model | `Qwen3.5-0.8B-Q4_0.gguf` (unsloth), 507,154,688 bytes (473 MiB), 752 M params, Q4_0. In `~/models` on the phone (the only model there) |
| Runtime | llama.cpp v0.7.0 `llama-server`, cross-built on the Mac (`deploy/local-model/build.sh`: NDK 28.2, `-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16`, static, no OpenMP), 127.0.0.1:8081 |
| Flags (new) | `-c 4096 -np 1 -t 2` pinned to the 2 big cores (`taskset c0`), `--jinja --no-webui -b 512 -ub 256 --cache-ram 128 --no-repack` |
| llama-bench (2 threads, repack on) | prompt 57.2 ± 9.5 tok/s (pp512), generation 12.3 ± 0.9 tok/s (tg64) |
| Qwen3.5-2B-Q4_0 (tried, 1.21 GB) | prompt 33.7 tok/s, generation 8.3 tok/s; router 30/30, but 2.5 s/request warm, 17.7 s cold, and only ~460 MB left free → not kept (deleted from the phone and the Mac) |
| Memory (0.8B) | repack on: 690 MB anon + 380 MB file. `--no-repack`: **219 MB anon** + 490 MB file (file pages can be dropped by Android without killing anything) |
| Time to first token (router prompt, 455 tokens) | cold: 7.5 s (repack) / 12.5 s (no-repack). Warm (prompt cached, ~18 new tokens): whole answer 1.0 s (repack) / 1.3 s (no-repack) |
| On-demand start | llama-server stopped → "Open Slack" through the live agentd: 17.6 s (≈5 s start + 6.4 s cold prompt). Warm: "Open Messages" 2.9 s end to end |

## What changed (why it works now)
Analogy: before, a tiny model had to drive the phone screen by screen, like a new driver in a strange city. Now it only fills in an order slip, and code drives.
- **Router** (`providers/local.js` `plan`): one call with a JSON schema (llama-server turns it into a grammar, so the output is always valid JSON). The forms are open_app, set_alarm, set_timer, web_search, add_event, play_music, look, and other. When the user's words name the action ("alarm", "timer", "calendar/schedule", "play", "search"), the grammar allows only that form. Code checks the slots against the user's own words (am/pm, "90 seconds", a made-up music query, "launch the X"). The prompt prefix is identical every time, so llama-server caches it and a warm request reads ~18 tokens.
- **Execution** in code via `runTool`, the same path as Codex: lease, broker tiers, Rule of Two, audit. Nothing skips the broker. Apps are resolved from `list_apps` labels.
- **New allow-listed intents** (Android `Actions.intent`, Broker tier T2 "draft"):
  - `event {title, start, minutes?}` opens Calendar's filled-in sheet. agentd then taps Save as a normal broker tap, and first taps "Continue as …" if Calendar shows its one-time account prompt.
  - `play {pkg, query}`: music play-from-search. With an empty query it opens the app and presses media Play at 3, 5 and 7 s.
  - Both are exposed to Codex too (`add_event`, `play_music` in tools.js and prompt.js).
- **look** ("read me Mom's last message"): code opens the app, and the small screen loop starts on its screen.
- **other**: an honest "can't do that on-device yet" instead of a guessing loop.
- **Bug found**: the committed local provider crashed on every screen (`s.nodes.filter is not a function`), because tools.js moved to compact text screens. It now formats from the guarded nodes (`turn.safeNodes`).

## Router accuracy (`bench/local-router.js`, 30 phrasings, 10 of them held out)
0.8B: 15/30 (first prompt) → 27/30 (examples) → **30/30** (grammar hints + word checks), 60/60 over 2 runs. Same on the phone. 2B: 30/30.

## Phone tasks (`bench/local-tasks.js`, 3 runs each, test agentd :8790, every call through the broker, checked with adb)
| Task | Committed code | Old design, JSON screens (fair before) | After (repack) | **Final (no-repack)** | Final median |
|---|---|---|---|---|---|
| Set an alarm for 7 | 0/3 | 0/3 (made up "I am setting an alarm") | 3/3 | **3/3** | 16 s* |
| Start a 5 minute timer | 0/3 | 0/3 | 3/3 | **3/3** | 6.3 s |
| Lunch with Sam on calendar tomorrow at noon | 0/3 | 0/3 | 1/3 → 8/10 after the "Continue as" fix | **2/3** (the miss said "didn't save, tap Save") | 25 s |
| Open Spotify and play something | 0/3 | 0/3 | 3/3 | **3/3** | 15 s |
| Search Chrome for weather in SLC | 0/3 | 0/3 | 3/3 | **3/3** | 13 s |
| Open Slack | 0/3 | 2/3 | 3/3 | **3/3** | 9.8 s |
| Open Messages | 0/3 | 3/3 | 3/3 | **3/3** | 6.3 s |
| Open the calculator | 0/3 | 3/3 | 3/3 | **3/3** | 6.2 s |
| Turn on Do Not Disturb | 0/3 | 0/3 | 0/3 | 0/3 (by design: Settings is a protected app for every agent; it says so in 1 s) | 1.3 s |
| Pigeon: read Mom's last message | 0/3 | 3/3 | 3/3 | **3/3** | 30 s* |
| **Total** | **0/30** | **11/30** | **25/30** | **26/30** (26/27 supported) | |

\* After the repack → no-repack switch, a request that follows a long Pigeon screen loop re-reads the router prompt (12 s), because the 128 MB prompt cache dropped it. The Pigeon loop also reads more slowly. In the repack run the same tasks took alarm 3.7 s, timer 4.5 s, apps 5–6 s, Pigeon 9.8 s. Untested fix: `--cache-ram 512` or 2 slots. My 2-slot test was invalid (a stale pid file kept the old server on :8081) and was dropped.

**Airplane mode** (live agentd, launcher path):
- Works offline: alarm 5.4 s, timer 5.1 s, Pigeon read 18 s.
- Calendar works offline: the event was saved, but showed up in the provider late.
- Spotify offline: nothing is downloaded, so nothing plays. The answer now says "Opened Spotify and pressed Play", not "Playing".

## Default and switching
- Default local model: **Qwen3.5-0.8B-Q4_0 + router**, llama-server with `--no-repack`. That's slower, but it uses 430 MB less anonymous RAM. A low-memory storm at 15:39 killed the whole Termux process (agentd, the boot loop and llama-server) while repack was on and a second bench agentd was running. Codex stays the agentd default.
- Launcher: Center sheet → **Model: Codex | Local** sends `provider:"local"`. Verified through the live agentd WS (same path). llama-server starts on demand on the first ask (~17 s cold). For the demo, keep it running: `touch ~/.agentos/local-model.on` (Termux:Boot starts it, and agentd warms the router at start). It is running now, but the flag is not set.
- Not covered: voice tasks and home suggestions always use Codex (tasks.js), so offline means typed asks only.

## Demo-safe (on-device, airplane OK)
"Set an alarm for 7" · "Start a 5 minute timer" · "Open Slack / Messages / Calculator" · "Open Pigeon and read me the last message from Mom" · "Search Chrome for …" (online only) · "Open Spotify and play something" (online, or with downloaded music) · Calendar works but miss ~1 in 5; rehearse it.

## Files
`providers/local.js` (router, perform, warm), `tools.js` (`add_event`, `play_music`, `turn.safeNodes`), `prompt.js`, `deploy/local-model/llama.sh` (flags; `stop` falls back to pgrep, not yet on the phone), Android `Actions.java` + `Broker.java` (event/play intents; installed), `bench/local-router.js`, `bench/local-tasks.js`, `bench/local-up.sh`, `bench/results/local-*.jsonl`.
