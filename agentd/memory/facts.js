// Facts v2: the durable things worth knowing ("Mom lives in Boise", "Sam is vegetarian now", "Joe Smith is my cousin"),
// each a row that cites the event it came from and carries Zep-style validity: valid_from/valid_to say when it held in
// the world, observed_at when we learned it, status active|superseded|retracted. A newer value for a single-valued
// predicate (lives_in, works_at, favorite:coffee place…) closes the old row instead of deleting it, so "where does Mom
// live now?" and "where did she live before?" both have answers. Nothing here is trivia: an OTP, a delivery or "omw"
// never becomes a fact (EPHEMERAL). Two extractors feed the same table: `rules` (free, instant, on the phone) and the
// LLM pass in extract-llm.js (by='llm').
import * as S from './store.js';

const D = 864e5;
export const SINGLE = /^(lives_in|address|works_at|relationship|birthday|diet|rent|favorite:.*)$/; // one value at a time
export const EPHEMERAL = /verification code|\botp\b|package (was|has been)? ?delivered|is arriving|your (table|order|ride|driver|lyft|uber)|% off|off tonight|\bomw\b|on my way|running \d+ min late|^(k|ok|okay|lol|haha|thanks|thx|yes|no|yep|nope)[.!]?$|ignore (all )?previous instructions/i;
const REL = 'mom|mother|dad|father|sister|brother|cousin|wife|husband|partner|girlfriend|boyfriend|boss|manager|landlord|dentist|doctor|roommate|climbing partner|aunt|uncle|grandma|grandpa|best friend|coworker|neighbor';
const NAME = "[A-Z][\\w.'-]+(?: [A-Z][\\w.'-]+)?", PLACE = "[A-Z][\\w'-]+(?:,? [A-Z][\\w'-]+)?";
const cut = (s) => String(s).replace(/\s+/g, ' ').replace(/[\s,.!?]+$/, '').replace(/\s+(btw|though|tho|lol|haha|remember|pls|please)$/i, '').trim();

export function ensure(db) {
  db.exec(`CREATE TABLE IF NOT EXISTS fact(id INTEGER PRIMARY KEY, subj INTEGER NOT NULL REFERENCES entity ON DELETE CASCADE, pred TEXT NOT NULL, obj TEXT NOT NULL,
    obj_entity INTEGER REFERENCES entity ON DELETE SET NULL, kind TEXT NOT NULL, conf REAL NOT NULL, event_id INTEGER REFERENCES event ON DELETE CASCADE, source TEXT NOT NULL,
    by TEXT NOT NULL, observed_at INTEGER NOT NULL, valid_from INTEGER, valid_to INTEGER, status TEXT NOT NULL DEFAULT 'active');
    CREATE INDEX IF NOT EXISTS fact_subj ON fact(subj, pred, status);`);
}

// ---------------------------------------------------------------- rules extractor
// text = what was said; who = {speaker: entity id, owner: bool (the owner wrote it)}. Returns [{subj, pred, obj, kind, conf, retract?}].
export function rules(db, text, { speaker, owner }) {
  if (!text || EPHEMERAL.test(text) || text.length > 600) return [];
  const t = String(text).replace(/\s+/g, ' '), out = [], me = db.me, self = owner ? me : speaker;
  const f = (pred, obj, kind, conf = 0.8, subj = self, extra = {}) => obj && cut(obj).length <= 60 && out.push({ subj, pred, obj: cut(obj), kind, conf, ...extra });
  let m;
  if ((m = t.match(new RegExp(`\\b(?:I|we)(?:'ve| have)? (?:just |finally |recently )?moved (?:to|into) (${PLACE})`)))) f('lives_in', m[1], 'location', 0.9);
  else if ((m = t.match(new RegExp(`\\b(?:I|we) (?:now )?live in (${PLACE})|\\b(?:I'm|we're|I am) (?:now )?living in (${PLACE})`)))) f('lives_in', m[1] || m[2], 'location', 0.85);
  else if ((m = t.match(new RegExp(`\\bmoved\\b[^.!?]*?\\b(?:we're|we are|I'm|I am) (?:now )?in (${PLACE})`)))) f('lives_in', m[1], 'location', 0.85);
  else if ((m = t.match(new RegExp(`\\b(?:come )?visit (?:me|us) (?:in|up in|down in) (${PLACE})`)))) f('lives_in', m[1], 'location', 0.6);
  if ((m = t.match(/\b(?:my|our) new address is ([^.!\n]+)/i))) f('address', m[1], 'location', 0.9);
  if ((m = t.match(/\b(?:my|our) (?:new )?favou?rite ([a-z]+(?: [a-z]+)?) (?:is|are) ([^.!,\n]+?)(?: in [A-Z]\w+)?(?=[.!,]|$)/i))) f(`favorite:${m[1].toLowerCase()}`, m[2], 'preference', 0.9);
  if ((m = t.match(/\b(.+?) is my (?:new )?favou?rite ([a-z]+(?: [a-z]+)?)(?: in [A-Z]\w+)?\b/))) f(`favorite:${m[2].toLowerCase()}`, m[1].replace(/^.*\b(honestly|btw|ok|so)\s+/i, ''), 'preference', 0.9);
  if ((m = t.match(/\bI(?:'m| am) allergic to ([^.!,\n]+?)(?= remember| so\b|,|\.|!|$)/i))) f('allergic_to', m[1], 'attribute', 0.95);
  if ((m = t.match(/\bI(?:'m| am) (?:(not|no longer) )?(vegetarian|vegan|gluten[- ]free|pescatarian|keto|dairy[- ]free)( now| anymore)?\b/i)))
    m[1] || /anymore/.test(m[3] || '') ? out.push({ subj: self, pred: 'diet', obj: m[2].toLowerCase(), kind: 'attribute', conf: 0.9, retract: true }) : f('diet', m[2].toLowerCase(), 'attribute', 0.9);
  if ((m = t.match(/\bI (?:just )?(?:started|got a job|am starting|start(?:ed)? working|now work|work) at ([A-Z][\w&]+(?: [A-Z][\w&]+)?)/))) f('works_at', m[1], 'attribute', 0.9);
  else if ((m = t.match(/\b(?:late night|long day|busy day|back) at ([A-Z][\w&]+)\b(?!.*\b(?:restaurant|bar|cafe|gym)\b)/))) f('works_at', m[1], 'attribute', 0.55);
  if ((m = t.match(/\bI retired from ([A-Z][\w&]+)/))) out.push({ subj: self, pred: 'works_at', obj: m[1], kind: 'attribute', conf: 0.9, retract: true }), f('status', `retired (from ${m[1]})`, 'attribute', 0.9);
  if (owner) for (const re of [new RegExp(`\\b(?:my|our) (${REL}) is (${NAME})`, 'i'), new RegExp(`\\b(${NAME}) is my (${REL})\\b`, 'i')]) if ((m = t.match(re))) {
    const [rel, name] = re.source.startsWith('\\b(?:my') ? [m[1], m[2]] : [m[2], m[1]];
    const who = db.prepare(`SELECT id FROM entity WHERE type='person' AND name=? COLLATE NOCASE`).get(name)?.id;
    if (who) f('relationship', rel.toLowerCase(), 'relationship', 0.95, who, { obj_entity: me });
    f(`${rel.toLowerCase()}_name`, name, 'relationship', 0.9, me, { obj_entity: who });
  }
  if ((m = t.match(/\b(?:I'm|I am|we're|we are) (?:working on|building|writing|training for) ([^.!,\n]+?)(?= next | this | for the next|,|\.|!|$)/i))) f('project', m[1], 'project', 0.8);
  if ((m = t.match(/\bfor my birthday I (?:just |really )?want (?:the |a |an )?([^.!,\n🙏]+)/i))) f('wants', m[1], 'preference', 0.9);
  if ((m = t.match(/\bmy birthday is (?:on )?((?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\w* \d{1,2})/i))) f('birthday', m[1], 'attribute', 0.95);
  if ((m = t.match(/\b((?:\w+ )?(?:trip|flight|wedding|vacation|reunion) to [A-Z][\w ]+?) is (?:on|confirmed|booked|happening)[.!]?\s*((?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\w* \d{1,2}(?:-\d{1,2})?)?/i))) f('plan', m[2] ? `${m[1]}, ${m[2]}` : m[1], 'plan', 0.85, me);
  if ((m = t.match(/\bI prefer ([^.!,\n]+)/i))) f('prefers', m[1], 'preference', 0.85);
  if ((m = t.match(/\bI take my (\w+) with ([^.!,\n]+)/i))) f('prefers', `${m[1]} with ${m[2]}`, 'preference', 0.85);
  if ((m = t.match(/\bI (?:really |absolutely )?(?:love|adore|am into|am obsessed with) ([^.!,\n]+?)(?=,|\.|!|$)/i)) && !/\byou\b/.test(m[1])) f('likes', m[1], 'preference', 0.6);
  if ((m = t.match(/\bI (?:hate|can't stand|don't like|dislike) ([^.!,\n]+?)(?= anymore|,|\.|!|$)/i)) && !/\byou\b/.test(m[1])) f('dislikes', m[1], 'preference', 0.6);
  if ((m = t.match(/\brent (?:goes (?:up )?to|is(?: now)?|will be|increases to) \$?([\d,]+(?:\/month| a month| per month)?)(?: starting ([A-Z]\w+ \d+))?/i))) f('rent', `$${m[1].replace(/\$/, '')}${m[2] ? ` from ${m[2]}` : ''}`, 'attribute', 0.9, me);
  if ((m = t.match(/\bI(?:'m| am) a (?:\w+ )?(engineer|designer|nurse|teacher|lawyer|doctor|student|pilot|chef)\b/i))) f('occupation', m[1].toLowerCase(), 'attribute', 0.8);
  return out;
}

// ---------------------------------------------------------------- writing (add / supersede / retract)
export function put(db, c, { event_id = null, source = 'user', by = 'rules', observed_at = Date.now(), valid_from = observed_at } = {}) {
  ensure(db);
  const act = db.prepare(`SELECT * FROM fact WHERE subj=? AND pred=? AND status='active'`).all(c.subj, c.pred);
  if (c.retract) { // "I'm not vegetarian anymore", "I retired from Delta": close the matching open facts
    let n = 0;
    for (const a of act) if (!c.obj || a.obj.toLowerCase() === c.obj.toLowerCase() || SINGLE.test(c.pred)) n += db.prepare(`UPDATE fact SET status='retracted', valid_to=? WHERE id=?`).run(valid_from, a.id).changes;
    return { retracted: n };
  }
  const dup = act.find((a) => a.obj.toLowerCase() === c.obj.toLowerCase());
  if (dup) { if (c.conf > dup.conf) db.prepare('UPDATE fact SET conf=?, event_id=coalesce(event_id,?) WHERE id=?').run(c.conf, event_id, dup.id); return { id: dup.id, dup: true }; }
  const id = db.prepare(`INSERT INTO fact(subj,pred,obj,obj_entity,kind,conf,event_id,source,by,observed_at,valid_from,status) VALUES(?,?,?,?,?,?,?,?,?,?,?,'active')`)
    .run(c.subj, c.pred, c.obj, c.obj_entity ?? null, c.kind, c.conf, event_id, source, by, observed_at, valid_from).lastInsertRowid;
  if (SINGLE.test(c.pred)) { // one value at a time: the newer (by valid_from) stays active, the other is superseded
    const newer = act.filter((a) => (a.valid_from ?? 0) > valid_from);
    if (newer.length) db.prepare(`UPDATE fact SET status='superseded', valid_to=? WHERE id=?`).run(Math.min(...newer.map((a) => a.valid_from)), id);
    else for (const a of act) db.prepare(`UPDATE fact SET status='superseded', valid_to=? WHERE id=?`).run(valid_from, a.id);
  }
  return { id: Number(id) };
}

// Extract from one event (messages, notes) with the rules. Who spoke: role 'from' person; the owner for 'to'/notes.
export function fromEvent(db, e) {
  if (!/^(message|note)$/.test(e.kind)) return 0;
  const ps = db.prepare('SELECT entity_id id, role FROM event_entity WHERE event_id=?').all(e.id);
  const from = ps.find((p) => p.role === 'from')?.id, owner = e.kind === 'note' || !from;
  const speaker = owner ? db.me : from;
  if (!owner && db.prepare('SELECT type FROM entity WHERE id=?').get(from)?.type !== 'person') return 0;
  let n = 0;
  for (const c of rules(db, e.body, { speaker, owner })) { put(db, c, { event_id: e.id, source: e.source, observed_at: e.ts, valid_from: e.ts }); n++; }
  return n;
}

// Incremental: everything since the last extracted event id (meta wm:facts). ~0.1 ms per message. Returns {events, facts, ms}.
export function sync(db, { force = false } = {}) {
  ensure(db);
  const t0 = performance.now(), wm = force ? 0 : +(db.prepare(`SELECT v FROM meta WHERE k='wm:facts'`).get()?.v || 0);
  if (force) db.exec(`DELETE FROM fact WHERE by='rules'`);
  const rows = db.prepare(`SELECT * FROM event WHERE id > ? AND kind IN ('message','note') ORDER BY ts, id`).all(wm);
  let facts = 0, last = wm;
  const body = () => { for (const e of rows) { facts += fromEvent(db, e); last = Math.max(last, e.id); }
    db.prepare(`INSERT OR REPLACE INTO meta VALUES('wm:facts',?)`).run(String(db.prepare('SELECT max(id) m FROM event').get().m || last)); };
  db.isTransaction ? body() : S.tx(db, body); // noteMessage already holds a transaction
  return { events: rows.length, facts, ms: +(performance.now() - t0).toFixed(2) };
}

// ---------------------------------------------------------------- reading
const when = (t) => new Date(t).toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
export const PRED_TEXT = { lives_in: 'lives in', address: 'address', works_at: 'works at', relationship: 'is your', birthday: 'birthday', diet: 'diet', allergic_to: 'allergic to',
  project: 'working on', wants: 'wants', plan: 'plan', prefers: 'prefers', likes: 'likes', dislikes: 'dislikes', rent: 'rent', status: 'status', occupation: 'is a' };
export function say(f) { const p = f.pred.startsWith('favorite:') ? `favorite ${f.pred.slice(9)}` : f.pred.endsWith('_name') ? `${f.pred.slice(0, -5)} is` : PRED_TEXT[f.pred] || f.pred.replace(/_/g, ' '); return `${p}: ${f.obj}`; }

// facts(db, {about:[ids], q, history, cloud, srcs}) -> [{id, subj, name, pred, obj, text, since, until, status, event_id, by}]
export function list(db, { about = [], q, history = false, cloud = true, srcs, limit = 40 } = {}) {
  ensure(db);
  const where = [history ? `status != 'retracted'` : `status='active'`], args = [];
  if (about.length) where.push(`subj IN (${about.map(() => '?').join(',')})`), args.push(...about);
  if (srcs) where.push(`source IN (${srcs.map(() => '?').join(',')})`), args.push(...srcs);
  const words = q ? String(q).toLowerCase().match(/[a-z][a-z']+/g)?.filter((w) => w.length > 2) || [] : [];
  let rows = db.prepare(`SELECT f.*, e.name FROM fact f JOIN entity e ON e.id=f.subj WHERE ${where.join(' AND ')} ORDER BY status='active' DESC, observed_at DESC LIMIT ?`).all(...args, words.length ? 500 : limit);
  if (words.length) rows = rows.filter((f) => { const s = `${f.pred} ${say(f)}`.toLowerCase(); return words.some((w) => s.includes(w)); }).slice(0, limit);
  const red = (s) => (cloud ? S.redact(db, s) : s);
  return rows.map((f) => ({ id: f.id, subj: f.subj, name: f.name === 'Me' ? 'you' : red(f.name), pred: f.pred, obj: red(f.obj), text: `${f.name === 'Me' ? 'You' : red(f.name)} ${say({ ...f, obj: red(f.obj) })}${f.status === 'active' ? (f.valid_from && SINGLE.test(f.pred) ? ` (since ${when(f.valid_from)})` : '') : ` (${f.status === 'superseded' ? 'until' : 'retracted'} ${when(f.valid_to)})`}`,
    since: f.valid_from, until: f.valid_to, status: f.status, event_id: f.event_id, by: f.by, conf: f.conf, kind: f.kind, source: f.source, at: f.observed_at }));
}

// Owner controls: edit a fact's value, or retract it (kept as a row, never shown again).
export function correct(db, id, { obj, retract } = {}) {
  ensure(db);
  if (retract) return { ok: db.prepare(`UPDATE fact SET status='retracted', valid_to=? WHERE id=?`).run(Date.now(), id).changes === 1 };
  if (obj) return { ok: db.prepare(`UPDATE fact SET obj=?, by='owner', conf=1 WHERE id=?`).run(cut(obj), id).changes === 1 };
  return { ok: false };
}

// "About you": a compact digest of active facts (Letta-style core block), capped by a token budget. Lines are grouped:
// you first, then people with what they are to you. `names` maps a canonical id to a display name (brain.js passes it).
export function about(db, { cloud = true, budget = 300, names = (id, n) => n, canon = (id) => id } = {}) {
  const rows = list(db, { cloud, limit: 200 }), by = new Map();
  for (const f of rows) { const k = f.subj === db.me ? db.me : canon(f.subj); (by.get(k) ?? by.set(k, []).get(k)).push(f); }
  const lines = [], chunk = (fs) => fs.map((f) => say(f).replace(/^is your: /, 'your ')).join('; ');
  if (by.get(db.me)) lines.push(`You: ${chunk(by.get(db.me))}`);
  const others = [...by].filter(([k]) => k !== db.me).sort((a, b) => (b[1].some((f) => f.pred === 'relationship') - a[1].some((f) => f.pred === 'relationship')) || b[1].length - a[1].length);
  for (const [k, fs] of others) lines.push(`${names(k, fs[0].name)}: ${chunk(fs)}`);
  const out = []; let used = 0;
  for (const l of lines) { const t = Math.ceil(l.length / 4); if (used + t > budget) break; out.push(l); used += t; }
  return { lines: out, tokens: used, total: lines.length };
}
