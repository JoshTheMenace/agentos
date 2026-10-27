# 02 — Voice assistant track (AgentOS)

Research date: 2026-10-26. Codex source inspected at `openai/codex@3f74ff2` (main, 2026-10-27), latest stable `rust-v0.164.0`; the phone lab already runs `codex-0.163.0` aarch64-musl (see `spikes/termux-codex/codex-env.sh`). No phone commands were run for this report.

## TL;DR

- **Yes, a ChatGPT subscription can power realtime speech, but only one supported way.** Codex app-server has an experimental `thread/realtime/start` method. With `transport: {type:"webrtc", sdp}`, app-server creates the call at `https://chatgpt.com/backend-api/codex/realtime/calls` using your **ChatGPT login**. The default model is `gpt-live-1-codex` (realtime v3). This is the same path the Codex TUI `/voice` uses, and it has been on by default since 0.156. The **WebSocket** transport still needs an API key (`"realtime conversation requires API key auth"`).
- **What that means for us:** our Android app is the WebRTC "phone handset". It creates the mic track and the `oai-events` data channel, then hands its SDP offer to app-server. App-server keeps the ChatGPT token and the control channel. Audio flows phone↔OpenAI directly. Voice requests are handed to a Codex turn, and that turn can call **our phone tools** through app-server `dynamicTools` (`item/tool/call`). Those calls pass through our permission gate.
- **Trigger:** become the Android 13 default digital assistant with a `VoiceInteractionService`. This role is `requestable="false"`, so either the user picks it in Settings or we run `cmd role add-role-holder` as root. Then set long-press power to "assistant" (`settings put global power_button_long_press 5`). Being the VIS app also exempts us from Android 12+'s "no mic from background" rule.
- **Fallback (no OpenAI realtime):** push-to-talk with Android `SpeechRecognizer` (bound to an explicit Google component), then a text turn on app-server or Claude, then Android `TextToSpeech` spoken sentence by sentence. Optional offline mode: sherpa-onnx (streaming STT, VAD, TTS). If the subscription path breaks, the API-key fallback is GPT-Live / `gpt-realtime-2.1(-mini)` over WebRTC.
- **Estimate:** about 13–17 h for the subscription realtime path plus the cascade fallback. The fallback alone is about 5–6 h. Do a 1.5 h de-risk spike first (laptop browser → app-server → voice) before writing any Android WebRTC code.

---

## 1. The key question: can the ChatGPT subscription do realtime voice?

### Short answer
**Yes, through Codex app-server's WebRTC transport. There is no other sanctioned path.** Evidence from the Codex source:

| Fact | Where |
|---|---|
| App-server exposes experimental realtime methods: `thread/realtime/start`, `appendAudio`, `appendText`, `appendSpeech`, `stop`, `listVoices`. Notifications include `thread/realtime/sdp`, `…/transcript/delta`, `…/outputAudio/delta`, `…/error`, and `…/closed`. All are marked `#[experimental]`. | `codex-rs/app-server-protocol/src/protocol/common.rs` L1099-1139, L2069-2090 |
| `ThreadRealtimeStartTransport` = `websocket` \| `webrtc{sdp}` \| `existingCall{callId}` | `app-server-protocol/src/protocol/v2/realtime.rs` |
| WebRTC call creation uses the **configured provider's auth**, which is the ChatGPT token when you log in with ChatGPT. When the base URL contains `/backend-api`, it POSTs to `…/backend-api/codex/realtime/calls`. | `core/src/client.rs` `create_realtime_call_with_headers`; `codex-api/src/endpoint/realtime_call.rs` (tests assert `https://chatgpt.com/backend-api/codex/realtime/calls`) |
| **WebSocket** transport calls `realtime_api_key()`. That returns a provider key, a bearer token, an API-key login, or `OPENAI_API_KEY`. Otherwise it errors with `"realtime conversation requires API key auth"`. A TODO says the ChatGPT/SIWC limitation is temporary. | `core/src/realtime_conversation.rs` L1940-1963 |
| Default models: `gpt-realtime-1.5` (v1/v2), `gpt-live-1-codex` (v3 "frameless"). | `realtime_conversation.rs` L114-115 |
| TUI `/voice` = `thread/realtime/start` with `transport: Webrtc{sdp}`, `version: V3`, `outputModality: audio`, `clientManagedHandoffs: true` | `tui/src/app_server_session/realtime.rs` |
| Attestation is optional. The TUI answers attestation requests with "not available" and voice still works. So we probably don't need it (verify). | `app-server/src/attestation.rs`, `tui/src/app/app_server_requests.rs` L200 |
| Release history: experimental `/voice` in 0.155; on by default with F8 in 0.156; device selection in 0.161; v3 expansion in 0.163. | `gh api repos/openai/codex/releases` |

**What is *not* sanctioned:**
- **Sign in with ChatGPT (SIWC)** for third-party apps covers only the Responses API. Its docs say to use `api.openai.com/v1/responses` "not ChatGPT's `backend-api` endpoints". It is also a limited trial for selected partners. https://developers.openai.com/siwc/quickstart , https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference.md
- Pulling the token out of `auth.json` and calling `chatgpt.com/backend-api/codex/realtime/calls` directly from our APK would be token misuse. It would also hurt the security pitch, so **don't**. Treat app-server as the trust boundary.
- ChatGPT "Advanced Voice / GPT-Live" in the ChatGPT apps has no public subscription API. OpenAI Help says Voice for Work/Codex is desktop-only (macOS/Windows), and voice time may be billed separately "where flexible pricing applies". https://help.openai.com/en/articles/20001275 (that page returned 403 to my fetcher, so this is from the search summary and is **unverified**).
- I know of no subscription-authenticated realtime speech endpoint for Claude. Use Claude as a text "brain" behind STT and TTS.

**Uncertain, so verify in the spike:**
1. Exactly how voice minutes count against Plus/Pro Codex limits.
2. Whether the backend restricts which clients may use `gpt-live-1-codex`. App-server reports `clientInfo` and an originator header. We send our own `clientInfo.name`, which is honest, and it might be rejected.
3. Whether the sideband WSS works through the phone lab's `HTTPS_PROXY=127.0.0.1:18080` wrapper.

### How the subscription voice path works (analogy)
App-server is like a **receptionist who holds the company badge** (your ChatGPT login). Our phone app is a **visitor with a walkie-talkie**. The visitor gives the receptionist a note ("here's how to reach my walkie-talkie", the SDP offer). The receptionist uses the badge to book the line and passes back the answer note. From then on the visitor talks straight to OpenAI. The receptionist also listens in on a side line (the sideband) so it can hand tasks to the Codex agent. The badge never leaves the receptionist's desk.

```
[Phone mic/speaker] ==WebRTC audio (Opus) + "oai-events" datachannel==> OpenAI (gpt-live-1-codex)
      |  SDP offer / answer                                                  ^
      v                                                                      | sideband WSS (ChatGPT auth)
[AgentOS app] --JSON-RPC (ws://127.0.0.1:4500 or stdio)--> [codex app-server] (holds ChatGPT login)
      ^                                                            |
      |  item/tool/call  (dynamicTools: tap, type, open_app, ...)  |  delegation -> Codex turn
      +------------------- permission gate (our UI) <--------------+
```

### Minimal JSON-RPC sequence (app-server v2, experimental)
```jsonc
→ {"id":1,"method":"initialize","params":{"clientInfo":{"name":"agentos","title":"AgentOS","version":"0.1"},
                                          "capabilities":{"experimentalApi":true}}}
→ {"method":"initialized"}
→ {"id":2,"method":"thread/start","params":{ /* cwd, model, ... */
      "dynamicTools":[{"name":"phone_tap","description":"...","inputSchema":{...}}]}}   // experimental field
→ {"id":3,"method":"thread/realtime/start","params":{"threadId":"<id>","outputModality":"audio",
      "version":"v3","clientManagedHandoffs":true,"includeStartupContext":false,
      "transport":{"type":"webrtc","sdp":"<offer SDP>"},"voice":"marin"}}
← {"method":"thread/realtime/sdp","params":{"threadId":"<id>","sdp":"<answer SDP>"}}   // setRemoteDescription
← thread/realtime/started, …/item/transcript/delta, …/transcript/done, …/error, …/closed
← {"id":"s1","method":"item/tool/call","params":{...}}   // Codex wants to use a phone tool -> gate -> respond
→ {"id":9,"method":"thread/realtime/appendSpeech","params":{"threadId":"<id>","text":"Done — I sent it."}}
→ {"id":10,"method":"thread/realtime/stop","params":{"threadId":"<id>"}}
```
- Get the exact field shapes from `codex app-server generate-json-schema` or `generate-ts`, or from `app-server-protocol/src/protocol/v2/realtime.rs`. The fields are experimental and change between releases, so **pin the Codex version** (0.163.0 is already on the phone).
- `clientManagedHandoffs:true`: Codex results are not spoken automatically. We decide what to say with `appendSpeech` (spoken) or `appendText` (quiet context). This is good for the security story: nothing is said or done without passing our gate.
- Run the server: `codex app-server --listen ws://127.0.0.1:4500`. If it is ever bound beyond loopback, add `--ws-auth capability-token --ws-token-file /abs/path`. On the phone, use the existing root wrapper (`agentos-codex-env.sh app-server`) over stdio, or ws on loopback. On a laptop, use `adb reverse tcp:4500 tcp:4500`. Note that adb reverse only carries JSON-RPC; WebRTC media goes over Wi-Fi or cellular.

### Android WebRTC client
- There is no official OpenAI Android sample. Use prebuilt libwebrtc: `io.github.webrtc-sdk:android` (Maven index shows 125.6422.07) or `io.getstream:stream-webrtc-android` (1.3.8). The index may lag, so check for newer versions. https://github.com/GetStream/webrtc-android
- Reference flow: OpenAI WebRTC guide (offer → POST SDP → answer; data channel label `oai-events`). https://developers.openai.com/api/docs/guides/realtime-webrtc.md. Pipecat has an Android OpenAI WebRTC transport you can read for ICE and audio setup: https://docs.pipecat.ai/api-reference/client/android/transports/openai-webrtc
- Use `JavaAudioDeviceModule.builder(ctx).setUseHardwareAcousticEchoCanceler(true).setUseHardwareNoiseSuppressor(true)`. Use `MODE_IN_COMMUNICATION` audio. Create the data channel **before** `createOffer`. Wait for ICE gathering to finish (non-trickle), because app-server takes one SDP blob.
- **Build gotcha:** the lab APK builds with javac + D8 and no Gradle. libwebrtc is an AAR with `.so` files. Either switch the voice app to Gradle, or unpack the AAR by hand (classes.jar → D8; `jni/arm64-v8a/*.so` → `lib/arm64-v8a/` in the APK, stored uncompressed, plus `extractNativeLibs`). Budget +1–2 h if you stay Gradle-less.

### API-key fallback (if the subscription path fails)
- **GPT-Live** with `POST /v1/live/sessions` over WebRTC, using **client delegation** (`delegation:{type:"client"}`). GPT-Live does the talking; our agent (Codex, Claude, or local) does the work. We send results back with `session.commentary.append` (spoken) or `session.thinking.append` (quiet). $0.05/min plus backend usage. WebRTC init bills 15 s up front, credited later. https://developers.openai.com/api/docs/guides/live-delegation.md
- **Classic Realtime** `gpt-realtime-2.1-mini` ($10/$20 per 1M audio tokens in/out) or `gpt-realtime-2.1` ($32/$64). Mint an ephemeral key at `POST /v1/realtime/client_secrets` on a laptop or relay, then the phone POSTs SDP to `/v1/realtime/calls`. https://developers.openai.com/api/docs/pricing. Rough cost: about 600 input + 1,200 output audio tokens per minute gives **≈ $0.03/min on mini**. That is an estimate and ignores context growth.
- Never ship the long-lived API key inside the APK. Use ephemeral keys, which also helps the security pitch.

---

## 2. Trigger: becoming the default assistant on Android 13

### Facts (AOSP android13-release)
- The `android.app.role.ASSISTANT` role has **`requestable="false"`**, so `RoleManager.createRequestRoleIntent` won't work. The holder must have either a `VoiceInteractionService` with `supportsAssist=true`, `sessionService`, and **`recognitionService`**, or an activity with `ACTION_ASSIST`. (`PermissionController/res/xml/roles.xml`)
- When the role changes, `VoiceInteractionManagerService.RoleObserver` writes `Settings.Secure.assistant` and `voice_interaction_service`. If our VIS has no `recognitionService`, it **falls back to "activity-only" assistant**, with no session and no mic exemption.
- VIS is disabled on devices without `FEATURE_VOICE_RECOGNIZERS` (svelte/low-RAM). Check with `pm list features | grep voice_recognizers`.
- Long-press power → `LONG_PRESS_POWER_ASSISTANT = 5` → `launchAssistAction(... INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS)`, which goes to the current assistant. (`PhoneWindowManager.java` L263-268, L1196-1203)
- Motorola UI path: **Settings → Gestures → Power button → Press and hold → Digital assistant**. https://help.motorola.com/hc/7162/16/global/en-gb/T2005233848.html. It is unverified whether Moto's build sends this to the role holder or hard-wires Google. Test it first.
- Mic from background: Android 12+ blocks starting a `microphone` foreground service from the background. One listed exemption is **"The service starts by an app which provides the `VoiceInteractionService`."** Being the overlay (SYSTEM_ALERT_WINDOW) or an accessibility service does **not** exempt you. https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

### Manifest sketch
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<queries>  <!-- Android 11+ package visibility -->
  <intent><action android:name="android.speech.RecognitionService"/></intent>
  <intent><action android:name="android.intent.action.TTS_SERVICE"/></intent>
</queries>
<service android:name=".voice.AgentVIS" android:exported="true"
    android:permission="android.permission.BIND_VOICE_INTERACTION">
  <intent-filter><action android:name="android.service.voice.VoiceInteractionService"/></intent-filter>
  <meta-data android:name="android.voice_interaction" android:resource="@xml/agent_vis"/>
</service>
<service android:name=".voice.AgentSessionService" android:exported="true"
    android:permission="android.permission.BIND_VOICE_INTERACTION"/>
<service android:name=".voice.AgentRecognizer" android:exported="true">   <!-- required stub -->
  <intent-filter><action android:name="android.speech.RecognitionService"/></intent-filter>
  <meta-data android:name="android.speech" android:resource="@xml/agent_recognizer"/>
</service>
<activity android:name=".voice.AssistActivity" android:exported="true">  <!-- belt-and-braces -->
  <intent-filter><action android:name="android.intent.action.ASSIST"/><category android:name="android.intent.category.DEFAULT"/></intent-filter>
</activity>
```
`res/xml/agent_vis.xml`:
```xml
<voice-interaction-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:sessionService="local.agentos.voice.AgentSessionService"
    android:recognitionService="local.agentos.voice.AgentRecognizer"
    android:supportsAssist="true"
    android:supportsLaunchVoiceAssistFromKeyguard="true"
    android:supportsLocalInteraction="true"/>
```
`AgentSessionService.onNewSession()` returns a `VoiceInteractionSession`. In `onShow()`, show the island card UI and start listening. The session window can draw over the lock screen.

**Bonus:** `onHandleAssist(AssistState)` and `onHandleScreenshot(Bitmap)` give us the foreground app's **view tree and screenshot without root**. The user has to enable "Use text from screen" / "Use screenshot" (`assist_structure_enabled`, `assist_screenshot_enabled`). `FLAG_SECURE` apps return nothing, which is a nice security point.

### Commands (for the team; root/adb; record old values first so you can restore)
```sh
settings get secure assistant; settings get secure voice_interaction_service; settings get secure voice_recognition_service
settings get global power_button_long_press                         # save for restore
cmd role add-role-holder --user 0 android.app.role.ASSISTANT local.agentos.app
settings put global power_button_long_press 5                       # 5 = assistant
dumpsys voiceinteraction | head -40                                 # confirm our VIS is bound
cmd voiceinteraction show                                           # open a session from shell (root-trigger fallback)
cmd package query-services -a android.speech.RecognitionService     # find Google's recognizer component
# restore:
cmd role add-role-holder --user 0 android.app.role.ASSISTANT com.google.android.googlequicksearchbox
settings put global power_button_long_press <saved>
```
(`cmd role` syntax, `cmd voiceinteraction show` and `power_button_long_press` values are confirmed from the AOSP 13 sources above. If our app "doesn't qualify", there is `cmd role set-bypassing-role-qualification true`. Use it only for debugging.)

**Trigger fallbacks, in order:**
1. A floating island mic button (always works).
2. A Quick Settings tile (the lab already has one).
3. The gesture-nav corner swipe, which also launches the assistant. The Moto toggle location is unverified.
4. Root: a `getevent -l` watcher for volume-down long-press that runs `cmd voiceinteraction show`.

**Always-on wake word:** don't use `AlwaysOnHotwordDetector`. It needs privileged `CAPTURE_AUDIO_HOTWORD` / `MANAGE_HOTWORD_DETECTION`, i.e. a priv-app via Magisk, which is out of scope. If wanted, run sherpa-onnx KWS in a mic foreground service. The green mic indicator stays on, which is a feature for the transparency pitch.

---

## 3. STT, TTS, barge-in

### Speech-to-text options
| Option | Streaming? | Offline? | Notes |
|---|---|---|---|
| **Realtime (subscription, via app-server)** | yes (server VAD) | no | Best quality and latency. Transcripts arrive as `…/transcript/delta`. |
| **Android `SpeechRecognizer`** | partials (`EXTRA_PARTIAL_RESULTS`) | `createOnDeviceSpeechRecognizer` / `isOnDeviceRecognitionAvailable` (API 31+); `checkRecognitionSupport`/`triggerModelDownload` (API 33) | Availability on Moto is **unverified**. The online Google recognizer is fine for a demo. Must run on the main thread. Plays a start beep. Errors include `ERROR_RECOGNIZER_BUSY`. **Gotcha:** once we are the VIS, the system's default recognizer setting may point at our stub, so always use `createSpeechRecognizer(ctx, ComponentName(<Google pkg>, <service>))`. Find the component name with the `query-services` command above. |
| **sherpa-onnx** v2.0.1 (2026-10-26) | yes (streaming zipformer) | yes | Also provides Silero VAD, keyword spotting, and Piper/Kokoro TTS, with a Kotlin API and prebuilt Android libs. https://github.com/k2-fsa/sherpa-onnx. Best offline choice for a "private mode" demo. |
| **whisper.cpp** v1.9.5 | no (chunked) | yes | Has an Android example. On the SD480+-class CPU (2×A76), use `tiny.en`/`base.en` only. Expect about 1–3 s per utterance (estimate). Good for "transcribe after release", not live. https://github.com/ggml-org/whisper.cpp |
| OpenAI `gpt-live-transcribe` / `gpt-realtime-whisper` | yes | no | $0.017/min, API key. |

### Text-to-speech
- **Realtime path:** the model speaks. Nothing to build.
- **Cascade path:** Android `TextToSpeech` (Google engine). Split the LLM stream into sentences and call `speak(..., QUEUE_ADD, ..., utteranceId)` per sentence. Track progress with `UtteranceProgressListener`. Cancel with `stop()`. Local voices start speaking fast (about 150–400 ms, estimate).
- Cloud: `gpt-4o-mini-tts` ($12/1M audio-out tokens, API key). Offline premium voice: sherpa-onnx Kokoro, which is CPU-heavy on 3.5 GB RAM. Only for the stretch goal.

### Barge-in (letting the user interrupt)
Analogy: barge-in is like talking over someone on the phone. The assistant must **hear you over its own voice** (that's echo cancellation), then **stop talking right away**.
- **Realtime path:** server VAD interrupts the model's reply automatically. Our job is echo control. Use hardware AEC/NS in `JavaAudioDeviceModule`, use the call audio mode, and keep speaker volume moderate. **In a noisy demo hall, use push-to-talk** by turning the local audio track off while not held (`track.setEnabled(false)`) so crowd noise and echo can't trigger replies. Keep full-duplex as the "wow" moment in a quiet room.
- **Cascade path:** during TTS, run Silero VAD (sherpa-onnx) on an `AudioRecord(VOICE_COMMUNICATION)` with `AcousticEchoCanceler`. More than 200–300 ms of speech means `tts.stop()`, cancel the LLM turn (`turn/interrupt`), and start STT. Simplest version: pressing the button again means stop and listen.

---

## 4. Latency budget (targets; numbers are estimates to measure, not measured)

| Stage | Realtime (subscription) | Cascade fallback |
|---|---|---|
| Trigger → UI + earcon | < 200 ms (VIS session show) | < 200 ms |
| Session connect | **1–2 s cold** (SDP via app-server + ICE + DTLS). Hide it by pre-connecting when the island opens, and play the earcon right away. | 0 (recognizer start ~200–400 ms) |
| End of speech detected | server VAD ~300–600 ms | SpeechRecognizer endpoint ~500–800 ms |
| First audio of reply | ~0.5–1.0 s after end of speech | STT final 0.2–0.5 s + LLM first token 0.5–3 s (Codex turns with reasoning can be slower; use a fast model/low effort) + TTS 0.2–0.4 s → **≈ 1.5–4 s** |
| Phone action (tap/type) | Delegated Codex turn + our gate: 2–10 s. Cover it with a spoken filler ("On it…", via `delegationAckFiller` or our own `appendSpeech`) and island progress. | same |

Rule of thumb: people accept about 1 s before a reply starts. Anything longer needs a filler sound or words.

---

## 5. Recommended approach (hackathon)

**Primary — "Subscription Live Voice":**
1. **Spike (1.5 h, laptop only):** run `codex` TUI `/voice` with the ChatGPT login to prove the account works (15 min). Then build a tiny desktop-Chrome page plus a ~60-line Node relay that sends SDP through `thread/realtime/start` on `codex app-server --listen ws://127.0.0.1:4500`. If you hear the voice reply, the backend is de-risked before any Android work.
2. **Android VIS skeleton (2–3 h):** VIS, session, stub recognizer, ACTION_ASSIST activity, role assignment by root, power long-press. The session UI reuses the island card.
3. **Android WebRTC + app-server client (4–6 h):** OkHttp WebSocket JSON-RPC to app-server (phone loopback, or laptop via `adb reverse`), libwebrtc peer, and transcript captions on the island.
4. **Tools through the gate (2 h, shared with the agent track):** `dynamicTools` on `thread/start`. Each `item/tool/call` goes to our permission card, then the executor, then the response. `clientManagedHandoffs:true` plus `appendSpeech` for confirmations.

**Fallback — "Cascade" (5–6 h, build it in parallel or first if the spike fails):** press-and-hold → `SpeechRecognizer` (explicit Google component, partials shown live) → text turn (app-server `turn/start`, or Claude) → sentence-streamed `TextToSpeech` → stop on button press. Works with no OpenAI realtime access. Add sherpa-onnx offline STT as a "private mode" switch only if there is time.

**Second fallback (API key):** GPT-Live over WebRTC with client delegation, or `gpt-realtime-2.1-mini`, with an ephemeral key minted on the laptop. Same Android WebRTC code; only the SDP exchange endpoint changes.

---

## 6. Risks
- **Experimental API churn:** every `thread/realtime/*` method and `dynamicTools` is `#[experimental]`, and Codex ships alpha builds daily. Pin the version and generate the schema from that binary.
- **Backend policy:** ChatGPT-backed realtime is built for Codex clients. It could be rate-limited, gated by plan or client, or count heavily against Codex usage. Have the cascade working before the demo.
- **Demo-floor audio:** echo and crowd noise break full-duplex. Use push-to-talk on stage, a wired or BT headset if allowed, and a pre-connected session.
- **Network:** WebRTC needs UDP. Venue Wi-Fi may block it; ICE falls back to TCP/TLS on 443 if the server offers it (unverified for this backend). Bring a phone hotspot.
- **Moto quirks:** the power long-press may be hard-wired to Google, and on-device SpeechRecognizer may be missing. Mitigate with the island button / QS tile and the online recognizer.
- **RAM (3.5 GB):** app-server on the phone, WebRTC, and the launcher together are fine. Adding local models too (whisper/sherpa TTS/local LLM) risks LMK kills. Keep local models optional.
- **Security (pitch material):**
  - *Voice prompt injection:* any audio, like a YouTube video, can "speak" commands. Fix: push-to-talk equals physical intent, and sensitive actions always need an on-screen tap.
  - *Lock screen:* the VIS can run over the keyguard. Allow read-only tools only while `KeyguardManager.isDeviceLocked()`.
  - *Token custody:* the ChatGPT token stays in app-server (root-owned dir); the APK never sees it. Use ws loopback plus capability token.
  - *Privacy:* audio goes to OpenAI in realtime mode. Make this visible with the mic indicator and island label, and offer an offline sherpa mode.
  - *Ethics:* tool demos only target our own test apps.

## 7. Estimate (hours)
| Item | h |
|---|---|
| Spike: TUI /voice + browser↔app-server WebRTC | 1.5 |
| VIS/assistant role/power-button + session UI hook | 2.5 |
| Android libwebrtc integration (+1–2 if no Gradle) | 3–5 |
| App-server JSON-RPC client, realtime events, captions | 2 |
| dynamicTools → permission gate → executor (shared) | 2 |
| Cascade fallback (SpeechRecognizer + TTS + text turn) | 3 |
| Barge-in/PTT polish, latency tuning, demo rehearsal | 1.5 |
| **Total** | **≈ 15.5–17.5** (primary only ≈ 11–13; fallback only ≈ 5–6) |

## Sources
- Codex source: https://github.com/openai/codex (files cited inline, commit 3f74ff2); releases: https://github.com/openai/codex/releases
- OpenAI WebRTC guide: https://developers.openai.com/api/docs/guides/realtime-webrtc.md
- GPT-Live delegation: https://developers.openai.com/api/docs/guides/live-delegation.md
- Pricing: https://developers.openai.com/api/docs/pricing
- SIWC: https://developers.openai.com/siwc/quickstart , https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference.md
- OpenAI Help (Voice in Work/Codex): https://help.openai.com/en/articles/20001275 (403 to fetcher; summary only)
- AOSP 13: roles.xml https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android13-release/PermissionController/res/xml/roles.xml ; VoiceInteractionManagerService https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerService.java ; shell cmd …/VoiceInteractionManagerServiceShellCommand.java ; PhoneWindowManager https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/policy/PhoneWindowManager.java ; RoleShellCommand https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android13-release/service/java/com/android/role/RoleShellCommand.java
- FGS while-in-use exemptions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Motorola power button: https://help.motorola.com/hc/7162/16/global/en-gb/T2005233848.html
- sherpa-onnx: https://github.com/k2-fsa/sherpa-onnx ; whisper.cpp: https://github.com/ggml-org/whisper.cpp ; Stream WebRTC Android: https://github.com/GetStream/webrtc-android ; Pipecat Android OpenAI WebRTC: https://docs.pipecat.ai/api-reference/client/android/transports/openai-webrtc
