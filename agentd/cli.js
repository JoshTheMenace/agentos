#!/usr/bin/env node
// Tiny agentd client. Usage: node cli.js [--provider codex|claude|local] "your request"
//   follow-ups in the same conversation: node cli.js "Open Spotify. Any trackers?" --then "Block them."
//   keyboard test: node cli.js --suggest reply|rewrite|fix [--screen "line 1|line 2"] "text before the cursor"
// Prints streamed text; tool/guard/approval events go to stderr with timings.
import WebSocket from 'ws';
import { agentdToken, cfg } from './config.js';

const argv = process.argv.slice(2);
const pi = argv.indexOf('--provider');
const provider = pi >= 0 ? argv.splice(pi, 2)[1] : undefined;
const opt = (k) => { const i = argv.indexOf(k); return i >= 0 ? argv.splice(i, 2)[1] : undefined; };
const mode = opt('--suggest'), screen = opt('--screen')?.split('|');
const asks = (argv.join(' ') || 'say hi').split(' --then '), text = asks.shift(); // follow-ups share the conversation: "q1" --then "q2"
const t0 = Date.now(), ms = () => `${String(Date.now() - t0).padStart(6)}ms`;
let first = 0;

const ws = new WebSocket(`ws://${cfg.host}:${cfg.port}`, { headers: { authorization: `Bearer ${agentdToken()}` } });
ws.on('open', () => ws.send(JSON.stringify(mode ? { type: 'suggest', id: 1, mode, before: argv.join(' '), field: { pkg: 'cli' }, screen }
  : { type: 'ask', text, provider })));
process.on('SIGINT', () => ws.send(JSON.stringify({ type: 'stop' })));
ws.on('message', (raw) => {
  const m = JSON.parse(raw);
  if (m.type === 'text.delta') { first ||= Date.now() - t0; return process.stdout.write(m.text); }
  const { type, ...rest } = m;
  console.error(`\n${ms()} [${type}]`, JSON.stringify(rest));
  if (type === 'turn.done' && asks.length) return ws.send(JSON.stringify({ type: 'ask', text: asks.shift(), provider }));
  // busy / provider_unavailable / bad_json end the request; turn_failed is followed by turn.done
  if (type === 'turn.done' || type === 'suggestions' || (type === 'error' && rest.code !== 'turn_failed')) { console.error(`first token ${first}ms, total ${Date.now() - t0}ms`); ws.close(); }
});
ws.on('unexpected-response', (_q, res) => { console.error(`agentd refused: HTTP ${res.statusCode}`); process.exit(1); });
ws.on('error', (e) => { console.error('agentd:', e.message); process.exit(1); });
