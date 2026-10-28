# Agent tools (what the AI can call)

Generated from agentd/tools.js + plugins.js on 2026-10-28. **core** = always loaded; plugins can be switched off in Command center › Plugins. Tier: read = looks only, act = changes something on the phone (the broker still gates every call: auto / tap / fingerprint).

## Core (always on) — 21

| Tool | Tier | What it does |
|---|---|---|
| `task_start` | read | Start this task's lease. |
| `screen_read` | read | Read the current screen: {pkg, activity, secure, nodes:[{id, role, text, desc, hint, bounds, actions, src, risk}]}. |
| `tap` | act | Tap a UI node by id. |
| `type` | act | Type text into an editable node (replaces its content). |
| `scroll` | act | Scroll a node (or the main list if no id). |
| `press` | act | Press a system key. |
| `open_app` | act | Launch an app by package name (see list_apps). |
| `list_apps` | read | List launchable apps {pkg, label}. |
| `set_alarm` | act | Set an alarm in Clock directly (24 h time). |
| `set_timer` | act | Start a countdown timer in Clock directly. |
| `web_search` | act | Open a Google search for the query in Chrome and return the results screen. |
| `act` | act | Do several steps in one call when the current screen already shows what to do, e.g. |
| `wait` | read | Wait for the UI to settle (max 5000 ms); returns the new screen. |
| `notifications_list` | read | Recent notifications, newest first: {key, app, pkg, conversation, sender, text, when, from_me, can_reply}, plus the foreground app. |
| `notifications_reply` | outward | Reply in a message thread straight from its notification (fast, no app opening). |
| `notifications_conversations` | read | List recent live message conversations, latest text, sender, timestamps, updated_at, conversation_id and available reply/mark-read keys. |
| `notifications_conversation` | read | Read a conversation’s recent messages, newest first, merged from Android MessagingStyle current and historic messages; keeps history after removal. |
| `notifications_changes` | read | What changed since time T: posted/removed notifications and newly observed messages, oldest first. |
| `notifications_mark_read` | outward | Use the app’s own notification mark-as-read action without opening it. |
| `memory_ask` | read | Ask the user's personal memory a question in plain words ("what did Sam want for his birthday?", "when am I free to see Mom this week?", "who was I talking to a |
| `catch_up` | read | What the user missed, in one fast local call: unanswered messages by person and app (live notification feed, most important first, with reply keys), the next 12 |

## Plugin: LinkedIn — 2

| Tool | Tier | What it does |
|---|---|---|
| `linkedin_notifications` | read | Delivered notifications; linkedin_unread also checks unread conversations in the local cache. |
| `linkedin_unread` | read | Unread LinkedIn conversations from a read-only local cache copy, plus delivered notifications. |

## Plugin: X — 1

| Tool | Tier | What it does |
|---|---|---|
| `x_mentions` | read | Delivered X notifications (possible mentions and DMs); not a complete mentions timeline. |

## Plugin: Slack — 1

| Tool | Tier | What it does |
|---|---|---|
| `slack_unread` | read | Delivered Slack messages and mentions; not a complete workspace unread count. |

## Plugin: Instagram — 1

| Tool | Tier | What it does |
|---|---|---|
| `instagram_dms_summary` | read | Delivered Instagram notifications, with messages ranked first; not a full DM inbox. |

## Plugin: Discord — 1

| Tool | Tier | What it does |
|---|---|---|
| `discord_mentions` | read | Delivered Discord messages and mentions; not a full server or DM history. |

## Plugin: WhatsApp — 1

| Tool | Tier | What it does |
|---|---|---|
| `whatsapp_unread` | read | Delivered WhatsApp message previews; not a complete unread count. |

## Plugin: Gmail — 2

| Tool | Tier | What it does |
|---|---|---|
| `gmail_important` | read | Delivered Gmail previews ranked for attention; not Gmail importance labels or a full inbox. |
| `email_draft` | act | Open a Gmail draft; nothing is sent until Send is tapped (approval). |

## Plugin: Spotify — 1

| Tool | Tier | What it does |
|---|---|---|
| `spotify_status` | read | Spotify now playing from its media session, plus delivered notifications; no listening history. |

## Plugin: YouTube — 1

| Tool | Tier | What it does |
|---|---|---|
| `youtube_updates` | read | Delivered YouTube updates and current media session; not subscription or watch history. |

## Plugin: Calendar — 4

| Tool | Tier | What it does |
|---|---|---|
| `add_event` | act | Add a calendar event directly (no UI); returns the saved event. |
| `find_events` | read | Calendar events from..to (default: the next 7 days), optional title search. |
| `update_event` | act | Change an event by id; the length stays unless minutes is given. |
| `delete_event` | outward | Delete an event by id (the phone asks first). |

## Plugin: Messages & calls — 5

| Tool | Tier | What it does |
|---|---|---|
| `find_contact` | read | Look up a contact by name or nickname: phones, emails. |
| `send_sms` | outward | Send an SMS. |
| `read_sms` | read | Recent SMS, newest first, optionally only from one contact. |
| `read_calls` | read | Recent calls (missed, incoming, outgoing). |
| `call` | outward | Place a phone call (the phone asks first). |

## Plugin: Alarms — 2

| Tool | Tier | What it does |
|---|---|---|
| `list_alarms` | read | Alarms in Clock: {items:[{id, time, days, label, enabled}]}. |
| `delete_alarm` | act | Delete an alarm by id from list_alarms; off:true only turns it off. |

## Plugin: Device settings — 2

| Tool | Tier | What it does |
|---|---|---|
| `phone_settings` | act | Change phone settings directly; give only what changes. |
| `phone_status` | read | Battery, DND, ringer, volumes, flashlight, brightness, Wi-Fi, Bluetooth, airplane, next alarm, now playing; screen_time adds today's app use. |

## Plugin: Media — 2

| Tool | Tier | What it does |
|---|---|---|
| `play_music` | act | Play music in an app (e.g. |
| `media` | act | Control what is playing; status = now playing. |

## Plugin: Maps & links — 2

| Tool | Tier | What it does |
|---|---|---|
| `navigate` | act | Start Google Maps navigation to a place. |
| `open_url` | act | Open an http(s) link in Chrome. |

## Plugin: Trackers & firewall — 7

| Tool | Tier | What it does |
|---|---|---|
| `firewall_list` | read | List per-app network rules. |
| `firewall_set` | outward | Allow or block network access for an app (optionally only some domains). |
| `firewall_clear` | outward | Remove every AgentOS firewall rule. |
| `trackers_scan` | read | Which tracker companies an app talks to. |
| `trackers_list` | read | Every app seen talking to trackers, and what is blocked: {apps:[{pkg, label, trackers}]}. |
| `trackers_block` | outward | Block an app's trackers (tracker-list domains only; the app keeps working). |
| `trackers_unblock` | outward | Undo trackers_block for an app (default: all its blocked trackers). |

## Plugin: Passwords — 2

| Tool | Tier | What it does |
|---|---|---|
| `vault_list` | read | Saved passwords: {entries:[{id, label, apps, username}]}. |
| `vault_fill` | act | Fill a saved password into a password field. |

## Plugin: Memory tools — 9

| Tool | Tier | What it does |
|---|---|---|
| `memory_search` | read | Full-text search over the user's personal memory (messages, calls, calendar, notes). |
| `memory_entity` | read | Card for one person/place/group: aliases (phone/email shown as [P:n]/[E:n]), strongest relations and last 10 events. |
| `memory_neighbors` | read | Graph around an entity (default: the user): {nodes, edges}. |
| `memory_timeline` | read | Events in time order for one day and/or one entity. |
| `memory_remember` | act | Save a note the user told you to remember, optionally about a person/place. |
| `memory_forget` | outward | Permanently delete one event, or one entity and every event involving it. |
| `consent_list` | read | Which data sources the user has granted, their scope, row counts, and whether the consent ledger hash chain verifies. |
| `consent_grant` | outward | Ask the user to allow a data source. |
| `consent_revoke` | outward | Revoke a data source: hard-deletes everything ingested from it. |

## Plugin: Reply rules — 3

| Tool | Tier | What it does |
|---|---|---|
| `rules_set` | act | Save a reply rule the user asked for, e.g. |
| `rules_list` | read | List the active reply rules. |
| `rules_clear` | act | Turn off one reply rule (id) or all of them (no id). |

## Plugin: Scheduled tasks — 3

| Tool | Tier | What it does |
|---|---|---|
| `schedule_task` | act | Run a task later, even if the phone is locked then (it unlocks for it). |
| `list_scheduled` | read | Upcoming scheduled tasks: {items:[{id, prompt, next, repeat}]}. |
| `cancel_scheduled` | act | Cancel a scheduled task by id, or by words from its prompt. |

## Plugin: Apps — 3

| Tool | Tier | What it does |
|---|---|---|
| `build_app` | act | Build a small custom app for the user and put it on their home screen: trackers (workouts, water, habits, plants, mood), counters, logs, checklists, timers, dic |
| `list_my_apps` | read | The custom apps built for the user: {apps:[{id, name, description}]}. |
| `remove_app` | act | Remove one of the user's custom apps by name or id (only when they ask). |

## Plugin: Mods — 14

| Tool | Tier | What it does |
|---|---|---|
| `mod_inspect` | read | For writing a mod: the app on screen's elements that have a view id, as "vid role \"text\" [left,top,right,bottom px] flags". |
| `mod_list` | read | List the mods on this phone: {mods:[{id, package, app, label, shown}]}. |
| `mod_remove` | act | Remove a mod by id (no approval needed). |
| `mod_preview` | read | Dry-run a mod in the foreground app: highlight matched rectangles for 3 seconds. |
| `mod_apply` | act | Apply the exact spec after mod_preview with visible matches; Clean owner-requested masks go through the low-risk reviewer automatically; remote/tainted/unbound  |
| `mod_disable` | act | Kill switch: disable every mod, or all mods for package. |
| `deepmod_style` | act | Preferred for "theme Pigeon navy and gold and remove the promoted rows": one call inspects, builds a strict signed spec, previews, screenshots, reviews and comm |
| `deepmod_inspect` | read | Inspect the running owned Pigeon cooperative runtime: exact build, revision, current spec, screen and supported tokens/targets. |
| `deepmod_preview` | act | Preview a signed Pigeon spec on its current screen, take before/after screenshots and pixel diff, verify native structure and restore automatically. |
| `deepmod_apply` | act | Commit the exact bytes from a successful deepmod_preview receipt within 30 seconds. |
| `deepmod_list` | read | Read Pigeon cooperative mod state (separate from overlay mods). |
| `deepmod_remove` | act | Restore built-in Pigeon appearance and all filtered synthetic rows, preserving conversations and composer; always allowed. |
| `deepmod_rollback` | act | Restore the previous successfully committed Pigeon appearance with a fresh higher signed revision; no chat data changes. |
| `deepmod_disable` | act | Durable Pigeon deep-mod kill switch: increase epoch, cancel previews and restore defaults. |

## Other (not in a plugin) — 4

| Tool | Tier | What it does |
|---|---|---|
| `app_mod_apply` | act | Add a button to an app (a "mod"): it sits next to one of the app's buttons, looks native, and when tapped presses the app's own buttons. |
| `app_mod_targets` | read | For writing a mod: the app on screen's elements that have a view id, as "vid role \"text\" [left,top,right,bottom px] flags". |
| `app_mod_list` | read | List the mods on this phone: {mods:[{id, package, app, label, shown}]}. |
| `app_mod_remove` | act | Remove a mod by id (no approval needed). |

Total: 94 tools.

## Memory v2 tools (also offered when MEMORY_V2=1)
| Tool | Tier | What it does |
|---|---|---|
| `memory_person` | read | Everything memory knows about one person: names across apps, apps, relationship, facts, identity status. |
| `memory_facts` | read | Durable facts (preferences, where people live/work, plans), each cited to its source. |
| `memory_identity` | read | Open "is Discord Joe the same as Joe Smith?" questions; resolves a name to one person for outward actions. |
| `memory_correct` | outward | The user corrects or deletes one remembered fact (needs confirmation). |
| `memory_confirm_identity` | outward | Records the user's answer to an identity question (needs confirmation). |

## What remote assistants (Grok / Dots) see: only these 5, never the tools above
| Tool | Tier | What it does |
|---|---|---|
| `run_task` | act | Ask the phone's own agent to do something; the phone asks the owner before using an app and before anything outward. |
| `task_result` | read | Wait for a task's result. |
| `task_status` | read | Current state of tasks it started. |
| `phone_brief` | read | Latest messages on the phone (switchable off: "reads off"). |
| `memory_lookup` | read | Search the owner's memory (switchable off). |

Meta: `load_plugin` lets the model load an off-by-default plugin's tools when `AGENTD_TOOLS=plugins` (default is all tools loaded).
