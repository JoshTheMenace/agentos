#!/usr/bin/env node
// Model-only latency through the bench agentd (no phone actions): "Say ok" N times, per-call ms from Codex's usage events.
//   node bench/ping.js [n] [text]    -> one line per ask, then the median
import { execFileSync } from 'node:child_process';
import WebSocket from 'ws';
const S = process.env.SERIAL || 'ZY22J3TK8S', n = Number(process.argv[2] || 5), text = process.argv[3] || 'Say ok. Do not use tools.';
const TOKEN = execFileSync('adb', ['-s', S, 'shell', "su -c 'cat /data/data/com.termux/files/home/.agentos-bench/agentd.token'"], { encoding: 'utf8' }).trim();
const ask = () => new Promise((res) => {
  const ws = new WebSocket('ws://127.0.0.1:18788', { headers: { authorization: `Bearer ${TOKEN}` } });
  const t = setTimeout(() => { ws.terminate(); res({ ms: 90_000, error: 'timeout' }); }, 90_000);
  ws.on('open', () => ws.send(JSON.stringify({ type: 'ask', text })));
  ws.on('error', (e) => { clearTimeout(t); res({ error: e.message }); });
  ws.on('message', (raw) => { const m = JSON.parse(raw); if (m.type === 'error') console.log('  error:', m.message?.slice(0, 300)); if (m.type === 'turn.done') { clearTimeout(t); ws.close(); res(m); } });
});
const out = [];
for (let i = 0; i < n; i++) { const m = await ask(), c = m.usage?.calls?.[0] || {}; out.push(c.at); console.log(`${m.error ? `ERR ${m.error} ` : ''}total ${m.ms} model ${c.at} in ${c.in} cached ${c.cached} out ${c.out} prep ${m.prep ?? '-'}`); }
console.log(`median model ${out.filter(Boolean).sort((a, b) => a - b)[Math.floor((n - 1) / 2)]} ms`);
