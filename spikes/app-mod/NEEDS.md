# NEEDS: wiring app mods into the broker

Goal: the user says "add a tip button to my calculator", the agent writes a mod spec, one tap approves it, and the mod is on screen. Mods are audited, listed in Settings, and removable.

## 1. Where the code goes (recommended: fold into AgentA11y)

Move `ModService.java` (~500 lines) into `android/src/local/agentos/Mods.java` as a helper owned by `AgentA11y`. Don't ship a second accessibility service:
- One service means no extra `enabled_accessibility_services` entry and no second restricted-settings grant.
- `AgentA11y.onAccessibilityEvent` → `Mods.onEvent()` (same 16 ms debounced `refresh`). Add `typeViewScrolled` to `res/xml/a11y.xml` event types.
- Add `android:canTakeScreenshot="true"` to `res/xml/a11y.xml` (used once per app/orientation/theme to copy the app's button colors). Without it, mods fall back to the explicit `bg/fg/round` in the spec.
- Delete `ModCmd` (the DUMP-guarded broadcast receiver). The broker calls `Mods.apply/remove/list` directly.
- Store specs in the broker's own prefs. They survive reboots, like now.

## 2. Broker tools

| tool | tier / approval | what the broker checks |
|---|---|---|
| `app.mod.apply {spec}` | new **T2 "UI change"** → **tap** approval (never fingerprint, never reviewer-auto: a person should see what's being added to their app) | schema (below); `package` is installed, not `Guard.forbidden` (system/AgentOS surfaces) and not `Guard.privateApp` (banking, password manager); `id` ≤ 32 chars `[a-z0-9_-]`; label ≤ 24 chars; ≤ 3 chips; ≤ 20 press steps; ≤ 10 mods total |
| `app.mod.remove {id}` | free, no approval (removing is always safe) | none, just audited |
| `app.mod.list` | T0 observe | returns `[{id, package, label, shown, created, by_task}]` |

The approval card (existing tap card) should say it plainly: **"Add 'Tip' to Calculator? Reads the display · presses + digits ="**. That line comes from the spec: `read` ids, and the `press`/`onChip` ids joined by labels from the current `screen.read`. Show a preview chip in the A2 style if cheap.

**When the user taps a mod**, it presses the app's buttons. That's the user acting through a button they approved, so no new approval. Two guards still run at press time:
- Skip and log if any target node is `Guard.riskyLabel` (Send, Pay, Delete, Buy...) or the screen is FLAG_SECURE. A model-written mod must not become a one-tap "Pay" button.
- Only ids inside the target package resolve (the spike's `find()` already prefixes `pkg:id/`).

`compute` is multiplication by a constant (`factor`), not an expression evaluator. Keep it that way. If we need more (currency, unit conversion), add named ops, not `eval`.

## 3. Audit

- `app.mod.apply`: `{tool, task, pkg, id, label, spec_sha256, decision: "tap", approver}`. Keep the full spec in the audit body so it can be replayed.
- `app.mod.remove`: `{tool, id, by: "long-press" | "settings" | task id}`.
- Each mod press: `{tool: "app.mod.press", id, pkg, steps: n, ms}`, decision `human` (the user tapped it).
- Emit `mod.changed` on `/events` so the launcher refreshes.

## 4. Settings / command center

Add a **Mods** row under Blockers / Permissions in the launcher command center. It shows the `app.mod.list` result: app icon, label, "Remove" (calls `app.mod.remove` via the agentd `rpc` passthrough). Empty state: hide the row.

## 5. agentd / model side

- New dynamic tool `app_mod_apply(spec)` → broker `app.mod.apply`, plus `app_mod_remove(id)`, `app_mod_list()`.
- Prompt snippet: "To add a button to an app: open the app, `screen.read` to get button ids and bounds, then write a spec. Anchor next to an existing button in empty space. Give an `alt` spot for landscape. Copy the look with `style.sample` = the neighbour's id. Prefer `press` sequences of the app's own buttons." Include `spikes/app-mod/specs/tip.json` and `pomodoro.json` as few-shot examples (~600 tokens).
- `screen.read` skips accessibility overlays (`Screen.java:49`), so the agent and Prompt Guard never see mods. Keep that. Optionally list active mods in `screen.read` as `{"src": "MOD:tip", "label": "Tip"}` so the agent knows they're there; it must not press them.

## 6. Effort

About half a day: port (~500 lines, mostly copy), 3 broker cases + validation (~80 lines), launcher list (~40 lines), agentd tool + prompt (~30 lines). Test with the two specs on the phone. Warm the color cache before the demo (open Calculator once after applying).
