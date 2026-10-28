# keyboard/ — `local.agentos.keys`, the AgentOS keyboard

A full Android keyboard (`InputMethodService`) with an AI row where QuickType sits. It works in any app.
Think of QuickType, but the three words are three *replies*, written from the conversation you are looking at.

```
 any app's text field ──InputConnection──▶ Keys (IME)
                                            ├─ screen.read  POST 127.0.0.1:7777/rpc  (AgentOS app: what is on screen)
                                            └─ suggest      ws://127.0.0.1:8787      (agentd: 3 suggestions, ~1.5 s)
```

## Files (`src/local/agentos/keys/`)
| File | Job |
|---|---|
| `Theme` | **Every** color, radius, size, spacing, font and timing. Restyle here only. Current values: A2 "Calm Glass iOS Dark" (`design/mockups/A2-calm-glass-ios-dark.html`). |
| `Keys` | The service: field lifecycle, privacy rules, debounced requests, inserting a suggestion, enter action, double-space period. |
| `KeyboardView` | The QWERTY grid drawn on one Canvas: 3 pages (letters, `123`, `#+=`), shift / caps lock (double tap), backspace repeat, multitouch, press animation, haptics. `Preview` is the iOS key bubble. |
| `Strip` | The AI row: `[dot] [3 suggestions] [mode pill]`. Tap the dot for the context detail line; tap the pill to cycle Reply → Rewrite → Fix. Shimmer while loading. |
| `Agent` | `screen.read` over HTTP, agentd `suggest` / `write` / `image` over a 90-line WebSocket client, redaction, `ask` fallback. |
| `Writer` | The ✨ panel above the strip: type an intent → finished message with chips (Shorter · Casual · Formal · Redo · Translate) and Insert; also shows a generated image, "Catch me up" and "Translate last". |
| `Images` | ContentProvider that hands a generated PNG to the app (`content://local.agentos.keys.images/…`) for `commitContent` or the clipboard. |

Build: `AndroidManifest.xml`, `res/xml/method.xml` (IME declaration), `assets/manrope.ttf` (unused by A2; `Theme.FONT_ASSET` switches it on).

## Build, install, switch
```sh
keyboard/build.sh            # aapt2 → javac → D8 → zipalign → apksigner, 2.3 s, no SDK (same path as demo-apps)
keyboard/install.sh --set    # takes the device lock; install -r; copies both bearer tokens into the IME's private dir (root); ime enable + set
keyboard/install.sh gboard   # back to Gboard
adb shell "am start -n local.agentos.pigeon/.Main"   # demo target
```
Tokens: agentd writes its token into `/data/data/local.agentos.keys/files/agentd.token` at start (`AGENTD_CLIENT_PKGS`); `install.sh` also copies it and the tool server's token (`phone.token`) so a fresh install works without restarting agentd.

## What it does
1. **Typing.** iOS layout and shapes (43 dp keys, 6 dp radius, 1 dp bottom shadow), keys commit on finger-down, preview bubble, haptic tick, shift one-shot / double-tap caps, backspace repeats after 350 ms, double-space inserts ". ", auto-capitalisation for `TYPE_TEXT_FLAG_CAP_SENTENCES`. Enter follows `imeOptions` (`send`, `go`, `search`, `next`, `done`; else newline) and turns blue when it performs an action. Globe key returns to the previous keyboard.
2. **Suggestions.** On focus (after a 300 ms settle) and 700 ms after typing pauses, the keyboard builds a context: text before/after the cursor (500/200 chars), `EditorInfo` (package, hint, inputType), and the conversation on screen from `screen.read` (the layer that holds the text field, last 12 lines, Prompt-Guard-flagged nodes withheld). Emails, phone numbers, card-like and OTP-like digit runs are replaced by `[number hidden]` before anything leaves the keyboard. It sends `{type:"suggest", id, mode, before, after, field, screen}` to agentd and shows the 3 items. Same context = cached, no request. A new request cancels the old one (socket close → agentd abort). If agentd returns no items, it falls back to an `ask` turn that must answer with a JSON array.
3. **Modes.** Reply (3 messages; with a draft, 3 completions that start with it) · Rewrite (shorter / friendlier / formal) · Fix (spelling and grammar). Tapping a suggestion replaces the draft the keyboard read.
4. **✨ Write for me** (like Gmail's Help me write, but in the keyboard, so it works in every app). Tap ✨ on the strip: the keys now type into a small line inside the keyboard ("thank him for fixing the sink and ask when we can meet next"), return sends it. agentd's `write` turn (same warm, tool-less thread idea as `suggest`, prompt `WRITE_PROMPT`) returns one finished message that fits the app: Gmail gets a greeting (the recipient's name only if it is on screen), a body and a sign-off; Pigeon / Messages / WhatsApp get one short casual text. Chips: **Shorter · Casual · Formal · Redo** rewrite the shown result (not the field), **Insert** replaces the draft. Same screen context, redaction and untrusted-data rules as suggestions; never an em dash (prompt + filter in agentd and in `Agent.addAll`).
5. **✨ make an image of …** The same box: an intent that starts with "make / draw / generate an image / picture of" runs agentd's `image` request, which spawns one `codex exec` with `features.image_generation=true` (agentd's long-lived app-server keeps it off). The PNG comes back over the socket, is previewed in the panel and inserted with `InputConnection.commitContent` (the image-keyboard API) when the field's `EditorInfo.contentMimeTypes` lists `image/png` or `image/*`; otherwise the panel says so and **Copy** puts the image on the clipboard. Pigeon's field accepts images (`setOnReceiveContentListener`, shows a photo bubble); Gmail's body does not (text only → Copy).
6. **Translate.** In the result, **Translate** opens a language row (Spanish, French, German, …). The language is remembered per conversation (app + the top line on screen, SharedPreferences), so next time the chip is just **→ Spanish**. The dot's detail line offers **Translate** for the last incoming message (shown in the panel, not inserted).
7. **Catch me up.** In a thread with 8+ lines on screen, the detail line offers **Catch up**: one sentence on what was discussed and what is being asked of you.
8. **Send-safety.** Before anything is sent, the draft is checked on-device: a 6–8 digit run, a `password is …` / `pin:` phrase, or a Luhn-valid 13–19 digit card number. If it matches, the strip shows one calm amber line ("Login code? Real support never asks for one.") instead of suggestions, and **nothing leaves the keyboard** for that draft. Fields whose hint asks for a code / password / card (a login form) are exempt.
9. **Privacy.** Password fields (`TYPE_TEXT_VARIATION_PASSWORD`, `VISIBLE_PASSWORD`, `WEB_PASSWORD`, `TYPE_NUMBER_VARIATION_PASSWORD`) and fields with `IME_FLAG_NO_PERSONALIZED_LEARNING`: red dot, no suggestions, no key previews, nothing sent; the detail line says "Password field · nothing sent". The dot's detail line otherwise shows exactly what was used: `Pigeon · 6 lines · 1 hidden · 1 flagged · agentd 1.6s`.

## Measured on the phone (Moto G 5G 2023, agentd with Codex `gpt-6-luna`, effort low)
- Suggestion round trip, `suggest` path, warm: **1.0–2.0 s** (1.0, 1.3, 1.4, 1.7, 2.0 s); first request after an agentd thread rotation ~3.1 s; 4.3 s once while another session was driving Codex through agentd at the same time. `screen.read` adds ~0.9 s before that (included in the numbers above the dot shows; the log line `suggest via agentd in N ms` is agentd only).
- Build 2.3 s; install ~5 s.
- **Write for me** (`write` path, gpt-6-luna low): Pigeon 1.3 s warm (3.0–3.3 s on the first call after an agentd restart); Gmail email 2.4 s. Chips: Formal 1.5 s, Translate 1.2 s, Translate last 1.3 s, Catch me up 7.5 s (one sample). screen.read is reused for 20 s, so a chip costs no extra screen read.
- **Image:** 37 s (Gmail) and 51 s (Pigeon) on the phone, 28–58 s on the Mac; 1.2–2.3 MB PNG. Send-safety: instant, a regex, no request.

## Screenshots (`shots/`)
`kb-01-write-typing` ✨ box · `kb-02-write-result` result + chips · `kb-03/04-gmail` email written and inserted · `kb-05-send-safety` login code line · `kb-06-detail-actions` Translate / Catch up on the detail line · `kb-07-catch-me-up` · `kb-08-translate-last` · `kb-09..11-image` generated, inserted into Pigeon as a bubble · `kb-12-image-copied` Gmail (text only) → clipboard.
`01-reply-prefetch` replies ready on focus · `02-context-detail` dot tapped · `03-key-preview` holding **s** · `04-complete-draft` completions for "Sounds g" · `05-rewrite` · `06-fix` · `07-inserted` suggestion tapped · `08-symbols` · `09-password-field` Chrome password input, strip off.

## Known limits
- Image generation is ~40–60 s and one `codex exec` process per image (no streaming preview). Google Messages couldn't be tested (it refuses a conversation with a placeholder number); Gmail's body is text-only for `commitContent`, so there it falls back to Copy.
- The ✨ box is a single line (long intents scroll); no dictation into it yet.
- Send-safety is a regex: 4–5 digit codes are not caught (too many false positives), and it looks at the draft only, not at pasted images.
- Cells are ~105 dp wide, so long suggestions get an ellipsis (ask agentd for short ones first; see `NEEDS.md`).
- No swipe typing, no autocorrect, no emoji page, no long-press alternates.
- `screen.read` costs ~0.9 s and returns Pigeon's hidden list layer too; the keyboard picks the layer with the text field (`NEEDS.md`).
- The `ask` fallback is slower (a full agent turn) and only used when `suggest` gives nothing.
