// Reflex bench: bar asks (in_app) against the LIVE agentd (:8787 over adb forward), end to end = ask sent -> turn.done.
//   node bench/reflex.mjs <label> [runs] [cmd ...]   -> rows in bench/results/reflex-<label>.jsonl, then p50/p90 per command
//   node bench/reflex.mjs --summary <label> [label ...]
// Each run: set the phone up (adb, under the shared lock), then one ask. Commands are harmless only: scroll, back, home,
// open an app, flashlight, volume, media.
import { execSync } from 'node:child_process';
import { appendFileSync, readFileSync, existsSync } from 'node:fs';
const S = process.env.SERIAL || 'ZY22J3TK8S', adb = (c) => execSync(`adb -s ${S} shell "${c}"`, { encoding: 'utf8' });
const lock = (f) => { execSync(`sh -c 'until mkdir /tmp/agentos-device.lock 2>/dev/null; do sleep 1; done; echo ${process.pid} > /tmp/agentos-device.lock/pid'`); try { return f(); } finally { execSync('rm -f /tmp/agentos-device.lock/pid; rmdir /tmp/agentos-device.lock'); } };
const chrome = 'am start -n com.android.chrome/com.google.android.apps.chrome.Main >/dev/null; sleep 1.5';
const CMDS = {
  'scroll down': chrome, 'scroll up': chrome, 'go back': chrome, 'go home': chrome,
  'open calculator': 'input keyevent HOME; sleep 1', 'turn on the flashlight': chrome, 'turn off the flashlight': chrome,
  'volume down': chrome, 'volume up': chrome, 'pause the music': chrome,
};
const file = (l) => new URL(`./results/reflex-${l}.jsonl`, import.meta.url);
const pct = (a, p) => a.length ? a.slice().sort((x, y) => x - y)[Math.min(a.length - 1, Math.floor(p * a.length))] : NaN;
function summary(labels) {
  for (const l of labels) {
    if (!existsSync(file(l))) continue;
    const rows = readFileSync(file(l), 'utf8').trim().split('\n').map((x) => JSON.parse(x)), by = {};
    for (const r of rows) (by[r.cmd] ??= []).push(r);
    console.log(`\n${l}`);
    for (const [c, rs] of Object.entries(by)) { const ms = rs.map((r) => r.ms); console.log(`  ${c.padEnd(26)} n=${rs.length} ok=${rs.filter((r) => r.ok).length} p50=${(pct(ms, 0.5) / 1000).toFixed(2)} s p90=${(pct(ms, 0.9) / 1000).toFixed(2)} s  path=${[...new Set(rs.map((r) => r.path))].join('/')}`); }
  }
}
if (process.argv[2] === '--summary') { summary(process.argv.slice(3)); process.exit(0); }
const label = process.argv[2] || 'run', runs = Number(process.argv[3] || 3), cmds = process.argv.slice(4).length ? process.argv.slice(4) : Object.keys(CMDS);
execSync(`adb -s ${S} forward tcp:18787 tcp:8787`);
const tok = adb("su -c 'cat /data/data/local.agentos.home/files/agentd.token'").trim();
const fgApp = () => { const m = adb('dumpsys window | grep mCurrentFocus').match(/ u0 ([\w.]+)\//); return m && { pkg: m[1], label: { 'com.android.chrome': 'Chrome' }[m[1]] || m[1] }; };
function ask(text, app) {
  return new Promise((done) => {
    const ws = new WebSocket('ws://127.0.0.1:18787/', 'agentos.bearer.' + tok), ev = []; let t0, said = '';
    const end = (m) => { clearTimeout(to); ws.close(); done({ ms: Date.now() - t0, status: m?.status, said: said.trim().slice(0, 120), ev, prep: m?.prep, reflex: m?.reflex }); };
    const to = setTimeout(() => end({ status: 'timeout' }), 60_000);
    ws.onopen = () => { t0 = Date.now(); ws.send(JSON.stringify({ type: 'ask', text, in_app: true, ...(app && process.env.NOAPP !== '1' && { app }) })); };
    ws.onmessage = (e) => { const m = JSON.parse(e.data), at = Date.now() - t0;
      if (m.type === 'text.delta') return void (said += m.text);
      if (/^(tool\.|task\.started|turn\.|error|approval)/.test(m.type)) ev.push([at, m.type, m.name || m.status || m.code || '', m.ms ?? m.reason ?? '', m.ok]);
      if (m.type === 'turn.done') end(m); };
  });
}
for (let i = 0; i < runs; i++) for (const cmd of cmds) {
  const app = lock(() => (adb(`input keyevent WAKEUP; ${CMDS[cmd] || chrome}`), fgApp())); // what Bar.java reports: the app underneath
  const r = await ask(cmd, app), tools = r.ev.filter((e) => e[1] === 'tool.result');
  const row = { label, cmd, i, at: new Date().toISOString(), ms: r.ms, ok: r.status === 'completed' && tools.every((e) => e[4] !== false), status: r.status,
    path: r.reflex ? `reflex:${r.reflex}` : 'agent', prep: r.prep, tools: tools.map((e) => `${e[2]}:${e[3]}`), said: r.said, ev: r.ev };
  appendFileSync(file(label), JSON.stringify(row) + '\n');
  console.log(`${cmd.padEnd(26)} ${String(r.ms).padStart(6)} ms ${row.path} ${r.status} [${row.tools.join(' ')}] ${r.said.slice(0, 60)}`);
  await new Promise((ok) => setTimeout(ok, 1500));
}
summary([label]);
