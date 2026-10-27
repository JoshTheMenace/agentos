# Rehearsal issues (2026-10-27, on the phone)

Fixed today are at the bottom. Open ones first, most important first.

## Open

1. (fixed, see below)
2. **One flag taints the next 10 minutes (by design, but it shapes the run order).** Like a club hand stamp, the broker keeps a task's history (taint, guard flag) for the same caller within the old lease's TTL, and every new agentd task renews it. So after the trap beat, every send needs a fingerprint, and the reviewer can't auto-approve. Run the auto-approve beat **before** the canary is staged. Recovery: one operator task from the laptop resets the stamp (RUN.md, "reset the stamp").
3. **Pigeon's chat list shows the canary preview**, so any task that passes the list gets flagged (Prompt Guard 0.95). That is correct behavior; it is why the run uses `scenario clean` until the trap beat.
4. **The fingerprint prompt is black on scrcpy.** BiometricPrompt is a secure window: screencap and mirroring show nothing. Point a document camera at the phone for this beat, or say "the system fingerprint sheet is hidden from screen capture on purpose".
5. **The Motorola "Android 14 is here!" heads-up pops through Do Not Disturb** after network changes (seen after airplane mode). Shell can't revoke its notification permission (system-fixed). Optional pre-show: `adb shell pm disable-user --user 0 com.motorola.ccc.ota` (undo: `adb shell pm enable com.motorola.ccc.ota`). Not applied; the owner should decide.
6. **Reviewer latency 1.1–3.9 s against the 4 s budget.** Before the input trim one review took 3919 ms. After the trim, 2.0 s. On a slow hotspot it can fall through to "human" (the card stays up and the presenter taps Approve, which is fine but not the ✓ moment).
7. **Say the app name.** "Reply to Mom: …" without "in Pigeon" made the agent open Google Messages (the real app) first, then widen the lease to Pigeon (two lease cards, about 15 s lost).
8. **Firewall card title shows the raw method:** "Firewall.set block httpbin.org?" (Fable: friendlier title, e.g. "Block httpbin.org for Beacon?").
9. **Island keeps "Tapping "Send"" with a spinner for about 3 s after an approval times out**, until the task stops (Fable/android, cosmetic).
10. **Keyboard:** long suggestions are cut with "…" (known). After inserting one, Pigeon's single-line field shows only the end of the text, with stray glyph tops above it (demo-apps cosmetic).
11. **demo-script.md is behind the build.** Voice, keyboard and local are marked PENDING but work. Step 3 says Send always needs a fingerprint; today a clean send is auto-approved, and a fingerprint is needed only after a flag (or for the firewall). Step 6 says a human tap on Pigeon's Send goes to the card; it doesn't (the user's own tap just sends). RUN.md has the current order.
12. **Domain firewall rules resolve IPs once.** httpbin.org rotates AWS addresses, so a domain rule can miss. The app-wide fallback works (rows go REFUSED within one 3 s interval).

## Fixed today
- **Reviewer too strict** (agentd/reviewer.js): a dictated send to the named contact was scored "high" and escalated to fingerprint. The prompt now scores it low when the chat matches the person the user named and the text is what the user asked to say. The code ladder also requires user authorization ≥ medium for "auto". Fingerprint-only approvals and guard-flagged tasks are still never approved, because that floor is in code. Tests: `node agentd/reviewer-test.js`, 14/14 on the Mac twice, 13/14 on the phone at 4 s (the miss was "tap" instead of "block" for a 2FA draft that the broker blocks first anyway).
- **Reviewer cold start:** agentd warms the reviewer thread at boot (server.js). The reviewer no longer sees status-bar nodes (tools.js `brief`), so prompts are smaller and reviews faster (3.9 s → 2.0 s on the same flow).
- **Keyboard crash** "AgentOS Keyboard keeps stopping" (NetworkOnMainThreadException): it sent the WebSocket close frame on the UI thread when the keyboard hid mid-request. It now closes on a background thread (keyboard Agent.java).
- **Prompt Guard flagged our own launcher:** the ask sheet's alert row "Ignored instructions in Pigeon" scored 0.999, so every later ask from home flagged itself. The copy is now "Suspicious message in Pigeon" (0.002; web/app.js).
- **Pigeon `scenario clean`:** the seed without the "Unknown number" chat, so the auto-approve beat can run before the trap.
- STATUS fix-ups already done: tools.js approval wait is 60 s card / 90 s RPC; config.js `localUrl` is 8081.
- **Send card showed an empty message** for a draft typed by an earlier task (fingerprint signed `""`). `Broker.draft()` now falls back to the screen's only text field (none or several → still `""`). Verified on the phone: the card shows "Draft left by task one".
