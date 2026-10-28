# Overnight security and correctness review

Reviewed 2026-10-28, America/Denver. Initial scope: `git log --since="2026-10-28 00:40"`, baseline `1e0732c^`, through `21937cb` (36 commits). Read NIGHT-PLAN.md, STATUS.md and ARCHITECTURE.md. STATUS.md's newer approval decisions govern ordinary tap/reviewer versus fingerprint-only actions. No earlier REVIEW-NIGHT.md existed.

This first-pass report was written before any production-code or test changes. No phone access, deployment, real model calls or approval taps. Other agents' dirty files were not treated as reviewed commits.

## Confirmed findings, ranked

1. **High: a task flagged during review can still be auto-approved.** `agentd/reviewer.js:76-83` uses the `flagged` boolean captured by `agentd/server.js:61` before awaiting the reviewer. A guard event or `turn.alerted` update during that await is ignored. Host reproduction: pause `codex.quick`, add a task guard flag and an alerted message, resolve a low-risk/untainted auto verdict; the captured RPC is `approval.confirm`. The broker's existing `confirm` path checks the approval's original channels, not this later guard state. This race predates `a333832`, but remains in the reviewed security path and violates the explicit “never approving flagged actions” requirement. Fix: recheck current task flags and current turn alerts after the grade returns, before confirming. Fingerprint and HUMAN floors must remain unchanged.

2. **Medium: missed voice handoffs never reach the recovery handler.** `agentd/server.js:204` (reviewed HEAD; line 213 with concurrent worktree edits) omits `voice.heard` from its WebSocket switch, although `agentd/voice.js` (`#heard`) implements it and both clients send it (`web/app.js` and `agentd/voice.html`). Introduced by the `a16552e` / `5e00624` voice changes. With `client_handoffs:true`, sideband fallback is disabled; when GPT Live acknowledges without delegating, the client transcript receives `unknown_type` and no task starts. Existing voice tests call Voice directly, missing the server boundary. Fix: route `voice.heard` through the existing handler; test actual dispatch plus late-handoff deduplication.

## Review coverage and initial checks

- Notifications (`6928905`, `660377b`): reply and mark-read enter Broker.act, T3 policy, pending approval and audit. Notification records are checked again for replacement/removal/disconnect before firing the app's PendingIntent. Conversation keys choose the app-owned reply action; text does not supply an arbitrary recipient. New feed objects label message content untrusted, and agentd recursively guards nested message data. Broker fingerprint/HUMAN floors still apply; the reviewer race above is the confirmed exception found in this pass.
- Deep mods (`3430c2e`): strict JSON keys/depth/size/capability limits, exact-byte signed envelope, pinned Binder peers, build/revision/nonce/expiry/epoch checks, native bounded rendering, no eval/shell/custom executable selector. Kill epoch, rollback, preview restore, consent and fixed read-only summary scope were inspected. No confirmed additional high/medium issue found.
- Offline routing (`b516bb5`): request/acted/text-progress checks prevent fallback replay after action progress; cancellation and connected cloud failures do not trigger fallback. Router's 12 host tests pass.
- Reviewer (`a333832`): timeout now rejects before abort can resolve a late success. Existing 32 mock checks pass, including fingerprint floor, taint, remote requests and already-flagged tasks; the mid-review race was absent from them.
- Voice (`a16552e`, `44c8223`, `5e00624`): generation checks and turn/handoff aliases inspected; eight direct-handler tests pass. Missing WebSocket dispatch is finding 2.
- Keyboard (`fd5fb1b`, `91b0fa8`): password/web-password/numeric-password and incognito gating remains; input and screen strings still pass through Agent.redact before model requests. Suggestion hedging and prefix reuse do not add tools. New calendar work was uncommitted during this pass and is outside this pinned review.
- Island/launcher/wake (`8e56541`, `4df084d`, `91b0fa8`): native approval card remains the gate; no new WebView approval-confirm RPC or fake approval buttons. Insets, morph fix, warm voice connection and wake mic handoff inspected. Overlay-mask changes (`1e0732c`) and follow-up syntax repair (`81f2872`) inspected.
- Benchmark/deployment/watchdog changes reviewed as source only, not run. Research, plans, screenshots and timing artifacts reviewed for scope and security decisions; no executable changes in those documentation commits.
- `node --check` passed for all **854** `.js` files recursively under agentd, including installed dependencies. Deep-mod mock suite passed **106** scope/consent/redaction cases. Combined router/voice/deep-mod runner passed 21 tests. No phone validation was performed or claimed.

## Commit inventory

```text
21937cb codex-bench.sh executable
fdb7008 night plan: offline fallback done, overnight review started
8e35921 bench round 3: calculator CLEAR setup, 10 s lock yield, bench-only Codex proxy port (codex-bench.sh) + usb-net.js, model/visible variants
8ac407f night plan: bug hunt done, test alarm turned off, catch-up started
b516bb5 agentd: automatically route offline asks to the on-device model
91b0fa8 bug hunt: launcher morph crash, calm reconnect/failure text in the ask sheet, no Duolingo in tracker UI, memory people count + app labels, scheduled row times, keyboard chips show only new words and drop draft-ignoring replies, tap spots +77 px
f3d97d7 night plan: voice done (deploy in morning), calendar-aware keyboard started
5e00624 voice: use client turn identities for reliable handoff deduplication
44c8223 voice: retain new turns when sideband delivery follows a fallback
a16552e voice: reuse realtime connections and reliably hand off spoken tasks
123f273 night plan: reviewer done, offline fallback started, morning deploy queued
a333832 Polish reviewer rationale and close timeout race
dd1bc5c night plan: research done, polish 2 started, offline fallback queued
4a243ce research: demo wow additions ranked by wow per hour
c139937 night plan: deep mods done, reviewer polish started
6cca5a2 night: phone Wi-Fi drop fix logged; watchdog checks connectivity
3430c2e Add signed Pigeon deep-mod proof with native previews and rollback
258c405 night plan: wake word done, research started
4df084d wake word: hands-free voice mode (Porcupine when keyed, Vosk "Hey Agent" fallback)
01317c0 night plan: keyboard done, bug hunt started
fd5fb1b keyboard: suggestion latency hill-climb (typing pause median ~7.3 s -> 3.5 s on the phone)
59ef5b9 bench round 3: safe read-only task set (Pigeon/Beacon/torch/calendar/notifications/calc), per-task lock with yield, clean HEAD snapshots, model ping, agentd prep timing
f918b28 night plan: notifications done, voice latency started on Sol
660377b Record verified Pigeon mark-as-read timings
2957a5e night plan: log 01:16 alarm near miss
6928905 Add live notification conversations and brokered read actions
5660ec6 night plan: island done, wake word started
8e56541 island below the camera cutout (window insets, no fixed offset); launcher polish: micro-app names wrap, '1 day ago', plugin summaries wrap, Clock alarm audit lines, shorter What I know note
3960c75 night plan: S2 done, S3 started
b0d3a16 research: deep mods for apps we own (cooperative signed-spec runtime recommended)
3985d63 night plan: 40-minute oversight cadence
acb507c night plan: oversight section
473b148 scripts/night-watch.py: read-only overnight watchdog (risky audit actions, calendar shrinkage)
ad29ea5 night plan: overnight tracks, limits, boundaries
81f2872 fix: unescaped quote in tools.js mod spec description (agentd would crash on restart)
1e0732c mods: masks fill with the app's own background, A2 "Ad hidden" chip (tap to peek, hold to remove), smooth scroll tracking clipped to the list
```

## Fix and verification follow-up

Both findings are fixed in this review's changes:

- `agentd/reviewer.js`: after the asynchronous grade, OR in the live broker task-flag set and the turn's current alerts. The existing held-action branch then refuses auto-confirmation. This preserves an earlier flag and all existing fingerprint/HUMAN/remote/draft restrictions.
- `agentd/server.js`: add only the missing `voice.heard` case to the existing voice dispatch branch. Recovery and deduplication remain in Voice.
- `agentd/reviewer-unit-test.js`: two deferred-review regressions independently flag through the broker event set and the turn alerts. Both require zero confirmation RPCs.
- `spikes/voice/voice-test.mjs`: execute the production WebSocket message callback in an isolated VM, deliver the client transcript, advance 600 ms and then send the late delegation. Exactly one task must start.

The new tests were run before the fixes and failed for the expected reasons (unexpected approval.confirm; zero voice tasks). After the fixes: 34/34 reviewer mock checks and 22 combined router/voice/deep-mod tests pass. The live worktree deep-mod suite now has 107 cases because another agent added a tool; the initial pinned suite had 106. The Java host suite passes 39 contract/signature and 15 broker-policy cases; its policy portion uses the existing local compiled Broker classes, so it is not a fresh APK or runtime validation.

`git pull --ff-only` completed with “Already up to date” before changing the shared server file. Only the one dispatch line from that file belongs to this review. No other agent's changes are to be staged. The code-golf skill was not present in the supplied catalog or searches of local Codex, Agents, Claude and T3 skill locations; a manual simplification pass kept the production fix to two added lines and one edited line. No formatter was run.

No phone access or deployment. Morning integration must deploy the committed agentd changes and smoke-test a missing voice handoff and ordinary versus fingerprint/flagged approvals.

Final verification before commit: all **856** live-worktree agentd `.js` files parse. An export of the exact review-only staged tree also passed 34 reviewer checks, 22 router/voice/deep-mod tests (106 deep-mod cases) and syntax checks on all 81 project agentd `.js` files. This excludes the other agents' unfinished code. Concurrent staging was detected, so the review commit uses a separate temporary Git index; their staged files remain intact.

The catch-up agent committed `ed01014` during final staging. Its eight-file diff was also inspected (read-only notification/calendar/promise aggregation, model-only phrasing, normal brokered follow-up tasks, escaped UI rows). No additional confirmed high/medium finding in that diff. The review fixes preserve this new HEAD. The fixed initial review inventory remains the 36 commits through `21937cb`; agents continue landing later work. The voice test now stubs catch-up warm-up so it stays isolated from that independent feature. Final publish checks use the new committed baseline, not the earlier staged tree.

Subsequent UI/plan commits continued arriving during publish. Preserve their changes when committing; they are outside the fixed initial review inventory. In `2f7f170`, the committed catch-up UI listener was removed, but its owner already restored the three-line listener in the live worktree. Morning integration should include that owner’s restoration, not overwrite it.

## Review #2

Reviewed 2026-10-28, America/Denver, starting with the 30 commits in `edc95bb..2af4d18`. Read NIGHT-PLAN.md, STATUS.md, ARCHITECTURE.md and review #1. No phone access, deployment, real model requests or approval taps. Findings below were recorded before production fixes.

### Confirmed findings, ranked

1. **Medium: tracker scans fail after reboot before the firewall chain is created.** `android/src/local/agentos/Trackers.java:173` now calls `Firewall.blockedCounts()` on every scan (`11b2cd7`). At `android/src/local/agentos/Firewall.java:65`, the counter read throws when `AGENTOS_FW` does not exist. Broker startup starts the separate `AGENTOS_SNI` radar; it does not initialize `AGENTOS_FW`. The existing `nameRules()` handles this normal empty state, so scans worked before this change. A fresh scan now errors until something calls `firewall.list` or changes firewall rules. Host reproduction compiled the production Firewall class with a Root stub returning no name rules and the missing-chain error for counter reads; it failed at Firewall.java:65. Return empty counters when there are no name rules. This also repairs the older empty-chain failure in `trackers.list`; approval and firewall write paths need no changes.

2. **Medium: two Stop controls restart queued work after cancellation.** `web/app.js:219` (home Stop) and `web/app.js:423` (command-center Stop) do not clear the queue introduced in `4814e76`. `web/app.js:707` drains it on every `turn.done`, including `interrupted`, so stopping the current turn immediately launches the next queued request. Only the ask-sheet Stop clears it. The host regression executes the actual production callbacks with two queued asks, holds the broker stop RPC open, and delivers `turn.done(interrupted)`; both controls start the next ask, while the ask-sheet control passes. Clear the queue synchronously before sending stop, matching the existing ask-sheet behavior. Broker/reviewer approval gates still apply to a restarted request; this is a cancellation failure, not an approval bypass.

### Coverage and limits

- Approvals/reviewer: no policy downgrade or new confirmation route in this range. Review #1's live-flag recheck remains present; 34 reviewer checks cover taint, remote/drafted requests, HUMAN/fingerprint floors, timeout and flags arriving during grading. The added finishing tools still call `runTool` and require a successful result before ending the turn. No shell, root or built-in approval capability was added to model sessions.
- Untrusted recipients: notification reply resolution and broker confirmation are unchanged. Catch-up remains tool-less while phrasing facts; follow-up cards launch ordinary brokered tasks. Proactive goals still register as drafted and cannot receive reviewer auto-approval. Message text remains untrusted in model context and reviewer transcripts. No confirmed new recipient-selection bypass found.
- Keyboard: inspected the new calendar snapshot, bounded retrieval, redaction, cache expiry and consent checks. Password and incognito gating remains in the IME; the facts provider rejects those fields, write/rewrite modes, guarded questions and non-cloud calendar grants. Availability exposes gaps rather than appointment titles, and saved Events data cannot claim availability without recurring Instances. Thirteen host tests cover these cases. No tool/approval path was added to calendar suggestions.
- WebView: new counters escape names/domains and catch-up rows escape titles/details. Waiting approvals remain status text, with no WebView approval-confirm buttons or RPC. The polish capture scripts can synthesize display events for recordings but are Mac-side test tooling, not a runtime approval surface. Home/IME animation and island changes were inspected as source; no visual/device verification claimed. The queued-ask cancellation failure is finding 2.
- Offline routing: the only router change is the 1 s to 2.5 s reachability timeout. All 12 router tests pass, including no fallback replay after an action request, an acted flag or visible text, and no fallback on cancellation or a connected cloud outage.
- Voice: the new change only adds elapsed task time to completion speech. All nine voice tests pass, including the actual server dispatch, missed-handoff recovery, late-handoff dedupe, session replacement and repeated user turns.
- Trackers/broker: count parsing aggregates IPv4 and IPv6 per UID/domain. `Trackers.plan` still canonicalizes domains against the bundled list and rejects empty plans before `who()` is called. Owner/protective block rules and human-only unblock rules are unchanged. Finding 1 covers the new scan dependency on counters.
- Restart risks: inspected new imports and the catch-up lazy import that breaks its startup cycle. All **857** `.js` files recursively under agentd, including installed dependencies, pass `node --check`. This is host validation, not a live daemon restart. All broker Java sources compile against Android 33 in an isolated temporary output directory.
- Magisk logging (`BUGHUNT-NIGHT-2.md:25`): the documented `UPDATE policies SET logging=0` suppresses Magisk superuser logging for existing policy rows; it does not alter grant policy or notifications. It reduces root-command forensic visibility, an explicit tradeoff for the founder's test phone. Broker hash-chained audit code is unchanged. The stated phone setting and restart-count measurements cannot be verified without phone access; no policy change was made here.
- Preflight, benchmark/deployment scripts and recording helpers reviewed as source only; none were run against a device. Preflight fixes remain explicit, locked and scoped; calendar/alarm candidates are reported, not deleted. Pitch/plan/results commits were inspected for executable changes and scope; PDFs/images were not treated as runtime code or independently revalidated measurements.
- Existing shared broker lease overlap remains documented in bug hunt 2. Stop benchmarks before integration smoke tests. The multi-part finishing heuristic is conservative for its listed conjunctions/punctuation, not a proof that every natural-language compound request is recognized; no additional deterministic failure was established here.

### Fixes and verification

- `android/src/local/agentos/Firewall.java`: reuse the existing cached `nameRules()` lookup and return the empty map when there are no name rules. There are then no name-rule counters to read, and no chain needs to be created by a read. The throws declaration follows the existing helper. Reads with active rules still surface root errors.
- `web/app.js`: clear queued asks before sending stop from the home card and command center. Clearing before the asynchronous RPC matters: an interrupted `turn.done` can arrive before that RPC returns.
- `android/tests/FirewallTest.java`: host-only Root stub, compiled with the production Firewall class. Missing-chain reproduction failed before the fix. Three cases now pass: empty fresh-boot state, IPv4/IPv6 aggregation excluding non-name rules, and failure propagation with active rules.
- `web/queue-test.mjs`: executes production stop/completion callbacks in a VM. The two affected controls failed before the fix; all four cases now pass, including the existing ask-sheet Stop and ordinary completion starting exactly one queued ask.
- Final host checks: 39 combined Node tests (router, voice, calendar suggestions, deep-mod and queue); 34 reviewer mock checks; 107 deep-mod scope/consent/redaction cases inside its suite; 39 Java contract/signature and 15 broker-policy cases using the freshly compiled production classes. `node --check web/app.js` and `git diff --check` pass. No formatter was run.
- `git pull --rebase` initially refused other agents' dirty files. `git -c pull.rebase=false pull --ff-only` then completed with “Already up to date” before each production fix; no other work was stashed. Only these two runtime files, their two host tests and this report belong to this review.
- The requested code-golf skill is absent from the supplied catalog and local Codex/Agents/Claude searches. A manual simplification pass retained one empty-map guard and two queue clears, with no refactor.

Morning integration needs fresh **local.agentos and launcher APKs** for these fixes, in addition to the already planned agentd deploy/restart. No phone action, APK install or deployment was performed. There are no confirmed high findings; both confirmed medium findings are fixed in source.

### Commit inventory

Final reviewed baseline: `edc95bb..8ad4c5d` (31 commits). The later `8ad4c5d` only adds the Wi-Fi incident/keep-alive note and was also inspected. Other agents' uncommitted speed/keyboard work is outside this reviewed range.

```text
250fc27 codex-bench.sh: env copy made once from adb
bb73767 catchup: calendar query fix (no location column), hedged model call, no load-time import cycle
78ddda9 night plan: review done, preflight started
42dca0f night plan: catch-up done, deck draft started
3467473 Add read-only stage preflight with scoped safe fixes
7f968d4 pitch: 5-slide deck draft (A2 Calm Glass, real shots, cited numbers) + refreshed one-pager PDF
0a514e8 preflight: only flag AgentOS-labeled calendar items (founder's own Test/benchmark events are real)
00c93c6 night plan: preflight done
78a3d0b pitch: deck PDF edge to edge, voice numbers, capability-first slide 1, fingerprint wording, commit count from git
5b4a80c pitch: notes.md sources for voice numbers, commit count method, fingerprint wording, swapped screenshots
11b2cd7 trackers: live blocked counter with company names (DEMO-WOW #7) + voice says the task time
34f9624 night plan: tracker + deck done, polish 3 started
787ac15 Add calendar-aware keyboard replies with bounded local context
1a0602e night plan: calendar keyboard done, morning deploy scheduled 6:43
0e8b3b7 UI polish pass 3: feel on stage (screen recordings reviewed frame by frame)
7e01c9a night plan: polish 3 done, integration deploy #1 started
1f76e59 night plan: integration deploy #1, agentd restarted once at 03:40:48
a36a04c catchup: nothing to report answers at once instead of waiting up to 6 s on the model
fb9abad speed r3: type into fields instead of tapping keypads (Calculator act 5.5-6 s -> 1.4 s on the phone); round-3 model/plugins A/B + network findings in RESULTS.md
34cb7d3 integration deploy #1: smoke results, open issues (pitch/rehearsal/DEPLOY-NIGHT.md)
be8761a night plan: bug hunt 2 started
d356423 speed r3: answer from the screen already shown (Pigeon 3 calls -> 2 in every run)
4814e76 launcher: a second ask during a running turn waits its turn instead of showing "a turn is already running; send stop first" and being dropped
1ad2ca5 speed r3: one-shot action tools (phone_settings, media, play_music, navigate, open_url, add_event, schedule_task) finish in the same call (flashlight 2 -> 1 model call)
e6ce489 RESULTS r3: real run times from the rows
b83ea89 speed r3 fix: a multi-part request never ends early on a finishing tool's done (cross-app task 2/3 -> 3/3); bench cross task
3633457 bench: snap.sh REV, before/after variants; RESULTS r3 timing notes
757636a router: reachability probe waits 2.5 s, not 1 s (back online, 2 of 7 asks went to the local model)
48c8367 bug hunt round 2: reliability of deploy #1 (pitch/rehearsal/BUGHUNT-NIGHT-2.md)
2af4d18 night plan: bug hunt 2 done, keyboard batching + review 2 started
8ad4c5d night plan: Wi-Fi drop #2 and keep-alive loop
```
