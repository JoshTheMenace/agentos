// Memory v2 tests (no phone, no model): node agentd/memory/test-v2.js
process.env.MEMORY_FIXTURES = new URL('./fixtures', import.meta.url).pathname;
const { test } = await import('node:test');
const assert = (await import('node:assert/strict')).default;
const S = await import('./store.js');
S.flags.v2 = true;
const M = await import('./index.js');
const F = await import('./facts.js');
const I = await import('./identity.js');
const { build, DIFF, SAME } = await import('./eval/world2.js');

const db = S.open(':memory:');
build(db);
const ctx = { db, cloud: false, actor: 'user:test' };
const id = (name) => db.prepare(`SELECT id FROM entity WHERE type='person' AND name=?`).get(name)?.id;

test('rules keep durable facts and drop trivia', () => {
  const r = M.syncFacts(ctx);
  assert.ok(db.prepare(`SELECT count(*) n FROM fact`).get().n >= 15, `facts ${r.facts}`);
  const all = db.prepare('SELECT obj FROM fact').all().map((f) => f.obj.toLowerCase());
  for (const bad of ['uber', 'prius', 'verification', 'doordash', 'lyft', 'omw']) assert.ok(!all.some((o) => o.includes(bad)), `trivia kept: ${bad}`);
  assert.equal(M.syncFacts(ctx).facts, 0, 'incremental: nothing new on the second run');
});

test('temporal: a move supersedes, history stays', () => {
  const now = M.facts({ about: 'Mom' }, ctx), hist = M.facts({ about: 'Mom', history: true }, ctx);
  assert.equal(now.find((f) => f.pred === 'lives_in')?.obj, 'Boise');
  assert.ok(hist.some((f) => f.pred === 'lives_in' && f.obj === 'Provo' && f.status === 'superseded'));
  const alex = M.facts({ about: 'Alex Rivera', history: true }, ctx).filter((f) => f.pred === 'works_at');
  assert.deepEqual(alex.map((f) => [f.obj, f.status]), [['Figma', 'active'], ['Adobe', 'superseded']]);
});

test('identity: hard evidence merges, names alone stay "maybe", the owner settles it', () => {
  M.contextLines('hello', ctx); // builds links
  const g = I.groups(db);
  assert.equal(g.canon(id('Linda Gimenes')), g.canon(id('Mom')), 'email name + owner note "my mom is Linda Gimenes" = same');
  for (const [a, b] of DIFF) if (id(a) && id(b)) assert.notEqual(g.canon(id(a)), g.canon(id(b)), `wrong merge ${a} / ${b}`);
  assert.equal(g.status(id('Joe'), id('Joe Smith')), 'maybe');
  assert.ok(M.identity.pending(ctx, 50).some((p) => /Joe/.test(p.question)));
  assert.equal(M.identity.target('Joe', ctx).ok, false, 'two Joes: ask');
  M.identity.confirm(id('Joe'), id('Joe Smith'), false, ctx);
  assert.equal(I.groups(db).status(id('Joe'), id('Joe Smith')), 'none', 'the owner said no: pinned apart');
  assert.equal(M.identity.target('Joe', ctx).ok, false, 'still two people, still ask');
  M.identity.confirm(id('Joe'), id('Joe Smith'), true, ctx);
  assert.equal(M.identity.target('Joe', ctx).ok, true, 'the owner said yes: one person');
});

test('actions to an ambiguous name say so; facts and the digest reach the context block', () => {
  const t = M.contextLines('message Chris Park about the deploy', ctx).map((l) => l.text).join('\n');
  assert.match(t, /Ambiguous/);
  assert.doesNotMatch(M.contextLines("tell Mom I'll call her", ctx).map((l) => l.text).join('\n'), /Ambiguous/);
  assert.match(M.contextLines('what do you know about me?', ctx).map((l) => l.text).join('\n'), /shellfish/);
  const p = M.person('Joe Smith', ctx);
  assert.equal(p.relationship, 'cousin');
  assert.ok(p.facts.every((f) => f.event_id), 'every fact cites its source event');
});

test('owner controls: correct and forget a fact; revoking a source drops its facts', async () => {
  const f = M.facts({ q: 'coffee' }, ctx).find((x) => x.pred.startsWith('favorite'));
  assert.ok(f);
  M.correct(f.id, { obj: 'Publik Coffee' }, ctx);
  assert.equal(M.facts({ q: 'coffee' }, ctx).find((x) => x.id === f.id).obj, 'Publik Coffee');
  M.correct(f.id, { retract: true }, ctx);
  assert.ok(!M.facts({ q: 'coffee' }, ctx).some((x) => x.id === f.id));
  const before = db.prepare(`SELECT count(*) n FROM fact WHERE source='sms'`).get().n;
  assert.ok(before > 0);
  await M.call('consent_revoke', { source: 'sms' }, ctx);
  assert.equal(db.prepare(`SELECT count(*) n FROM fact WHERE source='sms'`).get().n, 0, 'facts follow their source');
});

test('v1 world still answers under v2 (38 tuning questions, retrieval hit@5)', async () => {
  const d2 = S.open(':memory:');
  (await import('./eval/world.js')).build(d2);
  const { QUESTIONS } = await import('./eval/questions.js');
  const ext = (i) => d2.prepare('SELECT ext_id FROM event WHERE id=?').get(i)?.ext_id;
  let hit = 0, n = 0;
  for (const q of QUESTIONS) {
    if (!q.gold.length) continue; n++;
    const ids = M.ask(q.q, { db: d2 }).events.slice(0, 5).map((e) => ext(e.id));
    if (ids.some((x) => q.gold.some((g) => (g.endsWith('*') ? x?.startsWith(g.slice(0, -1)) : g === x)))) hit++;
  }
  assert.equal(hit, n, `${hit}/${n}`);
});
