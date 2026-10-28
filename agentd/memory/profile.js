// Derived profile: the handful of things a good assistant just knows about you (close people, where you work and live,
// routines, the apps you live in). Like a sticky note built from your history, where every line says where it came from
// (cites = event ids). Computed on demand from readable sources only, so a revoke or a cloud/local scope change is
// reflected at once; nothing is stored.
import * as S from './store.js';

const D = 864e5;
const hm = (h) => `${Math.floor(h) % 12 || 12}:${String(Math.round((h % 1) * 60)).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`;


export function profile(db, { cloud = true, now = Date.now(), close = [], pretty = (n) => n } = {}) {
  const srcs = S.readable(db, cloud), IN = srcs.map(() => '?').join(','), red = (s) => (cloud ? S.redact(db, s) : s);
  const facts = [];
  // close people (closeness comes from brain.js so links across apps count once)
  if (close.length) facts.push({ k: 'close_people', fact: `Closest people lately: ${close.map((p) => `${p.name} (${p.apps.slice(0, 3).join(', ')})`).join('; ')}`, cites: [] });
  // work: the calendar place you're at most on weekday daytimes
  const places = db.prepare(`SELECT p.name, e.id, e.ts FROM event e JOIN event_entity ee ON ee.event_id=e.id AND ee.role='place' JOIN entity p ON p.id=ee.entity_id
    WHERE e.kind='calendar' AND e.source IN (${IN}) AND e.ts > ?`).all(...srcs, now - 60 * D)
    .filter((r) => { const d = new Date(r.ts); return d.getDay() % 6 && d.getHours() >= 8 && d.getHours() < 18; });
  const byPlace = Object.entries(Object.groupBy(places, (r) => r.name)).sort((a, b) => b[1].length - a[1].length);
  if (byPlace[0]?.[1].length >= 3) facts.push({ k: 'work', fact: `Works at ${red(byPlace[0][0])} (weekday calendar events there: ${byPlace[0][1].length})`, cites: byPlace[0][1].slice(-3).map((r) => r.id) });
  // home: only what the user said (notes) — we never guess an address
  const home = db.prepare(`SELECT id, body FROM event WHERE source='user' AND kind='note' AND (body LIKE '%home%' OR body LIKE '%I live%') ORDER BY ts DESC LIMIT 1`).get();
  if (home) facts.push({ k: 'home', fact: `Home (you told me): ${red(home.body)}`, cites: [home.id] });
  // routines: repeated calendar titles at a similar hour
  const cal = db.prepare(`SELECT id, title, ts FROM event WHERE kind='calendar' AND source IN (${IN}) AND ts BETWEEN ? AND ?`).all(...srcs, now - 30 * D, now + 7 * D);
  for (const [title, rows] of Object.entries(Object.groupBy(cal, (r) => r.title || ''))) {
    if (!title || rows.length < 3) continue;
    const h = rows.map((r) => new Date(r.ts).getHours() + new Date(r.ts).getMinutes() / 60).sort((a, b) => a - b)[rows.length >> 1];
    const wk = rows.every((r) => new Date(r.ts).getDay() % 6);
    facts.push({ k: 'routine', fact: `"${red(title)}" ${wk ? 'weekdays' : 'often'} around ${hm(h)} (${rows.length}× in 30 days)`, cites: rows.slice(-3).map((r) => r.id) });
  }
  // listening habit: mornings, top artist
  const ls = db.prepare(`SELECT e.id, e.ts, a.name artist FROM event e LEFT JOIN event_entity ee ON ee.event_id=e.id AND ee.role='artist' LEFT JOIN entity a ON a.id=ee.entity_id
    WHERE e.kind='listen' AND e.source IN (${IN}) AND e.ts > ?`).all(...srcs, now - 30 * D);
  const morning = ls.filter((r) => { const h = new Date(r.ts).getHours(); return h >= 5 && h < 11; });
  if (morning.length >= 4) {
    const top = Object.entries(Object.groupBy(morning, (r) => r.artist || '?')).sort((a, b) => b[1].length - a[1].length)[0];
    facts.push({ k: 'routine', fact: `Listens to music most mornings, mostly ${red(top[0])}`, cites: morning.slice(-3).map((r) => r.id) });
  }
  // apps you live in (last 7 days of usage)
  const use = db.prepare(`SELECT e.id, e.title, a.name FROM event e JOIN event_entity ee ON ee.event_id=e.id AND ee.role IN ('used','app') JOIN entity a ON a.id=ee.entity_id
    WHERE e.kind='usage' AND e.source IN (${IN}) AND e.ts > ?`).all(...srcs, now - 7 * D);
  const mins = new Map(), ids = new Map();
  for (const u of use) { const m = +(u.title?.match(/(\d+) min/)?.[1] || 0); mins.set(u.name, (mins.get(u.name) || 0) + m); (ids.get(u.name) ?? ids.set(u.name, []).get(u.name)).push(u.id); }
  const top = [...mins].sort((a, b) => b[1] - a[1]).slice(0, 3);
  if (top.length) facts.push({ k: 'apps', fact: `Most-used apps this week: ${top.map(([n, m]) => `${pretty(n)} ${Math.round(m / 60 * 10) / 10} h`).join(', ')}`, cites: top.flatMap(([n]) => ids.get(n).slice(-1)) });
  return facts;
}
