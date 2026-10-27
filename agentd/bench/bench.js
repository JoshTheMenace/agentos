#!/usr/bin/env node
// Task-latency benchmark on the real phone. Runs on the Mac; drives the bench agentd (bench/up.sh, port 8788 via
// adb forward 18788) and checks results with adb. Every task goes through the normal broker gate.
//   node bench/bench.js [--label baseline] [--runs 2] [--tasks alarm,chrome,...]
// Writes one JSON line per run to bench/results/<label>.jsonl and prints a summary.
import { execFileSync } from 'node:child_process';
import { appendFileSync, mkdirSync, rmdirSync } from 'node:fs';
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
// Clock's own list, opened by intent after the task (the checker holds the device lock).
const clock = async (action) => { su(`am start -a android.intent.action.${action}`); await sleep(1500); return screenText(); };
const CLEAR_CLOCK = () => { su('pm clear com.google.android.deskclock'); su('pm grant com.google.android.deskclock android.permission.POST_NOTIFICATIONS'); };
// Chrome reopens its last tab, so start each run on a plain Google page (then go home).
const CHROME_FRESH = () => { su('am force-stop com.android.chrome; am start -a android.intent.action.VIEW -d https://www.google.com/ -p com.android.chrome'); adb('sleep 3; input keyevent HOME'); };
// Lease card = a human tap, not model time: the bench turns it off per task (broker setting) and back on after.
const SETTINGS = '/data/data/local.agentos/files/settings.json';
const leaseOff = () => su(`echo '{"require_user_lease":false}' > ${SETTINGS}; chown $(stat -c %u /data/data/local.agentos) ${SETTINGS}; chcon $(stat -c %C /data/data/local.agentos/files) ${SETTINGS}`);
const leaseOn = () => su(`rm -f ${SETTINGS}`);
// The broker carries a caller's History (taint AND step count) into its next task within 10 min, so back-to-back tasks
// hit max_steps (NEEDS-ANDROID.md). A throwaway lease from another caller (adb) makes the next task start fresh.
const resetHistory = async () => { await rpc('task.start', { apps: [], goal: 'bench reset' }); await rpc('task.stop', {}); };

export const TASKS = {
  alarm: { text: 'Set an alarm for 7 AM.', setup: CLEAR_CLOCK,
    check: async () => /7:00\s*AM/i.test(await clock('SHOW_ALARMS')) },
  timer: { text: 'Start a 5 minute timer.', setup: CLEAR_CLOCK,
    check: async () => /Pause/i.test(await clock('SHOW_TIMERS')) },
  chrome: { text: 'Search Chrome for the weather in Salt Lake City.', setup: CHROME_FRESH,
    check: async () => { for (let i = 0; i < 6; i++, await sleep(1000)) { const t = await screenText(); if (/weather in salt lake city/i.test(t) && /AI Mode|Images|News/.test(t)) return true; } return false; } }, // results tabs = submitted (the page may still be loading when a 1-call turn ends)
  calc: { text: 'Use the Calculator app to work out 234 times 17.', setup: () => su('pm clear com.google.android.calculator'),
    check: async (r) => /3,?978/.test(r.answer) && /3,?978/.test(await screenText()) },
  reply: { text: 'Reply to Mom in Pigeon that I\'m running 10 minutes late.', setup: () => { su('am force-stop local.agentos.pigeon; am start -n local.agentos.pigeon/.Main --es scenario clean'); adb('sleep 1; input keyevent HOME'); },
    check: async (r) => r.events.some((e) => e.type === 'tool.result' && e.ok && e.decision === 'approved') },
  multi: { text: 'Look up today\'s sunset time in Salt Lake City in Chrome, then set an alarm in Clock for that time.',
    setup: () => { CHROME_FRESH(); CLEAR_CLOCK(); },
    check: async () => /\d:\d\d\s*PM/i.test(await clock('SHOW_ALARMS')) },
};

async function lock() {
  for (let i = 0; i < 450; i++) { try { mkdirSync(LOCK); return; } catch { await sleep(2000); } }
  throw new Error('device lock busy for 15 min');
}

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
      if (m.type === 'turn.done') done({ status: m.status, ms: m.ms, usage: m.usage });
    });
  });
}

async function runTask(name) {
  const task = TASKS[name];
  await lock();
  try {
    adb('input keyevent HOME'); await sleep(800);
    task.setup?.(); leaseOff(); await resetHistory(); await sleep(500);
    const r = await ask(task.text);
    const act = r.events.find((e) => e.type === 'tool.request' && e.tier !== 'read');
    const calls = r.usage?.calls || [];
    let ok = false; try { ok = r.status === 'completed' && await task.check(r); } catch (e) { r.checkError = e.message; }
    if (!ok) try { r.checkError ??= (await screenText()).slice(0, 400); } catch {}
    return { task: name, ok, status: r.status, ms: r.ms, ttfa: act?.t ?? null, calls: calls.length,
      tools: r.events.filter((e) => e.type === 'tool.request').map((e) => `${e.name}${e.args?.id != null ? '#' + e.args.id : ''}`),
      toolMs: r.events.filter((e) => e.type === 'tool.result' && e.name !== 'act').map((e) => e.ms || 0),
      approvalMs: r.events.filter((e) => e.type === 'tool.result' && /approved|denied/.test(e.decision || '')).reduce((a, e) => a + (e.ms || 0), 0),
      inTok: calls.map((c) => c.in), callAt: calls.map((c) => c.at), outTok: calls.map((c) => c.out + (c.reasoning || 0)), answer: r.answer.slice(0, 200), checkError: r.checkError };
  } finally { leaseOn(); try { adb('input keyevent HOME'); } catch {} rmdirSync(LOCK); }
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
  const label = arg('label', 'run'), runs = Number(arg('runs', 1)), names = arg('tasks', Object.keys(TASKS).join(',')).split(',');
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
