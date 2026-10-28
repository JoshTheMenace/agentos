# AgentOS status / handoff (2026-10-27)

## What's new overnight + how to demo it (all live, smoke-tested 10-28 07:00–08:35; details in pitch/rehearsal/DEPLOY-FINAL.md)
| Feature | Say / tap | Expect |
|---|---|---|
| Faster tasks + stopwatch | Ask bar: "Turn on the flashlight" | One step, "Done · 2–5 s" in the sheet and island |
| Cross-app task | "Turn on the flashlight, then open <app> and read me <name>'s last message" (say **open**) | Done in ~10 s with the right message |
| What did I miss? | Ask bar or voice: "What did I miss?" | Cards (unanswered messages, next events) + one-line answer in ~2 s |
| Voice | Tap the mic, **wait for "Listening"**, then "What's my battery?" | "On it", then "Done, in N seconds…" (~6 s task; first start can take ~7 s) |
| Calendar-aware keyboard | In a chat that asks "Are you free Thursday at 3?", tap the text field | Chips in ~1 s, e.g. "Thursday 3 PM to 5 PM looks free." Typing a word: new chips in ~2–3 s |
| Tracker counter (owner setting, not an ask) | Open a real app with trackers (Weather), then Command center › Protected today › "N apps sent data to trackers"; tap Block | Blockers lists each domain with its company; counts tick live. Asking the agent about trackers just points you to Blockers |
| Offline fallback | Airplane mode (keep the local model warm), ask something simple | Answers with "On device" in ~2–5 s |
| Queued asks | Send a second ask while one runs | It waits, then runs (no error) |
| Scheduled task | "In 2 minutes, set an alarm for 6:15", lock the phone | Fires on time, unlocks, sets it, re-locks |
| Notifications + replies | "Reply back that I'm 10 min late" | Reply goes through the app's own notification Reply (not re-tested today) |
| Deep mods (Pigeon only, not on stage) | "Open Pigeon and preview a navy and gold theme" | Native preview, auto-restored (~9 s) |

## Before stage
1. Join the venue Wi-Fi, then `scripts/preflight.sh --fix wifi,warm,ime,home` (low-latency Wi-Fi, warm local model, AgentOS keyboard + home). **Never run `cmd wifi set-connected-score`**: it crashed Android at 07:03 (DEPLOY-FINAL issue 1).
2. Just before going on: `scripts/preflight.sh --fix dnd,reset` (Do Not Disturb + demo reset; Command center › Protected today says "12 actions stopped" until then).
3. Close apps the bench opened (YouTube, Facebook, Instagram) from Recents.
4. Open voice once and wait for "Listening" right before the voice beat.
5. Re-run `scripts/preflight.sh`. Expected reds only: Wake word (off), Test alarms (needs a lease; Clock is clean), Chrome tabs (check by hand).

## Remaining reds
- Wi-Fi is the #1 risk (dropped 3× overnight; one reboot-level crash from the score pin). Workaround: toggle Wi-Fi once, then `--fix wifi`.
- "Last message" questions can come from stale memory if you don't say "open <app>".
- The reviewer may leave a delete to you (a tap card). Keep deletes out of the demo.
- Voice: cold start ~7 s; the spoken "Done" can lag the task by ~6 s. The Mac WAV test rig is broken (test-only).
- Fingerprint e2e still not pressed at the phone; Picovoice key undecided (offline "Hey Agent" works, off by default).

Judging: Wed 2026-10-28, 5 PM. Read ARCHITECTURE.md first. Research is in research/. Pitch drafts are in pitch/.

## Decisions (rewritten 10-27 9:30 AM from founder direction; supersedes the older ones)
- **Product: an AI-native phone OS ("the Siri we should have"), built security-first.** The demo should be mostly "whoa" capability; security is a supporting thread, not the whole show.
- **Live demo uses real apps only** (no Pigeon/Beacon on stage; they stay as test fixtures). Real apps may be operated and, for the app-modification demo, modified on our own phone.
- **Demo order and content: the founder is writing it.** Old pitch/demo-script.md and pitch/rehearsal/RUN.md are out of date; don't polish them.
- **Approvals:** fingerprint ONLY for the most sensitive things (payments, using a vault password, and similar). Everything else is reviewer auto-approve or a tap. No red alert pop-ups: blocked/flagged actions go to a log (command center / settings). Island still shows what the agent is doing.
- **Voice is the main way to start tasks.** GPT Live doesn't do the task; it starts a background Codex thread (with a task system prompt), gets its status ("how's it going?"), and says when it's done.
- **Memory + notifications:** agents know recent notifications and cross-app context, so "reply back that I'm 10 min late" works without naming the person or app. Users can tell agents to handle notifications/replies for them.
- **Home screen:** no dummy data (no lamp, no heart rate, no "no active grants"). Show what agents are doing now and proactive suggestions.
- **Trackers/firewall are owner settings, not agent tools (10-28, supersedes the voice "any trackers?" demo):** the 7 tracker/firewall tools are gone from every model (Codex, Claude, local, reviewer, remote MCP). The owner scans, blocks and unblocks with one tap in Command center › Blockers (launcher uid → broker); the broker refuses `firewall.set/clear` and `trackers.block/unblock` from agentd. Reads (`trackers.list`, `firewall.list`) stay for the Command center.
- **Leaner tool list (10-28):** per-app reader tools removed (LinkedIn, X, Slack, Instagram, Discord, WhatsApp, Gmail incl. `email_draft`, Spotify, YouTube); live notification tools + "What did I miss?" cover messages, `media` covers "what's playing". Mods use one name per action (`mod_*`; the `app_mod_*` aliases are gone). 74 tools now (pitch/TOOLS.md).
- **Speed is a top priority:** hill-climb task latency (screen format, fewer round trips, Codex computer-use ideas, faster models e.g. GPT-6.1 Terra if available).
- **Explore:** Claude Code on the phone as a provider (like Codex app-server), the Galaxy Watch, live-modifying a real app (e.g. calculator/camera: add a button), password vault + fingerprint fill (stretch).
- UI: **A2 · Calm Glass iOS Dark** (design/mockups/A2-calm-glass-ios-dark.html, tokens in design/A2-tokens.md). Minimal text, progressive disclosure. Say "Fingerprint", never "Touch ID". **All UI work goes to Fable agents** (model: fable).
- Founder is Utah-based with an LLC. No Duolingo. Code freeze Wed noon; judging Wed 5 PM.
- **Tool plugins (post-hackathon, agentd/research/TOOLS-AT-SCALE.md):** a plugin toggle turns off its direct-API tools only; it never restricts the app itself (UI driving still allowed). Built 10-27 night: switches live on the phone (Command center › Plugins, owner line → broker `plugins.set`; agentd and the broker both refuse an off plugin's tools). The pre-selector/deferral is behind `AGENTD_TOOLS=plugins`; the default stays `all` (agentd/research/TOOLS-AT-SCALE-RESULTS.md: ~30 % fewer tokens, same speed and success, more cache misses).
- **App mods (10-28):** a hide/restyle mod the owner asked for in their own words applies without an approval card (the owner can remove it anytime). The card stays for remote (Grok/Dots) or tainted requests and for mods that press buttons. Agents never ask the founder to tap Approve for mod tests.
- **Paired remote assistants (10-28):** Grok/Dots low-risk steps (open/switch apps, home/back, scroll, flashlight/volume, alarms/timers, screen reads) run with no card; outward, delete, pay, install, mods, vault, radios and flagged tasks keep the card; task answers, `phone_brief` and `memory_lookup` follow the owner's remote reads switch.
- **Phone access (agents):** wrap every state-changing adb step in `scripts/withphone <cmd>` (takes the shared lock, stores its pid, releases on exit; a stray `rmdir` can't remove it). Never tap approval cards, never text/call real contacts, never touch the founder's real data except clearly-named test items deleted by exact id.

## Done (on the phone, committed)
- android/ `local.agentos`: tools, broker, leases, Rule of Two, fingerprint/tap/voice/reviewer approvals, audit chain, firewall, on-device Prompt Guard 2, /events SSE.
- agentd/: Codex app-server on the phone (root wrapper, 0.163), dynamicTools, guard, memory, voice relay (GPT live via subscription works), suggest, events, voice approvals, reviewer hook.
- keyboard/ `local.agentos.keys`: A2 AI keyboard, set as the default IME (crash on hide fixed 10-27).
- demo-apps/: Pigeon (canary chat) and Beacon (tracker demo).
- agentd/memory: consent ledger, ingestion, graph.json.
- Local model: Qwen3.5-0.8B on the phone via llama-server :8081 (off by default; `agentd/deploy/local-model/llama.sh start`). Use it for the airplane-mode demo "open Pigeon and read me the last message from Mom" (12 s from the launcher in airplane mode). The old fix-ups are done (approval wait 60 s card / 90 s RPC; localUrl 8081).

- Scheduled tasks + unlock (10-27): `schedule_task`/`list_scheduled`/`cancel_scheduled` (agentd/schedule.js, `~/.agentos/schedule.json`, exact AlarmManager alarm), and the broker opens a locked phone for the founder's own clean tasks (android `Wake`, PIN in root-only `/data/adb/agentos/unlock.pin`, audited `phone.unlock`, re-locks after). Tested on the phone: locked → alarm set/deleted at the due time → re-locked; Prompt-Guard/regex-tainted tasks stay locked. Demo: "in 2 minutes, set an alarm for 6:15", lock the phone, watch. Termux deaths: fixed, see Stage hygiene (agentd now runs outside the Termux app). Still check `pgrep -f "^node server.js$"` before demoing.

## In progress when this was written (check each folder's README/NEEDS for state)
- launcher DONE and set as default home (launcher/README.md). Follow-ups done: home is in AGENTD_CLIENT_PKGS, agentd `rpc` passthrough (no :7777 token in the launcher), live state from agentd-forwarded broker events (no polling), inline typed approve buttons removed (the native card is the only gate). Memory consent toggles done (owner-only `memory.consent`, 10-27 night).
- android/ island + approval card restyle (Fable). The island now follows task state (working/step/pause/hide, auto-approved moment, guard alert), and Send cards show the drafted message. The back-to-back card bug is fixed (android/NEEDS-UI.md).
- Reviewer is DONE (agentd/reviewer.js, deployed), including `approval.escalate` to fingerprint. Retuned 10-27: a dictated send to the named contact is low risk → auto-approved ("Auto-approved ✓" seen on the phone, 2.0 s). Fingerprint-floor and flagged tasks are never approved. Tests: `node agentd/reviewer-test.js` (14/14).

## Stage hygiene (done 10-27)
- **Preflight:** `scripts/preflight.sh` checks the connected phone read-only; GREEN = verified, RED = needs attention or could not be checked (exit 1). Code is compared with committed HEAD.
- `scripts/preflight.sh --fix` holds the phone lock and sets IME/home, warms the router, enables DND, resets demo counters and closes only Chrome targets recorded in `--tabs FILE` (`{serial,tabs:[{id,url}]}`). No alarms/calendar data or unrecorded tabs are deleted.
- Tonight use only `scripts/preflight.sh --fix warm,ime,home`; save DND/reset for stage. Review red alarm/calendar/tab checks manually; wake word is never toggled.
- Moto system-update screen (10-27 PM; the notification fix didn't stop the full-screen `com.motorola.ccc.ota/.ui.BaseActivity`): the OTA apps are hidden systemlessly by the Magisk module `/data/adb/modules/agentos-no-ota` (`.replace` on `/system_ext/priv-app/3c_ota` and `Fota`). Verified after reboot: neither package exists. Undo: delete that folder and reboot.
- **agentd runs outside the Termux app (10-27 PM).** Why Termux died: lmkd (`dumpsys activity exit-info com.termux`: `LOW_MEMORY` at 13:55, 14:35, 15:31, 15:39; logcat "Kill 'com.termux' … PSI critical event"), and Android kills an app's children with it. It was not the phantom killer. Now `/data/adb/service.d/agentos-agentd.sh` (= `agentd/deploy/magisk-service.sh`, Magisk, root) is a boot script plus a watchdog: it starts `termux-boot.sh` as uid 10401 via `setpriv` (inet group, full cap bounding set, oom_score_adj −900, magisk context) and restarts it within 3 s. lmkd doesn't track it. Tested: SIGKILL of the Termux app → agentd keeps the same pid; node killed → back in 5 s; loop+node killed → back in 2 s. Also set by that script (at every boot): `settings_enable_monitor_phantom_procs false`, `device_config max_phantom_processes 2147483647` (sync disabled: `persistent`), deviceidle whitelist com.termux, kernel wake lock `agentd`. Termux:Boot entry parked in `~/.termux/boot.off/agentos-agentd`. Undo: `rm /data/adb/service.d/agentos-agentd.sh`; `echo agentd > /sys/power/wake_unlock`; `mv ~/.termux/boot.off/agentos-agentd ~/.termux/boot/`; `settings delete global settings_enable_monitor_phantom_procs`; `device_config set_sync_disabled_for_tests none; device_config delete activity_manager max_phantom_processes`.
- llama-server sets its own oom_score_adj to 1000 (`llama.sh start`), so under pressure the model dies first and agentd last. The one running now (started over adb at −1000, i.e. unkillable) was set to 1000 by hand.
- `local.agentos.uilab` (old prototype) disabled: its QS tile service was relaunched every ~2 s under memory pressure (52 MB each). Undo: `pm enable local.agentos.uilab`.
- Don't run `uiautomator dump` on the phone during the demo: it suspends and re-binds accessibility services (the broker restarts, and agentd's events drop for 2–15 s). That re-bind is how a second Broker forked the audit chain today (android/README.md "Audit chain"). Use screenshots instead.
- Audit: the 8 broken links of 10-27 are marked by one `audit.break` line (2968), nothing rewritten. `python3 android/tools-client.py audit` → chain OK, 8 marked breaks.
- Motorola nag notifications (10-27, verified: 0 records after airplane on/off + 75 s). The Settings toggle was a no-op: the OTA app's POST_NOTIFICATIONS is SYSTEM_FIXED and its channels are importance-locked. What holds:
  - `com.motorola.ccc.ota`: `su 1000 -c "pm revoke com.motorola.ccc.ota android.permission.POST_NOTIFICATIONS"` (needs uid 1000, plain root is refused). Undo: `su 1000 -c "pm grant com.motorola.ccc.ota android.permission.POST_NOTIFICATIONS"`.
  - `com.motorola.ccc.notification` (ch_hello_moto_content), `com.motorola.gamemode` (play_game, ch_games), `com.motorola.setup` (SetupWizardService): every channel set to importance 0 through INotificationManager.updateNotificationChannelForPackage, helper dex `/data/local/tmp/nh.dex` (source `/tmp/stagehygiene/NH.java`; run `su -c "CLASSPATH=/data/local/tmp/nh.dex app_process / NH list|block|cancelall|enabled <pkg> <uid>"`). Undo: Settings → Apps → app → Notifications → turn the channel back on (or `pm clear <pkg>` for the Moto ones). Uids: ota 10308, ccc.notification 10307, gamemode 10197, setup 10348.
  - `com.motorola.actions` (Moto Actions, uid 10215): its "Fast Flashlight" heads-up (channel `FDN` "Feature discovery", importance 4) popped on every torch toggle. Fixed 10-27 PM: `su 1000 -c "pm revoke com.motorola.actions android.permission.POST_NOTIFICATIONS"` + `su 1000 -c "pm set-permission-flags com.motorola.actions android.permission.POST_NOTIFICATIONS user-set user-fixed"` (so updates/reboots don't re-grant it). Verified: flashlight on/off through agentd (`device.set {torch}`) → 0 Moto Actions notifications, no pop-up. Chop-chop flashlight etc. still work (only its notifications are off). Undo: `su 1000 -c "pm clear-permission-flags com.motorola.actions android.permission.POST_NOTIFICATIONS user-set user-fixed"` then `su 1000 -c "pm grant com.motorola.actions android.permission.POST_NOTIFICATIONS"`.
  - Cancelled once (not blocked): all Calendar, Google app (stories) and systemui "fully charged" notifications; they can come back, and Calendar reminders can pop up before an event. Left: Termux, plus the system "Developer options"/USB-debugging ongoing items (they stay while adb is on).
  - No iptables, no firewall, no Magisk policy or reboot involved.
- **Demo reset (10-27 PM):** before going on stage, `python3 android/tools-client.py demo-reset`, or on the phone: command center › hold the "Protected today" title until it says "Tap to start fresh", then tap it. Protected today and Activity then count from that moment. (10-28 AM: Home's For you shows only things to act on; the blocked/tracker tallies moved to Command center › Protected today, with taps to Activity/Blockers.) It is one `demo.reset` audit line (owner/adb only; agentd and models are refused); nothing is deleted and the chain stays whole. "Ads blocked" (iptables counters) and the trackers count are not reset.
- Prompt Guard: Maps' navigation safety banner no longer flags (`navigate` tasks were tainted for 10 min). See android/guard-tests/README.md.
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
- Blockers: Block / Block all / Unblock are one tap (owner line: launcher uid → :7777, no card). Since 10-28 agents can't block or unblock at all (broker refuses agentd).
- agentd edits (events.js FORWARD `tracker.blocked`, `vault.changed`; tools.js descriptions) need an agentd restart to take effect; the launcher works without it.

## Next up
1. Fingerprint press at the phone (beats 7, 8), and the open items in pitch/rehearsal/ISSUES.md. Update demo-script.md to the RUN.md order.
2. Watch app / plug (optional), the deck visuals (Fable) and a 2-minute backup video.

Device rule: `mkdir /tmp/agentos-device.lock` before state-changing adb, and `rmdir` only if your mkdir succeeded.
