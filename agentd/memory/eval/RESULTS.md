# Memory brain eval (10-27)

**What it is.** `world.js` builds a synthetic multi-app life relative to "now", in the shapes Memory A's connectors write: SMS, calls, calendar with attendees, chat apps arriving through the notification feed (WhatsApp, Messenger, Discord, Slack), media `listen`, Chrome `web`, `usage`, photos. The same person goes by different names in different apps ("Jordan W." on Discord is the contact Jordan Wu; "Linda Gimenes" on Messenger is Mom; "Alex C" on Slack is Alexis Chen, not Alex Rivera). There are two Sams, plans split across SMS + calendar + Discord, an imminent coffee meeting, a planted prompt injection and plenty of noise.
`questions.js` has 38 tuning questions plus 12 held-out ones, written after the brain was built and never tuned on. Each has gold evidence, an optional person, and answer regexes.

- `node memory/eval/retrieval.js [--set heldout] [-v]`: retrieval only, no model. The baseline is `memory_search` with the user's words; the brain is `memory.ask`.
- `node memory/eval/e2e.js --label x [--set all|heldout] [--provider codex|claude] [--agentd dir]`: a throwaway agentd on the Mac (mock phone, eval DB, connectors pointed at an empty fixture dir so they can't read the real phone) with the real model. Each question is a fresh chat. "Do X" requests get a suffix asking the model to say who, which app and what, instead of doing it.
- `node memory/eval/regrade.mjs label...` re-grades saved runs with the current regexes.
- The baseline is `git archive HEAD` (c3474c7) run through the same harness (`--agentd /tmp/agentd-base/agentd`).

## Scores

| | baseline | brain |
|---|---|---|
| Retrieval hit@5, tuning set | 20/37 | 37/37 |
| Retrieval hit@5, held-out | 8/12 | 12/12 |
| Right person ranked first | 4/10 (held-out 0/2) | 10/10 (2/2) |
| End-to-end answers, Codex (gpt-6.1-terra), tuning set | 22/38 | 38/38 |
| End-to-end, Codex, held-out | 12/12 | 12/12 |
| Median time per question (tuning / held-out) | 4.5 s / 4.2 s | 3.1 s / 2.7 s |
| Tool calls per question | 1.6 / 1.5 | 0.18 / 0.08 |
| Model calls per question | 2.6 / 2.5 | 1.2 / 1.1 |
| End-to-end, Claude (CLI), 8-question subset | – | 8/8 |

Honest notes:
- The held-out set shows the brain mostly buys **speed** (one model call instead of 2–3) on easy fact questions. The baseline model gets those right too, just slower. The correctness gains are on cross-app inference: which thread "reply back" means, who "the person I was talking to about X" is, free slots, "this morning" vs last night, counts, most-used, the unknown number, and pseudonymous numbers.
- The e2e harness's "(Eval: just say who…)" suffix: the brain ignores a trailing parenthetical when recalling (`brain.context`), because otherwise those words drown the request.
- Regex grading is strict. Two baseline items were re-graded after their regexes were fixed (abstain, mom_free); both fixes apply to every run.

## Latency added per request (phone, Moto G, real DB ~1,300 events)
- Memory lines in `<phone_context>`: **~15–65 ms warm**. A cold rebuild (people links, closeness, name index) takes ~120–230 ms. It happens after an agentd restart or every 50 new events.
- Tokens: 0–900 characters (~0–250 tokens), only when the request is about the user's life. "Set an alarm for 7" adds nothing.
- On the Mac eval DB: ~1 ms per request.

## On the phone (real data from Memory A's connectors), Codex, no tool calls
- "What do I have tomorrow?": 2.3 s
- "When am I free to see Sam this week?": 4.1 s (free slots computed from the real calendar, plus the lunch with Sam)
- "How many photos did I take this week?": 3.4 s (local model: 0.1 s, the brain's count is said as is)
- "Which app have I used most today?": 2.2 s
