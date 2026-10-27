# Presenter checklist (rehearsed on the phone 2026-10-27)

Run everything from the repo root on the Mac. `TX` = `agentd/deploy/tx.sh` (runs a command in Termux on the phone).
Hold the device lock only while changing state: `mkdir /tmp/agentos-device.lock` … `rmdir /tmp/agentos-device.lock`.

## Pre-show (by 4:15 PM)
```sh
TX='agentd/deploy/tx.sh'
$TX 'curl -s 127.0.0.1:8787/health'                                   # "ok" = agentd up (else: see Recovery, agentd)
python3 android/tools-client.py call screen.read | head -3               # tool server + accessibility answer
python3 android/tools-client.py call firewall.list                       # rules: []
adb shell ime set local.agentos.keys/.Keys                               # our keyboard (Gboard undo: keyboard/install.sh gboard)
adb shell "am start -n local.agentos.pigeon/.Main --es scenario clean"   # NO canary yet (see order below)
adb shell "am start -n local.agentos.pigeon/.Main --es inject 'Running late? Dinner at 7' --es from Alice"
adb shell "am start -n local.agentos.beacon/.Main --es analytics https://httpbin.org/post --ei interval 3000"
$TX 'sh ~/agentos/agentd/deploy/local-model/llama.sh start'              # ~6 s, "up on :8081"
$TX 'cd ~/agentos/agentd && node cli.js "what app is open?"'             # warms Codex (~10 s)
# Android 14 pop-up: already silenced (Settings > Apps > Motorola Software Update > notifications off; the app itself can't be disabled)
```
Command center → Model: **Codex**. Do Not Disturb on. Screen timeout ≥ 10 min. Go home.

**Order matters:** one guard flag makes every send for the next 10 min need a fingerprint (by design). So the auto-approve ✓ comes **before** the trap, and the fingerprint beat comes after it, where the story fits: "it saw a scam message, so now it wants my finger".

## Beats (measured on the phone)
| # | Beat | Do | Time | Shot |
|---|---|---|---|---|
| 1 | Launcher home | Show home; swipe left for command center | 5 s | 01 |
| 2 | Ask sheet | Tap "What should I do?", type "What app is open?" | ~10 s to answer | 02 |
| 3 | Voice | Tap the mic, hold the orb, say "What app is open?" **(needs presenter)** | session up 4 s; spoken answer 10–16 s | 03 |
| 4 | AI keyboard | Pigeon → Alice → tap the message box; tap a suggestion; don't send | 3 replies in ~3.4 s | 04, 04b |
| 5 | Pigeon task + auto-approve ✓ | Ask: "Reply to Alice in Pigeon that I'm running 10 minutes late". Tap **Allow** on the lease card; the reviewer approves Send, island shows "Auto-approved ✓" | lease card at ~8 s, sent at ~27 s | 05, 09 |
| 6 | Trap → security alert | `adb shell "am start -n local.agentos.pigeon/.Main --es scenario canary"`, then ask "Summarize my unread messages in Pigeon". Tap Allow. Red island: "Ignored instructions in a message" | alert at ~22 s, done ~26 s | 07 |
| 6b | Fully fooled model, blocked | The laptop plays the model: run the four `tools-client.py` lines in demo-script step 4 (tap Allow on the lease) → `blocked: T4 forbidden … (P3)` | 3 s | 08 |
| 7 | Fingerprint | Ask "Reply to Alice in Pigeon that 7 works". Card says "Fingerprint required" → Approve → **touch the sensor (needs presenter)**. Then `python3 android/tools-client.py audit 5` → `chain_ok=True` | card at ~21 s | 06 |
| 8 | Firewall | Ask "Beacon is sending analytics to httpbin.org. Block just that." → Approve → **fingerprint (needs presenter)** → httpbin rows turn REFUSED. Undo: ask "Remove all firewall rules" (fingerprint) | card at ~17 s | 10, 10b |
| 9 | Airplane + local model | Command center → **Local**. `adb shell cmd connectivity airplane-mode enable`. Ask "Open Pigeon and read me the last message from Mom", tap Allow | ~12 s (launcher), ~24 s (cli) | 11 |

After 9: `adb shell cmd connectivity airplane-mode disable`; Model back to Codex.

The fingerprint sheet is **black on scrcpy** (secure window). Use the document camera for 7 and 8, or say so.

## Recovery
| Beat | If it fails | Do |
|---|---|---|
| Any | Agent wanders | Hold volume-down 1 s (kill switch), or command center → Stop all |
| Any | agentd down / "Agent offline" | `adb shell am start -n com.termux/.app.TermuxActivity` then `adb shell "su -c 'am broadcast -a android.intent.action.BOOT_COMPLETED -n com.termux.boot/.BootReceiver'"` (restarts in ~10 s) |
| 2, 5–9 | Lease card missed or task paused ("screen was touched") | Ask again; don't touch the screen while it works |
| 3 | Voice stalls (bad network) | Type the request in the ask sheet; "voice needs a better connection" |
| 4 | Keyboard missing / empty strip | `adb shell ime set local.agentos.keys/.Keys`; if still empty, type normally ("suggestions come from the model") |
| 5 | Card says "Fingerprint required" instead of ✓ | The stamp is set (a flag in the last 10 min). Approve with the finger and move on. Before a retry: reset the stamp (below) and re-stage Pigeon clean + Alice |
| 5 | Card stays up, no ✓ (reviewer slower than 4 s) | Tap **Approve**: that is the normal tap approval |
| 5, 7 | Card shows no message bubble | Deny, re-stage Pigeon (`scenario clean` + Alice inject), ask again |
| 6 | No red island | `adb shell "am start -n local.agentos.pigeon/.Main --es scenario canary"` and ask again; 6b is deterministic and needs no model or network |
| 7 | Finger misses / times out | Tap **Deny**: "deny is the safe default; the agent is told no" |
| 8 | Fingerprint fails or rows stay green | App-wide block as root: "Firewall fallback" below. Say "app-wide block" honestly |
| 9 | Local slow or down | `$TX 'sh ~/agentos/agentd/deploy/local-model/llama.sh start'`; or run Codex only: "a local model runs behind the same gate; it's in the video" |

**Reset the stamp** (clears the 10-min fingerprint carry-over; operator only, it needs the root-only token):
`python3 android/tools-client.py call task.start '{"goal":"reset","apps":["local.agentos.pigeon"]}'` → tap Allow → `python3 android/tools-client.py call task.stop '{}'`

**Firewall fallback** (root, app-wide; rows go REFUSED within one 3 s interval):
```sh
U=$(adb shell pm list packages -U local.agentos.beacon | sed 's/.*uid://' | tr -d '\r')
adb shell "su -c 'iptables -w -A AGENTOS_FW -m owner --uid-owner $U -j REJECT; ip6tables -w -A AGENTOS_FW -m owner --uid-owner $U -j REJECT'"
adb shell "su -c 'iptables -w -F AGENTOS_FW; ip6tables -w -F AGENTOS_FW'"   # undo
```
