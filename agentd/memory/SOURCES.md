# Memory sources (ingestion side)

Everything here stays in `~/.agentos/memory.db` on the phone. Each source is a row in the consent ledger: granted by
default for the owner's own phone, revocable from the UI (`consent_revoke` hard-deletes its rows). Connectors live in
`connectors/<source>.js` and run from agentd (`connectors.start()` in server.js): once a minute it runs whatever is
due (each source has its own interval below), skips when the battery is under 15% and not charging, and never keeps
node awake. On demand: `node memory/cli.js sync [source] [--force]` or the WebSocket message `{type:"memory.sync"}`.
Incremental: each source keeps a watermark in `meta` (`wm:<source>`), reset when the source is re-granted.

**Deletes and edits (10-27 night).** Memory follows the phone, not just its additions. Calendar: every run replaces edited
events (`store.putEvent`: same ext_id, new time/title/attendees → one row, not two; unchanged rows keep their id) and
drops events the provider no longer has or flags `deleted=1` (`store.prune` against an id-only `content query`, ~0.3 s).
CalendarContract has no public last-modified column, so edits beyond the 14-day window land with the 6-hourly full read.
SMS, call log, photos: the same id diff, hourly (`pruneHourly`). Chrome: diff against the History copy's visit ids each run.
Contacts: a number/email no longer on any contact loses its contacts alias (the card goes if nothing else knows it); a
renamed contact renames its card unless two contacts share that number. Alarms: one row that follows the next alarm, or
none. A failed id query throws (it can never look like "everything was deleted"). agentd's own `add_event` /
`update_event` / `delete_event` / `delete_alarm` / `set_alarm` are mirrored at once (`memory.noteApi`, from tools.js).
Verified on the phone: the first forced calendar run removed 3 stale events; a test event inserted then deleted by id
appeared and disappeared; a repeat run changed nothing.

## Schema notes for Memory B (all additive, store.js)
- `SOURCES` gained `photos`, `usage`, `chrome`, `media`, `alarms`.
- `redact()` also replaces `password|passwd|passcode|pwd|pass <is|:|=> xxx` with `[PASSWORD]` (on top of OTP, card, SSN, email, phone, URL query).
- New event kinds: `media` (photos; was already used by WhatsApp media), `usage`, `web`, `listen`, `alarm`. New entity types: `album`, `site`, `artist`.
  New event_entity roles: `album`, `used`, `site`, `player`, `artist` (relation type falls through to the event kind).
- `meta` rows `wm:<source>` = `{grant, at, rows, added, ...}` (last sync state). No table or column changes.
- `ingest.js` calendar/calllog/sms accept `since` (ms) for incremental reads.

## Inventory

| Source | API or path | Interval | What we keep | Privacy notes |
|---|---|---|---|---|
| contacts | `content://com.android.contacts/data/phones`, `/data/emails` (ContactsContract) | 60 min | person entities; phone/email aliases (merge key across apps) | Numbers/emails are stored raw for matching; models see `[P:n]`/`[E:n]`. |
| calendar | `content://com.android.calendar/{calendars,events,attendees}` (CalendarContract) | 10 min (full re-read every 6 h, else the next 14 days) | event title, start/end, location (place entity), attendees (person by email) | Titles redacted (dial-ins, codes). The owner's own calendar address is not an attendee. |
| calllog | `content://call_log/calls` | 10 min, since the last stored call | type, time, duration, person by number | No audio, no voicemail text. |
| sms | `content://sms` | 10 min, since the last stored text (first run: newest 200) | body, time, direction, person by number | OTP/card/password redaction; `trusted:false`; MMS bodies not read. |
| notifications | `dumpsys notification --noredact` sweep + live feed (`context.js` → `memory.noteMessage`) | 10 min | app, title, text, time; messages link to the person and app | System, banking/2FA/wallet/password-manager packages dropped (ingest.js DENY); OTP-looking text dropped; `trusted:false`. |
| photos | `content://media/external/{images,video}/media` (MediaStore) | 10 min, by `date_added` | when a photo/video/screenshot was taken, album name (`album` entity) | Never pixels, never file names or paths. Location is not requested (MediaStore redacts it anyway on Android 10+). |
| usage | `dumpsys usagestats` "In-memory daily stats" (UsageStatsManager) | 30 min, row per (app, day) replaced each sync | minutes used and launches per app per day (`app` entity) | Apps < 1 min skipped; private apps (bank/pay/wallet/crypto/password/authenticator, system, AgentOS itself) skipped. |
| chrome | read-only copy of `/data/data/com.android.chrome/app_chrome/Default/History` → temp file → node:sqlite, deleted after | 10 min, by visit time | page title, URL (query string stripped in the redacted copy), host (`site` entity) | Rooted dev phone only. Chrome's files are never written. Private-looking hosts skipped. Incognito is never in History. Works from agentd (Magisk root); from a Termux shell (`cli.js sync chrome`, nested su) the read is denied, which is expected. |
| media | `dumpsys media_session` (MediaSession metadata) | 2 min, one row per track per 20-min slot | track, artist, player app (`artist`, `app` entities) | Players' ongoing notifications never enter the notification feed, so polling is the only signal; a 2-minute poll misses short tracks. |
| alarms | `settings get system next_alarm_formatted` (AlarmManager.getNextAlarmClock) | 10 min | the next alarm, as the lock screen shows it | The Clock app's own DB has no provider and was empty on this phone. |
| whatsapp, pigeon | manual imports (ingest.js); not scheduled | on demand | see ingest.js | Not granted by default. |

## Looked at and not built (10-27)
- Google Messages `bugle_db` (RCS): the copy had 0 messages on this phone, and SMS/MMS already come from the provider. Skip until there is data.
- Spotify: no local play history (only settings/cache); recently-played needs its Web API (cloud). The `media` poll covers "what am I listening to".
- Slack / Discord: no message databases on this phone (Slack: account/telemetry only; Discord: empty). Their notifications arrive through the feed.
- Clock alarms DB, Chrome Bookmarks file: absent on this phone.
- Never read: `/data/adb/agentos` (unlock PIN), the password vault, any package matching bank/pay/wallet/password/authenticator.

## Verified on the phone (10-27 evening, Moto G 5G 2023, agentd under the Magisk supervisor)
First full sync after the restart: 14.6 s for all ten sources (contacts 2.9 s, calendar 4.0 s, photos 2.9 s, the rest < 2 s each).
Scheduled runs afterwards: media every 2 min ≈ 0.1 s; the 10-min round ≈ 10 s, mostly `content query` process start-up.
DB after checkpoint: 373 KB (`memory.db`), WAL grows to ~1.5 MB between checkpoints.

| source | rows read | events stored | note |
|---|---|---|---|
| contacts | 103 | 0 (126 person entities, 137 aliases) | |
| calendar | 1000 | 1000 | newest 1000 before +90 d; 23 places |
| calllog | 0 | 0 | this test phone has no calls |
| sms | 0 | 0 | no SMS on this phone (Google Messages DB also empty) |
| notifications | 2 (+10 live messages) | 12 | |
| photos | 50 | 50 | 22 images + 28 videos, 7 albums |
| usage | 8 | 8 | apps ≥ 1 min today |
| chrome | 114 | 114 | 7 sites |
| media | 0 | 0 | nothing was playing; parser covered by fixtures. Play a track and it appears within 2 min |
| alarms | 1 | 1 | |

Sample queries (CLI, redacted view): "who did I text yesterday" → timeline 2026-10-26: 19 messages across 7 people and 1 group;
"what's on this week" → 4 calendar events; "today" → 8 app-usage rows, 83 web visits, 1 photo. Redacted bodies contain no
query strings or phone-shaped digits. Revoke chrome → 114 events deleted, ledger chain OK; re-grant → watermark reset, 114 re-read on the next round.

**Photos, deletes (10-27 late):** the photo connector now diffs against MediaStore's live ids on every run (two id-only queries,
~0.3 s), not hourly, so a shot deleted right after it was taken leaves memory by the next 10-minute tick. One stale test photo
(event 1343, `Photo · agentos-test`) was removed from the phone DB by hand that night.
