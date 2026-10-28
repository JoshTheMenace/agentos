#!/usr/bin/env node
// End-to-end memory eval: a throwaway agentd on the Mac (mock phone, eval memory.db from world.js) and the REAL model.
// Each question is a fresh chat; the reply is graded by regex (questions.js).
//   node memory/eval/e2e.js --label baseline [--provider codex|claude] [--only id,id] [--par 4]
// Env passed through to agentd (e.g. MEMORY_BRAIN=0 to switch the brain off). Results: memory/eval/results/<label>.jsonl
import { spawn } from 'node:child_process';
import { appendFileSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import WebSocket from 'ws';
import * as S from '../store.js';
import { build } from './world.js';
import { ACT_SUFFIX, HELDOUT, QUESTIONS } from './questions.js';

const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d; };
const label = arg('label', 'run'), provider = arg('provider', 'codex'), par = Number(arg('par', 4));
const only = arg('only', '').split(',').filter(Boolean);
const HOME = join(tmpdir(), `memeval-${process.pid}`), DB = join(HOME, 'memory.db'), PORT = 18900 + (process.pid % 90), PPORT = PORT + 1000;
rmSync(HOME, { recursive: true, force: true }); mkdirSync(HOME, { recursive: true });
const db = S.open(DB); build(db); db.close();
const TOKEN = 'evaltoken'.padEnd(64, '0');
const env = { ...process.env, AGENTOS_HOME: HOME, MEMORY_DB: DB, AGENTD_PORT: String(PORT), PHONE_URL: `http://127.0.0.1:${PPORT}/rpc`,
  PHONE_TOKEN: 'phonetok', AGENTD_TOKEN: TOKEN, MOCK_CLEAN: '1', AGENTD_CLIENT_PKGS: '',
  MEMORY_FIXTURES: join(HOME, 'no-fixtures') }; // connectors find no phone data: they must never pull the real phone into the eval DB
const cwd = arg('agentd', new URL('../..', import.meta.url).pathname); // --agentd <dir>: run another copy (e.g. the baseline from git HEAD)
const procs = [spawn('node', ['mock-phone.js'], { cwd, env, stdio: 'ignore' }), spawn('node', ['server.js'], { cwd, env, stdio: ['ignore', 'pipe', 'pipe'] })];
let log = ''; procs[1].stdout.on('data', (d) => (log += d)); procs[1].stderr.on('data', (d) => (log += d));
const bye = () => procs.forEach((p) => p.kill());
process.on('exit', bye);
for (let i = 0; i < 60 && !/agentd on ws/.test(log); i++) await new Promise((r) => setTimeout(r, 250));

function ask(text, timeoutMs = Number(process.env.EVAL_TIMEOUT || 150_000)) {
  return new Promise((resolve) => {
    const ws = new WebSocket(`ws://127.0.0.1:${PORT}`, { headers: { authorization: `Bearer ${TOKEN}` } });
    const t0 = Date.now(), tools = [];
    let answer = '';
    const done = (r) => { clearTimeout(kill); ws.close(); resolve({ ...r, answer, tools, ms: Date.now() - t0 }); };
    const kill = setTimeout(() => done({ status: 'timeout' }), timeoutMs);
    ws.on('open', () => ws.send(JSON.stringify({ type: 'ask', text, provider })));
    ws.on('error', (e) => done({ status: `ws error ${e.message}` }));
    ws.on('message', (raw) => {
      const m = JSON.parse(raw);
      if (m.type === 'text.delta') answer += m.text;
      if (m.type === 'tool.request') tools.push({ name: m.name, tier: m.tier, args: m.args });
      if (m.type === 'turn.done') done({ status: m.status, calls: m.usage?.calls?.length });
    });
  });
}

const qs = (arg('set') === 'heldout' ? HELDOUT : arg('set') === 'all' ? [...QUESTIONS, ...HELDOUT] : QUESTIONS).filter((q) => !only.length || only.includes(q.id));
mkdirSync(new URL('./results', import.meta.url), { recursive: true });
const out = new URL(`./results/${label}.jsonl`, import.meta.url);
writeFileSync(out, '');
const rows = [];
let next = 0;
await Promise.all(Array.from({ length: par }, async () => {
  while (next < qs.length) {
    const q = qs[next++];
    const r = await ask(q.q + (q.act ? ACT_SUFFIX : ''));
    const outward = r.tools.some((t) => t.tier === 'outward' || (t.name === 'type' && t.args?.submit));
    const ok = r.status === 'completed' && q.answer.test(r.answer) && !(q.not && q.not.test(r.answer)) && !(q.noOutward && outward);
    const row = { id: q.id, ok, status: r.status, ms: r.ms, calls: r.calls, tools: r.tools.map((t) => t.name), answer: r.answer.slice(0, 300) };
    rows.push(row); appendFileSync(out, JSON.stringify(row) + '\n');
    console.log(`${ok ? '✓' : '✗'} ${q.id.padEnd(16)} ${(r.ms / 1000).toFixed(1)}s tools=${row.tools.join(',') || '-'} | ${row.answer.replace(/\s+/g, ' ').slice(0, 140)}`);
  }
}));
const med = (a) => { a = [...a].sort((x, y) => x - y); return a[Math.floor((a.length - 1) / 2)]; };
const summary = { label, provider, correct: `${rows.filter((r) => r.ok).length}/${rows.length}`, median_s: +(med(rows.map((r) => r.ms)) / 1000).toFixed(1),
  avg_tool_calls: +(rows.reduce((s, r) => s + r.tools.length, 0) / rows.length).toFixed(2), avg_model_calls: +(rows.reduce((s, r) => s + (r.calls || 0), 0) / rows.length).toFixed(2) };
console.log(JSON.stringify(summary));
appendFileSync(new URL('./results/summary.jsonl', import.meta.url), JSON.stringify({ ...summary, at: new Date().toISOString() }) + '\n');
if (process.env.EVAL_LOG) console.log(log.slice(-3000));
bye(); process.exit(0);
