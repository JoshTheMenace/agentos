# Memory systems: what the good ones do, and what fits a phone (2026-10-27)

Short survey behind `agentd/memory` v2 (`MEMORY_V2=1`). Results of our own approaches are in `agentd/memory/eval/RESULTS-V2.md`.

## 1. Products

**ChatGPT memory** has two layers, each with its own switch. *Saved memories* are explicit items ("your name, favorite color,
dietary preferences") that the model may also add on its own; you can view and delete each one. *Reference chat history*
draws on past conversations and "can change over time as ChatGPT updates what's more helpful to remember"; its example is
"you once mentioned liking Thai food → lunch suggestion". Deleting a chat does not delete memories made from it; Temporary
Chat bypasses memory. ([OpenAI help: saved memories](https://help.openai.com/en/articles/11146739-how-does-reference-saved-memories-work),
[Memory FAQ](https://help.openai.com/en/articles/8590148-memory-faq), [launch post](https://openai.com/blog/memory-and-new-controls-for-chatgpt)).
Takeaway: a small, inspectable list of durable facts + a fuzzier "what you've talked about" layer, both owner-controlled.

**Claude memory** is per *project* (a startup roadmap doesn't bleed into vacation planning), viewable and editable under
Settings, with pause/delete/reset and an incognito chat. Third-party write-ups disagree on the storage shape (a running
summary earlier in 2026 vs. individual categorized entries since mid-2026), and report sensitive categories left out by
default. ([Tom's Guide launch piece](https://www.tomsguide.com/ai/claude-just-unlocked-memory-that-syncs-with-chatgpt-heres-how-it-works),
[fast.io guide](https://fast.io/resources/claude-memory-guide/), [ai-toolbox](https://www.ai-toolbox.co/claude-management-and-productivity/claude-memory-how-it-works-2026)).
Takeaway: scope memory, keep a compact "about you" block the user can read in one screen, exclude sensitive categories by default.

## 2. Frameworks

**Mem0** (Chhikara et al., 2025, arXiv 2504.19413): two phases. *Extract* candidate facts from the latest turns with the
LLM; *update* by retrieving the S most similar stored memories and letting the LLM pick ADD / UPDATE / DELETE / NOOP per
fact. The current release moved to an add-only extraction path because the two-pass loop was slow and overwrites sometimes
destroyed useful memories; explicit update/delete calls are needed for corrections. A third-party study saw ~25% malformed
JSON on ADD/UPDATE/DELETE with one model. ([Mem0 docs: how it works](https://docs.mem0.ai/core-concepts/how-it-works.md),
[architecture write-up](https://medium.com/@zeng.m.c22381/mem0-overall-architecture-and-principles-8edab6bc6dc4),
[arXiv 2606.15903 on placement of the forgetting decision](https://arxiv.org/pdf/2606.15903)).
Takeaway: LLM extraction is the best way to get *useful* facts out of free text, but letting the LLM delete is risky; prefer
"supersede, never delete" and keep the old row.

**Zep / Graphiti** (arXiv 2501.13956): a temporal knowledge graph. Every edge is *bi-temporal*: `valid_at/invalid_at` (when
the fact held in the world) and `created_at/expired_at` (when the system learned/retired it). A new fact that conflicts
with an old one *invalidates* the old edge instead of deleting it, so "where did Mom live before?" still has an answer.
([Graphiti temporal model](https://mintlify.com/getzep/graphiti/concepts/temporal-model), [Zep blog](https://blog.getzep.com/beyond-static-knowledge-graphs/)).
Takeaway: facts need `valid_from/valid_to` + `observed_at`; "now" questions read the open interval, history reads the closed ones.

**Letta / MemGPT** (arXiv 2310.08560): memory tiers like an OS. *Core* memory is a few always-in-context blocks (persona,
user) the agent edits with tools; *recall* is the searchable conversation log; *archival* is external long-term storage.
Newer Letta adds "sleep-time" agents that consolidate memory in the background. ([Letta docs](https://docs.letta.com/concepts/memory-management),
[MemGPT architecture](https://docs.letta.com/guides/agents/architectures/memgpt)).
Takeaway: a ~300-token always-present "about you" block, with everything else fetched on demand; consolidation runs off the
request path (for us: the connector tick).

## 3. Entity resolution (the hard part)

Record linkage à la **Fellegi–Sunter** (1969): each comparison between two records (same phone? same last name? same first
name?) gets a *weight* = log-likelihood ratio of seeing that agreement under "same person" vs "different people". Weights
sum; two thresholds split pairs into **link**, **non-link**, and a middle **possible match** band that goes to clerical
review (a human). Example practitioner cutoffs: non-match < 10, review 10–20, match > 20 on one unit's scale; the scale is
dataset-specific. Known caveat: conditional independence of agreements often fails (first-name and last-name agreement are
correlated). ([starling threshold vignette](https://cran.nics.utk.edu/cran/web/packages/starling/vignettes/threshold-selection.html),
[Bayesian bipartite matching, arXiv 1601.06630](https://arxiv.org/pdf/1601.06630), [metricgate summary](https://metricgate.com/docs/record-linkage)).

What this means for a personal assistant:
- Phone number or email agreement is near-decisive (hard identifiers). A name alone is not: "Joe" on Discord and "Joe
  Smith" in Contacts share one weak agreement, so they land in the *possible* band, never *link*.
- The "clerical review" band is exactly a one-tap card for the owner: "Is Discord Joe the same as Joe Smith?". Owner answers
  are the strongest evidence of all (and a "no" must stick).
- Reads can use possible links (show both cards, say "possibly"); *outward actions* (send, call) to a possible-match person
  must ask first. A wrong read costs a confused answer; a wrong send costs a message to the wrong person.
- Negative evidence matters: two different handles on the same platform are two people; two known, different phone numbers
  are two people; a different last name is strong evidence against.

## 4. What matters on a phone

- **Latency**: memory lines go into every request's `<phone_context>`; the v1 brain is 15–65 ms warm on the Moto G and
  ~1 ms on the Mac. Anything that adds a model call per request is out; LLM work belongs at ingest time, batched, off-path.
- **Tokens**: each 100 tokens of context costs the user time on every request. Target ≤ 300 tokens for the "about you"
  block and ≤ 600 for request-specific memories.
- **Battery**: connectors already skip under 15% battery and never hold a wake lock (SOURCES.md). Rule-based extraction is
  ~0.1 ms/message; LLM extraction costs ~2–3 s and ~3k tokens per batch (Claude CLI, Haiku) and should run only on
  charger/Wi-Fi or on demand. On-device RAG papers agree: keep the graph on the handset, call the model only for high-value
  steps ([SmartRAG, arXiv 2607.14661](https://arxiv.org/abs/2607.14661), [Ruva, arXiv 2602.15553](https://arxiv.org/html/2602.15553v1),
  [MobileRAG, arXiv 2507.01079](https://arxiv.org/pdf/2507.01079)).
- **Privacy**: everything stays in `~/.agentos/memory.db`; cloud models see `[P:n]`/`[E:n]` pseudonyms; facts carry their
  source event so the owner can see *why* something is remembered and delete it; sensitive apps never enter memory.

## 5. Design chosen for v2 (see memory/facts.js, identity.js, RESULTS-V2.md)

1. **Facts table** with Zep-style validity (`valid_from`, `valid_to`, `observed_at`, `status active|superseded|retracted`),
   each fact citing its source event. Single-valued predicates (lives_in, phone, works_at, relationship) supersede; multi-valued
   ones (likes, allergic_to, plan) accumulate. A "worth remembering" gate drops ephemeral chatter (OTP, deliveries, "omw").
2. **Two extractors behind one interface**: rules (free, instant, runs on the phone) and an LLM pass (Claude CLI / agentd's
   model, Mem0-style add/update ops, cached) for nuance the rules miss.
3. **Evidence-scored identity links** (Fellegi–Sunter weights, two thresholds): `same` / `maybe` / `different`; owner
   confirmations are evidence rows; `maybe` is visible for reads and blocks outward actions.
4. **"About you" digest** (Letta-style core block) built from active facts, budgeted in tokens, injected only for life
   questions.
5. **Incremental sync**: a watermark on the last extracted event id; `noteMessage` extracts in-line (rules), the LLM pass runs
   on the connector tick.
