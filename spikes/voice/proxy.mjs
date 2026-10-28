// Mac-only measurement relay. Serves the instrumented page, keeps the phone bearer token out of the browser.
import { createServer } from 'node:http';
import { readFileSync, appendFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import WebSocket, { WebSocketServer } from '../../agentd/node_modules/ws/wrapper.mjs';
const home = process.env.PRODUCTION ? '.agentos' : '.agentos-voice-latency';
const token = execFileSync('adb', ['shell', 'su', '-c', `cat /data/data/com.termux/files/home/${home}/agentd.token`], { encoding: 'utf8' }).trim();
const http = createServer((req, res) => {
  if (req.url === '/log') { req.on('data', (d) => appendFileSync(process.env.LOG || 'spikes/voice/out/latency.jsonl', d)); return req.on('end', () => res.end('ok')); }
  const path = req.url.startsWith('/voice') ? '../../agentd/voice.html' : './out/battery.wav';
  res.setHeader('content-type', path.endsWith('html') ? 'text/html' : 'audio/wav');
  res.end(readFileSync(new URL(path, import.meta.url)));
});
new WebSocketServer({ server: http, verifyClient: ({ req }) => req.headers['sec-websocket-protocol'] === 'agentos.bearer.bench' }).on('connection', (client) => {
  const upstream = new WebSocket(`ws://127.0.0.1:${process.env.PRODUCTION ? 8787 : 18793}`, 'agentos.bearer.' + token), pending = [];
  client.on('message', (b) => upstream.readyState === 1 ? upstream.send(b) : pending.push(b));
  upstream.on('open', () => pending.splice(0).forEach((b) => upstream.send(b)));
  upstream.on('message', (b) => client.readyState === 1 && client.send(b.toString()));
  client.on('close', () => upstream.close());
  upstream.on('close', () => client.close());
  upstream.on('error', (e) => { console.error(e.message); client.close(); });
});
http.listen(8794, '127.0.0.1', () => console.log('measurement relay :8794'));
