// Probe: does Claude Code in Termux see ONLY our MCP tools, and how much RAM does it use?
// Starts a tiny HTTP MCP server with one fake tool (`phone_echo`), then runs `claude -p` with the same lock-down
// flags as agentd/providers/claude-cli.js. Prints the init message (tools + MCP status), the result, timings and RSS.
// Usage (in Termux): CLAUDE_BIN=... [CLAUDE_CODE_OAUTH_TOKEN=...] node mcp-probe.js ["prompt"]
import { spawn } from 'node:child_process';
import { readFileSync } from 'node:fs';
import http from 'node:http';
import { createInterface } from 'node:readline';

const bin = process.env.CLAUDE_BIN || 'claude', port = 8799, secret = 'probe-' + process.pid;
const prompt = process.argv[2] || 'Call phone_echo with text "hi", then reply with what it returned.';
const t0 = Date.now(), ms = () => Date.now() - t0;
const rss = (pid) => { try { return Math.round(+readFileSync(`/proc/${pid}/status`, 'utf8').match(/VmRSS:\s+(\d+)/)[1] / 1024) + ' MB'; } catch { return '?'; } };

http.createServer((req, res) => {
  if (req.headers.authorization !== `Bearer ${secret}`) return res.writeHead(401).end();
  let b = ''; req.on('data', (c) => (b += c)).on('end', () => {
    let m; try { m = JSON.parse(b); } catch { return res.writeHead(405).end(); } // GET (SSE stream) etc.: not offered, like agentd
    if (m.id == null) return res.writeHead(202).end();
    const r = m.method === 'initialize' ? { protocolVersion: m.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'agentos', version: '0' } }
      : m.method === 'tools/list' ? { tools: [{ name: 'phone_echo', description: 'Echo text back', inputSchema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] } }] }
      : m.method === 'tools/call' ? (console.log(`${ms()}ms tools/call`, JSON.stringify(m.params)), { content: [{ type: 'text', text: `echo: ${m.params.arguments.text}` }] })
      : {};
    res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ jsonrpc: '2.0', id: m.id, result: r }));
  });
}).listen(port, '127.0.0.1', () => {
  const mcp = JSON.stringify({ mcpServers: { agentos: { type: 'http', url: `http://127.0.0.1:${port}/mcp`, headers: { Authorization: `Bearer ${secret}` } } } });
  const p = spawn(bin, ['-p', '--input-format', 'stream-json', '--output-format', 'stream-json', '--verbose',
    '--tools', '', '--strict-mcp-config', '--mcp-config', mcp, '--allowedTools', 'mcp__agentos__*', '--permission-mode', 'dontAsk',
    '--setting-sources', '', '--disable-slash-commands', '--no-session-persistence', '--system-prompt', 'You are a phone agent.',
    ...(process.env.CLAUDE_CLI_MODEL ? ['--model', process.env.CLAUDE_CLI_MODEL] : [])], { stdio: ['pipe', 'pipe', 'inherit'] });
  const peak = { v: 0 }, iv = setInterval(() => { const r = parseInt(rss(p.pid)); if (r > peak.v) peak.v = r; }, 200);
  setTimeout(() => console.log(`${ms()}ms idle RSS before first message: ${rss(p.pid)}`), 3000);
  setTimeout(() => p.stdin.write(JSON.stringify({ type: 'user', message: { role: 'user', content: prompt } }) + '\n'), 3500);
  createInterface({ input: p.stdout }).on('line', (l) => {
    const m = JSON.parse(l);
    if (m.type === 'system' && m.subtype === 'init') console.log(`${ms()}ms init: model=${m.model} tools=${JSON.stringify(m.tools)} mcp=${JSON.stringify(m.mcp_servers)} version=${m.claude_code_version}`);
    if (m.type === 'result') { console.log(`${ms()}ms result: is_error=${m.is_error} ${String(m.result).slice(0, 300)} | api_ms=${m.duration_api_ms} turns=${m.num_turns}`); console.log(`peak RSS ${peak.v} MB`); clearInterval(iv); p.kill(); process.exit(0); }
  });
});
