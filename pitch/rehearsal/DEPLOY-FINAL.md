# Final integration deploy (10-28, 07:00–08:35)

## What went live
- **agentd at HEAD** (b49e4a3, plus 421727b which is broker-only). Six runtime files changed since deploy #1: `catchup.js` (empty catch-up a36a04c), `prompt.js` (fb9abad), `providers/codex.js` (one-call actions, multi-part guard, first-suggestion streaming, spare thread b5da56e), `providers/router.js` (offline probe 757636a), `server.js`, `deploy/magisk-service.sh` (already in `/data/adb/service.d`). Pushed with `git archive HEAD <files>`; no npm install. All 85 tracked agentd `.js` files (857 with dependencies) passed `node --check`.
- **One** agentd restart at **07:03:35** (pid 28442 → 19770). Still 19770 at 08:32.
- **APKs from HEAD:** broker (`local.agentos`, incl. ba0b13e + the fix below), launcher (queued asks 4814e76, ba0b13e) and keyboard (b5da56e) installed at 07:03. Broker reinstalled at 07:31 (to clear Beacon's radar entry) and at 07:46 (fix 421727b).

## Fixed during the smoke test
| Bug | Fix | Live? |
|---|---|---|
| After the reboot below, Calendar re-posted ~10 reminders with **empty text**. The broker's "copied from untrusted content" check matched `""` against `""`, so every text-less API action (e.g. `alarms.delete`) skipped the reviewer and needed a person | `Broker.copied()`: empty text never counts as copied (421727b, 1 line) | Yes, 07:46. Same delete then listed `reviewer` as an allowed channel again |

## Smoke tests (harmless inputs, nothing sent, no approval card tapped)
| Beat | Result |
|---|---|
| Home | OK (screenshot after cleanup) |
| Ask sheet + stopwatch | "What's my battery level?" → **Done · 6.6 s** |
| What did I miss? | Nothing new: 3.6 s cold, 1.6 s warm. With a Pigeon test notification (TestNotifications, "AgentOS test chat"): card + "Robin asked on Pigeon if the 2 PM review is still on…", **2.3 s**. Test notification cleared |
| Keyboard in Pigeon (Mom: "Are you free Thursday at 3?") | Focus chips in **1.0 s**: "Looks free then.", "Thursday 3 PM to 5 PM looks free.", "Would Thursday 3 PM to 5 PM work?" (calendar local, 37 ms). Typed "Sure": first call 5.7 s (cold, hedge), then 2.3 / 4.5 / 2.2 s. Nothing sent, field cleared |
| Trackers counter with Beacon | Beacon → `google-analytics.com` showed as "google-analytics.com · Google Analytics" in Blockers (3,051 blocked). **Restored:** Beacon back to httpbin.org and stopped, uid 10420 removed from `trackers.json`, broker reinstalled (8 apps on the radar, as before) |
| Pigeon deep mod | First try: "Pigeon was not detected in the foreground" (issue 5). Retry: **Done · 8.7 s**, preview verified + auto-restored, nothing applied. `app.deepmod.remove` over adb → revision 9, spec null (vanilla) |
| Scheduled task + test alarm | Scheduled 07:36, fired 07:37:40, alarm 23:47 "AgentOS test" set via the Clock intent in 3.8 s. Agent delete raised a card each time (not tapped; timed out): first the empty-text bug, then the reviewer chose "tap" (issue 3). Deleted by hand in Clock: exactly one "AgentOS test 11:47 PM" card; 6:17 (off), 8:30, 9:00 and 10:15 "Before standup" untouched |
| Offline fallback | `~/.agentos/force-offline` → flashlight on, **Done · 4.6 s · On device**. Flag removed → next ask on Codex, 2.9 s |
| Voice | Launcher voice mode on the phone + a `voice.delegate` (what the page sends when GPT Live delegates): task running 0.2 s, "On it." 3.3 s, done 6.2 s, spoken **"Done, in 6 seconds. It's at 100% and charging."** Speech recognition itself not tested (issue 4) |
| Cross-app Codex task | "Turn on the flashlight, then open Pigeon and read me Alex's last message." → **Done · 10.1 s**, correct ("Wild ending. Call you later."). Without "open", 3.8 s but wrong (issue 2). Flashlight off after |

## Open issues
1. **`cmd wifi set-connected-score 60` crashed Android's system server** (soft reboot at 07:03:54). Log: `FATAL EXCEPTION IN SYSTEM PROCESS: WifiHandlerThread … NullPointerException … WifiServiceImpl.bindScorerService(WifiServiceImpl.java:498)`. Every app restarted, the phone locked, Wi-Fi took ~5 min and a rescan to rejoin, low-latency mode was lost, a "Select USB mode" sheet popped up and Calendar re-posted its reminders. agentd survived (Magisk loop). **Repro:** `adb shell su -c 'cmd wifi set-connected-score 60'` (it worked many times overnight, then crashed this once). **Stage workaround: never run it.** Use `scripts/preflight.sh --fix wifi` (low-latency mode only). If Wi-Fi drops: toggle Wi-Fi once, then `--fix wifi`. If it happens anyway: unlock, dismiss the USB sheet, wait for Wi-Fi, rerun preflight.
2. **"Tell me X's last message in <app>" can answer from memory without opening the app**, and memory can be stale (it said Alex's last Pigeon message was "Hey, is this still your number?", from Oct 26, before Pigeon was reset). **Repro:** "Turn on the flashlight, then tell me Alex's last message in Pigeon." **Stage workaround:** say "open <app> and read me…".
3. **The reviewer may hand a delete to you** ("Confirm this targets alarm 5 specifically") instead of auto-approving. A model judgment call; deploy #1 auto-approved the same delete. **Stage workaround:** don't put a delete in the demo, or be ready to tap.
4. **Voice:** (a) a cold voice-mode start took ~7.6 s to "Listening"/`voice.started` (warm reuse 1.4 s; the warm connection is dropped 60 s after voice closes). (b) The spoken "Done, in N seconds" came 6.3 s after the task finished (overnight ~1 s). **Repro:** launcher → mic → speak "what's my battery?" and time it. **Stage workaround:** open voice once right before the beat and wait for "Listening" before speaking. (c) Test rig only: the Mac's headless-Chrome WAV injector now sends 0 audio bytes (SDP fine, AudioContext running), so the say→WAV→Chrome test can't run until that's fixed. Not a product bug.
5. **Pigeon deep-mod preview failed once right after a broker reinstall** ("not detected in the foreground"); the retry worked. Pigeon isn't on stage. Workaround: `am force-stop local.agentos.pigeon` after any broker reinstall, or retry.
6. Calendar reminder notifications are back after the reboot (empty-text ones too). DND at stage hides pop-ups. Harmless for the broker now (fix above).
7. Home says "12 actions blocked today" from tests: demo-reset before stage.

## Left on the phone (08:35)
Home in front, overlays closed, ask sheet cleared, voice closed, flashlight off, force-offline flag removed, Pigeon `scenario clean` (my injected message gone), test notification cleared, Beacon stopped on httpbin.org, Pigeon deep mod removed, test alarm deleted, no open cards or tasks. Mac: headless Chrome, voice proxy and adb forwards stopped; Mac output mute restored.

## Final preflight (08:32, `--fix wifi,warm,ime,home`)
GREEN: agentd, Deployed code (matches 421727b9), Wi-Fi latency, Internet, Codex, Local model, Keyboard, Home, Battery 100 %, Test calendar items, Approval cards, Tasks, Prompt Guard, Audit chain (6026 lines, 8 marked breaks).
RED, all expected now: Wake word (off; never toggled by agents), Do Not Disturb and Demo reset (stage-time), Test alarms (needs a lease; Clock checked by hand), Chrome tabs (Chrome not running).
