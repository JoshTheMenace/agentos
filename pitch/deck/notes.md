# AgentOS deck: speaker notes and sources (v3, 2026-10-28)

Deck: `pitch/deck/index.html` (open in a browser, press **P** to present, arrows to move), `pitch/deck/AgentOS-deck.pdf`, one PNG per slide in `pitch/deck/slides/` and `slides/overview.png` (all five tiled). Earlier drafts: `index-v1.html` (wordy), `index-v2.html` (numbers); v2 notes are in `notes-v2.md`.

**v3 rule:** one headline per slide, real phone screenshots with 2–4 word labels, Ready features only. **No task times or speed numbers on the slides**: the founder shows speed live. Those numbers, with sources, are at the bottom under "Numbers the founder shows live". Trackers are a built-in privacy setting (Command center › Blockers, Protected today), not something you ask the agent for.

Re-export (PDF, then PNGs, then the overview):
```
CH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
"$CH" --headless=new --no-pdf-header-footer --print-to-pdf=$PWD/pitch/deck/AgentOS-deck.pdf file://$PWD/pitch/deck/index.html
for n in 1 2 3 4 5; do "$CH" --headless=new --hide-scrollbars --window-size=1600,900 --screenshot=pitch/deck/slides/slide-$n.png "file://$PWD/pitch/deck/index.html?slide=$n"; done
# overview: a 2-column HTML grid of the five PNGs at 800×450, screenshot at 1610×1370
```

Status key: **MEASURED** = a number from a results file in this repo, taken on the real phone or in our harness. **REPO** = checked in the repo or git. **SECONDARY** = press/aggregator, say with attribution. Screenshot sources are in `SOURCES.md`; feature readiness is in `pitch/FEATURES.md` and `pitch/SECURITY-FEATURES.md`.

The founder is writing the live demo order; these notes only cover the slides.

---

## Slide 1 · The problem (~30 s)

On the slide: **"Your assistant still can't do anything for you."** · "It can tell you the weather. It can't tell Mom you're running late." · 6 apps · 6 of 7 · voice-mode screenshot ("AgentOS: say it, it does it.").

Say it, not on the slide:
- 6 apps: today's flagship phone agent works in a handful of partner apps, cloud-only, and hands back before checkout (reported).
- 6 of 7: the open-source phone agents tested in July each fell to at least 6 of 7 published attacks (arXiv 2607.00333).
- Slow: one reviewer timed that agent at about 45 s to order one ride (say it, don't show it).

Suggested words: "Everyone's phone has an assistant, and it still can't do anything for you. It can tell you the weather. It can't tell Mom you're running late in the app you actually use. The first phone agents are arriving: a handful of partner apps, cloud-only, and slow enough that one reviewer timed a single ride order at 45 seconds. And to let an agent act, it has to be safe: the open-source phone agents tested this July each fell to at least six of seven published attacks. So we built the phone OS we wanted. Say it, it does it, in any app, and the safety lives in the OS, not in the model."

| On the slide | Source | Status |
|---|---|---|
| "6 apps", partner apps only, cloud-only, hands back before checkout | Gemini task automation on Galaxy S26 (9to5Google 2026-03-12); `agentd/research/DEMO-WOW.md` | SECONDARY, tagged "reported". It may change fast; say "today". |
| Open-source phone agents each fell to at least 6 of 7 attacks | arXiv 2607.00333 (Zhang et al., Jul 2026); `research/08-pitch-utah.md` §4; `pitch/claims-check.md` B1 | SECONDARY. Read the results table before saying numbers out loud. |
| ~45 s to order one ride (spoken only) | one hands-on review (gimmie.ai, Aug 2026), linked from `agentd/research/DEMO-WOW.md` | SECONDARY, a single review. |
| Screenshot: voice mode | `launcher/shots/voice.png` (Oct 27) | REPO. |

Do not say: "unhackable", "we beat the paper". Say "we block these attack types in our test setup".

## Slide 2 · What it does: say it, it does it (~50 s)

On the slide: **"Say it. It does it. In any app."** · six shots: Voice, any app · Keyboard writes replies · "What did I miss?" · Apps made by asking · Scheduled, even locked · Point the camera, ask.

Say it, not on the slide (one line per shot, all Ready in `FEATURES.md`):
- **Voice, any app.** Talk to it; each job becomes a background task and it tells you when it's done. It reads the screen and taps like a person, so any app works, no partner deals. "Turn on the flashlight, then open Pigeon and read me Mom's last message": one sentence, two apps.
- **Keyboard writes replies.** Our own keyboard, in every app's text box: three whole replies from the chat on screen, rewrite and fix modes, "write for me", translate. It knows your calendar: when a chat asks "Are you free Thursday at 3?", the reply says what's actually free. Pixel Magic Cue, but in every app on a budget phone.
- **"What did I miss?"** Unanswered messages, your next event, a promise you made, and a scam message hidden, plus one spoken or typed line. It summarizes *and* offers to act.
- **Apps made by asking.** "Make me a habit tracker": a small sandboxed app appears under My apps. Five on the phone now. An app store of one.
- **Scheduled, even locked.** "Tomorrow at 11, remind me to charge the phone." It fires on time from deep sleep; a locked phone unlocks, does the task and re-locks. A house sitter with a lockbox. Recurring works too.
- **Point the camera, ask.** Voice mode can see: "What does this sign say?", "What plant is this?", as fast as a plain question.

Suggested words: "Here's what it does today. You talk to it and it works in your real apps by reading the screen, like a person would, so there are no partner deals. Our keyboard writes whole replies in any app and knows your calendar. Ask 'what did I miss?' and it shows the messages you haven't answered, your next event and the promises you made, then offers to act. Ask for an app and it builds one. Schedule something and the phone does it later, even locked: it unlocks, does the job, and locks again. And it can look through the camera."

| On the slide | Source | Status |
|---|---|---|
| Voice assistant, cross-app tasks, operate any app by its screen | `FEATURES.md` §1–2; `pitch/rehearsal/DEPLOY-FINAL.md` | Ready (voice cold start is the stage risk: warm it first). |
| Keyboard: context replies, calendar-aware replies, rewrite/fix, write for me, translate | `FEATURES.md` §3; `keyboard/LATENCY.md` | Ready. Catch-me-up and image generation are Fragile: don't promise them. |
| "What did I miss?" | `FEATURES.md` §4 | Ready (clear private notifications first). |
| Micro-apps | `FEATURES.md` §8; `agentd/README.md` 5/5 built | Ready. |
| Scheduled task, unlocks itself, recurring | `FEATURES.md` §11; `SECURITY-FEATURES.md` §10 (lockbox PIN, clean tasks only) | Ready. Refuses to unlock if a guard flagged anything earlier. |
| Camera by voice | `FEATURES.md` §7; `LIVE-VISION.md` | Ready on 10-27, not re-smoked 10-28. |
| Screenshots | voice `launcher/shots/voice.png`; keyboard `pitch/rehearsal/04-keyboard.png`; catch-up frame cropped from `launcher/shots/polish-night-3/catchup-zoom2.png`; app `launcher/shots/apps-habit-streaks.png`; schedule `launcher/shots/climb-a/05-after-sched-chip.png`; camera `launcher/shots/vision-voice-realcam.png` | REPO. |

## Slide 3 · What it does: knows you, guards you, yours to change (~50 s)

On the slide: **"It knows you. It guards you. You can change it."** · six shots: Proactive For you cards · Knows your people · Trackers blocked, one tap · Hide LinkedIn ads · Add buttons to apps · Codex, Claude or on-device.

Say it, not on the slide:
- **Proactive For you cards.** The home screen is the agent. A question in a chat becomes "Reply to Alex?" with a draft; a promise you typed becomes a reminder. Cards only ever *start* a task; nothing proactive is sent without you.
- **Knows your people.** Cross-app memory on the phone: contacts, calendar, messages, photos, only with consent. "Who is Mom and when did I last message her?" You can read and fix what it knows (Command center › What I know).
- **Trackers blocked, one tap.** A built-in privacy setting, not an agent feature: Blockers shows which tracker companies each app talks to and blocks them at the kernel firewall, per app, by company name, while the app keeps working. The AI can't touch these settings; only you can. Protected today on the Command center keeps the tally.
- **Hide LinkedIn ads.** "Hide Promoted posts in LinkedIn": a mask colored like the app that follows the feed, with an "Ad hidden" chip. Edit a real app with words.
- **Add buttons to apps.** "Add a tip button to my calculator": a native-looking button that presses the app's own keys. Mods can never press Send or Pay.
- **Codex, Claude or on-device.** Same tools, same rules, different brain. The on-device model (Qwen 0.8B) takes over automatically when the signal drops; Siri says "can't connect", this keeps going.

Suggested words: "It also knows you. The home screen is the agent: it notices a question you haven't answered and offers a reply, and it turns your promises into reminders. It builds a memory of your people across apps, on the phone, and you can read and correct it. Privacy is a setting, not a favor: one tap blocks the trackers inside Spotify or Weather at the kernel, and the AI can't change that setting, only you can. You can change your apps with words: hide the promoted posts in LinkedIn, add a button to the calculator. And you pick the brain: Codex, Claude, or a small model on the phone that keeps working with no signal."

| On the slide | Source | Status |
|---|---|---|
| AI home screen, For you, moments | `FEATURES.md` §4 | Ready. Proactive nudges from a real teammate message are "Needs setup": the shot is real, the live beat needs a teammate. |
| Memory, What I know, memory graph, connectors with consent | `FEATURES.md` §5 | Ready (MEMORY_V2=1 on the phone; memory can be stale). |
| Tracker radar, one-tap blocks, Protected today | `FEATURES.md` §10 (owner settings since 10-28); `android/README.md` (476 tracker domains, iptables by app uid) | Ready. Rules reset on reboot. Nothing in the deck says "ask the agent to block". |
| Hide parts of a real app (LinkedIn Promoted) | `FEATURES.md` §9; `APP-MODS.md` | Fragile on stage (needs a Promoted post in the feed). The screenshot is real. Founder asked for it on the slide. |
| Add a button to a real app | `FEATURES.md` §9 | Ready (needs your tap). The shot shows a "Double" button; say "tip button" only if you demo one. |
| Multi-provider brains, auto offline fallback | `FEATURES.md` §12–13; `agentd/runs/2026-10-28-offline-fallback.md` | Codex and local Ready; Claude on the phone is Fragile (installed, never benchmarked). Say "pluggable" if pressed. |
| Screenshots | For you `launcher/shots/proactive-home-request.png`; memory `launcher/shots/memory.png`; Blockers `launcher/shots/trackers-night/blockers.png`; LinkedIn `launcher/shots/mods-linkedin-polished.png`; calculator `launcher/shots/mods-calculator-button.png`; Command center `launcher/shots/foryou/after-center.png` | REPO. |

## Slide 4 · The OS decides (~45 s)

On the slide: **"The model proposes. The OS decides."** · diagram You / Grok · ChatGPT (can ask, can't approve) → Model → [models stop here] → The gate → Your apps · three rules (Rule of Two · Fingerprint · Audit) · two shots (fingerprint card, planted attack caught).

Say it, not on the slide:
- The model (agentd, "the brains"): Codex, Claude, or Qwen on the phone. Swappable. No shell, no root. It only sees phone tools.
- The gate (`local.agentos`): the only thing that can tap, type or call an API. Leases · Rule of Two · on-device Prompt Guard 2 · approvals bound to the exact action · fingerprint for payments and passwords · hash-chained audit · per-app firewall. Your apps stay unmodified.
- Rule of Two: untrusted text + private data + outward action = a human decides. Codes read from the screen can never be typed out.
- Grok or ChatGPT can send a task to the phone through MCP; every one shows a "From Grok" lease card, no auto-approve, no unlock, no scheduling. "Grok can ask, it can't approve." (This needs the remote relay set up: it is a design claim today, don't offer to demo it.)
- Approval cards show the exact app, button and text; a tap or your own spoken "yes"; the riskiest steps need your fingerprint. Any touch pauses the task; a one-second volume-down hold stops everything.
- Screenshots: the native approval card with the "Untrusted content" label; a planted attack in our own test app, flagged on the phone.
- Precise claim: the model's plan can be fooled; an outward action without your approval cannot happen.

Suggested words: "The model is the brains, and we assume it can be fooled. So it never gets a shell or root; it only sees phone tools. Everything it proposes goes through one gate, an Android app that owns the accessibility layer, the keystore and the kernel firewall. Three rules do most of the work. Rule of Two: untrusted text plus private data plus an outward action means a person decides, and a code read from the screen can never be typed out. Payments and saved passwords need your fingerprint. And every decision lands in a hash-chained log. Other assistants can ask the phone to do things, Grok, ChatGPT, but only you can approve. The same gate lets us swap brains."

| On the slide | Source | Status |
|---|---|---|
| Broker is the only executor; models get no shell | `SECURITY-FEATURES.md` §1; `ARCHITECTURE.md` rules 1–2 | Ready (design and config). |
| Rule of Two; screen codes never typed | `ARCHITECTURE.md` rule 3; `android/README.md` e2e (OTP block) | Ready, verified on the phone for the OTP case. |
| Fingerprint for money, installs, saved passwords; exact-text binding | `SECURITY-FEATURES.md` §2 | Fragile: a real finger has not completed end to end; the prompt is black on screen capture. Say "require your fingerprint", not "your fingerprint signs". |
| Hash-chained audit | `SECURITY-FEATURES.md` §5; `android/README.md` `chain_ok` | Ready. |
| Grok · ChatGPT can ask, can't approve | `SECURITY-FEATURES.md` §8; `REMOTE-MCP.md` (7.1 s real-phone test) | Needs setup (REMOTE_MCP off, relay + pairing ~15 min; never tried with real Grok). Design claim only. |
| Prompt Guard 2 on device, planted attack flagged | `SECURITY-FEATURES.md` §3; `android/guard-tests/README.md` | Fragile (misses some polite injections; the broker rules are the real defense). Always say "our own test app". |
| Screenshots | `android/shots/card-approval-fingerprint.png`; `pitch/rehearsal/07-security-alert.png` (Pigeon, our test app) | REPO. |

Known limits to volunteer: root is above everything; the approval sheet text is drawn by our app (no Protected Confirmation); firewall rules reset on reboot.

## Slide 5 · Why it matters for Utah (~30 s)

On the slide: **"AI must be human-guided." Built into the OS.** (kicker credits Gov. Cox, Pro-Human AI initiative) · 7,000+ · 12 % · Utah map with four pins · approval-card screenshot ("Risky steps wait for you").

Say it, not on the slide:
- Every outward action on this phone waits for a person. That sentence is our permission system.
- 7,000+ Utahns served by the Division of Services for People with Disabilities. First users: people who can't easily use a touchscreen. Target pilot channel: the Utah Assistive Technology Program at USU (Logan pin). Say "target channel", never "partner".
- ~12 % of Utahns are 65+; projection 1 in 4 by 2065. "Grandma says it, the agent does it, anything risky waits for her fingerprint or the family's okay."
- Utah-based founder, built here. Second market: state agencies adopting AI with safeguards (EO 2026-08) and Utah's device-management industry.
- Pins: Logan (USU assistive tech program) · Salt Lake City (state agencies, Cyber Center) · Draper (Nucleus at The Point) · rural Utah (works with no signal).

Suggested words: "Governor Cox's Pro-Human AI values say, word for word, AI must be human-guided. That sentence is our permission system. Who needs it first? Utahns who can't easily use a touchscreen: the Division of Services for People with Disabilities serves over seven thousand people, and the state's assistive technology program at USU already runs demo and loan libraries. Then older adults, and families who want a grandparent to say it and have it done, with anything risky waiting for a fingerprint. It runs on the phone people already own and keeps working with no signal, which matters outside the Wasatch Front. We're building it here."

| On the slide | Source | Status |
|---|---|---|
| "AI must be human-guided" (Gov. Cox, Pro-Human AI initiative) | prohumanai.utah.gov; `research/08` §2; claims D1 | VERIFIED (state site). Quote exactly. |
| 7,000+ Utahns served by DSPD | DSPD FY2024 Annual Report PDF; claims D3 | VERIFIED (primary). |
| Utah Assistive Technology Program (USU), Logan | idrpp.usu.edu/uatp; claims D6 | VERIFIED. Say **"target pilot channel"**, never "partner". No agreement exists. |
| ~12 % of Utahns 65+; 1 in 4 by 2065 | KSL; KPCW (Gardner Institute projections); claims D5 | SECONDARY. |
| EO 2026-08; device-management industry (spoken only) | GovTech; SiliconANGLE; claims D2, D11 | SECONDARY. No company named. |
| Nucleus at The Point, Draper | startup.utah.gov; claims D8 | SECONDARY. |
| "Works with no signal" (rural pin) | offline fallback, `agentd/runs/2026-10-28-offline-fallback.md` | MEASURED on the phone (airplane mode). Rural framing is ours. |
| Screenshot: approval card | `launcher/shots/polish-night-2/after-card-approval.png` ("Tap Send?" with the exact text, Deny / Approve) | REPO. |
| Map | hand-drawn outline; pins approximate | n/a |

---

## If asked: $10K (spoken, no slide)

"Ten thousand turns one rooted lab phone into a ten-person Utah pilot in ninety days. About a quarter buys eight phones so we move off root onto the shippable path, a managed-device app and Android's AppFunctions. About a third funds the pilot itself: ten users through an assistive-technology channel, stipends, a specialist, travel to Logan. A quarter pays an independent red team, so the safety claim isn't just ours. The rest is model credits and the registrations for SBIR. Day 30: off root, open threat model. Day 60: pilot enrolled, red team done. Day 90: results published and a Phase I proposal ready. Beyond money, the ask is an intro to a university co-PI and to USU's assistive technology program."

Breakdown if pressed (our estimate, `pitch/one-pager.md`): $2,500 phones (6 low-cost Android + 2 Pixels) · $3,000 pilot · $2,500 independent red team · $1,000 model credits · $1,000 registrations and legal. Phone prices and $150 stipends are assumptions; SAM.gov registration is free; none of the off-root path is built yet.

## Numbers the founder shows live (not on the slides)

| Number | Source | Status |
|---|---|---|
| Median task ~30 s → ~11 s this week; "Set an alarm for 7 AM" 7.5 s | `agentd/bench/RESULTS.md` round 1 | MEASURED 10-27, typed request, real apps. |
| Voice: 1.7 s from end of sentence to task starting; "done" spoken ~1 s after | `spikes/voice/results.jsonl`, `spikes/voice/LATENCY.md` | MEASURED with a synthetic voice; say "about two seconds". |
| 157+ commits Oct 26–28 | `git rev-list --count HEAD` | REPO; re-run before quoting. |
| 6 of 6 injections flagged on our test set, 0 false flags | `android/guard-tests/README.md` | MEASURED on a small set, both guards together. |
| 26 of 30 tasks fully offline (was 0/30) | `agentd/runs/2026-10-27-local-model.md` | MEASURED on the phone. Start llama-server before the demo. |
| 476 tracker domains in the kernel firewall | `android/README.md` | REPO. |
| Keyboard suggestions 7.3 s → 3.5 s | `keyboard/LATENCY.md` | MEASURED on the phone. |
| Proactive cards 99–100 % precision on 152 threads | `agentd/research/PROACTIVE.md` | MEASURED on labeled threads. |

## Q&A crib (from `pitch/deck.md`, still current)

- **Root isn't shippable.** Agreed: root is the lab. Production paths: device-owner (managed-device) app, a privileged app on a re-locked custom build, Android AppFunctions.
- **Is software hard tech?** The brief lists cyber and AI infrastructure. We work in the keystore, the accessibility layer and the kernel firewall, and we stop a published attack class on the device.
- **One founder?** Yes, plus AI agents building under the founder's review. The $10K adds a co-PI, an assistive-tech specialist and an outside red team.
- **Which AI do you use?** Demo: the founder's ChatGPT plan through OpenAI's Codex app-server, plus a local model; Claude plugs in through an API key. Product: API keys or local models, not personal subscriptions.
- **Known limits to volunteer:** root is above everything; the approval prompt text is drawn by our app (no Protected Confirmation); firewall rules reset on reboot; `task.start` isn't human-approved.

## Deliberately left off the slides
- All task times and speed numbers (above; shown live).
- Company details (LLC, founder residence) and the $10K budget bar (spoken answer above).
- Voice "first spoken reply" (2.2 s) and cold session start; code line count (~19.8k); "$150 phone"; DSPD "$5K per person"; Humane / Rabbit funding figures.
