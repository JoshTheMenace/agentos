# API actions: Android's own services instead of taps

Taps are like asking someone to fill in a paper form for you. The API is handing the office a typed record. It's faster, and nothing gets misread.
So when Android has an API for a request, the agent calls it (`Api.java`) and doesn't drive the app's screen.
UI driving is the fallback when no tool fits.

Every API call still goes through `Broker.act()`, the same gate taps use, so it gets the lease, tier, card, Rule of Two, P3 secret check and audit line.
Each call's lease scope is a short name (`calendar`, `contacts`, `sms`, `calls`, `alarms`, `device`, `media`) instead of an app package.
agentd's lease adds it the same way it adds an app.
Reads of other people's text (SMS, call log, contacts, calendar) mark the task untrusted, which lights the first Rule-of-Two lamp.
Each SMS body and event title is scored by Prompt Guard; flagged text is withheld from the model, logged as `guard.flag` and flags the task.
OTPs in SMS go into the task's P3 secrets, so the agent can never type or send them.
The Settings app stays forbidden (T4). The API toggles are allowed and audited.

Tiers: T0 read · T1 navigate/toggle · T2 draft or local change · T3 outward (always a card with the exact content).
**A text, a call or a calendar invite always needs a person** (tap, voice or fingerprint, never the auto-reviewer: `Broker.channels`). Other T3 cards (delete an event or alarm) may be auto-approved by the reviewer when the user asked for them.

| Request | API | Broker method → model tool | Tier / approval | Status |
|---|---|---|---|---|
| "Lunch with Sam tomorrow at noon" | `CalendarContract.Events` insert into the primary writable calendar, then re-query the row | `calendar.add` → `add_event` | T2, no card. **T3 with attendees** (Google emails them invites): a person approves a card listing them | ✅ tested (140 ms, read back from the provider) |
| "What's on my calendar Friday?" | `CalendarContract.Instances` (repeats expanded) | `calendar.find` → `find_events` | T0, taints (invites carry other people's text), Prompt Guard on titles | ✅ tested |
| "Move it to 1 pm" | `Events` update (keeps the duration) | `calendar.update` → `update_event` | T2 | ✅ tested |
| "Delete my dentist appointment" | `Events` delete | `calendar.delete` → `delete_event` | T3 (card shows the event) | ✅ tested |
| "What's Sam's number?" | `ContactsContract.Data`: name or nickname → phones, emails | `contacts.find` → `find_contact` | T0, private, taints | ✅ tested |
| "Text Sam I'm 10 min late" | `SmsManager.sendMultipartTextMessage`, sent-result awaited. A name is resolved to the contact's mobile on the phone | `sms.send` → `send_sms` | **T3 outward, private, a person only**: card "Text Sam · <number>" + the exact text bubble | ✅ to the card (fake 555 number, denied). Test outward tools only up to the card |
| "Read my last texts" | `Telephony.Sms` | `sms.list` → `read_sms` | T0, private, taints, Prompt Guard, P3 OTPs | ✅ tested (inbox empty, no SIM) |
| "Who called?" | `CallLog.Calls` | `calls.list` → `read_calls` | T0, private, taints | ✅ tested (log empty) |
| "Call Mom" | `ACTION_CALL` (`CALL_PHONE`) | `call.place` → `call` | **T3 outward, a person only**: card "Call Mom · <number>" | ✅ to the card, denied there (never dialled) |
| "What alarms do I have?" | No public API (Google Clock refuses external queries). Root reads a copy of Clock's `alarms.db` | `alarms.list` → `list_alarms` | T0 | ✅ tested |
| "Delete my 6:15 alarm" / "turn it off" | Root: stop Clock, delete or disable the row in `alarms.db`, then send Clock `TIME_SET` so it reschedules the rest | `alarms.delete` → `delete_alarm` | T3 to delete (reviewer may auto-approve), T2 to turn off | ✅ tested |
| Set alarm / timer | `AlarmClock` intents (existing) | `intent alarm/timer` → `set_alarm`, `set_timer` | T1 | existing |
| "Do not disturb on" / "only alarms" | `NotificationManager.setInterruptionFilter` (policy access: `cmd notification allow_dnd`) | `device.set {dnd}` → `phone_settings` | T1, audited | ✅ tested |
| Zen rules ("DND every night 10–7") | `NotificationManager.addAutomaticZenRule` | – | T2 | not built (an app can only see its own rules) |
| Flashlight | `CameraManager.setTorchMode` | `device.set {torch}` | T1 | ✅ tested |
| Volume / ringer | `AudioManager.setStreamVolume` (media, ring, alarm in %), `setRingerMode` | `device.set {volume, ring_volume, alarm_volume, ringer}` | T1 | ✅ tested |
| Brightness | `Settings.System.SCREEN_BRIGHTNESS(_MODE)` (`WRITE_SETTINGS` appop) | `device.set {brightness: 0-100 \| -1 auto}` | T1 | ✅ tested |
| Wi-Fi / Bluetooth / airplane | Root `svc wifi`, `svc bluetooth`, `cmd connectivity airplane-mode` (no public API since Android 10) | `device.set {wifi, bluetooth, airplane}` | T1, audited. Wi-Fi off or airplane on cuts a cloud brain off mid-task (the local model keeps working) | ✅ Bluetooth tested; Wi-Fi/airplane built, not toggled (adb and the other agents use the network) |
| "Battery?", "what's playing", next alarm, DND state | `BatteryManager`, `AudioManager`, `NotificationManager`, `AlarmManager.getNextAlarmClock`, `MediaSessionManager` | `device.status` → `phone_status` | T0 | ✅ tested |
| Screen time | `UsageStatsManager` (`GET_USAGE_STATS` appop) | `device.status {screen_time:true}` | T0 | ✅ tested |
| Play / pause / next, now playing | `MediaSessionManager.getActiveSessions` (via our notification listener) → `TransportControls` | `media.control` → `media` | T1 | ⚠️ built; sessions found and commands sent, but neither music app played on the test phone (Spotify: empty session, YouTube Music: stopped), so `now_playing` stayed null |
| "Play X on Spotify" | `MEDIA_PLAY_FROM_SEARCH` to the app (autoplays) | `intent play` → `play_music` (existing), then `media status` confirms | T2 | existing; confirm via `media` |
| "Navigate to Costco" | `google.navigation:q=…&mode=` | `intent navigate` → `navigate` | T2 (the place is draft text) | ✅ tested. Note: Prompt Guard scored Maps' own safety banner 0.77 (false positive), which flags the task for 10 min |
| "Open nytimes.com" | `ACTION_VIEW` http(s) in Chrome | `intent url` → `open_url` | T2. **Rules:** http/https only, no `user@`, ≤ 2000 chars, no P3 secret in it. **A person must approve (T3 card with the full URL)** when the URL or its host appears in text the task read (screen, notification, SMS), or the task is flagged. Analogy: you can open a link you typed yourself, but a link someone slipped you needs a second look | ✅ rules tested (`javascript:` and `user@` refused; a link in a flagged task → person card) |
| "Email Dana that…" | `ACTION_SENDTO mailto:` with subject/body → Gmail compose | `intent email` → `email_draft` | T2, draft only (Gmail is a private app, so in an untrusted+flagged task a person approves the draft). The Send tap is a risky T3 tap like any other | ✅ card path tested |
| Share | `ACTION_SEND` chooser | – | would be T3 | not built (the target app is chosen in a system chooser, so the destination can't be put on a card) |
| Clipboard | `ClipboardManager` | – | – | not built: Android 10+ lets only the focused app or the keyboard read it (our keyboard could; later) |
| Location | `LocationManager` network fix, coarse only | – | T0, private; would never go into an outward action without a person | not built (no demo need; privacy first) |
| Screenshot | root `screencap` | – | – | not built: the model reads the screen as text, and an image of a private screen is a leak risk |
| Reminders / notes | Google Keep and Tasks have no public provider | – | – | use `schedule_task` ("remind me…") or `add_event` |

How the pieces fit:
- `android/src/local/agentos/Api.java`: the API hands (no policy). `Api.SPECS` has scope, tier, private and taint flags per method.
- `Broker.act()`: the API methods take the same path as taps (lease → tier → card → audit). API calls skip the screen re-read, so the result is the data (`{ok, event}`, `{items}`, `{status}`).
- `agentd/tools.js`: one model tool per method. `tools.js` runs third-party `items[].text` through agentd's guard too. `prompt.js` tells the model to prefer these tools. `providers/local.js`: the router got `add_event` (now API, no Save tap), `phone_setting`, `media`, `phone_status`, `list_alarms` and `navigate`.

Permissions (`install.sh`): `pm grant` calendar, contacts, SMS, call log and phone; `appops` WRITE_SETTINGS and GET_USAGE_STATS; `cmd notification allow_dnd`.
