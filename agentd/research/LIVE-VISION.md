# Live vision for the voice assistant (2026-10-27)

**Goal:** GPT Live can see. (a) Camera: "what plant is this?", "read this sign". (b) Screen: "what's on my screen?", "help me answer this".

**Result: both work on the real phone, behind `VOICE_VISION` (off by default).** GPT Live itself can't take images on our path, so a small vision model is its **eyes**. Think of a friend on a call who describes what they see to someone who can't look: the eyes look, write two sentences, and the voice says them.

- Camera answers start **0.4–1.3 s** after the user stops talking. That is as fast as a plain question (0.8–1.1 s), because the frame is described while the user is still talking.
- Screen answers: "Let me look." at **1.0–2.3 s**, then the answer at **3.9–6.4 s**.
- Images sent: screen **15–20 KB**, camera **21–125 KB** (JPEG).

## 1. Research

### What OpenAI documents (public Realtime API)
- `gpt-realtime` takes images as a content part of a user message: client event `conversation.item.create` with `{type:"input_image", image_url:"data:image/{format};base64,…"}`. The docs list no size or format limits for Realtime images. ([Realtime conversations guide](https://developers.openai.com/api/docs/guides/realtime-conversations); [community thread with the same JSON](https://community.openai.com/t/realtime-model-image-input/1355688))
- General image limits ([vision guide](https://developers.openai.com/docs/guides/images-vision)): PNG, JPEG, WEBP, non-animated GIF; up to 512 MB per request and 1,500 images.
  - Patch-based models: high detail allows at most 2,500 patches of 32 px; low detail fits the image in 512×512.
  - Tile-based models (gpt-4o): low detail is 85 tokens; a 1024² image at high detail is 765 tokens.
  - Our 461×1024 screenshot is 15 × 32 = 480 patches.
- **Video is not a documented input.** webrtcHacks found that a WebRTC video track is accepted, but "whenever you ask the model to look at something, it takes a snapshot and charges you for an image input". An OpenAI engineer advised "high-resolution with low FPS", and 1 fps is the practical floor. ([webrtcHacks](https://webrtchacks.com/how-openai-does-webrtc-in-the-new-gpt-realtime/))
- **So: sample frames, don't stream.** Send one frame when the user speaks, not a continuous 1 fps feed.
- **Cost (public API, for reference):** image input is listed at about $5 per 1M tokens for gpt-realtime ([OpenAI pricing](https://developers.openai.com/docs/pricing); the snippet was partial, so treat this as unverified). One look of about 500–1,000 tokens costs well under a cent.
- On our path there is no per-call bill: the eyes are a normal Codex turn on the ChatGPT plan and count toward its Codex usage limit.

### What our path allows (Codex app-server → ChatGPT backend, `gpt-live-1-codex`, realtime v3), tested 10-27
Tested with probes sent on the page's `oai-events` data channel and the server's own error replies (Mac, codex 0.164):

| Tried | Server said |
|---|---|
| `conversation.item.create` + `input_image` | `Invalid value: 'conversation.item.create'. Supported values are: 'session.update', 'output_audio.playback.play', 'session.feedback', 'input_audio.pause', 'input_audio.resume', 'session.context.append', 'response.item.create', 'response.create', 'delegation.context.append', 'delegation.function_call_output.create', 'output_audio.send_dtmf_event', and 'session.close'.` |
| `session.context.append` with text + image | `array too long … maximum length 1`; with only the image part: `Missing required parameter: 'content[0].text'` (text only) |
| `response.item.create`, `delegation.context.append` with `input_image` | `Missing required parameter: '…content[0].text'` (text only) |
| `delegation.function_call_output.create` with an image array | `Invalid type for 'item.output': expected a string` |
| `session.update` with `video` / `input_video` / `vision` / `input_image` | `Unknown parameter` (`instructions` can't change after start) |
| A WebRTC **video track** in the offer | Accepted (`m=video … H264 recvonly`) and frames flowed (92 frames, 512×320), but the model still made up the sign text ("Welcome to Las Vegas, Nevada", then "No Parking Any Time") |

App-server has no image method either. Its realtime methods are `appendAudio`, `appendText`, `appendSpeech`, `start`, `stop` and `listVoices`.

**Conclusion:** on the ChatGPT-auth v3 path, every content field the client can send is text-only, and video is ignored. GPT Live can't see directly. A vision-capable Codex model can: `turn/start` takes `{type:"image", url:"data:…"}` input, measured on the Mac with the warm thread:

| Eyes model (effort low) | Sign, cold | Plant, warm | Sign, warm | Correct? |
|---|---|---|---|---|
| `gpt-6-luna` | 2.7 s | 1.3 s | 1.3 s | yes (exact text; "monstera") |
| `gpt-6.1-terra` | 3.1 s | 1.5 s | 1.6 s | yes |

We picked `gpt-6-luna` (`VISION_MODEL`).

## 2. What was built

```
 camera (launcher voice sheet) ──voice.frame (768 px JPEG, on each orb press)──┐
                                                                              ▼
 GPT Live ◄── [CAMERA] note (silent context) / [BACKEND] answer ── agentd voice.js ──► eyes: Codex gpt-6-luna (image + words)
     │ "what's on my screen?" (transcript or hand-off)                         ▲
     └────────────────────────────────► screen.capture (broker, Look.java) ─────┘  private JPEG, never stored
```

- **Camera** (`voice.js` `#describe`): when the camera opens, agentd tells GPT Live it can't see and must wait for `[CAMERA]` notes instead of guessing (`CAMERA_NOTE`). This stopped the made-up answers seen above. It also warms the eyes thread.
  - Each orb press sends one frame (`voice.frame`). The eyes describe it at once, naming the thing as specifically as they can and copying text exactly. The description goes in silently (`appendText`, developer role).
  - If the user finished asking first, agentd asks GPT Live to answer from the note (`#answerFromCamera`, once).
  - A hand-off that is clearly about the picture (`CAMERA_Q`) is answered from the note, never started as a task.
- **Screen** (`#lookOnce` / `#look`): runs on the user's own words matching `LOOK` ("on my screen", "help me answer this") or a hand-off that `route()` sends to `look_screen`. GPT Live rarely hands off "help me answer this" by itself, so agentd acts on the transcript. `SCREEN_NOTE` makes it say "Let me look." and wait.
  - agentd calls the broker's `screen.capture`, gives the JPEG to the eyes with the question, and says the answer (`[BACKEND] Looked at the screen …`).
  - The image never reaches GPT Live or the page.
- **Broker `screen.capture`** (`android/src/local/agentos/Look.java`, documented in android/README.md):
  - **Never captured:** money, password and vault apps (`bank|pay|wallet|…|vault|authenticator`), `Guard.forbidden` surfaces (Settings, System UI, AgentOS, Magisk, installers), FLAG_SECURE windows, or anything while a task holds the lease (a read would also renumber the worker's node ids).
  - **Painted black:** password fields (also vault-filled ones), our own windows and every accessibility overlay (island, approval cards, sign-in offer). Verified on github.com/login: the password box was black.
  - **Prompt Guard flags:** a flagged screen is still captured but marked `flagged`, because PG2 flagged GitHub's plain login page. The eyes are told its text is a likely injection and must never be followed.
  - **From the home screen:** if the launcher (voice sheet) is in front, the last app the user had open comes back first. The user sees exactly what was looked at, and the voice keeps talking from the background.
  - **Logging:** every capture and every refusal is one audit line (`tool:"screen.capture"`), shown in Activity as "Looked at your screen in Chrome · private parts hidden" or "Didn't look at Settings · …". There are no pop-ups.
- **Launcher** (`web/`, `Home.java`, manifest): an eye button in the voice sheet (shown only when agentd says `vision:true`) turns on the back camera, shown dimmed behind the glass.
  - Each orb press sends one frame, and a "Looked" step appears.
  - The WebView now grants camera capture for our origin only, and asks for `CAMERA` the same way as the mic.
  - The UI is minimal on purpose; Fable can restyle it.
- **Bare client** `agentd/voice.html`: a Camera button, a frame at speech start, and `?img=<url>` to stand in for the camera in tests.

### Flag and settings
- `VOICE_VISION=1`, or the file `~/.agentos/vision.on`, turns it on. **Default off.** Off means: no camera button, `voice.camera`/`voice.frame` are ignored, and `route()` never picks `look_screen`.
- `VISION_MODEL` (default `gpt-6-luna`).
- `bench/up.sh` passes both through.

## 3. Measured on the phone (Moto G 5G, 10-27 night)
Setup:
- A separate test agentd on the phone (:8788, `bench/up.sh`, this tree, `VOICE_VISION=1`, reviewer off), with the eyes running through the phone's Codex 0.163 and the real broker.
- Questions were TTS audio played by headless Chrome on the Mac through `adb forward`, the same method as the earlier voice numbers. Camera frames were test images (a park sign and a monstera photo).
- Times are from the end of the user's speech.

| Question | First spoken word | Answer starts | Inside | Image sent |
|---|---|---|---|---|
| "What is the capital of France?" (no image) | 0.84, 1.12 s | same | — | — |
| Camera: "What does this sign say?" | 0.36, 1.34 s | same turn, exact text | eyes 1.2–2.2 s, finished before or just after the end of speech | 21 KB (768×480) |
| Camera: "What am I looking at?" | -0.90, 0.36 s (started in a pause) | same turn ("a big Monstera…") | eyes 1.4 s | 125 KB (768 px photo) |
| Screen, Chrome in front: "What's on my screen?" | 1.5–2.2 s ("Checking now.") | 3.9–4.7 s | hand-off 1.0–1.5 s · capture 0.1–0.6 s · eyes 1.4–2.1 s | 20 KB (461×1024) |
| Screen, from the home screen | 2.3 s | 5.6 s | capture incl. app switch 1.8 s | 20 KB |
| "Help me answer this." from home (Pigeon chat) | 1.0–1.4 s ("Let me look.") | 4.4–6.4 s ("You could reply to Alex: 'Sounds good, talk to you later!'") | capture incl. switch 1.4–2.0 s · eyes 0.8–1.8 s | 14.5 KB |

- The broker's screenshot itself takes 62–131 ms. The rest of a capture is the root FLAG_SECURE check and Prompt Guard.
- Before the describe-first change (Mac), a camera question gave "Let me look." at 1.6 s and the answer at 5.4 s. Describing the frame while the user talks removed about 4 s.

## 4. 30-second demo
Setup: `touch ~/.agentos/vision.on` and restart agentd. Have a plant and a printed sign ready. Have a chat open with a question in it, from a test or friendly contact that's OK to show; Pigeon works. Never a real send.

| Time | Do | Phone says / shows |
|---|---|---|
| 0:00 | Home → mic. Tap the eye: the camera shows behind the glass. Point at the plant, hold the orb: "What plant is this?" | About 1 s later: "That's a monstera, a Swiss cheese plant." |
| 0:08 | Point at the sign: "What does this say?" | Reads it word for word. |
| 0:14 | Tap the eye off. Open the chat app, press Home, mic, hold: "Help me answer this." | "Let me look." The chat comes back to the front (you see exactly what it looked at), then: "You could say: 'Sounds good, see you Friday!'" |
| 0:24 | Command center › Activity | "Looked at your screen in Pigeon". Line to say: "Passwords are blacked out, banking apps are never captured, and every look is logged." |

## 5. Deploy (not done on the live phone: it needs the orchestrator's go-ahead)
- **Broker:** `android/build/agentos.apk` with `screen.capture` **is installed** on the phone. Other callers are unaffected.
- **agentd:** copy `voice.js`, `config.js`, `server.js`, `providers/codex.js` (and `voice.html`) to `~/agentos/agentd`, `touch ~/.agentos/vision.on`, then `pkill -f "^node server.js$"` (the boot loop restarts it).
  - Note: `server.js`, `config.js` and `codex.js` in this tree also hold another session's uncommitted changes (micro-apps / remote MCP).
- **Launcher:** `launcher/install.sh` (now also grants `CAMERA`). It was built but **not installed**, because its bundle also carries the other session's uncommitted micro-apps UI.
  - So the camera button was not run on phone hardware. Camera timings above use test images through the same agentd path.

## 6. Open issues and risks
- **Prompt injection through the screen:** the eyes read text written by others, and their answer is spoken. GPT Live's only tool is the hand-off, and Prompt-Guard-flagged screens carry a warning. Even so, a crafted message could make the spoken answer suggest something. Don't wire a "send that reply" shortcut from a look without the normal approval card.
- **memory sync blocks agentd:** on a fresh state dir, `memory sync` (calendar alone 6.3 s, synchronous) froze the event loop. One hand-off waited 9 s. This is not vision code, but the live agentd syncs too. Worth moving off the main thread.
- GPT Live once added a stray line after an answer ("Stinger's go home, champ.") during silence.
- Refused looks count in Home's "N actions blocked today" (decision `refused`).
- Test leftovers: up to two Chrome tabs opened on the phone (en.wikipedia.org/wiki/Monstera_deliciosa, github.com/login). Nothing was typed or sent.
