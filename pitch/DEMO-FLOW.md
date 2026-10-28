# 6-minute demo flow (draft for the founder)

Rhythm: one sentence of setup, one ask, the result on screen, move on. Security is shown *inside* the beats (cards, island, "Auto-approved ✓") plus one 45 s tour near the end. Timings are measured numbers from pitch/FEATURES.md plus speaking time.

Two facts that shape the order:
- **Agent tasks run one at a time** (one screen, one lease). A second ask waits its turn. Things that aren't agent tasks run alongside: keyboard suggestions, Command center screens, charts already on screen.
- **Toggling airplane mode is risky:** Wi-Fi dropped 3× overnight. Show offline last, or use Command center › Model › On-device instead.

| Time | Beat | Say / do | What they see | Shows (feature + security) |
|---|---|---|---|---|
| 0:00 | **Bookend start** | "Hey Jarvis… in 5 minutes, set an alarm for 6:15." | Chime, voice orb, "Scheduled · 6:15 in 5 min" | Wake word, voice, scheduled tasks |
| 0:15 | **What did I miss?** | "What did I miss?" | Spoken 2-line summary + cards (unanswered messages, next event, a "Reply to …?" For You card) | Proactivity, notifications; "it hid a suspicious message" (untrusted text, Prompt Guard) |
| 0:40 | **Cross-app + ambiguous name** | "Text Sam that I'm running 10 minutes late." | "Which Sam?" (two cards) → pick → island steps → Send card → tap → "Done · ~10 s" | Knowledge graph / same-person, cross-app actions, approval card, temporary lease, stopwatch |
| 1:10 | **Start a micro-app (runs in the background)** | "Build me a water tracker app." | Island: building… | Micro-apps (14–31 s); keep talking |
| 1:15 | **Keyboard while it builds** | Open a chat that asks "Are you free Thursday at 3?" → tap the field | Calendar-aware chip "Thursday 3–5 PM looks free" → tap → ✨ Translate to Spanish | Smart keyboard, calendar replies, translate (not an agent task, so it runs alongside) |
| 1:40 | **Micro-app lands** | Swipe Home | The new water tracker card under My apps → open it, tap +1 | "An app store of one" |
| 1:55 | **Modify a real app** | Open LinkedIn: "Hide promoted posts." | ~20 s, narrate: "I asked in my own words, so the reviewer auto-approves; no code runs" → "Ad hidden" chips, tap to peek | Real-time app mods, Codex auto-approval, AI can't write or run code |
| 2:25 | **Live talk with your screen** | Open voice: "What's on my screen? Help me reply." | Spoken answer about the screen, 4–6 s | Screenshot/vision in live talk |
| 2:45 | **Health from the watch** | "Show me my sleep this week." | Stacked sleep-stage chart + averages, 3 s | Galaxy Watch data (consented import), visual answers |
| 3:05 | **Smart home + clean power** | "What smart home devices do I have?" → "When should I turn on my Christmas tree today?" | Device card (names and rooms) → grid timeline (3–6 PM clean vs 6–10 PM peak) | Knows your home + grid-aware timing (Torus angle). Say "it knows my devices and when to run them", not "it controls them" |
| 3:35 | **Grok controls the phone** | Laptop, Grok: "Use AgentOS: open LinkedIn." Then: "…and text Sam hi." | LinkedIn opens with no card; the Send gets a card on the phone that only you can tap | Grok/Dots via MCP; "the AI only asks, it can't approve" |
| 4:00 | **Security tour (45 s)** | Swipe to Command center | Protected today (trackers + ads blocked, scams caught, actions stopped) → Activity log → Permissions + Plugins switches → Blockers (one-tap block) | Activity log, permissions/plugins, trackers/ads, scam detection |
| 4:45 | **Fingerprint for sensitive** | "Log me into Slack." | Vault sign-in card → fingerprint → filled | Password vault, fingerprint only for sensitive tasks |
| 5:00 | **Brains + offline** | Command center › Model: tap Codex / Claude / On-device → On-device: "Turn on the flashlight." | "On device" tag, ~2–5 s | Local model + ChatGPT + Claude subscriptions |
| 5:15 | **Bookend close** | Lock the phone, put it face up | At 5:00 after scheduling, it wakes, unlocks itself, sets the alarm, re-locks | Scheduled tasks + auto-unlock (refuses if anything was flagged) |
| 5:40 | **Close** | One line: "The model proposes. The OS decides." | – | – |

## Say-only lines (no demo time)
- "The AI never gets a shell or root; it can't write or run code on the phone."
- "Everything on screen and every incoming message is untrusted data; Meta's Prompt Guard 2 runs on the phone."
- "Off-limits windows: it can't see or tap its own approval cards, so it can't approve itself."
- "Same tools, any brain: ChatGPT and Claude subscriptions, or a model on the phone."

## Cut first if running long
1. Fingerprint/vault (fragile; a doc camera is needed because the prompt is black on screen capture).
2. Micro-app (or pre-build it and just open it).
3. Live talk with the screen.
4. Grok's second line (keep "open LinkedIn").

## Prep checklist
- **Sam:** use a teammate saved as "Sam" next to another Sam, so the ambiguous-name beat is real and the text goes to someone in the room. Never a real outside contact.
- **Hey Jarvis:** "Jarvis" needs a Picovoice key (7-day trial); otherwise "Hey Agent" works offline now (or rename the offline phrase).
- **Keyboard chat:** a real chat where someone asked "Are you free Thursday at 3?"
- **Grok:** laptop open on grok.com with AgentOS connected; Remote "reads" switch set how you want.
- **"What did I miss?":** have 2–3 real unanswered messages waiting (a teammate can send them).
- **Slack:** a saved Slack login in the vault, rehearsed once with your finger.
- **Preflight:** `scripts/preflight.sh --fix wifi,warm,ime,home`, then `--fix dnd,reset`. Close the bench's leftover apps. No sign-in page open in Chrome.
