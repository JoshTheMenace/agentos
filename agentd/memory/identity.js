// Identity v2: who is the same person across apps, decided by evidence, Fellegi–Sunter style. Every clue between two
// cards gets a weight (log-odds-ish: +8 "same phone", +3.5 "same full name", -6 "two different Discord handles"); the
// weights add up; two thresholds split pairs into SAME (merged for everything), MAYBE (shown together for reads, but an
// outward action asks the owner first) and nothing. Think of a detective's corkboard: a photo pinned to a name with a
// phone bill is a match; with just a first name it's a string of red yarn and a question mark.
// Owner answers ("yes, Discord Joe is Joe Smith" / "no") are evidence rows and outweigh everything else.
import * as S from './store.js';

export const SAME = 6, MAYBE = 2; // thresholds on the summed score
const W = { phone: 8, email: 8, owner_same: 10, owner_diff: -10, full_name: 3.5, address_book_name: 3, first_initial: 3.5, nick_initial: 3, email_name: 4, email_prefix: 2,
  first_only: 2, nick_only: 1.5, relationship_name: 4, same_platform: -6, same_group: -6, phones_differ: -5, emails_differ: -3, last_differ: -6, many_candidates: -1 };
const NICK = { sam: ['samuel', 'samantha', 'sammy'], sammy: ['sam', 'samuel', 'samantha'], alex: ['alexander', 'alexis', 'alexandra', 'alejandro'], mike: ['michael'], matt: ['matthew'],
  chris: ['christopher', 'christine', 'christina'], dan: ['daniel'], dave: ['david'], jen: ['jennifer'], kate: ['katherine', 'kathryn', 'katie'], liz: ['elizabeth'],
  nick: ['nicholas'], rob: ['robert'], bob: ['robert'], will: ['william'], bill: ['william'], tom: ['thomas'], tony: ['anthony'], joe: ['joseph', 'joey'], josh: ['joshua'],
  jake: ['jacob'], ben: ['benjamin'], nate: ['nathan', 'nathaniel'], abby: ['abigail'], becca: ['rebecca'], beck: ['rebecca'], jordy: ['jordan'], kai: ['kaito'] };
const RELSYN = [['mom', 'mother', 'mum', 'mama', 'mommy'], ['dad', 'father', 'papa', 'daddy'], ['grandma', 'nana', 'grandmother'], ['grandpa', 'grandfather']];
const RELW = (w) => RELSYN.find((l) => l.includes(norm(w))) || [norm(w)];
const nickOf = (a, b) => a !== b && (NICK[a]?.includes(b) || NICK[b]?.includes(a) || Object.values(NICK).some((l) => l.includes(a) && l.includes(b)));
export const norm = (s) => String(s || '').normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/\(.*?\)/g, ' ').replace(/[^a-z\s.]/g, ' ').replace(/\s+/g, ' ').trim();
const toks = (s) => norm(s).replace(/\./g, ' ').split(' ').filter(Boolean);

export function ensure(db) {
  db.exec(`CREATE TABLE IF NOT EXISTS identity_evidence(id INTEGER PRIMARY KEY, a INTEGER NOT NULL REFERENCES entity ON DELETE CASCADE,
      b INTEGER NOT NULL REFERENCES entity ON DELETE CASCADE, kind TEXT NOT NULL, weight REAL NOT NULL, why TEXT, actor TEXT, at INTEGER);
    CREATE TABLE IF NOT EXISTS identity_link(a INTEGER NOT NULL REFERENCES entity ON DELETE CASCADE, b INTEGER NOT NULL REFERENCES entity ON DELETE CASCADE,
      score REAL NOT NULL, status TEXT NOT NULL, why TEXT, PRIMARY KEY(a,b));`);
}

// Cards with what we know about each: name tokens, phones, emails, platforms (chat handles), groups they post in, contact?
function cards(db) {
  const people = db.prepare(`SELECT id, name FROM entity WHERE type='person' AND name NOT LIKE 'Unknown%'`).all()
    .map((p) => ({ ...p, t: toks(p.name), phone: new Set(), email: new Set(), plat: new Map(), groups: new Set(), contact: false, rel: null }));
  const by = new Map(people.map((p) => [p.id, p]));
  for (const a of db.prepare('SELECT entity_id, kind, value, source FROM alias').all()) {
    const p = by.get(a.entity_id); if (!p) continue;
    if (a.kind === 'phone' || a.kind === 'email') p[a.kind].add(a.value);
    if (a.kind === 'chat') { const [pkg, handle] = a.value.split('|'); p.plat.set(pkg, handle); }
    if (a.source === 'contacts') p.contact = true;
  }
  for (const r of db.prepare(`SELECT DISTINCT f.entity_id pid, g.entity_id gid FROM event_entity f JOIN event_entity g ON g.event_id=f.event_id AND g.role='group' WHERE f.role='from'`).all())
    by.get(r.pid)?.groups.add(r.gid);
  try { // the owner said "my mom is Linda Gimenes" (facts.js: pred mom_name on the owner's card, obj_entity = the Mom card)
    for (const r of db.prepare(`SELECT obj, obj_entity FROM fact WHERE pred LIKE '%_name' AND status='active' AND obj_entity IS NOT NULL`).all()) if (by.get(r.obj_entity)) by.get(r.obj_entity).rel = r.obj;
    // and the card itself knows "is your mom" (facts.js relationship): a contact simply named "Mom" is then that person
    for (const r of db.prepare(`SELECT subj, obj FROM fact WHERE pred='relationship' AND status='active'`).all()) if (by.get(r.subj)) by.get(r.subj).relword = r.obj;
  } catch {}
  return people;
}

// Score one pair. Returns {score, why:[...]} or null when there is nothing to compare.
export function compare(a, b, extra = []) {
  const why = [], add = (k, note) => why.push([k, W[k], note]);
  const share = (x, y) => [...x].some((v) => y.has(v));
  if (share(a.phone, b.phone)) add('phone', 'same phone number');
  if (share(a.email, b.email)) add('email', 'same email');
  const [fa, fb] = [a.t[0], b.t[0]], la = a.t.length > 1 ? a.t.at(-1) : null, lb = b.t.length > 1 ? b.t.at(-1) : null;
  if (!fa || !fb) return null;
  const firstSame = fa === fb, nick = nickOf(fa, fb), fullSame = firstSame && la && lb && la === lb;
  // hard-ish clues that work even when the names look unrelated ("Mom" vs "Linda Gimenes")
  if (!fullSame) for (const [x, y] of [[a, b], [b, a]]) { // "linda.gimenes@…" on one card, "Linda Gimenes" as the other's name
    const loc = [...x.email].map((e) => e.split('@')[0].split(/[^a-z]+/).filter(Boolean));
    if (y.t.length > 1 && loc.some((l) => l.includes(y.t[0]) && l.includes(y.t.at(-1)))) { add('email_name', `${x.name}'s email has the name ${y.name}`); break; }
    if (y.t.length > 1 && loc.some((l) => l.length > 1 && (l[0] === y.t[0] || l[0].startsWith(y.t[0]) || y.t[0].startsWith(l[0])) && l[1][0] === y.t.at(-1)[0])) { add('email_prefix', `${x.name}'s email starts like ${y.name}`); break; }
  }
  if (a.rel && norm(a.rel) === norm(b.name) || b.rel && norm(b.rel) === norm(a.name)) add('relationship_name', `the owner said ${a.rel ? `"${a.name}" is ${a.rel}` : `"${b.name}" is ${b.rel}`}`);
  else if (a.relword && RELW(a.relword).includes(norm(b.name)) || b.relword && RELW(b.relword).includes(norm(a.name))) add('relationship_name', `the owner said "${a.relword ? a.name : b.name}" is their ${a.relword || b.relword}, and the other card is named that`);
  if (!firstSame && !nick && !why.length) return null; // nothing ties them
  if (la && lb && la.length > 1 && lb.length > 1 && la !== lb) add('last_differ', `last names differ (${la} / ${lb})`);
  else if (la && lb && la[0] !== lb[0]) add('last_differ', `last initials differ (${la} / ${lb})`);
  else if (fullSame) add('full_name', `same full name "${a.t.join(' ')}"`);
  else if (la && lb && (la.length === 1 || lb.length === 1) && la[0] === lb[0] && (firstSame || nick)) add(firstSame ? 'first_initial' : 'nick_initial', `"${a.name}" = first name + last initial of "${b.name}"`);
  else if (firstSame) add('first_only', 'only the first name matches');
  else if (nick) add('nick_only', `"${fa}" is short for "${fb}"`);
  for (const [pkg, h] of a.plat) if (b.plat.has(pkg) && b.plat.get(pkg) !== h) { add('same_platform', `two different ${S.PLATFORM[pkg] || pkg} handles`); break; }
  if (share(a.groups, b.groups)) add('same_group', 'post as two different members of the same group chat');
  if (a.phone.size && b.phone.size && !share(a.phone, b.phone)) add('phones_differ', 'different phone numbers');
  if (a.email.size && b.email.size && !share(a.email, b.email)) add('emails_differ', 'different emails');
  for (const e of extra) why.push([e.kind, e.weight, e.why]);
  return { score: why.reduce((s, w) => s + w[1], 0), why };
}

// Recompute every link from scratch (cheap: pairs are blocked on first name). Owner evidence rows are kept and added in.
export function relink(db) {
  ensure(db);
  const P = cards(db), own = new Map();
  for (const e of db.prepare('SELECT * FROM identity_evidence').all()) { const k = [e.a, e.b].sort((x, y) => x - y).join('-'); (own.get(k) ?? own.set(k, []).get(k)).push(e); }
  const scored = [];
  for (let i = 0; i < P.length; i++) for (let j = i + 1; j < P.length; j++) {
    const a = P[i], b = P[j], r = compare(a, b, own.get(`${Math.min(a.id, b.id)}-${Math.max(a.id, b.id)}`) || []);
    if (r && r.score >= MAYBE) scored.push({ a: a.id, b: b.id, ...r });
  }
  // a bare "Sam" that fits two contacts equally is nobody in particular: penalise ties among weak links
  const weak = (x) => x.score < SAME, s0 = new Map(scored.map((l) => [l, l.score]));
  for (const l of scored.filter(weak)) {
    const rivals = scored.filter((o) => o !== l && weak(o) && (o.a === l.a || o.b === l.a || o.a === l.b || o.b === l.b) && Math.abs(s0.get(o) - s0.get(l)) < 1e-9);
    if (rivals.length) l.why.push(['many_candidates', W.many_candidates, `${rivals.length + 1} people fit equally well`]), l.score += W.many_candidates;
  }
  db.exec('DELETE FROM identity_link');
  const ins = db.prepare('INSERT INTO identity_link(a,b,score,status,why) VALUES(?,?,?,?,?)');
  const out = scored.filter((l) => l.score >= MAYBE).map((l) => ({ ...l, status: l.score >= SAME ? 'same' : 'maybe' }));
  for (const l of out) ins.run(l.a, l.b, Math.round(l.score * 10) / 10, l.status, JSON.stringify(l.why));
  return out;
}

// Groups for the brain: canon(id) follows SAME links only; soft(id) also follows MAYBE links (reads rank across them).
// The canonical card is the contact (address book) when there is one, else the one with the longest name.
export function groups(db) {
  ensure(db);
  const links = db.prepare('SELECT a, b, status, score FROM identity_link').all();
  const contact = new Set(db.prepare(`SELECT DISTINCT entity_id FROM alias WHERE source='contacts'`).all().map((r) => r.entity_id));
  const names = new Map(db.prepare(`SELECT id, name FROM entity WHERE type='person'`).all().map((r) => [r.id, r.name]));
  const rank = (id) => (contact.has(id) ? 1e6 : 0) + (names.get(id)?.replace(/\(.*\)/, '').trim().length || 0);
  const union = (want) => { // union-find over the chosen links
    const par = new Map(), find = (x) => (par.has(x) && par.get(x) !== x ? (par.set(x, find(par.get(x))), par.get(x)) : x);
    for (const l of links) if (want(l)) { const ra = find(l.a), rb = find(l.b); if (ra !== rb) par.set(rank(ra) >= rank(rb) ? rb : ra, rank(ra) >= rank(rb) ? ra : rb); }
    const members = new Map();
    for (const id of new Set(links.flatMap((l) => [l.a, l.b]))) { const r = find(id); members.set(r, [...(members.get(r) || [r]), ...(id === r ? [] : [id])]); }
    return { canon: (id) => find(id), members: (id) => members.get(find(id)) || [id] };
  };
  const hard = union((l) => l.status === 'same'), soft = union(() => true);
  const maybe = (id) => links.filter((l) => l.status === 'maybe' && (l.a === id || l.b === id)).map((l) => ({ id: l.a === id ? l.b : l.a, score: l.score }));
  return { canon: hard.canon, members: hard.members, soft: soft.members, softCanon: soft.canon, maybe, status: (a, b) => links.find((l) => (l.a === a && l.b === b) || (l.a === b && l.b === a))?.status || 'none' };
}

// Pairs the owner should settle, as one-tap cards: [{a:{id,name}, b:{id,name}, score, why, question}].
export function pending(db, limit = 5) {
  ensure(db);
  const nm = (id) => db.prepare('SELECT name FROM entity WHERE id=?').get(id)?.name;
  return db.prepare(`SELECT * FROM identity_link WHERE status='maybe' AND NOT EXISTS (SELECT 1 FROM identity_evidence e WHERE (e.a=identity_link.a AND e.b=identity_link.b) OR (e.a=identity_link.b AND e.b=identity_link.a))
    ORDER BY score DESC LIMIT ?`).all(limit).map((l) => ({ a: { id: l.a, name: nm(l.a) }, b: { id: l.b, name: nm(l.b) }, score: l.score, why: JSON.parse(l.why).map((w) => w[2]),
    question: `Is "${nm(l.a)}" the same person as "${nm(l.b)}"?` }));
}

// The owner answered. Stored as evidence, links recomputed. same=false pins the pair apart for good.
export function confirm(db, a, b, same, actor = 'user:owner') {
  ensure(db);
  db.prepare('DELETE FROM identity_evidence WHERE (a=? AND b=?) OR (a=? AND b=?)').run(a, b, b, a);
  db.prepare('INSERT INTO identity_evidence(a,b,kind,weight,why,actor,at) VALUES(?,?,?,?,?,?,?)')
    .run(a, b, same ? 'owner_same' : 'owner_diff', same ? W.owner_same : W.owner_diff, same ? 'the owner confirmed they are the same person' : 'the owner said they are different people', actor, Date.now());
  return relink(db).find((l) => (l.a === a && l.b === b) || (l.a === b && l.b === a)) || { a, b, status: 'none' };
}
