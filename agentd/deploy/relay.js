#!/usr/bin/env node
// AgentOS relay: runs on an always-on server with a public HTTPS name, so Grok / Dots can reach the phone's MCP server
// (agentd/mcp-remote.js) while the phone only dials OUT. Analogy: a hotel front desk. Guests (Grok) walk up to the desk; the
// phone keeps one phone line open to the desk and answers each guest through it. The desk can't open any room: OAuth, the
// pairing code, tokens, rate limits and approvals are all checked on the phone. If the phone is offline, guests get 503.
//   RELAY_KEY=<secret> PORT=8080 node relay.js     (put Caddy or cloudflared in front for TLS; see research/REMOTE-MCP.md)
// The phone connects to wss://<host>/agentos-link with "Authorization: Bearer <RELAY_KEY>". Needs Node 22+ and `ws`.
import { createHash, timingSafeEqual } from 'node:crypto';
import { createServer } from 'node:http';
import { WebSocketServer } from 'ws';

const KEY = createHash('sha256').update(process.env.RELAY_KEY || '').digest();
if (!process.env.RELAY_KEY || process.env.RELAY_KEY.length < 32) { console.error('set RELAY_KEY (32+ random chars)'); process.exit(1); }
const PORT = Number(process.env.PORT || 8080), HOST = process.env.HOST || '127.0.0.1', LINK = '/agentos-link', WAIT_MS = 100_000;
const PASS = ['authorization', 'content-type', 'accept', 'mcp-protocol-version', 'mcp-session-id', 'last-event-id', 'host', 'x-forwarded-proto', 'x-forwarded-host', 'user-agent'];
const log = (...a) => console.log(new Date().toISOString(), ...a);
let phone = null, n = 0;
const waiting = new Map(); // request id -> {res, timer}

const http = createServer((req, res) => {
  if (req.url === '/relay-health') return res.writeHead(200).end(phone ? 'phone connected\n' : 'phone offline\n');
  let body = '';
  req.setEncoding('utf8');
  req.on('data', (c) => { body += c; if (body.length > 1 << 20) req.destroy(); });
  req.on('end', () => {
    if (!phone) return res.writeHead(503, { 'content-type': 'application/json', 'retry-after': '10' }).end(JSON.stringify({ error: 'phone offline' }));
    const id = `r${++n}`, headers = Object.fromEntries(PASS.filter((h) => req.headers[h]).map((h) => [h, req.headers[h]]));
    headers['x-forwarded-proto'] ??= process.env.PROTO || 'https'; // behind Caddy/cloudflared the public side is https
    headers['x-forwarded-host'] ??= req.headers.host;
    const timer = setTimeout(() => { waiting.delete(id); res.writeHead(504).end(); }, WAIT_MS);
    waiting.set(id, { res, timer });
    phone.send(JSON.stringify({ id, method: req.method, url: req.url, headers, body }));
  });
});

const wss = new WebSocketServer({ server: http, path: LINK, maxPayload: 4 << 20,
  verifyClient: ({ req }) => { const k = createHash('sha256').update(String(req.headers.authorization || '').replace(/^Bearer\s+/i, '')).digest(); return timingSafeEqual(k, KEY); } });
wss.on('connection', (ws, req) => {
  phone?.terminate(); // a new line from the phone replaces the old one (it reconnected after a network change)
  phone = ws; log('phone connected', req.socket.remoteAddress);
  let alive = true;
  const beat = setInterval(() => { if (!alive) return ws.terminate(); alive = false; ws.ping(); }, 15_000); // dead line found within 30 s
  ws.on('pong', () => { alive = true; });
  ws.on('message', (raw) => {
    let r; try { r = JSON.parse(raw); } catch { return; }
    const w = waiting.get(r.id); if (!w) return;
    clearTimeout(w.timer); waiting.delete(r.id);
    const h = Object.fromEntries(Object.entries(r.headers || {}).filter(([k]) => /^[\w-]+$/.test(k)));
    w.res.writeHead(Number(r.status) || 502, h).end(r.body ?? '');
  });
  ws.on('close', () => { clearInterval(beat); if (phone === ws) { phone = null; log('phone disconnected'); } });
});
http.listen(PORT, HOST, () => log(`relay on http://${HOST}:${PORT} (phone line at ${LINK})`));
