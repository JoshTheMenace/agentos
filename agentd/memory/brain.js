// The memory "brain": turns a request into the few memories that answer it. Three steps, like a good assistant:
//  1. plan: read the request for people ("Sam", "Mom", "the person I..."), time ("this morning", "this week"), what kind
//     of thing (song, call, page, photo) and what is asked (who / when free / how many / reply to whom);
//  2. recall: hybrid ranking = keyword match (FTS5 BM25) + person match (across every app the person uses, see
//     resolve.js) + time window + kind + recency + how close the person is;
//  3. answer: small exact answers code can compute itself (free slots, the thread "reply back" means, who you talk to most,
//     counts), each cited to its source events.
// Everything respects consent (only readable sources) and the cloud view (redacted text, [P:n]/[E:n]).
// No embeddings: on a 3.5 GB phone with ~1 GB free, a second model costs more RAM and latency than it buys here;
// synonyms + prefix search + people/time planning carry the eval (memory/eval). Swap in vectors behind recall() later.
import * as S from './store.js';
import { groups, relink } from './resolve.js';
import { profile as derive } from './profile.js';
import * as identity from './identity.js';
import * as F from './facts.js';

const H = 36e5, D = 864e5;
const STOP = new Set(('a an the i me my mine you your yours he him his she her hers it its we us our they them their what whats which who whom whose when where why how ' +
  'is are was were be been being do does did done have has had having can could would should will shall may might must to of in on at for from by with about into over ' +
  'and or but if then than so that this these those there here up down out off again just only also very really any some all most more much many few ' +
  'tell say said ask asked let know get got give make like want wanted thing things stuff someone person guy girl people whoever one ones else ' +
  'am pm min mins minute minutes hour hours time back please hey ok okay yes no not dont im ive id ill ' +
  'talk talking talked text texted texting message messaged messages send sent wrote write call called calling remind reminder number').split(' '));
const NOT_NAMES = new Set('may will june april august mark rose bill pat art grace hope joy sky summer sunny faith dawn jack jean'.split(' '));
const SYN = { birthday: ['bday', 'birthday'], bday: ['birthday'], gift: ['want', 'birthday'], present: ['want', 'gift'], wine: ['malbec', 'merlot', 'cabernet', 'pinot', 'wine', 'rose'],
  late: ['omw', 'coffee'], dentist: ['dental', 'dentist'], doctor: ['dr', 'clinic', 'appointment'], flight: ['flight', 'airport', 'airline'],
  climbing: ['climb', 'climbing'], trip: ['trip', 'travel'], rent: ['rent', 'lease', 'landlord'], landlord: ['rent', 'lease'],
  restaurant: ['restaurant', 'brunch', 'dinner', 'lunch', 'try'], recommend: ['try', 'recommend', 'best'], driving: ['drive', 'driving'], drive: ['drive'],
  bike: ['bike', 'bicycle', 'cycle'], article: ['http', 'https', 'check', 'article'], address: ['ave', 'street', 'st'], song: [], music: [], podcast: ['podcast'],
  thanksgiving: ['thanksgiving'], invite: ['invite', 'invited', 'welcome', 'night'], invited: ['invite', 'night', 'welcome'] };
const REL = { mom: ['mom', 'mother', 'mum', 'mama'], mother: ['mom', 'mother'], mum: ['mom', 'mum'], dad: ['dad', 'father', 'papa'], father: ['dad', 'father'],
  landlord: ['landlord'], dentist: ['dentist', 'dr', 'dental'], boss: ['boss', 'manager'], wife: ['wife'], husband: ['husband'] };
const FACETS = { // what kind of memory: kinds/sources it maps to, and the words that ask for it
  listen: { re: /\b(songs?|music|listen(ed|ing)?|tracks?|albums?|artists?|playlists?|podcasts?|playing|played)\b/, kinds: ['listen'], sources: ['media'] },
  web: { re: /\b(look(ed)? up|search(ed)?|googled?|website|web ?page|browsing|recipe|airbnb|online|chrome)\b/, kinds: ['web'], sources: ['chrome'] },
  usage: { re: /\b(screen ?time|used most|most used|use most|which apps?|how (long|much time) (was i|did i spend|have i spent))\b/, kinds: ['usage'], sources: ['usage'] },
  photo: { re: /\b(photos?|pictures?|pics|screenshots?|videos?)\b/, kinds: ['media'], sources: ['photos'] },
  call: { re: /\b(called|calls?|phoned|rang|missed|talk(ed)? to .* on the phone)\b/, kinds: ['call'], sources: ['calllog'] },
  calendar: { re: /\b(meetings?|calendar|schedule|free|busy|appointments?|events?|flights?|plans?|dinner|lunch|coming up|next)\b/, kinds: ['calendar'], sources: ['calendar'] },
  message: { re: /\b(say|said|text(ed|s)?|messages?d?|told|ask(ed)?|wrote|sent|send|reply|replied|recommend(ed)?|talk(ed|ing)?|chat|group)\b/, kinds: ['message', 'media'], sources: [] },
};
const APPS = { discord: /discord/i, slack: /slack/i, whatsapp: /whatsapp/i, messenger: /messenger|facebook\.orca/i, instagram: /instagram/i,
  telegram: /telegram/i, signal: /signal/i, spotify: /spotify/i, chrome: /chrome/i, sms: /^Messages$|sms|mms/i };
const ACTION = /^(please )?(text|message|call|email|reply|respond|tell|send|remind|write|dm|ping|invite)\b/;

// ---------------------------------------------------------------- caches (rebuilt when the data or consent changes)
const caches = new WeakMap();
function cache(db, cloud = true) {
  // People, links and closeness change slowly: rebuild on new cards/aliases/consent, or every 50 new events (~200 ms on the
  // phone with ~1,300 events), not on every incoming message.
  if (S.flags.v2) { identity.ensure(db); F.ensure(db); }
  const sig = db.prepare(`SELECT ((SELECT max(id) FROM event)/50)||'/'||(SELECT count(*) FROM alias)||'/'||(SELECT count(*) FROM entity)||'/'||
    (SELECT max(id) FROM consent)||'/'||(SELECT count(*) FROM event)/50${S.flags.v2 ? "||'/'||(SELECT count(*) FROM identity_evidence)||'/'||(SELECT count(*) FROM fact)" : ''} AS s`).get().s;
  const srcs = S.readable(db, cloud), IN = srcs.map(() => '?').join(',');
  const per0 = caches.get(db) ?? caches.set(db, {}).get(db);
  let c = per0[cloud];
  if (c?.sig === sig) return c;
  let g;
  if (S.flags.v2) { // identity.js: SAME links merge; MAYBE links are followed for reads (soft) but never for actions (hard)
    identity.relink(db); const ig = identity.groups(db);
    g = { canon: ig.softCanon, members: ig.soft, hard: ig.canon, hardMembers: ig.members, maybe: ig.maybe, status: ig.status };
  } else { relink(db); g = groups(db); }
  const close = new Map(); // canon id -> closeness 0..1 (how much and how recently you deal with them, over every app)
  // closeness = how much you deal with someone, per channel (log-scaled so one chatty channel can't dominate), fading
  // over a month: a 1:1 text counts 1.2, a call 1.5, a group-chat line 0.15, a shared calendar event 0.3, a contact card 0.5
  const W = { message: 1.2, call: 1.5, calendar: 0.3 }, per = new Map();
  for (const r of db.prepare(`SELECT ee.entity_id id, e.kind, e.ts, EXISTS(SELECT 1 FROM event_entity g WHERE g.event_id=e.id AND g.role='group') grp
    FROM event e JOIN event_entity ee ON ee.event_id=e.id AND ee.role IN ('from','to','attendee') WHERE e.ts > ? AND e.source IN (${IN})`).all(Date.now() - 180 * D, ...srcs)) {
    const k = `${g.canon(r.id)}|${r.kind}|${r.grp}`, w = (r.grp ? 0.15 : W[r.kind] ?? 0.5) * (0.4 + Math.exp(-Math.abs(Date.now() - r.ts) / (30 * D)));
    per.set(k, (per.get(k) || 0) + w);
  }
  for (const [k, v] of per) { const id = +k.split('|')[0]; close.set(id, (close.get(id) || 0) + Math.log1p(v)); }
  for (const r of db.prepare(`SELECT DISTINCT entity_id id FROM alias WHERE source='contacts' AND source IN (${IN})`).all(...srcs)) close.set(g.canon(r.id), (close.get(g.canon(r.id)) || 0) + 0.4);
  const maxC = Math.max(1, ...close.values());
  for (const [k, v] of close) close.set(k, v / maxC);
  const names = new Map(), raw = new Map(); // token or full name -> Set(canon ids); raw: -> Set(card ids), for "which Joe?" (actions)
  const add = (key, id, rid) => { if (key && key.length > 1) { (names.get(key) ?? names.set(key, new Set()).get(key)).add(id); if (rid) (raw.get(key) ?? raw.set(key, new Set()).get(key)).add(rid); } };
  const people = db.prepare(`SELECT id, name, type FROM entity WHERE type IN ('person','group') AND name NOT LIKE 'Unknown%'`).all();
  for (const p of people) {
    const id = g.canon(p.id), n = norm(p.name).replace(/\s*\b(discord|slack|messenger|whatsapp|instagram|telegram|signal|notifications|calendar)$/, ''), t = n.split(' ').filter(Boolean);
    add(n, id, p.id); if (p.type === 'person' && t.length > 1 && !NOT_NAMES.has(t[0])) add(t[0], id, p.id); // groups only by their full name
    for (const r of Object.entries(REL)) if (r[1].includes(n)) for (const w of r[1]) add(w, id);
  }
  for (const a of db.prepare(`SELECT entity_id, value FROM alias WHERE kind NOT IN ('phone','email','chat') AND source IN (${IN})`).all(...srcs)) add(norm(a.value), g.canon(a.entity_id), a.entity_id);
  c = { sig, g, close, names, raw, srcs, IN, name: new Map(db.prepare('SELECT id, name, type FROM entity').all().map((e) => [e.id, e])) };
  per0[cloud] = c;
  return c;
}
const norm = (s) => String(s || '').normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[’']s\b/g, '').replace(/[^\p{L}\p{N}\s]/gu, ' ').replace(/\s+/g, ' ').trim();
// word variants for exact FTS match: driving -> driv, drive (never a prefix: "drive"* would hit "Lyft driver")
const stems = (w) => { const b = w.length > 5 ? w.replace(/(ing|ed)$/, '') : w; return [...new Set([w, b, b + 'e', w.length > 3 ? w.replace(/(es|s)$/, '') : w])].filter((x) => x.length > 2); };

// ---------------------------------------------------------------- 1. plan
const day0 = (t, add = 0) => { const d = new Date(t); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() + add); return d.getTime(); };
const PARTS = { morning: [5, 12], afternoon: [12, 17], evening: [17, 22], night: [18, 28], tonight: [17, 28] };
const WD = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];
export function timeWindow(q, now = Date.now()) {
  const past = /\b(did|was|were|went|had|took|said|sent|asked|talked|called|listened|looked|last|ago|earlier|played)\b/.test(q) && !/\bnext\b/.test(q);
  const mon = day0(now, -((new Date(now).getDay() + 6) % 7));
  const part = (d, p) => [d + PARTS[p][0] * H, d + PARTS[p][1] * H];
  let m;
  if (/\blast night\b/.test(q)) return { since: day0(now, -1) + 18 * H, until: day0(now) + 4 * H, label: 'last night' };
  if ((m = q.match(/\bthis (morning|afternoon|evening)\b|\b(tonight)\b/))) { const [s, u] = part(day0(now), m[1] || m[2]); return { since: s, until: u, label: m[0] }; }
  if ((m = q.match(/\b(yesterday|today|tomorrow)(?: (morning|afternoon|evening|night))?\b/))) {
    const d = day0(now, { yesterday: -1, today: 0, tomorrow: 1 }[m[1]]);
    const [s, u] = m[2] ? part(d, m[2]) : [d, d + D];
    return { since: s, until: u, label: m[0] };
  }
  if (/\bthis weekend\b/.test(q)) return { since: mon + 5 * D, until: mon + 7 * D, label: 'this weekend' };
  if (/\bthis week\b/.test(q)) return { since: past ? mon : Math.max(mon, now), until: past ? now : mon + 7 * D, label: 'this week' };
  if (/\blast week\b/.test(q)) return { since: mon - 7 * D, until: mon, label: 'last week' };
  if (/\bnext week\b/.test(q)) return { since: mon + 7 * D, until: mon + 14 * D, label: 'next week' };
  if ((m = q.match(/\b(last |next |this |on )?(sun|mon|tues|wednes|thurs|fri|satur)day\b/))) {
    const w = WD.findIndex((d) => d.startsWith(m[2])), today = new Date(now).getDay();
    const back = m[1] === 'last ' || (past && m[1] !== 'next ');
    const d = back ? day0(now, -(((today - w + 7) % 7) || 7)) : day0(now, ((w - today + 7) % 7) + (m[1] === 'next ' && w === today ? 7 : 0));
    return { since: d, until: d + D, label: m[0] };
  }
  if ((m = q.match(/\b(\d+|a|one|two|three) (day|week)s? ago\b/))) {
    const n = { a: 1, one: 1, two: 2, three: 3 }[m[1]] ?? +m[1], d = day0(now, -n * (m[2] === 'week' ? 7 : 1));
    return { since: d - (m[2] === 'week' ? 3 * D : 0), until: d + (m[2] === 'week' ? 4 : 1) * D, label: m[0] };
  }
  if (/\b(recently|lately|these days)\b/.test(q)) return { since: now - 7 * D, until: now, label: 'recently' };
  return null;
}

export function plan(db, question, now = Date.now(), cloud = true) {
  const c = cache(db, cloud), q = norm(question), raw = String(question);
  const words = q.replace(ACTION, '').split(' ').filter(Boolean);
  const window = timeWindow(q, now);
  const facets = Object.keys(FACETS).filter((f) => FACETS[f].re.test(q.replace(ACTION, '')));
  const apps = Object.keys(APPS).filter((a) => new RegExp(`\\b${a}\\b`).test(q.replace(ACTION, '')));
  // people: full names / aliases first (longest n-gram), then first names; ambiguous names split the score
  const people = new Map(), used = new Set(), keys = [];
  for (let n = 3; n >= 1; n--) for (let i = 0; i + n <= words.length; i++) {
    if ([...Array(n).keys()].some((k) => used.has(i + k))) continue;
    const key = words.slice(i, i + n).join(' ');
    if (n === 1 && (STOP.has(key) || NOT_NAMES.has(key) && !/[A-Z]/.test(raw.match(new RegExp(`\\b${key}\\b`, 'i'))?.[0]?.[0] || ''))) continue;
    const ids = c.names.get(key);
    if (!ids) continue;
    for (let k = 0; k < n; k++) used.add(i + k);
    keys.push(key);
    const list = [...ids], tot = list.reduce((s, id) => s + 0.2 + (c.close.get(id) || 0), 0);
    for (const id of list) people.set(id, Math.max(people.get(id) || 0, list.length === 1 ? 1 : (0.2 + (c.close.get(id) || 0)) / tot, n > 1 ? 1 : 0));
  }
  const terms = new Set();
  words.forEach((w, i) => {
    if (used.has(i) || STOP.has(w) || /^\d+$/.test(w) && w.length < 3) return;
    if (/^(yesterday|today|tomorrow|tonight|morning|afternoon|evening|night|week|weekend|last|next|this|ago|recently|lately)$/.test(w) || WD.includes(w)) return;
    terms.add(w); for (const s of SYN[w] || []) terms.add(s);
  });
  for (const a of apps) terms.delete(a);
  const intent = /\b(free|available|availability)\b/.test(q) ? 'free'
    : /\b(reply|respond|text back|write back|let (them|him|her) know)\b|\bi m (running )?(\d+ \w+ )?late\b|\b(omw|on my way)\b/.test(q) && !people.size ? 'reply'
    : /\bhow many\b/.test(q) ? 'count'
    : /\b(most|the most)\b/.test(q) && (apps.length || facets.includes('usage') || /\btalk|text|message|chat/.test(q)) ? 'most'
    : /\b(next|coming up|upcoming)\b/.test(q) && facets.includes('calendar') ? 'next'
    : /\bwhen did i last\b|\blast (talk|spoke|saw|heard|texted|called)\b/.test(q) ? 'last'
    : /^(who|whoever)\b|\b(whoever|the (person|guy|girl|one) (who|that|i))\b|\bwho s\b/.test(q) || (ACTION.test(q) && !people.size) ? 'who' : null;
  const unknown = /\b(unknown|random|unsaved|new) (number|caller|sender)\b|\bnumber i don t know\b/.test(q);
  if (unknown) for (const w of ['unknown', 'random', 'unsaved', 'new', 'caller', 'sender']) terms.delete(w);
  return { q, window, facets, apps, unknown, people: [...people].map(([id, score]) => ({ id, score })).sort((a, b) => b.score - a.score), terms: [...terms], intent, now, action: ACTION.test(q), keys };
}

// ---------------------------------------------------------------- 2. recall
const LABEL = { orca: 'Messenger', katana: 'Facebook', deskclock: 'Clock', messaging: 'Messages', dialer: 'Phone', gm: 'Gmail', youtube: 'YouTube',
  calendar: 'Calendar', photos: 'Photos', maps: 'Maps', chrome: 'Chrome', vending: 'Play Store', contacts: 'Contacts' };
export const pretty = (name) => { // app package -> label: com.instagram.android -> Instagram, local.agentos.pigeon -> Pigeon
  if (!/^[a-z][\w]*(\.[\w]+)+$/i.test(name || '')) return name;
  const parts = name.split('.').filter((p) => !/^(com|org|net|android|app|apps|google|mobile|music|client|lite|local|agentos|launcher)$/i.test(p));
  const last = parts.at(-1) || name.split('.').at(-1);
  return LABEL[last.toLowerCase()] || last[0].toUpperCase() + last.slice(1);
};
const facetOf = (e) => Object.keys(FACETS).filter((f) => FACETS[f].kinds.includes(e.kind) && (!FACETS[f].sources.length || FACETS[f].sources.includes(e.source))
  || FACETS[f].sources.includes(e.source));

export function recall(db, question, { cloud = true, k = 8, now = Date.now() } = {}) {
  const c = cache(db, cloud), p = plan(db, question, now, cloud), srcs = S.readable(db, cloud), IN = srcs.map(() => '?').join(',');
  const cand = new Map(), bump = (e, key, v) => { const x = cand.get(e.id) ?? cand.set(e.id, { e, s: {} }).get(e.id); x.s[key] = Math.max(x.s[key] || 0, v); };
  // keyword match
  if (p.terms.length && db.fts !== 'none') {
    const match = p.terms.flatMap((w) => [`"${w}"${w.length >= 4 ? '*' : ''}`, ...stems(w).filter((x) => x !== w).map((x) => `"${x}"`)]).join(' OR ');
    const rows = db.prepare(`SELECT e.*, ${db.fts === 'fts5' ? 'bm25(event_fts)' : '-1'} AS bm FROM event_fts JOIN event e ON e.id=event_fts.rowid
      WHERE event_fts MATCH ? AND e.source IN (${IN}) ORDER BY bm LIMIT 80`).all(match, ...srcs);
    const best = Math.min(-1e-9, ...rows.map((r) => r.bm));
    for (const r of rows) bump(r, 'words', Math.min(1, r.bm / best));
    // a hit inside a conversation lifts the messages around it ("I can drive" sits next to "trip to Red Rock")
    for (const r of rows.filter((x) => x.kind === 'message').slice(0, 8)) {
      for (const n of db.prepare(`SELECT DISTINCT e.* FROM event e JOIN event_entity a ON a.event_id=e.id JOIN event_entity b ON b.entity_id=a.entity_id AND b.role=a.role AND b.event_id=?
        WHERE a.role IN ('from','to','group') AND e.kind='message' AND e.id!=? AND e.ts BETWEEN ? AND ? AND e.source IN (${IN}) LIMIT 6`).all(r.id, r.id, r.ts - 3 * H, r.ts + 3 * H, ...srcs))
        bump(n, 'thread', 0.7 * Math.min(1, r.bm / best));
    }
  }
  if (p.facets.includes('calendar') && !p.window || p.intent === 'next') // what's coming up
    for (const r of db.prepare(`SELECT e.* FROM event e WHERE e.kind='calendar' AND e.source IN (${IN}) AND e.ts >= ? ORDER BY e.ts LIMIT 8`).all(...srcs, now - 15 * 60000))
      bump(r, p.intent === 'next' ? 'when' : 'soon', p.intent === 'next' ? 1 : 0.3);
  // person match: every event of every card of the person
  const who = p.people.filter((x) => x.score >= 0.2).slice(0, 4);
  for (const { id, score } of who) {
    const ids = c.g.members(id);
    for (const r of db.prepare(`SELECT e.* FROM event e JOIN event_entity ee ON ee.event_id=e.id WHERE ee.entity_id IN (${ids.map(() => '?').join(',')})
      AND e.source IN (${IN}) ORDER BY abs(e.ts - ?) LIMIT 60`).all(...ids, ...srcs, now)) bump(r, 'person', score);
  }
  // time window / kind / app
  const fk = p.facets.flatMap((f) => FACETS[f].kinds), fs = p.facets.flatMap((f) => FACETS[f].sources);
  if (p.window || (p.facets.length && !p.terms.length) || p.apps.length) {
    const where = [`e.source IN (${IN})`], args = [...srcs];
    if (p.window) where.push('e.ts >= ? AND e.ts < ?'), args.push(p.window.since, p.window.until);
    if (p.facets.length && !p.facets.includes('message')) where.push(`(e.kind IN (${fk.map(() => '?')}) OR e.source IN (${fs.map(() => '?').join(',') || "''"}))`), args.push(...fk, ...fs);
    for (const r of db.prepare(`SELECT e.* FROM event e WHERE ${where.join(' AND ')} ORDER BY abs(e.ts - ?) LIMIT 120`).all(...args, now)) bump(r, 'when', 1);
  }
  if (!cand.size) for (const r of db.prepare(`SELECT e.* FROM event e WHERE e.source IN (${IN}) ORDER BY e.ts DESC LIMIT 20`).all(...srcs)) bump(r, 'recent', 0.1);

  const parts = (id) => db.prepare('SELECT entity_id id, role FROM event_entity WHERE event_id=?').all(id);
  const scored = [...cand.values()].map(({ e, s }) => {
    const ps = parts(e.id), f = facetOf(e);
    const appHit = p.apps.length && ps.some((x) => x.role === 'app' || x.role === 'player' || x.role === 'used'
      ? p.apps.some((a) => APPS[a].test(c.name.get(x.id)?.name || '')) : false) || p.apps.some((a) => APPS[a].test(e.source) || APPS[a].test(e.title || ''));
    const inWin = p.window ? e.ts >= p.window.since && e.ts < p.window.until : null;
    const age = Math.abs(now - e.ts);
    const unk = p.unknown && ps.some((x) => /^Unknown/.test(c.name.get(x.id)?.name || '') && c.g.canon(x.id) === x.id);
    const score = 1.3 * Math.max(s.words || 0, s.thread || 0) + 1.1 * (s.person || 0) + (unk ? 1.2 : 0) + (s.soon || 0) + (p.facets.some((x) => f.includes(x)) ? 0.6 : 0) + (appHit ? 0.8 : p.apps.length ? -0.4 : 0)
      + (inWin === true ? 0.8 : inWin === false ? -1.2 : 0) + 0.35 * Math.exp(-age / (5 * D))
      + 0.2 * Math.max(0, ...ps.map((x) => c.close.get(c.g.canon(x.id)) || 0)) - (e.kind === 'notification' ? 0.3 : 0) + (e.trusted ? 0.1 : 0)
      - (!p.facets.includes('listen') && e.kind === 'listen' ? 0.3 : 0) - (!p.facets.includes('usage') && e.kind === 'usage' ? 0.4 : 0);
    return { e, ps, score: p.intent === 'next' && e.kind === 'calendar' && e.ts >= now - 15 * 60000 ? score + 1 - (e.ts - now) / (7 * D) : score, why: Object.keys(s).concat(appHit ? ['app'] : [], inWin ? ['time'] : []) };
  }).sort((a, b) => b.score - a.score);
  // people: the ones named, then whoever appears in the best evidence
  const pscore = new Map();
  for (const x of who) pscore.set(x.id, (pscore.get(x.id) || 0) + 10 * x.score); // the people the user named come first
  for (const [i, r] of scored.slice(0, 6).entries()) for (const x of r.ps) {
    const en = c.name.get(x.id); if (en?.type !== 'person') continue;
    const id = c.g.canon(x.id); pscore.set(id, (pscore.get(id) || 0) + r.score / (i + 1));
  }
  const people = [...pscore].sort((a, b) => b[1] - a[1]).slice(0, 4).map(([id]) => personCard(db, c, id, cloud));
  const seen = new Set(), uniq = scored.filter((r) => { const key = `${r.e.kind}|${r.e.ts}|${r.e.title}|${r.e.body}`; return !seen.has(key) && seen.add(key); }); // same event on two calendars
  return { plan: p, events: uniq.slice(0, k).map((r) => card(db, c, r, cloud, now)), people, _scored: uniq };
}

const when = (t, now) => {
  const d = new Date(t), dd = Math.round((day0(t) - day0(now)) / D);
  const rel = dd === 0 ? 'today' : dd === -1 ? 'yesterday' : dd === 1 ? 'tomorrow' : dd < 0 && dd > -7 ? `${-dd} days ago` : dd > 0 && dd < 7 ? `in ${dd} days` : null;
  return `${d.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' })} ${d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })}${rel ? ` (${rel})` : ''}`;
};
function card(db, c, { e, ps, score }, cloud, now) {
  const nm = (id) => { const x = c.name.get(c.g.hard ? c.g.hard(id) : c.g.canon(id)); return x && (cloud ? S.redact(db, x.name) : x.name); };
  const from = ps.filter((x) => x.role === 'from').map((x) => nm(x.id)), to = ps.filter((x) => x.role === 'to' || x.role === 'attendee').map((x) => nm(x.id));
  const grp = ps.filter((x) => x.role === 'group').map((x) => nm(x.id)), place = ps.filter((x) => x.role === 'place').map((x) => nm(x.id));
  const app = ps.filter((x) => ['app', 'player', 'used', 'site', 'album', 'artist'].includes(x.role)).map((x) => pretty(c.name.get(x.id)?.name));
  const text = (cloud ? e.body_redacted : e.body) ?? undefined;
  return { id: e.id, score: score == null ? undefined : +score.toFixed(2), source: e.source, app: app.join(', ') || undefined, kind: e.kind, when: when(e.ts, now), ts: e.ts,
    ...(e.ts_end && e.kind !== 'message' && { ends: new Date(e.ts_end).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' }) }),
    from: from.length ? from.join(', ') : e.kind === 'message' && to.length ? 'you' : undefined, to: to.length ? to.join(', ') : from.length && e.kind === 'message' ? 'you' : undefined,
    group: grp[0], place: place[0], title: e.title || undefined, text, trusted: !!e.trusted };
}

function personCard(db, c, id, cloud) {
  const ids = c.g.members(id), en = c.name.get(id), Q = ids.map(() => '?').join(','), S2 = c.srcs, IN = c.IN; // only sources this reader may see
  const hardIds = c.g.hardMembers ? c.g.hardMembers(id) : ids, maybe = ids.filter((x) => !hardIds.includes(x)).map((x) => c.name.get(x)?.name).filter(Boolean);
  const aka = [...new Set([...hardIds.map((x) => c.name.get(x)?.name), ...db.prepare(`SELECT value FROM alias WHERE entity_id IN (${Q}) AND kind NOT IN ('phone','email','chat') AND source IN (${IN})`).all(...ids, ...S2).map((a) => a.value)])]
    .filter((n) => n && n !== en?.name);
  const al = db.prepare(`SELECT DISTINCT kind, value FROM alias WHERE entity_id IN (${Q}) AND kind IN ('phone','email') AND source IN (${IN})`).all(...ids, ...S2);
  const apps = db.prepare(`SELECT a.name, count(*) n, max(e.ts) last FROM event_entity pe JOIN event e ON e.id=pe.event_id
    JOIN event_entity ae ON ae.event_id=e.id AND ae.role='app' JOIN entity a ON a.id=ae.entity_id WHERE pe.entity_id IN (${Q}) AND e.source IN (${IN}) GROUP BY a.name ORDER BY last DESC`).all(...ids, ...S2);
  const srcs = db.prepare(`SELECT DISTINCT e.source FROM event e JOIN event_entity pe ON pe.event_id=e.id WHERE pe.entity_id IN (${Q}) AND e.source IN (${IN})`).all(...ids, ...S2).map((r) => r.source);
  const links = S.flags.v2 ? db.prepare(`SELECT l.score conf, l.status, l.why FROM identity_link l WHERE l.a IN (${Q}) OR l.b IN (${Q})`).all(...ids, ...ids).map((l) => ({ conf: l.conf / 10, why: `${l.status === 'same' ? 'same person' : 'possibly the same person'}: ${JSON.parse(l.why).map((w) => w[2]).join(', ')}` }))
    : db.prepare(`SELECT l.conf, l.why FROM entity_link l WHERE l.id IN (${Q}) OR l.canon IN (${Q})`).all(...ids, ...ids);
  const red = (s) => (cloud ? S.redact(db, s) : s);
  return { id, name: red(en?.name), also: aka.length ? aka.map(red) : undefined, maybe: maybe.length ? maybe.map(red) : undefined, type: en?.type,
    phone: al.filter((a) => a.kind === 'phone').map((a) => (cloud ? S.pseudo(db, 'P', a.value) : a.value)), email: al.filter((a) => a.kind === 'email').map((a) => (cloud ? S.pseudo(db, 'E', a.value) : a.value)),
    apps: [...new Set([...apps.map((a) => pretty(a.name)), ...srcs.filter((s) => ['sms', 'calllog', 'calendar'].includes(s)).map((s) => ({ sms: 'SMS', calllog: 'calls', calendar: 'calendar' })[s])])],
    closeness: +(c.close.get(id) || 0).toFixed(2), linked: links.length ? links.map((l) => `${l.why} (${Math.round(l.conf * 100)}%)`) : undefined };
}

// ---------------------------------------------------------------- 3. answers code can compute (each cited)
const hm = (t) => new Date(t).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' }).replace(':00', '');
const dayName = (t) => new Date(t).toLocaleDateString('en-US', { weekday: 'long' });

export function freeSlots(db, srcs, since, until, now, instances) {
  const IN = srcs.map(() => '?').join(',');
  const busy = instances ?? db.prepare(`SELECT id, ts, coalesce(ts_end, ts + 3600000) te, title FROM event WHERE kind='calendar' AND source IN (${IN}) AND ts < ? AND coalesce(ts_end, ts+3600000) > ? ORDER BY ts`)
    .all(...srcs, until, since);
  const out = [];
  for (let d = day0(Math.max(since, now)); d < until; d = day0(d, 1)) { // setHours, not +24 h: DST days are 23 or 25 h long
    const at = (h) => new Date(d).setHours(h, 0, 0, 0);
    let t = Math.max(at(9), now, since); const end = Math.min(at(21), until);
    for (const b of busy.filter((b) => b.te > t && b.ts < end)) { if (b.ts - t >= H) out.push([t, b.ts]); t = Math.max(t, b.te); }
    if (end - t >= H) out.push([t, end]);
  }
  const days = new Map();
  for (const [s, e] of out) (days.get(dayName(s)) ?? days.set(dayName(s), []).get(dayName(s))).push(`${hm(s)}–${hm(e)}`);
  return { text: [...days].map(([d, l]) => `${d} ${l.join(', ')}`).join('; '), busy: busy.map((b) => b.id) };
}

// The thread "reply back" / "I'm late" means: a recent incoming message, strongly preferring someone you're about to meet.
function replyTarget(db, c, srcs, now) {
  const IN = srcs.map(() => '?').join(',');
  const msgs = db.prepare(`SELECT e.id, e.ts, e.source, pe.entity_id pid, (SELECT a.name FROM event_entity ae JOIN entity a ON a.id=ae.entity_id WHERE ae.event_id=e.id AND ae.role='app') app,
    (SELECT 1 FROM event_entity ge WHERE ge.event_id=e.id AND ge.role='group') grp FROM event e JOIN event_entity pe ON pe.event_id=e.id AND pe.role='from'
    WHERE e.kind='message' AND e.source IN (${IN}) AND e.ts > ? ORDER BY e.ts DESC LIMIT 40`).all(...srcs, now - 12 * H);
  const meet = db.prepare(`SELECT e.id, e.ts, e.title, ee.entity_id pid FROM event e JOIN event_entity ee ON ee.event_id=e.id AND ee.role='attendee'
    WHERE e.kind='calendar' AND e.source IN (${IN}) AND e.ts BETWEEN ? AND ?`).all(...srcs, now - 0.5 * H, now + 3 * H);
  let best = null;
  for (const m of msgs) {
    const pid = c.g.canon(m.pid), mt = meet.find((x) => c.g.canon(x.pid) === pid);
    const s = Math.exp(-(now - m.ts) / (2 * H)) + (mt ? 2 : 0) - (m.grp ? 0.5 : 0);
    if (!best || s > best.s) best = { s, m, pid, mt };
  }
  return best;
}

export function ask(db, question, { cloud = true, now = Date.now(), k = 6 } = {}) {
  const c = cache(db, cloud), r = recall(db, question, { cloud, k, now }), p = r.plan, srcs = S.readable(db, cloud);
  const facts = [], cite = (ids) => [...new Set(ids)].slice(0, 4);
  const nm = (id) => { const n = c.name.get(c.g.canon(id))?.name; return cloud ? S.redact(db, n) : n; };
  if (p.intent === 'free') {
    const w = p.window || { since: now, until: day0(now, 8 - (new Date(now).getDay() || 7)) };
    const f = freeSlots(db, srcs, w.since, w.until, now);
    facts.push({ fact: `Your calendar is free (9 AM–9 PM, gaps ≥1 h) ${w.label || 'this week'}: ${f.text || 'no free time'}`, cites: cite(f.busy) });
    for (const x of p.people.slice(0, 1)) { // what they said about timing, so the model can match it against the gaps
      const ids = c.g.members(x.id), said = db.prepare(`SELECT e.id, e.ts FROM event e JOIN event_entity ee ON ee.event_id=e.id AND ee.role='from' WHERE ee.entity_id IN (${ids.map(() => '?')})
        AND e.kind='message' AND e.source IN (${srcs.map(() => '?')}) AND e.ts > ? ORDER BY e.ts DESC LIMIT 1`).get(...ids, ...srcs, now - 7 * D);
      if (said) facts.push({ fact: `${nm(x.id)} last wrote ${when(said.ts, now)}; check what they said about timing against those gaps`, cites: [said.id] });
    }
  }
  if (p.intent === 'reply' || (p.action && /\blate\b|\bomw\b|on my way/.test(p.q))) {
    const t = replyTarget(db, c, srcs, now);
    if (t) facts.push({ fact: `"Reply back" most likely means ${nm(t.pid)} in ${pretty(t.m.app) || t.m.source} (wrote ${Math.round((now - t.m.ts) / 60000)} min ago)${t.mt ? `; you have "${t.mt.title}" with them at ${hm(t.mt.ts)}` : ''}`, cites: cite([t.m.id, t.mt?.id].filter(Boolean)), person: t.pid });
  }
  if (p.intent === 'next') {
    const n = db.prepare(`SELECT id, ts, title FROM event WHERE kind='calendar' AND source IN (${srcs.map(() => '?')}) AND ts >= ? GROUP BY title, ts ORDER BY ts LIMIT 3`).all(...srcs, now - 10 * 60000);
    if (n.length) facts.push({ fact: `Next on your calendar: ${n.map((x) => `"${cloud ? S.redact(db, x.title) : x.title}" ${when(x.ts, now)}`).join('; ')}`, cites: n.map((x) => x.id) });
  }
  if (p.intent === 'most') {
    if (p.facets.includes('usage') || /\bapps?\b/.test(p.q)) {
      const w = p.window || { since: day0(now), until: now + 1 };
      const u = db.prepare(`SELECT e.id, e.title, a.name FROM event e JOIN event_entity ee ON ee.event_id=e.id AND ee.role IN ('used','app') JOIN entity a ON a.id=ee.entity_id
        WHERE e.kind='usage' AND e.source IN (${srcs.map(() => '?')}) AND e.ts >= ? AND e.ts < ?`).all(...srcs, w.since, w.until)
        .map((x) => ({ ...x, min: +(x.title.match(/(\d+) min/)?.[1] || 0) })).sort((a, b) => b.min - a.min).slice(0, 3);
      if (u.length) facts.push({ fact: `Most used ${w.label || 'today'}: ${u.map((x) => `${pretty(x.name)} ${x.min} min`).join(', ')}`, cites: u.map((x) => x.id) });
    } else {
      const counts = new Map(), ids = new Map(), w = p.window || { since: now - 30 * D, until: now + 1 };
      for (const x of db.prepare(`SELECT e.id, e.source, e.title, pe.entity_id pid, (SELECT group_concat(a.name) FROM event_entity ae JOIN entity a ON a.id=ae.entity_id WHERE ae.event_id=e.id AND ae.role='app') app,
        (SELECT 1 FROM event_entity ge WHERE ge.event_id=e.id AND ge.role='group') grp
        FROM event e JOIN event_entity pe ON pe.event_id=e.id AND pe.role IN ('from','to') WHERE e.kind IN ('message','call') AND e.source IN (${srcs.map(() => '?')}) AND e.ts >= ? AND e.ts < ?`).all(...srcs, w.since, w.until)) {
        if (p.apps.length && !p.apps.some((a) => APPS[a].test(x.app || '') || APPS[a].test(x.source))) continue;
        if (c.name.get(x.pid)?.type !== 'person') continue;
        const id = c.g.canon(x.pid); counts.set(id, (counts.get(id) || 0) + (/Discord: |Slack: #|WhatsApp: .* group/.test(x.title || '') && x.grp ? 0.2 : x.grp ? 0.2 : 1)); (ids.get(id) ?? ids.set(id, []).get(id)).push(x.id);
      }
      const top = [...counts].sort((a, b) => b[1] - a[1]).slice(0, 3);
      if (top.length) facts.push({ fact: `Most messages${p.apps.length ? ` on ${p.apps.join('/')}` : ''} (1:1 counts full, group chat 0.2): ${top.map(([id, n]) => `${nm(id)} (${Math.round(n * 10) / 10})`).join(', ')}`, cites: cite(ids.get(top[0][0])), person: top[0][0] });
    }
  }
  if (p.intent === 'count' && p.window && p.facets.length) {
    const fk = p.facets.flatMap((f) => FACETS[f].kinds), fs = p.facets.flatMap((f) => FACETS[f].sources);
    const rows = db.prepare(`SELECT id, title, kind FROM event WHERE source IN (${srcs.map(() => '?')}) AND ts >= ? AND ts < ? AND kind IN (${fk.map(() => '?')})
      AND source IN (${fs.map(() => '?').join(',') || "''"})`).all(...srcs, p.window.since, p.window.until, ...fk, ...fs);
    const by = new Map(); for (const x of rows) { const t = String(x.title || x.kind).split(' · ')[0]; by.set(t, (by.get(t) || 0) + 1); } // "12 Photo, 1 Screenshot"
    facts.push({ fact: `${[...by].map(([t, n]) => `${n} ${t.toLowerCase()}${n > 1 ? 's' : ''}`).join(' and ') || `No ${p.facets[0]} items`} ${p.window.label}`, cites: cite(rows.map((x) => x.id)), count: true });
  }
  if (p.intent === 'last' && r.people[0]) {
    const ids = c.g.members(r.people[0].id);
    const l = db.prepare(`SELECT e.id, e.ts, e.source, e.kind FROM event e JOIN event_entity ee ON ee.event_id=e.id WHERE ee.entity_id IN (${ids.map(() => '?')})
      AND e.kind IN ('message','call') AND e.source IN (${srcs.map(() => '?')}) AND e.ts <= ? ORDER BY e.ts DESC LIMIT 1`).get(...ids, ...srcs, now);
    if (l) facts.push({ fact: `Last ${l.kind} with ${r.people[0].name}: ${when(l.ts, now)} via ${l.source}`, cites: [l.id] });
  }
  const PK = [[/\b(home|get back|house|address)\b/, 'home'], [/\b(work|office|job)\b/, 'work'], [/\b(usually|routine|habit|every (day|morning)|always|mornings?|most days)\b/, 'routine'],
    [/\b(close|closest|best friend|friends|family)\b/, 'close_people'], [/\b(apps?|screen time|phone time)\b/, 'apps']].filter(([re]) => re.test(p.q)).map(([, k]) => k);
  if (PK.length) for (const f of profile(db, { cloud, now })) if (PK.includes(f.k)) facts.push({ fact: f.fact, cites: f.cites, profile: true });
  if (S.flags.v2) {
    // durable facts (facts.js): about the people named, about the owner when the question is about "me", else by words
    const hist = /\b(before|used to|previously|old|earlier|was|were|moved from|first)\b/.test(p.q), named = p.people.slice(0, 2).flatMap((x) => c.g.members(x.id));
    let fs = named.length ? F.list(db, { about: named, history: hist, cloud, srcs }) : [];
    if (!named.length || /\b(my|mine|am i|do i|did i|i m|i am|i take|i prefer|i like|should i)\b/.test(p.q)) fs.push(...F.list(db, { about: [db.me], q: p.terms.join(' '), history: hist, cloud, srcs }));
    if (!fs.length && p.terms.length) fs = F.list(db, { q: p.terms.join(' '), history: hist, cloud, srcs });
    const hit = p.terms.length ? fs.filter((f) => p.terms.some((t) => `${f.pred} ${F.say(f)}`.toLowerCase().includes(t))) : [];
    if (hit.length) fs = [...hit, ...fs.filter((f) => !hit.includes(f))];
    else if (p.terms.length && named.length) fs = [...fs.filter((f) => f.pred === 'relationship'), ...fs.filter((f) => f.pred !== 'relationship')].slice(0, 2); // the question is about something else: just who they are
    for (const f of fs.slice(0, hit.length ? 6 : 4)) facts.push({ fact: f.text, cites: f.event_id ? [f.event_id] : [], memory: f.id });
    // an outward action to a name that fits two different people: say so, and never pick one
    if (p.action && p.keys.length) {
      const rid = [...(c.raw.get(p.keys[0]) || [])].filter((id) => c.name.get(id)?.type === 'person'), hard = new Map();
      for (const id of rid) (hard.get(c.g.hard(id)) ?? hard.set(c.g.hard(id), []).get(c.g.hard(id))).push(id);
      if (hard.size > 1) {
        const desc = [...hard].map(([h, ids]) => { const n = c.name.get(h)?.name, rel = F.list(db, { about: ids, cloud, srcs }).find((f) => f.pred === 'relationship'); return `${cloud ? S.redact(db, n) : n}${rel ? ` (your ${rel.obj})` : ''}${ids.some((i) => db.prepare(`SELECT 1 FROM alias WHERE entity_id=? AND source='contacts'`).get(i)) ? ' [contact]' : ''}`; });
        facts.push({ fact: `Ambiguous: "${p.keys[0]}" could be ${desc.join(' or ')}. Ask the user which one before sending anything; do not guess.`, cites: [], ambiguous: desc });
      } else if (hard.size === 1 && c.g.maybe) {
        const h = [...hard.keys()][0], mb = c.g.maybe(h).filter((m) => c.g.hard(m.id) !== h);
        if (mb.length) facts.push({ fact: `Note: ${c.name.get(h)?.name} is possibly (not confirmed) the same person as ${mb.map((m) => c.name.get(m.id)?.name).join(' / ')}; send only to the card the user named.`, cites: [] });
      }
    }
  }
  const top = facts.find((f) => f.person);
  if (top && r.people[0]?.id !== top.person) r.people = [personCard(db, c, top.person, cloud), ...r.people.filter((x) => x.id !== top.person)].slice(0, 4);
  const have = new Set(r.events.map((e) => e.id)), cited = facts.filter((f) => !f.memory).flatMap((f) => f.cites).filter((id) => !have.has(id)).slice(0, 3);
  const extra = cited.map((id) => db.prepare('SELECT * FROM event WHERE id=?').get(id)).filter((e) => e && srcs.includes(e.source))
    .map((e) => card(db, c, { e, ps: db.prepare('SELECT entity_id id, role FROM event_entity WHERE event_id=?').all(e.id) }, cloud, now));
  delete r._scored;
  return { ...r, events: [...extra, ...r.events].slice(0, k + extra.length), facts };
}

// One short block for <phone_context>: the few memories that most likely answer this request (empty if none do).
// Small on purpose: every 100 tokens costs the user time on every request.
const DEVICE = /^(please )?(set|start|stop|turn|open|launch|close|play|pause|resume|skip|navigate|take|search|google|wake|increase|decrease|raise|lower|mute|unmute|enable|disable|block|unblock|install|uninstall|show|go|scroll|tap|type|any trackers)\b/;
export function context(db, goal, { cloud = true, now = Date.now(), max = 3 } = {}) {
  goal = String(goal).replace(/\s*\([^()]*\)\s*$/, ''); // a trailing aside ("(asap)", the eval's "(Eval: just say who...)") isn't what to recall
  const p = plan(db, goal, now, cloud), aboutMe = S.flags.v2 && /\b(about me|know about me|remember about me|my (preferences|favou?rites|habits)|who am i)\b/.test(p.q);
  if (!p.people.length && !p.intent && !p.window && !p.terms.length && !aboutMe) return null;
  if (!p.people.length && !p.intent && DEVICE.test(p.q)) return null; // "set an alarm for 7" needs no memories
  const a = ask(db, goal, { cloud, now, k: max + 2 });
  const top = Math.max(0, ...a.events.map((e) => e.score ?? 0));
  const cited = new Set(a.facts.filter((f) => !f.memory).flatMap((f) => f.cites)); // a stored fact already says it: its source event needn't take a slot
  const strong = a.events.filter((e) => cited.has(e.id) || (e.score ?? 0) >= Math.max(0.9, 0.6 * top)).slice(0, max);
  if (aboutMe) for (const l of F.about(db, { cloud, names: (id, n) => c0(db, cloud).name.get(id)?.name || n, canon: (id) => c0(db, cloud).g.hard(id) }).lines) a.facts.push({ fact: l, cites: [], profile: true });
  if (!a.facts.length && !a.plan.people.length && !strong.length) return null;
  const named = a.plan.people.length, target = a.facts.some((f) => f.person);
  return { facts: a.facts, people: a.people.slice(0, target ? 1 : named ? Math.min(2, named) : strong.length ? 1 : 0), events: strong, plan: a.plan };
}

const c0 = (db, cloud) => cache(db, cloud);
// People the owner should settle (identity.js), the digest, facts: for UIs and the proactivity agent (index.js wraps these).
export const v2 = { pending: (db, limit) => { cache(db); return identity.pending(db, limit); }, confirm: (db, a, b, same, actor) => { const r = identity.confirm(db, a, b, same, actor); caches.delete(db); return r; },
  about: (db, { cloud = true, budget } = {}) => F.about(db, { cloud, budget, names: (id, n) => c0(db, cloud).name.get(id)?.name || n, canon: (id) => c0(db, cloud).g.hard(id) }),
  // resolve a name for an outward action: {ok, person} or {ok:false, ask, candidates}
  target(db, name, { cloud = true } = {}) {
    const c = cache(db, cloud), key = norm(name), rid = [...(c.raw.get(key) || [])].filter((id) => c.name.get(id)?.type === 'person'), hard = new Map();
    for (const id of rid) (hard.get(c.g.hard(id)) ?? hard.set(c.g.hard(id), []).get(c.g.hard(id))).push(id);
    const cand = [...hard].map(([h, ids]) => ({ id: h, name: c.name.get(h)?.name, cards: ids.map((i) => c.name.get(i)?.name), contact: ids.some((i) => db.prepare(`SELECT 1 FROM alias WHERE entity_id=? AND source='contacts'`).get(i)),
      maybe: c.g.maybe ? c.g.maybe(h).filter((m) => c.g.hard(m.id) !== h).map((m) => c.name.get(m.id)?.name) : [] }));
    if (!cand.length) return { ok: false, ask: `I don't know anyone called "${name}".`, candidates: [] };
    if (cand.length > 1) return { ok: false, ask: `Which ${name}: ${cand.map((x) => x.name).join(' or ')}?`, candidates: cand };
    return { ok: true, person: cand[0], note: cand[0].maybe.length ? `possibly the same person as ${cand[0].maybe.join(' / ')} (not confirmed)` : undefined };
  } };

// The derived profile (profile.js) with closeness from the cross-app person groups.
export function profile(db, { cloud = true, now = Date.now() } = {}) {
  const c = cache(db, cloud), people = [...c.close].filter(([id]) => c.name.get(id)?.type === 'person' && !/^Unknown/.test(c.name.get(id)?.name))
    .sort((a, b) => b[1] - a[1]).slice(0, 5).map(([id]) => personCard(db, c, id, cloud));
  return derive(db, { cloud, now, close: people, pretty });
}
