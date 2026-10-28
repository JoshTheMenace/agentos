// Model-time bench for keyboard suggestions (keyboard/LATENCY.md). Runs ON the phone next to a copy of agentd,
// with its own codex app-server (the live agentd is untouched):  node kb-model.js [models] [efforts] [n]
//   node kb-model.js list                      -> model catalog with reasoning efforts
//   node kb-model.js gpt-6-luna low 5 [priority|standard] [hedge ms, 0 = off]     (one model per process: the warm thread keeps its model)
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { cfg } from './config.js';
import { Codex } from './providers/codex.js';

const [models = 'gpt-6-luna', efforts = 'low', n = '5', tier = 'priority', hedge = '0'] = process.argv.slice(2);
if (models === 'list') {
  const p = spawn(cfg.codexBin, ['app-server'], { stdio: ['pipe', 'pipe', 'inherit'] }), send = (m) => p.stdin.write(JSON.stringify(m) + '\n');
  createInterface({ input: p.stdout }).on('line', (l) => { const m = JSON.parse(l);
    if (m.id === 1) { send({ method: 'initialized' }); send({ id: 2, method: 'model/list', params: {} }); }
    if (m.id === 2) { for (const x of m.result.data) console.log(x.id, '|', (x.supportedReasoningEfforts || []).map((e) => e.reasoningEffort || e).join(','), '|', x.description || ''); p.kill(); process.exit(0); } });
  send({ id: 1, method: 'initialize', params: { clientInfo: { name: 'kb-bench', version: '0' }, capabilities: { experimentalApi: true } } });
} else {
  cfg.codexTier = tier === 'standard' ? '' : tier; cfg.suggestHedge = +hedge;
  const codex = new Codex();
  const said = ['Did you catch the game last night?', 'Only the second half', 'Wild ending. Call you later'];
  const input = (i) => JSON.stringify({ mode: 'reply', before: i % 2 ? `Sure${i}` : '', after: '', field: { pkg: 'local.agentos.pigeon', app: 'Pigeon', hint: 'Message' },
    untrusted_screen_data: { source_pkg: 'local.agentos.pigeon', trusted: false, lines: ['Alex', ...said] } });
  { const model = models, effort = efforts;
    cfg.suggestModel = model; cfg.suggestEffort = effort;
    const rows = [];
    for (let i = 0; i < +n + 1; i++) { // round 0 = new thread (cold)
      const tm = {}, t0 = Date.now();
      const items = await codex.suggest(input(i + rows.length * 7), AbortSignal.timeout(30_000), tm).catch((e) => [`ERR ${e.message}`]);
      rows.push({ ms: Date.now() - t0, ...tm, items });
      console.log(model, effort, i ? 'warm' : 'cold', JSON.stringify(rows.at(-1)));
    }
    const warm = rows.slice(1).map((r) => r.ms).sort((a, b) => a - b);
    console.log(`p90 ${warm[Math.floor(warm.length * 0.9)]} mean ${Math.round(warm.reduce((a, b) => a + b) / warm.length)}`);
    console.log(`== ${model} ${effort} ${tier} hedge=${hedge}: cold ${rows[0].ms} ms, warm median ${warm[warm.length >> 1]} ms (min ${warm[0]}, max ${warm.at(-1)})`);
  }
  process.exit(0);
}
