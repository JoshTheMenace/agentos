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
- LLM pass: `node memory/cli.js facts-llm` or `extract-llm.run(db, { cache })` — off the request path (connector tick when charging/Wi-Fi), Claude CLI haiku, ~2 s and ~3k tokens per thread, cached by input hash.

## Commitments and plans (what the proactivity agent most likely wants)
- `memory.facts({ q: 'plan' })`, `memory.facts({ q: 'wants' })`, `memory.facts({ about: 'Sam Patel' })` (birthday, diet, wants).
- Open asks live in events, not facts: `memory.ask("what did X ask me to do", ctx)` returns cited events; `memory.contextLines(goal, ctx)` is the per-request block.

## Model tools (index.js `tools`, same shape as agentd TOOLS)
`memory_person`, `memory_facts`, `memory_identity {name?}`, read tier; `memory_correct`, `memory_confirm_identity`, outward tier (need the owner's confirmation).

## One-tap identity card (UI spec, no red pop-ups)
Poll `memory.identity.pending()` (or `node memory/cli.js pending`) and render each as a quiet card in the command center / Memory screen:
```
Is "Joe" on Discord the same person as Joe Smith (your cousin)?
  why: first name "Joe" only                                   [Same person]  [Different]  [Later]
```
Tap → `memory.identity.confirm(a.id, b.id, true|false, { actor: 'user:owner' })`. Suggested WebSocket wiring in agentd (owner uid only, like
`memory.consent`): `{type:"memory.identity", a, b, same}` → `{type:"memory.identity", ok, link}`; `{type:"memory.identity.list"}` → `{pending}`.
"What I remember" screen: `memory.facts({ history: true })` grouped by `name`, each row with its `text`, the source event (`memory_timeline`/`event_id`)
and two actions: edit (`memory.correct(id, {obj})`) and forget (`memory.correct(id, {retract:true})`).
