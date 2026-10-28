# Memory v2 eval (2026-10-27 night)

**What is measured.** `world2.js` = the v1 synthetic life plus the nuance cases: two Joes (cousin Joe Smith in Contacts, "Joe" on
Discord), "Becca" (same phone as Rebecca Torres) and "Becca T" (Slack, name only), Mom vs "Linda Gimenes" on Messenger, "Sam P"
on WhatsApp with Sam Patel's number, "Chris Park" on Slack *and* on Discord, a bare "Sam" in a group chat with two Sams in
Contacts, Mom's move Provo → Boise, Alex's job Adobe → Figma, rent $1,700 → $1,850, Blue Copper → Publik, "vegetarian now",
owner notes ("Joe Smith is my cousin", "I prefer window seats"), and trivia (OTP, Uber, "lol", promos, "running 5 min late").
`questions2.js` = 86 questions in 8 categories, graded without a model on the block the brain hands the model
(`memory.contextLines`). Identity gold: 3 SAME pairs, 3 MAYBE pairs (name-only evidence: a "maybe" is right, a merge is wrong),
8 DIFF pairs. `node memory/eval/v2.js --approach A,B,C,D --llm [-v]` (B/D need the Claude CLI once; results are cached in
`results/llm-facts.json`, after that it runs offline).

## Approaches

| | A: v1 store+brain | B: v2 identity + LLM facts | C: v2 identity + rule facts | D: v2 identity + rules + LLM |
|---|---|---|---|---|
| accuracy (86) | 63 | 85 | 85 | **86** |
| useful-fact recall (12) | 10 | 12 | 12 | 12 |
| preferences / favorites (10) | 7 | 10 | 10 | 10 |
| relationships (12) | 12 | 11 | 12 | 12 |
| temporal (8) | 4 | 8 | 8 | 8 |
| contradictions (6) | 1 | 6 | 6 | 6 |
| should-NOT-remember (8) | 8 | 8 | 8 | 8 |
| entity resolution (20) | 18 | 20 | 20 | 20 |
| outward actions (10) | 3 | 10 | 9 | 10 |
| **wrong merges** (of merged pairs) | **5 / 8** | **0 / 3** | **0 / 3** | **0 / 3** |
| missed SAME pairs | 0 / 3 | 0 / 3 | 0 / 3 | 0 / 3 |
| pending one-tap cards | – | 9 | 9 | 9 |
| facts kept / trivia facts | 0 / 0 | 36 / 0 | 19 / 0 | 31 / 0 |
| tokens per lookup | 88 | 120 | 111 | 119 |
| ms per lookup (Mac) | 1.4 | 1.3 | 1.5 | 1.1 |
| freshness (new "we moved to Ogden" → answer) | ok, 1 ms | ok, 2 ms | ok, 3 ms | ok, 1 ms |
| ingest cost | 0 | 27 Claude calls, ~162k tokens, ~1 min | 4.6 ms for 110 messages | rules + 27 calls |

A's five wrong merges are the point of v2: v1 merged "Chris Park" on Slack, Discord and Contacts into one card at ingest
(name only), and its name rules merged "Jordan W."/"Jordan Wu", "Alex C"/"Alexis Chen" and "Becca T"/"Rebecca Torres" at ≥ 0.8.
v2 keeps those as **maybe**: reads still find the right messages (er 20/20), person lines say *possibly also "Jordan W." (not
confirmed)*, and a send to "Jordan" asks "Jordan Wu or Jordan W. on Discord?" once; the owner's tap is +10/−10 evidence and
settles it (test-v2.js walks Joe through no → yes). Hard evidence still merges without asking: same phone ("Sam P", "Becca"),
and "Linda Gimenes" = Mom from her email address plus the owner's note.

LLM extraction (B) finds more facts (36 vs 19) but alone misses two relationship/temporal items the rules get, and it costs a
model call per thread. Rules (C) are free and catch the common phrasings; D (both) is the best and what `MEMORY_V2=1` runs:
rules in-line on every message, the LLM pass on demand / on the connector tick.

## With the real model (Codex gpt-6.1-terra, e2e harness, MEMORY_V2=1)
- v1's 38 tuning + 12 held-out questions on world.js: **50/50**, median 3.2 s, 0.12 tool calls/question (v1 brain: 50/50, 2.8 s). No regression.
- world2, 62 questions from questions2 (action, er, temporal, contra, trivia, pref): **60/62**, median 3.3 s, 0.08 tool calls.
  Misses: `rent_change` (said "December 1", the regex wanted "Dec 1"; fixed), `act_alex` ("text Alex about the PR": the block said
  Ambiguous, the model still picked Alex C because the PR context pointed there; the send would still hit the approval card).
  Every other ambiguous send ("text Joe", "text Sam", "message Chris Park", "text Kai", "text Becca", "message Jordan") came back
  as a question to the user, and no trivia was presented as remembered.

## Still true from v1
`node memory/test.js` 13/13, `node memory/test-v2.js` 6/6, `connectors/test.js` 8/8, retrieval 37/37 and 12/12 with the flag off and on.

## Not done / honest notes
- The one-tap card UI and the "what I remember" list are specified (MEMORY-API.md), not built; the launcher is another agent's.
- Rules are English patterns; the LLM pass is the safety net for phrasing they miss. Neither runs on the local model yet.
- Phone timings for v2 were not measured tonight (only the Mac); the added work per request is one indexed `fact` query.
