// Time one bar ask against agentd from the Mac: prints every event with ms since the ask (adb forward 18787 -> 8787).
// usage: scripts/withphone node measure.mjs "scroll down"   (the app the user is "in" is whatever is on the phone's screen)
import { execSync } from 'node:child_process';
const S = process.env.ANDROID_SERIAL || 'ZY22J3TK8S', text = process.argv[2] || 'scroll down';
execSync(`adb -s ${S} forward tcp:18787 tcp:8787`);
const tok = execSync(`adb -s ${S} shell su -c "cat /data/data/local.agentos.home/files/agentd.token"`).toString().trim();
const ws = new WebSocket('ws://127.0.0.1:18787/', 'agentos.bearer.' + tok);
await new Promise((ok, no) => { ws.onopen = ok; ws.onerror = no; });
const t0 = Date.now(); let text_ = '';
ws.send(JSON.stringify({ type: 'ask', text, in_app: true }));
ws.onmessage = (e) => { const m = JSON.parse(e.data); const ms = Date.now() - t0;
  if (m.type === 'text.delta') { text_ += m.text; return; }
  if (/^(tool\.|task\.|turn\.|error|approval)/.test(m.type)) console.log(String(ms).padStart(6), m.type, m.name || m.status || m.code || '', m.args ? JSON.stringify(m.args).slice(0, 80) : m.ms ? `ms=${m.ms} prep=${m.prep}` : m.reason || m.message || '');
  if (m.type === 'turn.done') { console.log('answer:', text_.trim().slice(0, 160)); ws.close(); process.exit(0); } };
setTimeout(() => { console.log('timeout'); process.exit(1); }, 60000);
