# Requests for the island/card designer (from the android/ backend)

None of these block the backend. Thanks for wiring `HumanTouch`, `Approvals.channelsFor` and `LeaseCard` already.

1. **Approval card hint text.** `Approvals.channelsFor(m)` returns the channels that may approve the action on this card.
   - It contains `"tap"` (ordinary action): show **"Say 'send it' or tap Approve"**. A real tap on Approve approves at once (no fingerprint).
   - Only `"fingerprint"`: show **"Fingerprint required"**. Approve opens the fingerprint prompt.
   - Policy changed 10-27 (founder): a fingerprint only for money/installs/saved passwords (`fingerprint_because`: "money or an install", "uses a saved password"). A tainted task now needs **a person** (tap or voice, not the reviewer): channels `tap, voice, fingerprint`, reason in `held_because` ("Prompt Guard flagged text in this task", "the text was copied from untrusted content", "all three Rule-of-Two lamps are lit", "Prompt Guard flagged this message", "a standing grant needs a person"). Both are in `Broker.channels(...)`.
2. **Back-to-back cards: fixed, please keep.** Your `cancelled` flag in `morph()` plus `removeCallbacks(autoHide)` in `expand()` fixed the lost card (verified on the phone: a second lease card right after a timeout opens and takes the tap; `tools-client.py approvals` no longer waits between cards).
   - The backend found one more case and fixed it in `Island` without changing the look: a pill re-fit (`set()` with a new label or ring) that started during a collapse cancelled the collapse's clean-up, so the closed card stayed attached and the window kept the card's size (it blocked touches under it). Now `collapse()` sets `closing`, and `closed()` does the clean-up at the end of either morph. After a closed card the "done" pill keeps its 3 s fade.
3. **Card positions for the adb test.** `tools-client.py` taps Allow at `580 190` and Approve at `575 325` (`AGENTOS_ALLOW_XY` / `AGENTOS_APPROVE_XY`). Every "Send" card now has the message bubble (and usually the "Untrusted content" chip), which put Approve at y≈325. Cards without a bubble have it at y≈248–270. If the layout moves, tell me the new spot.
4. **Island state from the broker: done.** `Broker` drives your existing hooks (labels are short so the 150 dp pill fits):
   - `task.start` → `set("working", "Starting", 0)` and `island.goal`; each action → `set("working", "Tapping “Send”" | "Typing in Pigeon" | "Opening Pigeon" | "Pressing back" | "Scrolling", pct)`, where pct = steps used of `max_steps`;
   - pause → `set("idle", "Paused", -1)`, resume → back to the step; stop / kill switch / lease end by `task.stop` → `hide()`;
   - the reviewer approves → `card.autoApproved(title, rationale)` right after the card's `hide()`;
   - `guard.flag` during a task → `card.alert(app, sender, snippet, score, …)` once per task and sender (sender = the short label before the flagged text in its row, e.g. "Unknown number"; else the snippet). Never over an open approval card. **OK** closes it and restores the step pill; **Block** stops the task.
   - Escalation (`approval.escalate`) redraws the open card with `show(m, l)` again, so it switches to "Fingerprint required".
   - Wish (optional): the tap hint "Say 'send it' or tap Approve" from item 1 isn't drawn yet.

5. **Red guard alert removed (founder, 10-27).** The broker no longer calls `ApprovalCard.alert(...)`, and the adb demo hook's `card alert` case is gone. The method, `AlertListener` and the island's red `alert` state are now unused: delete them when convenient. Guard hits are audited (`tool: "guard.flag"`) and sent as `guard.flag {task_id, pkg, sender, snippet, score}` for the launcher's log.
6. **New cards from notifications** (no layout change needed): `notifications.reply` shows a normal approval card with title "Reply to <conversation>", the app's icon and the reply as the bubble; `notifications.autoreply` shows "Auto-reply for N min" with the exact text, app AgentOS. Launcher: `notification.posted` and agentd's `rule` events are forwarded to every client, and `context.suggestions()` (agentd) gives "Reply to Sam" home suggestions in the `home-suggest.js` shape.

## From the designer (10-28, 1 AM)
- **Island moved below the camera cutout** (founder). `Island` now places the window from the window insets (status bar ∪ display cutout) + 8 dp, no fixed offset; on the demo phone that is 77 px lower than before. **Item 3 coordinates move by +77 px:** Allow `580 267`, Approve `575 402` (`AGENTOS_ALLOW_XY` / `AGENTOS_APPROVE_XY`); the defaults in `tools-client.py` are yours to update. (Done 10-28 in the bug hunt: the defaults are now `580 267` / `575 402`.)

## From the designer (10-28, 2:45 AM)
- **Stopwatch on every finish (DEMO-WOW #4).** agentd's `endTurn(turn, doneMs)` sends `task.stop {task_id, done_ms}` when the turn completed (0 when it failed or was stopped). `Broker.stop(taskId, why, doneMs)` then shows the green pill "Done · 6.8 s" for 3 s instead of hiding (plain "Done" past 30 s). The same number reaches the launcher as `turn.done.ms` / `task.timing.total_ms`.
- **Island placement fix.** `Island.place()` now always reads the display's insets (`getCurrentWindowMetrics().getWindowInsets()`): the insets dispatched to the window are relative to its own frame, so once the pill sat below the status bar they read 0 and the pill sometimes jumped up into the status bar (seen 10-28 2:21 AM).
- **Firewall card title**: `Broker.approve` titles `firewall.set` as "<mode> <domains|all traffic> for <App>" → "Block httpbin.org for Beacon?" (ISSUES #8).
