# AgentOS status / handoff (2026-10-27)

Judging: Wed 2026-10-28, 5 PM. Read ARCHITECTURE.md first. Research is in research/. Pitch drafts are in pitch/.

## Decisions (rewritten 10-27 9:30 AM from founder direction; supersedes the older ones)
- **Product: an AI-native phone OS ("the Siri we should have"), built security-first.** The demo should be mostly "whoa" capability; security is a supporting thread, not the whole show.
- **Live demo uses real apps only** (no Pigeon/Beacon on stage; they stay as test fixtures). Real apps may be operated and, for the app-modification demo, modified on our own phone.
- **Demo order and content: the founder is writing it.** Old pitch/demo-script.md and pitch/rehearsal/RUN.md are out of date; don't polish them.
- **Approvals:** fingerprint ONLY for the most sensitive things (payments, using a vault password, and similar). Everything else is reviewer auto-approve or a tap. No red alert pop-ups: blocked/flagged actions go to a log (command center / settings). Island still shows what the agent is doing.
- **Voice is the main way to start tasks.** GPT Live doesn't do the task; it starts a background Codex thread (with a task system prompt), gets its status ("how's it going?"), and says when it's done.
- **Memory + notifications:** agents know recent notifications and cross-app context, so "reply back that I'm 10 min late" works without naming the person or app. Users can tell agents to handle notifications/replies for them.
- **Home screen:** no dummy data (no lamp, no heart rate, no "no active grants"). Show what agents are doing now and proactive suggestions.
- **Firewall demo:** in a real app, ask by voice "any trackers?", it finds them, "block them", it does; a blockers view lists what's blocked per app.
- **Speed is a top priority:** hill-climb task latency (screen format, fewer round trips, Codex computer-use ideas, faster models e.g. GPT-6.1 Terra if available).
- **Explore:** Claude Code on the phone as a provider (like Codex app-server), the Galaxy Watch, live-modifying a real app (e.g. calculator/camera: add a button), password vault + fingerprint fill (stretch).
- UI: **A2 · Calm Glass iOS Dark** (design/mockups/A2-calm-glass-ios-dark.html, tokens in design/A2-tokens.md). Minimal text, progressive disclosure. Say "Fingerprint", never "Touch ID". **All UI work goes to Fable agents** (model: fable).
- Founder is Utah-based with an LLC. No Duolingo. Code freeze Wed noon; judging Wed 5 PM.

## Done (on the phone, committed)
- android/ `local.agentos`: tools, broker, leases, Rule of Two, fingerprint/tap/voice/reviewer approvals, audit chain, firewall, on-device Prompt Guard 2, /events SSE.
- agentd/: Codex app-server on the phone (root wrapper, 0.163), dynamicTools, guard, memory, voice relay (GPT live via subscription works), suggest, events, voice approvals, reviewer hook.
- keyboard/ `local.agentos.keys`: A2 AI keyboard, set as the default IME (crash on hide fixed 10-27).
- demo-apps/: Pigeon (canary chat) and Beacon (tracker demo).
- agentd/memory: consent ledger, ingestion, graph.json.
- Local model: Qwen3.5-0.8B on the phone via llama-server :8081 (off by default; `agentd/deploy/local-model/llama.sh start`). Use it for the airplane-mode demo "open Pigeon and read me the last message from Mom" (12 s from the launcher in airplane mode). The old fix-ups are done (approval wait 60 s card / 90 s RPC; localUrl 8081).

- Scheduled tasks + unlock (10-27): `schedule_task`/`list_scheduled`/`cancel_scheduled` (agentd/schedule.js, `~/.agentos/schedule.json`, exact AlarmManager alarm), and the broker opens a locked phone for the founder's own clean tasks (android `Wake`, PIN in root-only `/data/adb/agentos/unlock.pin`, audited `phone.unlock`, re-locks after). Tested on the phone: locked → alarm set/deleted at the due time → re-locked; Prompt-Guard/regex-tainted tasks stay locked. Demo: "in 2 minutes, set an alarm for 6:15", lock the phone, watch. Seen: the Termux app (and so the agentd boot loop) died twice today with nothing in the logs; if agentd is down at fire time the task runs late (on restart, up to 30 min late) or is skipped. Check `pgrep -af "^node server.js$"` before demoing.

## In progress when this was written (check each folder's README/NEEDS for state)
- launcher DONE and set as default home (launcher/README.md). Follow-ups done: home is in AGENTD_CLIENT_PKGS, agentd `rpc` passthrough (no :7777 token in the launcher), live state from agentd-forwarded broker events (no polling), inline typed approve buttons removed (the native card is the only gate). Left: memory consent toggle (launcher/NEEDS.md).
- android/ island + approval card restyle (Fable). The island now follows task state (working/step/pause/hide, auto-approved moment, guard alert), and Send cards show the drafted message. The back-to-back card bug is fixed (android/NEEDS-UI.md).
- Reviewer is DONE (agentd/reviewer.js, deployed), including `approval.escalate` to fingerprint. Retuned 10-27: a dictated send to the named contact is low risk → auto-approved ("Auto-approved ✓" seen on the phone, 2.0 s). Fingerprint-floor and flagged tasks are never approved. Tests: `node agentd/reviewer-test.js` (14/14).

## Stage hygiene (done 10-27)
- Moto system-update screen (10-27 PM; the notification fix didn't stop the full-screen `com.motorola.ccc.ota/.ui.BaseActivity`): the OTA apps are hidden systemlessly by the Magisk module `/data/adb/modules/agentos-no-ota` (`.replace` on `/system_ext/priv-app/3c_ota` and `Fota`). Verified after reboot: neither package exists. Undo: delete that folder and reboot.
- agentd at boot: Termux:Boot missed BOOT_COMPLETED after that reboot, so `/data/adb/service.d/agentos-agentd.sh` (Magisk, root) re-sends it to `com.termux.boot` 30 s after boot. The boot script's lock stops a second loop.
- Motorola nag notifications (10-27, verified: 0 records after airplane on/off + 75 s). The Settings toggle was a no-op: the OTA app's POST_NOTIFICATIONS is SYSTEM_FIXED and its channels are importance-locked. What holds:
  - `com.motorola.ccc.ota`: `su 1000 -c "pm revoke com.motorola.ccc.ota android.permission.POST_NOTIFICATIONS"` (needs uid 1000, plain root is refused). Undo: `su 1000 -c "pm grant com.motorola.ccc.ota android.permission.POST_NOTIFICATIONS"`.
  - `com.motorola.ccc.notification` (ch_hello_moto_content), `com.motorola.gamemode` (play_game, ch_games), `com.motorola.setup` (SetupWizardService): every channel set to importance 0 through INotificationManager.updateNotificationChannelForPackage, helper dex `/data/local/tmp/nh.dex` (source `/tmp/stagehygiene/NH.java`; run `su -c "CLASSPATH=/data/local/tmp/nh.dex app_process / NH list|block|cancelall|enabled <pkg> <uid>"`). Undo: Settings → Apps → app → Notifications → turn the channel back on (or `pm clear <pkg>` for the Moto ones). Uids: ota 10308, ccc.notification 10307, gamemode 10197, setup 10348.
  - Cancelled once (not blocked): all Calendar, Google app (stories) and systemui "fully charged" notifications; they can come back, and Calendar reminders can pop up before an event. Left: Termux, plus the system "Developer options"/USB-debugging ongoing items (they stay while adb is on).
  - No iptables, no firewall, no Magisk policy or reboot involved.
- Magisk "granted Superuser" toasts: off for all granted uids (`magisk --sqlite "UPDATE policies SET notification=0"`; re-run if a new uid is granted).

## Waiting on the user
- Claude on the phone: token installed 10-27 (`~/.agentos/claude.token`, works: `claude -p` ok in 10 s). Copied the new claude-cli.js; needs one agentd restart, then benchmark `node cli.js --provider claude`. Role: Codex is default; Claude is shown only as "you can switch brains".
- Watch: founder to enable Wi-Fi debugging on the Watch4 and send IP:port + pairing code (watch/README.md).
- Fingerprint e2e test at the phone: `python3 android/tools-client.py e2e --press-approve`.
- Smart plug brand.

## Rehearsal (10-27, pitch/rehearsal/)
All beats ran on the phone; shots, timings and the presenter checklist are in `pitch/rehearsal/RUN.md`, open issues in `ISSUES.md`. Pass: home, ask sheet, keyboard, Pigeon task + lease tap, auto-approve ✓, trap → alert, P3 block, fingerprint card + system prompt, firewall card + root fallback, local model in airplane mode, audit chain. Needs the presenter: speaking (voice), the finger (fingerprint, firewall). Order matters: a guard flag makes every send need a fingerprint for 10 min, so auto-approve runs before the canary is staged (`--es scenario clean`).

## Vault + one-tap blocks (10-27 evening, Fable; android/README.md "Password vault", launcher/README.md)
- Fresh vault (old `test` entry wiped). Passwords view in the launcher (command center › Passwords): list, Delete, "Add password" → native A2 screen in local.agentos (password never touches the WebView/agentd/model), Save with fingerprint.
- Sign-in offer: open an app with a saved login → card "Sign in as <user>?" → fingerprint → filled, Continue tapped. Agent "log me into Slack" → `vault_fill` → the same prompt. Accessibility path (not Autofill Framework), reasons in android/README.md.
- Blockers: Block / Block all / Unblock are one tap (owner line: launcher uid → :7777, no card). Agent trackers.block: no card (protective). Agent trackers.unblock: human card.
- agentd edits (events.js FORWARD `tracker.blocked`, `vault.changed`; tools.js descriptions) need an agentd restart to take effect; the launcher works without it.

## Next up
1. Fingerprint press at the phone (beats 7, 8), and the open items in pitch/rehearsal/ISSUES.md. Update demo-script.md to the RUN.md order.
2. Watch app / plug (optional), the deck visuals (Fable) and a 2-minute backup video.

Device rule: `mkdir /tmp/agentos-device.lock` before state-changing adb, and `rmdir` only if your mkdir succeeded.
