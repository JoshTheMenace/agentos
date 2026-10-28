// On phone: node bench/app-reads-live.js [--inventory]. Metadata only; no model, actions or message bodies printed.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import WebSocket from 'ws';
import { agentdToken, cfg } from '../config.js';
import { tools, APPS } from '../app-reads.js';
import { runTool } from '../tools.js';

if (process.argv.includes('--token-stdin')) process.env.PHONE_TOKEN = readFileSync(0, 'utf8').trim(); // shell harness only; no token in argv/logs
const inventory = await new Promise((resolve, reject) => {
  const ws = new WebSocket(`ws://${cfg.host}:${cfg.port}`, { headers: { authorization: `Bearer ${agentdToken()}` } }), data = {};
  const timer = setTimeout(() => { ws.terminate(); reject(new Error('agentd inventory timed out')); }, 5000);
  ws.on('error', (e) => { clearTimeout(timer); reject(e); });
  ws.on('open', () => { ws.send('{"type":"plugins.list"}'); ws.send('{"type":"task.list"}'); });
  ws.on('message', (raw) => {
    const m = JSON.parse(raw);
    if (m.type === 'plugins.list') data.apps = m.plugins.filter((p) => APPS.some(([id]) => id === p.id)).map((p) => ({ id: p.id, on: p.on }));
    if (m.type === 'task.list') data.active = m.tasks.filter((t) => !['done', 'failed', 'stopped'].includes(t.state)).length;
    if (data.apps && data.active != null) { clearTimeout(timer); ws.close(); resolve(data); }
  });
});
console.log(JSON.stringify({ inventory }));
if (!process.argv.includes('--inventory')) {
  assert.equal(inventory.apps.length, 9, 'live agentd must load the new plugins');
  for (const t of tools) {
    const ctx = { provider: 'codex', turn: { goal: 'Read-only app plugin verification' }, emit() {} }, begin = Date.now();
    const result = await runTool(t.name, {}, ctx), r = JSON.parse(result.text);
    assert.ok(result.success, r.reason || r.error);
    assert.ok((r.items || []).every((x) => x.trusted === false));
    if (r.items?.length) assert.equal(ctx.turn.untrusted, true);
    console.log(JSON.stringify({ tool: t.name, installed: r.installed, cache: r.cache, items: r.items.length, available: r.available, ms: Date.now() - begin, untrusted: !!ctx.turn.untrusted }));
  }
}
