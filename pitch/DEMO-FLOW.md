# Demo flow (stage, 10-28)

**Run of show: `pitch/SCRIPT.md`.** It is the source of truth for order, lines, timing and what to do at each beat. This file only adds the slides and the prep.

Slides: `pitch/stage/index.html`, one slide per SCRIPT.md beat (17 with Sources; → / clicker to advance; `stage.pdf` is the same deck). Each slide carries the founder's cue in the bottom-left corner, the "Do" from the script. Rhythm: say the line, do the thing, move on.

Two facts that shape the order:
- **Agent tasks run one at a time** (one screen, one lease). A second ask waits its turn. Things that aren't agent tasks run alongside: keyboard suggestions, Command center screens, charts already on screen. That is why the keyboard beat sits inside the micro-app build.
- **Prompt Guard after fingerprint:** a flagged message makes later sends need a person for 10 min, so the live injection beat comes after the install beat.

## Cut first if running long
1. Micro-app: pre-build the timer and just open it (keep the keyboard).
2. Smart home's second question (keep the device card).
3. Grok finale: fall back to "turn on my flashlight".
4. Spare-seconds items in SCRIPT.md (speed line, airplane mode, image generation) are already optional.

## Prep checklist (SCRIPT.md prep + ours)
- **Voice beat:** a teammate or your main account sends the Moto a Slack/Discord DM or email from "Alex" with a place: "Let's meet at Kneaders in Orem at 6." Rehearse the exact sentence once.
- **Knowledge graph:** pick a person who's in several apps (Slack + Discord + email/calendar) and is OK to show on a big screen. Check "What do I know about <name>?" once.
- **Keyboard:** email yourself "Are you free Thursday at 3?" so it's waiting in Gmail.
- **LinkedIn:** apply "Hide promoted posts" before you go on stage; it needs a Promoted post in the feed.
- **Prompt Guard:** in Pigeon, ⋮ › Replay test scenario (or `adb shell "am start -n local.agentos.pigeon/.Main --es scenario clean"` then `--es scenario canary` for exactly one trap message). Run this beat AFTER the fingerprint beat.
- **Fingerprint:** pick a small free app that isn't installed; know its name.
- **Grok:** laptop open on grok.com with AgentOS connected; "Remote can unlock" ON (Command center › Remote).
- **"Hey Agent":** the offline wake phrase works now; "Jarvis" needs a Picovoice key.
- **Slides:** open `pitch/stage/index.html` in Chrome, full screen (it opens in present mode; P toggles the overview).
- **Preflight:** `scripts/preflight.sh --fix wifi,warm,ime,home`, then `--fix dnd,reset`. Close leftover apps; no sign-in page open in Chrome; phone on Home.

## Say-only lines (no demo time)
- "The AI never gets a shell or root; it can't write or run code on the phone."
- "Everything on screen and every incoming message is untrusted data; Meta's Prompt Guard 2 runs on the phone."
- "Off-limits windows: it can't see or tap its own approval cards, so it can't approve itself."
- "Same tools, any brain: ChatGPT and Claude subscriptions, or a model on the phone."
