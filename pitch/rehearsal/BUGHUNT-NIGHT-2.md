# Bug hunt round 2: reliability of deploy #1 (10-28, 03:56–04:20)

The build deployed at 03:40 (agentd pid 28442), the speed bench running the whole time. Each beat ran 3–7 times in a row. UI steps ran in short `scripts/withphone` bursts. The launcher was driven over its WebView debugger. No approval card was tapped, nothing was sent, and no alarm or calendar item was made.

## Results per beat
| Beat | Runs | Result |
|---|---|---|
| Voice start/stop in the launcher (no speech) | 9 | 9/9 reached "Listening". First start 2.75 s, warm restarts 0.9–1.4 s, back-to-back restarts 100 ms apart fine |
| Voice full turn (`say` WAV → headless Chrome → :8787, "what's my battery?") | 6 (3 fresh pages, 3 stop/start on one page) | 6/6 "On it" → task → spoken "Done, in N seconds…". N = 4, 5, 5, 4, **30**, 10. See issue 3 |
| "What did I miss?" | 5 | 5/5 the same correct card + answer, total 1.0–2.3 s |
| Ask sheet, 4 asks back to back | 4 | 4/4, "Done · 2.0–5.8 s" |
| Ask sheet, 2nd ask while the 1st runs | 1 + 2 after the fix | **Bug, fixed** (fix 1) |
| Keyboard in Pigeon, fast typing (a word every ~0.3 s), 4 phrases | 4 | 4/4 sensible chips, no crash, field cleared after, nothing sent. Waits are 1.4–3.6 s. See issue 2 |
| Stopwatch (flashlight on, off, battery) | 3 | 3/3 ticks every second, "Done · 5.6 / 4.0 / 8.3 s", island matches the sheet |
| Offline flag flips, llama warm | 7 cycles | Offline side: always local. **Back online: 2/7 still went local** (fix 2). 1 offline failure was the bench (issue 4) |
| Memory pressure | 2 snapshots | No lmkd kill of agentd, launcher, broker or keyboard. Their last exits are all reinstalls (home: mine, 04:07). Free RAM 640–900 MB, 0.5–1.3 GB in ZRAM, PSI memory some avg60 5 %. agentd oom −900, llama 1000 |
| agentd log since the restart | — | No errors. Only noise: the SQLite ExperimentalWarning is printed 49 times |
| logcat, our processes | — | No crash, ANR or WebView exception |

## Fixed
| # | Bug | Fix | Live? |
|---|---|---|---|
| 1 | **The ask sheet dropped a second ask.** If you sent one while a turn ran, the sheet printed the raw "a turn is already running; send stop first" and the ask was lost | `web/app.js`: the ask waits and is sent when the current turn ends. Its bubble moves below the answer. Stop and a lost connection clear the queue (4814e76) | Yes, launcher reinstalled 04:07. 2 asks and 3 asks fired 1.2 s apart all answered in order |
| 2 | **Back online, the next ask could still use the local model.** The router's reachability probe gave up after 1 s. A cold HTTPS probe on the phone sometimes takes longer (measured 1.17 s while idle) | `agentd/providers/router.js`: probe timeout 1 s → 2.5 s. It still answers at the first success when online, and airplane mode (no addresses) is still instant. 12/12 router tests pass (757636a) | **No.** Goes in with the 6:43 deploy (no extra restart) |
| 3 | **The Magisk app was restarted ~5 times a minute** (56 starts in 10 min, ~70 MB each, then killed by lmkd). Every `su` by agentd/Codex was logged through the Magisk app | Phone setting: `magisk --sqlite "UPDATE policies SET logging=0"` (uids 10400, 10401, 2000, 10421). Undo: `… SET logging=1`. Re-run it if a new uid is granted root (like the notification=0 note in STATUS) | Yes, 03:57. 0 Magisk starts in the next 11 min |

## Open (not fixed)
1. **Voice says "Still checking, hang on" even when the task is about to finish** (4 of 6 turns, e.g. "Still checking—hang on. Done, in 4 seconds"). It is the voice model filling time, not a wrong state. It sounds fine; a prompt change isn't worth the risk tonight.
2. **Keyboard and Codex replies arrive all at once after a stall.** In the slow suggestion calls, the whole reply came in 5–33 ms (normal streaming takes 400–600 ms). So the answer was ready and was held back somewhere. In 5 of the 6 calls where the 3 s hedge fired, the first request's reply arrived 93–258 ms after the hedge sent its second request (so a write on the connection seems to release it). The sixth waited 5.9 s. A guess to test, not a finding: the stall is in the path from root Codex through `android-connect-proxy` (:18080) or the shared Responses connection, not in the model. Speed track: compare against Codex over USB (`bench/usb-net.js`) and look at the *spread* between first token and done, not just the median. Raw numbers: `lat` lines from `adb logcat | grep AgentKeys`, and `node agentd/cli.js --suggest reply …` timing.
3. **Codex turn times vary a lot while the bench runs.** One voice battery task took 30.4 s: first tool at 11.3 s, then 19 s for a 14-token answer. A sheet battery ask took 8.3 s, and another 3.0 s. Re-measure with the bench stopped before stage (same as DEPLOY-NIGHT issue 2).
4. **The broker has a single lease, shared by everyone.** While the bench ran, its own `task.start`/`task.stop` calls (shell uid) replaced an ask's lease mid-turn. The offline ask then answered "I couldn't do that: no active task: call task.start first". On stage there is no bench. But a scheduled task that fires during an ask, or a voice task running next to a typed one, would hit the same thing. Don't overlap beats.
5. **Before stage, close background apps the bench opened.** YouTube (147 MB), Facebook (141–202 MB) and Instagram (114 MB) were running. lmkd kills them first, so this is not a risk to our processes, just free RAM. Also run demo-reset: Home shows "1 action blocked today" from tonight's tests.
6. Log noise: the `ExperimentalWarning: SQLite` line repeats in agentd.log. `node --disable-warning=ExperimentalWarning server.js` in termux-boot.sh would quiet it at the next restart. Cosmetic only.

## Left on the phone
Home in front, ask sheet closed and its test history cleared, flashlight off, force-offline flag removed, the Pigeon draft empty, no test files in /data/local/tmp. Headless Chrome and the voice proxy on the Mac were stopped.
