#!/usr/bin/env node
// Tiny agentd client. Usage: node cli.js [--provider codex|claude|local] "your request"
// Prints streamed text; tool/guard/approval events go to stderr with timings.
import WebSocket from 'ws';
import { agentdToken, cfg } from './config.js';

const argv = process.argv.slice(2);
const pi = argv.indexOf('--provider');
const provider = pi >= 0 ? argv.splice(pi, 2)[1] : undefined;
const text = argv.join(' ') || 'say hi';
const t0 = Date.now(), ms = () => `${String(Date.now() - t0).padStart(6)}ms`;
let first = 0;

const ws = new WebSocket(`ws://${cfg.host}:${cfg.port}`, { headers: { authorization: `Bearer ${agentdToken()}` } });
ws.on('open', () => ws.send(JSON.stringify({ type: 'ask', text, provider })));
process.on('SIGINT', () => ws.send(JSON.stringify({ type: 'stop' })));
ws.on('message', (raw) => {
  const m = JSON.parse(raw);
  if (m.type === 'text.delta') { first ||= Date.now() - t0; return process.stdout.write(m.text); }
  const { type, ...rest } = m;
  console.error(`\n${ms()} [${type}]`, JSON.stringify(rest));
  if (type === 'turn.done') { console.error(`first token ${first}ms, total ${Date.now() - t0}ms`); ws.close(); }
});
ws.on('unexpected-response', (_q, res) => { console.error(`agentd refused: HTTP ${res.statusCode}`); process.exit(1); });
ws.on('error', (e) => { console.error('agentd:', e.message); process.exit(1); });
