#!/usr/bin/env node
// Voice spike relay: browser page <-> (HTTP + SSE) <-> `codex app-server` (stdio JSON-RPC).
// It plays the role agentd will play on the phone: pass the SDP offer in, the SDP answer out,
// stream realtime events, and answer dynamic tool calls. Audio never touches this process.
// Usage: node spikes/voice/relay.mjs [port]   then open http://127.0.0.1:8790
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createInterface } from 'node:readline';

const DIR = new URL('.', import.meta.url).pathname, PORT = +(process.argv[2] || 8790);
const CODEX = process.env.CODEX_BIN || 'codex';
const t0 = Date.now(), ts = () => ((Date.now() - t0) / 1000).toFixed(3);
const log = (...a) => console.log(ts(), ...a);

// --- app-server over stdio (rule 1: no shell tool, read-only sandbox, approvals declined)
// Laptop only: switch off the personal MCP servers/apps in ~/.codex so they don't load into voice threads.
const mcpOff = process.env.CODEX_BIN ? [] : [...(readFileSync(process.env.HOME + '/.codex/config.toml', 'utf8').matchAll(/^\[mcp_servers\.([\w-]+)\]$/gm))]
  .flatMap((m) => ['-c', `mcp_servers.${m[1]}.enabled=false`]);
const cx = spawn(CODEX, ['app-server', '-c', 'features.shell_tool=false', '-c', 'features.apps=false',
  '-c', 'model_reasoning_effort="low"', ...mcpOff],
  { stdio: ['pipe', 'pipe', 'inherit'] });
let nextId = 0; const pending = new Map(), clients = new Set();
const rpc = (method, params) => new Promise((ok, fail) => {
  const id = ++nextId; pending.set(id, { ok, fail });
  cx.stdin.write(JSON.stringify({ id, method, params }) + '\n');
});
const notify = (method, params) => cx.stdin.write(JSON.stringify({ method, params }) + '\n');
const push = (ev) => { for (const c of clients) c.write(`data: ${JSON.stringify(ev)}\n\n`); };

// Fake phone tools: on the phone these are forwarded to the Android tool server (:7777).
const TOOLS = [
  { type: 'function', name: 'phone_battery', description: 'Read the phone battery level and charging state.',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false } },
  { type: 'function', name: 'phone_open_app', description: 'Open an app on the phone by name.',
    inputSchema: { type: 'object', properties: { name: { type: 'string' } }, required: ['name'], additionalProperties: false } },
];
const fakeTool = (t, args) => t === 'phone_battery' ? 'Battery 73%, not charging.' : `Opened ${args?.name}.`;

createInterface({ input: cx.stdout }).on('line', (line) => {
  let m; try { m = JSON.parse(line); } catch { return; }
  if (m.id != null && !m.method) {                       // response to our request
    const p = pending.get(m.id); pending.delete(m.id);
    return m.error ? p?.fail(new Error(JSON.stringify(m.error))) : p?.ok(m.result);
  }
  const brief = m.method?.includes('outputAudio') ? '(audio)' : JSON.stringify(m.params).slice(0, 300);
  log('<-', m.method, brief);
  push({ t: ts(), method: m.method, params: m.method?.includes('outputAudio') ? undefined : m.params });
  if (m.method === 'thread/realtime/sdp') sdpWaiter?.(m.params.sdp); // answer arrives as a notification
  const w = turns.get(m.params?.threadId);
  if (w && m.method === 'item/completed' && m.params.item.type === 'agentMessage') w.text = m.params.item.text;
  if (w && m.method === 'turn/completed') { turns.delete(m.params.threadId); w.ok(w.text); }
  if (m.id == null) return;                              // server -> client request: must answer
  let result = { decision: 'decline' };
  if (m.method === 'item/tool/call') {
    result = { success: true, contentItems: [{ type: 'inputText', text: fakeTool(m.params.tool, m.params.arguments) }] };
  } else if (m.method === 'attestation/generate') result = { attestation: null };
  cx.stdin.write(JSON.stringify({ id: m.id, result }) + '\n');
});

// Default (server mode): app-server routes the voice model's delegation into a Codex turn via its sideband.
// DELEGATE=client: the page catches `delegation.created` on its data channel and we run it on a worker thread.
const CLIENT = process.env.DELEGATE === 'client', turns = new Map();
const base = { ephemeral: true, sandbox: 'read-only', approvalPolicy: 'never', cwd: '/tmp',
  developerInstructions: 'You are AgentOS, a phone assistant. Use phone_* tools for anything about the phone. Answer in one short sentence.' };
let threadId, worker, sdpWaiter;
const ready = rpc('initialize', { clientInfo: { name: 'agentos_voice_spike', title: 'AgentOS voice spike', version: '0.1' },
  capabilities: { experimentalApi: true } }).then((r) => { notify('initialized'); log('initialized', r.userAgent); });

async function delegate(text) {  // one Codex turn with our phone tools; resolves with its final message
  const done = new Promise((ok) => turns.set(worker, { ok, text: '' }));
  await rpc('turn/start', { threadId: worker, input: [{ type: 'text', text }] });
  return done;
}

async function start(offer) {
  await ready;
  const [th, wk] = await Promise.all([rpc('thread/start', { ...base, dynamicTools: CLIENT ? [] : TOOLS }),
    CLIENT && rpc('thread/start', { ...base, dynamicTools: TOOLS })]);
  threadId = th.thread.id; worker = wk?.thread?.id; log('thread', threadId, 'worker', worker);
  const answer = new Promise((ok) => { sdpWaiter = ok; });
  await rpc('thread/realtime/start', { threadId, outputModality: 'audio', version: 'v3', clientManagedHandoffs: CLIENT,
    transport: { type: 'webrtc', sdp: offer } });
  return answer;
}

const body = (req) => new Promise((ok) => { const b = []; req.on('data', (c) => b.push(c)).on('end', () => ok(Buffer.concat(b))); });
createServer(async (req, res) => {
  try {
    if (req.url === '/' || req.url.startsWith('/?')) return res.end(readFileSync(DIR + 'index.html'));
    if (/^\/out\/[\w-]+\.wav$/.test(req.url)) return res.end(readFileSync(DIR + req.url.slice(1)));
    if (req.url === '/events') {
      res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' });
      clients.add(res); return req.on('close', () => clients.delete(res));
    }
    if (req.url === '/start') { const t = Date.now(); const sdp = await start((await body(req)).toString());
      log('answer sdp after', Date.now() - t, 'ms'); return res.end(sdp); }
    if (req.url === '/stop') { await rpc('thread/realtime/stop', { threadId }); return res.end('ok'); }
    if (req.url === '/say') { await rpc('thread/realtime/appendText', { threadId, text: (await body(req)).toString(), role: 'user' }); return res.end('ok'); }
    if (req.url === '/delegate' && !CLIENT) return res.writeHead(409).end('server mode');
    if (req.url === '/delegate') { const t = Date.now(), r = await delegate((await body(req)).toString());
      log('delegate done', Date.now() - t, 'ms:', r); return res.end(r); }
    if (req.url === '/log') { log('[page]', (await body(req)).toString()); return res.end('ok'); }
    if (req.url === '/upload') { mkdirSync(DIR + 'out', { recursive: true }); writeFileSync(DIR + 'out/reply.webm', await body(req)); log('saved out/reply.webm'); return res.end('ok'); }
    res.writeHead(404).end();
  } catch (e) { log('error', e.message); res.writeHead(500).end(e.message); }
}).listen(PORT, '127.0.0.1', () => log(`open http://127.0.0.1:${PORT}`));
