#!/usr/bin/env node
// agentd: WebSocket front door (127.0.0.1:8787, bearer token) -> provider router -> phone tools.
import { timingSafeEqual } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { WebSocketServer } from 'ws';
import { agentdToken, cfg, log, provisionClients } from './config.js';
import { classify } from './guard.js';
import { endTurn, rpc, tasks } from './tools.js';
import * as memory from './memory/index.js';
import * as events from './events.js';
import * as reviewer from './reviewer.js';
import * as context from './context.js';
import * as taskman from './tasks.js';
import * as schedule from './schedule.js';
import { Voice } from './voice.js';
import * as homeSuggest from './home-suggest.js'; // TODO(context): context.js replaces this stand-in
import { Codex } from './providers/codex.js';
import { Claude } from './providers/claude.js';
import { ClaudeCli, handleMcp } from './providers/claude-cli.js';
import { Local, suggest as localSuggest } from './providers/local.js';

// claude: the Claude Code CLI if installed (subscription), else the Messages API with ANTHROPIC_API_KEY.
const cli = new ClaudeCli();
const codex = new Codex();
const providers = Object.fromEntries([codex, cli.available() ? cli : new Claude(), new Local()].map((p) => [p.id, p]));
if (cfg.provider === 'codex') codex.ready().catch(() => {}); // warm start; it also restarts itself if it crashes
taskman.init(codex); // background workers for voice (and typed task.start); see tasks.js
events.watch(); // broker approvals/guard/task events -> every connected UI
schedule.init({ start: taskman.start, onUpdate: taskman.onUpdate, rpc, onEvent: events.onEvent, publish: events.publish, flagged: events.flagged }); // scheduled tasks
context.start(providers[cfg.provider], codex); // notification feed -> context block, memory, reply rules
if (cfg.reviewModel !== 'off') events.setReviewer((a) => { // grade every approval card; see reviewer.js for what it may do
  const turn = tasks.get(a.task_id);
  reviewer.onApproval(codex, a, { turn, flagged: events.flagged.has(a.task_id) || !!turn?.alerted?.size }, events.publish);
});
if (cfg.reviewModel !== 'off') reviewer.grade(codex, { user: ['warm-up'], transcript: [], action: { tool: 'scroll' } }, 60_000); // first real review is warm (<4 s)
// UI passthrough to the tool server, so the launcher needs no :7777 token. Read/UI methods only; approvals are never here
// (firewall.set is allowed because the broker still puts up its fingerprint card for it).
const UI_RPC = { 'audit.tail': 10_000, 'approval.list': 10_000, 'firewall.list': 10_000, 'firewall.set': 90_000, 'task.pause': 10_000, 'task.resume': 10_000, 'task.stop': 10_000, 'trackers.list': 10_000, 'app.mod.list': 10_000, 'app.mod.remove': 10_000 };
const VOICE_PAGE = new URL('./voice.html', import.meta.url);
const TOKEN = Buffer.from(agentdToken());
const PROTO = 'agentos.bearer.'; // browsers/WebViews can't set headers on a WebSocket, so they may send the token as a subprotocol

function authorized(req) {
  const h = req.headers.authorization?.match(/^Bearer (.+)$/)?.[1]
    ?? req.headers['sec-websocket-protocol']?.split(',').map((s) => s.trim()).find((s) => s.startsWith(PROTO))?.slice(PROTO.length);
  const b = Buffer.from(h || '');
  return b.length === TOKEN.length && timingSafeEqual(b, TOKEN);
}

const http = createServer((req, res) => {
  if (req.url === '/mcp') return handleMcp(req, res); // phone tools for the Claude Code CLI (per-session secret)
  if (req.url.split('?')[0] === '/memory/graph.json') { // same bearer token as the WebSocket
    if (!authorized(req)) return res.writeHead(401).end();
    return res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify(memory.graph()));
  }
  if (req.url.split(/[?#]/)[0] === '/voice') return res.writeHead(200, { 'content-type': 'text/html' }).end(readFileSync(VOICE_PAGE)); // no secrets in it
  res.writeHead(req.url === '/health' ? 200 : 404).end(req.url === '/health' ? 'ok\n' : '');
});
const wss = new WebSocketServer({
  server: http, maxPayload: 1 << 20,
  verifyClient: ({ req }) => authorized(req),
  handleProtocols: (protos) => [...protos].find((p) => p.startsWith(PROTO)) ?? false,
});

wss.on('connection', (ws) => {
  const session = {}; // per-connection conversation state (codex thread, claude history, ...)
  let active = null;  // {provider, abort}
  const send = (m) => ws.readyState === 1 && ws.send(JSON.stringify(m));
  const voice = new Voice(codex, send); // voice front desk: hands spoken jobs to background tasks (voice.js)
  const offEvents = events.onEvent((e) => events.FORWARD.has(e.type) && send(e));

  // One model turn (typed, or a voice delegation). Resolves with the final text, or null if busy/unavailable.
  async function ask(text, provider, emit = send) {
    if (active) return void send({ type: 'error', code: 'busy', message: 'a turn is already running; send stop first' });
    const p = providers[provider || cfg.provider];
    if (!p?.available()) return void send({ type: 'error', code: 'provider_unavailable', message: `provider ${provider || cfg.provider} is not available` });
    const abort = new AbortController(), t0 = Date.now(), turn = { goal: String(text || '') };
    turn.asks = session.asks = [...(session.asks || []), turn.goal].slice(-4); // the reviewer reads "block them" with the question before it
    let said = '';
    active = { p, abort };
    const ctx = { provider: p.id, emit: (m) => { if (m.type === 'text.delta') said += m.text; emit(m); }, signal: abort.signal, turn };
    let r;
    const c = p.id === 'local' ? null : await context.block(); // "what's going on" (untrusted notifications etc.)
    if (c) turn.log = c.log; // the reviewer sees which message "reply back" answers
    try { r = await p.run(session, c ? `${c.text}\n\n${turn.goal}` : turn.goal, ctx); }
    catch (e) { r = { status: abort.signal.aborted ? 'interrupted' : 'failed', error: e.message }; }
    await endTurn(turn);
    active = null;
    if (r.error) send({ type: 'error', code: 'turn_failed', message: r.error, retryable: true });
    send({ type: 'turn.done', provider: p.id, status: r.status, usage: r.usage ?? null, ms: Date.now() - t0 });
    return r.text || said || (r.error ? `Sorry, that failed: ${r.error}` : '');
  }

  // Keyboard suggestions (local.agentos.keys). Only the newest id counts: a new request cancels the old one.
  let suggesting = null, offSuggest = null; // offSuggest: home suggestions feed (home-suggest.js)
  async function suggest(m) {
    suggesting?.abort();
    const abort = (suggesting = new AbortController()), t0 = Date.now(), reply = (x) => !abort.signal.aborted && send({ type: 'suggestions', id: m.id, ...x, ms: Date.now() - t0 });
    if ([0x81, 0x91, 0xe1, 0x12].includes((Number(m.field?.inputType) || 0) & 0xfff)) return reply({ items: [] }); // password fields: never
    const lines = [];
    for (const l of (Array.isArray(m.screen) ? m.screen : []).slice(-40).map((x) => String(x).slice(0, 300))) {
      const g = await classify(l);
      if (g.label !== 'benign') send({ type: 'guard.alert', source_pkg: m.field?.pkg, score: g.score, label: g.label, excerpt: l.slice(0, 160) });
      lines.push(g.label === 'benign' ? l : `[withheld by guard: likely prompt injection, score ${g.score}]`);
    }
    const input = JSON.stringify({ mode: m.mode || 'reply', before: String(m.before || '').slice(-2000), after: String(m.after || '').slice(0, 500),
      field: { pkg: m.field?.pkg, hint: m.field?.hint }, untrusted_screen_data: { source_pkg: m.field?.pkg, trusted: false, lines } });
    const local = cfg.suggestProvider === 'local' && (m.mode || 'reply') === 'reply'; // the local prompt only writes replies
    try { reply({ items: (await (local ? localSuggest([...lines, String(m.before || '')].join('\n'), abort.signal) : codex.suggest(input, abort.signal))).slice(0, 3).map(String) }); }
    catch (e) { reply({ items: [], error: e.message }); }
  }

  ws.on('message', async (raw) => {
    let msg; try { msg = JSON.parse(raw); } catch { return send({ type: 'error', code: 'bad_json' }); }
    switch (msg.type) {
      case 'ask': return ask(msg.text, msg.provider); // typed text never answers an approval (taps happen on the card)
      case 'stop':
        if (!active) return;
        active.abort.abort();
        return active.p.stop?.(session);
      case 'suggest': return suggest(msg);
      case 'suggestions.watch': return (offSuggest ??= homeSuggest.watch(send, events.onEvent));
      case 'rpc': { // {id, method, params} -> {type:"rpc.result", id, method, result}
        const ms = UI_RPC[msg.method];
        if (!ms) return send({ type: 'rpc.result', id: msg.id, method: msg.method, result: { ok: false, error: 'method not allowed' } });
        const result = await rpc(msg.method, msg.params && typeof msg.params === 'object' ? msg.params : {}, ms).catch((e) => ({ ok: false, error: e.message }));
        return send({ type: 'rpc.result', id: msg.id, method: msg.method, result });
      }
      case 'voice.start': case 'voice.stop': case 'voice.say': case 'voice.delegate': case 'voice.text': return voice.handle(msg);
      case 'task.start': return taskman.start(String(msg.goal || ''), { origin: 'typed' }); // background task; progress comes as task.update
      case 'task.list': return send({ type: 'task.list', tasks: taskman.list() });
      case 'task.cancel': return taskman.stop(String(msg.id || ''));
      case 'schedule.list': return send({ type: 'schedule.list', items: schedule.list() });
      case 'schedule.cancel': return schedule.cancel(String(msg.id || '')); // the owner's x on the home screen
      default:
        return send({ type: 'error', code: 'unknown_type', message: String(msg.type) });
    }
  });
  ws.on('close', () => { offEvents(); offSuggest?.(); suggesting?.abort(); active?.abort.abort(); active?.p.stop?.(session); voice.dispose(); });
});

if (process.platform === 'android') provisionClients(agentdToken());
http.listen(cfg.port, cfg.host, () => log(`agentd on ws://${cfg.host}:${cfg.port} (token in ${process.env.AGENTD_TOKEN ? 'env' : cfg.tokenFile}); phone tools at ${cfg.phoneUrl}; default provider ${cfg.provider}`));
