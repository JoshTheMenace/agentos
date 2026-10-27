# Voice spike: GPT live voice on the ChatGPT subscription (2026-10-26)

**Result: it works.** A plain browser page (WebRTC mic + speaker) talked to `gpt-live-1-codex`
through `codex app-server`'s `thread/realtime/start`, using the Mac's **ChatGPT Pro login** (no API key).
Voice questions came back as spoken audio with transcripts, and a spoken "what's my battery?" ran our
**dynamic tool** (`phone_battery`) and spoke the answer.

Codex used: Mac `codex-cli 0.164.0` (logged in with ChatGPT, plan `pro`). The same flow then worked **on the phone**: WebView mic → the phone's codex **0.163** app-server (see [Phone path](#phone-path)).

## How it works (analogy)

Think of a phone call booked by a receptionist.
- **app-server is the receptionist with the company badge** (the ChatGPT token). It books the call
  (`POST chatgpt.com/backend-api/codex/realtime/calls`) and then listens on a side line (the "sideband" WebSocket).
- **The page is the handset.** It sends its "how to reach me" note (SDP offer) through the receptionist,
  gets the answer note back, and then talks **straight to OpenAI** over WebRTC (Opus audio + an `oai-events` data channel).
- When you ask for something the voice model can't do itself, it raises a **delegation**
  ("someone please check the battery"). A Codex turn picks it up, calls our phone tools, and hands the answer back
  to the voice, which says it.

```
 page / WebView ──WebRTC audio + "oai-events" data channel──────────────► OpenAI gpt-live-1-codex
     │ SDP offer ▲ SDP answer                                                   ▲
     ▼           │                                                              │ sideband WSS (ChatGPT auth)
 relay.mjs (= agentd) ──stdio JSON-RPC──► codex app-server ─────────────────────┘
                         ◄── item/tool/call (dynamicTools) ── Codex turn (started by a delegation)
```
The token never leaves app-server. Audio never passes through the relay.

## Files

| File | What |
|---|---|
| `relay.mjs` | ~100-line Node relay (no deps). Spawns `codex app-server` on stdio, serves the page, `POST /start` (offer → answer), `GET /events` (SSE of every app-server notification), `/stop`, `/say`, `/delegate`. Fakes two phone tools. It plays agentd's role. |
| `index.html` | Bare test page: mic → RTCPeerConnection, `oai-events` data channel, level meters for latency, records the reply track. `?auto` starts at once; `?wav=/out/test.wav` sends a speech file instead of the mic. |
| `make-test-audio.sh` | `say` → `out/test.wav` (two questions with silence between). |
| `phone-codex.sh` | Stand-in `CODEX_BIN` that runs the **phone's** app-server over `adb shell -T` (stdio). |
| `android/` | Spike APK `local.agentos.voicespike`: one Activity with a WebView, `onPermissionRequest` + `RECORD_AUDIO`. `android/build.sh`. |
| `out/run-*.log` | Logs from the runs below (gitignored). |

## Run it

```sh
node spikes/voice/relay.mjs                      # server mode; DELEGATE=client for client mode (see below)
open http://127.0.0.1:8790                       # click "Start voice", talk
# headless self-test with synthesized speech:
spikes/voice/make-test-audio.sh
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --user-data-dir=/tmp/voice-chrome \
  --autoplay-policy=no-user-gesture-required "http://127.0.0.1:8790/?auto&wav=/out/test.wav"
```
On the Mac the relay switches off your personal `~/.codex` MCP servers for its own app-server (they slowed thread start).

## Exact protocol (app-server 0.164, all `#[experimental]`, needs `capabilities.experimentalApi: true`)

```jsonc
→ {"id":1,"method":"initialize","params":{"clientInfo":{"name":"agentos_voice_spike","title":"…","version":"0.1"},"capabilities":{"experimentalApi":true}}}
→ {"method":"initialized"}
→ {"id":2,"method":"thread/start","params":{"ephemeral":true,"sandbox":"read-only","approvalPolicy":"never","cwd":"/tmp",
     "developerInstructions":"You are AgentOS… Use phone_* tools…",
     "dynamicTools":[{"type":"function","name":"phone_battery","description":"…","inputSchema":{"type":"object","properties":{}}}]}}
← {"id":2,"result":{"thread":{"id":"<T>",…}}}
→ {"id":3,"method":"thread/realtime/start","params":{"threadId":"<T>","outputModality":"audio","version":"v3",
     "clientManagedHandoffs":false,                       // true = we speak results ourselves (client mode)
     "transport":{"type":"webrtc","sdp":"<offer SDP>"}}}  // omit "voice" or use a v3 voice (see below)
← {"method":"thread/realtime/started","params":{"threadId":"<T>","realtimeSessionId":"<T>","version":"v3"}}
← {"method":"thread/realtime/sdp","params":{"threadId":"<T>","sdp":"<answer SDP>"}}   // → pc.setRemoteDescription
← {"id":3,"result":{}}
← thread/realtime/transcript/delta {threadId, role:"user"|"assistant", delta}
← thread/realtime/transcript/done  {threadId, role, text}
← thread/realtime/itemAdded {threadId, item:{type:"handoff_request", handoff_id, item_id, input_transcript, active_transcript}}
← turn/started … item/completed {item:{type:"agentMessage", phase:"commentary"|"final_answer", text}}
← {"id":"…","method":"item/tool/call","params":{"threadId":"<T>","turnId":"…","callId":"…","tool":"phone_battery","arguments":{}}}
→ {"id":"…","result":{"success":true,"contentItems":[{"type":"inputText","text":"Battery 73%, not charging."}]}}
← turn/completed
→ {"id":9,"method":"thread/realtime/stop","params":{"threadId":"<T>"}}
← thread/realtime/closed {threadId, reason}
```
Other methods: `thread/realtime/appendText {threadId,text,role}` (typed input), `appendSpeech {threadId,text}`
(make it say something), `appendAudio`, `listVoices`. `thread/realtime/outputAudio/delta` is only for the
websocket transport; with WebRTC the audio arrives on the media track.

**Browser side (same for WebView):** `getUserMedia({audio:{echoCancellation:true,noiseSuppression:true}})` →
`addTrack` → `createDataChannel('oai-events')` **before** `createOffer` → `setLocalDescription` → wait for ICE
gathering, **capped at 500 ms** (headless Chrome took 40–119 s to report "complete"; host candidates are enough
because the answer is `ice-lite`) → send `localDescription.sdp`.

**Data channel events seen (v3 "frameless"):** `session.started`, `turn.created`/`turn.delta`/`turn.done`
(`turn.role` user/assistant, `turn.transcript`), `input_transcript.added`, `output_transcript.added`,
`delegation.created`, `delegation.context.appended`, `session.usage.updated`.
```jsonc
{"type":"delegation.created","item":{"id":"item_…","type":"delegation","target":"client","handoff_id":"handoff_1",
  "content":[{"type":"input_text","text":"What is my phone battery level right now"}],"user_bidi_turn_id":"turn_…"}}
// a client may answer it on the same data channel ("speakable" = say it, "commentary" = quiet context):
{"type":"delegation.context.append","delegation_item_id":"item_…","channel":"speakable",
  "content":[{"type":"input_text","text":"Your phone's battery is at 73% and isn't charging."}]}
```

## Tool calls and handoff: what I learned

- The voice model (`gpt-live-1-codex`) **does not call our tools directly.** In v3 its only "tool" is
  delegation. It says a short filler ("Checking now—hang on") and emits `delegation.created`.
- **Server mode** (`clientManagedHandoffs:false`, what Codex's TUI does): app-server sees the delegation on its
  sideband and starts a normal **Codex turn on the same thread** (`turn_trigger:"realtime"`, input wrapped in
  `<realtime_delegation><input>…</input><transcript_delta>…</transcript_delta>`). That turn has the thread's
  `dynamicTools`, so we get an ordinary `item/tool/call`. Codex's commentary and final answer are streamed
  back to the voice automatically, which speaks them. **No delegation code needed in the client.**
- **Client mode** (`clientManagedHandoffs:true`): the page sees `delegation.created` on its own data channel
  at once, asks agentd to run the work (any provider), and answers with `delegation.context.append`. Tested and
  works. Catch: app-server still routes the same delegation into a turn on the voice thread, so give the voice
  thread **no tools** (or interrupt that turn) to avoid doing the work twice.
- Both modes keep rule 1 intact: actions only happen through `item/tool/call` → agentd → Android tool server.

## Latency (Mac, Chrome, home network that swung between ~1 Mbps and fine)

| Step | Measured |
|---|---|
| Offer ready (500 ms ICE cap) | 0.32–0.41 s |
| `thread/realtime/start` → SDP answer | **1.2 s** and 2.4 s on a good moment; 7.3 s, 14.3 s, 64.5 s when the link was ~1 Mbps (120 KB/s measured) |
| Answer → `pc connected` → data channel open | +0.3–0.7 s → +0.1–1.4 s (best total from click: **2.0 s**) |
| End of speech → first reply audio (plain question) | **0.4–2.1 s** (sometimes it starts before you finish) |
| Server mode, "what's my battery?" (clean run) | `delegation.created` +1.2 s · filler audio ("Checking that real quick") +2.8 s · `item/tool/call` +3.1 s · Codex final text +4.4 s · voice finished the answer +9.4 s |
| Client mode, same question (bad-network moment) | worker Codex turn 12.5 s (first item after 4 s) · answer audio +16.4 s |

Cost note: each delegated Codex turn used about 19k input tokens per model call (two calls per tool question,
the second mostly cached). The weekly Codex limit showed `usedPercent: 8` after all runs. Voice minutes
themselves did not show up separately in `account/rateLimits/updated`.

**The big risk is bandwidth, not the model.** The sideband WebSocket receives **every audio frame in both
directions as base64 PCM** (`session.input_audio.append`, `session.output_audio.delta`, roughly 64 KB/s each way
of speech). On the ~1 Mbps link the sideband fell minutes behind in one run: transcripts stopped at 24 s and
the delegation never reached app-server, so the voice said "checking" and went silent. Client mode does not
depend on the sideband (the page gets `delegation.created` straight away), which is why it exists.

## Gotchas
- `voice:"marin"` fails on v3: `supported voices: juniper, maple, spruce, ember, vale, breeze, arbor, sol, cove, aube, bubbie, haetsal, hanul, himari, leher, miko, neer, porto, rindo, rio, rivage, selva, sonna, tinta, viola`. Omit it for the default.
- Chrome's `--use-file-for-fake-audio-capture` gave silence on macOS (audio energy 0); the page's `?wav=` mode is the reliable test path.
- Attestation was never requested; `clientInfo.name:"agentos_voice_spike"` was accepted by the backend.
- `window.stop` shadows an element with `id="stop"`; use `getElementById`.

## Phone path

### Design
```
 AgentOS app (local.agentos)                         Termux                          root
 ┌───────────────────────────────┐   ws :8787    ┌──────────┐  stdio JSON-RPC  ┌────────────────────┐
 │ WebView: voice page           │──voice.start──►│  agentd  │─────────────────►│ codex app-server   │
 │  getUserMedia + RTCPeerConn.  │◄─voice.answer──│          │◄─ item/tool/call │ (ChatGPT login)    │
 │  data channel "oai-events"    │                └────┬─────┘                  └─────────┬──────────┘
 └──────────────┬────────────────┘                     │ :7777 tool server (gate)         │ sideband WSS
                │ WebRTC (UDP, Opus)                    ▼                                  │ via :18080 proxy
                └──────────────────────────► OpenAI gpt-live-1-codex ◄─────────────────────┘
```
- **No native WebRTC library.** Android WebView (Chromium 155 on the Moto) has `getUserMedia` and
  `RTCPeerConnection`; `index.html` runs unchanged.
- **Mic permission flow (implemented in `android/VoiceActivity.java`):**
  1. Manifest: `RECORD_AUDIO` + `MODIFY_AUDIO_SETTINGS` (WebView's WebRTC audio needs both) + `INTERNET`;
     `usesCleartextTraffic="true"` (or a network-security-config for 127.0.0.1 only) because targetSdk 33 blocks `http://`.
  2. `settings.setJavaScriptEnabled(true)`, `setMediaPlaybackRequiresUserGesture(false)` (reply audio autoplays).
  3. Page calls `getUserMedia` → `WebChromeClient.onPermissionRequest(req)`. Grant **only** when the resources
     are exactly `RESOURCE_AUDIO_CAPTURE` and the origin is `http://127.0.0.1:…` (our page); deny everything else.
  4. If the app lacks `RECORD_AUDIO`, call `requestPermissions` and finish the `PermissionRequest` in
     `onRequestPermissionsResult` (grant or deny). On the lab phone `pm grant` also works.
  5. The page must be a secure context: `http://127.0.0.1` or `localhost` count; `file://` doesn't reliably.
     Serve the voice page from agentd (`GET /voice`) or bundle it with `WebViewAssetLoader`.
- **Signaling** goes through agentd on the existing WebSocket; agentd owns the only app-server pipe.
- **Push-to-talk on stage:** keep one pre-connected session while the island is open and toggle
  `track.enabled` on the mic track. This hides the 1–2 s connect time and stops crowd noise from triggering replies.
- **Firewall note:** WebRTC media goes from the **app's uid** straight to OpenAI (the SDP answer is
  `ice-lite`, i.e. a server with a public address). The iptables rules must allow that uid UDP out, and the root app-server uid
  must reach chatgpt.com through the proxy.

### Phone results (Moto G 5G, Android 13, WebView 155, phone's own **codex 0.163.0**, 2026-10-26 23:33)
Setup: spike APK in a WebView → `adb reverse tcp:8790` → `relay.mjs` on the Mac with
`CODEX_BIN=spikes/voice/phone-codex.sh` (the phone's root app-server over adb stdio, using its
ChatGPT login, going through `android-connect-proxy :18080`). Media went over the phone's Wi-Fi.

| Check | Result |
|---|---|
| codex **0.163** supports it? | **Yes.** `thread/realtime/start` v3 + WebRTC + `gpt-live-1-codex` worked as-is; no need for 0.164. The sideband WSS works through the CONNECT proxy. |
| `thread/realtime/start` → SDP answer | **0.7–1.3 s** (4 sessions); data channel open 2.2 s after the offer |
| Plain question → reply audio | 1.9 s after end of speech ("Paris.") |
| "What is my phone battery level?" | `delegation.created` +0.7 s · filler +1.8 s ("Hang on, checking now") · `item/tool/call phone_battery` +3.4 s · final text +6.4 s · spoken answer started +8.0 s ("You're at 73 percent, not charging.") |
| WebView mic permission | `onPermissionRequest [AUDIO_CAPTURE] from http://127.0.0.1:8790/` → `requestPermissions(RECORD_AUDIO)` → system dialog → Allow (tapped by a person at the phone, 3.8 s) → `grant()` → `getUserMedia` resolved. Real mic live: `media-source audioLevel 0.07`. |
| Real mic in a noisy room | It heard nearby talk ("Okay. Do print") and **delegated it to Codex**. This is the voice prompt-injection risk in practice: use push-to-talk. |
| Cleanup | Spike APK uninstalled, adb reverse/forward removed, phone app-server exited with the relay, lock released. |

## agentd integration (proposal; I did not edit `agentd/`)

> **Now built** (10-27): `agentd/voice.js` (front desk) + `agentd/tasks.js` (background Codex workers). See `agentd/README.md` → "Voice front desk".

agentd already has the hooks: WebViews authenticate with the `agentos.bearer.<token>` subprotocol, and
`voice.start`/`voice.stop` currently answer `unsupported`. Proposed messages:

| Direction | Message | Meaning |
|---|---|---|
| client → agentd | `{type:"voice.start", sdp, mode?:"server"\|"client"}` | Offer SDP from the page. agentd does `thread/start` (same `dynamicTools` + `SYSTEM_PROMPT`, plus "answers are spoken: one or two short sentences") and `thread/realtime/start {threadId, version:"v3", outputModality:"audio", clientManagedHandoffs: mode==="client", transport:{type:"webrtc", sdp}}`. |
| agentd → client | `{type:"voice.answer", sdp}` | From the `thread/realtime/sdp` notification. Page calls `setRemoteDescription`. |
| agentd → client | `{type:"voice.started", session_id}` | From `thread/realtime/started`. |
| agentd → client | `{type:"voice.transcript", role, text, final}` | Optional mirror of `thread/realtime/transcript/*` for UIs that are not the voice page (the voice page already gets `turn.done` on its data channel). |
| client → agentd | `{type:"voice.stop"}` | `thread/realtime/stop`. |
| agentd → client | `{type:"voice.closed", reason}` / `{type:"voice.error", message}` | From `thread/realtime/closed` / `thread/realtime/error`. |
| client → agentd | `{type:"voice.say", text}` | `thread/realtime/appendSpeech`, e.g. "Approve it on your phone" when `approval.needed` fires. |
| client → agentd | `{type:"voice.delegate", item_id, text}` *(client mode only)* | Page got `delegation.created`. agentd runs a normal `ask` turn (any provider). |
| agentd → client | `{type:"voice.result", item_id, text}` *(client mode only)* | Final answer. Page sends `delegation.context.append {delegation_item_id:item_id, channel:"speakable", content:[{type:"input_text", text}]}` on its data channel. |

Changes inside `providers/codex.js` (for the agentd owner):
1. In **server mode** the Codex turn is started by app-server, not by `run()`. So `#onLine` must treat a
   `turn/started` on the voice thread as a new turn: create the `t` context (emit = the voice client's `send`,
   `turn = {goal}`) so `item/tool/call` → `runTool` works (today it answers "No active turn"), and call
   `endTurn` on `turn/completed`. The existing `tool.request` / `approval.needed` / `turn.done` events then flow
   to the island as usual, and the permission gate is unchanged.
2. Route `thread/realtime/*` notifications by `threadId` to the voice connection (they are not tied to a turn).
3. In **client mode**, start the voice thread **without** `dynamicTools` and interrupt (`turn/interrupt`)
   any turn app-server starts on it, because app-server routes the same delegation too.
4. `voice.stop` on WebSocket close, like `stop`.

**Which mode?** Use **server mode** by default: no client code for delegation, Codex's own commentary is spoken
as filler, and it is the path Codex's TUI uses. Switch to client mode if the sideband lags (slow uplink),
or if a non-Codex provider (Claude, local) should do the work.

## Next steps
1. agentd owner: add the messages above and the voice-thread turn routing (about 60 lines).
2. Android owner: put the `VoiceActivity` permission code into the island/VIS WebView host; serve the voice page from agentd.
3. Fable designer: replace `index.html` UI; keep the WebRTC/data-channel logic.
4. Demo: venue Wi-Fi or a good hotspot (budget ≥ 2 Mbps up/down for media + sideband), pre-connect, push-to-talk.
