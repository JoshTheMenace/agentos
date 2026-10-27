// Claude via the official Claude Code CLI (the user's own subscription, unmodified binary).
// One `claude -p` process per agentd connection, stream-json both ways. Its ONLY tools are our phone tools,
// served by agentd itself as an MCP server over HTTP (/mcp), so every call still goes through runTool + guard.
// Analogy: Claude Code is a contractor who arrives with an empty toolbox; we hand it just our phone tools.
import { spawn, spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { cfg, fileToken, log } from '../config.js';
import { SYSTEM_PROMPT } from '../prompt.js';
import { TOOLS, runTool } from '../tools.js';

const live = new Map(); // mcp secret -> {ctx} of the session's running turn

export class ClaudeCli {
  id = 'claude';
  #ok = spawnSync(cfg.claudeBin, ['--version'], { stdio: 'ignore' }).status === 0;
  available() { return this.#ok; }

  #start(session) {
    const secret = randomBytes(24).toString('hex'), dir = join(cfg.codexCwd, '..', 'claude-cwd');
    mkdirSync(dir, { recursive: true });
    const mcpFile = join(dir, `mcp-${process.pid}-${secret.slice(0, 8)}.json`); // mode 600: holds the per-session secret
    writeFileSync(mcpFile, JSON.stringify({ mcpServers: { agentos: { type: 'http', url: `http://${cfg.host}:${cfg.port}/mcp`,
      headers: { Authorization: `Bearer ${secret}` } } } }), { mode: 0o600 });
    const args = ['-p', '--input-format', 'stream-json', '--output-format', 'stream-json', '--verbose', '--include-partial-messages',
      '--tools', '', '--strict-mcp-config', '--mcp-config', mcpFile, '--allowedTools', 'mcp__agentos__*',
      '--permission-mode', 'dontAsk', '--setting-sources', '', '--disable-slash-commands', '--no-session-persistence',
      '--system-prompt', SYSTEM_PROMPT, ...(cfg.claudeCliModel ? ['--model', cfg.claudeCliModel] : [])];
    // Headless login (phone): `claude setup-token` output in ~/.agentos/claude.token. With it, use a clean config dir
    // so stale logins in ~/.claude can't win. Without it, the CLI uses its own saved login (the Mac).
    // No auto-update: on the phone we pin 2.1.112, the last plain-JS release (newer ones are native and can't run on Android).
    const token = fileToken(join(cfg.codexCwd, '..', 'claude.token'));
    const env = { ...process.env, DISABLE_AUTOUPDATER: '1', CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: '1',
      ...(token && { CLAUDE_CODE_OAUTH_TOKEN: token, CLAUDE_CONFIG_DIR: join(dir, 'config') }) };
    const p = spawn(cfg.claudeBin, args, { cwd: dir, env, stdio: ['pipe', 'pipe', 'pipe'] });
    const s = { p, secret, turn: null, stderr: [] };
    live.set(secret, s);
    createInterface({ input: p.stdout }).on('line', (l) => this.#onLine(s, l));
    createInterface({ input: p.stderr }).on('line', (l) => { s.stderr = [...s.stderr.slice(-10), l]; if (cfg.debug) log('[claude]', l); });
    p.on('exit', (code) => {
      live.delete(secret); rmSync(mcpFile, { force: true });
      if (session.claudeCli === s) session.claudeCli = null;
      s.turn?.done(s.stopped ? { status: 'interrupted' } : { status: 'failed', error: `claude exited (${code}) ${s.stderr.slice(-2).join(' | ')}` });
    });
    return s;
  }

  #onLine(s, line) {
    let m; try { m = JSON.parse(line); } catch { return; }
    const t = s.turn; if (!t) return;
    if (m.type === 'stream_event' && m.event?.delta?.type === 'text_delta') t.ctx.emit({ type: 'text.delta', text: m.event.delta.text });
    if (m.type === 'result') t.done({ status: m.is_error ? 'failed' : 'completed', error: m.is_error ? String(m.result || m.subtype) : undefined,
      usage: { ...m.usage, cost_usd: m.total_cost_usd } });
  }

  run(session, text, ctx) {
    const s = (session.claudeCli ??= this.#start(session));
    return new Promise((resolve) => {
      s.turn = { ctx, done: (r) => { s.turn = null; resolve(r); } };
      s.p.stdin.write(JSON.stringify({ type: 'user', message: { role: 'user', content: text } }) + '\n');
    });
  }

  stop(session) { const s = session.claudeCli; if (s) { s.stopped = true; s.p.kill(); } } // simplest reliable interrupt; the next ask starts a fresh process
}

// Minimal MCP "streamable HTTP" server (JSON responses only): initialize, tools/list, tools/call.
const mcpTools = Object.entries(TOOLS).map(([name, t]) => ({ name, description: t.description, inputSchema: t.schema }));
export function handleMcp(req, res) {
  const s = live.get(req.headers.authorization?.replace(/^Bearer /, ''));
  if (!s) return res.writeHead(401).end();
  if (req.method !== 'POST') return res.writeHead(405).end();
  let body = '';
  req.on('data', (c) => (body += c)).on('end', async () => {
    let m; try { m = JSON.parse(body); } catch { return res.writeHead(400).end(); }
    if (m.id == null) return res.writeHead(202).end(); // notification
    const reply = (result) => res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ jsonrpc: '2.0', id: m.id, result }));
    if (m.method === 'initialize') return reply({ protocolVersion: m.params?.protocolVersion || '2025-06-18', capabilities: { tools: {} }, serverInfo: { name: 'agentos', version: '0.1.0' } });
    if (m.method === 'tools/list') return reply({ tools: mcpTools });
    if (m.method === 'ping') return reply({});
    if (m.method === 'tools/call') {
      if (!s.turn) return reply({ isError: true, content: [{ type: 'text', text: 'No active turn' }] });
      const r = await runTool(m.params.name, m.params.arguments, s.turn.ctx);
      return reply({ isError: !r.success, content: [{ type: 'text', text: r.text }] });
    }
    res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ jsonrpc: '2.0', id: m.id, error: { code: -32601, message: 'not found' } }));
  });
}
