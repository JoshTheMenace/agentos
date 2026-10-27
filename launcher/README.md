# launcher/ — `local.agentos.home`, the AgentOS home screen

Design: A2 · Calm Glass iOS Dark (`design/mockups/A2-calm-glass-ios-dark.html`, tokens in `design/A2-tokens.md` and `web/theme.css`).

Think of a picture frame. The frame is a tiny native HOME activity (`src/local/agentos/home/Home.java`); the picture is `web/`
(HTML/CSS/vanilla JS, bundled into the APK as assets). The frame only hands the page a few things: the app list and launching,
haptics, system settings, the next calendar event, the two bearer tokens, and an async relay to the tool server. Approvals never
happen here: a fingerprint card drawn by `local.agentos` is the gate.

```
web/index.html + app.js ──ws://127.0.0.1:8787 (agentd: ask, voice.*, events)
                        ──Native.rpc ──▶ Home.java ──POST 127.0.0.1:7777/rpc (audit.tail, firewall.*, task.*)
                        ──fetch /memory/graph.json (agentd)
```

## Screens
| Page | What |
|---|---|
| Home | clock, status line, trust pill (active lease + minutes left), next calendar event, two quiet demo tiles (watch, plug), "What should I do?" bar, squircle dock of real apps. Swipe up: app drawer with search. Swipe down: notification shade. Swipe left: command center, again: memory. |
| Ask sheet | sends `ask` to agentd; streams the reply, tool steps (orb → done/denied), guard alerts, and an inline "Send it?" card on `approval.needed` (Approve/Deny send "Yes, send it." / "No" as a normal message; the fingerprint card on the phone remains the real gate) |
| Voice | Siri-like rotating edge + glows, transcript, hold-the-orb push-to-talk over WebRTC (`voice.start` offer/answer), tool steps for delegated requests |
| Command center | active task with Pause/Stop, model switch (Codex / Local), recent activity from the audit log, links to Permissions and Settings. "Stop all" = agentd `stop` + `task.stop` |
| Permissions | grants (lease apps, minutes left, Revoke), per-app network toggles (`firewall.set`, fingerprint), memory sources (consent ledger), the never-list |
| Memory | force-laid-out graph of `/memory/graph.json` (tap a node for a one-line detail) and sources |

## Build, install, test
```sh
launcher/build.sh            # javac + D8 + aapt2, ~3 s -> launcher/build/home.apk (web/ bundled as assets)
launcher/install.sh --set    # takes the device lock; installs, copies both tokens into the app's private dir (root), grants mic + calendar, sets default home
launcher/install.sh          # same without changing the default home
launcher/install.sh moto     # restore Moto's launcher
```
Restore Moto's launcher by hand: `adb shell cmd package set-home-activity com.motorola.launcher3/com.android.launcher3.CustomizationPanelLauncher`

Live editing from the Mac (no rebuild): `launcher/install.sh dev` then `python3 -m http.server 5173 -d web`; the page loads over
`adb reverse`. `launcher/install.sh nodev` goes back to the bundle. Inspect the WebView from `chrome://inspect`.

Tokens: like the keyboard, `install.sh` copies `~/.agentos/agentd.token` (Termux) and `/data/data/local.agentos/files/token` into
`/data/data/local.agentos.home/files/` as root. agentd also re-provisions its token at start if `local.agentos.home` is in
`AGENTD_CLIENT_PKGS`.

## Notes
- 3-button navigation. Edge to edge; the shell passes status/nav/IME insets to CSS variables.
- Only `transform`/`opacity` animate; no blur, no libraries. Inter is bundled (`web/fonts`, 350 KB).
- Watch heart rate and the smart plug are stubbed demo tiles (marked "demo").
- `shots/`: screenshots from the phone.
