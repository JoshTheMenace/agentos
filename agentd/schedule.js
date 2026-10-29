// Scheduler: "at 7 PM text Sam I'm on my way", "every weekday at 8 read me my notifications", "remind me to stretch at 3".
// Like notes on the fridge: each says when and what. When one is due it becomes a normal background task (tasks.js) with
// origin "schedule", which the broker may unlock a locked phone for (android/README.md "Unlock for the founder's tasks").
// Two clocks ring it: the phone's exact AlarmManager alarm (wakes the CPU even in deep doze; the broker sends schedule.due
// on /events), and a 30 s check here (Node timers stop while the CPU sleeps, so due times are wall-clock compares).
// Saved in ~/.agentos/schedule.json, so it survives an agentd restart and a reboot.
// No imports from tools/tasks/events (they import this file): server.js hands them over in init().
import { existsSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { log } from './config.js';

const FILE = join(process.env.AGENTOS_HOME || join(homedir(), '.agentos'), 'schedule.json');
const LATE_MS = 30 * 60_000; // missed by more than this (phone off): skip it rather than text Sam an hour late
const REPEAT = ['none', 'daily', 'weekdays', 'weekly'];
let items = [], io = null;

const obj = (props, required = []) => ({ type: 'object', properties: props, required, additionalProperties: false });
export const tools = [
  { name: 'schedule_task', tier: 'act', description: 'Run a task later, even if the phone is locked then (it unlocks for it). For "at 7 PM text Sam I\'m on my way", '
    + '"every weekday at 8 read me my notifications", "remind me to call Mom at 3". prompt = the task as one complete instruction (for a reminder: '
    + '"Remind me to call Mom"; its result is shown as a notification). Only for the user\'s own requests.',
  schema: obj({ when: { type: 'string', description: '"in 20 minutes", "7 PM", "19:30", "tomorrow 8 AM", "monday 9am" or "2026-10-28 07:00" (phone\'s local time)' },
    repeat: { type: 'string', enum: REPEAT, description: 'default none' }, prompt: { type: 'string' } }, ['when', 'prompt']) },
  { name: 'list_scheduled', tier: 'read', description: 'Upcoming scheduled tasks: {items:[{id, prompt, next, repeat}]}.', schema: obj({}) },
  { name: 'cancel_scheduled', tier: 'act', description: 'Cancel a scheduled task by id, or by words from its prompt.', schema: obj({ which: { type: 'string' } }, ['which']) },
];

// "in 20 minutes" | "7 PM" | "19:30" | "tomorrow 8 AM" | "monday 9am" | "noon" | ISO -> epoch ms (next time it happens), or NaN.
const DAYS = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];
export function parseWhen(when, now = Date.now()) {
  const s = String(when || '').toLowerCase().replace(/[.,]/g, '').replace(/\b(at|on|the|this)\b/g, ' ').replace(/\s+/g, ' ').trim();
  const rel = s.match(/^in (\d+|an?|half an?) ?(sec|second|min|minute|hr|hour|day)s?$/);
  if (rel) return now + (/^half/.test(rel[1]) ? 0.5 : /^an?$/.test(rel[1]) ? 1 : +rel[1]) * { sec: 1e3, second: 1e3, min: 6e4, minute: 6e4, hr: 36e5, hour: 36e5, day: 864e5 }[rel[2]];
  if (/^\d{4}-\d\d-\d\d[ t]\d\d?:\d\d/.test(s)) return new Date(s.replace(' ', 'T')).getTime();
  const m = s.match(/^(?:(today|tonight|tomorrow|morning|evening|sunday|monday|tuesday|wednesday|thursday|friday|saturday) ?)?(noon|midnight|(\d{1,2})(?::(\d\d))? ?(am|pm|a m|p m)?)(?: (today|tonight|tomorrow|morning|evening|sunday|monday|tuesday|wednesday|thursday|friday|saturday))?$/);
  if (!m) return NaN;
  const day = m[1] || m[6], ap = (m[5] || '').replace(' ', '');
  let h = m[2] === 'noon' ? 12 : m[2] === 'midnight' ? 0 : +m[3];
  if (h > 23 || +(m[4] || 0) > 59 || (ap && h > 12)) return NaN;
  if (ap === 'pm' && h < 12 || !ap && /tonight|evening/.test(day) && h < 12) h += 12;
  if (ap === 'am' && h === 12) h = 0;
  const d = new Date(now); d.setHours(h, +(m[4] || 0), 0, 0);
  if (day === 'tomorrow') d.setDate(d.getDate() + 1);
  else if (DAYS.includes(day)) d.setDate(d.getDate() + ((DAYS.indexOf(day) - d.getDay() + 7) % 7));
  if (d.getTime() <= now && !ap && !day && h >= 1 && h < 12) d.setHours(h + 12); // "at 8" said at 2 PM = 8 PM
  if (d.getTime() <= now) d.setDate(d.getDate() + (DAYS.includes(day) ? 7 : 1)); // "7 PM" said at 8 PM = tomorrow
  return d.getTime();
}
// First/next run for a repeat: weekdays skip Sat/Sun. after: the run that just happened (null = first).
function next(at, repeat, after = null) {
  const d = new Date(at);
  if (after != null) d.setDate(d.getDate() + (repeat === 'weekly' ? 7 : 1)); // setDate keeps the local time across DST
  while (repeat === 'weekdays' && (d.getDay() === 0 || d.getDay() === 6)) d.setDate(d.getDate() + 1);
  return d.getTime();
}

const when = (t) => new Date(t).toLocaleString('en-US', { weekday: 'short', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
const view = (x) => ({ id: x.id, prompt: x.prompt, next: when(x.next), at: x.next, repeat: x.repeat, ...(x.parent && { daily_check: x.parent }) });
export const list = () => items.map(view);

function save() {
  try { writeFileSync(`${FILE}.tmp`, JSON.stringify(items, null, 1), { mode: 0o600 }); renameSync(`${FILE}.tmp`, FILE); }
  catch (e) { log('schedule save failed', e.message); }
  io?.publish({ type: 'schedule.list', items: list() }); // every UI's home screen
  arm();
}
// One exact alarm for the earliest item (0 = none).
const arm = () => io?.rpc('schedule.alarm', { at: items.length ? Math.min(...items.map((x) => x.next)) : 0 }, 10_000).catch((e) => log('schedule.alarm failed', e.message));
const audit = (event, x, extra = {}) => io?.rpc('schedule.alarm', { event, id: x.id, prompt: x.prompt, when: `${when(x.next)}${x.repeat !== 'none' ? `, ${x.repeat}` : ''}`, ...extra }, 10_000).catch(() => {});

// Due items become tasks. A repeat moves to its next time; a one-shot is removed.
export function check(now = Date.now()) {
  const due = items.filter((x) => x.next <= now);
  if (!due.length) return;
  for (const x of due) {
    if (now - x.next > LATE_MS) { log(`schedule ${x.id} missed (${when(x.next)})`); audit('missed', x); }
    else if (x.notify) { log(`schedule ${x.id} reminder: ${x.prompt}`); audit('done', x, { text: x.text }); } // an app's reminder: a notification, no model, no task
    else if (x.plan && planner) { log(`schedule ${x.id} re-plan: ${x.plan}`); planner(x).catch((e) => log('re-plan failed', e.message)); } // home.js: code, no model
    else { log(`schedule ${x.id} due: ${x.prompt}`); const t = io.start(x.prompt, { origin: 'schedule' }); fired.set(t.id, x); }
    if (x.repeat === 'none') items = items.filter((y) => y !== x);
    else { let n = x.next; while (n <= now) n = next(n, x.repeat, n); x.next = n; }
  }
  save();
}
const fired = new Map(); // task id -> its schedule item, to report the result

export async function call(name, args, turn = {}) {
  if (name === 'list_scheduled') return { ok: true, items: list() };
  if (name === 'cancel_scheduled') {
    const w = String(args.which || '').toLowerCase(), words = w.split(/\W+/).filter((x) => x.length > 2);
    const x = items.find((i) => i.id === w) || items.find((i) => words.length && words.every((k) => i.prompt.toLowerCase().includes(k)));
    if (!x) return { ok: false, error: 'no such scheduled task', items: list() };
    items = items.filter((i) => i !== x && i.parent !== x.id); audit('cancelled', x); save(); // a daily check takes its reminder with it
    return { ok: true, cancelled: view(x) };
  }
  // schedule_task: only the user's own, clean request. A turn that read flagged text (or a reply rule's, or a scheduled
  // task's own) can't plant a schedule, because a schedule may later unlock the phone.
  if (turn.untrusted || turn.alerted?.size || io?.flagged.has(turn.lease?.task_id) || turn.origin === 'schedule')
    return { ok: false, error: 'not scheduled: only the user\'s own request can schedule a task (this one read flagged or third-party text)' };
  const repeat = REPEAT.includes(args.repeat) ? args.repeat : 'none', at = parseWhen(args.when);
  const prompt = String(args.prompt || '').trim().slice(0, 300);
  if (!prompt) return { ok: false, error: 'prompt is required' };
  if (!(at > Date.now())) return { ok: false, error: `couldn't read the time "${args.when}" (try "7 PM", "in 20 minutes", "tomorrow 8 AM")` };
  if (items.length >= 20) return { ok: false, error: 'at most 20 scheduled tasks: cancel one first' };
  const x = { id: `s${Date.now().toString(36)}`, prompt, repeat, next: next(at, repeat), created: Date.now(), by: turn.origin || 'typed' };
  items.push(x); items.sort((a, b) => a.next - b.next);
  audit('created', x); save();
  return { ok: true, scheduled: view(x), note: 'It runs then even if the phone is locked.' };
}

export const cancel = (id) => call('cancel_scheduled', { which: id });
// A micro-app's reminder (apps.js AOS.notify): notification only at `at`, never a task. by = "app:<id>"; at most `max` pending per app.
export function remind({ title, text, at, by, max = 5 }) {
  if (items.filter((i) => i.by === by).length >= max) return { ok: false, error: `at most ${max} pending reminders per app` };
  const x = { id: `s${Date.now().toString(36)}n`, prompt: String(title).slice(0, 60), text: String(text).slice(0, 200), repeat: 'none', next: at, created: Date.now(), by, notify: true };
  items.push(x); items.sort((a, b) => a.next - b.next); audit('created', x); save(); arm();
  return { ok: true, id: x.id, at };
}
export function cancelBy(by) { const n = items.length; items = items.filter((i) => i.by !== by); if (items.length !== n) { save(); arm(); } return n - items.length; }

// Daily checks (home.js home_daily): a daily item with `plan` is re-planned by code when due (planner), and owns at most one
// one-shot reminder (parent = its id) that rebook() replaces. Only the user's own home_daily call creates one.
let planner = null;
export const onPlan = (fn) => { planner = fn; };
export function setPlan(id, plan) { const x = items.find((i) => i.id === id); if (x) { x.plan = plan; save(); } }
export function rebook(parent, r) {
  items = items.filter((i) => i.parent !== parent);
  const x = r && { id: `s${Date.now().toString(36)}r`, prompt: r.prompt.slice(0, 300), repeat: 'none', next: r.at, created: Date.now(), by: 'daily-check', parent };
  if (x) { items.push(x); items.sort((a, b) => a.next - b.next); audit('created', x); }
  save();
  return x && view(x);
}

// io: {start, onUpdate, rpc, onEvent, publish, flagged} from server.js.
export function init(deps) {
  io = deps;
  try { items = existsSync(FILE) ? JSON.parse(readFileSync(FILE, 'utf8')).filter((x) => x && x.prompt && x.next) : []; }
  catch (e) { log('schedule.json unreadable', e.message); }
  io.onEvent((e) => e.type === 'schedule.due' && check());
  io.onUpdate((t, why) => { // report a scheduled task's result: a notification (task.update and the voice already have it)
    const x = fired.get(t.id);
    if (!x || !['done', 'failed', 'stopped'].includes(why)) return;
    fired.delete(t.id);
    audit(why === 'done' ? 'done' : 'failed', x, { text: why === 'done' ? t.result : `Didn't finish: ${t.error || why}` });
  });
  setInterval(check, 30_000);
  arm(); check();
  log(`scheduler: ${items.length} scheduled (${FILE})`);
}
