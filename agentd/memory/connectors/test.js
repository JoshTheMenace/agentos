// Connector tests on fixtures (no phone): node agentd/memory/connectors/test.js
process.env.MEMORY_FIXTURES = new URL('../fixtures', import.meta.url).pathname;
const { test } = await import('node:test');
const assert = (await import('node:assert/strict')).default;
const S = await import('../store.js');
const C = await import('./index.js');
const { DatabaseSync } = await import('node:sqlite');
const { tmpdir } = await import('node:os');
const { join } = await import('node:path');

const db = S.open(':memory:');
const n = (src) => db.prepare('SELECT count(*) n FROM event WHERE source=?').get(src).n;

test('default grants are revocable and never re-granted after a revoke', () => {
  assert.equal(C.grantDefaults(db).length, C.DEFAULT_GRANTS.length);
  assert.equal(C.grantDefaults(db).length, 0);
  S.revoke(db, 'alarms');
  assert.equal(C.grantDefaults(db).length, 0, 'a revoke sticks');
  assert.throws(() => C.sync(db, 'alarms'), /no active consent/);
  S.grant(db, 'alarms', { actor: 'user:test' });
});

test('usage: daily totals per app, private apps and <1 min skipped, re-sync replaces', () => {
  const r = C.sync(db, 'usage');
  assert.deepEqual([r.rows, r.added], [2, 2]);
  assert.ok(!db.prepare(`SELECT 1 FROM entity WHERE name='com.example.bank'`).get());
  assert.match(db.prepare(`SELECT title FROM event WHERE ext_id='com.android.chrome|2026-10-27'`).get().title, /62 min \(5 opens\)/);
  assert.equal(C.sync(db, 'usage', { force: true }).added, 2);
  assert.equal(n('usage'), 2, 'replaced, not duplicated');
});

test('media: only playing sessions; one row per 20-minute slot', () => {
  assert.equal(C.sync(db, 'media').added, 1);
  assert.equal(C.sync(db, 'media', { force: true }).added, 0);
  const e = db.prepare(`SELECT title FROM event WHERE source='media'`).get();
  assert.equal(e.title, 'Shelter · Porter Robinson');
  assert.ok(db.prepare(`SELECT 1 FROM entity WHERE type='artist' AND name='Porter Robinson'`).get());
});

test('photos: timestamps + album only, incremental by date_added', () => {
  assert.deepEqual(C.sync(db, 'photos').added, 3);
  assert.equal(C.sync(db, 'photos').added, 0, 'idempotent (fixtures ignore the where clause)');
  assert.ok(db.prepare(`SELECT 1 FROM event WHERE source='photos' AND title='Screenshot · Screenshots' AND ts=1761503600000`).get());
  assert.equal(C.watermark(db, 'photos').ts, 1761507200);
});

test('alarms', () => {
  assert.equal(C.sync(db, 'alarms').added, 1);
  assert.equal(C.sync(db, 'alarms').added, 0);
});

test('chrome: reads a copy of History, strips query strings, skips private hosts, watermark on visit time', async () => {
  const f = join(tmpdir(), `agentos-test-history-${process.pid}.db`);
  const h = new DatabaseSync(f);
  h.exec(`CREATE TABLE urls(id INTEGER PRIMARY KEY, url TEXT, title TEXT); CREATE TABLE visits(id INTEGER PRIMARY KEY, url INTEGER, visit_time INTEGER);
    INSERT INTO urls VALUES(1,'https://example.com/a?token=secret123','Example A'),(2,'https://mybank.com/login','Bank'),(3,'chrome://flags','Flags');
    INSERT INTO visits VALUES(1,1,13437557697914593),(2,2,13437557697914600),(3,3,13437557697914700),(4,1,13437557697915000)`);
  h.close();
  const r = C.sync(db, 'chrome', { file: f });
  assert.deepEqual([r.rows, r.added], [3, 2]);
  assert.equal(C.sync(db, 'chrome', { file: f }).added, 0);
  const e = db.prepare(`SELECT body, body_redacted FROM event WHERE source='chrome' LIMIT 1`).get();
  assert.doesNotMatch(e.body_redacted, /secret123/);
  assert.match(e.body, /secret123/);
  assert.ok(!db.prepare(`SELECT 1 FROM entity WHERE name='mybank.com'`).get());
  (await import('node:fs')).unlinkSync(f);
});

test('redaction covers passwords', () => {
  assert.equal(S.redact(db, 'the wifi password is hunter2 ok'), 'the wifi password is [PASSWORD] ok');
  assert.equal(S.redact(db, 'Password: Tr0ub4dor&3'), 'Password: [PASSWORD]');
});

test('syncAll reports per-source errors without throwing', () => {
  const out = C.syncAll(db, { force: true });
  assert.ok(out.find((r) => r.source === 'chrome')?.error, 'no History file in tests -> error reported');
  assert.ok(out.find((r) => r.source === 'usage')?.rows === 2);
});
