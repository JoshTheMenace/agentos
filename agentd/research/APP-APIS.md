# App reads without opening apps

## Live conversation feed (2026-10-28)

Prefer `notifications_conversations {pkg?, n?, since?}`, then `notifications_conversation {conversation_id, n?}` over opening an app or using its stale local inbox cache. `notifications_changes {since, pkg?, n?}` reports newly observed messages and posted/removed events, oldest first. All times are epoch milliseconds; `since` is exclusive. Continue with `next_since`, including when a page is full. An older message delivered now appears because changes use observation time, not the app's message timestamp. `gap:true` means restart, disconnect or bounded update retention lost coverage; re-list conversations. `fresh_as_of`, `connected_at`, `last_disconnect_at`, `history_since`, `cursor` and `listener` expose freshness and coverage. A disconnected listener has `fresh_as_of:null`.

`NotificationFeed.java` keeps 120 conversations, 50 deduplicated messages each, and 240 changes in memory. Android `MessagingStyle` current **and historic** messages retain sender, original time and observation time. IDs use package + Android user + shortcut ID, or the notification key when no shortcut is published. Same-title chats are never merged by name. Removing a notification retains observed messages, records the removal reason and disables its actions. Reconnecting seeds active notifications, but cannot recover messages that arrived and disappeared while disconnected. This does not force any app to refresh, establish inbox unread counts, or recover suppressed/hidden previews. Existing app-specific summary/cache tools remain available with their original limitations.

`notifications_reply {key,text}` uses the notification's own free-form RemoteInput; `notifications_mark_read {key}` uses only `SEMANTIC_ACTION_MARK_AS_READ`, when `can_mark_read:true`. Both are broker T3 outward actions with existing approval policy, audit entries (including notification key/conversation ID), and the native island/card. Mark-as-read may send read receipts. A successful PendingIntent dispatch means the app accepted the action, **not** confirmed server delivery. Actions are refused if removed/disconnected, and checked again after approval against the exact captured notification record. An update during approval requires a fresh read/retry. No dismiss-as-read guess, private API, token extraction, standing grant or approval bypass was added. All message text stays untrusted and passes the existing guards; codes from older MessagingStyle messages also enter the broker's P3 protection.

Supported Android references: [recover a notification's public builder/style](https://developer.android.com/reference/android/app/Notification.Builder#recoverBuilder(android.content.Context,%20android.app.Notification)), [notification actions](https://developer.android.com/reference/android/app/Notification.Action), [RemoteInput](https://developer.android.com/reference/android/app/RemoteInput), [listener callbacks](https://developer.android.com/reference/android/service/notification/NotificationListenerService).

### Setup and test fixture

The founder only needs AgentOS notification access enabled (Settings → Notification access → AgentOS), and notifications/message previews enabled for each source app. The listener is already enabled on the test phone. No account token is required. Some apps or channels do not publish reply/read actions, and previews can be suppressed by app settings or Android.

Pigeon adds an offline, silent test-notification receiver. It accepts only conversation names starting `AgentOS test`; fixture history is separate from its demo conversations. Replies only update this local fixture history. Build with `demo-apps/build.sh pigeon`, install through `scripts/withphone`, and grant Pigeon's notification permission. Example (all mutations under the device lock):

```sh
scripts/withphone adb shell am broadcast --include-stopped-packages -n local.agentos.pigeon/.TestNotifications -a local.agentos.pigeon.TEST_POST --es conversation 'AgentOS test notifications' --es sender Alice --es text 'Pigeon test message'
scripts/withphone adb shell am broadcast --include-stopped-packages -n local.agentos.pigeon/.TestNotifications -a local.agentos.pigeon.TEST_CLEAR --es conversation 'AgentOS test notifications'
scripts/withphone python3 android/notification-feed-test.py
```

The test checks history, deduplication, cursor paging, removal invalidation, broker audit and the existing reviewer path for Pigeon reply/read actions. It never taps/confirms a card or enables auto-reply; if the reviewer leaves an action to the owner, the test denies its own pending request and reports the unexecuted action. Real-app tests only list notification conversations; no reply/read action is invoked on a real app. Clear only the exact test conversation afterwards.

Phone validation: four Pigeon messages from two senders survived MessagingStyle historic/current merging without duplicates; one-event cursor pages lost no updates; an older message's test code was blocked by P3; removal retained history and refused both actions. Pigeon's actual RemoteInput reply returned as `from_me:true`, approved by the existing reviewer and recorded in the intact audit chain. Read RPC medians were 20 ms (conversations), 19.5 ms (messages) and 21 ms (changes); adb broadcast → observed conversation took 0.73–1.19 s. The complete model/reviewer reply turn took 32.0 s, separate from native dispatch time. LinkedIn, Slack, Instagram, Discord, Gmail and Messages had zero active message conversations during the read-only check; this does not establish inbox emptiness. No real-app actions, auto-reply grants, API credentials, calendar, firewall, vault or plugin-setting changes were used.

### Optional official APIs (documentation only; no adapter required or configured)

For fuller, fresh Slack history, the founder can create a Slack app in the intended workspace, open **OAuth & Permissions**, and request only needed **user-token read scopes**: `channels:history`, `groups:history`, `im:history`, `mpim:history`; add the corresponding `*:read` scopes for conversation discovery and `users:read` for names. Install/authorize as the founder, with workspace admin approval if required. For a distributed OAuth flow, configure a redirect URL, use state validation, request user scopes with `user_scope`, exchange the code server-side, and retain the resulting user grant privately. Never take a token from the installed Slack app. These are optional preparation steps; the notification tools do not consume a token.

A future adapter should enumerate permitted conversations, call `conversations.history` with pagination/`oldest`, use `conversations.replies` when permitted for threads, and honor `Retry-After` and the installation's current limits. User and bot grants have different coverage; a bot grant does not stand in for the founder's DMs. Do not add `chat:write` or invoke read-receipt/write methods. Keep the grant in broker-private storage or an agentd mode-0600 file, never prompts, tool arguments, logs or argv. Revoke it in Slack's app management when finished. [Slack OAuth setup](https://docs.slack.dev/authentication/installing-with-oauth/), [history access and pagination](https://docs.slack.dev/reference/methods/conversations.history/), [thread replies](https://docs.slack.dev/reference/methods/conversations.replies/).

For Gmail, create a Google Cloud project, enable Gmail API, configure OAuth consent and an appropriate OAuth client, add the founder as a test user where needed, and authorize only `gmail.readonly`; a future adapter can list/get messages and use history or watch for updates. Do not request send/modify scopes. [Gmail scopes](https://developers.google.com/workspace/gmail/api/auth/scopes), [OAuth setup](https://developers.google.com/workspace/guides/create-credentials). LinkedIn's open permissions do not include a general personal inbox; use notifications unless approved partner access exists. Instagram's official messaging route requires an eligible professional account and Meta authorization, not an arbitrary personal inbox. Discord bots likewise only cover granted bot-accessible conversations. See the official API table below; native app login never authorizes an API adapter.

## Previous app-read investigation (2026-10-27)

2026-10-27, Moto G 5G 2023 / Android 13, serial ZY22J3TK8S. Implemented in `android/src/local/agentos/AppReads.java` and `agentd/app-reads.js`. All inventory, deployment and device tests used `scripts/withphone`. No commits.

## What ships

Nine app plugins, ten new read tools. Each app can be switched off independently in Command center → Plugins; agentd hides/refuses its tools and the broker checks the method's plugin again. UI driving remains available. The existing `email_draft` now belongs to Gmail, including the broker's `intent:email` gate. No new outward tool or credential reader was added.

| App | Tool / capability | Method | Works on this phone? | Needs key? |
|---|---|---|---|---|
| LinkedIn | `linkedin_notifications`, delivered updates | (a) notification listener | Installed; read works, 0 retained notifications at test | No |
| LinkedIn | `linkedin_unread`, latest message per unread conversation | (b) root copy of `databases/messenger-sdk` + WAL, read-only SQLite | **9 unread conversations**, including 1 sponsored; source has 20 conversations / 104 messages | No |
| X | `x_mentions`, delivered notifications | (a) listener, `com.twitter.android` | **Not installed**; returns `installed:false` and no items | No; owner must install/sign in |
| Slack | `slack_unread`, message/mention previews | (a) listener, `com.Slack` | Installed; read works, 0 retained notifications | No |
| Instagram | `instagram_dms_summary`, message previews ranked first | (a) listener, `com.instagram.android` | Installed; read works, 0 retained notifications | No |
| Discord | `discord_mentions`, message/mention previews | (a) listener, `com.discord` | Installed; read works, 0 retained notifications | No |
| WhatsApp | `whatsapp_unread`, message previews | (a) listener, `com.whatsapp` | **Not installed**; returns `installed:false` and no items | No; owner must install/sign in |
| Gmail | `gmail_important`, previews ranked for attention | (a) listener, `com.google.android.gm` | Installed; read works, 0 retained notifications | No |
| Spotify | `spotify_status`, current track and delivered updates | (a) listener + Android MediaSession | Installed; APIs readable, no active session at test | No |
| YouTube | `youtube_updates`, delivered updates and current media | (a) listener + Android MediaSession | Installed; APIs readable, no active session at test | No |

Empty notification results **do not mean an empty inbox**. Notification tools cannot establish true unread totals, Slack workspace coverage, Gmail IMPORTANT labels, X's complete mentions timeline, or Instagram's whole DM inbox. Descriptions, plugin prompts and broker responses state these limits. Spotify/YouTube return metadata only when their own app publishes a media session; playback was not started for testing. These native interfaces do not need app-specific OAuth keys. [Android notification listener](https://developer.android.com/reference/android/service/notification/NotificationListenerService), [Android media sessions](https://developer.android.com/reference/android/media/session/MediaSessionManager).

Results contain at most 20 previews (default 8), at most 600 characters each, epoch timestamps, source/method, `trusted:false`, a numeric rank, `worth_looking_at` and a short reason. `since` defaults to all cached unread for `linkedin_unread`, and seven days for notification tools; an explicit cutoff narrows either. Ranking favors message previews, cached unread flags and time-sensitive words; discounts old/removed notifications and promotions; explicit LinkedIn sponsored messages rank zero. It is a heuristic, not model judgment. No URLs, profile blobs, tracking metadata, cookies or session credentials are returned.

## The ladder, cheapest first

### (a) Delivered notification history

Use `Notifs` before touching app storage or the network. It retains the latest notification per Android notification key, including removed notifications, in a bounded 240-record ring. Per-app/time filtering happens **before** limiting results. The existing core notification tool still caps its requests at 40. The ring resets on broker restart and seeds currently active notifications; response fields expose listener status, history start and capacity. This is not Android's full historical notification archive. Group summaries, ongoing notifications and system noise remain excluded by the existing parser. [Listener callbacks and active-notification access](https://developer.android.com/reference/android/service/notification/NotificationListenerService).

### (b) Local databases/cache through root

| App | Capability / observed local data | Method | Works on this phone? | Needs key? |
|---|---|---|---|---|
| LinkedIn | Unread conversations and text | `messenger-sdk`: `ConversationsData.entityData` JSON (`read`, `unreadCount`), latest `MessagesData.entityData.body.text` and actor name | **Built and tested**. Only the app's message cache is read; drafts/send queue, sync tokens, receipts and settings are not queried | No |
| Slack | Account/telemetry/log-sync/exoplayer DBs; no message DB found | Database directory inventory only; no account blobs or tokens queried | No usable message cache found; use notifications | No |
| Instagram | Only `plog_config_store` in database directory | Inventory only | No DM cache found; use notifications | No |
| Discord | Telemetry/datatransport/exoplayer DBs | Inventory only | No message cache found; use notifications | No |
| Gmail | `bigTopDataDB.*`: 181 items / 205 messages, protobuf blobs; legacy `EmailProvider.Message` empty | Copied database schema/count inspection only | Data exists, **decoder not built**. Notification-room tables empty. Use notifications now; official API next | No for cache; OAuth for API |
| Spotify | Event sender, measurement, CES, messages and offline DBs | File inventory; previous memory-source investigation found no useful local listening history | History reader not built; use MediaSession | No |
| YouTube | Entity stores/offline DB; notification `threads` table empty | Notification schema/count inspection | No decoded history reader; use notifications/MediaSession | No |
| X / WhatsApp | 11.0 / 8.8 ms | Package inventory | Not available | Owner login first |

The LinkedIn reader copies the fixed main DB and its WAL into the broker's private cache, opens **only the copy** with `OPEN_READONLY`, and deletes it in `finally`. WAL contains committed data that may not yet be in the main file. Source files are never opened with SQLite, checkpointed, edited, stopped or unlocked. An unavailable/changed schema falls back to notifications with `cache:unavailable`; partial cache rows are discarded. This best-effort copy can fail during an app write, so results explicitly remain cached/incomplete. [SQLite WAL behavior and read-only access](https://www.sqlite.org/wal.html).

### (c) The app's own headless refresh

Inventory only. `dumpsys jobscheduler` showed these candidates; IDs are a point-in-time observation and must be rediscovered, not hard-coded into a tool:

| App | Capability | Method / observation | Works on this phone? | Needs key? |
|---|---|---|---|---|
| LinkedIn | Background work | WorkManager `SystemJobService`; IDs include `-2147483648`, `-2147482816`, `-2147482812`, `-2147482808`, `-2147482798` | Jobs exist; **refresh not executed or proven** | Existing app login |
| Slack | Background work | WorkManager job `3823` | Job exists; refresh not executed/proven | Existing app login |
| Instagram | Background work | WorkManager job `224` | Job exists; refresh not executed/proven | Existing app login |
| Gmail | Account sync / background work | `gmail-ls` owned by SyncManager (`android`, jobs `100020`, `100038`); Gmail also has WorkManager jobs | Jobs exist; no forced sync | Existing Google login |
| YouTube | Background work | Multiple WorkManager jobs, e.g. `1195`, `1362` | Jobs exist; refresh not executed/proven | Existing Google login |
| Spotify / Discord | Background work | No package-attributed job observed in this snapshot | No verified candidate | Existing app login |
| X / WhatsApp | 11.0 / 8.8 ms | Not installed | Unavailable | Install/login |

Investigation command: `scripts/withphone adb -s ZY22J3TK8S shell "su -c 'dumpsys jobscheduler'"`. For a later **verified read-only worker**, the shell form is `cmd jobscheduler run -f -u 0 PACKAGE JOB_ID`; run it under `scripts/withphone`, wait for the worker, then re-read (a)/(b). A generic WorkManager job can drain sends/reactions/read-state uploads, so merely finding a job is insufficient evidence that forcing it is read-only. This task deliberately did not run jobs, refresh broadcasts, or brief off-screen activities. No generic refresh tool ships. WorkManager's diagnostics broadcast is documented for debug builds, not a universal production refresh broadcast. [Android WorkManager diagnostics](https://developer.android.com/develop/background-work/background-tasks/testing/persistent/debug), [Android 13 shell-command implementation](https://cs.android.com/android/platform/superproject/+/android-13.0.0_r1:frameworks/base/apex/jobscheduler/service/java/com/android/server/job/JobSchedulerShellCommand.java).

### (d) Official APIs, owner-authorized phone-only tokens

Research only; **no cloud adapter, OAuth setup, token extraction or API request ships in this change**. Native reads above work now without keys. These are the next steps when complete/fresh cloud coverage matters:

| App | Capability | Official method | Works on this phone? | Needs key? |
|---|---|---|---|---|
| Gmail | Real important unread mail, subjects/snippets | `users.messages.list` (`q=is:unread is:important`) → bounded `messages.get` calls | Not configured/tested; strongest next adapter | Google OAuth client + owner `gmail.readonly` grant |
| Slack | Channel/DM history, workspace search | Web API `conversations.history` / `search.messages`; user-approved scopes and channel membership determine access | Not configured/tested; notifications now | Slack OAuth app/user grant; workspace may restrict installs |
| Spotify | Recent listening history | `GET /v1/me/player/recently-played` | Not configured/tested; native now-playing now | Spotify app + `user-read-recently-played` grant; check current dev-mode eligibility |
| YouTube | Channel activity/subscriptions | Data API `activities.list`, subscriptions endpoints; not a notification-center or watch-history API | Not configured/tested | API key for public data; Google OAuth for personal data |
| X | Complete mentions timeline | `GET /2/users/{id}/mentions` | Not configured; app absent | X developer access + appropriately authorized token |
| LinkedIn | General inbox/notification APIs | Open permissions cover identity and posting; no general personal inbox permission advertised there | No verified general inbox adapter; local cache now | Restricted partner access where applicable |
| Instagram | Professional-account messaging | Instagram Messaging API | Not configured; not a general personal-account inbox API | Eligible professional account + Meta OAuth permissions |
| WhatsApp | Business messaging | WhatsApp Cloud API | Not configured; does not expose the owner's personal phone inbox | Meta business portfolio/WABA/business number + token |
| Discord | Bot-accessible channel messages | Bot/OAuth APIs within granted access | Not configured; not a substitute for the owner's entire personal DM inbox | Bot install/grants, potentially message-content access |

Sources: [Gmail list/query/scopes](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/list), [Gmail message retrieval](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/get), [Slack history and rate limits](https://docs.slack.dev/reference/methods/conversations.history/), [Spotify recently played](https://developer.spotify.com/documentation/web-api/reference/get-recently-played), [YouTube activities](https://developers.google.com/youtube/data/reference/rest/v3/activities/list), [X mentions](https://docs.x.com/x-api/users/get-mentions), [LinkedIn open permissions](https://learn.microsoft.com/en-us/linkedin/shared/authentication/getting-access), [Meta's Instagram documentation collection](https://www.postman.com/meta/instagram/documentation/6yqw8pt/instagram-api?entity=request-23987686-3fe78620-2258-44b6-893f-42d76c7200d7), [Meta's WhatsApp Cloud API collection](https://www.postman.com/meta/whatsapp-business-platform/documentation/wlk6lh4/whatsapp-cloud-api?entity=request-13382743-d0e227d1-e520-4b12-a8f6-32657a52d26e), [Discord OAuth2](https://docs.discord.com/developers/topics/oauth2). Meta's developer-site pages returned HTTP 429; the linked Meta-owned Postman collections supplied the account requirements.

Future token contract: OAuth finishes on the phone; persist grants only in broker-private storage or agentd's mode-0600 state directory. Tokens never appear in tool parameters/results, model context, stdout, audit, argv or network-error excerpts. Endpoint hosts/paths/methods are allowlisted; read adapters use GET only, enforce timeouts/rate limits and return selected preview fields. Do not infer permission from the owner being logged into the native app. Do not add send/write scopes to a read adapter.

### (e) Intents/deep links/share sheets

| App | Capability | Method | Works on this phone? | Needs key? |
|---|---|---|---|---|
| Gmail | Prefilled email | Existing broker `intent:email` / `email_draft` | Already implemented; now under Gmail toggle; **not invoked in these tests** | No |
| Slack, LinkedIn, Instagram, Discord, WhatsApp, X | Prefill share/draft where app supports it | Android `ACTION_SEND` with explicit package, or app-supported compose deep link | Research candidate only; app-specific support not verified here | No; app login |
| Spotify / YouTube | Search/play/open content | Existing `play_music`, `media`, approved `open_url` path | Existing tools; not exercised by this read-only task | No; native app |

`ACTION_SEND` hands text to another app; it does not guarantee that every app treats the intent as a safe compose-only draft. Verify each target before registering it. No new share/send path was added. Existing `notifications_reply` remains behind the broker's exact-action approval flow; this task never invoked it. [Android sharing intents](https://developer.android.com/develop/ui/compose/sharing/send).

### (f) Private APIs using native sessions

Last resort, **not implemented or probed** for any app. No cookie, OAuth grant, native session or credential values were retrieved; Gmail's empty legacy EmailProvider was inspected for schema/counts only. Session-backed web APIs can violate service terms, break without notice, or behave differently from read-only UI. Discord explicitly forbids automating normal user accounts outside its approved OAuth/bot API. LinkedIn's API terms also constrain usage. Do not add these for tomorrow's demo. If later authorized for another service, keep sessions entirely inside the phone's broker/agentd, expose only allowlisted read capabilities, strip credentials and signed URLs from errors/results, and independently verify no read receipts or queued writes occur. [Discord self-bot policy](https://support.discord.com/hc/en-us/articles/115002192352-Automated-User-Accounts-Self-Bots), [LinkedIn API terms](https://www.linkedin.com/legal/l/api-terms-of-use).

## Guard, code path and tests

`agentd/app-reads.js` defines each app's tool schema and plugin entry in one table. `plugins.js` imports those entries; `tools.js` registers their fixed broker methods. No model-selected package, file path, SQL, intent or shell command is accepted. Reads require no task lease and never wake/unlock/open an app.

The broker checks `Plugins.off` first, calls the fixed background reader, then reuses `Broker.absorb` to classify untrusted preview text and taint an existing lease. Audit records include method, package and item count, not message bodies. agentd sends all app results through **`guardMemory`**, retaining `trusted:false`/source labels. `guardMemory` now also sets turn-level untrusted and flagged state, so a read **before** `task.start` cannot lose the guard's taint when the next action creates a lease. Sender/conversation text is combined into the guarded `text` field, rather than leaked through unguarded display fields. Replies still need the existing broker gate.

Verification:

- `node agentd/app-reads-test.js`: real `runTool` path with a mock broker; nine plugins/ten tools, routing, plugin deferral/refusal, no UI/lease calls, injection withholding and sticky taint. No phone/model/network API credentials.
- `android/tests/AppReadsTest.java`, separate on-phone `app_process`: synthetic in-process notifications test package filtering before limit, timestamp cutoff, missing app isolation, unchanged core limit, message-over-ad ranking, fixed method allowlist, broker switch mappings, Gmail draft gate and continued `open_app` availability. No notification is posted; the running broker's settings/data are untouched.
- Android javac/D8 build passed after another agent finished its in-progress ModMask fix. No unrelated edit was reverted.
- `scripts/withphone python3 agentd/bench/app-reads-phone.py`: installed-app checks, real LinkedIn cache reads, since/limit handling and inclusion of older unread by default, foreground stability and before/after source-file hashes. Raw **metadata only**: `agentd/bench/results/app-reads-phone.json`.
- `node bench/app-reads-live.js` on the phone: all ten tools through the real `runTool` path as Termux uid 10401 (metadata in `agentd/bench/results/app-reads-live.jsonl`); live agentd advertises all nine app plugins enabled. An adb-created nested `su` shell could not reopen the broker token, so the harness's `--token-stdin` option receives it on an already-open phone-local descriptor; no token is copied to a file, command argument, model or log. The supervised agentd keeps its existing startup token path.
- Audit verification: chain OK, 3424 lines, eight previously marked breaks and no new break. No owner toggle was changed; native mapping/refusal tests ran in a separate process.

The code-golf pass kept absent-app and empty-history results, package/time filtering, limits, cache-failure fallback, sponsored ranking, both toggle checks and guard taint. A shared app table/reader replaces nine separate implementations; only LinkedIn cache decoding and the two media-session readers need special handling.

## Phone measurements

Three calls per method, HTTP JSON-RPC via adb forward, including broker Prompt Guard and audit. No model time. Notification results were empty after broker restart; this is an empty-path latency measurement, not evidence of live message delivery for every app.

| Method | Median | Result |
|---|---:|---|
| LinkedIn notifications | 11.5 ms | 0 retained |
| Slack unread previews | 9.5 ms | 0 retained |
| Instagram DM previews | 12.3 ms | 0 retained |
| Discord mentions | 10.3 ms | 0 retained |
| Gmail important previews | 9.4 ms | 0 retained |
| Spotify status | 10.5 ms | No active session / retained notifications |
| YouTube updates | 13.2 ms | No active session / retained notifications |
| X / WhatsApp | 11.0 / 8.8 ms | Correctly reports app absent |
| LinkedIn cached unread, `since:0,n:20` | **385.7 ms** | 9 conversations; 8 worth-looking-at, 1 sponsored rank zero |
| Existing `screen.read` baseline | 24.4 ms | Current foreground only |

LinkedIn cold call was 1402.5 ms; subsequent calls 356.3 / 385.7 ms. The earlier build measured 2354.8 / 510.6 / 486.2 ms; results vary with broker/model warmup. No source DB or WAL hash changed and foreground package stayed the same during the locked benchmark. No messages were sent, marked read, reacted to or refreshed. Temporary copies were deleted.

There is deliberately **no newly measured app-inbox UI comparison**: opening these real inboxes may mark messages read. Existing `agentd/bench/RESULTS.md` measured roughly 2 s per `open_app`, 0.7–1 s per tap, and ~2.5 s per model call. Those earlier measurements suggest the expected savings from one read versus several UI steps; they are not a paired per-app experiment. A safe future comparison needs disposable conversations/accounts.

## Founder/demo needs

Best immediate demo: ask **“Any LinkedIn messages worth looking at?”** → `linkedin_unread`, without opening LinkedIn. The default includes older cached unread threads. The existing owner session supplies real data; no new key or login required.

For another app's notification demo, the owner must already be signed in and have notification previews enabled, with a recent notification delivered since the listener started. We did not change any login/notification setting or generate a message to the owner. X and WhatsApp need installation/sign-in first. Full fresh Gmail/Slack/Spotify reads need the OAuth setup above and a subsequent adapter implementation; supplying a key alone does not enable an unbuilt adapter. Existing Gmail compose and native media controls remain available through their normal broker gates.
