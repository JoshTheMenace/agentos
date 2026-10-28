# 6-minute demo flow (stage order, 10-28)

Slides: `pitch/stage/index.html` (one slide per beat; → / clicker to advance; `stage.pdf` is the same deck). Each slide carries the founder's cue in the bottom-left corner. Rhythm: one sentence of setup, one ask, the result on screen, move on. Timings are measured numbers from pitch/FEATURES.md plus speaking time.

Two facts that shape the order:
- **Agent tasks run one at a time** (one screen, one lease). A second ask waits its turn. Things that aren't agent tasks run alongside: keyboard suggestions, Command center screens, charts already on screen.
- **Toggling airplane mode is risky:** Wi-Fi dropped 3× overnight. It is the last live beat, so a failure costs nothing before it. Fallback: Command center › Model › On-device.

| # | Time | Beat | Say / do | What they see | Shows (feature + security) |
|---|---|---|---|---|---|
| 1 | 0:00 | **Title** | "AgentOS: the Siri we should have." | Title slide | – |
| 2 | 0:05 | **Voice + "What did I miss?"** | Wake word → "What did I miss?" | Chime, orb, spoken 2-line summary + cards (unanswered messages, next event, a promise, "1 scam message hidden") | Wake word, voice, proactive cards, notifications; untrusted text + Prompt Guard hid a suspicious message |
| 3 | 0:35 | **Cross-app + "Which Sam?"** | "Text Sam that I'm running 10 minutes late." | "Which Sam?" (two cards) → pick → island steps → Send card → tap → "Done · N s" | Knowledge graph / same-person, cross-app actions, approval card, temporary lease, stopwatch |
| 4 | 1:05 | **Build an app + keyboard** | "Build me a water tracker app." Then open the chat that asks "Are you free Thursday at 3?" → tap the field | Island: building… · calendar-aware chip "Thursday 3–5 PM looks free" → tap → ✨ Translate to Spanish · swipe Home: the new app card under My apps | Micro-apps (14–31 s) in the background; smart keyboard, calendar replies, translate (not an agent task, so it runs alongside) |
| 5 | 1:45 | **Modify a real app live** | Open LinkedIn: "Hide promoted posts." | ~20 s, narrate: "my own words, so the reviewer auto-approves; no code runs" → "Ad hidden" chips, tap to peek | Real-time app mods, Codex auto-approval, AI can't write or run code |
| 6 | 2:15 | **Live talk sees your screen** | Open voice on the LinkedIn screen: "What's on my screen? Help me reply." | Spoken answer about the screen, 4–6 s | Screenshot/vision in live talk |
| 7 | 2:35 | **Health from the Galaxy Watch** | "Show me my sleep this week." | Stacked sleep-stage chart + averages, 3 s | Galaxy Watch data (consented import), visual answers, data stays on the phone |
| 8 | 2:55 | **Smart home + clean power** | "What smart home devices do I have?" → "When's the cleanest time to turn on the Christmas tree today?" | Device card (names and rooms) → grid timeline (3–6 PM clean vs 6–10 PM peak) | Knows your home + grid-aware timing (Torus angle). Say "it knows my devices and when to run them", not "it controls them" |
| 9 | 3:25 | **Grok controls the phone** | Laptop, Grok: "Use AgentOS: open LinkedIn." Then: "…and text Sam hi." | LinkedIn opens with no card; the Send gets a card on the phone that only you can tap | Grok/Dots via MCP; "the AI only asks, it can't approve or unlock" |
| 10 | 3:50 | **Fingerprint for money and installs** | "Install <small free app>." | Approval card with "Fingerprint required" → touch the sensor. The system prompt is black on mirroring: hold the phone up | Fingerprint only for payments, installs, saved passwords |
| 11 | 4:10 | **Locked phone** | "In one minute, turn on the flashlight." Lock the phone, put it face up | Scheduled chip on Home; a minute later it wakes, unlocks itself, turns the torch on, re-locks | Scheduled tasks + auto-unlock (refuses if anything was flagged) |
| 12 | 4:20 | **Security in one breath** (during the minute) | Swipe to Command center | Protected today (trackers + ads blocked, scams caught, actions stopped) → Activity log → Permissions + Plugins switches → Blockers. Slide 12 shows the full chip grid | AI only asks · auto-approval · AI can't run code · temporary leases · can't see its own cards · permissions & plugins · trackers & ads blocked · approval cards · fingerprint · Prompt Guard 2 on device · screen text is untrusted · scam detection · activity log · password vault |
| 13 | 5:05 | **Multiple brains + airplane mode** | Command center › Model: tap Codex / Claude / On-device. Airplane mode on → "Turn on the flashlight." | "On device" tag, ~2–5 s | Local model + ChatGPT + Claude subscriptions; works with no signal |
| 14 | 5:35 | **Close** | One line: "The model proposes. The OS decides." | Closing slide | – |

## Say-only lines (no demo time)
- "The AI never gets a shell or root; it can't write or run code on the phone."
- "Everything on screen and every incoming message is untrusted data; Meta's Prompt Guard 2 runs on the phone."
- "Off-limits windows: it can't see or tap its own approval cards, so it can't approve itself."
- "Same tools, any brain: ChatGPT and Claude subscriptions, or a model on the phone."

## Cut first if running long
1. Live talk with the screen (beat 6).
2. Micro-app (beat 4: pre-build it and just open it; keep the keyboard).
3. Grok's second line (keep "open LinkedIn").
4. Airplane mode (beat 13: just tap the Model row instead).

## Prep checklist
- **Sam:** use a teammate saved as "Sam" next to another Sam, so the ambiguous-name beat is real and the text goes to someone in the room. Never a real outside contact.
- **Hey Jarvis:** "Jarvis" needs a Picovoice key (7-day trial); otherwise "Hey Agent" works offline now (or rename the offline phrase).
- **Keyboard chat:** a real chat where someone asked "Are you free Thursday at 3?"
- **Grok:** laptop open on grok.com with AgentOS connected; Remote "reads" switch set how you want.
- **"What did I miss?":** have 2–3 real unanswered messages waiting (a teammate can send them), plus the planted suspicious one.
- **Install beat:** pick a small free app that is not installed; know its name.
- **Flashlight timer:** rehearse the lock + one-minute wait once, so the security tour fits the gap.
- **Slides:** open `pitch/stage/index.html` in Chrome, full screen (it opens in present mode; P toggles the overview).
- **Preflight:** `scripts/preflight.sh --fix wifi,warm,ime,home`, then `--fix dnd,reset`. Close the bench's leftover apps. No sign-in page open in Chrome.
