# Keyboard suggestion latency (hill-climb, 10-28 night)

Goal: shorten the time from focusing a field (or pausing while typing) to the suggestions showing, without making them worse.
All numbers are from the phone (Moto G 5G 2023), in Pigeon, agentd with Codex `gpt-6-luna`, effort `low`, priority tier, on the hotspot.

## How to measure
- **End to end:** `N=6 WARMUP=30 scripts/withphone keyboard/bench/lat.sh`. It force-starts Pigeon, opens a chat, taps the field (focus prefetch), types a real word (typing pause), clears it, and moves to the next chat. It never presses Send and never leaves Pigeon (it stops if Pigeon isn't in front).
  Each shown suggestion set logs one line (`adb logcat -s AgentKeys | grep " lat "`):
  `lat focus total=1960 wait=163 queue=0 screen=454 agentd=1342 render=1 agentd guard=1 queue=0 n=5 hedge=1801 first=1313 done=1323 won=first`
  `total` is from the focus or keystroke to the strip, split into `wait` (settle/debounce), `queue` (keyboard worker), `screen` (screen.read), `agentd` (WebSocket round trip) and `render`. After that comes agentd's own breakdown (`timing` on the `suggestions` reply): `guard` (Prompt Guard regex on the lines), `thread` (a new warm thread was started), `queue` (waiting for the thread's previous turn), `n` (turns on this thread), `hedge` (when the hedge request went out), `first` (first output token), `hfirst` (the hedge's first token), `done`, and `won` (first or hedge).
- **Model only:** `keyboard/bench/model.js`, copied next to an agentd copy on the phone, with its own `codex app-server` (the live agentd is untouched): `node kb-model.js gpt-6-luna low 10 priority 1800` (model, effort, rounds, tier, hedge ms). `node kb-model.js list` prints the catalog.

## Where the time went (before)
| Step | Focus | Typing pause |
|---|---|---|
| Keyboard wait | 300 ms settle | 700 ms debounce |
| screen.read (tool server, incl. Prompt Guard) | 260–450 ms | 260–450 ms, again on every pause |
| agentd guard + queue | 0–25 ms | 0–25 ms |
| Model (Codex round trip) | warm median 1.4–1.7 s in quiet periods, 3–5 s in noisy ones; random stalls of 5–30 s | same |
| First request after an agentd restart or thread rotation (new thread) | 5–25 s | |
| Render | 1–9 ms | |

The model call is ~85 % of the time, and it is mostly waiting: an error reply (unsupported effort) comes back in **0.8 s**, so that is the network + backend floor. The first token arrives only 0.1–0.4 s before the last one, so streaming items one by one would save almost nothing.

## Experiments
| # | Change | Result | Kept |
|---|---|---|---|
| 1 | Measure (instrumentation in Keys.java, agentd `timing`) | Before, e2e: focus 6.0 s on the first try after a restart; warm focus ≈ 0.3 + 0.35 + 1.5 = ~2.2 s; typing ≈ 0.7 + 0.35 + 1.5 = ~2.6 s, with a 5–30 s tail | yes |
| 2 | Faster model: catalog on the phone's Codex 0.163 is gpt-6.1-sol, gpt-6-astra, gpt-6.1-terra, gpt-6-sol, gpt-6-luna, gpt-5.6-sol/terra/luna. No mini/nano. Lowest effort is `low` (`minimal` is rejected; `none` is accepted) | luna warm median 1.4 s; terra 4.5–15 s (worse); gpt-5.6-luna 1.0–3.0 s after warming but 9–25 s cold; luna `none` vs `low` A/B: 2.6/1.7 s vs 3.1/1.6 s, within noise | stay on gpt-6-luna `low` |
| 3 | Priority vs standard tier | priority median 1.4 s, standard 4.0–5.3 s (max 24–30 s) | priority (already) |
| 4 | **Hedged request:** no answer after N ms → the same request on a 2nd warm thread, first answer wins, the other is interrupted | Interleaved A/B, 1.8 s hedge, 3×10 warm calls each: p90 off 3.9 / 2.5 / 2.6 s, on 2.2 / 1.9 / 7.1 s. It fired on half the calls and won 1 of 15: most stalls hit both threads at once (same network/backend). Earlier non-interleaved run in a noisy window: p90 13.9 s off → 2.2 s on | yes, at **3 s** (`SUGGEST_HEDGE`, 0 = off): a cheap retry for real stalls and rotation cold starts, ~10 % duplicates |
| 5 | **Pre-warm** both suggestion threads when agentd starts (one throwaway turn each) | First suggestion after a restart: 5–25 s → a normal warm call. The hedge also hides thread rotation (the two threads rotate at different times) | yes |
| 6 | **Skip screen.read while typing:** reuse the focus read for 30 s while the draft isn't empty (an empty draft, e.g. just after Send, re-reads) | −260 to −450 ms on every typing pause (`screen=0 … screen-reused`) | yes |
| 7 | **Shorter waits:** focus settle 300 → 150 ms, debounce 700 → 400 ms | −150 ms focus, −300 ms typing. Old requests are still cancelled when a new one starts | yes |
| 8 | **Instant prefix:** while typing, any shown reply that starts with the draft stays on the strip at once (QuickType style) while the new request runs | 0 ms when it matches (`lat type … instant N`) | yes |
| 9 | **Shorter outputs:** prompt asks for quick-reply length (first item under 20 chars, the others under 40) and for completions that start with the draft | Item 1 now fits the ~105 dp cell without "…"; output tokens about halved; time change within noise | yes |
| 10 | Stream the first suggestion | first token → done is 0.1–0.4 s, so at most ~0.2 s gained; not worth a protocol change | no |
| 11 | On-phone Qwen3.5-0.8B (llama-server :8081) | 1.8–4.0 s per set on this CPU, weaker replies ("Good luck.", "No"). Slower than warm cloud, so not used online. Used **offline**: no network interface with a routable address (airplane mode) → straight to the local model; Codex error → local fallback (tested: Codex dead → `["Yes, I did.","No, I missed.","What time is it?"]`) | offline only |
| 12 | Per-conversation cache | Already there (key = mode + app + draft + screen lines): refocusing a chat shows its set in **0.46 s** (that's the screen.read) | yes (existing) |

## After
End to end in Pigeon, `WARMUP` first, 6 rounds each, run back to back at 02:00 (both on a slow network: model 2–3 s after, 2.5–11 s before).
| | Before | After |
|---|---|---|
| Typing pause → suggestions | 4.4, 6.3, 8.2, 8.9 s (median ~7.3 s) | 1.9, 2.5, 3.5, 3.6, 3.6 s (median 3.5 s) |
| Typing a prefix of a shown reply | same as above | **0.4 s** (instant, then refreshed) |
| Focus, new chat | 3.2, 11.7 s + 2 not answered within 12 s | 3.4, 3.6 s |
| Focus, chat seen before (cache) | 0.51–0.64 s | 0.46 s |
| First suggestion after an agentd restart | 5–25 s (cold thread) | warm (threads warmed at start) |
| Fixed overhead outside the model (typing) | 700 + ~340 ms = ~1.05 s | 400 + 0 ms = 0.4 s |
| Fixed overhead outside the model (focus) | 300 + ~340 ms | 150 + ~310 ms |

The model round trip is now nearly all of the remaining time; on a quiet network it is ~1.4 s, so typing → suggestions is ~1.8 s and focus ~1.9 s.

## Notes
- The cloud round trip still dominates, and it varies with the hotspot and with other Codex users on the same account (the task bench ran at the same time as most of these numbers).
- Privacy is unchanged: password and no-learning fields send nothing, redaction runs before anything leaves the keyboard, send-safety drafts send nothing, Prompt Guard lines are withheld, and the reused screen is the same redacted, guarded read.
- The hedge sends a duplicate request only when the first one is slow (after 1.8 s, about a third of requests); each is ~1k tokens.

## Calendar-aware replies (10-28, DEMO-WOW #8)

The suggest path now recognizes availability, next-event, meal, flight/arrival, and named-event questions in the latest guarded conversation line. Empty drafts get three local replies, including a concrete calendar answer when known. Typed drafts keep the existing model and prefix behavior, with a small separate `trusted_facts` block. Rewrite, Fix, and Write are unchanged.

### Correctness and privacy

- **Use occurrences, not saved event rows, for availability.** On this phone, memory's Events table said Thursday was free 9 AM–5:30 PM. A read-only CalendarContract Instances query included recurring work and correctly narrowed it to **3–5 PM**. `memory/brain.js` now exports its existing `freeSlots` calculation and accepts these occurrences; its other callers retain their original behavior.
- `suggest-calendar.js` uses the existing memory connector's supported `contentQuery` surface in a worker. It reads 15 days of visible calendar instances, normalizes all-day dates, excludes cancelled events, and respects events marked free. Refresh is every 45 s, with a strict 60 s freshness limit. No synchronous phone read runs on the typing path. No task lease is acquired or replaced: `calendar.find` requires a lease, so it cannot be the keyboard's default background reader. The facts helper still supports that API with a bounded deadline for callers without a snapshot.
- Calendar cloud consent is checked before collecting the snapshot and on every facts lookup/cache hit. Revocation or local-only scope withholds facts. An unavailable/incomplete recurrence snapshot means **unknown availability**, never an empty calendar. Consented saved memory may still provide an event's title, time, and place.
- Only relevant facts leave the phone for a drafted reply. Titles/places pass memory redaction, keyboard-style number/email redaction, and the injection guard; they remain labelled untrusted. Password/no-learning fields and guarded screen lines do not retrieve facts. Empty-draft calendar replies stay entirely local and never send/insert anything.
- Landing time is used only for an explicitly labelled arrival event. A flight event's end time is never assumed to mean landing. There was no matching upcoming dinner or flight in the real-calendar test, so both produced checking replies instead of invented times.
- `cache_ms` expires calendar-backed IME replies after 60 s (5 s for memory-only/unknown results). Ordinary conversation caching, debounce, screen reuse, prefix suggestions, model choice, and hedging are unchanged.

### Measurement and validation

Measured in an isolated on-phone agentd copy with the existing warmed Codex suggestion provider (`gpt-6-luna`, low, priority, 3 s hedge). Injected messages into our own Pigeon Alex chat, captured the actual `screen.read` layers using the keyboard's filtering, and replayed those contexts through `keyboard/bench/calendar.js`. Alternating before/after order reduces network drift. The benchmark measures **facts + suggestion computation**, not screen-read/debounce/UI time; the live service is reserved for the morning integration restart.

The first experiment injected facts into every model call. The model sometimes ignored a known event, and could add several hundred milliseconds. The final empty-draft path constructs its replies locally; the existing model remains responsible for completing typed drafts.

Final empty-draft run, 03:06 MDT, 3 interleaved pairs per question (15 before + 15 after):

| Pigeon question | Before median | After median | After samples |
|---|---:|---:|---|
| What time is dinner? (unknown) | 2,055 ms | 10 ms | 17, 10, 5 ms |
| When do you land? (unknown) | 1,557 ms | 4 ms | 4, 5, 3 ms |
| What is next on your calendar? | 1,072 ms | 3 ms | 20, 3, 2 ms |
| Are you free Thursday? | 1,891 ms | 4 ms | 17, 4, 3 ms |
| Where is judging? | 1,681 ms | 4 ms | 69, 4, 3 ms |
| **All five** | **1,681 ms** | **4 ms** | **2–69 ms** |

Facts preparation alone: median **4.1 ms**, max **64.2 ms**, below the +150 ms budget. Examples: “Thursday 3 PM to 5 PM looks free.”; the next event at 7 AM; “At 250 E 200 S ste 320, Salt Lake City, UT 84111, USA.”; “I'll check my flight details.” No live events were added, edited, or deleted. The 1–2 s background CalendarContract read is outside the request path; before its first snapshot completes, availability stays unknown.

Typed-draft run, 03:10 MDT, the same Thursday question with `before: "I'm "`, 4 interleaved pairs: before **1,592 ms median** (1,542 / 1,227 / 2,774 / 1,642); after **1,769 ms median** (1,601 / 1,937 / 1,224 / 3,115), **+177 ms**. Facts preparation was 45.4 / 32.0 / 2.6 / 10.7 ms. All completions kept the draft prefix; the grounded options included “I'm free Thursday, 3–5 PM.” This is near the approximate +150 ms target, with cloud variance still dominating. The deterministic empty-draft path has no cloud variance.

Validation: `node --check` on every changed agentd file; 13 facts/reply tests plus 13 existing memory fixture tests; keyboard APK built and installed. Tests cover consent revocation, cache windows, recurrence, free events, no false free time from empty memory, redaction, injection, password/no-learning fields, cancellation/deadline, event details, explicit arrival vs flight end, and preserving typed drafts. The requested code-golf skill was not installed; the manual simplification pass shared question selection and replaced a filtered array with `find`.

Deployment: the compatible keyboard APK is installed. Backend testing used `~/agentos/kb-calendar-agentd`; the live agentd was not restarted while other tracks were benchmarking. Morning integration must deploy `server.js`, `suggest-facts.js`, `suggest-calendar.js`, and `memory/brain.js` together, then restart once. Reproduction: copy `keyboard/bench/calendar.js` beside agentd's `config.js` as `kb-calendar.js`; run `node kb-calendar.js <Pigeon-context.json> 3` in that copy. Context is `{type:"suggest", mode:"reply", before:"", field:{pkg:"local.agentos.pigeon",inputType:1}, screen:[...guarded/redacted lines]}`; an array tests several conversations. Respect `scripts/withphone` for injection/capture/install, and release it before model benchmarking.
