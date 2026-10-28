// Codex adapter: one long-lived `codex app-server` child on stdio (JSON-RPC, one JSON object per line).
// Shapes come from `codex app-server generate-ts --experimental` (0.164.0).
// Analogy: app-server is the engine; we hold its only pipe, and its only "hands" are our phone tools.
import { spawn } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { cfg, log } from '../config.js';
import { SUGGEST_PROMPT, WRITE_PROMPT, systemPrompt } from '../prompt.js';
import { appList, runTool, spec, toolset } from '../tools.js';
import { LOADERS } from '../plugins.js';

// Turn off everything that would give the model a capability besides our tools.
const OFF = ['shell_tool', 'unified_exec', 'apps', 'plugins', 'browser_use', 'browser_use_external', 'computer_use',
  'in_app_browser', 'image_generation', 'multi_agent', 'tool_suggest', 'view_image', 'hooks', 'memories', 'goals', 'skill_search', 'sleep_tool'];
const ARGS = ['app-server', ...OFF.flatMap((f) => ['-c', `features.${f}=false`]), '-c', 'web_search="disabled"',
  '-c', 'sandbox_mode="read-only"', '-c', 'approval_policy="never"', '-c', 'mcp_servers={}'];
// Errors worth retrying (the hotspot's DNS is flaky; a crashed app-server is restarted below).
// "workspace routing discovery failed" is Codex's auth manager failing to reach the ChatGPT backend (not the tier).
const TRANSIENT = /dns|lookup|resolve|ENOTFOUND|EAI_AGAIN|error sending request|stream disconnected|connection (reset|refused|closed|failed)|timed? ?out|network|unreachable|exited|routing discovery failed/i;
const STALL_MS = 45_000;
const SUGGEST_SCHEMA = { type: 'object', properties: { items: { type: 'array', items: { type: 'string' } } }, required: ['items'], additionalProperties: false };
const WRITE_SCHEMA = { type: 'object', properties: { text: { type: 'string' } }, required: ['text'], additionalProperties: false };
const noDash = (x) => String(x).replace(/\s*—\s*/g, ', '); // the owner never wants em dashes
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// Finishing tools (cfg.codexFinish): the model may pass its final reply as `done`. If the call succeeds (so the broker
// allowed it), agentd shows that reply and ends the turn: the "tell the user it's done" model call (~2.5 s) is skipped.
const FINISH = new Set(['set_alarm', 'set_timer', 'web_search', 'notifications_reply', 'act', 'build_app']);
const DONE = { type: 'string', description: 'Only if this call completes the whole request and nothing is left to read or report: your final short reply to the user, e.g. "Alarm set for 7:00 AM." It is shown only if the call succeeds, and your turn ends there.' };
const DONE_HINT = '- Finish in the same call: when an action call completes the request (set_alarm, set_timer, web_search, notifications_reply, or an act that ends with the result on screen), put your final reply in its `done` field. Then you are finished; don\'t send another message.';
const tier = () => cfg.codexTier && { serviceTier: cfg.codexTier };
// bench/climb.js: prompt and tool-description variants, applied without a code change.
const patch = cfg.promptPatch ? JSON.parse(Buffer.from(cfg.promptPatch, 'base64').toString()) : {};
const patched = (p) => (patch.replace || []).reduce((a, [f, t]) => a.split(f).join(t), p) + (patch.append ? `\n${patch.append}` : '');

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
  #proc; #ready; #nid = 0; #pending = new Map(); #turns = new Map(); #voice = new Map(); #gen = 0; #stderr = []; #fails = 0; #warm = new Map();

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
    if (m.id != null) {
      this.#send({ id: m.id, ...(await this.#serverRequest(m, t)) });
      if (t?.finish != null && t.turnId) this.#req('turn/interrupt', { threadId: m.params.threadId, turnId: t.turnId }).catch(() => {});
      return;
    }
    if (!t) return;
    if (m.method !== 'error') t.last = Date.now(); // "Reconnecting..." errors are not progress
    switch (m.method) {
      case 'turn/started': t.turnId = m.params.turn.id; break;
      case 'item/agentMessage/delta': t.ctx.emit({ type: 'text.delta', text: m.params.delta }); break;
      case 'item/completed': if (m.params.item.type === 'agentMessage') t.text = m.params.item.text; break;
      case 'thread/tokenUsage/updated': { // one per model call: the bench reads usage.calls
        const u = (t.usage = m.params.tokenUsage.last);
        t.calls.push({ in: u.inputTokens, cached: u.cachedInputTokens, out: u.outputTokens, reasoning: u.reasoningOutputTokens, at: Date.now() - t.t0 });
        break;
      }
      case 'error': t.ctx.emit({ type: 'error', message: m.params.error.message, retryable: m.params.willRetry }); break;
      case 'turn/completed': if (t.finish != null) return t.done({ status: 'completed', usage: t.usage && { ...t.usage, calls: t.calls }, text: t.finish });
        t.done({ status: m.params.turn.status, error: m.params.turn.error?.message, usage: t.usage && { ...t.usage, calls: t.calls }, text: t.text }); break;
    }
  }

  async #serverRequest(m, t) {
    if (m.method === 'item/tool/call') {
      const { tool } = m.params, { done, ...args } = m.params.arguments || {};
      if (!t) return { result: { success: false, contentItems: [{ type: 'inputText', text: 'No active turn' }] } };
      t.tools++;
      const r = await runTool(tool, args, t.ctx).finally(() => { t.tools--; t.last = Date.now(); });
      if (cfg.codexFinish && FINISH.has(tool) && typeof done === 'string' && done.trim() && r.success) { t.finish = done.trim(); t.ctx.emit({ type: 'text.delta', text: t.finish }); }
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
    // Tools are fixed at thread/start (turn/start has no tools field). The first request's plugins are loaded into the thread;
    // later ones reach the rest through load_plugin. Codex's own deferLoading isn't used: with gpt-6.1-terra (a code-mode
    // model) deferred dynamic tools just vanish and no tool_search appears (TOOLS-AT-SCALE-RESULTS.md). A plugin switched
    // on or off starts a new thread, so the model never sees a tool the owner turned off.
    const ts = await toolset(ctx.turn.goal);
    if (!s || s.gen !== this.#gen || s.off !== ts.off.join()) {
      if (s?.gen === this.#gen) this.#req('thread/unsubscribe', { threadId: s.threadId }).catch(() => {});
      const names = [...ts.names, ...(ts.deferred.length ? Object.keys(LOADERS) : [])];
      const r = await this.#req('thread/start', {
        ...(cfg.codexModel && { model: cfg.codexModel }), ...tier(), cwd: cfg.codexCwd, ephemeral: true,
        sandbox: 'read-only', approvalPolicy: 'never', baseInstructions: `${patched(systemPrompt(ts))}${cfg.codexFinish ? `\n${DONE_HINT}` : ''}\n\n# Apps on this phone (label=package)\n${await appList()}`,
        dynamicTools: names.map((name) => { const t = spec(name); return { type: 'function', name, description: patch.tools?.[name] ?? t.description,
          inputSchema: cfg.codexFinish && FINISH.has(name) ? { ...t.schema, properties: { ...t.schema.properties, done: DONE } } : t.schema }; }),
      });
      s = session.codex = { threadId: r.thread.id, gen: this.#gen, off: ts.off.join(), loaded: ts.loaded, deferred: ts.deferred };
    }
    const r = await this.#turn(s.threadId, text, ctx, { effort: cfg.codexEffort, images: ctx.images }); // a photo the user attached (server.js ask)
    if (r.usage) r.usage.plugins = { mode: cfg.tools, loaded: s.loaded, deferred: s.deferred }; // for the bench
    return r;
  }

  #turn(threadId, text, ctx, { images = [], ...extra } = {}) { // images: data: URLs sent with the text (vision.js)
    return new Promise((resolve) => {
      // Watchdog: on a dead network Codex can wait silently for minutes. No progress for STALL_MS (approval waits
      // don't count) -> recycle app-server; the turn then fails as "exited" and run() retries it.
      const dog = setInterval(() => {
        if (this.#turns.get(threadId) !== t) return t.done({ status: 'failed', error: 'turn superseded' }); // orphaned: never kill app-server for it
        if (!t.tools && Date.now() - t.last > STALL_MS) { log('codex turn stalled; restarting app-server'); this.#proc?.kill(); }
      }, 5000);
      const t = { ctx, usage: null, calls: [], t0: Date.now(), tools: 0, last: Date.now(), done: (r) => { clearInterval(dog); if (this.#turns.get(threadId) === t) this.#turns.delete(threadId); resolve(r); } };
      this.#turns.get(threadId)?.done({ status: 'failed', error: 'turn superseded' }); // one live turn per thread
      this.#turns.set(threadId, t);
      ctx.signal?.addEventListener('abort', () => t.turnId && this.#req('turn/interrupt', { threadId, turnId: t.turnId }).catch(() => {}), { once: true });
      this.#req('turn/start', { threadId, ...extra, input: [{ type: 'text', text, text_elements: [] }, ...images.map((url) => ({ type: 'image', url }))] })
        .then((r) => { t.turnId ??= r.turn?.id; })
        .catch((e) => t.done({ status: 'failed', error: e.message }));
    });
  }

  // Small tool-less JSON jobs on warm threads, one per kind: the warm connection and prompt cache make a turn ~1 s
  // instead of ~3 s. One request at a time per kind; rotated so history stays small. Returns the parsed JSON; abort = stale.
  // job: {model, effort, prompt, schema?, rotate, images?}; onDelta(text) streams the output (apps.js shows build progress). No schema = raw text.
  async quick(kind, job, text, signal, onDelta) {
    await this.ready();
    let s = this.#warm.get(kind);
    if (!s || s.gen !== this.#gen || s.n >= job.rotate) {
      if (s) this.#req('thread/unsubscribe', { threadId: s.threadId }).catch(() => {});
      const { thread } = await this.#req('thread/start', { ...(job.model && { model: job.model }), ...tier(), cwd: cfg.codexCwd,
        ephemeral: true, sandbox: 'read-only', approvalPolicy: 'never', baseInstructions: job.prompt, dynamicTools: [] });
      this.#warm.set(kind, (s = { threadId: thread.id, gen: this.#gen, n: 0, idle: Promise.resolve() }));
    }
    await s.idle; // a stale or timed-out turn is being interrupted; wait for it to end
    if (signal.aborted) throw new Error('stale');
    s.n++;
    const turn = this.#turn(s.threadId, text, { emit: (m) => m.type === 'text.delta' && onDelta?.(m.text), signal }, { effort: job.effort, images: job.images, ...(job.schema && { outputSchema: job.schema }) });
    s.idle = turn;
    const r = await turn;
    this.lastUsage = r.usage; // token counts of the last quick job (proactive eval reads it)
    if (r.status !== 'completed') throw new Error(r.error || r.status);
    return job.schema ? JSON.parse(r.text) : r.text;
  }

  // Keyboard suggestions -> items[].
  async suggest(text, signal) {
    return (await this.quick('suggest', { model: cfg.suggestModel, effort: cfg.suggestEffort, prompt: SUGGEST_PROMPT, schema: SUGGEST_SCHEMA, rotate: 30 }, text, signal)).items.map(noDash);
  }
  // Keyboard "Write for me" (write / shorter / casual / formal / redo / translate / summary) -> one finished text.
  async write(text, signal) {
    return noDash((await this.quick('write', { model: cfg.suggestModel, effort: cfg.suggestEffort, prompt: WRITE_PROMPT, schema: WRITE_SCHEMA, rotate: 30 }, text, signal)).text);
  }

  // Voice: a separate thread with NO tools. Delegations are handed to agentd (onEvent gets every realtime
  // notification, including itemAdded/handoff_request), which runs them as normal turns and answers with voiceSay.
  // Codex sends the realtime call once, with no retry: one network blip ("Connection failed: error sending request")
  // used to leave the page on "Connecting". So an error before the SDP answer fails fast and we retry (3 tries).
  async voiceStart(sdp, on, extra = {}) { // extra: more thread/realtime/start params (voice.js passes its prompt)
    for (let i = 0; ; i++) {
      try { return await this.#voiceStartOnce(sdp, on, extra); }
      catch (e) { if (i >= 2 || !TRANSIENT.test(e.message)) throw e; log(`voice start failed (${e.message}); retry ${i + 1}/2`); await sleep(500 * 2 ** i); }
    }
  }
  async #voiceStartOnce(sdp, on, extra) {
    await this.ready();
    const { thread } = await this.#req('thread/start', { cwd: cfg.codexCwd, ephemeral: true, sandbox: 'read-only', approvalPolicy: 'never',
      baseInstructions: 'You relay spoken requests. You have no tools; another agent does the work.', dynamicTools: [] });
    let fail;
    const answer = new Promise((ok, no) => { fail = no; setTimeout(() => no(new Error('SDP answer timed out (20 s)')), 20_000);
      this.#voice.set(thread.id, { answer: (s) => { fail = null; ok(s); }, // after the answer, every notification goes to voice.js
        on: (m) => fail && /realtime\/(error|closed)$/.test(m.method) ? fail(new Error(m.params?.message || m.params?.reason || m.method)) : on(m) }); });
    answer.catch(() => {});
    try {
      await this.#req('thread/realtime/start', { threadId: thread.id, outputModality: 'audio', version: 'v3', clientManagedHandoffs: true,
        includeStartupContext: false, transport: { type: 'webrtc', sdp }, ...extra });
      return { threadId: thread.id, sdp: await answer };
    } catch (e) { this.#voice.delete(thread.id); this.#req('thread/realtime/stop', { threadId: thread.id }).catch(() => {}); throw e; }
  }
  voiceSay(threadId, text) { return this.#req('thread/realtime/appendSpeech', { threadId, text }); }
  voiceText(threadId, text, role = 'user') { return this.#req('thread/realtime/appendText', { threadId, text, role }); }
  voiceStop(threadId) { return this.#req('thread/realtime/stop', { threadId }); }

  async stop(session) {
    const s = session.codex, t = s && this.#turns.get(s.threadId);
    if (t?.turnId) await this.#req('turn/interrupt', { threadId: s.threadId, turnId: t.turnId }).catch(() => {});
  }
}
