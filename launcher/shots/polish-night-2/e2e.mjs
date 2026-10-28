// Stopwatch end-to-end (dev aid): starts a read-only task on the phone, screenshots the island the moment it finishes,
// then the Home card and the ask sheet. Run inside scripts/withphone. usage: node e2e.mjs '<goal>' <prefix>
import { execSync } from 'node:child_process';
const S = 'ZY22J3TK8S', sh = (c) => execSync(`adb -s ${S} shell ${c}`, { stdio: 'pipe' });
const cap = (n) => { execSync(`adb -s ${S} exec-out screencap -p > ${process.argv[3]}-${n}.png`); console.log(new Date().toISOString().slice(11, 23), 'shot', n); };
const tok = execSync(`adb -s ${S} shell "su -c 'cat /data/data/com.termux/files/home/.agentos/agentd.token'"`).toString().trim();
sh('input keyevent KEYCODE_HOME'); await new Promise((r) => setTimeout(r, 800));
const ws = new WebSocket('ws://127.0.0.1:18787/', 'agentos.bearer.' + tok);
const t0 = Date.now();
ws.onopen = () => { ws.send(JSON.stringify({ type: 'task.start', goal: process.argv[2] })); setTimeout(() => { console.log('timeout'); process.exit(1); }, 120000); };
ws.onmessage = async (e) => {
  const m = JSON.parse(e.data); if (/text.delta|suggestions|audit.append/.test(m.type)) return;
  console.log(new Date().toISOString().slice(11, 23), JSON.stringify(m).slice(0, 200));
  if (m.type === 'task.update' && ['done', 'failed', 'stopped'].includes(m.task?.state)) {
    console.log('wall', Date.now() - t0, 'ms; agentd total_ms', m.task.timing?.total_ms);
    await new Promise((r) => setTimeout(r, 700)); cap('island-done');
    sh('input keyevent KEYCODE_HOME'); await new Promise((r) => setTimeout(r, 1200)); cap('home-done-pill');
    await new Promise((r) => setTimeout(r, 2500)); cap('home-done');
    sh('input tap 300 1240'); await new Promise((r) => setTimeout(r, 1500)); cap('sheet-done');
    sh('input keyevent KEYCODE_BACK'); await new Promise((r) => setTimeout(r, 400)); sh('input keyevent KEYCODE_BACK'); sh('input keyevent KEYCODE_HOME');
    process.exit(0);
  }
  if (m.type === 'tool.request' && m.name === 'open_app') { await new Promise((r) => setTimeout(r, 2500)); cap('island-working'); }
};
ws.onerror = (e) => { console.error('ws error', e.message); process.exit(1); };
