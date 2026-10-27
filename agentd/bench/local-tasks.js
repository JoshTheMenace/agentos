#!/usr/bin/env node
// Real phone tasks with the local model (test agentd from bench/local-up.sh on :8790, adb forward 18790). Every action
// goes through the normal broker. One task per device-lock hold; each run is checked with adb, then cleaned up
// (no test alarms/events left, music paused, phone back on Home).
//   node bench/local-tasks.js --label after --runs 3 [--tasks alarm,calendar]
import { execFileSync } from 'node:child_process';
import { appendFileSync, mkdirSync, rmdirSync } from 'node:fs';
import WebSocket from 'ws';

const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d; };
const S = process.env.SERIAL || 'ZY22J3TK8S', LOCK = '/tmp/agentos-device.lock';
const adb = (cmd, ms = 20_000) => execFileSync('adb', ['-s', S, 'shell', cmd], { encoding: 'utf8', timeout: ms }).trim();
const su = (cmd) => adb(`su -c '${cmd.replace(/'/g, `'\\''`)}'`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const TOKEN = su('cat /data/data/com.termux/files/home/.agentos-local/agentd.token');
const PHONE = su('cat /data/data/local.agentos/files/token');
execFileSync('adb', ['-s', S, 'forward', 'tcp:17777', 'tcp:7777']);
const rpc = async (method, params = {}) => (await (await fetch('http://127.0.0.1:17777/rpc', { method: 'POST',
  headers: { authorization: `Bearer ${PHONE}`, 'content-type': 'application/json' }, body: JSON.stringify({ method, params }) })).json());
const screenText = async () => ((await rpc('screen.read'))?.nodes || []).map((n) => `${n.text || ''} ${n.desc || ''}`).join(' | ');
const top = () => (adb('dumpsys activity activities | grep -m1 topResumedActivity').match(/ ([\w.]+)\//) || [])[1];
const clock = async (action) => { su(`am start -a android.intent.action.${action}`); await sleep(1500); return screenText(); };
const CLEAR_CLOCK = () => { su('pm clear com.google.android.deskclock'); su('pm grant com.google.android.deskclock android.permission.POST_NOTIFICATIONS'); };
const EVENTS = "content query --uri content://com.android.calendar/events --projection _id:title:dtstart --where \"title = 'Lunch with Sam' AND deleted=0\"";
const DEL_EVENTS = () => { for (const id of su(EVENTS).matchAll(/_id=(\d+)/g)) su(`content delete --uri content://com.android.calendar/events/${id[1]}`); };
const playing = () => /state=3/.test(su('dumpsys media_session').split('Sessions Stack')[1]?.split(/package=/).find((b) => b.startsWith('com.spotify.music')) || '');
const PAUSE = () => { su('cmd media_session dispatch pause'); su('am force-stop com.spotify.music'); };
const fresh = (pkg) => () => su(`am force-stop ${pkg}`);

export const TASKS = {
  alarm: { text: 'Set an alarm for 7', setup: CLEAR_CLOCK, check: async () => /7:00\s*AM/i.test(await clock('SHOW_ALARMS')), after: CLEAR_CLOCK },
  timer: { text: 'Start a 5 minute timer', setup: CLEAR_CLOCK, check: async () => /Pause/i.test(await clock('SHOW_TIMERS')), after: CLEAR_CLOCK },
  calendar: { text: 'Put lunch with Sam on my calendar tomorrow at noon', setup: () => { DEL_EVENTS(); fresh('com.google.android.calendar')(); },
    check: async () => { let m; for (let i = 0; i < 6 && !(m = su(EVENTS).match(/title=([^,]*), dtstart=(\d+)/)); i++) await sleep(1000); // Calendar saves a moment after Save
      m = m || su(EVENTS).match(/title=([^,]*), dtstart=(\d+)/); if (!m) return false;
      const want = new Date(); want.setDate(want.getDate() + 1); want.setHours(12, 0, 0, 0); return /lunch/i.test(m[1]) && +m[2] === want.getTime(); },
    debug: () => su(`content query --uri content://com.android.calendar/events --projection _id:title:dtstart:deleted --where "dtstart=${(() => { const d = new Date(); d.setDate(d.getDate() + 1); d.setHours(12, 0, 0, 0); return d.getTime(); })()}"`),
    after: () => { DEL_EVENTS(); fresh('com.google.android.calendar')(); } },
  spotify: { text: 'Open Spotify and play something', setup: PAUSE, check: async () => { await sleep(8000); return playing(); }, after: PAUSE },
  chrome: { text: 'Search Chrome for the weather in Salt Lake City', setup: fresh('com.android.chrome'),
    check: async () => (await sleep(2000), top() === 'com.android.chrome') && /salt.lake.city/i.test(await screenText()) },
  slack: { text: 'Open Slack', check: async () => top() === 'com.Slack' },
  messages: { text: 'Open Messages', check: async () => top() === 'com.google.android.apps.messaging' },
  calc: { text: 'Open the calculator', check: async () => top() === 'com.google.android.calculator' },
  dnd: { text: 'Turn on Do Not Disturb', check: async () => su('settings get global zen_mode') !== '0', after: () => su('cmd notification set_dnd off') },
  pigeon: { text: 'Open Pigeon and read me the last message from Mom', setup: () => su('am force-stop local.agentos.pigeon; am start -n local.agentos.pigeon/.Main --es scenario clean; sleep 1; input keyevent HOME'),
    check: async (r) => /love you/i.test(r.answer) },
};

async function lock() {
  for (let i = 0; i < 300; i++) { try { mkdirSync(LOCK); return; } catch { await sleep(2000); } }
  throw new Error('device lock busy for 10 min');
}

function ask(text, timeoutMs = 150_000) {
  return new Promise((resolve) => {
    const ws = new WebSocket('ws://127.0.0.1:18790', { headers: { authorization: `Bearer ${TOKEN}` } });
    const t0 = Date.now(), events = [];
    let answer = '';
    const done = (r) => { clearTimeout(kill); try { ws.send(JSON.stringify({ type: 'stop' })); } catch {} ws.close(); resolve({ ...r, answer, events }); };
    const kill = setTimeout(() => done({ status: 'timeout', ms: Date.now() - t0 }), timeoutMs);
    ws.on('open', () => ws.send(JSON.stringify({ type: 'ask', text, provider: 'local' })));
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
    adb('input keyevent WAKEUP; wm dismiss-keyguard; input keyevent HOME'); await sleep(600);
    task.setup?.(); adb('input keyevent HOME'); await sleep(600);
    const r = await ask(task.text);
    const act = r.events.find((e) => e.type === 'tool.request' && e.tier !== 'read');
    let ok = false; try { ok = r.status === 'completed' && await task.check(r); } catch (e) { r.checkError = e.message; }
    if (!ok && task.debug) r.checkError = task.debug();
    return { task: name, ok, status: r.status, ms: r.ms, ttfa: act?.t ?? null, tools: r.events.filter((e) => e.type === 'tool.request').map((e) => e.name),
      plan: r.events.find((e) => e.type === 'local.plan')?.plan, usage: r.usage, answer: r.answer.slice(0, 160), checkError: r.checkError, err: r.events.find((e) => e.type === 'error')?.message };
  } finally { try { task.after?.(); adb('input keyevent HOME'); } catch {} rmdirSync(LOCK); }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const label = arg('label', 'run'), runs = Number(arg('runs', 1)), names = arg('tasks', Object.keys(TASKS).join(',')).split(',');
  mkdirSync(new URL('./results/', import.meta.url), { recursive: true });
  const out = new URL(`./results/local-${label}.jsonl`, import.meta.url), rows = [];
  for (let i = 0; i < runs; i++) for (const n of names) {
    const r = { label, run: i, at: new Date().toISOString(), ...(await runTask(n)) };
    rows.push(r); appendFileSync(out, JSON.stringify(r) + '\n');
    console.log(`${n.padEnd(8)} ${r.ok ? 'OK  ' : 'FAIL'} ${((r.ms ?? 0) / 1000).toFixed(1)}s first action ${((r.ttfa ?? 0) / 1000).toFixed(1)}s ${r.status} tools ${r.tools.join(',')} | ${(r.err || r.answer).replace(/\s+/g, ' ').slice(0, 100)}`);
  }
  console.log(`\n${label}:`);
  for (const n of names) { const t = rows.filter((r) => r.task === n), ms = t.map((r) => r.ms ?? 0).sort((a, b) => a - b);
    console.log(`| ${n} | ${t.filter((r) => r.ok).length}/${t.length} | ${(ms[ms.length >> 1] / 1000).toFixed(1)} s |`); }
  console.log(`total ${rows.filter((r) => r.ok).length}/${rows.length}`);
}
