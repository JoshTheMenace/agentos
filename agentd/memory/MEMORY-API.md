# Memory v2 API (for the proactivity agent, UIs and agentd)

`import * as memory from './memory/index.js'`. Everything is synchronous, reads `~/.agentos/memory.db` (`MEMORY_DB`), and
respects consent: a `ctx` of `{ cloud: true }` (default) returns redacted text (`[P:n]`/`[E:n]`), `{ cloud: false }` raw.
Set `MEMORY_V2=1` in agentd's env to turn v2 on (identity.js + facts.js). Without it, v1 behaviour is unchanged and the
v2 calls below still work but see no facts and no identity links. Nothing here throws on an unknown name: you get `null`
or `[]`.

## People
- `memory.person(idOrName, ctx)` → `{ id, name, also, maybe, apps, phone, email, closeness, relationship, facts, history, identity: { pending } } | null`
  - `also`: other names that are confirmed the same person (same phone/email, owner said so).
  - `maybe`: cards that are *possibly* the same person (name-only evidence). Reads may combine them; never send to one meaning the other.
  - `relationship`: "cousin", "mom", "landlord"… (owner-stated or extracted). `facts`: active durable facts; `history`: superseded/retracted.
- `memory.identity.target(name, ctx)` → `{ ok: true, person: { id, name, cards, contact, maybe }, note? }` or `{ ok: false, ask: "Which Joe: Joe or Joe Smith?", candidates }`.
  **Call this before any outward action to a person named in free text.** `ok:false` means show the owner a choice; `note` means "possibly also X, not confirmed".
- `memory.identity.pending(ctx, limit)` → `[{ a: {id, name}, b: {id, name}, score, why: [...], question }]`: the one-tap cards.
- `memory.identity.confirm(aId, bId, same, ctx)` → the resulting link. Owner answers are evidence rows (+10 / −10) and are final until changed.
- `memory.recentPeople(ctx, n)` (v1): who the owner talked to lately and in which app.

## Facts
- `memory.facts({ q, about, history }, ctx)` → `[{ id, name, pred, obj, text, since, until, status, event_id, by, conf, kind }]`
  - `pred`: `lives_in | address | works_at | relationship | birthday | diet | allergic_to | favorite:<thing> | likes | dislikes | prefers | wants | plan | project | rent | status | occupation`
  - Single-valued predicates (`SINGLE` in facts.js) are superseded by newer values: `history:true` shows the old row with `until`.
  - `event_id` is the source event (show it as "why I remember this"); `by` is `rules | llm | owner`.
- `memory.about(ctx)` → `{ lines: ["You: allergic to shellfish; …", "Mom: lives in Boise; your mom", …], tokens, total }`. Budget via `ctx.budget` (default 300 tokens).
- `memory.correct(factId, { obj } | { retract: true }, ctx)`; `memory.syncFacts(ctx)` (rules, incremental, ~0.1 ms/message; runs automatically in `noteMessage` and `memory_remember`).
- LLM pass (`extract-llm.js`, by `llm`): one slice at a time (each chat, the owner's notes, and digests of the calendar as routines + upcoming plans, of web history and of app usage) goes to Codex gpt-6-luna at low effort, cloud-scoped sources only, redacted bodies. agentd runs it on Codex's warm `quick` thread: a backfill 90 s after start, then every 15 min for new events, at most 10 calls a run and 40 a day (`MEMORY_LLM=0` turns it off). `node memory/cli.js facts-llm [--force] [--max N]` does the same with `codex exec`. Outputs are cached by input hash in `~/.agentos/memory-llm-cache.json`, so an unchanged slice is never sent twice. On the founder's real data (10-28): 12 slices, ~20 facts (13 routines, 7 plans, sometimes works_at / lives_in); the model varies run to run at low effort, and relationship guesses from digests are dropped (a test run called Ashley the bishop).

## Commitments and plans (what the proactivity agent most likely wants)
- `memory.facts({ q: 'plan' })`, `memory.facts({ q: 'wants' })`, `memory.facts({ about: 'Sam Patel' })` (birthday, diet, wants).
- Open asks live in events, not facts: `memory.ask("what did X ask me to do", ctx)` returns cited events; `memory.contextLines(goal, ctx)` is the per-request block.

## Model tools (index.js `tools`, same shape as agentd TOOLS)
`memory_person`, `memory_facts`, `memory_identity {name?}`, read tier; `memory_correct`, `memory_confirm_identity`, outward tier (need the owner's confirmation).

## Launcher wiring (built 10-28: Command center › "What I know", web/app.js)
agentd WebSocket, **owner only** (`fromOwner`: the launcher's uid; the token alone, a model or the keyboard get `{ok:false, error}`):
- `{type:"memory.about"}` → `{type:"memory.about", v2, lines, facts: [...memory.facts({history:true}) + source, at], people: memory.knownPeople()}`
- `{type:"memory.identity"}` → `{type:"memory.identity", pending: [{a:{id,name,apps,rel}, b:{…}, score, why, question}]}`
- `{type:"memory.identity.confirm", a, b, same}` → `{ok, link}`, then fresh `memory.identity` + `memory.about`
- `{type:"memory.correct", id, obj}` or `{…, id, retract:true}` → `{ok}`, then a fresh `memory.about`
- pushed `{type:"memory.asked", names, goal}` when a send hit two people with one name (context.js): Home "For you" shows the matching
  "Same person?" cards for 30 minutes. Cards are calm (Yes / No / Not sure), each fact row shows its source app and day with Fix (inline) and x (forget).
