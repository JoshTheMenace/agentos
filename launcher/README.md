# launcher/ — `local.agentos.home`, the AgentOS home screen

Design: A2 · Calm Glass iOS Dark (`design/mockups/A2-calm-glass-ios-dark.html`, tokens in `design/A2-tokens.md` and `web/theme.css`).

Think of a picture frame. The frame is a tiny native HOME activity (`src/local/agentos/home/Home.java`); the picture is `web/`
(HTML/CSS/vanilla JS, bundled into the APK as assets). The frame only hands the page a few things: the app list and launching,
haptics, system settings, the next calendar event, the agentd token, and a GET on agentd. Tool-server calls go through agentd's
`rpc` passthrough, except the **owner line**: `Native.phone(id, method, params)` posts to `:7777` with `files/phone.token` from this app's own uid, which the broker treats as the owner's tap (Blockers taps, Passwords list/delete). agentd and the model connect from Termux's uid and never get that. Approvals never happen here: the approval card drawn by `local.agentos`
(tap or voice; fingerprint only for money, installs and saved passwords) is the gate.

```
web/index.html + app.js ──ws://127.0.0.1:8787 (agentd: ask, voice.*, suggestions.watch, rpc {audit.tail, approval.list, firewall.*, task.*, trackers.list, app.mod.list/remove}, forwarded broker events)
                        ──Native.get ──▶ Home.java ──GET 127.0.0.1:8787/memory/graph.json
```

No polling: one `audit.tail` + `approval.list` snapshot on connect/resume, then `task.state` (lease, real `expires`, steps, paused),
`audit.append` (recent activity), `approval.shown/resolved` and `guard.flag` keep the home, command center and permissions live.

## Screens
| Page | What |
|---|---|
| Home | clock, status line, **Now** (the live task: goal, current step from `tool.request`/`audit.append`, a sliding progress bar, pause/stop; tap opens the sheet; a task started from home lingers 20 s with its answer), **For you** (suggestions from agentd's `suggestions` feed plus "Leave for …" from the real next calendar event; one tap sends the item's `goal` as an `ask`, or opens a view), the next calendar event (tap opens Calendar), "What should I do?" bar, squircle dock of real apps. No dummy data; empty state is one line, "All caught up". Swipe up: app drawer with search. Swipe down: notification shade. Swipe left: command center, again: memory. |
| Ask sheet | sends `ask` to agentd; streams the reply, tool steps (orb → done/denied) and guard alerts. Approvals are not here: on `approval.shown` the header says "Waiting for your approval", on `approval.resolved` "Approved" / "Auto-approved ✓" / "Denied". The native card is the only gate. |
| Voice | Siri-like rotating edge + glows, transcript, hold-the-orb push-to-talk over WebRTC (`voice.start` offer/answer), tool steps for delegated requests |
| Command center | **Protected today** (trackers blocked from `trackers.list`, scam messages caught = `guard.flag`, actions stopped = blocked/denied, approvals · auto; all from today's audit, `audit.tail n:500`; after a **demo reset** only from the last `demo.reset` line: hold the title, then tap "Tap to start fresh" → `audit.demo_reset` over the owner line. A view filter, nothing deleted; ads blocked keeps counting), **Tasks** (running workers + upcoming scheduled tasks from `schedule.list`, x = `schedule.cancel`), **Model** (Codex default / Claude / On-device with an offline badge; sent as `provider` on each `ask`), **Activity** (today's audit in plain language: "Tapped "Send" in Pigeon", "Scam message caught in Pigeon · hidden from the agent", "Unlocked for your scheduled task", "Looked up Dan · Contacts"; Caught / Blocked / Denied / Auto / Approved tags, no seconds; this is the calm log that replaces red pop-ups), then a 2-column tile grid: Blockers, Passwords, Mods, Memory, Plugins, Permissions, Settings with live notes. "Stop all" is grey until something runs. |
| Blockers | per app: trackers seen and blocked (`trackers.list` → `{apps:[{pkg,label,trackers:[{domain,company,blocked,hits}]}]}`), green = blocked, orange = open, "Block", "Block all" (bar) and "Unblock" go over the **owner line** (`Native.phone` → `:7777` from this app's own uid → `trackers.block/unblock`): the tap is the approval, no card, no fingerprint. Only tracker-list names can be blocked (the broker checks). Refreshes on `tracker.blocked`. Legend under the title (green = blocked, orange = still sending, numbers = requests seen). Empty state: "No trackers seen". Reached from the command center or Permissions › Network. |
| Passwords | the vault (`vault.list` over the owner line → `{entries:[{id,label,apps,username}]}`, never the password): app icon, label, username, "Fingerprint to use · only in <app>", Delete (two taps, `vault.remove`). "Add password" opens `local.agentos/.VaultAddActivity`, a native screen: the password is typed and encrypted there, never in this WebView. Refreshes on `vault.changed` and on resume. |
| Mods | buttons agents added to apps (`app.mod.list` → `{mods:[{id,package,app,label,shown}]}`): app icon, label, Remove (`app.mod.remove {id, by:"settings"}`, no approval). Refreshes on `mod.changed`. Empty state: "Ask to add a button to any app". |
| Plugins | The owner's switches for groups of the agent's direct-API tools (agentd `plugins.js`): name, one-line summary, toggle; "Always on" lists the core. The list comes from agentd (`plugins.list`); a toggle goes over the owner line to the broker (`plugins.set`, launcher uid only), so agentd and models can't flip one. Off = the agent loses those API tools but may still use the app on screen. Mods and the Network toggles now also use the owner line first, so they keep working while their plugin is off. Shots: `shots/plugins.png`, `shots/plugins-one-off.png` |
| Permissions | grants (lease apps, minutes left, Revoke), per-app network toggles (`firewall.set`, tap approval; per-domain `name:` rules show as "6 trackers blocked", only a rule without `dest` reads "Blocked"), memory sources (consent ledger, read-only), the never-list |
| Memory | force-laid-out graph of `/memory/graph.json` (tap a node for a one-line detail) and sources |

## Build, install, test
```sh
launcher/build.sh            # javac + D8 + aapt2, ~3 s -> launcher/build/home.apk (web/ bundled as assets)
launcher/install.sh --set    # takes the device lock; installs, copies the agentd token into the app's private dir (root), grants mic + calendar, sets default home
launcher/install.sh          # same without changing the default home
launcher/install.sh moto     # restore Moto's launcher
```
Restore Moto's launcher by hand: `adb shell cmd package set-home-activity com.motorola.launcher3/com.android.launcher3.CustomizationPanelLauncher`

Live editing from the Mac (no rebuild): `launcher/install.sh dev` then `python3 -m http.server 5173 -d web`; the page loads over
`adb reverse`. `launcher/install.sh nodev` goes back to the bundle. Inspect the WebView from `chrome://inspect`.

Token: `install.sh` copies `~/.agentos/agentd.token` (Termux) into `/data/data/local.agentos.home/files/` as root; agentd does the
same at start (`local.agentos.home` is in the default `AGENTD_CLIENT_PKGS`). There is no `:7777` token in this app.

## Home polish (climb-a, 10-27 PM)
- Portrait-locked (manifest); auto-rotate was turned off on the demo phone. `--l3` lifted .3 → .38 for projector contrast (brighter only; backward compatible).
- **Try chips** above the ask bar and inside the empty ask sheet: prompts that prefill the sheet (scheduled task, trackers, reply, sign-in) and an on-device model toggle. Prompts, not data.
- **Task card**: optimistic card the instant a suggestion/ask starts, pretty tool names (`TOOLN` in app.js; new agentd tools fall back to "Snake case → Words"), elapsed time + step count, orange glow while acting, check/x + bar fill on finish, heavy haptic, 45 s linger (survives a trip through another app via `task.list`). Tapping it opens the sheet, which is the task's transcript.
- **Scheduled cards**: blue calendar tile, "Tomorrow 11:00 AM · scheduled" (from `at`).
- **Moments**: approved `notifications.reply/autoreply`, `vault.fill`, `trackers.block`, `schedule.created`, `app.mod.add` audit lines show a 45 s "done" card on Home.
- **Ask sheet**: typing dots while thinking, 17 px answers, "Done · 6s". **Voice**: "Try saying" examples until the first transcript; the live task card sits above the orb.
- Shots: `shots/climb-a/` (before/after pairs; `.tools/task.mjs` starts a task from the Mac over `adb forward tcp:18787 tcp:8787`).

## Micro-apps (`MicroApp.java`, `web/apps/`, agentd `apps.js`)
"I want to track my workouts" → the agent's `build_app` writes one HTML file → it appears under **My apps** on Home (2-column widget cards: emoji, name, the live one-line summary the app left, e.g. "0 of 8 glasses today"; tap opens, hold 600 ms shows Remove = `apps.remove` over the WebSocket, owner only) and as a row in the drawer. The task card streams "Designing → Building · 4 KB → Installing".
- **Sandbox:** each app opens full screen in `MicroApp` (own task, so it sits in recents like a real app) in a separate WebView with its own origin `https://<id>.agentos.app`, a CSP of `default-src 'none'` (inline script/style, `/ui.css`, `/ui.js`, the font; `connect-src 'none'`), `setBlockNetworkLoads`, no file/content access, alert/confirm swallowed, no `Native` bridge. Its only bridge is `AgentOSApp.summary(text)` (the home widget line, `microapps` prefs), `haptic(kind)`, `close()`. Storage = the origin's own localStorage. Probed on the phone (`shots/apps-*`): fetch/WebSocket/img to example.com, agentd :8787 and the broker :7777 all blocked by CSP, `window.Native` undefined.
- The file comes from agentd `GET /apps/<id>/index.html` (bearer) and is cached in `files/apps/`, so apps still open when agentd is down. `web/apps/ui.css` + `ui.js` are the runtime every generated app links (A2 tokens, cards, buttons, lists, `AOS.bars` chart, `AOS.ring`, `AOS.load/save`, `AOS.summary`, `AOS.toast`).
- Shots: `shots/apps-building.png` (task card mid-build), `apps-home.png` (My apps with 5 apps), `apps-workout.png`, `apps-water-tracker.png`, `apps-dice-roller.png`, `apps-habit-streaks.png`, `apps-plant-care.png`.
- The Plugins toggle "Apps" needs the broker to know the id (`android Plugins.java` IDS has it in source; rebuild + install local.agentos for the switch to take; until then the plugin is simply on).

## Wake word (`Wake.java`, `src-wake/`, `wake.sh`)
Say the word, hear a soft two-note chime, and voice mode opens (the same `voice.start` as the orb). No agentd change.
- **Toggle:** Command center, under Model: “Jarvis” (Porcupine) or “Hey Agent” (fallback), a status line and a switch. With no engine it reads **Needs a Picovoice key** and the switch is greyed out; everything else works as before. **Indicator:** a small teal mic after the home status line ("Ready") breathes while listening and turns grey while paused. Android's own green mic dot shows too, and the ongoing notification "Listening for “…”" has **Turn off**.
- **How it listens:** a microphone foreground service in this app holds a 16 kHz `AudioRecord` only while the switch is on. It lets go of the mic (and says why) while voice mode is open (`Native.wake('voice:1'/'voice:0')` from `voiceStart`/`voiceStop`, 5 min cap), in a call (`AudioManager` mode), while another app records (`getActiveRecordingConfigurations`, the system "Hey Google" hotword source ignored), with the screen off or locked. It picks the mic back up after. On a hit: release the mic → chime → `startActivity(Home, WAKE)` → `app.wake()` → `voiceStart()`. Getting in front of other apps needs the overlay appop (`install.sh` sets `appops set local.agentos.home SYSTEM_ALERT_WINDOW allow`). Home `onResume` restarts the service if it's on (so after a reboot it comes back when Home first shows; a background restart would get a silent mic).
- **Build:** `build.sh` adds the engines when `~/.cache/agentos-wake` has them (it runs `wake.sh fetch` once; offline = it builds without them). That is Porcupine `classes.jar` + `libpv_porcupine.so` + model/keywords (Apache-2.0 AAR from Maven Central, no Gradle or AndroidX) and Vosk + JNA (`libvosk.so` 10 MB), arm64 only. APK 1.4 → 5 MB. `PvEngine` calls Porcupine's JNI class directly, which skips the Java wrapper (its static init needs OkHttp, used only for train-from-phrase).

### Porcupine: installing the key (the founder)
**Heads-up (checked 10-28):** Picovoice ended the free tier on **2026-06-30**. Free-tier AccessKeys stopped working, and new sign-ups get a **7-day free trial** for product teams (then enterprise only, no non-commercial plan). A trial key covers the demo. Afterwards it stops working, and the key-free fallback below is the way. The key is also checked online each time the engine starts (it retries every 30 s if there's no network).
1. Sign up at console.picovoice.ai and copy the AccessKey.
2. One command (it prompts without echoing, keeps the key out of the repo and shell history, takes the phone lock):
   ```sh
   launcher/wake.sh key
   ```
   It writes `/data/adb/agentos/porcupine.key` (root-only, 0600) and the app-private copy `files/porcupine.key` (owned by the app, 0600), then restarts Home. By hand: `printf %s "$KEY" | adb shell "su -c 'cat > /data/adb/agentos/porcupine.key; cp /data/adb/agentos/porcupine.key /data/data/local.agentos.home/files/; chown \$(stat -c %u /data/data/local.agentos.home):\$(stat -c %g /data/data/local.agentos.home) /data/data/local.agentos.home/files/porcupine.key; chmod 600 /data/data/local.agentos.home/files/porcupine.key; restorecon -RD /data/data/local.agentos.home/files'"`
3. Test: Command center › “Jarvis” › switch on → the status line says Listening and the home shows the teal mic. Say "Jarvis" → chime → voice mode. `launcher/wake.sh status` shows the files, the service and the last log lines (`logcat -s AgentOSWake`: `listening`, `wake word`, `paused (voice)`).
- **Built-in words** in the AAR: alexa, americano, blueberry, bumblebee, computer, grapefruit, grasshopper, hey google, hey siri, **jarvis** (default: rarely said on stage, unlike "computer"), ok google, picovoice, porcupine, terminator.
- **Custom "Hey Agent":** (a) `launcher/wake.sh train "hey agent"`: Porcupine 4 trains a keyword from text through its REST API (`POST https://rest.picovoice.ai/en/api/ppn`, `x-api-key`, `{"platform":"android","phrase":…}`, read from the SDK; not tried without a key). (b) Or in the Console: Porcupine › type "Hey Agent" › language English › Train › platform **Android** › download the `.ppn` › `scripts/withphone launcher/wake.sh _ppn ~/Downloads/hey-agent_en_android.ppn "Hey Agent"`. The engine uses `files/wake.ppn` over Jarvis, and the label reads "Hey Agent".

### Key-free fallback: Vosk "Hey Agent" (behind a flag)
`launcher/wake.sh vosk` pushes the 68 MB `vosk-model-small-en-us-0.15` to `files/vosk` and creates the flag `files/wake.vosk` (`wake.sh vosk off` removes both). Used only when there's no Porcupine key. Vosk (Kaldi) runs fully offline. A tiny grammar turns it into a keyword spotter: `["hey agent","hey","agent","the","a","an","[unk]"]`. The extra words are decoys, so "the agent replied" decodes as "the agent", not "hey agent". It fires on the partial result, about 0.6 s into the phrase.
- **Rule:** the partial result must be exactly "hey agent" for 300 ms (said after a pause, then a short pause), about 0.9 s from the start of the phrase. Looser rules ("contains", "starts with") fired twice on the phone during back-to-back speech ("Hey, how are you doing").
- Mac test (TTS clips, `vosk` Python, same model): 7/7 phrasings caught ("Hey Agent", "hey agent, what's on my calendar", "hay agent", "Hey Ajent", …). 0/5 false hits ("The agent replied to Sam", "today I'll show you an agent native phone", "Hey Siri", "Hey Jarvis", "Hey, how are you doing"). Without the decoys, "The agent replied" fired.
- With noise + a 50 ms echo mixed in: 20 dB SNR 7/7 caught, 0/9 false; **10 dB 3/7**, 0/9; 5 dB 3/7, 1/9. A loud room hurts this small model; Porcupine is the better engine on stage.
- **On the phone (10-28, 2 AM, Moto g 5G 2023):** TTS "Hey Agent" from the Mac speaker, about 1 m away, woke it from Home and from inside Pigeon (Home came to the front, chime, voice mode "Listening" with GPT Live connected). After closing voice it went back to `listening`. 12 back-to-back negative sentences gave 0 wakes with the final rule. The positive wasn't re-run over the air with the final rule (the Mac was muted for the night). Shots: `shots/wake/` (`nokey-center`, `vosk-listening-home`, `vosk-listening-center`, `vosk-woke`, `vosk-off-center`).
- **CPU / battery** (`launcher/wake.sh cpu 60`): Vosk listening = **~14–15 % of one core** (the `wake` thread; 8-core phone, so ~2 % of the CPU). Off = 0. Rough estimate from CPU, not a power meter: ~0.5–1 % battery per screen-on hour. It only listens with the screen on and unlocked, so a sleeping phone pays nothing. Porcupine wasn't measured (no key); Picovoice states it is several times lighter than ASR-based spotting.
- Not tested: pausing for a call (no calls on this phone), for another app's recorder, and screen off/locked (the phone wasn't locked overnight). The code paths are in `Wake.pauseReason()`.
- **Why not Android's SpeechRecognizer loop:** each session ends after one utterance, so it must restart all the time, with an earcon on many devices. That is full-vocabulary recognition (more CPU than a grammar spotter), and on this phone it goes to Google's on-device service (`com.google.android.as`), which isn't meant for always-on use. Vosk was quicker to make reliable.
- Left on the phone 10-28: the model + flag installed, **switch off** (so it can't fire during other agents' voice/bench runs). The founder turns it on in the Command center.

## Notes
- 3-button navigation. Edge to edge; the shell passes status/nav/IME insets to CSS variables.
- Only `transform`/`opacity` animate; no blur, no libraries. Inter is bundled (`web/fonts`, 350 KB).
- Suggestions: the page sends `{type:"suggestions.watch"}` on connect; agentd answers with `{type:"suggestions", items:[{id,title,detail,app?,goal?,icon?,view?}]}` now and whenever it changes (`agentd/home-suggest.js` is the stand-in until `context.js` lands; keyboard replies carry an `id`, the home feed doesn't). `icon`: msg | car | eye | shield | cal | spark; `view`: blockers | activity.
- Styling on the Mac without the phone: `python3 -m http.server 5173 -d web`, then `index.html#demo` (feed + a running task), `#demo-blockers`, `#demo-center`. Only in a browser, never on the phone.
- `shots/climb-b/` (2026-10-27 PM, secondary screens hill-climb): `01-center-before` → `01-center-after-final`, `05-center-scrolled-before` → `05-center-scrolled-after-loop7` (tiles), `02-blockers-before` → `02-blockers-after-loop6`, `07-perms-before` → `07-perms-after-final`.
- `shots/` (phone, 2026-10-27): `home` = `home-idle` (phone, nothing running: next event + "All caught up"), `home-feed-mac` (Mac `#demo` render: Now card + For you; the phone's agentd predates the feed), `center` (Activity log with tags, Blockers link), `blockers` (empty state), `memory`, `permissions`, `drawer`, `voice`, `ask-*`.
