// Codex adapter: one long-lived `codex app-server` child on stdio (JSON-RPC, one JSON object per line).
// Shapes come from `codex app-server generate-ts --experimental` (0.164.0).
// Analogy: app-server is the engine; we hold its only pipe, and its only "hands" are our phone tools.
import { spawn } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { cfg, log } from '../config.js';
import { SYSTEM_PROMPT } from '../prompt.js';
import { TOOLS, runTool } from '../tools.js';

// Turn off everything that would give the model a capability besides our tools.
const OFF = ['shell_tool', 'unified_exec', 'apps', 'plugins', 'browser_use', 'browser_use_external', 'computer_use',
  'in_app_browser', 'image_generation', 'multi_agent', 'tool_suggest', 'view_image', 'hooks', 'memories', 'goals', 'skill_search', 'sleep_tool'];
const ARGS = ['app-server', ...OFF.flatMap((f) => ['-c', `features.${f}=false`]), '-c', 'web_search="disabled"',
  '-c', 'sandbox_mode="read-only"', '-c', 'approval_policy="never"', '-c', 'mcp_servers={}'];
// Errors worth retrying (the hotspot's DNS is flaky; a crashed app-server is restarted below).
const TRANSIENT = /dns|lookup|resolve|ENOTFOUND|EAI_AGAIN|error sending request|stream disconnected|connection (reset|refused|closed)|timed? ?out|network|unreachable|exited/i;
const STALL_MS = 45_000;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Codex's own approval prompts: always say no (rule 1). Shapes per request type.
const DECLINE = {
  'item/commandExecution/requestApproval': { decision: 'decline' },
  'item/fileChange/requestApproval': { decision: 'decline' },
  'item/permissions/requestApproval': { permissions: {}, scope: 'turn' },
  'execCommandApproval': { decision: 'abort' },
  'applyPatchApproval': { decision: 'abort' },
  'mcpServer/elicitation/request': { action: 'decline', content: null, _meta: null },
  'item/tool/requestUserInput': { answers: {} },
};

export class Codex {
  id = 'codex';
  #proc; #ready; #nid = 0; #pending = new Map(); #turns = new Map(); #voice = new Map(); #gen = 0; #stderr = []; #fails = 0;

  available() { return true; }
  ready() { return (this.#ready ??= this.#start()); }

  #start() {
    mkdirSync(cfg.codexCwd, { recursive: true });
    const p = (this.#proc = spawn(cfg.codexBin, ARGS, { stdio: ['pipe', 'pipe', 'pipe'] }));
    this.#gen++;
    createInterface({ input: p.stdout }).on('line', (l) => this.#onLine(l));
    createInterface({ input: p.stderr }).on('line', (l) => { l = l.replace(/\x1b\[[0-9;]*m/g, ''); this.#stderr = [...this.#stderr.slice(-20), l]; if (cfg.debug) log('[codex]', l); });
    p.stdin.on('error', () => {});
    const died = (why) => { // fail everything in flight, then restart with backoff (1 s, 2 s, 4 s ... 30 s)
      if (p.dead) return; p.dead = true; p.stdin.destroy(); // EOF also ends a root app-server behind su
      log(`codex app-server ${why}`);
      const err = new Error(`codex app-server ${why}: ${this.#stderr.slice(-3).join(' | ')}`);
      for (const { reject } of this.#pending.values()) reject(err);
      for (const t of this.#turns.values()) t.done({ status: 'failed', error: err.message });
      for (const v of this.#voice.values()) v.on({ method: 'thread/realtime/closed', params: { reason: 'codex app-server restarted' } });
      this.#pending.clear(); this.#turns.clear(); this.#voice.clear(); this.#ready = null;
      setTimeout(() => this.ready().catch(() => {}), Math.min(30_000, 1000 * 2 ** this.#fails++));
    };
    p.on('exit', (code) => died(`exited (${code})`));
    p.on('error', (e) => died(`spawn error: ${e.message}`));
    return (async () => {
      const r = await this.#req('initialize', { clientInfo: { name: 'agentos', title: 'AgentOS agentd', version: '0.1.0' },
        capabilities: { experimentalApi: true } });
      this.#send({ method: 'initialized' });
      this.#fails = 0;
      log(`codex ready: ${r.userAgent}`);
      return r;
    })();
  }

  #send(msg) { this.#proc.stdin.write(JSON.stringify(msg) + '\n'); }
  #req(method, params) {
    const id = ++this.#nid;
    if (!this.#proc || this.#proc.dead) return Promise.reject(new Error('codex app-server is not running'));
    return new Promise((resolve, reject) => { this.#pending.set(id, { resolve, reject }); this.#send({ id, method, params }); });
  }

  async #onLine(line) {
    let m; try { m = JSON.parse(line); } catch { return; }
    if (m.id != null && !m.method) { // response to us
      const p = this.#pending.get(m.id); this.#pending.delete(m.id);
      return m.error ? p?.reject(new Error(m.error.message || JSON.stringify(m.error))) : p?.resolve(m.result);
    }
    const v = m.id == null && this.#voice.get(m.params?.threadId);
    if (v) { // voice thread: it has no tools, so interrupt the turn app-server starts for a delegation (we run it ourselves)
      if (m.method === 'thread/realtime/sdp') v.answer(m.params.sdp);
      if (m.method === 'turn/started') this.#req('turn/interrupt', { threadId: m.params.threadId, turnId: m.params.turn.id }).catch(() => {});
      if (m.method === 'thread/realtime/closed') this.#voice.delete(m.params.threadId);
      return v.on(m);
    }
    const t = this.#turns.get(m.params?.threadId);
    if (m.id != null) return this.#send({ id: m.id, ...(await this.#serverRequest(m, t)) });
    if (!t) return;
    t.last = Date.now();
    switch (m.method) {
      case 'turn/started': t.turnId = m.params.turn.id; break;
      case 'item/agentMessage/delta': t.ctx.emit({ type: 'text.delta', text: m.params.delta }); break;
      case 'item/completed': if (m.params.item.type === 'agentMessage') t.text = m.params.item.text; break;
      case 'thread/tokenUsage/updated': t.usage = m.params.tokenUsage.last; break;
      case 'error': t.ctx.emit({ type: 'error', message: m.params.error.message, retryable: m.params.willRetry }); break;
      case 'turn/completed': t.done({ status: m.params.turn.status, error: m.params.turn.error?.message, usage: t.usage, text: t.text }); break;
    }
  }

  async #serverRequest(m, t) {
    if (m.method === 'item/tool/call') {
      const { tool, arguments: args } = m.params;
      if (!t) return { result: { success: false, contentItems: [{ type: 'inputText', text: 'No active turn' }] } };
      t.tools++;
      const r = await runTool(tool, args, t.ctx).finally(() => { t.tools--; t.last = Date.now(); });
      return { result: { success: r.success, contentItems: [{ type: 'inputText', text: r.text }] } };
    }
    if (DECLINE[m.method]) { log(`declined codex ${m.method}`); return { result: DECLINE[m.method] }; }
    return { error: { code: -32601, message: `agentd does not handle ${m.method}` } };
  }

  // One turn, retried on transient network errors or an app-server crash. If phone actions already happened,
  // the retry asks the model to re-read the screen and continue instead of starting over.
  async run(session, text, ctx) {
    const goal = text;
    for (let i = 0; ; i++) {
      const r = await this.#runOnce(session, text, ctx).catch((e) => ({ status: 'failed', error: e.message }));
      if (r.status !== 'failed' || ctx.signal.aborted || i >= 3 || !TRANSIENT.test(r.error || '')) return r;
      ctx.emit({ type: 'error', code: 'transient', message: `${r.error} (retry ${i + 1}/3)`, retryable: true });
      if (i >= 1) this.#proc?.kill(); // still failing: start a fresh app-server (CODEX_BIN also restarts the proxy if it died)
      await sleep(2000 * 2 ** i);
      if (ctx.turn.acted) text = `The connection dropped mid-task. Re-read the screen and finish this request without repeating actions that already happened: ${goal}`;
    }
  }

  async #runOnce(session, text, ctx) {
    await this.ready();
    let s = session.codex;
    if (!s || s.gen !== this.#gen) {
      const r = await this.#req('thread/start', {
        ...(cfg.codexModel && { model: cfg.codexModel }), cwd: cfg.codexCwd, ephemeral: true,
        sandbox: 'read-only', approvalPolicy: 'never', baseInstructions: SYSTEM_PROMPT,
        dynamicTools: Object.entries(TOOLS).map(([name, t]) => ({ type: 'function', name, description: t.description, inputSchema: t.schema })),
      });
      s = session.codex = { threadId: r.thread.id, gen: this.#gen };
    }
    return new Promise((resolve) => {
      // Watchdog: on a dead network Codex can wait silently for minutes. No progress for STALL_MS (approval waits
      // don't count) -> recycle app-server; the turn then fails as "exited" and run() retries it.
      const dog = setInterval(() => { if (!t.tools && Date.now() - t.last > STALL_MS) { log('codex turn stalled; restarting app-server'); this.#proc?.kill(); } }, 5000);
      const t = { ctx, usage: null, tools: 0, last: Date.now(), done: (r) => { clearInterval(dog); this.#turns.delete(s.threadId); resolve(r); } };
      this.#turns.set(s.threadId, t);
      this.#req('turn/start', { threadId: s.threadId, effort: cfg.codexEffort, input: [{ type: 'text', text, text_elements: [] }] })
        .then((r) => { t.turnId ??= r.turn?.id; })
        .catch((e) => t.done({ status: 'failed', error: e.message }));
    });
  }

  // Voice: a separate thread with NO tools. Delegations are handed to agentd (onEvent gets every realtime
  // notification, including itemAdded/handoff_request), which runs them as normal turns and answers with voiceSay.
  async voiceStart(sdp, on) {
    await this.ready();
    const { thread } = await this.#req('thread/start', { cwd: cfg.codexCwd, ephemeral: true, sandbox: 'read-only', approvalPolicy: 'never',
      baseInstructions: 'You relay spoken requests. You have no tools; another agent does the work.', dynamicTools: [] });
    const answer = new Promise((ok, no) => { this.#voice.set(thread.id, { on, answer: ok }); setTimeout(() => no(new Error('no SDP answer within 60 s')), 60_000); });
    answer.catch(() => {});
    try {
      await this.#req('thread/realtime/start', { threadId: thread.id, outputModality: 'audio', version: 'v3', clientManagedHandoffs: true,
        includeStartupContext: false, transport: { type: 'webrtc', sdp } });
      return { threadId: thread.id, sdp: await answer };
    } catch (e) { this.#voice.delete(thread.id); throw e; }
  }
  voiceSay(threadId, text) { return this.#req('thread/realtime/appendSpeech', { threadId, text }); }
  voiceStop(threadId) { return this.#req('thread/realtime/stop', { threadId }); }

  async stop(session) {
    const s = session.codex, t = s && this.#turns.get(s.threadId);
    if (t?.turnId) await this.#req('turn/interrupt', { threadId: s.threadId, turnId: t.turnId }).catch(() => {});
  }
}
