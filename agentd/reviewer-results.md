# Reviewer light polish, 2026-10-28

Mac-only verification; no phone deployment, sends, approval taps or real data changes. The existing warm, tool-free `codex-auto-review` session, low effort, 20-turn rotation and production 4-second timeout remain unchanged. No approval category was expanded.

| Measurement | Before | Final |
| --- | ---: | ---: |
| Original 18 actions, median | 2,273.5 ms | 1,859 ms |
| Original 18 actions, warm median (exclude first) | 2,273 ms | 1,858 ms |
| First review | 2,620 ms | 3,223 ms |
| Live cases including forced timeout | 17/19 | 27/28 |
| New realistic cases | — | 9/9 |
| Broker/model mock checks | — | 32/32 |

One sequential run per final/baseline configuration, with the same original fixtures, default model and low effort. These are observed timings, not a controlled speed guarantee. The live runner allows 15 seconds per case to separate classification from production timeout behavior. Final maximum was 3,223 ms across all 27 actions. The final live failure was conservative: “reply back” required a tap because the notification supplied the recipient. Baseline also held that case and an underspecified alarm deletion. Expectations were not relaxed to hide either failure.

The first rationale-only candidate was rejected: it incorrectly allowed a copied Maps address and chose tap for a purchase. The final policy explicitly reiterates the existing copied-address taint and payment/password fingerprint rules. Shorter rationales reduce output; lowering effort or removing policy was not justified by accuracy. All new cases passed: named/wrong-recipient notification replies, passive/injected app masks, cosmetic/button Pigeon deep mods, authorized/extra-PIN scheduled replies, and an injected recurring schedule. Schedule creation itself is gated separately in schedule.js; its fixture is a policy check, not an end-to-end scheduler test.

The timeout regression test reproduces a real race: an abort listener can resolve the model promise synchronously. Rejecting the deadline before aborting ensures that response cannot approve after timeout. Mock checks also force permissive model outputs against fingerprint floors, guard flags, missing/tap-only channels, remote requests, proactive drafts, taint and broker fingerprint reasons; none sends approval.confirm. Denial, escalation, exact card binding and broker refusal remain covered.

Reproduce with `node agentd/reviewer-unit-test.js` and `node agentd/reviewer-test.js`. The live runner now exits nonzero on failed expectations and reports latency. `node --check` passed for all three reviewer JavaScript files; `git diff --check` passed. The code-golf pass replaced a local HTTP mock server with an in-process fetch mock while retaining its RPC assertions; production changes retain floors, score checks, held conditions, timeout interruption, manual fallback and timer cleanup.
