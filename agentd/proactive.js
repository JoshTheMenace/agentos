// Proactive assistant (PROACTIVE=1): notices loose ends in your messages and calendar and offers help, never acts alone.
// Like a good chief of staff reading over your shoulder: "Sam asked about Friday 2 days ago", "you told Priya you'd send
// the Q3 numbers by EOD", "Design review overlaps lunch". Research + eval: research/PROACTIVE.md, proactive/eval/.
//   Triggers: a new message notification or a reply we sent (20 s debounce), the memory sync (every 10 min), the clock
//   (every minute: reminders, snoozes, calendar).
//   Detect: proactive/detect.js. Bots are dropped on the phone; the rest go in ONE batched cloud call (Codex, low effort),
//   then code's gate() has the last word. Offline or failing: regex rules. Eval: 99-100% precision, 93-95% recall.
//   Output: a Promises list (~/.agentos/promises.json; launcher: done / dismiss / snooze) and at most 3 Home cards.
//   A tap starts a normal task (drafted reply -> the usual approval card). Nothing is ever sent by this file.
//   Interruptions: only MY commitments with a due day become pushed reminders (via schedule.js, 9 AM on the due day),
//   at most PROACTIVE_MAX a day (3), never in quiet hours (PROACTIVE_QUIET=22-8) or while Do Not Disturb is on.
//   Requests and replies come from other people's text, so they never schedule anything: Home card and list only.
import { existsSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { cfg, log } from './config.js';
import { classify } from './guard.js';
import * as D from './proactive/detect.js';
import { open as openMemory, readable } from './memory/store.js';

const FILE = join(process.env.AGENTOS_HOME || join(homedir(), '.agentos'), 'promises.json');
const MAX = +(process.env.PROACTIVE_MAX || 3), [QS, QE] = (process.env.PROACTIVE_QUIET || '22-8').split('-').map(Number);
const H = 36e5, DAY = 24 * H, WINDOW = 3 * DAY; // threads older than 3 days are left alone
let io = null, items = [], calCards = [], db = null, debounce = null, scanning = Promise.resolve();
const live = new Map(); // thread id -> {app, pkg, conversation, group, key, msgs[]} from the notification feed
const seen = new Map(); // thread id -> signature at the last scan (unchanged threads are not re-sent)

export const enabled = () => !!io;
const save = () => { try { writeFileSync(`${FILE}.tmp`, JSON.stringify(items, null, 1), { mode: 0o600 }); renameSync(`${FILE}.tmp`, FILE); } catch (e) { log('promises save failed', e.message); } };
const changed = () => { items = items.filter((x) => x.state === 'open' || x.state === 'snoozed' || Date.now() - (x.closed || 0) < 7 * DAY); save(); io?.publish({ type: 'promises', items: list() }); };
const hash = (s) => createHash('sha1').update(s).digest('hex').slice(0, 10);
const short = (s, n) => { s = String(s || '').replace(/\s+/g, ' ').trim(); return s.length > n ? `${s.slice(0, n - 1)}…` : s; };
const ago = (t) => { const m = (Date.now() - t) / 6e4; return m < 60 ? `${Math.round(m)} min ago` : m < 1440 ? `${Math.round(m / 60)} h ago` : `${Math.round(m / 1440)} days ago`; };
const dayLabel = (d) => { const n = Math.round((new Date(`${d}T12:00`) - new Date(new Date().setHours(12, 0, 0, 0))) / DAY);
  return n < 0 ? 'overdue' : n === 0 ? 'today' : n === 1 ? 'tomorrow' : new Date(`${d}T12:00`).toLocaleDateString('en-US', { weekday: 'long' }); };

// ---------------------------------------------------------------- threads: live notifications + memory (read-only)
function note(n, from) { // one message into the live buffer
  const id = `${n.app || n.pkg}|${n.conversation}`, t = live.get(id) ?? { id, app: n.app || n.pkg, pkg: n.pkg, conversation: n.conversation, group: !!n.group, msgs: [] };
  if (!t.msgs.some((m) => m.text === n.text && Math.abs(m.when - n.when) < 5000)) t.msgs.push({ from, text: String(n.text || ''), when: n.when || Date.now() });
  if (n.key && n.can_reply !== false) t.key = n.key;
  t.msgs.sort((a, b) => a.when - b.when).splice(0, Math.max(0, t.msgs.length - 12));
  live.set(id, t);
}
function fromMemory() { // message events of the last 3 days from consented, cloud-scoped sources (redacted bodies)
  try {
    const srcs = readable(db ??= openMemory(), true); // what the owner allows cloud models to see
    return db.prepare(`SELECT e.ts, e.body_redacted AS text, p.name AS person, pe.role, a.name AS app, g.name AS grp FROM event e
      JOIN event_entity pe ON pe.event_id=e.id AND pe.role IN ('from','to') JOIN entity p ON p.id=pe.entity_id AND p.type='person'
      LEFT JOIN event_entity ae ON ae.event_id=e.id AND ae.role='app' LEFT JOIN entity a ON a.id=ae.entity_id
      LEFT JOIN event_entity ge ON ge.event_id=e.id AND ge.role='group' LEFT JOIN entity g ON g.id=ge.entity_id
      WHERE e.kind='message' AND e.ts > ? AND e.source IN (${srcs.map(() => '?').join(',')}) ORDER BY e.ts`).all(Date.now() - WINDOW, ...srcs);
  } catch (e) { log(`proactive: memory read failed (${e.message})`); return []; }
}
function threads() {
  const all = new Map([...live].map(([k, t]) => [k, { ...t, msgs: [...t.msgs] }]));
  for (const r of fromMemory()) {
    const conv = r.grp || r.person, id = `${r.app || 'Messages'}|${conv}`, t = all.get(id) ?? { id, app: r.app || 'Messages', conversation: conv, group: !!r.grp, msgs: [] };
    if (!t.msgs.some((m) => Math.abs(m.when - r.ts) < 60_000 && short(m.text, 30) === short(r.text, 30))) t.msgs.push({ from: r.role === 'to' ? 'me' : r.grp ? r.person : conv, text: r.text || '', when: r.ts });
    all.set(id, t);
  }
  return [...all.values()].map((t) => ({ ...t, msgs: t.msgs.sort((a, b) => a.when - b.when).slice(-12) }))
    .filter((t) => t.msgs.length && Date.now() - t.msgs.at(-1).when < WINDOW && !D.isBot(t));
}

// ---------------------------------------------------------------- scan: one batched model call for what changed
const sig = (t) => hash(t.msgs.map((m) => `${m.from}:${m.when}`).join('|'));
export function scan(why = '') { return (scanning = scanning.then(() => scanNow(why)).catch((e) => log(`proactive scan failed: ${e.message}`))); }
async function scanNow(why) {
  const now = Date.now(), todo = [];
  for (const t of threads().filter((t) => seen.get(t.id) !== sig(t)).sort((a, b) => b.msgs.at(-1).when - a.msgs.at(-1).when).slice(0, 40)) {
    // Other people's text is untrusted: a thread with a flagged message is never sent to a model or turned into a card.
    const flagged = (await Promise.all(t.msgs.filter((m) => m.from !== 'me').map((m) => classify(m.text)))).some((g) => g.label !== 'benign');
    seen.set(t.id, sig(t));
    if (!flagged) todo.push({ ...t, msgs: t.msgs.map((m) => ({ ...m, text: m.text.replace(/(?<![\d.:])\d{4,8}(?![\d.:])/g, '[code]') })) });
  }
  if (!todo.length) return;
  const ids = todo.map((t, i) => ({ ...t, id: `t${i + 1}`, thread: t.id })), t0 = Date.now();
  let out, via = 'cloud';
  try {
    const r = await io.codex.quick('proactive', { model: cfg.suggestModel, effort: cfg.suggestEffort, prompt: D.CLOUD_PROMPT, schema: D.CLOUD_SCHEMA, rotate: 20 }, D.cloudInput(ids, now), AbortSignal.timeout(90_000));
    const m = new Map(r.items.map((x) => [x.id, x]));
    out = ids.map((t) => D.gate(t, D.fromModel(t, m.get(t.id)), now));
  } catch (e) { via = `rules (${e.message})`; out = ids.map((t) => ({ ...D.gate(t, D.rules(t, now), now), what: '' })); } // rules have no summary: the card quotes
  ids.forEach((t, i) => upsert({ ...t, id: t.thread }, out[i]));
  log(`proactive: scanned ${ids.length} thread(s) via ${via} in ${Date.now() - t0} ms (${why}); ${items.filter((x) => x.state === 'open').length} open`);
  changed();
}

function upsert(t, r) {
  const mine = items.filter((x) => x.thread === t.id && (x.state === 'open' || x.state === 'snoozed'));
  const id = r.kind === 'none' ? null : hash(`${t.id}|${r.kind}|${r.at}`);
  for (const x of mine) if (x.id !== id) Object.assign(x, { state: x.kind === 'commitment' ? 'done' : 'resolved', closed: Date.now(), auto: true }); // answered, delivered, or it changed kind
  if (!id) return;
  const x = items.find((y) => y.id === id);
  if (x) { if (x.state === 'open') Object.assign(x, { due: r.due, what: r.what, draft: r.draft || x.draft }); return; } // dismissed/done stay that way
  const key = t.msgs.find((m) => m.when === r.at) ?? t.msgs.at(-1);
  items.push({ id, thread: t.id, kind: r.kind, who: r.who || t.conversation, app: t.app, pkg: t.pkg || '', conversation: t.conversation, key: t.key || null,
    what: short(r.what, 80), due: r.due || null, draft: short(r.draft, 200), quote: short(key.text, 160), at: r.at || key.when, created: Date.now(), state: 'open' });
}

// ---------------------------------------------------------------- the clock: snoozes, reminders, calendar
const quiet = (t = Date.now()) => { const h = new Date(t).getHours(); return QS > QE ? h >= QS || h < QE : h >= QS && h < QE; };
const dnd = async () => { const r = await io.rpc('device.status', {}, 5000).catch(() => null); return !!r && r.dnd != null && r.dnd !== 'off'; };
export const budget = () => ({ max: MAX, used: items.filter((x) => x.reminded >= new Date().setHours(0, 0, 0, 0)).length, quiet: quiet() });
export async function tick() {
  const now = Date.now();
  let dirty = false;
  for (const x of items) if (x.state === 'snoozed' && x.until <= now) { x.state = 'open'; dirty = true; }
  // Pushed reminders: my own promises with a due day, from 9 AM that day; overdue by 2+ days = Home card only.
  for (const x of items.filter((y) => y.state === 'open' && y.kind === 'commitment' && y.due && !y.reminded)) {
    const at = new Date(`${x.due}T09:00`).getTime();
    if (now < at || now - at > 2 * DAY) continue;
    if (quiet(now) || budget().used >= MAX || await dnd()) break; // try again next minute (quiet hours end, DND off, tomorrow's budget)
    x.reminded = now; dirty = true;
    const prompt = `Remind me: ${x.what ? `${short(x.what, 60)}. ` : ''}I told ${short(x.who, 30)} on ${x.app}: "${short(x.quote, 100)}". Only show this reminder; do nothing else.`;
    if ((await classify(prompt)).label !== 'benign') continue; // a scheduled task may unlock the phone: guarded text only (the quote is my own words)
    const r = await io.schedule.call('schedule_task', { when: 'in 1 minute', prompt }, { origin: 'proactive' });
    log(`proactive reminder ${x.id}: ${r.ok ? 'scheduled' : r.error}`);
  }
  calCards = calendarCards(now);
  if (dirty) changed();
}
function calendarCards(now) { // conflicts and prep from the memory calendar (the launcher already makes the "Leave for ..." card)
  try {
    if (!readable(db ??= openMemory(), true).includes('calendar')) return [];
    const ev = db.prepare(`SELECT e.title, e.ts AS start, COALESCE(e.ts_end, e.ts + 3600000) AS end, (SELECT COUNT(*) FROM event_entity x WHERE x.event_id=e.id AND x.role='attendee') AS attendees
      FROM event e WHERE e.source='calendar' AND e.kind='calendar' AND e.ts BETWEEN ? AND ?`).all(now - 6 * H, now + DAY).map((e) => ({ ...e, allDay: e.end - e.start >= 23 * H }));
    const { conflict, prep } = D.calendar(ev, now), t = (ms) => new Date(ms).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });
    return [conflict && { id: `cal:conflict:${hash(conflict.map((e) => e.title + e.start).join())}`, title: `${short(conflict[0].title, 22)} overlaps ${short(conflict[1].title, 22)}`, detail: `${t(conflict[1].start)} · tap to sort it out`, icon: 'cal',
      goal: `My calendar has "${conflict[0].title}" (${t(conflict[0].start)}) overlapping "${conflict[1].title}" (${t(conflict[1].start)}). Suggest which to move and draft any message for my approval. Don't change the calendar or send anything without asking.` },
    prep && { id: `cal:prep:${hash(prep.title + prep.start)}`, title: `Prep for ${short(prep.title, 30)}`, detail: `${t(prep.start)} · ${prep.attendees} other${prep.attendees > 1 ? 's' : ''}`, icon: 'cal',
      goal: `Prep me for "${prep.title}" at ${t(prep.start)}: a short brief from my memory (who's coming, recent messages with them, anything I promised them). Read only; don't send anything.` }].filter(Boolean);
  } catch { return []; }
}

// ---------------------------------------------------------------- what the UIs see
const RANK = (x) => (x.kind === 'commitment' && x.due && dayLabel(x.due).match(/overdue|today/) ? 0 : x.kind === 'reply' ? 1 : x.kind === 'request' ? 2 : 3);
function goal(x) {
  const where = `${x.who} in ${x.app}`, ask = "Don't send, pay or change anything without my approval.";
  if (x.kind === 'reply') return `Reply to ${where}. Their message: "${short(x.quote, 140)}".${x.draft ? ` Suggested reply (drafted by the assistant, not dictated by me): "${x.draft}".` : ''} Show me the reply for approval before it is sent.`;
  if (x.kind === 'request') return `${where} asked me: "${short(x.quote, 140)}". Help me do it: do what you can on the phone and draft any message to ${x.who} for my approval. ${ask}`;
  return `I promised ${where}: "${short(x.quote, 140)}". Help me keep it: do what you can on the phone and draft the message to ${x.who} for my approval. ${ask}`;
}
const card = (x) => ({ id: `p:${x.id}`, app: x.pkg || undefined, icon: x.kind === 'reply' ? 'chat' : 'spark', goal: goal(x),
  title: x.kind === 'reply' ? `Reply to ${short(x.who, 24)}?` : x.what ? short(x.what, 40) : x.kind === 'commitment' ? `Your promise to ${short(x.who, 24)}` : `${short(x.who, 24)} asked you`,
  detail: x.kind === 'reply' || !x.what ? `${ago(x.at)}: ${short(x.quote, 44)}` : x.kind === 'request' ? `${short(x.who, 20)} asked · ${x.due ? `due ${dayLabel(x.due)}` : ago(x.at)}` : `You told ${short(x.who, 20)} · ${x.due ? `due ${dayLabel(x.due)}` : ago(x.at)}` });
// Home: calendar first (time-bound), then the 2 most pressing promises. Cards you tapped hide for 2 h (the task is on it).
export const cards = () => !io ? [] : [...calCards, ...items.filter((x) => x.state === 'open' && !(x.acted > Date.now() - 2 * H))
  .sort((a, b) => RANK(a) - RANK(b) || a.at - b.at).slice(0, 2).map(card)].slice(0, 3);
export const list = () => items.filter((x) => x.state === 'open' || x.state === 'snoozed' || x.closed > Date.now() - DAY)
  .sort((a, b) => (a.state !== 'open') - (b.state !== 'open') || RANK(a) - RANK(b) || a.at - b.at)
  .map((x) => ({ ...x, dueLabel: x.due ? dayLabel(x.due) : '', ago: ago(x.at), goal: goal(x) }));

// WebSocket messages from the launcher: {type:"promises.list"} | {type:"promises.update", id, action:"done"|"dismiss"|"snooze"|"open", hours?}
export function handle(m) {
  if (!io) return { type: 'promises', items: [], off: true };
  if (m.type === 'promises.update') {
    const x = items.find((y) => y.id === String(m.id || '').replace(/^p:/, ''));
    if (!x) return { type: 'promises', items: list(), error: 'no such item' };
    if (m.action === 'snooze') Object.assign(x, { state: 'snoozed', until: Date.now() + Math.min(Math.max(+m.hours || 3, 0.25), 168) * H });
    else if (m.action === 'done' || m.action === 'dismiss') Object.assign(x, { state: m.action === 'done' ? 'done' : 'dismissed', closed: Date.now() });
    else if (m.action === 'open') Object.assign(x, { state: 'open', closed: undefined, until: undefined });
    changed();
  }
  return { type: 'promises', items: list(), budget: budget() };
}

// io: {codex, rpc, publish, onEvent, schedule} from server.js.
export function init(deps) {
  io = deps;
  try { items = existsSync(FILE) ? JSON.parse(readFileSync(FILE, 'utf8')) : []; } catch (e) { log('promises.json unreadable', e.message); }
  const soon = (why) => { clearTimeout(debounce); debounce = setTimeout(() => scan(why), 20_000); };
  io.onEvent((e) => {
    if (e.type === 'notification.posted' && e.message) { note(e, e.from_me ? 'me' : e.group ? e.sender : e.conversation); soon('message'); }
    if (e.type === 'audit.append' && e.tool === 'notifications.reply' && e.ok) { // a reply we sent: find its thread by the audit's target
      const t = [...live.values()].find((x) => x.pkg === e.pkg && `Reply to ${x.conversation}` === e.target);
      if (t) { note({ ...t, text: e.text, when: Date.now() }, 'me'); soon('my reply'); }
    }
    if (e.type === 'task.update' && e.task?.goal) for (const x of items) if (x.state === 'open' && goal(x) === e.task.goal && !x.acted) { x.acted = Date.now(); changed(); }
  });
  setInterval(() => scan('memory sync'), 10 * 60_000).unref?.();
  setInterval(() => tick().catch((e) => log(`proactive tick failed: ${e.message}`)), 60_000).unref?.();
  setTimeout(() => { scan('start'); tick().catch(() => {}); }, 15_000).unref?.();
  log(`proactive: on (${items.filter((x) => x.state === 'open').length} open, ${MAX} reminders/day, quiet ${QS}-${QE})`);
}
