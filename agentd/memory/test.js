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
  assert.deepEqual(M.tools.map((t) => t.name).sort(), ['consent_grant', 'consent_list', 'consent_revoke', 'health_query', 'health_summary', 'memory_ask', 'memory_entity', 'memory_forget',
    'memory_neighbors', 'memory_remember', 'memory_search', 'memory_timeline', ...(S.flags.v2 ? ['memory_confirm_identity', 'memory_correct', 'memory_facts', 'memory_identity', 'memory_person'] : [])].sort());
  for (const t of M.tools) assert.ok(t.description && t.schema.type === 'object' && M.handlers[t.name]);
});

test('whatsapp parser: 12 AM, narrow no-break space, continuation lines', async () => {
  const { parseWhatsApp } = await import('./ingest.js');
  const m = parseWhatsApp('1/2/26, 12:05 AM - A: hi\nsecond line\n1/2/26, 12:30 PM - B: yo');
  assert.equal(new Date(m[0].ts).getHours(), 0);
  assert.equal(new Date(m[1].ts).getHours(), 12);
  assert.equal(m[0].text, 'hi\nsecond line');
});

test('deletes and edits on the phone reach memory (calendar, contacts, sms, alarms, direct API writes)', async () => {
  const fs = await import('node:fs'), { join } = await import('node:path'), C = await import('./connectors/index.js'), { liveIds } = await import('./ingest.js');
  const dir = fs.mkdtempSync(join((await import('node:os')).tmpdir(), 'agentos-fx-')), orig = process.env.MEMORY_FIXTURES;
  fs.cpSync(orig, dir, { recursive: true });
  process.env.MEMORY_FIXTURES = dir;
  const edit = (f, fn) => fs.writeFileSync(join(dir, `${f}.txt`), fn(fs.readFileSync(join(dir, `${f}.txt`), 'utf8')));
  const d = S.open(':memory:'), ctx = { db: d, actor: 'user:test' }, cal = () => d.prepare(`SELECT id, ext_id, title FROM event WHERE source='calendar' ORDER BY ext_id`).all();
  try {
    for (const s of ['contacts', 'calendar', 'sms', 'alarms']) S.grant(d, s, { actor: 'user:test' });
    M.ingest('contacts', {}, ctx); M.ingest('calendar', {}, ctx);
    const before = cal();
    assert.deepEqual(before.map((e) => e.ext_id), ['10', '11', '12']);
    assert.equal(M.ingest('calendar', {}, ctx).added, 0, 'unchanged events are left alone');
    assert.deepEqual(cal(), before, 'and keep their ids');
    // Edit 10's title, flag 11 deleted=1 (waiting for sync), remove 12 entirely (purged).
    edit('calendar_events', (t) => t.replace('Moab trip planning', 'Moab trip planning (moved)').replace(/(_id=11,.*)deleted=0/, '$1deleted=1').replace(/^Row: 2 _id=12.*\n/m, ''));
    const r = M.ingest('calendar', {}, ctx);
    assert.deepEqual([r.added, r.removed], [1, 2]);
    assert.deepEqual(cal().map((e) => [e.ext_id, e.title]), [['10', 'Moab trip planning (moved)']], 'edited in place of a duplicate; deleted ones gone');
    assert.equal((await M.call('memory_search', { q: 'judging' }, ctx)).results.length, 0, 'search no longer finds the deleted event');
    // Direct API writes (tools.js -> memory.noteApi) show up before any sync.
    M.noteApi('calendar.add', { attendees: ['priya.shah@example.com'] }, { ok: true, event: { id: 77, title: 'AgentOS test lunch', start_ms: 1792500000000, end_ms: 1792503600000 } }, ctx);
    const priya = d.prepare(`SELECT id FROM entity WHERE name='Priya Shah'`).get().id;
    assert.ok(d.prepare(`SELECT 1 FROM event e JOIN event_entity ee ON ee.event_id=e.id WHERE e.ext_id='77' AND ee.entity_id=?`).get(priya), 'attendee linked by email');
    M.noteApi('calendar.update', { id: 77, title: 'AgentOS test lunch v2' }, { ok: true, event: { id: 77, title: 'AgentOS test lunch v2', start_ms: 1792500000000, end_ms: 1792503600000 } }, ctx);
    assert.equal(d.prepare(`SELECT count(*) n FROM event WHERE source='calendar' AND ext_id='77' AND title='AgentOS test lunch v2'`).get().n, 1);
    assert.ok(d.prepare(`SELECT 1 FROM event e JOIN event_entity ee ON ee.event_id=e.id WHERE e.ext_id='77' AND ee.entity_id=?`).get(priya), 'update keeps attendees');
    M.noteApi('calendar.delete', { id: 77 }, { ok: false }, ctx);
    assert.ok(d.prepare(`SELECT 1 FROM event WHERE ext_id='77'`).get(), 'a failed delete changes nothing');
    M.noteApi('calendar.delete', { id: 77 }, { ok: true, deleted: true }, ctx);
    assert.ok(!d.prepare(`SELECT 1 FROM event WHERE ext_id='77'`).get());
    // Contacts: Sam's number removed, Mom renamed.
    edit('contacts_phones', (t) => t.replace(/^Row: 2 .*\n/m, '').replace('display_name=Mom', 'display_name=Mom Gimenes'));
    assert.equal(M.ingest('contacts', {}, ctx).removed, 1);
    assert.ok(!d.prepare(`SELECT 1 FROM entity WHERE name='Sam Lee'`).get(), 'a contact with no number left is gone');
    assert.ok(d.prepare(`SELECT 1 FROM entity e JOIN alias a ON a.entity_id=e.id WHERE e.name='Mom Gimenes' AND a.value='+18015550102'`).get(), 'renamed, same card');
    // SMS (hourly id diff) and alarms (one row that follows the phone).
    assert.equal(C.sync(d, 'sms').added, 5);
    edit('sms', (t) => t.replace(/^Row: 4 _id=5.*\n/m, ''));
    assert.equal(C.sync(d, 'sms', { force: true }).removed, 1);
    assert.equal(C.sync(d, 'alarms').added, 1);
    edit('next_alarm', () => 'Wed 7:30 AM\n');
    C.sync(d, 'alarms');
    assert.deepEqual(d.prepare(`SELECT title FROM event WHERE source='alarms'`).all().map((e) => e.title), ['Next alarm: Wed 7:30 AM']);
    edit('next_alarm', () => 'null\n');
    assert.equal(C.sync(d, 'alarms').removed, 1);
    assert.equal(d.prepare(`SELECT count(*) n FROM event WHERE source='alarms'`).get().n, 0);
    edit('calendar_events', () => 'Error while accessing provider\n');
    assert.throws(() => M.ingest('calendar', {}, ctx));
    assert.throws(() => liveIds('content://com.android.calendar/events', 'calendar_events'), /failed/);
    assert.equal(cal().length, 1, 'a failed query never looks like "everything was deleted"');
  } finally { process.env.MEMORY_FIXTURES = orig; fs.rmSync(dir, { recursive: true }); }
});
