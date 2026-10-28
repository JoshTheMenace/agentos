// Network trace for keyboard suggestions (keyboard/LATENCY.md "Where the stall is"). Runs ON the phone next to a copy of agentd,
// with its own codex app-server whose proxy is a logging CONNECT proxy here (PORT, default 18082), so every TLS chunk from
// chatgpt.com gets a timestamp next to Codex's own deltas. Arms alternate per call: plain, and `wake` (UDP to the gateway every
// 100 ms while a call runs, so the Wi-Fi radio never dozes). The live agentd is untouched.
//   CODEX_BIN=./codex-trace.sh node kb-trace.js [n=10] [arms=plain,wake]
import dgram from 'node:dgram';
import { createServer } from 'node:http';
import { connect } from 'node:net';
import { cfg } from './config.js';
import { SUGGEST_PROMPT } from './prompt.js';
import { Codex } from './providers/codex.js';

const [n = '10', arms = 'plain,wake'] = process.argv.slice(2), PORT = +(process.env.PORT || 18082), GW = process.env.GW || '10.0.0.1';
let marks = null; // the current call's chunk log: [ms since call start, 'up'|'down', bytes, conn]
let cid = 0, t0 = 0; const hosts = {};
createServer((_, res) => res.writeHead(405).end()).on('connect', (req, sock, head) => {
  const [host, port] = req.url.split(':'), id = ++cid; hosts[id] = host;
  if (!/(^|\.)(chatgpt|openai)\.com$/.test(host)) return sock.end('HTTP/1.1 403 Forbidden\r\n\r\n');
  const up = connect({ host, port: +port || 443 }, () => { sock.write('HTTP/1.1 200 Connection Established\r\n\r\n'); up.write(head); });
  up.setNoDelay(true); sock.setNoDelay(true);
  up.on('data', (b) => { marks?.push([Date.now() - t0, 'down', b.length, id]); sock.write(b); });
  sock.on('data', (b) => { marks?.push([Date.now() - t0, 'up', b.length, id]); up.write(b); });
  const end = () => { up.destroy(); sock.destroy(); }; up.on('error', end); sock.on('error', end); up.on('close', end); sock.on('close', end);
}).listen(PORT, '127.0.0.1');

const udp = dgram.createSocket('udp4'), ping = () => udp.send(Buffer.alloc(1), 9, GW); // discard port: the gateway drops it
const job = (timing) => ({ model: cfg.suggestModel, effort: cfg.suggestEffort, prompt: SUGGEST_PROMPT, rotate: +(process.env.ROTATE || 30), spare: !!process.env.SPARE, timing,
  schema: { type: 'object', properties: { items: { type: 'array', items: { type: 'string' } } }, required: ['items'], additionalProperties: false } });
const said = ['Did you catch the game last night?', 'Only the second half', 'Wild ending. Call you later'];
const input = (i) => JSON.stringify({ mode: 'reply', before: i % 2 ? `Sure${i}` : '', after: '', field: { pkg: 'local.agentos.pigeon', app: 'Pigeon', hint: 'Message' },
  untrusted_screen_data: { source_pkg: 'local.agentos.pigeon', trusted: false, lines: ['Alex', ...said] } });

const codex = new Codex(), list = arms.split(','), rows = [];
await codex.quick('suggest', job(), input(0), AbortSignal.timeout(60_000)).catch(() => {}); // cold thread
for (let i = 0; i < +n * list.length; i++) {
  const arm = list[i % list.length], tm = {}, deltas = []; let text = '', item1;
  marks = []; t0 = Date.now();
  const wake = arm === 'wake' && setInterval(ping, 100);
  const r = await codex.quick('suggest', job(tm), input(i + 1), AbortSignal.timeout(30_000), (d) => { deltas.push(Date.now() - t0); text += d; item1 ??= /"items"\s*:\s*\[\s*"(?:[^"\\]|\\.)*"/.test(text) ? Date.now() - t0 : undefined; }).catch((e) => ({ items: [`ERR ${e.message}`] }));
  clearInterval(wake);
  const down = marks.filter((m) => m[1] === 'down'), up = marks.filter((m) => m[1] === 'up');
  // net = the first response bytes after the request went up; spread = first token -> last token (Codex), wire = first -> last response chunk
  const row = { arm, ms: Date.now() - t0, sent: up[0]?.[0], net: down[0]?.[0], wire: down.length ? down.at(-1)[0] - down[0][0] : null, chunks: down.length,
    first: deltas[0], item1, spread: deltas.length ? deltas.at(-1) - deltas[0] : null, deltas: deltas.length, done: tm.done, conns: [...new Set(marks.map((m) => m[3]))].map((c) => `${c}:${hosts[c]}`), items: r.items };
  rows.push(row); console.log(JSON.stringify({ ...row, marks: process.env.MARKS ? marks : undefined }));
}
const med = (a) => { a = a.filter((x) => x != null).sort((x, y) => x - y); return a[a.length >> 1]; };
for (const arm of list) { const r = rows.filter((x) => x.arm === arm); console.log(`== ${arm}: n=${r.length} median ${med(r.map((x) => x.ms))} ms, p90 ${r.map((x) => x.ms).sort((a, b) => a - b)[Math.floor(r.length * 0.9)]}, max ${Math.max(...r.map((x) => x.ms))}, first-byte median ${med(r.map((x) => x.net))}, spread median ${med(r.map((x) => x.spread))}, item1 median ${med(r.map((x) => x.item1))}, done-item1 median ${med(r.map((x) => x.done - x.item1))}`); }
process.exit(0);
