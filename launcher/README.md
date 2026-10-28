# launcher/ — `local.agentos.home`, the AgentOS home screen

Design: A2 · Calm Glass iOS Dark (`design/mockups/A2-calm-glass-ios-dark.html`, tokens in `design/A2-tokens.md` and `web/theme.css`).

Think of a picture frame. The frame is a tiny native HOME activity (`src/local/agentos/home/Home.java`); the picture is `web/`
(HTML/CSS/vanilla JS, bundled into the APK as assets). The frame only hands the page a few things: the app list and launching,
haptics, system settings, the next calendar event, the agentd token, and a GET on agentd. Tool-server calls go through agentd's
`rpc` passthrough, except the **owner line**: `Native.phone(id, method, params)` posts to `:7777` with `files/phone.token` from this app's own uid, which the broker treats as the owner's tap (Blockers taps, Passwords list/delete). agentd and the model connect from Termux's uid and never get that. Approvals never happen here: the approval card drawn by `local.agentos`
(tap or voice; fingerprint only for money, installs and saved passwords) is the gate.

```
web/index.html + app.js ──ws://127.0.0.1:8787 (agentd: ask, voice.*, suggestions.watch, rpc {audit.tail, approval.list, firewall.*, task.*, trackers.list, app.mod.list/remove}, forwarded broker events)
                        ──Native.get ──▶ Home.java ──GET 127.0.0.1:8787/memory/graph.json
```

No polling: one `audit.tail` + `approval.list` snapshot on connect/resume, then `task.state` (lease, real `expires`, steps, paused),
`audit.append` (recent activity), `approval.shown/resolved` and `guard.flag` keep the home, command center and permissions live.

## Screens
| Page | What |
|---|---|
| Home | clock, status line, **Now** (the live task: goal, current step from `tool.request`/`audit.append`, a sliding progress bar, pause/stop; tap opens the sheet; a task started from home lingers 20 s with its answer), **For you** (suggestions from agentd's `suggestions` feed plus "Leave for …" from the real next calendar event; one tap sends the item's `goal` as an `ask`, or opens a view), the next calendar event (tap opens Calendar), "What should I do?" bar, squircle dock of real apps. No dummy data; empty state is one line, "All caught up". Swipe up: app drawer with search. Swipe down: notification shade. Swipe left: command center, again: memory. |
| Ask sheet | sends `ask` to agentd; streams the reply, tool steps (orb → done/denied) and guard alerts. Approvals are not here: on `approval.shown` the header says "Waiting for your approval", on `approval.resolved` "Approved" / "Auto-approved ✓" / "Denied". The native card is the only gate. |
| Voice | Siri-like rotating edge + glows, transcript, hold-the-orb push-to-talk over WebRTC (`voice.start` offer/answer), tool steps for delegated requests |
| Command center | **Protected today** (trackers blocked from `trackers.list`, scam messages caught = `guard.flag`, actions stopped = blocked/denied, approvals · auto; all from today's audit, `audit.tail n:500`; after a **demo reset** only from the last `demo.reset` line: hold the title, then tap "Tap to start fresh" → `audit.demo_reset` over the owner line. A view filter, nothing deleted; ads blocked keeps counting), **Tasks** (running workers + upcoming scheduled tasks from `schedule.list`, x = `schedule.cancel`), **Model** (Codex default / Claude / On-device with an offline badge; sent as `provider` on each `ask`), **Activity** (today's audit in plain language: "Tapped "Send" in Pigeon", "Scam message caught in Pigeon · hidden from the agent", "Unlocked for your scheduled task", "Looked up Dan · Contacts"; Caught / Blocked / Denied / Auto / Approved tags, no seconds; this is the calm log that replaces red pop-ups), then a 2-column tile grid: Blockers, Passwords, Mods, Memory, Plugins, Permissions, Settings with live notes. "Stop all" is grey until something runs. |
| Blockers | per app: trackers seen and blocked (`trackers.list` → `{apps:[{pkg,label,trackers:[{domain,company,blocked,hits}]}]}`), green = blocked, orange = open, "Block", "Block all" (bar) and "Unblock" go over the **owner line** (`Native.phone` → `:7777` from this app's own uid → `trackers.block/unblock`): the tap is the approval, no card, no fingerprint. Only tracker-list names can be blocked (the broker checks). Refreshes on `tracker.blocked`. Legend under the title (green = blocked, orange = still sending, numbers = requests seen). Empty state: "No trackers seen". Reached from the command center or Permissions › Network. |
| Passwords | the vault (`vault.list` over the owner line → `{entries:[{id,label,apps,username}]}`, never the password): app icon, label, username, "Fingerprint to use · only in <app>", Delete (two taps, `vault.remove`). "Add password" opens `local.agentos/.VaultAddActivity`, a native screen: the password is typed and encrypted there, never in this WebView. Refreshes on `vault.changed` and on resume. |
| Mods | buttons agents added to apps (`app.mod.list` → `{mods:[{id,package,app,label,shown}]}`): app icon, label, Remove (`app.mod.remove {id, by:"settings"}`, no approval). Refreshes on `mod.changed`. Empty state: "Ask to add a button to any app". |
| Plugins | The owner's switches for groups of the agent's direct-API tools (agentd `plugins.js`): name, one-line summary, toggle; "Always on" lists the core. The list comes from agentd (`plugins.list`); a toggle goes over the owner line to the broker (`plugins.set`, launcher uid only), so agentd and models can't flip one. Off = the agent loses those API tools but may still use the app on screen. Mods and the Network toggles now also use the owner line first, so they keep working while their plugin is off. Shots: `shots/plugins.png`, `shots/plugins-one-off.png` |
| Permissions | grants (lease apps, minutes left, Revoke), per-app network toggles (`firewall.set`, tap approval; per-domain `name:` rules show as "6 trackers blocked", only a rule without `dest` reads "Blocked"), memory sources (consent ledger, read-only), the never-list |
| Memory | force-laid-out graph of `/memory/graph.json` (tap a node for a one-line detail) and sources |

## Build, install, test
```sh
launcher/build.sh            # javac + D8 + aapt2, ~3 s -> launcher/build/home.apk (web/ bundled as assets)
launcher/install.sh --set    # takes the device lock; installs, copies the agentd token into the app's private dir (root), grants mic + calendar, sets default home
launcher/install.sh          # same without changing the default home
launcher/install.sh moto     # restore Moto's launcher
```
Restore Moto's launcher by hand: `adb shell cmd package set-home-activity com.motorola.launcher3/com.android.launcher3.CustomizationPanelLauncher`

Live editing from the Mac (no rebuild): `launcher/install.sh dev` then `python3 -m http.server 5173 -d web`; the page loads over
`adb reverse`. `launcher/install.sh nodev` goes back to the bundle. Inspect the WebView from `chrome://inspect`.

Token: `install.sh` copies `~/.agentos/agentd.token` (Termux) into `/data/data/local.agentos.home/files/` as root; agentd does the
same at start (`local.agentos.home` is in the default `AGENTD_CLIENT_PKGS`). There is no `:7777` token in this app.

## Home polish (climb-a, 10-27 PM)
- Portrait-locked (manifest); auto-rotate was turned off on the demo phone. `--l3` lifted .3 → .38 for projector contrast (brighter only; backward compatible).
- **Try chips** above the ask bar and inside the empty ask sheet: prompts that prefill the sheet (scheduled task, trackers, reply, sign-in) and an on-device model toggle. Prompts, not data.
- **Task card**: optimistic card the instant a suggestion/ask starts, pretty tool names (`TOOLN` in app.js; new agentd tools fall back to "Snake case → Words"), elapsed time + step count, orange glow while acting, check/x + bar fill on finish, heavy haptic, 45 s linger (survives a trip through another app via `task.list`). Tapping it opens the sheet, which is the task's transcript.
- **Scheduled cards**: blue calendar tile, "Tomorrow 11:00 AM · scheduled" (from `at`).
- **Moments**: approved `notifications.reply/autoreply`, `vault.fill`, `trackers.block`, `schedule.created`, `app.mod.add` audit lines show a 45 s "done" card on Home.
- **Ask sheet**: typing dots while thinking, 17 px answers, "Done · 6s". **Voice**: "Try saying" examples until the first transcript; the live task card sits above the orb.
- Shots: `shots/climb-a/` (before/after pairs; `.tools/task.mjs` starts a task from the Mac over `adb forward tcp:18787 tcp:8787`).

## Notes
- 3-button navigation. Edge to edge; the shell passes status/nav/IME insets to CSS variables.
- Only `transform`/`opacity` animate; no blur, no libraries. Inter is bundled (`web/fonts`, 350 KB).
- Suggestions: the page sends `{type:"suggestions.watch"}` on connect; agentd answers with `{type:"suggestions", items:[{id,title,detail,app?,goal?,icon?,view?}]}` now and whenever it changes (`agentd/home-suggest.js` is the stand-in until `context.js` lands; keyboard replies carry an `id`, the home feed doesn't). `icon`: msg | car | eye | shield | cal | spark; `view`: blockers | activity.
- Styling on the Mac without the phone: `python3 -m http.server 5173 -d web`, then `index.html#demo` (feed + a running task), `#demo-blockers`, `#demo-center`. Only in a browser, never on the phone.
- `shots/climb-b/` (2026-10-27 PM, secondary screens hill-climb): `01-center-before` → `01-center-after-final`, `05-center-scrolled-before` → `05-center-scrolled-after-loop7` (tiles), `02-blockers-before` → `02-blockers-after-loop6`, `07-perms-before` → `07-perms-after-final`.
- `shots/` (phone, 2026-10-27): `home` = `home-idle` (phone, nothing running: next event + "All caught up"), `home-feed-mac` (Mac `#demo` render: Now card + For you; the phone's agentd predates the feed), `center` (Activity log with tags, Blockers link), `blockers` (empty state), `memory`, `permissions`, `drawer`, `voice`, `ask-*`.
