import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { test } from 'node:test';
import assert from 'node:assert/strict';

const source = readFileSync(new URL('./app.js', import.meta.url), 'utf8');
function fixture() {
  const controls = { '#kill': {}, '#shstop': {} }, events = {}, started = [], sent = [], c = {};
  const S = { turn: true, queue: [['first'], ['second']] };
  let release;
  const rpc = new Promise((resolve) => { release = resolve; });
  const prefixes = ["$('#kill').onclick", "$('#shstop').onclick", "  c.onclick = async (e) => { const a = e.target.closest('[data-a]')", "on('turn.done',"];
  const code = prefixes.map((p) => { const line = source.split('\n').find((l) => l.startsWith(p)); assert.ok(line, p); return line; }).join('\n');
  runInNewContext(code, { S, c, t: { task_id: 'active' }, $: (s) => controls[s], on: (e, f) => { events[e] = f; },
    send: (m) => sent.push(m), sendUser: (...a) => started.push(a), rpc: () => rpc, hap() {}, refresh() {}, setSheet() {}, live() {} });
  return { S, controls, events, started, sent, c, release };
}

for (const control of ['#kill', '#shstop', 'home']) test(`${control} cancels queued asks before the interrupted turn completes`, async () => {
  const f = fixture();
  const pending = (control === 'home' ? f.c : f.controls[control]).onclick({ target: { closest: () => ({ dataset: { a: 'stop' } }) } });
  f.events['turn.done']({ status: 'interrupted' }); // arrives while the broker's task.stop RPC is still pending
  f.release(); await pending;
  assert.equal(f.S.queue.length, 0);
  assert.deepEqual(f.started, []);
  assert.equal(f.sent[0].type, 'stop');
});

test('ordinary completion still starts exactly one queued ask', () => {
  const f = fixture(); f.events['turn.done']({ status: 'completed' });
  assert.deepEqual(f.started, [['first']]); assert.deepEqual(f.S.queue, [['second']]);
});
