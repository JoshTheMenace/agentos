// Cloud by default, local when the phone cannot reach the internet. Never replay a phone action after a disconnect.
import { existsSync } from 'node:fs';
import { homedir, networkInterfaces } from 'node:os';
import { join } from 'node:path';

const CONNECTION = /dns|lookup|resolve|ENOTFOUND|EAI_AGAIN|error sending request|stream disconnected|connect|timed? ?out|network|unreachable|routing discovery failed/i;
export function reachability({ probe = () => Promise.any(['https://chatgpt.com/', 'https://connectivitycheck.gstatic.com/generate_204'].map(async (url) => {
  const r = await fetch(url, { method: 'HEAD', redirect: 'manual', signal: AbortSignal.timeout(2500) }); // a cold TLS handshake can pass 1 s (bug hunt 2: back online, 2 of 7 asks went to the local model)
  await r.body?.cancel(); // any HTTP response (even 403/503) proves connectivity; a cloud outage is not offline
  return true;
})), interfaces = networkInterfaces, forced = () => process.env.AGENTD_FORCE_OFFLINE === '1'
  || existsSync(join(process.env.AGENTOS_HOME || join(homedir(), '.agentos'), 'force-offline')), now = Date.now } = {}) {
  let cached, until = 0, pending, fingerprint;
  return async (fresh = false) => {
    if (forced()) return false; // test-only, phone networking stays untouched
    const addresses = Object.values(interfaces()).flat().filter((x) => !x.internal && !/^(fe80:|169\.254\.)/.test(x.address)).map((x) => x.address).sort().join();
    if (!addresses) return false;
    if (!fresh && addresses === fingerprint && now() < until) return cached;
    if (pending) return pending;
    fingerprint = addresses;
    pending = Promise.resolve().then(probe).catch(() => false).then((up) => {
      cached = up; until = now() + (up ? 5000 : 1000); pending = null; return up;
    });
    return pending;
  };
}

export class Router {
  id = 'codex';
  constructor(codex, local, online = reachability()) {
    Object.assign(this, { codex, local, online });
    void online(); setInterval(() => online(), 3000).unref(); // warm cache: ordinary online asks do not wait on a probe
  }
  available() { return this.codex.available(); }
  async select(p = this) { return p === this && !await this.online() ? this.local : p; }
  stop(session) { return this.codex.stop(session); }
  async run(session, text, ctx) {
    const choose = (p) => { ctx.provider = p.id; ctx.emit({ type: 'provider.selected', provider: p.id }); };
    const local = async () => {
      choose(this.local);
      if (ctx.images?.length) return { status: 'failed', error: 'Photos need an internet connection.', provider: 'local' };
      let answer = '';
      const r = await this.local.run(session, ctx.turn.goal, { ...ctx, emit: (m) => { if (m.type === 'text.delta') answer += m.text; ctx.emit(m); } });
      return { ...r, text: r.text || answer, provider: 'local' }; // workers need the answer, not just streamed deltas
    };
    if (await this.select() === this.local) return local();
    choose(this.codex);
    const abort = new AbortController(), signal = AbortSignal.any([ctx.signal, abort.signal]);
    let safe = !ctx.turn.acted, disconnected = false, checking;
    const r = await this.codex.run(session, text, { ...ctx, signal, emit: (m) => {
      if (m.type === 'text.delta' && m.text || m.type === 'tool.request' && m.tier !== 'read') safe = false;
      // Native stream errors arrive before Codex's retries. Interrupt that turn, then fall back only if no action started.
      if (safe && m.type === 'error' && CONNECTION.test(m.message || '') && m.code !== 'transient' && !checking)
        checking = this.online(true).then((up) => { if (!up && safe && !ctx.turn.acted) { disconnected = true; abort.abort(); void this.codex.stop(session).catch(() => {}); } });
      ctx.emit({ ...m, provider: 'codex' });
    } }).catch((e) => ({ status: 'failed', error: e.message }));
    await checking;
    if (!ctx.signal.aborted && safe && !ctx.turn.acted && (disconnected || r.status === 'failed' && CONNECTION.test(r.error || '') && !await this.online(true))) return local();
    return { ...r, provider: 'codex' };
  }
}
