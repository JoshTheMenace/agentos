// Isolated phone voice benchmark. No production server, schedules, reviewer, or notification rules.
import { createServer } from 'node:http';
import { WebSocketServer } from '../../agentd/node_modules/ws/wrapper.mjs';
import { Codex } from '../../agentd/providers/codex.js';
import { agentdToken } from '../../agentd/config.js';
import { Voice } from '../../agentd/voice.js';
import * as tasks from '../../agentd/tasks.js';
import * as events from '../../agentd/events.js';
const token = agentdToken(), codex = new Codex();
tasks.init(codex);
events.watch();
await codex.ready();
const http = createServer((req, res) => res.writeHead(req.url === '/health' ? 200 : 404).end());
const wss = new WebSocketServer({ server: http, verifyClient: ({ req }) => req.headers['sec-websocket-protocol'] === 'agentos.bearer.' + token });
wss.on('connection', (ws) => {
  const send = (m) => ws.readyState === 1 && ws.send(JSON.stringify(m));
  const voice = new Voice(codex, send), off = events.onEvent((m) => m.type === 'task.update' && send(m));
  ws.on('message', (b) => voice.handle(JSON.parse(b)));
  ws.on('close', () => { off(); voice.dispose(); });
});
http.listen(8793, '127.0.0.1', () => console.log('voice bench :8793'));
