// usage: node task.mjs '<json message>' [seconds]  -> sends to agentd via adb forward :18787, prints events (dev aid, not shipped)
import { execSync } from 'node:child_process';
const tok = execSync(`adb -s ZY22J3TK8S shell "su -c 'cat /data/data/com.termux/files/home/.agentos/agentd.token'"`).toString().trim();
const msg = JSON.parse(process.argv[2]), secs = Number(process.argv[3] || 5);
const ws = new WebSocket('ws://127.0.0.1:18787/', 'agentos.bearer.' + tok);
ws.onopen = () => { ws.send(JSON.stringify(msg)); setTimeout(() => process.exit(0), secs * 1000); };
ws.onmessage = (e) => { const m = JSON.parse(e.data); if (!/text.delta/.test(m.type)) console.log(new Date().toISOString().slice(11, 19), JSON.stringify(m).slice(0, 220)); };
ws.onerror = (e) => { console.error('ws error', e.message); process.exit(1); };
