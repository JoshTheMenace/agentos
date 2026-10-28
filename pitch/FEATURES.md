# AgentOS: every feature, for picking demo beats (10-28, from repo docs + final deploy)

**Entry:** V = voice · A = ask bar · G = Grok/Dots · I = inside a real app (keyboard, card, sign-in, mod).
G only works after remote setup (see 14). Every Grok task always needs a lease tap, can't schedule and can't unlock the phone.
**Status:** Ready = smoke-tested on the phone 10-27/28 · Needs setup (what) · Fragile (works, but has a known stage risk) · Not on phone.
**Sources:** DF = pitch/rehearsal/DEPLOY-FINAL.md · BR = agentd/bench/RESULTS.md · and each area's README or research note.
Key stage rules: say **"open <app>"** for message reads. Keep deletes out. Run any security trap **last**: a guard flag makes every send need a person for 10 min.

## 1. Voice
| Feature | What it does | Trigger | Entry | Time (source) | Status | Wow |
|---|---|---|---|---|---|---|
| Voice assistant | GPT Live talks; each job becomes a background Codex task; it says when it's done | Tap mic, **wait for "Listening"**, "What's my battery?" | V | "On it" 3.3 s, done 6.2 s, spoken "Done, in 6 seconds…" (DF) | Fragile: cold start ~7.6 s; spoken "Done" can lag ~6 s | A receptionist plus a back office: keep talking while it works |
| Task status by voice | Answers from live task state, no model call | "How's it going?" · "What's running?" · "Never mind, stop that" | V | ~0.5 s (agentd README) | Ready (it sometimes answers from memory) | Instant answers about your own jobs |
| Voice "yes" approves | Your own spoken yes/no answers one pending card | When a card is up: "Send it" / "No" | V | – | Ready (fingerprint-only cards reply "needs your fingerprint") | Hands-free approval, but only *your* voice |
| Several jobs at once | Jobs queue and take turns at the screen; they keep going after voice closes | Ask a second thing while one runs | V A | – | Ready | Like tickets at a counter |
| Wake word "Hey Agent" | Offline word spotter opens voice mode, with a chime | Command center › wake word switch on, then "Hey Agent" | V | ~0.9 s spot (launcher README) | Needs setup: switch is off. Fragile in a loud room (3/7 at 10 dB noise) | Hands-free and fully offline |
| Wake word "Jarvis" | Porcupine engine (better in noise) | Same switch | V | – | Needs setup: Picovoice key (7-day trial only) | – |

## 2. Agent actions across apps (ask bar or voice)
| Feature | What it does | Trigger | Entry | Time (source) | Status | Wow |
|---|---|---|---|---|---|---|
| One-step tasks | Direct phone APIs, no screen tapping | "Turn on the flashlight" · "What's my battery level?" | V A G | 2–5 s flashlight; 6.6 s battery (DF) | Ready | Faster than doing it by hand |
| Stopwatch | Every finish shows its real time | Any task | all | "Done · N s" in sheet, island, Home card | Ready | Measured, not claimed |
| Cross-app task | Chains actions across apps | "Turn on the flashlight, then open <app> and read me <name>'s last message" | V A G | 10.1 s (DF) | Ready if you say **open**; without it, it may answer from stale memory | One sentence, two apps |
| Alarm / timer / web search | Allow-listed system intents | "Set an alarm for 7 AM" · "Start a 5 minute timer" · "Search Chrome for the weather in Salt Lake City" | V A G | 7.5 / 6.8 / 9.9 s (BR) | Ready | 4× faster than last week (33 → 7.5 s) |
| Operate any app by its screen | Reads the screen, taps and types like a person | "Use the Calculator to work out 234 × 17" | V A G | 15.1 s; Calculator step 1.4 s (BR) | Ready | Any app, no partner deals (Gemini: 6 apps) |
| Multi-step chain | Look something up, then act on it | "Look up today's sunset in Chrome, then set an alarm for it" | V A G | 19.9 s (BR) | Ready | Reasoning across apps |
| Calendar | Add, find, move, delete events | "What's on my calendar tomorrow?" · "Add lunch with Sam Friday at noon" | V A G | read 3–4 s (BR r3) | Ready (use test items; deletes may ask for a tap) | No screen needed |
| Contacts, SMS, calls | Find people, read texts and calls, send a text, place a call | "Text Sam I'm on my way" | V A G | – | Ready (a person approves every text/call). Don't text real contacts on stage | – |
| Phone settings | DND, ringer, volume, brightness, torch, Wi-Fi, Bluetooth, airplane | "Turn on Do Not Disturb" · "Brightness to 50%" | V A G | one call (BR r3) | Ready (never toggle Wi-Fi on stage) | – |
| Media | Play, pause, next on any player | "Pause the music" · "Play lo-fi on Spotify" | V A G | – | Ready | – |
| Maps and links | Navigate, open a URL | "Navigate to the airport" | V A G | – | Ready | – |
| Your request = permission | Your own ask needs no "allow app" card (except money, install, password apps) | Any own ask | V A | – | Ready | No permission nagging |
| Queued asks | A second ask waits its turn, no error | Send two asks quickly | A | – | Ready | – |

## 3. Keyboard (works in every app's text box)
| Feature | What it does | Trigger | Entry | Time (source) | Status | Wow |
|---|---|---|---|---|---|---|
| Context replies | 3 replies written from the chat on screen | Tap a reply field in any chat | I | chips ~1.0 s on focus; 2.2–2.6 s after typing (DF) | Ready (first call after idle ~5.7 s) | QuickType, but whole replies |
| Calendar-aware replies | Replies know your free time | Chat asks "Are you free Thursday at 3?", tap the field | I | 1.0 s, e.g. "Thursday 3 PM to 5 PM looks free." (DF) | Ready (check the real calendar first) | Pixel Magic Cue, but in every app, on a $200 phone |
| Rewrite / Fix modes | Shorter, friendlier, formal; or spelling and grammar | Tap the mode pill | I | ~1.5 s | Ready | – |
| ✨ Write for me | Type an intent, get a finished message that fits the app | Tap ✨, "thank him for fixing the sink and ask when we can meet" | I | 1.3 s chat, 2.4 s email (keyboard README) | Ready | Gmail's Help me write, in every app |
| Translate | Translate the draft or the last incoming message; remembers the language per chat | ✨ result › Translate · dot › Translate | I | 1.2–1.3 s | Ready | – |
| Catch me up | One line on a long thread and what's asked of you | Dot › Catch up (thread of 8+ lines) | I | 7.5 s (one sample) | Fragile (slow, one sample) | – |
| Make an image | Generates a picture and drops it into the chat | ✨ "make an image of a cat in a spacesuit" | I | 37–51 s | Fragile (slow; Gmail falls back to Copy) | – |
| Send-safety + privacy | See SECURITY-FEATURES.md | – | I | instant | Ready | – |

## 4. Home, For you, proactive
| Feature | What it does | Trigger | Entry | Time (source) | Status | Wow |
|---|---|---|---|---|---|---|
| AI home screen | Now card (live task), For you suggestions, next event, "Leave for…", My apps, dock | Press Home | – | – | Ready (no dummy data) | The home screen *is* the agent |
| What did I miss? | Cards for unanswered messages and next events, plus one spoken or typed line | "What did I miss?" | V A | 1.6–3.6 s; 2.3 s with a message (DF) | Ready (clear private notifications first) | Summarizes *and* offers to act |
| Proactive nudges | A new question in a chat becomes "Reply to Alex?" with a draft; your promises become reminders | A teammate texts you a question | – | DM → card 5.6 s; reply → promise 4.8 s (commit d10828b) | Needs setup: a teammate's real message. Not re-run in the final deploy | 99–100 % precision on 152 threads (PROACTIVE.md) |
| Try chips | Example prompts above the ask bar | Tap a chip | A | – | Ready | – |
| Moments | Home shows a 45 s "done" card for replies, sign-ins, blocks, schedules, mods | Automatic | – | – | Ready | – |

## 5. Memory and knowledge graph
| Feature | What it does | Trigger | Entry | Time (source) | Status | Wow |
|---|---|---|---|---|---|---|
| Personal memory | Answers about people and your life from many apps | "Who is Mom and when did I last message her?" | V A G | 7.7 s (agentd README) | Ready (memory can be stale; for "last message" say "open <app>") | Cross-app memory, on the phone |
| What I know | One screen: About you, People, "Same person?" cards; you correct it | Command center › What I know | – | – | Ready (MEMORY_V2=1 on the phone) | You can read and fix what it knows |
| Memory graph | Live graph of people, apps and events | Swipe left twice from Home | – | – | Ready | Visual "brain" |
| Connectors | Contacts, calendar, Chrome, photos, notifications, usage, alarms, calls, SMS, media; only with consent | Command center › memory sources | – | – | Ready | 86/86 eval (v1: 63/86) |
| Ambiguous names | "Text Sam" with two Sams asks which one | Ask with an ambiguous name | V A | – | Ready | – |

## 6. Notifications and replies
| Feature | What it does | Trigger | Entry | Time (source) | Status | Wow |
|---|---|---|---|---|---|---|
| Reply without naming anyone | Replies to the newest thread through the notification's own Reply | "Reply back that I'm 10 min late" | V A | card 3.5–4.2 s; app has it 0.02 s after the tap (agentd README) | Fragile: tested on a test chat, not a real WhatsApp/Discord; not re-tested 10-28. A person taps (3 lamps lit) | No app opens at all |
| Auto-reply rule | Fixed reply for N minutes, after one approval | "Tell people I'm driving for the next 30 minutes" | V A | rule 4.8 s; each reply 0.7 s, no card | Ready on the test chat. Never on real contacts | Driving mode in one sentence |
| Handle replies for me | Each new message from a person starts a task (sends still approved) | "Handle replies from Sam for me" | V A | – | Needs setup: a teammate | – |
| Mark as read | Uses the app's own Mark as read | "Mark Alex's messages as read" | V A | 10 s turn (APP-APIS.md) | Ready (may send read receipts) | – |
| Any app's messages | Live notification tools + "What did I miss?" cover Slack, Instagram, Discord, Gmail, LinkedIn… without opening them (no per-app reader tools since 10-28) | "Any new Slack messages?" | V A G | reads ~20 ms | Ready (only what notifications show) | – |

## 7. Vision and camera
| Feature | What it does | Trigger | Entry | Time (source) | Status | Wow |
|---|---|---|---|---|---|---|
| Camera by voice | Voice can see through the camera | Voice sheet › camera, "What plant is this?" / "Read this sign" | V | 0.4–1.3 s after you stop talking (LIVE-VISION.md) | Ready on 10-27; not re-smoked 10-28 | As fast as a plain question |
| Screen vision | Looks at your screen and helps | "What's on my screen?" / "Help me answer this" | V | 3.9–6.4 s | Ready on 10-27 (refused in money/password apps) | – |
| Attach a photo | Send a camera shot or photo with an ask | Ask bar › + › Camera / Photos | A V | – | Ready | – |

## 8. Micro-apps
| Feature | What it does | Trigger | Entry | Time (source) | Status | Wow |
|---|---|---|---|---|---|---|
| Build an app by asking | Writes a small sandboxed app that appears under My apps | "Make me a water tracker app" | V A | 14–31 s; 5/5 built (agentd README) | Ready (5 apps already on Home) | An app store of one |
| Edit an app | Rewrites your app | "Update my water tracker to 10 glasses" | V A | 21.9 s | Ready | – |
| Remove | Hold the card 600 ms › Remove | Home | – | – | Ready | – |

## 9. App mods (real apps) and deep mods (our app)
| Feature | What it does | Trigger | Entry | Time (source) | Status | Wow |
|---|---|---|---|---|---|---|
| Hide parts of a real app | Masks LinkedIn "Promoted" posts, colored like the app, follows scrolling; "Ad hidden" chip (tap to peek, hold to remove) | Open LinkedIn: "Hide Promoted posts in LinkedIn" | V A I | applied at 19.6 s, reviewer auto-approved, no tap (APP-MODS.md) | Fragile: needs a Promoted post in the feed; the agent may scroll | Edit a real app with words |
| Add a button to a real app | Native-looking button that presses the app's own keys | "Add a tip button to my calculator" · "Add a focus 25 minute button to my timer" | V A | 13.6 s / 9.4 s incl. the tap (android README) | Ready (needs your tap) | Tip key next to ÷ |
| Manage mods | List, remove, disable all | "List my mods" · "Remove the LinkedIn mod" · Command center › Mods | V A | – | Ready | – |
| Deep mods (Pigeon only) | Signed native re-theme of our own app, previewed, auto-restored | "Open Pigeon and preview a navy and gold theme" | V A | 8.7 s (DF) | Not on stage (own app only; first try can fail after a broker reinstall) | Real view changes, no hacks |

## 10. Trackers and firewall (owner settings, not agent tools, since 10-28)
| Feature | What it does | Trigger | Entry | Time (source) | Status | Wow |
|---|---|---|---|---|---|---|
| Tracker radar | Sees which tracker companies each app talks to | Open Weather or Spotify, then Home › "N apps sent data to trackers" | – | lists in 10–50 ms (android README) | Ready | iPhone shows trackers; this also blocks them |
| Live counter | Blocked counts tick live; island "Blocked · Facebook +5" | Blockers view open | – | refresh every 2 s | Ready | Number climbs while the app still works |
| One-tap blocks | Block / Block all / Unblock; only the owner can (the broker refuses agentd) | Blockers view | – | – | Ready | The AI can't touch your privacy settings |
| Cut an app off | Per-app network switch | Permissions › Network | A | – | Ready | – |
| Protected today | Trackers blocked, scams caught, actions stopped, approvals | Command center | – | – | Ready (run demo reset first) | – |

## 11. Scheduled tasks and unlock
| Feature | What it does | Trigger | Entry | Time (source) | Status | Wow |
|---|---|---|---|---|---|---|
| Scheduled task | Runs later, even from deep sleep | "In 2 minutes, set an alarm for 6:15" | V A | fired on time; alarm set 3.8 s (DF) | Ready | Works while nobody touches it |
| Grid-aware agent (Energy plugin) | Says when to run the dishwasher/laundry or charge the car: Rocky Mountain Power TOU price (4.5× peak/off-peak) + live U.S. EIA data for Utah's grid (PACE demand + fuel mix, carbon is our labeled estimate); "Yes" books a real reminder | "When should I run the dishwasher?" → "Yes" | V A | 3.7–8.0 s (tool 10–25 ms cached), "Yes" → booked in 1.7 s (agentd/grid.js) | Ready (DEMO_KEY until the founder's EIA key; fuel mix ~13 h old) | Torus bounty: Rush Hour for Electrons + Compute Follows the Sun |
| Unlocks itself | A locked phone opens, does your task, re-locks | Lock the phone after scheduling | – | – | Ready (refuses if a guard flagged anything earlier) | House sitter with a lockbox |
| Recurring | Daily, weekdays, weekly | "Every weekday at 8, read me my notifications" | V A | – | Ready (not shown on stage yet) | – |
| Manage | List and cancel; Home shows the next two | "What's scheduled?" · Command center › Tasks › x | V A | – | Ready | – |

## 12. Offline and the local model
| Feature | What it does | Trigger | Entry | Time (source) | Status | Wow |
|---|---|---|---|---|---|---|
| Auto offline fallback | No network → the phone's own model answers, tagged "On device" | Airplane mode, "Turn on the flashlight" | A | 4.6 s (DF); warm 2.3 s, cold 24 s | Ready if warmed (`preflight --fix warm`) | Siri says "can't connect"; this keeps going |
| Local tasks | Open app, alarm, timer, search, event, music, look in an app | Airplane: "Open <app> and read me the last message from <name>" | A | 12 s (STATUS) | Ready (26/30 tasks) | Qwen 0.8B on a $200 phone |
| Force on-device | Pick the local brain by hand | Command center › Model › On-device | – | – | Ready | – |

## 13. Multi-provider brains
| Feature | What it does | Trigger | Entry | Time | Status | Wow |
|---|---|---|---|---|---|---|
| Codex (default) | gpt-6.1-terra on the phone through Codex app-server | Default | all | median ~11 s (BR) | Ready | – |
| Claude Code on the phone | Same tools, same rules, different brain | Command center › Model › Claude | A | not measured | Fragile: token installed, never benchmarked on the phone | "Switch brains" |
| Local | See 12 | – | – | – | Ready | – |

## 14. Remote assistants (Grok, Dots/ChatGPT via MCP)
| Feature | What it does | Trigger | Entry | Time (source) | Status | Wow |
|---|---|---|---|---|---|---|
| Grok runs a phone task | Another company's AI asks; only you approve on the phone | In Grok: "Use AgentOS: what's my next alarm?" → lease card "From Grok: …" → tap | G | ~10 s; 7.1 s real phone test (REMOTE-MCP.md) | Needs setup: REMOTE_MCP is off on the phone; needs a VPS relay or Cloudflare tunnel + pairing (~15 min). Never tried with real Grok/ChatGPT | "Grok can ask, it can't approve" |
| Remote reads | Grok reads your latest threads or memory (codes masked) | "Use AgentOS: summarize my messages" | G | – | Needs setup (same) | – |
| Remote tile | Pairing code, kill switch, revoke | Command center › Remote | – | – | Ready (UI) | – |

## 15. Watch (Galaxy Watch4)
| Feature | What it does | Trigger | Entry | Time | Status | Wow |
|---|---|---|---|---|---|---|
| Glance, buzz, tap to talk | Shows what the agent does, buzzes for approvals, voice asks | Tap the watch face | – | – | Not on phone/watch: built, shown on the Moto as a stand-in; needs Wi-Fi debugging on the watch + the Mac tunnel | – |

## 16. Password vault
| Feature | What it does | Trigger | Entry | Time (source) | Status | Wow |
|---|---|---|---|---|---|---|
| Add a password | Native screen; the password never reaches the AI | Command center › Passwords › Add password › Save with fingerprint | – | – | Needs setup: the vault is empty; add your own Slack login | – |
| Sign-in offer | Open a sign-in page: "Sign in as <you>?" → fingerprint → filled + Continue | Open Slack's sign-in page | I | – | Fragile: real finger never tested end to end; prompt is black on screen capture (use a doc camera) | Like iOS, but the AI can't see the key |
| "Log me into Slack" | Agent asks the vault; you touch the sensor | "Log me into Slack" | V A | prompt at ~12 s (android README) | Fragile (same) | – |

## 17. Plugins
| Feature | What it does | Trigger | Entry | Time | Status | Wow |
|---|---|---|---|---|---|---|
| Plugin switches | 12 groups of API tools you can switch off (the agent may still use the app on screen) | Command center › Plugins | – | – | Ready | – |

## 18. Island and on-screen UI
| Feature | What it does | Trigger | Entry | Time | Status | Wow |
|---|---|---|---|---|---|---|
| Island | Pill under the camera shows each step, a progress ring, "Auto-approved ✓", "Done · N s", "Blocked · …" | Any task; tap for the sheet with Stop | – | – | Ready | Always see what the agent is doing |
| Activity log | Today in plain words ("Tapped Send in Pigeon", "Scam message caught") | Command center › Activity | – | – | Ready | Calm log, no red pop-ups |
| Command center | Protected today, Tasks, Model, Activity, tiles | Swipe left from Home | – | – | Ready | – |

## Count
79 rows in 18 areas: 62 Ready, 8 Fragile, 7 Needs setup, 2 not on stage/phone (deep mods, watch). "Ready" includes a few last tested 10-27 (vision, auto-reply); re-check those in rehearsal.
