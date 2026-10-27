# Requests for the island/card designer (from the android/ backend)

None of these block the backend. Thanks for wiring `HumanTouch`, `Approvals.channelsFor` and `LeaseCard` already.

1. **Approval card hint text.** `Approvals.channelsFor(m)` returns the channels that may approve the action on this card.
   - It contains `"tap"` (ordinary action): show **"Say 'send it' or tap Approve"**. A real tap on Approve approves at once (no fingerprint).
   - Only `"fingerprint"`: show **"Fingerprint required"**. Approve opens the fingerprint prompt.
   - The reason a fingerprint is needed is in `Broker.fingerprintRequired(...)` and in the `approval.shown` event (`fingerprint_because`), if you want to show it in the details.
     Possible values: "Prompt Guard flagged text in this task", "the agent typed text copied from the screen (untrusted arguments)", "network rule change", "money, delete or install", "all three Rule-of-Two lamps are lit".
2. **Back-to-back cards get lost (bug).** When `hide()` and then `show()`/`lease()` run within ~2 s, the new card vanishes:
   - `Island.collapse()`'s end callback removes `face` (by then the *new* card) and shrinks the window to the pill;
   - and when no task is active it posts `autoHide` 1.5 s later, which then hides the whole island, new card included.
   - Repro: `tools-client.py call task.start '{"goal":"a","apps":["local.agentos.pigeon"],"approval_timeout_s":10}'`, then a second `task.start` right after it times out. The second card never appears.
   - This happens for real when agentd asks again right after a denial. `tools-client.py approvals` waits 2.5 s between cards to work around it.
   - Suggested fix: in `expand()`, cancel any running collapse morph and `removeCallbacks(autoHide)` after it, or have the collapse end action check that `face` is still the face it closed.
3. **Card positions for the adb test.** `tools-client.py approvals` taps Allow/Approve at `580 190` (Allow) and `575 248` (Approve under the "Untrusted content" chip; override with `AGENTOS_ALLOW_XY` / `AGENTOS_APPROVE_XY`), and waits 4 s for the card to finish opening.
   If the layout moves, tell me the new spot, or keep Approve at the same place for cards without a text bubble.
4. **Island state from the broker (optional).** If you want the pill to follow real tasks, the broker can call `island.set(...)` on `task.state` changes. Tell me which states and labels you want and I'll add the calls in `Broker` (or read the same events off `GET /events`).
