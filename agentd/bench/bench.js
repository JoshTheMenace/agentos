#!/usr/bin/env node
// Task-latency benchmark on the real phone. Runs on the Mac; drives the bench agentd (bench/up.sh, port 8788 via
// adb forward 18788) and checks results with adb. Every task goes through the normal broker gate.
//   node bench/bench.js [--label baseline] [--runs 2] [--tasks r3 | chrome,calc,...]
// Writes one JSON line per run to bench/results/<label>.jsonl and prints a summary.
import { execFileSync } from 'node:child_process';
import { appendFileSync, mkdirSync, rmSync, rmdirSync, writeFileSync } from 'node:fs';
import WebSocket from 'ws';

const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d; };
const S = process.env.SERIAL || 'ZY22J3TK8S', LOCK = '/tmp/agentos-device.lock';
const adb = (cmd, ms = 20_000) => execFileSync('adb', ['-s', S, 'shell', cmd], { encoding: 'utf8', timeout: ms }).trim();
const su = (cmd) => adb(`su -c '${cmd.replace(/'/g, `'\\''`)}'`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const TOKEN = su('cat /data/data/com.termux/files/home/.agentos-bench/agentd.token');
const PHONE = su('cat /data/data/local.agentos/files/token');
execFileSync('adb', ['-s', S, 'forward', 'tcp:17777', 'tcp:7777']);
const rpc = async (method, params = {}) => (await (await fetch('http://127.0.0.1:17777/rpc', { method: 'POST',
  headers: { authorization: `Bearer ${PHONE}`, 'content-type': 'application/json' }, body: JSON.stringify({ method, params }) })).json());
const screenText = async () => ((await rpc('screen.read'))?.nodes || []).map((n) => `${n.text || ''} ${n.desc || ''}`).join(' | ');
// Chrome reopens its last tab, so start each run on a plain Google page (then go home).
const CHROME_FRESH = () => { su('am force-stop com.android.chrome; am start -a android.intent.action.VIEW -d https://www.google.com/ -p com.android.chrome'); adb('sleep 3; input keyevent HOME'); };
// Lease card = a human tap, not model time: the bench turns it off per task (broker setting) and back on after.
const SETTINGS = '/data/data/local.agentos/files/settings.json';
const leaseOff = () => su(`echo '{"require_user_lease":false}' > ${SETTINGS}; chown $(stat -c %u /data/data/local.agentos) ${SETTINGS}; chcon $(stat -c %C /data/data/local.agentos/files) ${SETTINGS}`);
const leaseOn = () => su(`rm -f ${SETTINGS}`);
// The broker carries a caller's History (taint AND step count) into its next task within 10 min, so back-to-back tasks
// hit max_steps (NEEDS-ANDROID.md). A throwaway lease from another caller (adb) makes the next task start fresh.
const resetHistory = async () => { await rpc('task.start', { apps: [], goal: 'bench reset' }); await rpc('task.stop', {}); };
// A direct API call from the bench itself (checks and cleanup), in its own short lease.
const api = async (apps, method, params = {}) => { const l = await rpc('task.start', { apps, goal: 'bench check' });
  try { return await rpc(method, { ...params, task_id: l.task_id }); } finally { await rpc('task.stop', { task_id: l.task_id }); } };
const PIGEON = (extra = '') => { su(`am force-stop local.agentos.pigeon; am start -n local.agentos.pigeon/.Main --es scenario clean${extra}`); adb('sleep 1; input keyevent HOME'); };

// Removed 10-28 (founder limits): alarm/timer/multi (they wiped Clock with pm clear and left alarms), reply/preply (sends that
// can raise an approval card), alarm set+delete (delete needs approval). Benchmarks never delete, send or wait on a card.
export const TASKS = {
  chrome: { text: 'Search Chrome for the weather in Salt Lake City.', setup: CHROME_FRESH,
    check: async () => { for (let i = 0; i < 6; i++, await sleep(1000)) { const t = await screenText(); if (/weather in salt lake city/i.test(t) && /AI Mode|Images|News/.test(t)) return true; } return false; } }, // results tabs = submitted (the page may still be loading when a 1-call turn ends)
  calc: { text: 'Use the Calculator app to work out 234 times 17.', setup: () => su('am force-stop com.google.android.calculator'),
    check: async (r) => /3,?978/.test(r.answer) && /3,?978/.test(await screenText()) },
  // Plugin tasks (plugins.js), read-only so nothing on the phone changes.
  battery: { text: 'How much battery do I have?', check: async (r) => /\d+\s?%/.test(r.answer) },
  trackers: { text: 'Which trackers does Chrome talk to?', check: async (r) => r.events.some((e) => e.type === 'tool.result' && e.name === 'trackers.scan' && e.ok) },
  // Round 3 (2026-10-28): read-only in real apps; changes only the torch (turned back off) and our own Pigeon/Beacon.
  // No task may need an approval card or delete anything (founder limits 10-28): alarm set+delete tasks were removed.
  pigeon: { text: 'Open Pigeon and tell me Alex\'s last message.', setup: () => PIGEON(), check: async (r) => /wild ending/i.test(r.answer) },
  beacon: { text: 'Open Beacon and tell me the uid it shows.', setup: () => su('am force-stop local.agentos.beacon'),
    check: async (r) => r.answer.includes(su('stat -c %u /data/data/local.agentos.beacon')) },
  torch: { text: 'Turn on the flashlight.', cleanup: () => api(['device'], 'device.set', { torch: false }),
    check: async () => (await api(['device'], 'device.status')).status?.torch === true },
  calendar: { text: 'When is the hackathon judging?', check: async (r) => /\b5(:00)?\s*(PM|p\.m\.)|17:00/i.test(r.answer) },
  notifs: { text: 'Summarize my notifications in one sentence.', private: true, check: async (r) => r.answer.length > 20 },
};

export const R3 = 'pigeon,beacon,torch,calendar,notifs,calc'; // round-3 set (--tasks r3)

// Held per task only, and released for YIELD_MS between tasks so other agents' withphone (2 s polls) get their turn.
// lockWait > 0 means someone else used the phone just before this run (noted in RESULTS.md as "overlapped").
const YIELD_MS = 5000;
async function lock() { // same lock as scripts/withphone (pid inside, so a dead owner's lock can be reclaimed)
  for (let i = 0; i < 450; i++) { try { mkdirSync(LOCK); writeFileSync(`${LOCK}/pid`, String(process.pid)); return i * 2000; } catch { await sleep(2000); } }
  throw new Error('device lock busy for 15 min');
}
const unlock = () => { rmSync(`${LOCK}/pid`, { force: true }); rmdirSync(LOCK); };

function ask(text, timeoutMs = 180_000) {
  return new Promise((resolve) => {
    const ws = new WebSocket('ws://127.0.0.1:18788', { headers: { authorization: `Bearer ${TOKEN}` } });
    const t0 = Date.now(), events = [];
    let answer = '';
    const done = (r) => { clearTimeout(kill); ws.close(); resolve({ ...r, answer, events }); };
    const kill = setTimeout(() => done({ status: 'timeout', ms: Date.now() - t0 }), timeoutMs);
    ws.on('open', () => ws.send(JSON.stringify({ type: 'ask', text })));
    ws.on('error', (e) => done({ status: 'ws error ' + e.message, ms: Date.now() - t0 }));
    ws.on('message', (raw) => {
      const m = JSON.parse(raw);
      if (m.type === 'text.delta') return void (answer += m.text);
      events.push({ ...m, t: Date.now() - t0 });
      if (m.type === 'turn.done') done({ status: m.status, ms: m.ms, prep: m.prep, usage: m.usage });
    });
  });
}

async function runTask(name) {
  const task = TASKS[name];
  const lockWait = await lock();
  try {
    adb('input keyevent HOME'); await sleep(800);
    leaseOff(); await task.setup?.(); await resetHistory(); await sleep(500);
    const r = await ask(task.text);
    const act = r.events.find((e) => e.type === 'tool.request' && e.tier !== 'read');
    const calls = r.usage?.calls || [];
    let ok = false; try { ok = r.status === 'completed' && await task.check(r); } catch (e) { r.checkError = e.message; }
    if (!ok) try { r.checkError ??= (await screenText()).slice(0, 400); } catch {}
    return { task: name, ok, status: r.status, lockWait, prep: r.prep, ms: r.ms, ttfa: act?.t ?? null, calls: calls.length,
      tools: r.events.filter((e) => e.type === 'tool.request').map((e) => `${e.name}${e.args?.id != null ? '#' + e.args.id : ''}`),
      toolMs: r.events.filter((e) => e.type === 'tool.result' && e.name !== 'act').map((e) => e.ms || 0),
      approvalMs: r.events.filter((e) => e.type === 'tool.result' && /approved|denied/.test(e.decision || '')).reduce((a, e) => a + (e.ms || 0), 0),
      inTok: calls.map((c) => c.in), cached: calls.map((c) => c.cached), plugins: r.usage?.plugins, loads: r.events.filter((e) => e.type === 'tool.request' && e.name === 'load_plugin').length, callAt: calls.map((c) => c.at), outTok: calls.map((c) => c.out + (c.reasoning || 0)), answer: task.private ? `[${r.answer.length} chars]` : r.answer.slice(0, 200), checkError: r.checkError };
  } finally { try { await task.cleanup?.(); } catch (e) { console.error(`cleanup ${name}: ${e.message}`); } leaseOn(); try { adb('input keyevent HOME'); } catch {} unlock(); await sleep(YIELD_MS); }
}

const med = (a) => { a = a.filter((x) => x != null).sort((x, y) => x - y); return a.length ? a[Math.floor((a.length - 1) / 2)] : null; };
const sec = (ms) => ms == null ? '–' : (ms / 1000).toFixed(1);

// node bench/bench.js --summary a,b,c: one markdown row per label and task (median of its runs).
if (process.argv.includes('--summary')) {
  const { readFileSync } = await import('node:fs');
  console.log('| setup | task | ok | total s | first action s | model calls | tokens in (1st call) |\n|---|---|---|---|---|---|---|');
  for (const label of arg('summary').split(',')) {
    const rows = readFileSync(new URL(`./results/${label}.jsonl`, import.meta.url), 'utf8').trim().split('\n').map((l) => JSON.parse(l));
    for (const task of [...new Set(rows.map((r) => r.task))]) {
      const t = rows.filter((r) => r.task === task);
      console.log(`| ${label} | ${task} | ${t.filter((r) => r.ok).length}/${t.length} | ${sec(med(t.map((r) => r.ms)))} | ${sec(med(t.map((r) => r.ttfa)))} | ${med(t.map((r) => r.calls))} | ${med(t.map((r) => r.inTok[0]))} |`);
    }
  }
  process.exit(0);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const label = arg('label', 'run'), runs = Number(arg('runs', 1)), names = arg('tasks', Object.keys(TASKS).join(',')).replace('r3', R3).split(',');
  mkdirSync(new URL('./results/', import.meta.url), { recursive: true });
  const out = new URL(`./results/${label}.jsonl`, import.meta.url), rows = [];
  await ask('Say ok.', 60_000); // warm app-server + network; not timed
  for (let i = 0; i < runs; i++) for (const n of names) {
    const r = { label, run: i, at: new Date().toISOString(), ...(await runTask(n)) };
    rows.push(r); appendFileSync(out, JSON.stringify(r) + '\n');
    console.log(`${n.padEnd(7)} ${r.ok ? 'OK  ' : 'FAIL'} ${(r.ms / 1000).toFixed(1)}s ttfa ${((r.ttfa ?? 0) / 1000).toFixed(1)}s calls ${r.calls} wait ${(r.approvalMs / 1000).toFixed(1)}s tools ${r.tools.join(',')} ms ${r.toolMs.join('/')} at ${r.callAt.map((x) => (x / 1000).toFixed(1)).join('/')} in ${r.inTok.join('/')} | ${r.answer.replace(/\s+/g, ' ').slice(0, 90)}`);
  }
  console.log(`\n${label}: success ${rows.filter((r) => r.ok).length}/${rows.length}, median total ${(med(rows.map((r) => r.ms)) / 1000).toFixed(1)}s, median ttfa ${(med(rows.map((r) => r.ttfa)) / 1000).toFixed(1)}s, median calls ${med(rows.map((r) => r.calls))}`);
}
