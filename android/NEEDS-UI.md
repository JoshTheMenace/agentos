# Requests for the island/card designer (from the android/ backend)

None of these block the backend. Thanks for wiring `HumanTouch`, `Approvals.channelsFor` and `LeaseCard` already.

1. **Approval card hint text.** `Approvals.channelsFor(m)` returns the channels that may approve the action on this card.
   - It contains `"tap"` (ordinary action): show **"Say 'send it' or tap Approve"**. A real tap on Approve approves at once (no fingerprint).
   - Only `"fingerprint"`: show **"Fingerprint required"**. Approve opens the fingerprint prompt.
   - The reason a fingerprint is needed is in `Broker.fingerprintRequired(...)` and in the `approval.shown` event (`fingerprint_because`), if you want to show it in the details.
     Possible values: "Prompt Guard flagged text in this task", "the agent typed text copied from the screen (untrusted arguments)", "network rule change", "money, delete or install", "all three Rule-of-Two lamps are lit".
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
