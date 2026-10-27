#!/usr/bin/env node
// agentd: WebSocket front door (127.0.0.1:8787, bearer token) -> provider router -> phone tools.
import { timingSafeEqual } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { WebSocketServer } from 'ws';
import { agentdToken, cfg, log } from './config.js';
import { endTurn } from './tools.js';
import { Codex } from './providers/codex.js';
import { Claude } from './providers/claude.js';
import { ClaudeCli, handleMcp } from './providers/claude-cli.js';
import { Local } from './providers/local.js';

// claude: the Claude Code CLI if installed (subscription), else the Messages API with ANTHROPIC_API_KEY.
const cli = new ClaudeCli();
const codex = new Codex();
const providers = Object.fromEntries([codex, cli.available() ? cli : new Claude(), new Local()].map((p) => [p.id, p]));
if (cfg.provider === 'codex') codex.ready().catch(() => {}); // warm start; it also restarts itself if it crashes
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
  const say = (text) => session.voice && codex.voiceSay(session.voice, text).catch(() => {});

  // One model turn (typed, or a voice delegation). Resolves with the final text, or null if busy/unavailable.
  async function ask(text, provider, emit = send) {
    if (active) return void send({ type: 'error', code: 'busy', message: 'a turn is already running; send stop first' });
    const p = providers[provider || cfg.provider];
    if (!p?.available()) return void send({ type: 'error', code: 'provider_unavailable', message: `provider ${provider || cfg.provider} is not available` });
    const abort = new AbortController(), t0 = Date.now(), turn = { goal: String(text || '') };
    let said = '';
    active = { p, abort };
    const ctx = { emit: (m) => { if (m.type === 'text.delta') said += m.text; emit(m); }, signal: abort.signal, turn };
    let r;
    try { r = await p.run(session, turn.goal, ctx); }
    catch (e) { r = { status: abort.signal.aborted ? 'interrupted' : 'failed', error: e.message }; }
    await endTurn(turn);
    active = null;
    if (r.error) send({ type: 'error', code: 'turn_failed', message: r.error, retryable: true });
    send({ type: 'turn.done', provider: p.id, status: r.status, usage: r.usage ?? null, ms: Date.now() - t0 });
    return r.text || said || (r.error ? `Sorry, that failed: ${r.error}` : '');
  }

  // A spoken request the voice model handed off. It runs as a normal turn (our tools, same gate), then the voice says the answer.
  const delegations = new Set();
  async function delegate(itemId, text) {
    if (!text || delegations.has(itemId)) return; // the page and app-server's sideband may both report it
    delegations.add(itemId);
    send({ type: 'voice.delegation', item_id: itemId, text });
    if (active) return say('I am still working on the last request.');
    const emit = (m) => { send(m); if (m.type === 'approval.needed') say('Please approve that on your phone with your fingerprint.'); };
    const answer = await ask(`(Spoken request: reply in one or two short sentences, it will be read aloud.) ${text}`, undefined, emit);
    if (answer) { send({ type: 'voice.result', item_id: itemId, text: answer }); say(answer); }
  }

  function onVoice({ method, params: p }) { // realtime notifications for this connection's voice thread
    if (method === 'thread/realtime/started') send({ type: 'voice.started', session_id: p.realtimeSessionId });
    else if (method === 'thread/realtime/transcript/done') send({ type: 'voice.transcript', role: p.role, text: p.text, final: true });
    else if (method === 'thread/realtime/itemAdded' && p.item?.type === 'handoff_request') delegate(p.item.handoff_id, p.item.input_transcript);
    else if (method === 'thread/realtime/error') send({ type: 'voice.error', message: p.message ?? JSON.stringify(p) });
    else if (method === 'thread/realtime/closed') { session.voice = null; send({ type: 'voice.closed', reason: p.reason }); }
  }

  ws.on('message', async (raw) => {
    let msg; try { msg = JSON.parse(raw); } catch { return send({ type: 'error', code: 'bad_json' }); }
    switch (msg.type) {
      case 'ask': return ask(msg.text, msg.provider);
      case 'stop':
        if (!active) return;
        active.abort.abort();
        return active.p.stop?.(session);
      case 'suggest': // keyboard suggestions: placeholder until the local model is wired up
        return send({ type: 'suggestions', items: [] });
      case 'voice.start': { // {sdp}: WebRTC offer from the page -> answer; audio then flows page <-> OpenAI directly
        if (session.voice) await codex.voiceStop(session.voice).catch(() => {});
        const t0 = Date.now();
        try {
          const v = await codex.voiceStart(String(msg.sdp || ''), onVoice);
          session.voice = v.threadId;
          return send({ type: 'voice.answer', sdp: v.sdp, ms: Date.now() - t0 });
        } catch (e) { return send({ type: 'voice.error', message: e.message }); }
      }
      case 'voice.stop': return session.voice && codex.voiceStop(session.voice).catch(() => {});
      case 'voice.say': return say(String(msg.text || ''));
      case 'voice.delegate': return delegate(String(msg.item_id), String(msg.text || '')); // page saw delegation.created first
      default:
        return send({ type: 'error', code: 'unknown_type', message: String(msg.type) });
    }
  });
  ws.on('close', () => { active?.abort.abort(); active?.p.stop?.(session); if (session.voice) codex.voiceStop(session.voice).catch(() => {}); });
});

http.listen(cfg.port, cfg.host, () => log(`agentd on ws://${cfg.host}:${cfg.port} (token in ${process.env.AGENTD_TOKEN ? 'env' : cfg.tokenFile}); phone tools at ${cfg.phoneUrl}; default provider ${cfg.provider}`));
