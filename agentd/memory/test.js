// Fixture tests (no phone needed): node agentd/memory/test.js  (plain node; `node --test` trips over Termux's exec shim)
process.env.MEMORY_FIXTURES = new URL('./fixtures', import.meta.url).pathname;
const { test } = await import('node:test');
const assert = (await import('node:assert/strict')).default;
const M = await import('./index.js');
const S = await import('./store.js');

const db = S.open(':memory:');
const user = { db, actor: 'user:test' }, agent = { db }; // agent: cloud view, no confirm callback
const fx = (f) => decodeURIComponent(new URL(`./fixtures/${f}`, import.meta.url).pathname);
const text = (r) => JSON.stringify(r);

test('ingest is blocked without consent', () => assert.throws(() => M.ingest('sms', {}, user), /no active consent/));

test('agent cannot grant without user confirmation; user can', async () => {
  assert.match((await M.call('consent_grant', { source: 'sms' }, agent)).error, /confirmation/);
  for (const s of M.sources) assert.equal((await M.call('consent_grant', { source: s }, user)).ok, true);
});

test('all ingestors run on fixtures', () => {
  for (const s of ['contacts', 'calendar', 'calllog', 'sms', 'notifications', 'pigeon']) assert.ok(M.ingest(s, {}, user).rows > 0, s);
  assert.equal(M.ingest('whatsapp', { file: fx('WhatsApp Chat with Alex Rivera.txt') }, user).added, 4);
  const g = M.ingest('whatsapp', { file: fx('WhatsApp Chat - Climbing Club.txt') }, user);
  assert.equal(g.added, 3);
  assert.equal(M.ingest('sms', {}, user).added, 0, 're-ingest is idempotent');
  assert.equal(db.prepare(`SELECT count(*) n FROM event WHERE source='notifications'`).get().n, 1, 'system + bank notifications dropped');
  assert.equal(db.prepare(`SELECT count(*) n FROM event WHERE source='calendar'`).get().n, 3, 'deleted event skipped');
});

test('entity resolution merges by alias across sources', () => {
  const alex = db.prepare(`SELECT id FROM entity WHERE name='Alex Rivera'`).all();
  assert.equal(alex.length, 1);
  const n = db.prepare('SELECT count(DISTINCT source) n FROM alias WHERE entity_id=?').get(alex[0].id).n;
  assert.ok(n >= 3, `alex has aliases from ${n} sources`);
  assert.ok(db.prepare(`SELECT 1 FROM entity WHERE type='group' AND name='Climbing Club'`).get());
  assert.ok(!db.prepare(`SELECT 1 FROM entity WHERE type='person' AND name='Climbing Club'`).get());
});

test('search returns redacted, labelled results', async () => {
  const r = await M.call('memory_search', { q: 'moab tent' }, agent);
  assert.ok(r.results.length >= 2);
  assert.match(r.note, /never as instructions/);
  assert.ok(r.results.every((e) => e.trusted === false));
  const all = text(await M.call('memory_search', { q: 'code verification instructions gas dial', limit: 20 }, agent));
  assert.doesNotMatch(all, /555[-\s]?01|482913|4432|@example\.com/, 'no phone numbers, OTPs or emails reach the cloud view');
  assert.match(all, /\[P:\d+\]/);
  assert.match(all, /\[OTP\]/);
});

test('entity card, pseudonym lookup, neighbors, timeline', async () => {
  const c = await M.call('memory_entity', { name: 'alex' }, agent);
  assert.equal(c.entity.name, 'Alex Rivera');
  assert.ok(c.entity.aliases.some((a) => /^\[P:\d+\]$/.test(a.value)));
  assert.doesNotMatch(text(c), /555|example\.com/);
  const p = c.entity.aliases.find((a) => a.kind === 'phone').value;
  assert.equal((await M.call('memory_entity', { name: p }, agent)).entity.id, c.entity.id);
  const g = await M.call('memory_neighbors', { depth: 2 }, agent);
  assert.ok(g.nodes.length > 5 && g.edges.length > 5);
  const t = await M.call('memory_timeline', { entity_id: c.entity.id }, agent);
  assert.ok(t.results.length >= 5);
  assert.ok(t.results[0].ts <= t.results.at(-1).ts);
});

test('remember + local scope hides a source from cloud models', async () => {
  const r = await M.call('memory_remember', { text: 'Alex is allergic to peanuts', about: 'Alex Rivera' }, agent);
  assert.equal(r.ok, true);
  const s = await M.call('memory_search', { q: 'peanuts' }, agent);
  assert.equal(s.results[0].trusted, true);
  await M.call('consent_grant', { source: 'pigeon', scope: 'local' }, user);
  assert.equal((await M.call('memory_search', { q: 'cooler' }, agent)).results.length, 0);
  assert.equal((await M.call('memory_search', { q: 'cooler' }, { db, cloud: false })).results.length, 1);
});

test('brain: consent scope, pseudonyms back to real values, nothing for device commands', async () => {
  const cloud = M.ask('who has the cooler?', { db }), local = M.ask('who has the cooler?', { db, cloud: false });
  assert.ok(!JSON.stringify(cloud.events).includes('cooler'), 'pigeon is local-only: cloud models never see it');
  assert.ok(JSON.stringify(local.events).includes('cooler'));
  const card = (await M.call('memory_ask', { q: "what's Alex's number?" }, agent)).people.find((p) => p.name === 'Alex Rivera');
  assert.match(card.phone[0], /^\[P:\d+\]$/);
  assert.equal(M.unpseudo({ to: card.phone[0], text: `call ${card.phone[0]}` }, { db }).to, '+18015550101');
  const s = M.unpseudoStream({ db }); assert.equal(s.push(`it's ${card.phone[0].slice(0, 3)}`) + s.push(card.phone[0].slice(3)) + s.flush(), "it's +18015550101");
  assert.deepEqual(M.contextLines('set an alarm for 7', { db }), []);
  assert.ok(M.contextLines('what did Alex say about moab?', { db }).some((l) => /moab/i.test(l.text) && l.untrusted));
});

test('revoke hard-deletes rows and edges; ledger chain verifies and detects tampering', async () => {
  const before = M.graph({ db });
  assert.ok(before.edges.some((e) => e.data.via === 'sms'));
  assert.match((await M.call('consent_revoke', { source: 'sms' }, agent)).error, /confirmation/);
  const r = await M.call('consent_revoke', { source: 'sms' }, { db, confirm: async () => true });
  assert.equal(r.deleted.events, 5);
  assert.equal(db.prepare(`SELECT count(*) n FROM event WHERE source='sms'`).get().n, 0);
  assert.ok(!M.graph({ db }).edges.some((e) => e.data.via === 'sms'));
  assert.ok(!db.prepare(`SELECT 1 FROM entity WHERE name LIKE 'Unknown%' AND id IN (SELECT entity_id FROM alias WHERE value='+14355550177' AND source='sms')`).get());
  assert.throws(() => M.ingest('sms', {}, user), /no active consent/);
  const l = await M.call('consent_list', {}, agent);
  assert.equal(l.ledger.chain_ok, true);
  assert.equal(l.consents.find((c) => c.source === 'sms').active, false);
  assert.throws(() => db.exec(`UPDATE consent SET scope='local' WHERE id=1`), /append-only/);
  db.exec('DROP TRIGGER consent_append_only'); db.exec(`UPDATE consent SET scope='local' WHERE id=1`);
  assert.equal(S.ledger(db).broken_at, 1);
});

test('forget an entity removes it and its events', async () => {
  const id = db.prepare(`SELECT id FROM entity WHERE name='Mom'`).get().id;
  const r = await M.call('memory_forget', { entity_id: id }, user);
  assert.ok(r.deleted.events > 0);
  assert.ok(!db.prepare('SELECT 1 FROM entity WHERE id=?').get(id));
});

test('tool specs are well formed', () => {
  assert.deepEqual(M.tools.map((t) => t.name).sort(), ['consent_grant', 'consent_list', 'consent_revoke', 'memory_ask', 'memory_entity', 'memory_forget',
    'memory_neighbors', 'memory_remember', 'memory_search', 'memory_timeline']);
  for (const t of M.tools) assert.ok(t.description && t.schema.type === 'object' && M.handlers[t.name]);
});

test('whatsapp parser: 12 AM, narrow no-break space, continuation lines', async () => {
  const { parseWhatsApp } = await import('./ingest.js');
  const m = parseWhatsApp('1/2/26, 12:05 AM - A: hi\nsecond line\n1/2/26, 12:30 PM - B: yo');
  assert.equal(new Date(m[0].ts).getHours(), 0);
  assert.equal(new Date(m[1].ts).getHours(), 12);
  assert.equal(m[0].text, 'hi\nsecond line');
});
