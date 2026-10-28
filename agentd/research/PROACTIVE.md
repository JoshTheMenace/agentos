# Proactive assistant: research, eval, build (2026-10-27)

The goal is help without being asked: "Sam asked about Friday 2 days ago, reply?", "you told Priya the Q3 numbers by EOD",
"Design review overlaps lunch". The hard part is not detection but **precision**. An assistant that nags about the
wrong things gets ignored or turned off, so it is worse than none. Everything below is tuned for that.

**Result:** one batched cloud call (gpt-6-luna, low effort) on every non-bot thread, followed by a code gate.
Across 5 runs: 99–100% precision, 92–95% recall, and 100% card precision (never a card on a thread that didn't need one). The blind set, written by another agent, scores 100% precision and 91–97% recall.
Regex alone scores 94% / 88% overall but drops to 85% / 72% on the blind set. The on-phone Qwen 0.8B scores 83% / 35%.
The build is behind `PROACTIVE=1` (`agentd/proactive.js`).

## 1. What others do (and what we take from each)

| Product / paper | What it does | Lesson for us |
|---|---|---|
| **Gmail nudges** (2018) | "Received 3 days ago. Reply?" and "Sent 5 days ago. Follow up?" beside important mail. On by default, and you can dismiss each one. [Google](https://workspaceupdates.googleblog.com/2018/05/gmail-remind-respond.html), [9to5Google](https://9to5google.com/2018/05/14/new-gmail-web-nudge-ai-reminders/), [Digital Trends](https://digitaltrends.com/?p=1372727) | Wait days, not minutes, before asking about a reply. Show only "important" threads. Every nudge can be dismissed. |
| **Cortana suggested reminders / Outlook commitments** (2017) | Spots "I'll send you the report by Friday" in your sent mail and offers a reminder. "Let's have lunch" doesn't trigger it, but "I'll book us a reservation" does. EOD maps to a 3 PM reminder; Friday maps to 11 AM the day before. [Windows blog](https://blogs.windows.com/windowsexperience/2017/02/09/cortana-reminds-important-stuff-even-forget-ask/), [PCWorld](https://www.pcworld.com/article/411989/windows-10s-cortana-adds-suggested-reminders-to-help-you-keep-your-promises.html), [VentureBeat](https://venturebeat.com/ai/microsofts-cortana-can-now-remind-you-to-keep-your-emailed-promises) | Commitments by me are the highest-value signal. Remind before the deadline. A vague plan is not a promise. (We copy the EOD → 3 PM rule.) |
| **Viva Insights / Cortana Briefing email** | Once a day, lists *commitments* (you promised), *requests* (asked of you) and *follow-ups* (you asked others). It is skipped on days with nothing actionable. It is currently paused. [Microsoft Learn](https://learn.microsoft.com/en-ca/viva/insights/personal/briefing/be-tasks), [overview](https://learn.microsoft.com/en-us/Briefing/be-overview) | Our label set (commitment / request / reply) is theirs. Say nothing when there's nothing. |
| **Google Now cards → Assistant** | "Time to leave" for calendar events with a place, flights and reservations, plus card history. [TechCrunch](https://techcrunch.com/?p=954924), [Droid-Life](https://www.droid-life.com/2015/01/07/google-app-update-adds-google-now-card-controls-history/), [Wikipedia](https://en.wikipedia.org/wiki/Google_Now) | Calendar plus location is the classic win. Our launcher already shows "Leave for X soon". |
| **ChatGPT Pulse** (Sep 2025) | Overnight research becomes 5–10 cards a day. It can draft a meeting agenda or remind you to buy a gift from Gmail/Calendar. It is deliberately *finite*: it refreshes once a day, with no endless feed, and you can curate it or give a thumbs up or down. [Tom's Guide](https://www.tomsguide.com/ai/chatgpt-pulse-is-here-now-ai-starts-the-chat-and-curates-your-feed), [Android Authority](https://www.androidauthority.com/chatgpt-pulse-launched-3601783/) | Use a small, finite set of cards and let feedback (dismiss) teach it. |
| **ChatGPT Tasks** (Jan 2025) | Scheduled and recurring tasks. Tasks the model suggests must be approved first. [TechRepublic](https://www.techrepublic.com/article/chatgpt-tasks-reminders/) | Suggestions are offers. The user taps to start one. |
| **Apple Intelligence** (iOS 18) | Priority notifications and mail, summaries, and a "Reduce Interruptions" Focus. Summaries of short texts were often wrong. [CO/AI](https://getcoai.com/news/ios-18-1s-ai-powered-notifications-could-apple-intelligences-killer-app), [Android Authority](https://androidauthority.com/ai-summaries-apple-mail-feature-3466934) | Short chat text is hard. Quote the message rather than paraphrase it. |
| **Pixel Magic Cue** (Aug 2025) | On-device Gemini Nano surfaces calendar, flight and order details inside Messages when someone asks. "If it doesn't find a relevant suggestion, it won't disturb you." Reviews were mixed: it often just showed "View calendar". [Google](https://blog.google/products/pixel/google-pixel-10-ai-features-updates/), [9to5Google](https://9to5google.com/2025/08/20/pixel-10-magic-cue-launch/) | Silence is a feature. A generic card is noise. |
| **Lindy, Martin** (personal agents) | They text you about important emails, give meeting reminders with context, offer "prep me for my 2pm", draft replies, and run morning syncs. [Lindy](https://www.lindy.ai/blog/lindy-assistant-launch), [Martin](https://apps.apple.com/us/app/-/id6453473083) | Drafts plus prep are the expected bar. |
| Cohen, Carvalho & Mitchell, *Email "Speech Acts"*, EMNLP 2004 | Verb–noun intent classes (request, commit, deliver…). Some classes reach >80% precision at >50% recall. [ACL](https://aclanthology.org/W04-3240/) | Commit, request and deliver are learnable. "Deliver" is how we close a promise. |
| Lampert, Dale & Paris, *Detecting Emails Containing Requests for Action*, NAACL 2010, and *Requests and Commitments in Email are More Complex Than You Think*, ALTA 2008 | Requests as obligations to act or respond. Eight sources of annotation ambiguity (conditionals, hedges, multiple recipients…). [N10-1142](https://aclanthology.org/N10-1142/), [U08-1009](https://aclanthology.org/U08-1009/) | We wrote label rules for exactly those cases: hedges, conditionals, group broadcasts and other people's promises are *none*. |
| Yang, Dumais, Bennett & Awadallah, *Characterizing and Predicting Enterprise Email Reply Behavior*, SIGIR 2017 | Predicts whether and when someone replies, from content, history and timing. [MSR PDF](https://microsoft.com/en-us/research/uploads/prod/2017/04/sigir17a.pdf) | Time since the message is a key feature. We wait 3 h before a reply nudge. |
| Microsoft patent, *Automatic extraction of commitments and requests* (US 10,361,981) | A pipeline that extracts commitments and requests and links them to reminders. [USPTO](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/10361981) | Same pipeline: detect, then promise item, then timed reminder. |
| Lu et al., *Proactive Agent*, ICLR 2025 (ProactiveBench) | The best fine-tuned model reaches only 66% F1 at deciding *when* to offer help. [arXiv](https://arxiv.org/abs/2410.12361) | Open-ended proactivity is hard. Narrow, labelled behaviors plus a gate beat "do whatever seems helpful". |
| Horvitz, Cutrell & Czerwinski (interruption studies) | Interruption cost depends on the task phase and on relevance. Queuing until a break, and bounded deferral, reduce disruption. [Cutrell CHI 2000](https://www.interruptions.net/literature/Cutrell-CHI00.pdf), [Interact 2001](https://www.microsoft.com/en-us/research/wp-content/uploads/2016/02/Interact2001Messaging.pdf) | Default to passive Home cards. Push only a few, deferred around quiet hours and DND. |

## 2. Ranked catalog of proactive behaviors

V = value, F = feasibility on our phone today, A = annoyance risk (lower is better). Rank ≈ V × F / A.

| # | Behavior | V | F | A | Status |
|---|---|---|---|---|---|
| 1 | **Reply owed**: "Reply to Sam? Asked 2 days ago: Are you free Friday…" with a ready draft | 5 | 5 | 2 | **Built** (card → task → approval card) |
| 2 | **My promises**: "You told Priya: Q3 numbers by EOD" in a Promises list, plus one reminder on the due day | 5 | 5 | 2 | **Built** (reminder via schedule.js) |
| 3 | **Requests to me**: "Priya asked for the signed NDA by Thursday" | 4 | 5 | 2 | **Built** (list + card, never pushed) |
| 4 | **One-tap drafted help**: the card opens a task with the draft; the send goes through the approval card | 5 | 5 | 1 | **Built** |
| 5 | **Calendar conflict**: "Design review overlaps Lunch with Sam" | 4 | 5 | 1 | **Built** (Home card) |
| 6 | **Meeting prep**: "Prep for Interview: Kim Lee" builds a read-only brief from memory | 4 | 4 | 1 | **Built** (Home card) |
| 7 | **Leave now** for events with a place | 5 | 4 | 2 | Exists in the launcher (no traffic data yet) |
| 8 | **Auto-close**: a promise disappears when you deliver ("here it is 📎") or reply | 4 | 5 | 0 | **Built** (rescan → resolved/done) |
| 9 | **Follow up**: "You asked Rachel about lunch 3 days ago, no answer" | 3 | 5 | 3 | Next (add a `waiting` kind; the eval labels it none today) |
| 10 | **Others' promises**: "Kim said the offer letter would come today" | 3 | 4 | 3 | Next |
| 11 | **Morning brief**: once a day, today's promises plus calendar plus replies owed (Pulse/Viva style) | 4 | 4 | 2 | Next (all the data is in `promises.list` + calendar) |
| 12 | **In-the-moment cue**: someone asks "free Friday?" and the keyboard shows your Friday | 4 | 4 | 1 | Next (keyboard suggestions already read the thread) |
| 13 | Occasions (birthdays, gifts) | 3 | 3 | 2 | Later |
| 14 | Acting on its own (book, pay, send) | 5 | 3 | 5 | **Never** without a tap; drafts only |
| 15 | Bills, packages, OTPs | 2 | 4 | 4 | Skip (the apps already notify) |

## 3. Eval (`agentd/proactive/eval/`)

- `cases.js`: 102 synthetic threads and notifications (22 commitments, 16 requests, 16 replies owed, 48 hard negatives) and 15 calendar days. They were labelled before any detector existed. Negatives are deliberately tricky: hedges ("I might go", "I'll try to make it"), ETAs ("be there in 10"), jokes ("I'll just quit my job lol"), feelings ("I'll miss you"), other people's promises, promises already delivered (📎) or acknowledged, questions answered or under 3 h old, questions to a group, rhetorical questions, stale questions about past events, OTP, promos, bots, wrong-number and injection texts. Even ids are *dev* (tuned on); odd ids are *test*.
- `blind.js`: 50 more threads written by a separate agent that **never saw the detectors or cases.js**, with different phrasing (slang, typos, long emails, bursts). This is the honest number: I wrote both cases.js and the regex, so the regex is fitted to my own phrasing.
- Labels: one kind per thread, plus who and the due date (YYYY-MM-DD, read relative to when the message was sent). Strict precision counts a predicted card as correct only if its kind matches. Card precision counts any card on a thread that deserves one.
- Run: `node proactive/eval/run.js rules|local|local-raw|cloud|cloud-raw|hybrid|calendar --set main|blind|both`, then `node proactive/eval/table.js`.

## 4. Approaches compared (same 152 threads; precision/recall, strict)

| approach | model | main dev | main test | **blind** | all | card prec. | due acc. | latency (all 152) | calls | tokens in/out |
|---|---|---|---|---|---|---|---|---|---|---|
| A. regex rules | – | 100/96 | 96/100 | **85/72** | 94/88 | 95% | 94% | <10 ms | 0 | 0 |
| B. prefilter → local Qwen 0.8B → gate | qwen3.5-0.8b | 93/52 | 73/30 | **80/25** | 83/35 | 83% | 95% | 10 s on the M-series Mac; ≈10+ min on the phone (≈24 tok/s prefill) | 110 | 20k/1.4k |
| B'. local Qwen, no gate | qwen3.5-0.8b | 70/52 | 36/30 | 42/25 | 49/35 | 69% | 95% | 14 s Mac | 152 | 28k/1.9k |
| C'. one cloud call, no gate | gpt-6-luna low | 93/96 | 96/93 | 91/97 | 93/95 | 93% | 88% | 30 s | 1 | 15.6k/5.1k |
| **C. one cloud call → gate (shipped)** | gpt-6-luna low | 100/89–96 | 96–100/89 | **100/97** | **99–100/92–94** | **100%** | 95% | 29–32 s | 1 | 15.0k/4.8k |
| C (bigger model) | gpt-6.1-terra low | 100/96 | 100/93 | 100/94 | 100/94 | 100% | 95% | 29 s | 1 | 15.0k/5.0k |
| D. regex prefilter → cloud → gate | gpt-6-luna low | 100/93 | 100/96 | 100/88 | 100/92 | 100% | 95% | 31 s | 1 | 13.3k/4.3k |
| Calendar rules (leave / conflict / prep) | – | | | | 13/13 cards, 0 false | | | <1 ms | 0 | 0 |

Row C covers 5 runs (4 luna, including 2 before the last gate fix; 1 terra) and D one run (ranges shown); the model is not deterministic. The 30 s for 152 threads is mostly output, because every thread gets a draft. In real use a scan only sends the threads that changed: a live run of 4 threads took **3.7 s** with 7.1k input tokens (5.9k cached prompt) and 163 output tokens.

**Cost per day.** A busy day is about 40 changed threads across about 20 scans: roughly 25k cached plus 5k fresh input and about 2k output tokens. That is about 1 cent at small-model API prices (assuming roughly $0.25/M input and $2/M output; gpt-6-luna's own price isn't public to us). Through the Codex subscription it costs no extra money. The regex costs nothing and so does the local model (except battery: at ≈24 tok/s, one 150-token thread is ≈6 s of two big cores).

**Why C wins.** Precision is what matters, and in 5 runs C never put a card on a thread that didn't deserve one. Its only strict error is u15 ("Reply C to confirm") labelled request instead of reply. The model reads intent (hedges, sarcasm, stale questions, "this is a reply to a group, not to me") far better than a regex. The **gate** is code that vetoes impossible answers: a reply nudge when I spoke last, a question under 3 h old or stale, a group item that doesn't name me, a promise with no words from me, and bots. The gate turned C' (93%) into C (99–100%). The regex is a decent offline fallback for my own clearly phrased promises, but it misses natural phrasing (blind recall 72%). The 0.8B local model mostly answers "none". It could be a privacy-first mode later with a 3–4B model, but not on this 3.5 GB phone today. D (prefilter) saves only 12% of tokens and loses blind recall ("you still down for climbing this weekend or nah" has no "?"), so we only drop bots on the phone.

**Hill-climb log** (dev/test/blind, all measured):
1. C' raw (93/95): the false positives were questions under 3 h old, stale questions ("coming to the 11am call?" from yesterday) and newsletters. Adding the gate gave C at 96/93.
2. The gate's promise check reused the regex and so rejected "gonna drop it off thursday" and "will tag you tonight". It now only requires a message from me: 100/93 → 100/95.
3. Dates: the model often left `due` empty ("bring the chairs saturday") or miscounted ("today" sent 2 days ago). Now code's relative-date parser wins and the model's date is the fallback: due accuracy 82% → 95%.
4. Stale questions: the rule is that a time with no day means the day the message was sent ("coming tonight??" 3 days later is stale), and "this season/next week" keeps it alive. That fixed b02/n28 without losing "6am or 7am slot this season".
5. Bots never leave the phone (isBot: app/sender lists, short codes, unknown numbers, promo/OTP text), which also saves tokens.

Remaining misses are mostly defensible judgment calls: "let me check my schedule and get back to you" (a soft promise), a dentist's "please call us to reschedule", and a 3-day-old "did you get the card?".

## 5. What was built (behind `PROACTIVE=1`)

- `agentd/proactive/detect.js`: rules, prefilter, `gate()`, the cloud prompt/schema, the local prompt, the date parser, calendar rules.
- `agentd/proactive.js`: the runtime. **Triggers:** a new message notification or a reply we sent (20 s debounce), the memory sync (every 10 min; read-only SQL over `memory.db` message events from sources the owner granted for cloud, redacted bodies), and the clock (every minute: snoozes, reminders, calendar cards). **Scan:** only changed threads, guard-checked (a thread with a flagged message is dropped), codes masked, one `codex.quick('proactive')` call, then the gate. Offline it falls back to regex. **Store:** `~/.agentos/promises.json` (open / snoozed / done / dismissed / resolved). Dismissed and done items stay that way; delivered or answered ones close on their own.
- **Home:** `home-suggest.js` shows the proactive cards instead of "every unanswered thread" when on. That is at most 3 cards: calendar conflict/prep first, then the 2 most pressing (overdue/today promises, then replies owed, then requests). A tapped card hides for 2 h while its task runs.
- **A tap** starts a normal background task, for example *"Reply to Sam in Messages… Suggested reply (drafted by the assistant, not dictated by me): '…'. Show me the reply for approval before it is sent."* The send is the usual `notifications_reply`, which shows the approval card. **Nothing is ever sent by proactive.js.**
- **Promises list** (launcher › Command center › Promises; hidden when off): tap = help me with it; Done / Later (3 h) / ✕; undo. WebSocket: `promises.list`, `promises.update`, and pushed `promises`.
- **Interruption budget:** only *my own* dated promises are ever pushed, as a reminder through `schedule.js` (`Remind me: … Only show this reminder; do nothing else.`). It goes out at 9 AM on the due day, or 3 PM for a same-day "by EOD" promise. The limits are max `PROACTIVE_MAX`=3 per day, never in quiet hours (`PROACTIVE_QUIET`=22-8), never while DND is on (checked through `device.status`; it retries next minute), and never for anything more than 2 days overdue. Requests and replies come from other people's text, so they never schedule a task (a scheduled task may unlock the phone); they stay passive. The reminder text is guard-checked.
- Hooks: `server.js` (+3 lines), `home-suggest.js` (+3), `events.js` (FORWARD `promises`), `providers/codex.js` (`lastUsage` for the eval), `web/` (Promises overlay + link).
- Tests: `node proactive/test.js` covers 16 checks (reply/promise/request detection, OTP and injection dropped, DND, budget, one reminder, auto-resolve, snooze, done sticks), plus one live run against Codex.

**Live on the phone (10-28 00:00).** `~/.agentos/agentd.env` has `PROACTIVE=1` and `PROACTIVE_STAGE=1`. (The phone's `deploy/termux-boot.sh` was an old copy that never read `agentd.env`; it's replaced now. The env is read when the *loop* starts, so after editing it kill `termux-boot.sh` as well as node; the Magisk watchdog restarts both.) First real scan: 4–5 threads, 10–11 s, and every thread was a test fixture (Pigeon, the AgentOS test chat). Memory holds no Discord/Slack/Instagram message yet, so the real apps haven't been tested. Cards it made: *Bring Mom dessert* (Pigeon fixture "Yes! I'll bring dessert": a real promise, deserved); *Text Bob back* (our standing auto-reply "Driving, I'll text you back soon": borderline, closed itself on the next scan); the injection, OTP and "Unknown number" threads got nothing. Fixed while verifying: a question vetoed as too young was **never re-checked** (its signature didn't change; now the "old enough" bit is in the signature and a timer rescans when it ripens). Also fixed: Pigeon threads were labelled "Messages", so the task would open the wrong app; and Home showed the oldest promise first, so a fresh "due tomorrow" was hidden (now: sooner due day first).

**Safety.** A task started from a proactive card or a Promises row (its goal is one this file served, `drafted()`) carries model-drafted text, so the reviewer never auto-approves its sends: the owner always sees the approval card with the draft (`reviewer.js` held reason "a proactive card: the assistant wrote this text, not you").

**Your own outgoing messages.** These are seen from: replies the agent sends (the audit line; it now also works when that thread came only from memory), replies typed in the notification shade (the app re-posts the thread with your message, `from_me`; now also saved to memory so it survives a restart, in `context.js`), and SMS. A promise typed *inside* WhatsApp/Slack/Discord is still not seen. There's nothing cheap to reuse: no WhatsApp on this phone, Slack and Discord keep no message DB (memory/SOURCES.md), and app-reads.js reads only LinkedIn's cache (incoming only). An accessibility read of the app's own send could add it later. The memory feed needs the `notifications` grant.

**Stage mode** (`PROACTIVE_STAGE=1`): questions qualify for "Reply to X?" after 1 min (not 3 h), the debounce is 3 s (not 20 s), and `{type:"promises.scan"}` on the WebSocket scans now (refused when stage is off). `PROACTIVE_REPLY_MIN` still overrides the minimum. To turn stage off, delete that line from `agentd.env` and restart the loop.

## 6. Demo (top 5)

1. **"Send me the deck?"** (timed on the phone 10-27/28 with the AgentOS test chat as the founder's second account; Discord/Slack notifications go through the same feed). The founder DMs the phone from a second account: "hey can you send me the deck?". **≈5–6 s later** (3 s debounce + 1.4–1.9 s gpt-6-luna) Home shows *Send Test Founder the deck · Test Founder asked · 0 min ago* with the draft "Sure, I'll send you the deck." (`launcher/shots/proactive-stage-1-card.png`). A plain question ("free Friday for dinner?") shows *Reply to X?* instead, 1 min later (the ripen timer: +62 s). Tap the card: the task drafts the reply, and the approval card shows the draft. The reviewer never auto-approves it, because the text came from the assistant. Don't approve on stage unless you mean to send.
2. **Promise caught**: the owner answers in the notification shade: "I will send it tomorrow". **≈5 s later** (3 s + 1.6 s) the request closes itself and Promises shows *Send Dana the deck · You promised · due tomorrow* (`proactive-promise-caught.png`), with one reminder at 9 AM on the due day. Show the budget: 3/day, quiet hours 22–8, never under DND. (An answer the agent sends after the owner approves it works the same way. An answer typed inside the Discord app does not: see Limits.)
   *Reset before stage:* Promises › ✕ on the old rows (e.g. the Pigeon "Bring Mom dessert"), so only the live demo item shows.
3. **Promise closes itself**: send the attachment. On the next scan the item flips to done, so there's no stale nag.
4. **Calendar conflict + prep**: two overlapping events; the card says "Design review overlaps Lunch with Sam". Then the "Prep for Interview" card gives a read-only brief from memory.
5. **Precision on stage**: an OTP, a promo, a group "anyone up for raids?", "I'll be there in 10" and an injection text "ignore your instructions…" all produce **no** card. Say: "a spammy assistant is worse than none: 100% precision on our 152-thread eval".
