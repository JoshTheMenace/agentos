// Entity resolution across apps: "Jordan W." on Discord, "Linda Gimenes" on Messenger and "Alex C" on Slack are cards
// for people you already know as Jordan Wu, Mom and Alexis Chen. store.person() already merges exact phone/email/name
// matches at ingest; this adds name rules with a confidence and a reason, like a librarian clipping two cards together
// with a note "same person? 85%: first name + last initial". Links live in their own table (additive, nothing is
// rewritten), are recomputed from scratch when the data changes, and vanish with the entity on revoke (ON DELETE CASCADE).
const NICK = { sam: ['samuel', 'samantha', 'sammy'], alex: ['alexander', 'alexis', 'alexandra', 'alejandro'], mike: ['michael'], matt: ['matthew'],
  chris: ['christopher', 'christine', 'christina'], dan: ['daniel'], dave: ['david'], jen: ['jennifer'], kate: ['katherine', 'kathryn', 'katie'],
  liz: ['elizabeth'], nick: ['nicholas'], rob: ['robert'], bob: ['robert'], will: ['william'], bill: ['william'], tom: ['thomas'], tony: ['anthony'],
  joe: ['joseph'], josh: ['joshua'], jake: ['jacob'], ben: ['benjamin'], nate: ['nathan', 'nathaniel'], abby: ['abigail'], becca: ['rebecca'] };
const same = (a, b) => a === b || NICK[a]?.includes(b) || NICK[b]?.includes(a) || Object.values(NICK).some((l) => l.includes(a) && l.includes(b));
export const norm = (s) => String(s || '').normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/\(.*?\)/g, ' ')
  .replace(/[^a-z\s.]/g, ' ').replace(/\s+/g, ' ').trim();
const toks = (s) => norm(s).replace(/\./g, ' ').split(' ').filter(Boolean);
export const MERGE = 0.8; // links at or above this are treated as one person; lower ones are only shown as "maybe"

export function ensureTable(db) {
  db.exec(`CREATE TABLE IF NOT EXISTS entity_link(id INTEGER PRIMARY KEY REFERENCES entity ON DELETE CASCADE,
    canon INTEGER NOT NULL REFERENCES entity ON DELETE CASCADE, conf REAL NOT NULL, why TEXT)`);
}

// Recompute every link. Returns [{id, canon, conf, why}].
export function relink(db) {
  ensureTable(db);
  const people = db.prepare(`SELECT id, name FROM entity WHERE type='person' AND name NOT LIKE 'Unknown%'`).all();
  const al = new Map(people.map((p) => [p.id, { phone: new Set(), email: new Set(), contacts: false }]));
  for (const a of db.prepare(`SELECT entity_id, kind, value, source FROM alias WHERE entity_id IN (SELECT id FROM entity WHERE type='person')`).all()) {
    const x = al.get(a.entity_id); if (!x) continue;
    if (a.kind === 'phone' || a.kind === 'email') x[a.kind].add(a.value);
    if (a.source === 'contacts') x.contacts = true;
  }
  const P = people.map((p) => ({ ...p, t: toks(p.name), ...al.get(p.id) }));
  const conflict = (a, b) => (a.phone.size && b.phone.size && ![...a.phone].some((v) => b.phone.has(v)));
  const out = [];
  for (const b of P) { // b = the card that may belong to someone else (usually a chat-app name)
    if (!b.t.length) continue;
    const cands = [];
    for (const a of P) {
      if (a.id === b.id || conflict(a, b)) continue;
      let conf = 0, why = '';
      if (a.t.length >= 2 && a.t.join(' ') === b.t.join(' ')) [conf, why] = [0.95, 'same full name'];
      else if (a.t.length >= 2 && b.t.length === 2 && b.t[1].length === 1 && same(a.t[0], b.t[0]) && a.t.at(-1)[0] === b.t[1])
        [conf, why] = [a.t[0] === b.t[0] ? 0.85 : 0.8, `"${b.name}" = first name + last initial of ${a.name}`];
      else if (b.t.length >= 2 && [...a.email].some((e) => { const l = e.split('@')[0].split(/[^a-z]+/); return l.includes(b.t[0]) && l.includes(b.t.at(-1)); }))
        [conf, why] = [0.9, `${a.name}'s email address has the name ${b.name}`];
      else if (b.t.length === 1 && a.t.length >= 2 && same(a.t[0], b.t[0]) && !b.phone.size && !b.email.size)
        [conf, why] = [0.5, `first name "${b.name}" matches ${a.name}`];
      if (conf) cands.push({ a, conf, why });
    }
    if (!cands.length) continue;
    const top = Math.max(...cands.map((c) => c.conf)), best = cands.filter((c) => c.conf === top);
    const pick = best.length === 1 ? best[0] : null; // two equally good matches: ambiguous, link nobody
    if (!pick) continue;
    const ab = [pick.a, b].sort((x, y) => (y.contacts - x.contacts) || (y.t.length - x.t.length) || (x.id - y.id)); // the contact card wins
    out.push({ id: ab[1].id, canon: ab[0].id, conf: pick.conf / (cands.length > 1 ? 1.1 : 1), why: pick.why });
  }
  const best = new Map(); // one link per card: the strongest
  for (const l of out) if (!best.has(l.id) || best.get(l.id).conf < l.conf) best.set(l.id, l);
  db.exec('DELETE FROM entity_link');
  const ins = db.prepare('INSERT OR REPLACE INTO entity_link(id, canon, conf, why) VALUES(?,?,?,?)');
  for (const l of best.values()) if (l.id !== l.canon) ins.run(l.id, l.canon, Math.round(l.conf * 100) / 100, l.why);
  return [...best.values()];
}

// {canon: Map id -> canonical id, members: Map canon -> [ids]} using merged links only. Chains (a->b->c) are followed.
export function groups(db) {
  ensureTable(db);
  const link = new Map(db.prepare('SELECT id, canon FROM entity_link WHERE conf >= ?').all(MERGE).map((r) => [r.id, r.canon]));
  const root = (id, n = 0) => (link.has(id) && n < 5 ? root(link.get(id), n + 1) : id);
  const canon = new Map(), members = new Map();
  for (const id of link.keys()) { const r = root(id); canon.set(id, r); members.set(r, [...(members.get(r) || [r]), id]); }
  return { canon: (id) => canon.get(id) ?? id, members: (id) => members.get(id) || [id] };
}
