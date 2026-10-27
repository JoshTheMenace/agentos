# 07 — Personal memory / knowledge graph (cheap version)

Research date: 2026-10-26. No phone commands were run. Checks marked **[verified]** were run on the Mac or read from a primary source during this research. **[unverified]** means from memory or secondary sources; check before relying on it.

## TL;DR

1. **Keep the memory on the phone, inside the existing `local.agentos.uilab` app, using Android's built-in SQLite.** It needs no new dependencies, so the Gradle-free `build.sh` keeps working. One catch: Android 13's built-in SQLite is **3.32 with FTS3/FTS4 but no FTS5, no load_extension, no JSON1 and no `RETURNING`**, so use **FTS4** and skip sqlite-vec on the phone. **[verified: AOSP android13 Android.bp, Android SQLite version table]**
2. **Data model:** `entity` (people, apps, places, artists), `alias` (phone numbers, emails, WhatsApp names), `event` (a call, text, notification, calendar item, song play), `relation` (derived edges with counts and first/last seen), plus `source`, an append-only **hash-chained `consent` ledger**, and an `audit` log. Revoking consent **hard-deletes** that source's rows. Android's SQLite is compiled with `SQLITE_SECURE_DELETE`, which overwrites deleted rows with zeros. **[verified]**
3. **Retrieval tools for the agent** go on the existing bearer-token `tools/call` server (port 7777): `memory_search`, `memory_entity`, `memory_neighbors`, `memory_timeline`, `memory_remember`, `memory_forget`, `consent_list`, `consent_revoke`, `memory_export`. Cloud models only ever get **redacted** text. Retrieved third-party text is labelled as untrusted to defend against prompt injection.
4. **Demo moment:** a live Cytoscape.js graph (v3.34.4). In WhatsApp, tap Export chat, share it to AgentOS, and a consent card appears. The graph grows. Then tap "Revoke SMS" and the SMS-derived edges disappear live.
5. **Utah facts for the pitch [verified, primary text]:**
   - The Digital Choice Act (HB 418, 2025; Laws 2025 ch. 468; now Utah Code 13-81) has been **in force since July 1, 2026**. Only its rulemaking section started earlier, on May 7, 2025.
   - The 2026 amendments (HB 408, ch. 316) take effect **July 1, 2027**.
   - The AI Policy Act (SB 149, 2024) is set to be **repealed July 1, 2027** under SB 226/SB 332 (2025).
   - HB 320 (2026, effective May 6, 2026) rewrote the Office of AI Policy's regulatory-agreement rules.

Estimate: **about 5 h core**, 4 h for the minimum cut, 7–8 h with all the export parsers and the stretch security items.

---

## 1. What exists already (reused, not rebuilt)

- `systemui-lab/app` has a debug APK `local.agentos.uilab`, built with plain `javac` + D8 + aapt2 (no Gradle). The target is API 33 and `allowBackup="false"` is already set.
- `Tools.java` runs a single-threaded loopback HTTP JSON-RPC server on **127.0.0.1:7777** with a bearer token (`tools/list`, `tools/call`). It already has `calendar_*` tools via `CalendarContract`.
- **Gotcha:** request bodies are capped at **65,536 bytes**, with a 5 s socket timeout. Bulk import over HTTP therefore has to be chunked. The better route is importing on the phone through the share sheet or a file picker (section 4).
- `tools-client.py` calls tools over `adb forward` without printing the token. Memory tools get added to the same `switch` in `Tools.call()`.

## 2. Storage decision

| Option | FTS | Vectors | Effort | Security story |
|---|---|---|---|---|
| **A. On-device, framework `SQLiteDatabase` (recommended)** | FTS4 (no `bm25()`; rank by recency or `matchinfo`) | none in v1; optional brute-force cosine in Java | lowest: no new deps | "Your memory never leaves the phone; models only see redacted snippets" |
| B. On-device, requery `sqlite-android` 3.49.0 AAR + sqlite-vec | FTS5 + bm25 | sqlite-vec 0.1.9 has an `android-aarch64` loadable build **[verified: GitHub release assets]** | +1.5–2 h: the AAR is built for Gradle, so it has to be unpacked by hand (`classes.jar` + `.so`) | same |
| C. Laptop, Python 3.12 (uv) or Node 24 | FTS5 + bm25 | sqlite-vec loads fine | low | weaker: personal data sits on the laptop |

Facts checked for the table:

- **Android 13 SQLite build flags** (AOSP `android13-release/dist/Android.bp`): `SQLITE_ENABLE_FTS3`, `SQLITE_ENABLE_FTS4`, `SQLITE_OMIT_LOAD_EXTENSION`, `SQLITE_SECURE_DELETE`, `SQLITE_DEFAULT_FILE_PERMISSIONS=0600`, `SQLITE_ENABLE_ICU`. There is no FTS5 or JSON1 flag.
  - Sources: https://android.googlesource.com/platform/external/sqlite/+/refs/heads/android13-release/dist/Android.bp
  - Android's version table lists API 33 = SQLite 3.32: https://developer.android.com/reference/android/database/sqlite/package-summary
  - **[unverified]** Motorola could ship a different build. Run `SELECT sqlite_version()` once in the app, and test `CREATE VIRTUAL TABLE t USING fts4(x, tokenize=unicode61)`.
  - Because the version is 3.32: `RETURNING` (needs 3.35) and built-in `json_extract` (built in from 3.38) are out. UPSERT (3.24) and window functions (3.25) are fine.
- **Mac gotchas [verified locally]:**
  - `/usr/bin/python3` is 3.9.6 with SQLite 3.51.0. It has FTS5 but **no `enable_load_extension`**, so sqlite-vec cannot load there.
  - The uv Python `~/.local/bin/python3.12` (SQLite 3.53.1) has FTS5 and `enable_load_extension`.
  - Node v24.14.0's `node:sqlite` (SQLite 3.51.2) has FTS5 and `loadExtension` when opened with `{allowExtension:true}`. It is marked experimental.

**Recommendation: Option A.** An analogy: FTS4 is the card catalogue, the entity graph is the "see also" links, and the LLM is a librarian who can rephrase the question and search again. That is enough for a demo with fewer than 50k rows. Vectors are like a smarter catalogue, but they bring native-library and embedding-model work that does not pay off in 36 hours.

If semantic search is wanted later:
- Store `float32` embeddings as BLOBs, computed on the laptop through a provider API, and run brute-force cosine in Java. 10k × 384 dims is about 4M multiply-adds, which should be well under 100 ms on this phone **[estimate]**.
- Or switch to Option B.

## 3. Schema (works on FTS4; for FTS5 change one line)

```sql
PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;
CREATE TABLE source(id INTEGER PRIMARY KEY, kind TEXT NOT NULL,      -- contacts|calllog|sms|notif|usage|calendar|wa_export|takeout|spotify|instagram|own_app|user_said
  label TEXT, origin TEXT, sha256 TEXT, created_at INTEGER NOT NULL);
CREATE TABLE consent(id INTEGER PRIMARY KEY, source_kind TEXT NOT NULL,
  purpose TEXT NOT NULL,            -- ingest | agent_read | cloud_redacted | local_raw
  action TEXT NOT NULL,             -- grant | revoke | pause
  actor TEXT NOT NULL,              -- 'user:ui' only; agent can never write here
  at INTEGER NOT NULL, expires_at INTEGER, note TEXT,
  prev_hash TEXT, hash TEXT NOT NULL);           -- sha256(prev_hash||canonical_row): tamper-evident
CREATE TABLE entity(id INTEGER PRIMARY KEY, type TEXT NOT NULL, name TEXT NOT NULL,
  attrs TEXT, sensitivity INTEGER DEFAULT 0, created_at INTEGER, UNIQUE(type,name));
CREATE TABLE alias(entity_id INTEGER NOT NULL REFERENCES entity ON DELETE CASCADE,
  kind TEXT NOT NULL, value TEXT NOT NULL, UNIQUE(kind,value));   -- e164|email|wa_name|ig_handle|pkg
CREATE TABLE event(id INTEGER PRIMARY KEY, source_id INTEGER NOT NULL REFERENCES source ON DELETE CASCADE,
  kind TEXT NOT NULL, ts INTEGER NOT NULL, ts_end INTEGER, title TEXT,
  body TEXT,                         -- raw (optionally Keystore-encrypted, see §7)
  body_redacted TEXT,                -- the ONLY text indexed and sent to cloud models
  sensitivity INTEGER DEFAULT 0, ext_id TEXT, UNIQUE(source_id, ext_id));
CREATE TABLE event_entity(event_id INTEGER REFERENCES event ON DELETE CASCADE,
  entity_id INTEGER REFERENCES entity ON DELETE CASCADE, role TEXT);      -- from|to|attendee|app|artist
CREATE TABLE relation(id INTEGER PRIMARY KEY, src INTEGER, dst INTEGER, type TEXT,   -- calls|texts|meets|uses_app|listens_to|knows
  weight REAL, first_seen INTEGER, last_seen INTEGER, source_kind TEXT,
  UNIQUE(src,dst,type,source_kind));
CREATE VIRTUAL TABLE event_fts USING fts4(title, body_redacted, tokenize=unicode61);  -- docid = event.id
CREATE TABLE audit(id INTEGER PRIMARY KEY, at INTEGER, actor TEXT, tool TEXT, args TEXT,
  n_results INTEGER, event_ids TEXT, prev_hash TEXT, hash TEXT);
```

- Write `event` and `event_fts` in the same transaction.
- **Relations are derived data.** Rebuild them with `GROUP BY` over `event_entity` after each import, for example "Me –texts→ Alex, weight = message count, last_seen = max ts". Because they are recomputed, revoking a source makes its edges disappear on the next rebuild. Graphiti/Zep use the same idea of edges with time bounds (bi-temporal edges, `invalid_at` instead of delete): https://arxiv.org/abs/2501.13956
- The tool names follow the MCP reference memory server (entities, relations, observations; `search_nodes`, `open_nodes`, `read_graph`), so agents will recognise them: https://github.com/modelcontextprotocol/servers/tree/main/src/memory
- **Revoke means `DELETE FROM source WHERE kind=?`.** The delete cascades to events. Then delete the matching FTS docids, garbage-collect orphan entities that have no aliases from other sources, rebuild relations, and append a `revoke` row to the ledger.
- **Honest caveat for judges:** secure delete zeroes the SQLite pages. Flash wear-levelling and old WAL frames can still leave copies on disk, which file-based encryption (FBE) mitigates. Run `PRAGMA wal_checkpoint(TRUNCATE)` after a revoke.

## 4. Ingestion sources (all consent-gated)

All of these are runtime or special permissions, and the user grants each one. For the demo, **grant them on camera through the real Settings UI**. That moment is the consent story.

The adb shortcuts below are for development only. I did **not** run them.

| Source | API | Permission / grant | Fields | Effort |
|---|---|---|---|---|
| Contacts | `ContactsContract.CommonDataKinds.Phone/Email.CONTENT_URI` | `READ_CONTACTS` (`pm grant local.agentos.uilab android.permission.READ_CONTACTS`) | CONTACT_ID, DISPLAY_NAME, NUMBER, NORMALIZED_NUMBER, Email.ADDRESS | 20 min |
| Call log | `CallLog.Calls.CONTENT_URI` | `READ_CALL_LOG` | NUMBER, CACHED_NAME, TYPE (1 in / 2 out / 3 missed), DATE, DURATION | 15 min |
| SMS | `Telephony.Sms.CONTENT_URI` (skip MMS) | `READ_SMS` | ADDRESS, BODY, DATE, TYPE (1 inbox / 2 sent), THREAD_ID | 15 min |
| Calendar | already in `Tools.java` | `READ_CALENDAR` (already declared) | events + attendees | 10 min |
| Usage stats | `UsageStatsManager.queryAndAggregateUsageStats` / `queryEvents` | special app-op: user toggles "Usage access", or `appops set local.agentos.uilab GET_USAGE_STATS allow`. Declare `android.permission.PACKAGE_USAGE_STATS` | package, totalTimeInForeground, ACTIVITY_RESUMED/PAUSED | 20 min |
| Notifications | `NotificationListenerService` | user toggles "Notification access", or `cmd notification allow_listener local.agentos.uilab/.MemNotif` **[verified: AOSP NotificationShellCmd usage text]** | `sbn.getPackageName()`, `getPostTime()`, `getKey()`, extras `EXTRA_TITLE`, `EXTRA_TEXT`, `EXTRA_BIG_TEXT`, `EXTRA_MESSAGES` | 30 min |
| WhatsApp export | share sheet → our `ACTION_SEND` activity (`text/plain`, `application/zip`) | the user's own export action **is** the consent | see parser below | 45 min |
| Google Takeout / Spotify / Instagram | parse on the laptop (Python) → normalized JSONL → push in ≤60 KB chunks via `memory_import`, or SAF `ACTION_OPEN_DOCUMENT` on the phone | user downloads their own data | see below | 30 min each |
| Our own apps | our test app exposes a `ContentProvider` guarded by a **signature-level** permission. Both APKs use the same debug keystore, so no root is needed | signature permission | app-defined | 30 min |

Notification listener manifest:

```xml
<service android:name=".MemNotif" android:exported="true"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
  <intent-filter><action android:name="android.service.notification.NotificationListenerService"/></intent-filter>
</service>
```

What to drop in `onNotificationPosted`:
- ongoing notifications and `FLAG_GROUP_SUMMARY`
- our own package
- a **package denylist**: banking apps, authenticator/2FA apps, password managers, `com.google.android.gms` sign-in
- anything matching OTP patterns

Store `sbn.getKey()` plus a text hash so updates to the same notification are de-duplicated.

**Android 13 "restricted settings":** sideloaded apps that were not installed through a session-based installer get the Notification-access and Accessibility toggles greyed out. The fix is App info → ⋮ → "Allow restricted settings", or `appops set local.agentos.uilab ACCESS_RESTRICTED_SETTINGS allow`.
- Sources: https://gitlab.com/fdroid/fdroidclient/-/issues/2680, https://9to5google.com/2022/05/06/android-13-sideloaded-accessibility/
- **[unverified]** whether `adb install` counts as restricted. It probably does not, because adb uses `pm install-create` sessions. Expect it either way.

**Entity resolution (cheap and deterministic):**
- Normalize phone numbers with `PhoneNumberUtils.formatNumberToE164(n, "US")` and look them up in the `alias` table, falling back to `ContactsContract.PhoneLookup`.
- Lowercase emails.
- Match WhatsApp and Instagram display names exactly against contact display names. If there is no match, create a new `person` with alias kind `wa_name`. Do not fuzzy-merge; show "possible duplicate" in the UI instead.

**WhatsApp `.txt` parser:**
- No official spec exists. The format varies by platform, locale and 12/24 h clock: https://dev.to/generalistp/building-whatsapp-chat-export-solutions-a-developers-guide-to-pdf-conversion-1jf5, https://pypi.org/project/whatsapp-chat-analyzer/
- Lines that do not match the patterns below are continuations of the previous message.
- Lines with no `Name:` part are system messages.
- `<Media omitted>` / `image omitted` mean media → `kind=media`.
- To tell dd/mm from mm/dd, scan the file for a first field greater than 12.
- Strip `‎` and treat ` ` (narrow no-break space before AM/PM) as a space. **[unverified but commonly seen]**

```java
Pattern AND = Pattern.compile("^(\\d{1,2}[/.]\\d{1,2}[/.]\\d{2,4}), (\\d{1,2}:\\d{2}(?:[ \\u202f]?[AaPp]\\.?[Mm]\\.?)?) - ([^:]+): (.*)$");
Pattern IOS = Pattern.compile("^\\u200e?\\[(\\d{1,2}[/.]\\d{1,2}[/.]\\d{2,4}), (\\d{1,2}:\\d{2}(?::\\d{2})?(?:[ \\u202f]?[AaPp][Mm])?)\\] ([^:]+): (.*)$");
```

**Exports parsed on the laptop.** All file names and fields here are **[unverified, from memory and secondary sources]**. Write each parser against a real export first.
- **Google Takeout:**
  - In Takeout, switch "My Activity" from HTML to **JSON** under "Multiple formats". Files are `My Activity/*/MyActivity.json` with `{header,title,titleUrl,time,products}`.
  - `Chrome/History.json` has `time_usec`.
  - Contacts export as `.vcf`; Calendar as `.ics`.
  - **Location Timeline moved on-device in late 2024.** Export it from phone Settings → Location → Location services → Timeline → Export, not from Takeout: https://support.google.com/maps/thread/334584618
- **Spotify:**
  - "Account data" gives `StreamingHistory_music_N.json` with `{endTime, artistName, trackName, msPlayed}` (about the last year).
  - "Extended streaming history" gives `Streaming_History_Audio_*.json` with `{ts, ms_played, master_metadata_track_name, master_metadata_album_artist_name, spotify_track_uri,…}`. It covers the whole account lifetime and can take **up to 30 days** to arrive. Request it today if wanted, or use a teammate's existing download: https://takeoutday.org/guides/download-spotify-data
  - Some extended records have null metadata: https://community.spotify.com/t5/Other-Podcasts-Partners-etc/Extended-Streaming-History-missing-track-metadata/td-p/6269152
- **Instagram:**
  - Accounts Center → Download your information → choose **JSON** (the default is HTML).
  - Messages are in `messages/inbox/<thread>/message_1.json` with `{participants[], messages[{sender_name,timestamp_ms,content}]}`. Followers and following lists are under `connections/`.
  - Meta exports often have mojibake (garbled accented characters and emoji). Fix it with `s.encode('latin-1').decode('utf-8')`, or use `ftfy.fix_encoding`: https://pypi.org/project/ftfy

**Ethics guardrails:**
- Call logs, SMS, notifications and chat exports contain **other people's** words. Only ingest the phone owner's own accounts.
- Keep `body` local. Cloud models get `body_redacted` only.
- Demo with seeded data: teammates' test contacts, texts between team phones, and a WhatsApp chat made for the demo.
- Do **not** read other apps' private databases via root. Our own apps are exposed through their own providers. The existing Duolingo-cache root broker is a grey area under the stated rule; I recommend keeping it out of the memory pitch.

## 5. Redaction (runs at ingest; result stored in `body_redacted`)

Order matters: run the most specific rules first.

1. **Package and category drops** (the denylist above).
2. **OTP:** `(?i)(code|otp|passcode|verification|pin)\D{0,20}(\d{4,8})` → `[OTP]`.
3. **Payment cards:** `\b(?:\d[ -]?){13,19}\b` plus a Luhn check → `[CARD]`.
4. **US SSN:** `\b\d{3}-\d{2}-\d{4}\b`.
5. **Email** → `[EMAIL]`.
6. **URLs with query strings** → host only.
7. **Phone numbers → pseudonyms.** Replace E.164 matches with `[P:<entity_id>]`, so the cloud model sees "[P:12] says meet at 6", not "+1 801…".

Agent action tools take an `entity_id`, so for example `sms_send(entity_id=12)` re-hydrates the real number only on the phone, after the permission system approves. **Pitch line: "the model never sees your contacts' phone numbers."**

**Optional, on the laptop:** Microsoft Presidio (2.2.365, Oct 2026) does NER-based PII detection in Python: https://github.com/microsoft/presidio. It is too heavy for the phone.

## 6. Agent tools (add to `Tools.list()` / `Tools.call()`)

| Tool | Args | Returns | Gate |
|---|---|---|---|
| `memory_search` | `q`, `kinds?`, `since?`, `until?`, `entity?`, `limit≤20` | events (redacted) with `{id, source, ts, who}` citations | needs `agent_read` consent for each source kind; logged |
| `memory_entity` | `name` or `id` | entity card: aliases (redacted), top relations, last 10 events | same |
| `memory_neighbors` | `id`, `depth≤2`, `types?` | `{nodes, edges}` (also feeds the graph view) | same |
| `memory_timeline` | `day` or `entity_id` | events in time order | same |
| `memory_remember` | `text`, `about?` | new event, `source.kind=user_said` | always allowed (the user said it) |
| `memory_forget` | `event_id` / `entity_id` | count deleted | **user confirmation** via the permission UI |
| `consent_list` | — | ledger + per-source counts | read-only |
| `consent_revoke` | `source_kind` | — | **only from the phone UI**; agent calls return "ask the user" |
| `memory_export` | `format=jsonl` | whole graph in an open format | user confirmation |

FTS4 query: `SELECT e.* FROM event_fts f JOIN event e ON e.id=f.docid WHERE event_fts MATCH ? AND e.kind IN (…) ORDER BY e.ts DESC LIMIT ?`. Ask the LLM to issue 2–3 rephrased queries; this is "agentic search" in place of vectors.

**Prompt-injection defence (important, because the pitch is about security):**
- SMS, notifications and chats are text written by strangers. Wrap results as `{"untrusted_content": …, "provenance": "sms from [P:12]"}`.
- The system prompt states that retrieved content is data, never instructions.
- The permission system must require confirmation for any send, tap or purchase that follows a memory read in the same turn.
- **Analogy:** a letter in your mailbox saying "the homeowner says give me the keys" is not the homeowner.

**Audit:** every tool call appends a hash-chained `audit` row. The island card shows something like "Agent read 3 texts from Mom (SMS consent #4) at 14:02."

## 7. Security extras (stretch, about 1 h each)

- **Encrypt raw `body` with an Android Keystore AES-GCM key.** FTS only ever indexes redacted text. On a rooted phone, root can read any app's database, so be honest that this raises the bar but is not absolute. Whether this Moto has StrongBox is **[unverified]**; assume a TEE-backed key.
- **Consent expiry:** a grant with `expires_at` (for example "notifications for 24 h") stops being honoured after that time.
- The ledger hash chain lets you show tamper-evidence live: edit a row with sqlite3, and `consent_verify` reports the break.
- Format inspiration for consent records: ISO/IEC TS 27560:2023 (consent record structure) and the Kantara Consent Receipt spec. **[unverified details; cite as inspiration only]**

## 8. Graph view for the demo

- **Cytoscape.js v3.34.4** (released 2026-10-15) uses the built-in `cose` layout, needs no plugins, and is one `cytoscape.min.js` file: https://github.com/cytoscape/cytoscape.js
- Put it in `app/assets/graph.html` and show it in a `WebView` island card, or open it in a laptop browser through the forwarded port. If WebView is used, add a token-free read endpoint that serves only redacted data, or pass the token via `evaluateJavascript`.
- `GET`/`tools/call memory_neighbors {id: me, depth: 2}` returns `{nodes:[{data:{id,label,type,source}}], edges:[{data:{source,target,type,weight}}]}`.
- Styling: colour nodes by type, set edge width from `log(weight)`, and colour each edge by its source kind. Edge colours make revocation visible.
- Clicking a node opens a side panel with recent redacted events and badges showing which consents back them.
- **Demo script (90 s):**
  1. Empty graph.
  2. Grant Contacts and Call log in Settings → people appear.
  3. WhatsApp → Export chat → share to AgentOS → consent card → new edges.
  4. Ask the assistant "when did Alex and I last talk about the trip?" → cited answer.
  5. Tap Revoke SMS → edges vanish, and the ledger shows the revoke row.
  6. `memory_export` → a portable JSONL file, matching the theme of Utah's portability law.
- Alternatives: sigma.js 4.0.3 (WebGL, better for large graphs), vis-network 10.1.3, force-graph 1.52.0. Cytoscape is the simplest choice for fewer than 2k nodes.

## 9. Recommended plan and fallback

**Plan (Option A, about 5 h, one person):**

| Step | h |
|---|---|
| `MemoryDb.java` (SQLiteOpenHelper, schema, insert/search/rebuild-relations, revoke) | 1.25 |
| Collectors: contacts, call log, SMS, calendar, usage (one `sync` tool) | 1.0 |
| `MemNotif` listener + redaction | 0.75 |
| WhatsApp share-target import + consent card | 0.75 |
| Tools wiring + audit + consent ledger | 0.5 |
| `graph.html` (Cytoscape) + WebView card | 0.75 |
| **Total** | **5.0** |

- **Minimum cut, about 4 h:** contacts, call log, SMS, WhatsApp import, search, graph, revoke.
- **Optional extras:**
  - laptop Python parsers for Spotify, Takeout and Instagram plus chunked `memory_import`: +1.5 h
  - Keystore encryption: +1 h
  - brute-force vectors: +1 h

**Fallback (Option C, about 3.5 h):** if Java work stalls, run the store and tools on the laptop.
- Use `~/.local/bin/python3.12` with `sqlite3`, FTS5 and `bm25()`. Optionally add sqlite-vec via `pip install sqlite-vec`; 0.1.9 is the latest stable release (2026-03-31, with 0.1.10 alphas after it).
- Phone collectors become thin tools that return JSON, which the laptop pulls over the existing adb-forwarded 7777.
- Serve `graph.html` with `python -m http.server`.
- The pitch becomes "local-first on your own hardware" instead of "on-phone".

## 10. Risks

- **The FTS4 tokenizer could be missing on Motorola's build** → fall back to `LIKE '%q%'` over `body_redacted`. That is fine below roughly 20k rows. Test this first (5 min).
- **The notification listener is unbound after an APK reinstall** → call `requestRebind`, or toggle access again. Restricted-settings dialog → appop above.
- **The 64 KB HTTP body cap** → chunk imports, or import on the phone through the share sheet or SAF.
- **Export formats drift** (WhatsApp locales, Spotify naming, Instagram encoding) → test each parser on a real file before demo day. Treat the field lists here as hints, not specs.
- **Extended Spotify history and Takeout take days**, so request them now or use teammates' existing downloads.
- **Third-party data and prompt injection** in ingested content → section 6 guardrails, plus seeded demo data.
- **Over-claiming legal compliance** → AgentOS is not a "social media service" under the Act. Position it as a user-side tool that aligns with the Act's goals, not "compliant with" it.

## 11. Utah law facts for the pitch (primary sources read)

**Utah Digital Choice Act: HB 418 (2025), "Data Sharing Amendments"**
- Sponsors: Rep. Doug Fiefia; Senate sponsor Sen. Michael K. McKell. Laws of Utah 2025, ch. 468.
- Enrolled text: https://le.utah.gov/Session/2025/bills/enrolled/HB0418.pdf
- **Effective dates:** "Except as provided in Subsection (2), this bill takes effect July 1, 2026". The rulemaking/open-protocol section (enacted as 13-75-301) took effect **May 7, 2025**.
- It was enacted as Title 13 ch. 75 and now appears as **Utah Code Title 13, Chapter 81** (https://le.utah.gov/xcode/Title13/Chapter81/C13-81_2026070120250507.pdf). The renumbering is my inference from the current Code and HB 408 citing "13-81 … as enacted by Laws of Utah 2025, Chapter 468".
- **Substance:**
  - Social-media services must provide personal data, **including the user's social graph**, in a portable, readily usable format when a consumer makes a request under the UCPA (13-81-201).
  - Social-media companies must offer a third-party-accessible **interoperability interface using an open protocol**. The protocol must be free of licence fees and patent restrictions. Sharing requires **user consent** through an "accessible, prominent, and persistent" consent method (13-81-202).
  - **Third parties** receiving data must safeguard it under their privacy notice and security practices.
  - Platforms do not have to share inferences or proprietary algorithms.
  - The Division of Consumer Protection may identify qualifying open protocols, which gives a rebuttable presumption of compliance. No published list of identified protocols was found as of this research **[searched, not found]**.
  - **Enforcement:** the Division of Consumer Protection; administrative fine up to **$2,500 per violation**; court civil penalty up to $2,500 per violation; up to **$5,000** for violating an order. The chapter text names no private right of action.
- HB 418 also amended the UCPA (13-61-201) to add a **right to correct**, effective July 1, 2026.

**HB 408 (2026), "Data Sharing Amendments"**
- Same sponsors. Laws of Utah 2026, ch. 316. **Takes effect July 1, 2027.**
- Enrolled text: https://le.utah.gov/Session/2026/bills/enrolled/HB0408.pdf
- Changes:
  - users may move a **user-selected portion** of their data
  - **continuous, real-time** sharing
  - portability requests answered **within five business days**
  - consent decisions implemented within five business days
  - users must consent **before their data is included in another user's social graph**
  - companies must publicly disclose which open protocol they use
  - safe harbor for temporary outages handled in good faith
  - removes the duty to offer "functionally equivalent" internal interfaces
  - nothing requires a company to *receive* data
- The current Code shows the 2025 text "Superseded 7/1/2027" and the 2026 text "Effective 7/1/2027".
- Note: a draft (HB0408S02) appeared to repeal provisions. The enrolled version amends rather than repeals.

**Utah Consumer Privacy Act (SB 227, 2022)**
- Effective **Dec 31, 2023**. Rights to access, delete, portability and opt-out.
- Applies to controllers with more than $25M revenue that meet consumer-volume thresholds. The exact and/or wording is **[unverified; secondary sources disagree]**.
- AG enforcement; no private right of action.
- Source: https://www.foley.com/insights/publications/2022/03/utah-fourth-state-pass-consumer-privacy-law/

**AI Policy Act (SB 149, 2024; Utah Code 13-72)**
- Effective **May 1, 2024**. Created the **Office of Artificial Intelligence Policy** in the Department of Commerce, the AI Learning Laboratory, and regulatory mitigation agreements.
- Source: https://en.wikipedia.org/wiki/Utah_Artificial_Intelligence_Policy_Act
- **Repeal date:** originally May 1, 2025. Extended to **July 1, 2027** by SB 226 and SB 332 (2025), both effective May 7, 2025: https://le.utah.gov/Session/2025/bills/enrolled/SB0332.pdf
- The current 63I-2-213 still reads "Title 13, Chapter 72, Artificial Intelligence Policy Act, is repealed July 1, 2027" **[verified on le.utah.gov]**. Any 2027 session extension is unknown.

**SB 226 (2025), AI Consumer Protection Amendments** (https://le.utah.gov/Session/2025/bills/enrolled/SB0226.pdf)
- A supplier using generative AI must disclose that it is AI **when the user clearly asks**.
- Regulated occupations must prominently disclose AI use in "high-risk" interactions: health, financial or biometric data, or personalized financial, legal, medical or mental-health advice.
- **Safe harbor** if the AI discloses at the outset and throughout.
- "The AI did it" is not a defense to consumer-protection violations.
- Penalties: $2,500 per violation; $5,000 for violating an order.
- **Design takeaway:** the assistant should say it is AI at the start of a voice session and in the UI. This is cheap and fits the safe harbor.

**HB 452 (2025)** regulates mental-health chatbots (13-72a), effective May 7, 2025: https://fpf.org/blog/chatbots-in-check-utahs-latest-ai-legislation/. Do not position AgentOS as a mental-health tool.

**HB 320 (2026), Office of AI Policy Amendments** (https://le.utah.gov/Session/2026/bills/enrolled/HB0320.pdf)
- Sponsors: Rep. Paul A. Cutler / Sen. Kirk A. Cullimore. **Effective May 6, 2026.**
- Adds **joint interpretation agreements** alongside regulatory mitigation agreements.
- Demonstration period of 12 months or less, with up to two 12-month extensions.
- The Office must audit participants. Agreements must specify scope, safeguards, consumer disclosures and reporting.
- The learning lab's purpose changes from encouraging AI "development" to "**responsible deployment**".
- The Office reports annually before Nov 30.
- A live example is the Doctronic prescription-refill RMA: https://commerce.utah.gov/ai/ai-faq
- **Pitch hook:** Utah has a formal sandbox route for exactly this kind of "agent that acts for you" product.

**HB 276 (2026), Digital Content Provenance Standards Act**
- Passed and signed March 2026.
- Effective date **[unverified: Jan 1, 2027, with capture-device duties possibly Jan 1, 2028]**: https://le.utah.gov/Session/2026/bills/enrolled/HB0276.pdf
- Mention only if the pitch touches AI-generated media.

**Suggested pitch line:** "Since July 1, 2026, Utah law says you have a right to take your social graph with you. AgentOS gives that data a safe home on your own phone, with a consent ledger you control and an AI that can only see what you've allowed, redacted."
