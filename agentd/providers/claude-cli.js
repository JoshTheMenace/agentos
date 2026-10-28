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
import { systemPrompt } from '../prompt.js';
import { runTool, spec, toolset } from '../tools.js';
import { BY_ID, LOADERS } from '../plugins.js';

const probe = () => { // is the CLI there (and its version)? Say why not in the log
  const r = spawnSync(cfg.claudeBin, ['--version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  if (r.status !== 0) log(`claude CLI not available: ${r.error?.message || r.stderr?.trim().slice(0, 300) || `exit ${r.status}`}`);
  return r.status === 0 && (r.stdout.match(/(\d+)\.(\d+)\.(\d+)/)?.slice(1).map(Number) || [0, 0, 0]);
};
// Claude Code >= 2.1.285 defers MCP tools natively (ToolSearch) and keeps tools marked alwaysLoad upfront. The phone pins
// 2.1.112 (the last plain-JS build), so there it gets the load_plugin fallback instead.
const nativeSearch = (v) => v && v[0] * 1e6 + v[1] * 1e3 + v[2] >= 2001285;
const live = new Map(); // mcp secret -> {ctx} of the session's running turn

export class ClaudeCli {
  id = 'claude';
  #v = probe();
  available() { return !!this.#v; }

  #start(session, ts) {
    const native = cfg.tools === 'plugins' && nativeSearch(this.#v) && ts.deferred.length > 0;
    const eager = new Set(ts.names), names = native ? [...ts.names, ...ts.deferred.flatMap((id) => BY_ID[id].tools)] : [...ts.names, ...(ts.deferred.length ? Object.keys(LOADERS) : [])];
    const tools = names.map((name) => ({ name, description: spec(name).description, inputSchema: spec(name).schema, ...(native && eager.has(name) && { _meta: { 'anthropic/alwaysLoad': true } }) }));
    const secret = randomBytes(24).toString('hex'), dir = join(cfg.codexCwd, '..', 'claude-cwd');
    mkdirSync(dir, { recursive: true });
    const mcpFile = join(dir, `mcp-${process.pid}-${secret.slice(0, 8)}.json`); // mode 600: holds the per-session secret
    writeFileSync(mcpFile, JSON.stringify({ mcpServers: { agentos: { type: 'http', url: `http://${cfg.host}:${cfg.port}/mcp`,
      headers: { Authorization: `Bearer ${secret}` } } } }), { mode: 0o600 });
    const args = ['-p', '--input-format', 'stream-json', '--output-format', 'stream-json', '--verbose', '--include-partial-messages',
      '--tools', native ? 'ToolSearch' : '', '--strict-mcp-config', '--mcp-config', mcpFile, '--allowedTools', native ? 'mcp__agentos__*,ToolSearch' : 'mcp__agentos__*',
      '--permission-mode', 'dontAsk', '--setting-sources', '', '--disable-slash-commands', '--no-session-persistence',
      '--system-prompt', systemPrompt({ ...ts, native }), ...(cfg.claudeCliModel ? ['--model', cfg.claudeCliModel] : [])];
    // Headless login (phone): `claude setup-token` output in ~/.agentos/claude.token. With it, use a clean config dir
    // so stale logins in ~/.claude can't win. Without it, the CLI uses its own saved login (the Mac).
    // No auto-update: on the phone we pin 2.1.112, the last plain-JS release (newer ones are native and can't run on Android).
    const token = fileToken(join(cfg.codexCwd, '..', 'claude.token'));
    const env = { ...process.env, DISABLE_AUTOUPDATER: '1', CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: '1',
      ...(token && { CLAUDE_CODE_OAUTH_TOKEN: token, CLAUDE_CONFIG_DIR: join(dir, 'config') }) };
    const p = spawn(cfg.claudeBin, args, { cwd: dir, env, stdio: ['pipe', 'pipe', 'pipe'] });
    const s = { p, secret, turn: null, stderr: [], tools, off: ts.off.join(), plugins: { mode: cfg.tools, native, loaded: ts.loaded, deferred: ts.deferred } };
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
      usage: { ...m.usage, cost_usd: m.total_cost_usd, num_turns: m.num_turns, plugins: s.plugins } });
  }

  async run(session, text, ctx) {
    const ts = await toolset(ctx.turn.goal); // tools are fixed per process (tools/list is read once); a switched plugin restarts it
    if (session.claudeCli && session.claudeCli.off !== ts.off.join()) { session.claudeCli.stopped = true; session.claudeCli.p.kill(); session.claudeCli = null; }
    const s = (session.claudeCli ??= this.#start(session, ts));
    return new Promise((resolve) => {
      s.turn = { ctx, done: (r) => { s.turn = null; resolve(r); } };
      s.p.stdin.write(JSON.stringify({ type: 'user', message: { role: 'user', content: text } }) + '\n');
    });
  }

  stop(session) { const s = session.claudeCli; if (s) { s.stopped = true; s.p.kill(); } } // simplest reliable interrupt; the next ask starts a fresh process
}

// Minimal MCP "streamable HTTP" server (JSON responses only): initialize, tools/list (this session's tools), tools/call.
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
    if (m.method === 'tools/list') return reply({ tools: s.tools });
    if (m.method === 'ping') return reply({});
    if (m.method === 'tools/call') {
      if (!s.turn) return reply({ isError: true, content: [{ type: 'text', text: 'No active turn' }] });
      const r = await runTool(m.params.name, m.params.arguments, s.turn.ctx);
      return reply({ isError: !r.success, content: [{ type: 'text', text: r.text }] });
    }
    res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ jsonrpc: '2.0', id: m.id, error: { code: -32601, message: 'not found' } }));
  });
}
