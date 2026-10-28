// Tool plugins (research/TOOLS-AT-SCALE.md): the direct-API tools grouped so the owner can switch a group off and the
// model only reads the groups a request needs. Analogy: a restaurant menu. The core page is always on the table; the
// host hands over the dessert page when you mention cake (pick); any other page you can still ask for (load_plugin).
//   - Toggles: the broker (android Plugins.java) is the source of truth. Only the owner's launcher (owner line) or adb can
//     flip one; agentd reads `plugins.list` and refuses a disabled plugin's tools in runTool, and the broker refuses its
//     methods again with its own map. A toggle gates the API tools only: the app itself can still be driven on screen.
//   - AGENTD_TOOLS=all (default): every enabled tool upfront, as before. AGENTD_TOOLS=plugins: core + the 0-2 plugins
//     pick() reads in the user's own words; the other enabled plugins are deferred (one-line summaries, load on demand).
import { cfg } from './config.js';
import { tools as notificationTools } from './notification-tools.js';
import { names as deepmodNames } from './deepmod-tools.js';

// Always on, never toggled: everything the current bench tasks need, so no core task gets slower.
export const CORE = ['task_start', 'screen_read', 'tap', 'type', 'scroll', 'press', 'open_app', 'list_apps', 'act', 'wait',
  'memory_ask', 'notifications_list', 'notifications_reply', ...notificationTools.map((t) => t.name), 'set_alarm', 'set_timer', 'web_search', 'catch_up', 'show'];

// route: the pre-selector's words (the user's request only; never screen or notification text, so planted text can't
// widen the tool set). prompt: the system-prompt lines that only matter when the plugin's tools are visible.
export const PLUGINS = [
  { id: 'health', name: 'Samsung Health', on: true, summary: 'Imported watch sleep, heart rate, HRV, stress, steps and exercise; private, read-only.',
    tools: ['health_summary', 'health_query'], route: /\b(sleep|slept|heart rate|hrv|stress|resting|steps|exercise|workouts?|samsung health|watch|fitness)\b/,
    prompt: '- Health questions: use health_query for "this week" (Monday through today) and "last week" (previous Monday through Sunday); never replace a calendar week with a rolling 7 days. Use health_summary for a rolling period or an unspecified trend. Missing readings are unknown; never call minimum HR resting HR. These tools read the consented export on this phone. Then call show once: sleep → stacked_bar per night (labels "Mon"…, series deep/light/rem/awake in min, unit "min") plus stats (average per night, best night); steps → bar per day (unit "steps") plus stats (total, daily average); heart rate / HRV / stress → line; then one or two sentences.' },
  { id: 'calendar', name: 'Calendar', on: true, summary: 'Find, add, move and delete calendar events directly (no Calendar screen).',
    tools: ['add_event', 'find_events', 'update_event', 'delete_event'],
    route: /\b(calendars?|meetings?|events?|appointments?|agenda|invites?|free|busy|lunch|dinner|breakfast|dentist|doctor)\b/,
    prompt: '- Calendar: add_event/find_events/update_event/delete_event; never tap through the Calendar app.' },
  { id: 'messages', name: 'Messages & calls', on: true, summary: 'Contacts, send/read SMS, call log, place calls.',
    tools: ['find_contact', 'send_sms', 'read_sms', 'read_calls', 'call'],
    route: /\b(texts?|texted|sms|messages?|calls?|called|calling|phone number|number|contacts?|voicemail)\b/,
    prompt: '- Messages & calls: find_contact; send_sms, read_sms, read_calls, call.' },
  { id: 'clock', name: 'Alarms', on: true, summary: 'List the alarms in Clock and delete or turn one off.',
    tools: ['list_alarms', 'delete_alarm'],
    route: /\b(alarms|(delete|remove|cancel|turn off|disable|clear|change|snooze)\b.{0,30}\balarm|alarm\b.{0,20}\b(off|delete)|my alarm)\b/,
    prompt: '- Alarms: list_alarms, delete_alarm (set_alarm sets one).' },
  { id: 'device', name: 'Device settings', on: true, summary: 'Do Not Disturb, flashlight, volume, ringer, brightness, Wi-Fi, Bluetooth, airplane; battery and status.',
    tools: ['phone_settings', 'phone_status'],
    route: /\b(dnd|do not disturb|flashlight|torch|volume|louder|quieter|ringer|silent|vibrate|mute|brightness|brighter|dimmer|wi-?fi|bluetooth|airplane|battery|charging|screen time|settings?)\b/,
    prompt: '- Device settings: phone_settings for Do Not Disturb, flashlight, volume, ringer, brightness, Wi-Fi, Bluetooth, airplane (never open Settings); phone_status for battery and state.' },
  { id: 'media', name: 'Media', on: true, summary: 'Play music in an app; pause, skip, now playing.',
    tools: ['play_music', 'media'],
    route: /\b(play|playing|pause|resume|skip|songs?|music|playlists?|spotify|podcasts?|album|track)\b/,
    prompt: '- Media: play_music, media.' },
  { id: 'web', name: 'Maps & links', on: true, summary: 'Start Google Maps navigation; open a web link in Chrome.',
    tools: ['navigate', 'open_url'],
    route: /\b(navigate|navigation|directions|route|drive to|take me|maps?|how far|links?|urls?|websites?|site)\b|https?:\/\/|\.(com|org|net|io|dev)\b/,
    prompt: '- Maps & links: navigate; open_url.' },
  { id: 'vault', name: 'Passwords', on: true, summary: 'Fill a saved password into a login screen (fingerprint; you never see it).',
    tools: ['vault_list', 'vault_fill'],
    route: /\b(passwords?|log (me )?in(to)?|logged out|sign (me )?in(to)?|signed out|login|credentials|vault)\b/,
    prompt: '- If a login needs a password, call vault_list, then vault_fill with the password field (and user_field_id for the email/username field: typing is blocked on login screens). Never ask the user to type or tell you a password, and never type one.' },
  { id: 'memory', name: 'Memory tools', on: true, summary: 'Search and browse personal memory, remember or forget facts, memory consent.',
    tools: ['memory_search', 'memory_entity', 'memory_neighbors', 'memory_timeline', 'memory_remember', 'memory_forget', 'consent_list', 'consent_grant', 'consent_revoke'],
    route: /\b(remember|forget|memory|memories|consent|what do you know|timeline|history with)\b/,
    prompt: '- Memory tools: memory_search/memory_timeline only to dig further after memory_ask. Memory only holds sources the user granted (consent_list). Granting, revoking and forgetting need the user\'s own confirmation; if a tool says so, tell the user how to do it.' },
  { id: 'rules', name: 'Reply rules', on: true, summary: 'Standing rules that answer messages for the user (auto, handle or draft).',
    tools: ['rules_set', 'rules_list', 'rules_clear'],
    route: /\b(handle|auto-?reply|rules?|replies from|tell (people|everyone|anyone)|while i'?m|i'?m driving|whenever)\b/,
    prompt: '- "Handle replies from X for me", "tell people I\'m driving" and similar: save it with rules_set (mode auto for a fixed message, handle when replies need thought, draft to only suggest). Turn rules off with rules_clear.' },
  { id: 'schedule', name: 'Scheduled tasks', on: true, summary: 'Run a request later or on a repeat ("at 7 PM ...", "every morning ...", "remind me ...").',
    tools: ['schedule_task', 'list_scheduled', 'cancel_scheduled'],
    route: /\b(remind|reminders?|every|daily|weekly|weekdays?|tonight|later|scheduled?|at \d{1,2}(:\d\d)?\s*(am|pm)?|in \d+\s*(sec|second|min|minute|hour|hr)s?)\b/,
    prompt: '- "At 7 PM ...", "every morning ...", "in 20 minutes ...", "remind me ...": call schedule_task once (prompt = the later task in the user\'s words) and say when it will run. Don\'t do the task now.' },
  { id: 'energy', name: 'Energy', on: true, summary: 'Utah power outlook: Rocky Mountain Power time-of-use prices + EIA grid data; the cheapest and cleanest hours.',
    tools: ['grid_outlook'], route: /\b(when should i|good time to|dishwasher|laundry|dryer|washer|washing|charge (my |the )?(car|ev|tesla)|ev|electricity|power|energy|grid|peak|off-peak|kwh|solar|carbon|cheapest|cleanest)\b/,
    prompt: '- Energy (Utah): "when should I run the dishwasher / laundry / charge my car?", "is now a good time to run X?": call grid_outlook once (for one of the owner\'s Google Home devices, use home_plan instead) and answer in one or two sentences: the window (from–to), the price ratio ("4.5× cheaper on the time-of-use rate"), and the cleaner-grid reason in estimated g CO₂ (say "estimated"; mention the data is from yesterday\'s grid mix). Dishwasher/laundry → the cleanest window (if now is good_time, say now is fine). A car → plug in at the overnight window\'s start (cheapest, but name the cleaner daytime window too: cheap vs clean). If the best window is later, end with exactly "Remind you?" (the one allowed follow-up offer) If the user says yes, call schedule_task once: when = that window\'s remind_when, prompt = "Remind me to <their task> (off-peak, cleaner grid now)", and say the time. For "when is the cleanest / cheapest time to use power today?" also call show once with a timeline: spans = the cleanest window (kind good, label "Cleanest"), the overnight/cheapest window if different (good, "Cheapest") and the next peak (kind peak, label "Peak"), from/to as clock times ("3 PM"), unit "¢/kWh", plus stats for the off-peak and peak prices. The agent\'s own deferrable work the user asks for ("do my weekly recap when the grid is cleanest") is scheduled the same way.' },
  { id: 'home', name: 'Smart home', on: true, summary: 'Your Google Home devices (saved list: name, room, type, est. watts) and when to run them on the grid.',
    tools: ['home_devices', 'home_plan', 'home_daily'],
    route: /\b(google home|smart home|devices?|lights?|lamps?|plugs?|outlets?|christmas tree|computer|turn (it |them )?(on|off)|switch(es)?)\b/,
    prompt: '- Smart home: home_devices/home_plan/home_daily use the owner\'s Google Home device list saved in memory. Always say you know the devices from their Google Home list and when you last saw it (last_seen_in_google_home); live status and remote control aren\'t connected yet, so never claim to switch a device or know its current state (say e.g. "it was offline when I last saw it"). "What devices do I have?": home_devices, then show once (type list: label = name, value = type, detail = room) and one sentence. "When should I turn on / run <device>?": home_plan, then show once (type timeline: the best window kind good, the next peak kind peak; stats for the estimated cost there vs at peak) and one or two sentences: the window, the estimated cost difference, the cleaner-grid reason, ending with "Remind you then?"; on yes, schedule_task once at best_window.remind_when with prompt "Remind me to turn on the <device>". "Every morning / each day check and remind me ...": home_daily once and say when the check runs, today\'s reminder time and the check\'s id.' },
  { id: 'apps', name: 'Apps', on: true, summary: 'Build small custom apps for the user (trackers, counters, dice, timers) onto the home screen, or update one.',
    tools: ['build_app', 'list_my_apps', 'remove_app'],
    route: /\b(apps?|build|make|create|track|tracker|tracking|log|logger|counter|dice|roller|habits?|streaks?|widget|intake)\b/,
    prompt: '- Apps: when the user wants to track, log, count, roll or time something, or asks for an app/tool, call build_app once with their request (no questions; sensible defaults). "Add X to my <name> app" = build_app with update:"<name>". It takes about half a minute; then say it is on their home screen.' },
  { id: 'mods', name: 'Mods', on: true, summary: 'Preview and mask promoted app content, simplify screens, add safe buttons, list/remove mods.',
    tools: ['mod_inspect', 'mod_preview', 'mod_apply', 'mod_list', 'mod_remove', 'mod_disable', ...deepmodNames],
    route: /\b(mods?|buttons?|promoted|sponsored|simplify|restyle|theme|fonts?|navy|gold|pigeon|hide|reorder|banners?)\b/,
    prompt: '' },
];
export const BY_ID = Object.fromEntries(PLUGINS.map((p) => [p.id, p]));
export const pluginOf = Object.fromEntries(PLUGINS.flatMap((p) => p.tools.map((t) => [t, p.id])));

// Toggle state, read from the broker (plugins.list) and cached for a few seconds. If the broker can't be reached we keep
// the last answer (or the manifest defaults): nothing on the phone runs without the broker anyway.
let off = new Set(PLUGINS.filter((p) => !p.on).map((p) => p.id)), at = 0, pending = null;
export const invalidate = () => { at = 0; };
export function disabled(rpc) {
  if (Date.now() - at < 5000) return Promise.resolve(off);
  return (pending ??= rpc('plugins.list', {}, 5000).then((r) => { if (Array.isArray(r.off)) off = new Set(r.off.map(String)); at = Date.now(); },
    () => { at = Date.now() - 4000; }).then(() => { pending = null; return off; }));
}

// Pre-selector: plain code, no model, < 1 ms. Leans toward picking: an extra plugin costs ~0.1-1k cached tokens, a miss a
// whole model call. At most 2 (3 for a long request), earliest mention first.
export function pick(goal) {
  const t = String(goal || '').toLowerCase();
  return PLUGINS.map((p) => [p.id, t.search(p.route)]).filter(([, i]) => i >= 0).sort((a, b) => a[1] - b[1])
    .slice(0, t.length > 120 ? 3 : 2).map(([id]) => id);
}

// What one turn gets: loaded = plugins whose tools go in upfront, deferred = enabled ones reachable on demand.
export function toolset(goal, offSet = off, mode = cfg.tools) {
  const on = PLUGINS.filter((p) => !offSet.has(p.id)).map((p) => p.id);
  const loaded = mode === 'plugins' ? pick(goal).filter((id) => on.includes(id)) : on;
  const deferred = on.filter((id) => !loaded.includes(id));
  return { loaded, deferred, off: PLUGINS.filter((p) => offSet.has(p.id)).map((p) => p.id),
    names: [...CORE, ...PLUGINS.filter((p) => loaded.includes(p.id)).flatMap((p) => p.tools)] };
}

// The fallback for providers without native tool search (Codex with gpt-6.1-terra, older Claude Code): two small tools.
// A miss costs one extra call (load_plugin); the actual call then goes through plugin_call -> runTool like any other.
export const LOADERS = {
  load_plugin: { description: 'Load a deferred plugin (see "More tools" in your instructions): returns its tools\' names, descriptions and input schemas. Then call them with plugin_call.',
    schema: { type: 'object', properties: { id: { type: 'string', description: 'plugin id' } }, required: ['id'], additionalProperties: false } },
  plugin_call: { description: 'Call a tool of a loaded plugin: {tool, args} where args follows the schema load_plugin returned.',
    schema: { type: 'object', properties: { tool: { type: 'string' }, args: { type: 'object' } }, required: ['tool', 'args'], additionalProperties: false } },
};
