# AgentOS: 6-minute script (one line, one thing)

Rule: say the line, do the thing, move on. Approved wording fixes are applied. ⏱ = cumulative time (a target, not a hard limit).

| ⏱ | Beat | Say (one line) | Do | They see |
|---|---|---|---|---|
| 0:00 | **Hook** | "If you're like me, you've thought: why can't Siri do this for me? This is AgentOS, an agent-native phone OS built for maximum control and security. Rapid fire." | Phone on Home | Clean Home |
| 0:20 | **Voice, across apps** | "Watch." | "Hey Agent… find the place Alex sent me and get me directions." | It reads the message in another app, Maps opens with directions, "Done · N s" |
| 0:45 | **Home** | "Home shows what agents are doing, and For You surfaces what I'd forget." | Point at the agent status line + a For You card; tap it | A drafted follow-up |
| 1:05 | **Knowledge graph** | "It links everything it knows across apps, and asks when it isn't sure." | Ask: "What do I know about Alex?" → swipe to What I know / graph | One person merged from Slack, Discord, email and calendar + a "Same person?" card |
| 1:30 | **Micro-app (start)** | "It can build me an app, right now." | "Build me a six-minute pitch timer." | Island: building… |
| 1:35 | **Keyboard (while it builds)** | "It follows me into any app's keyboard." | Gmail: open "Are you free Thursday at 3?" → tap Reply → tap the chip → ✨ polish or translate | "Thursday 3–5 PM looks free", then the polished or Spanish text |
| 2:05 | **Micro-app lands** | "Done. My pitch timer." | Open it, start it | A live countdown (leave it running) |
| 2:15 | **In-app assistant + mods** | "Help inside any app, without leaving it. It can even mod apps; experimental, but real." | LinkedIn → open the bar: "scroll down" → point at "Ad hidden" (applied before stage) | Instant scroll; ads covered |
| 2:45 | **Smart home + clean power** | "It knows my smart home devices and when to run them." | "What smart home devices do I have?" → "When should I turn on my Christmas tree?" | Device card → grid chart: 3–6 PM clean vs 6–10 PM peak |
| 3:15 | **Watch health** | "It uses my Galaxy Watch health data." | "Show me my sleep this week." | Sleep-stage chart + averages |
| 3:30 | **Brains** | "OpenAI or Claude, or an on-device model for privacy and offline." | Command center › Model: tap through Codex / Claude / On-device | The switch |
| 3:45 | **Security, part 1: what it can do vs what it may do** | "It can do almost anything, but it can only ask. Every action is logged." | Command center › Activity | The log in plain words |
| 4:00 | **Trackers & scams** | "It sees which companies every app talks to and blocks trackers and ads, and catches scam messages before the AI reads them." | Protected today → Blockers | Live counters, company names |
| 4:20 | **Approvals** | "An auto-reviewer, the same model OpenAI uses for Codex's auto mode, clears safe steps; risky ones need me; money and installs need my fingerprint." | "Install <small free app>." | "Fingerprint required" card → touch or Deny (the prompt is black on mirroring, so say so) |
| 4:45 | **Prompt injection, live** | "Here's a message with hidden instructions for my AI. Meta's Prompt Guard 2 runs on the phone and catches it, and because of the OS-vs-model split, even a fooled model gets blocked at the system level." | Pigeon (our test messenger): the "Unknown number" chat holds the trap message (stage it with Pigeon's ⋮ › Replay test scenario). Ask: "Summarize my Pigeon messages." | The island's calm amber "Ignored instructions in a message"; the summary leaves the trap out; Activity shows "Scam message caught" |
| 5:05 | **Grok finale** | "I love Grok as an assistant, so mine can reach my phone. It can ask, but the OS decides." | Lock the phone. Laptop Grok: "Use AgentOS: open Spotify and play my liked songs." (or "turn on my flashlight") | The phone unlocks itself, does it, re-locks; music plays |
| 5:40 | **Close** | "The model proposes. The OS decides. That's AgentOS." | Hold the phone up | – |

## If you have spare seconds
- Speed: "Simple commands take under a second" (show the bar's "Done · 0.6 s").
- Airplane mode: turn it on → "turn on the flashlight" → "On device". Risky with flaky Wi-Fi, so only if time allows.
- Image generation in the keyboard takes 40–50 s, so mention it, don't demo it.

## Prep (must do before stage)
1. **Voice beat:** someone (a teammate or your main account) sends the Moto a Slack/Discord DM or email from "Alex" with a place: "Let's meet at Kneaders in Orem at 6." Rehearse the exact sentence once.
2. **Knowledge graph:** pick a person who's in several apps (Slack + Discord + email/calendar) and is OK to show on a big screen. Check "What do I know about <name>?" once.
3. **Keyboard:** email yourself "Are you free Thursday at 3?" so it's waiting in Gmail.
4. **LinkedIn:** apply "Hide promoted posts" before you go on stage.
5. **Prompt Guard:** in Pigeon, ⋮ › Replay test scenario before stage so the trap message is waiting. Run this beat AFTER the fingerprint beat, since a flag makes later sends need a person for 10 min.
6. **Fingerprint:** pick a small free app that isn't installed.
7. **Grok:** laptop on grok.com with AgentOS connected; "Remote can unlock" ON (Command center › Remote).
8. `scripts/preflight.sh --fix wifi,warm,ime,home`, then `--fix dnd,reset`. Close leftover apps; no sign-in page open in Chrome.
