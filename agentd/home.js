// Smart home plugin: the owner's Google Home devices as an inventory in memory, planned against the grid.
// Like a list of appliances taped inside a kitchen cabinet: the agent knows what you own, where it is and roughly what it
// draws, but it can't see whether it's on or flip it. The list was read once from the owner's Google Home app (10-28) and
// saved as the consent-gated memory source "home_devices" (memory/ingest.js); revoking the source deletes it.
//   - home_devices: list/filter, local and instant (no app is opened; live status and control aren't connected).
//   - home_plan: the device's estimated watts x grid_outlook (grid.js) -> best window, estimated cost difference, cleaner grid.
//   - home_daily: a daily morning check (schedule.js item with `plan`): agentd re-plans in code, no model, and moves that
//     day's reminder to the new window. Cancelling the check also cancels its reminder.
// The earlier version drove the Google Home app on screen (git 23b04b1); the founder chose not to have the agent open it.
import { log } from './config.js';
import * as grid from './grid.js';
import * as schedule from './schedule.js';
import { open as openMemory, readable } from './memory/store.js';

const HOURS = { light: 4, plug: 3 }; // a typical run when the user doesn't say
let db = null;
function inventory(cloud = true) {
  db ??= openMemory();
  if (!readable(db, cloud).includes('home_devices')) return null;
  return db.prepare(`SELECT body FROM event WHERE source='home_devices' AND kind='device' ORDER BY title`).all().map((r) => JSON.parse(r.body));
}
const seen = (t) => new Date(t).toLocaleString('en-US', { weekday: 'short', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
const OFF = { ok: false, error: 'The Home devices memory source is off (or was never added). The owner can turn it on in Memory › Sources.' };
const HONEST = 'The owner\'s saved Google Home list: no live on/off state and no remote control. Never say a device is on/off now or that you switched one. Watts are typical estimates.';
const view = (d) => ({ name: d.name, room: d.room, type: d.type, ecosystem: d.ecosystem, est_watts: d.watts_est, watts_basis: d.watts_basis,
  saved_from_google_home: seen(d.seen_at) }); // no on/off state: it was a snapshot, not live
const norm = (s) => String(s || '').toLowerCase().replace(/\b(the|my|lights?|plug)\b/g, ' ').replace(/\s+/g, ' ').trim();
function find(list, name) {
  const q = norm(name), near = list.filter((d) => norm(d.name).includes(q) || q.includes(norm(d.name)));
  return list.find((d) => norm(d.name) === q) ?? (near.length === 1 ? near[0] : null);
}

export function devices(args = {}, ctx = {}) {
  const list = inventory(ctx.provider !== 'local');
  if (!list) return OFF;
  const f = (k) => String(args[k] || '').toLowerCase();
  const hit = list.filter((d) => (!args.room || d.room?.toLowerCase().includes(f('room'))) && (!args.type || d.type === f('type')) && (!args.name || find([d], args.name)));
  return { ok: true, devices: hit.map(view), note: HONEST };
}

// The plan: run the device for `hours` starting at the cleanest off-peak window vs during the next peak.
export async function plan(args = {}, ctx = {}) {
  const list = inventory(ctx.provider !== 'local');
  if (!list) return OFF;
  const d = find(list, args.device);
  if (!d) return { ok: false, error: `"${args.device}" isn't in the owner's Google Home list`, devices: list.map((x) => x.name) };
  const g = await grid.call(), w = g.cleanest ?? g.cheapest, pk = g.next_peak;
  const hours = Math.min(24, Math.max(0.25, +args.hours || HOURS[d.type] || 2)), kwh = d.watts_est * hours / 1000;
  const c = (cents) => +(kwh * cents).toFixed(1), co2 = (gkwh) => (gkwh == null ? null : Math.round(kwh * gkwh));
  return { ok: true, device: view(d), hours, est_kwh: +kwh.toFixed(2),
    best_window: { from: w.from, to: w.to, remind_when: w.remind_when, price_cents_kwh: w.price_cents, est_g_co2_kwh: w.est_g_co2_kwh },
    now: g.now, next_peak: pk && { from: pk.from, to: pk.to, price_cents_kwh: pk.price_cents, est_g_co2_kwh: pk.est_g_co2_kwh },
    est_cost_cents: { best_window: c(w.price_cents), peak: pk ? c(pk.price_cents) : null, saving: pk ? c(pk.price_cents - w.price_cents) : null },
    est_co2_g: { best_window: co2(w.est_g_co2_kwh), peak: co2(pk?.est_g_co2_kwh ?? g.dirtiest_est_g_co2_kwh) },
    reason: g.reason, data_age: g.data_age, note: `${HONEST} Prices: ${g.notes}` };
}

// Morning check: re-plan and move today's reminder. x = the daily schedule item ({id, plan: device name}).
async function replan(x) {
  const p = await plan({ device: x.plan });
  if (!p.ok) { log(`home: daily check for ${x.plan} skipped (${p.error})`); return schedule.rebook(x.id, null); }
  const w = p.best_window, at = Math.max(schedule.parseWhen(w.remind_when), Date.now() + 60_000);
  log(`home: daily check ${x.plan} -> ${w.from}–${w.to}`);
  return schedule.rebook(x.id, { at, prompt: `Remind me to turn on the ${p.device.name} now: the cleanest power window is ${w.from}–${w.to} (estimated)` });
}
schedule.onPlan(replan);

export async function daily(args = {}, ctx = {}) {
  const list = inventory(ctx.provider !== 'local');
  if (!list) return OFF;
  const d = find(list, args.device);
  if (!d) return { ok: false, error: `"${args.device}" isn't in the owner's Google Home list`, devices: list.map((x) => x.name) };
  const r = await schedule.call('schedule_task', { when: args.at || '7 AM', repeat: 'daily', prompt: `Daily grid check for the ${d.name}: re-plan its cleanest window and move its reminder` }, ctx.turn || {});
  if (!r.ok) return r;
  schedule.setPlan(r.scheduled.id, d.name);
  const child = await replan({ id: r.scheduled.id, plan: d.name });
  return { ok: true, daily_check: r.scheduled, todays_reminder: child, note: 'Each morning agentd re-plans from that day\'s grid outlook and moves the reminder. It only reminds: it can\'t switch the device. Cancel the daily check by its id (that cancels its reminder too).' };
}

const obj = (props, required = []) => ({ type: 'object', properties: props, required, additionalProperties: false });
const S = { type: 'string' };
export const tools = [
  { name: 'home_devices', tier: 'read', run: devices, schema: obj({ room: S, type: { type: 'string', enum: ['light', 'plug'] }, name: S }),
    description: 'The owner\'s smart home devices, from their Google Home list saved in memory: name, room, type, ecosystem, estimated watts. Instant, read-only. No live on/off state and no control.' },
  { name: 'home_plan', tier: 'read', run: plan, schema: obj({ device: S, hours: { type: 'number', description: 'how long it runs; default 4 h for a light, 3 h for a plug' } }, ['device']),
    description: 'Best time to run one of the owner\'s devices: grid_outlook + its estimated watts -> best_window (with remind_when for schedule_task), estimated cost there vs at the next peak, estimated CO₂, reason.' },
  { name: 'home_daily', tier: 'act', run: daily, schema: obj({ device: S, at: { type: 'string', description: 'when the morning check runs; default "7 AM"' } }, ['device']),
    description: 'A daily morning check for one device: each morning agentd re-plans its cleanest window from that day\'s grid outlook and moves a reminder there (books today\'s now). Returns the daily check\'s id and today\'s reminder.' },
];
