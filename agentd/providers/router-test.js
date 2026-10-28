// node --test agentd/providers/router-test.js (no phone, credentials or external requests)
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { reachability, Router } from './router.js';

test('reachability caches, detects lost internet with a live interface, and recovers', async () => {
  let time = 0, up = true, calls = 0;
  const online = reachability({ forced: () => false, now: () => time, interfaces: () => ({ wlan: [{ internal: false, address: '192.0.2.1' }] }),
    probe: async () => { calls++; if (!up) throw new Error('DNS unavailable'); return true; } });
  assert.equal(await online(), true);
  up = false;
  assert.equal(await online(), true);
  assert.equal(calls, 1);
  assert.equal(await online(true), false);
  up = true; time = 1001;
  assert.equal(await online(), true);
  assert.equal(calls, 3);
});

test('no interface and force-offline bypass probes; address changes invalidate the cache', async () => {
  let forced = false, address = 'fe80::1', calls = 0;
  const online = reachability({ forced: () => forced, interfaces: () => ({ wlan: [{ address }], lo: [{ address: '127.0.0.1', internal: true }] }),
    probe: async () => { calls++; return true; } });
  assert.equal(await online(), false);
  address = '2001:db8::1'; assert.equal(await online(), true);
  address = '2001:db8::2'; assert.equal(await online(), true);
  forced = true; assert.equal(await online(), false);
  forced = false; assert.equal(await online(), true);
  assert.equal(calls, 2);
});

test('concurrent reachability checks share one probe', async () => {
  let calls = 0;
  const online = reachability({ forced: () => false, interfaces: () => ({ wlan: [{ address: '192.0.2.1' }] }), probe: async () => { calls++; return true; } });
  assert.deepEqual(await Promise.all([online(), online(), online(true)]), [true, true, true]);
  assert.equal(calls, 1);
});

function fixture() {
  const seen = [], controller = new AbortController();
  let up = true, cloudCalls = 0, localCalls = 0;
  const codex = { id: 'codex', available: () => true, stop: async () => {}, run: async () => { cloudCalls++; return { status: 'completed', text: 'cloud' }; } };
  const local = { id: 'local', run: async (_session, text, ctx) => {
    localCalls++; assert.equal(text, 'How much battery do I have?'); assert.equal(ctx.provider, 'local');
    ctx.emit({ type: 'text.delta', text: 'Battery is at 80%.' }); return { status: 'completed' };
  } };
  const router = new Router(codex, local, async () => up);
  const ctx = { turn: { goal: 'How much battery do I have?' }, provider: 'codex', signal: controller.signal, emit: (m) => seen.push(m) };
  return { codex, local, router, ctx, seen, controller, offline: () => { up = false; }, online: () => { up = true; }, counts: () => [cloudCalls, localCalls] };
}

test('ordinary asks return to Codex automatically; explicit provider choices stay intact', async () => {
  const f = fixture();
  assert.equal((await f.router.run({}, 'brief', f.ctx)).provider, 'codex');
  f.offline();
  assert.equal(await f.router.select(), f.local);
  assert.equal((await f.router.run({}, 'brief', f.ctx)).text, 'Battery is at 80%.');
  assert.equal(f.ctx.provider, 'local');
  f.online();
  assert.equal((await f.router.run({}, 'brief', f.ctx)).provider, 'codex');
  assert.deepEqual(f.counts(), [2, 1]);
  const claude = { id: 'claude' };
  assert.equal(await f.router.select(claude), claude);
  assert.equal(await f.router.select(f.local), f.local);
});

test('a Codex connection failure also probes and falls back', async () => {
  const f = fixture();
  f.codex.run = async () => { f.offline(); throw new Error('DNS lookup failed'); };
  assert.equal((await f.router.run({}, 'brief', f.ctx)).provider, 'local');
});

test('native connection errors interrupt the cloud turn before local starts', async () => {
  const f = fixture(); let ended = false;
  f.codex.run = async (_session, _text, ctx) => {
    f.offline();
    ctx.emit({ type: 'error', message: 'stream disconnected', retryable: true });
    await new Promise((resolve) => ctx.signal.addEventListener('abort', resolve, { once: true }));
    ended = true; return { status: 'interrupted' };
  };
  const run = f.local.run;
  f.local.run = (...args) => { assert.equal(ended, true); return run(...args); };
  assert.equal((await f.router.run({}, 'brief', f.ctx)).provider, 'local');
  assert.equal(f.controller.signal.aborted, false); // only the cloud attempt is interrupted
});

for (const progress of [{ type: 'tool.request', tier: 'write' }, { type: 'text.delta', text: 'Already answering' }, 'acted'])
  test(`disconnect never replays after ${typeof progress === 'string' ? progress : progress.type}`, async () => {
    const f = fixture();
    f.codex.run = async (_session, _text, ctx) => {
      if (progress === 'acted') ctx.turn.acted = true; else ctx.emit(progress);
      f.offline(); return { status: 'failed', error: 'network unreachable' };
    };
    assert.equal((await f.router.run({}, 'brief', f.ctx)).provider, 'codex');
    assert.equal(f.counts()[1], 0);
  });

test('a cloud outage with working internet does not downgrade', async () => {
  const f = fixture();
  f.codex.run = async () => ({ status: 'failed', error: 'connection closed' });
  assert.equal((await f.router.run({}, 'brief', f.ctx)).provider, 'codex');
  assert.equal(f.counts()[1], 0);
});

test('user cancellation does not trigger fallback', async () => {
  const f = fixture();
  f.codex.run = async () => { f.offline(); f.controller.abort(); return { status: 'failed', error: 'network unreachable' }; };
  assert.equal((await f.router.run({}, 'brief', f.ctx)).provider, 'codex');
  assert.equal(f.counts()[1], 0);
});

test('unsupported photos fail clearly without calling either model offline', async () => {
  const f = fixture(); f.offline(); f.ctx.images = ['data:image/jpeg;base64,AA=='];
  const r = await f.router.run({}, 'brief', f.ctx);
  assert.match(r.error, /Photos need an internet connection/);
  assert.deepEqual(f.counts(), [0, 0]);
});
