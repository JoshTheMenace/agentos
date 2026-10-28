# Reflexes: one-step asks without the agent turn (Moto G, live agentd, 2026-10-28 noon)

"Scroll down" from the assistant bar used to run a full Codex turn. Now a clear one-step ask runs its one tool call straight
away. Think of a knee reflex: the action happens before the "brain" (the planning model) gets involved. The **gate stays the
same**: the call goes through `runTool` → broker exactly like a model's call (lease, tier, approval card, Rule of Two, audit,
Prompt Guard taint). Only the planning is skipped.

Measured with `node bench/reflex.mjs <label> <runs> <cmd…>`. That sends real bar asks (`in_app` + the foreground `app`, the
same thing Bar.java sends) to the live agentd and times from the ask to `turn.done`. Raw rows are in `results/reflex-*.jsonl`.

| Command (from the bar, in Chrome) | Before: full agent turn, p50 (n=3) | After: reflex, p50 / p90 (n=10) | Path |
|---|---|---|---|
| scroll down | 7.9 s | **0.83 / 0.95 s** | parser |
| scroll up | 5.4 s | **0.83 / 1.01 s** | parser |
| go back | 5.9 s | **0.56 / 0.59 s** | parser |
| go home | – | **0.55 / 0.67 s** | parser |
| open calculator | 6.1 s | **0.59 / 3.13 s**¹ | parser |
| turn on / off the flashlight | 6.8 / 4.3 s | **0.60 / 0.59 s** | parser |
| volume down / up | 6.3 / 5.5 s | **0.62 / 0.60 s** | parser |
| pause the music | – | **1.08 / 1.18 s** | parser |
| "can you scroll a little further down" | – | 2.61 s → **0.87 / 1.00 s** after widening the parser | model → parser |
| "make it louder" | – | **1.79 / 2.02 s** | model pick |

All 130 measured reflex runs did the right thing (10/10 per command). ¹ A cold Calculator start: `awaitApp` waits for its window (≤ 3 s).

## How it works (`agentd/reflex.js`, hooked in `server.js ask()`)
1. **Parser (instant):** whole-sentence patterns only: scroll/swipe up/down/left/right (filler words like "a little further"
   are allowed), back, home, open <app> (matched against the phone's app list; "already open" if it's in front), flashlight
   on/off, volume up/down/mute/N %, play/pause/next/previous, a timer for N units, an alarm with AM/PM or 24 h. Scroll, back
   and home need the bar (they act on the app underneath).
2. **Model pick (~1.5–2 s):** short asks (≤ 9 words) that mention a reflex word but don't parse, and that have no
   and/then/what/send/reply/… in them, go to a warm `codex.quick('reflex')` thread (gpt-6.1-terra, low effort, Fast tier,
   structured output: one tool or `none`). Choice from agentd/research/FAST-DECISIONS.md (the Decisions API needs an API key).
   This model sees only the user's words and the app's name, never screen or notification text, so nothing on screen can
   prompt-inject it.
3. **Anything else** (two steps, questions, sends, unknown apps, "alarm for 7") takes the normal agent turn. No time is added:
   the parser takes microseconds, and the model pick is skipped for those asks. If the broker blocks a reflex (for example,
   the app in front changed), the agent takes over and looks at the screen. If a person denies the card, the reflex stops
   there.

## Fixed overheads cut
- **No context block, no pre-read, no model** for a reflex (the bar path paid 0.5 s for the pre-read and ~3 s per model call).
- **Broker `screen:false`** (`Broker.java`, sent only by reflexes for scroll/press/open_app): return right after the gesture,
  with no settle (≤ 2 s) and no guarded re-read of the next screen (0.5–3 s on a 90-node web page). Broker scroll went from
  1.2–4.7 s to ~0.8 s. Nobody reads that screen, so no third-party text reaches a model. The lease, tier, real-touch pause,
  forbidden-app and audit checks all run before the action, as before.
- **Bug fixed in Broker:** scroll/press without a node checked the lease against the app from the *last screen read*, not the
  app in front (`Screen.pkgNow()` now refreshes the focus when a window changed, ~0 ms if nothing changed). Before the fix, a
  scroll in Chrome after a read of Calculator was blocked ("Calculator is not in this task's lease"). The reverse case
  could let a scroll land in an app outside the lease.
- **`task.stop` after `turn.done`** (`server.js`): when Home came back, the broker took up to 2.7 s to answer `task.stop`.
  The next ask waits for it (`session.ending`), so leases stay in order and a queued ask is never refused as busy.

## Normal path (same agentd, `results/reflex-normal.jsonl`)
"What is this page about" 3/3 (p50 3.5 s), "scroll down and tell me the first result title" 3/3 (3.4 s, agent path as
intended). "Use the calculator app to work out 12 times 3": 2/3. The miss was a lease card that waited 60 s with no tap.
By then Chrome sat on a GitHub sign-in page (my 20 "go back" runs had walked its history there), the guard tainted the task,
and the broker correctly asks a person before a tainted task adds an app. That is the existing gate, not a reflex change.

## Not done
- Local model reflex (c): the parser already covers the phrasings it would catch, and offline it still works (the parser
  needs no network; the model pick runs only when the provider is Codex).
- Timers and alarms were checked by the parser only (no live runs, so the founder's Clock stays clean).
