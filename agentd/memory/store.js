// Memory store: one SQLite file (node:sqlite, no deps). Think of it as a card catalogue:
// entity = a person/place/app card, alias = the ways to find that card (phone, email, chat name),
// event = one thing that happened (text, call, meeting), relation = derived "see also" links.
// Every row remembers which `source` it came from, so revoking a source can hard-delete exactly its rows.
import { DatabaseSync } from 'node:sqlite';
import { createHash, randomBytes } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';

export const SOURCES = {
  contacts: 'Android contacts (names, phone numbers, emails)',
  calendar: 'Android calendar events and attendees',
  calllog: 'Android call log',
  sms: 'Android SMS (most recent N only)',
  notifications: 'Currently posted notifications',
  whatsapp: 'WhatsApp chat export (.txt) you chose to import',
  pigeon: 'Our own Pigeon messaging app',
  photos: 'Photo and video timestamps and album names (MediaStore; never image content)',
  usage: 'Which apps you use and for how long, per day (UsageStats)',
  chrome: 'Chrome browsing history (page titles and addresses without query strings)',
  media: 'What is playing (media sessions: track, artist, app)',
  alarms: 'The next alarm clock',
};
export const SCOPES = ['cloud', 'local']; // cloud: redacted results may go to cloud models. local: only local models see it.
const USER = 'user'; // memory_remember notes: the user said it, no grant needed

const sha = (s) => createHash('sha256').update(s).digest('hex');

export function open(path = process.env.MEMORY_DB || join(process.env.AGENTOS_HOME || join(homedir(), '.agentos'), 'memory.db')) {
  if (path !== ':memory:') mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
  const db = new DatabaseSync(path);
  db.exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA secure_delete=ON;
  CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT);
  CREATE TABLE IF NOT EXISTS consent(id INTEGER PRIMARY KEY, source TEXT NOT NULL, scope TEXT NOT NULL,
    action TEXT NOT NULL CHECK(action IN ('grant','revoke')), actor TEXT NOT NULL, at INTEGER NOT NULL, note TEXT,
    prev_hash TEXT, hash TEXT NOT NULL);
  CREATE TRIGGER IF NOT EXISTS consent_append_only BEFORE UPDATE ON consent BEGIN SELECT RAISE(ABORT,'consent ledger is append-only'); END;
  CREATE TABLE IF NOT EXISTS entity(id INTEGER PRIMARY KEY, type TEXT NOT NULL, name TEXT NOT NULL, created_at INTEGER, UNIQUE(type,name));
  CREATE TABLE IF NOT EXISTS alias(entity_id INTEGER NOT NULL REFERENCES entity ON DELETE CASCADE,
    kind TEXT NOT NULL, value TEXT NOT NULL, source TEXT NOT NULL, UNIQUE(kind,value,source));
  CREATE INDEX IF NOT EXISTS alias_kv ON alias(kind,value);
  CREATE TABLE IF NOT EXISTS event(id INTEGER PRIMARY KEY, source TEXT NOT NULL, kind TEXT NOT NULL, ts INTEGER NOT NULL,
    ts_end INTEGER, title TEXT, body TEXT, body_redacted TEXT, trusted INTEGER NOT NULL DEFAULT 0, ext_id TEXT, UNIQUE(source,ext_id));
  CREATE INDEX IF NOT EXISTS event_ts ON event(ts);
  CREATE TABLE IF NOT EXISTS event_entity(event_id INTEGER NOT NULL REFERENCES event ON DELETE CASCADE,
    entity_id INTEGER NOT NULL REFERENCES entity ON DELETE CASCADE, role TEXT, UNIQUE(event_id,entity_id,role));
  CREATE INDEX IF NOT EXISTS ee_entity ON event_entity(entity_id);
  CREATE TABLE IF NOT EXISTS relation(src INTEGER, dst INTEGER, type TEXT, weight REAL, first_seen INTEGER, last_seen INTEGER, source TEXT);
  CREATE TABLE IF NOT EXISTS pseudo(id INTEGER PRIMARY KEY, h TEXT UNIQUE NOT NULL);`); // holds salted hashes only, never numbers
  // Full-text index over redacted text only. FTS5 if available (it is in Node 22+ on Mac and Termux), else FTS4, else LIKE.
  db.fts = db.prepare(`SELECT v FROM meta WHERE k='fts'`).get()?.v;
  if (!db.fts) for (const m of ['fts5', 'fts4', 'none']) {
    try { if (m !== 'none') db.exec(`CREATE VIRTUAL TABLE event_fts USING ${m}(title, body)`); db.fts = m; break; } catch {}
  }
  if (db.fts !== 'none') db.exec(`
    CREATE TRIGGER IF NOT EXISTS event_ai AFTER INSERT ON event BEGIN INSERT INTO event_fts(rowid,title,body) VALUES(new.id,new.title,new.body_redacted); END;
    CREATE TRIGGER IF NOT EXISTS event_ad AFTER DELETE ON event BEGIN DELETE FROM event_fts WHERE rowid=old.id; END;`);
  db.prepare(`INSERT OR IGNORE INTO meta VALUES('fts',?),('salt',?)`).run(db.fts, randomBytes(16).toString('hex'));
  db.salt = db.prepare(`SELECT v FROM meta WHERE k='salt'`).get().v;
  db.me = entity(db, 'me', 'Me');
  return db;
}

export function tx(db, fn) {
  db.exec('BEGIN');
  try { const r = fn(); db.exec('COMMIT'); return r; } catch (e) { db.exec('ROLLBACK'); throw e; }
}

// ---------- consent ledger: append-only, each row hashes the previous one (like links in a chain) ----------
const rowHash = (prev, r) => sha((prev || '') + JSON.stringify([r.source, r.scope, r.action, r.actor, r.at, r.note ?? null]));

function appendConsent(db, r) {
  const prev = db.prepare('SELECT hash FROM consent ORDER BY id DESC LIMIT 1').get()?.hash || null;
  r = { ...r, note: r.note ?? null, at: Date.now() };
  db.prepare('INSERT INTO consent(source,scope,action,actor,at,note,prev_hash,hash) VALUES(?,?,?,?,?,?,?,?)')
    .run(r.source, r.scope, r.action, r.actor, r.at, r.note, prev, rowHash(prev, r));
}

export function grant(db, source, { scope = 'cloud', actor = 'user', note } = {}) {
  if (!SOURCES[source]) throw new Error(`unknown source ${source}; one of ${Object.keys(SOURCES).join(', ')}`);
  if (!SCOPES.includes(scope)) throw new Error(`scope must be ${SCOPES.join(' or ')}`);
  appendConsent(db, { source, scope, action: 'grant', actor, note });
  return consentState(db).find((c) => c.source === source);
}

// Revoke = ledger row + hard delete of every row from that source (events, aliases, orphaned entities), then rebuild edges.
export function revoke(db, source, { actor = 'user', note } = {}) {
  const deleted = tx(db, () => {
    const n = { events: db.prepare('DELETE FROM event WHERE source=?').run(source).changes,
      aliases: db.prepare('DELETE FROM alias WHERE source=?').run(source).changes };
    n.entities = gcEntities(db);
    appendConsent(db, { source, scope: active(db, source)?.scope || 'cloud', action: 'revoke', actor, note: note ?? JSON.stringify(n) });
    rebuildRelations(db);
    return n;
  });
  db.exec('PRAGMA wal_checkpoint(TRUNCATE)'); // don't leave old copies in the WAL file
  return deleted;
}

export const gcEntities = (db) => db.prepare(`DELETE FROM entity WHERE type!='me'
  AND id NOT IN (SELECT entity_id FROM alias) AND id NOT IN (SELECT entity_id FROM event_entity)`).run().changes;

// Latest ledger row per source decides whether it's active.
export function consentState(db) {
  return db.prepare(`SELECT c.source, c.scope, c.action, c.at, c.id,
      (SELECT max(at) FROM consent g WHERE g.source=c.source AND g.action='grant') AS granted_at,
      (SELECT count(*) FROM event e WHERE e.source=c.source) AS events
    FROM consent c WHERE c.id=(SELECT max(id) FROM consent WHERE source=c.source) ORDER BY c.source`).all()
    .map((c) => ({ source: c.source, scope: c.scope, active: c.action === 'grant', granted_at: c.granted_at,
      revoked_at: c.action === 'revoke' ? c.at : null, events: c.events, ledger_id: c.id }));
}
export const active = (db, source) => consentState(db).find((c) => c.source === source && c.active);
export function requireGrant(db, source) {
  if (!active(db, source)) throw new Error(`no active consent for "${source}". Grant it first: consent_grant {source:"${source}"}`);
}
// Sources whose events the caller may read. Cloud callers can't see sources granted with scope "local".
export const readable = (db, cloud) => [USER, ...consentState(db).filter((c) => c.active && (!cloud || c.scope === 'cloud')).map((c) => c.source)];

export function ledger(db) {
  const rows = db.prepare('SELECT * FROM consent ORDER BY id').all();
  let prev = null, broken_at = null;
  for (const r of rows) {
    if (r.prev_hash !== prev || r.hash !== rowHash(prev, r)) { broken_at ??= r.id; }
    prev = r.hash;
  }
  return { ok: broken_at === null, broken_at, rows: rows.map(({ prev_hash, ...r }) => r) };
}

// ---------- pseudonyms + redaction (runs at ingest; body_redacted is the only text indexed or sent to cloud models) ----------
export const normPhone = (s) => {
  const d = String(s || '').replace(/[^\d+]/g, '');
  const digits = d.replace(/\D/g, '');
  if (digits.length < 7) return digits || null;      // short codes stay as-is
  if (d.startsWith('+')) return '+' + digits;
  if (digits.length === 10) return '+1' + digits;     // US default
  if (digits.length === 11 && digits[0] === '1') return '+' + digits;
  return '+' + digits;
};

// [P:n] / [E:n]: n is a stable number per value. The table stores salted hashes, so it holds no phone numbers.
export function pseudo(db, kind, value) {
  const h = sha(db.salt + kind + value);
  db.prepare('INSERT OR IGNORE INTO pseudo(h) VALUES(?)').run(h);
  return `[${kind}:${db.prepare('SELECT id FROM pseudo WHERE h=?').get(h).id}]`;
}

const luhn = (n) => [...n].reverse().reduce((s, c, i) => { let d = +c; if (i % 2) { d *= 2; if (d > 9) d -= 9; } return s + d; }, 0) % 10 === 0;
const PHONE = /(?<![\w+])(?:\+\d{1,3}[\s.-]?)?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}(?!\w)|\+\d{8,15}\b/g;

export function redact(db, text) {
  if (!text) return text ?? null;
  return String(text)
    .replace(/\b(code|otp|passcode|verification|pin)(\D{0,20})\d{4,8}\b/gi, '$1$2[OTP]')
    .replace(/\b(password|passwd|passcode|pwd|pass)(\s*(?:is|:|=)\s*)\S+/gi, '$1$2[PASSWORD]')
    .replace(/\b(?:\d[ -]?){13,19}\b/g, (m) => (luhn(m.replace(/\D/g, '')) ? '[CARD]' : m))
    .replace(/\b\d{3}-\d{2}-\d{4}\b/g, '[SSN]')
    .replace(/[\w.+-]+@[\w-]+\.[\w.-]+/g, (m) => pseudo(db, 'E', m.toLowerCase()))
    .replace(/https?:\/\/([^\s/?#]+)([^\s?#]*)\?\S*/g, (m, host, path) => `https://${host}${/\d{6,}|[\w-]{25,}/.test(path) ? '/…' : path}?…`) // query dropped; a readable path ("/hangboard-training-guide/") stays
    .replace(PHONE, (m) => pseudo(db, 'P', normPhone(m)));
}
export const redactAlias = (db, a) => (a.kind === 'phone' ? pseudo(db, 'P', a.value) : a.kind === 'email' ? pseudo(db, 'E', a.value) : a.value);

// ---------- entities and events ----------
export function entity(db, type, name) {
  db.prepare('INSERT OR IGNORE INTO entity(type,name,created_at) VALUES(?,?,?)').run(type, name, Date.now());
  return db.prepare('SELECT id FROM entity WHERE type=? AND name=?').get(type, name).id;
}

// Find or create a person by any alias (exact match only; no fuzzy merging). Unknown numbers get a pseudonymous name.
export function person(db, source, name, aliases = []) {
  aliases = aliases.filter((a) => a.value).map((a) => ({ ...a, value: a.kind === 'phone' ? normPhone(a.value) : a.kind === 'email' ? a.value.toLowerCase() : a.value }));
  let id;
  for (const a of aliases) id ??= db.prepare('SELECT entity_id FROM alias WHERE kind=? AND value=?').get(a.kind, a.value)?.entity_id;
  if (!id && name) id = db.prepare(`SELECT id FROM entity WHERE type='person' AND name=?`).get(name)?.id;
  const label = name || (aliases[0] ? `Unknown ${redactAlias(db, aliases[0])}` : 'Unknown');
  if (!id) id = entity(db, 'person', label);
  else if (name) { // a real name upgrades an "Unknown [P:n]" placeholder
    const cur = db.prepare('SELECT name FROM entity WHERE id=?').get(id).name;
    if (cur.startsWith('Unknown') && !db.prepare(`SELECT 1 FROM entity WHERE type='person' AND name=?`).get(name))
      db.prepare('UPDATE entity SET name=? WHERE id=?').run(name, id);
  }
  for (const a of aliases) db.prepare('INSERT OR IGNORE INTO alias(entity_id,kind,value,source) VALUES(?,?,?,?)').run(id, a.kind, a.value, source);
  return id;
}

// Add one event with its participants [[entity_id, role]]. Re-ingesting the same ext_id is a no-op.
export function addEvent(db, e, who = []) {
  const r = db.prepare(`INSERT OR IGNORE INTO event(source,kind,ts,ts_end,title,body,body_redacted,trusted,ext_id) VALUES(?,?,?,?,?,?,?,?,?)`)
    .run(e.source, e.kind, e.ts, e.ts_end ?? null, redact(db, e.title), e.body ?? null, redact(db, e.body), // titles (invites with dial-ins) are redacted too
      e.source === USER ? 1 : 0, e.ext_id ?? sha(`${e.kind}|${e.ts}|${e.title}|${e.body}`).slice(0, 24));
  if (!r.changes) return null;
  for (const [id, role] of who) if (id) db.prepare('INSERT OR IGNORE INTO event_entity VALUES(?,?,?)').run(r.lastInsertRowid, id, role);
  return Number(r.lastInsertRowid);
}

// Like addEvent, but a source row that changed (an edited calendar event: new time, title or attendees) replaces our copy
// instead of being ignored. Unchanged rows are left alone and keep their id. Returns the new id, or null if unchanged.
export function putEvent(db, e, who = []) {
  const cur = db.prepare('SELECT id, ts, ts_end, title, body FROM event WHERE source=? AND ext_id=?').get(e.source, String(e.ext_id));
  if (cur) {
    const links = (id) => db.prepare('SELECT entity_id, role FROM event_entity WHERE event_id=?').all(id).map((r) => `${r.entity_id}:${r.role}`).sort().join();
    const want = [...new Set(who.filter(([id]) => id).map(([id, role]) => `${id}:${role}`))].sort().join();
    if (cur.ts === e.ts && cur.ts_end === (e.ts_end ?? null) && cur.title === redact(db, e.title) && cur.body === (e.body ?? null) && links(cur.id) === want) return null;
    db.prepare('DELETE FROM event WHERE id=?').run(cur.id);
  }
  return addEvent(db, e, who);
}

// The phone deleted some rows: drop our copies of `source` whose ext_id isn't in `live` (the source's current ids).
// Think of it as checking the catalogue against the shelf: a card whose book is gone is thrown away. Returns how many.
export function prune(db, source, live) {
  const ids = new Set([...live].map(String)), del = db.prepare('DELETE FROM event WHERE id=?');
  let n = 0;
  for (const r of db.prepare('SELECT id, ext_id FROM event WHERE source=?').all(source)) if (!ids.has(r.ext_id)) n += del.run(r.id).changes;
  if (n) gcEntities(db);
  return n;
}

// Relations are derived: wipe and recompute from events, so revoked data can't leave edges behind.
const EDGE = `CASE WHEN ee.role='place' THEN 'visits' WHEN ee.role='app' THEN 'notified_by' ELSE
  CASE e.kind WHEN 'message' THEN 'messages' WHEN 'media' THEN 'messages' WHEN 'call' THEN 'calls' WHEN 'calendar' THEN 'meets' WHEN 'note' THEN 'noted' ELSE e.kind END END`;
export function rebuildRelations(db) {
  db.exec('DELETE FROM relation');
  db.prepare(`INSERT INTO relation SELECT :me, ee.entity_id, ${EDGE}, count(*), min(e.ts), max(e.ts), e.source
    FROM event e JOIN event_entity ee ON ee.event_id=e.id WHERE ee.entity_id!=:me GROUP BY ee.entity_id, 3, e.source`).run({ me: db.me });
  db.prepare(`INSERT INTO relation SELECT :me, entity_id, 'knows', 1, NULL, NULL, 'contacts' FROM alias WHERE source='contacts' GROUP BY entity_id`).run({ me: db.me });
  db.exec(`INSERT INTO relation SELECT p.entity_id, g.entity_id, 'member_of', count(*), min(e.ts), max(e.ts), e.source
    FROM event e JOIN event_entity p ON p.event_id=e.id AND p.role='from' JOIN event_entity g ON g.event_id=e.id AND g.role='group'
    GROUP BY 1, 2, e.source`);
}
