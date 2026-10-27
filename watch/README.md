# watch/ — AgentOS on the Galaxy Watch4

A small Wear OS app ("Agent") that puts the phone's agent on your wrist:
- **Glance:** a round A2 face shows what the agent is doing right now ("Reply to Sam" / "Typing in Messages"), with the rim glowing in the state color (orange working, blue needs you, green done, red flagged, grey idle). The same line sits in the watch's notification stream.
- **Buzz:** a strong buzz when an approval card goes up on the phone ("Fingerprint on phone" / "Approve on phone"), a double buzz when the task is done, "Auto-approved ✓" when the reviewer clears a step.
- **Tap to talk:** tap the face, speak, and the words go to agentd as a normal `ask` (same tools, same approval gate as typing on the phone). The short reply shows on the watch.

Ranking of all watch ideas and why these two won: `spikes/watch/IDEAS.md`. What other owners need to do: `NEEDS.md`.

## How it works (picture: the watch is a pager clipped to the phone's agent)
`Link` (a foreground service) holds one WebSocket to agentd and turns its broadcast events (`task.state`, `audit.append`, `approval.shown/resolved`, `review`, `guard.flag`) into one line of state. `Face` draws it. `Ws` is a ~70-line WebSocket client, because we build without Gradle/OkHttp.
No Data Layer: it would need Google's wearable library and a matching phone app. Instead the watch talks to agentd's existing WebSocket with agentd's token (the same allow-list idea as the launcher: only our apps hold the token).

Getting there today: agentd listens only on the phone's 127.0.0.1, so `install.sh` builds an adb tunnel: watch 127.0.0.1:8787 → Mac :18787 → phone 127.0.0.1:8787. **The Mac must stay connected to both** (USB to the phone, Wi-Fi adb to the watch). NEEDS.md #1 removes the Mac.

## Status (honest)
| What | State |
|---|---|
| Builds (`./build.sh`, javac + D8, minSdk 30, target 33) | ✅ |
| Connects to the real agentd and shows live events | ✅ verified **on the Moto** (installed there as a stand-in): it picked up another agent's live task and showed "Tap 7" with the orange glow |
| Runs on the Galaxy Watch4 | ❌ not tried: the watch is not paired with the Moto and not visible on Wi-Fi adb |
| Tap to talk (watch speech recognizer → `ask`) | built, **not verified** (needs a watch with Google speech input) |
| Approve on the wrist, watch as second factor, heart rate | not built; specs in NEEDS.md |

## What the founder needs to do (about 5 minutes)
The watch does **not** need to be re-paired to the Moto for this; it only needs Wi-Fi on the same network as the Mac.
1. Watch: Settings → Connections → Wi-Fi → join the same Wi-Fi as the Mac (or the phone's hotspot). If it's paired by Bluetooth to a phone, Wear turns Wi-Fi off to save battery: set Wi-Fi to **Always on** or turn Bluetooth off for the demo.
2. Watch: Settings → About watch → Software information → tap **Software version** 7 times (turns on Developer options). Tell us the Wear OS / One UI Watch version shown there.
3. Settings → Developer options → turn on **ADB debugging** and **Wireless debugging**. Tap Wireless debugging → **Pair new device**: it shows `IP:port` and a 6-digit code.
4. On the Mac (or tell an agent the numbers):
   ```sh
   . tooling/android-env.sh
   adb pair <ip>:<pair-port> <code>      # once
   adb connect <ip>:<port>               # the port on the main Wireless debugging screen (not the pair port)
   watch/build.sh && watch/install.sh <ip>:<port>
   ```
5. On the watch: tap **Allow** for notifications. Start a task on the phone and watch the wrist. Tap the face to talk.
6. After the demo: turn **ADB debugging off** on the watch (it is full control of the watch).

If the Mac or watch reconnects, rerun `watch/install.sh relink <ip>:<port>`. To try it on the phone without a watch: `watch/install.sh phone` (uninstall after: `adb uninstall local.agentos.watch`).

## Security notes
- The token goes phone (root `cat`) → pipe → watch (`run-as` into the app's private dir). It never appears in argv or logs. `run-as` works because the APK is built debuggable; anyone with adb on the watch can read it, but adb on the watch is already full control.
- The watch holds the full agentd token today (it could also call the launcher's read-only `rpc` methods). NEEDS.md #1 asks for a watch-scoped token that only allows `ask`/`stop`.
- The tunnel only exists while the Mac's adb is attached; agentd itself still binds to 127.0.0.1.
