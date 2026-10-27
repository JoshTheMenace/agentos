#!/usr/bin/env node
// Phone-side action latency, no model: how long open_app / tap / intent take to return their screen (mostly settle()).
// Holds the device lock for the whole (short) run; the lease card is off for it, like bench.js.
//   node bench/settle.js [--label old] [--n 5]   -> results/settle-<label>.jsonl
import { execFileSync } from 'node:child_process';
import { appendFileSync, mkdirSync, rmdirSync } from 'node:fs';

const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d; };
const S = process.env.SERIAL || 'ZY22J3TK8S', LOCK = '/tmp/agentos-device.lock', sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const su = (c) => execFileSync('adb', ['-s', S, 'shell', `su -c '${c.replace(/'/g, `'\\''`)}'`], { encoding: 'utf8' }).trim();
const SETTINGS = '/data/data/local.agentos/files/settings.json';
for (let i = 0; ; i++) { try { mkdirSync(LOCK); break; } catch { if (i > 450) throw new Error('lock busy'); await sleep(2000); } }
try {
  su('input keyevent WAKEUP; sleep 0.5; input swipe 540 1900 540 600 200'); // no PIN on the test phone; adb callers can't unlock through the broker
  const PHONE = su('cat /data/data/local.agentos/files/token');
  execFileSync('adb', ['-s', S, 'forward', 'tcp:17777', 'tcp:7777']);
  const rpc = async (method, params = {}) => (await (await fetch('http://127.0.0.1:17777/rpc', { method: 'POST',
    headers: { authorization: `Bearer ${PHONE}`, 'content-type': 'application/json' }, body: JSON.stringify({ method, params }) })).json());
  su(`echo '{"require_user_lease":false}' > ${SETTINGS}; chown $(stat -c %u /data/data/local.agentos) ${SETTINGS}; chcon $(stat -c %C /data/data/local.agentos/files) ${SETTINGS}`);
  const CALC = 'com.google.android.calculator', CLOCK = 'com.google.android.deskclock', CHROME = 'com.android.chrome';
  const { task_id } = await rpc('task.start', { goal: 'settle bench', apps: [CALC, CLOCK, CHROME] });
  const time = async (name, method, params) => { const t0 = Date.now(), r = await rpc(method, { ...params, task_id }); return [name, Date.now() - t0, r?.ok !== false, r?.screen?.nodes?.length ?? 0, r?.screen]; };
  const find = (scr, re) => scr?.nodes?.find((n) => re.test(`${n.text || ''}|${n.desc || ''}`))?.id;
  const label = arg('label', 'run'), out = new URL(`./results/settle-${label}.jsonl`, import.meta.url);
  mkdirSync(new URL('./results/', import.meta.url), { recursive: true });
  for (let i = 0; i < Number(arg('n', 5)); i++) {
    const row = [];
    su('input keyevent HOME'); await sleep(800);
    let r = await time('open_app calc', 'open_app', { pkg: CALC }); row.push(r);
    const seven = find(r[4], /^7\|/);
    if (seven != null) { r = await time('tap calc 7', 'tap', { id: seven }); row.push(r); }
    const plus = find(r[4], /^\+\||\|plus$/i);
    if (plus != null) row.push(await time('tap calc +', 'tap', { id: plus }));
    r = await time('open_app clock', 'open_app', { pkg: CLOCK }); row.push(r);
    const tab = find(r[4], /^Timers?\|/i);
    if (tab != null) row.push(await time('tap clock tab', 'tap', { id: tab }));
    row.push(await time('intent search', 'intent', { kind: 'search', query: `weather ${i}` }));
    const line = { label, at: new Date().toISOString(), steps: row.map(([n, ms, ok, nodes]) => ({ n, ms, ok, nodes })) };
    appendFileSync(out, JSON.stringify(line) + '\n');
    console.log(row.map(([n, ms, ok, nodes]) => `${n} ${ms}ms${ok ? '' : ' FAIL'} (${nodes})`).join(' · '));
  }
  await rpc('task.stop', { task_id });
} finally { try { su(`rm -f ${SETTINGS}; input keyevent HOME`); } catch {} rmdirSync(LOCK); }
