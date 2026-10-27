// Broker events (GET /events, server-sent events): approval.shown/resolved, guard.flag, task.state.
// Approvals are a tap on the native card (or the fingerprint). Extra channels, each only if the broker lists it in
// allowed_channels: "voice" (the user SAYS a clear yes/no in a voice session), "tap" (the launcher's mirrored button),
// and later "reviewer" (an automated reviewer for low-risk actions; hook below, not built yet).
// Analogy: the broker rings a bell when it puts an approval card up; agentd listens and tells every UI.
import { cfg, log } from './config.js';
import { phoneAuth, rpc } from './tools.js';

export const FORWARD = new Set(['approval.shown', 'approval.resolved', 'guard.flag', 'task.state']);
export const pending = new Map(); // approval_id -> approval.shown payload
export const state = { connected: false, answeredAt: 0 };
const listeners = new Set();
export const onEvent = (fn) => (listeners.add(fn), () => listeners.delete(fn));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Reviewer hook: setReviewer(async (approval) => ({approve, rationale}) | null). Not built yet (see spikes/codex-reviewer/).
// It is only asked when the broker offers the "reviewer" channel; it can only approve, and the broker re-checks.
let reviewer = null;
export const setReviewer = (fn) => { reviewer = fn; };
async function review(a) {
  const d = await reviewer(a).catch((e) => (log(`reviewer failed: ${e.message}`), null));
  if (d?.approve) await rpc('approval.confirm', { approval_id: a.approval_id, channel: 'reviewer', rationale: String(d.rationale || '').slice(0, 500) }, 10_000)
    .catch((e) => log(`reviewer confirm failed: ${e.message}`));
}

function emit(e) {
  if (e.type === 'approval.shown') pending.set(e.approval_id, e);
  if (e.type === 'approval.shown' && reviewer && e.allowed_channels?.includes('reviewer')) review(e);
  if (e.type === 'approval.resolved') pending.delete(e.approval_id);
  for (const fn of listeners) fn(e);
}

// Stay subscribed forever; on every (re)connect resync the pending list with approval.list.
export async function watch() {
  for (let wait = 1000, warned = false; ; wait = Math.min(wait * 2, 30_000)) {
    try {
      const res = await fetch(cfg.phoneUrl.replace(/\/rpc$/, '/events'), { headers: { authorization: phoneAuth(), accept: 'text/event-stream' } });
      if (!res.ok) throw new Error(`GET /events: HTTP ${res.status}`);
      state.connected = true; wait = 1000; warned = false;
      log('broker events connected');
      const list = await rpc('approval.list', {}, 5000).catch(() => null);
      pending.clear();
      for (const a of list?.approvals || []) pending.set(a.approval_id, a);
      let buf = '', name = null;
      for await (const chunk of res.body.pipeThrough(new TextDecoderStream())) {
        buf += chunk;
        for (let i; (i = buf.indexOf('\n')) >= 0; buf = buf.slice(i + 1)) {
          const line = buf.slice(0, i).replace(/\r$/, '');
          if (line.startsWith('event:')) name = line.slice(6).trim();
          else if (line.startsWith('data:')) { try { const d = JSON.parse(line.slice(5)); emit({ type: d.type || name, ...d }); } catch {} }
          else if (!line) name = null;
        }
      }
    } catch (e) { if (!warned) log(`broker events unavailable (${e.message}); retrying with backoff`); warned = true; }
    state.connected = false;
    await sleep(wait);
  }
}

// Strict: the whole utterance must be yes-words or no-words. Anything else is not an answer (it goes to the model).
const YES = /^(?:(?:yes|yeah|yep|approve(?: it)?|approved|confirm(?: it)?|go ahead|send it|do it)\s*)+$/;
const NO = /^(?:(?:no|nope|cancel(?: it)?|don'?t(?: send it| do it)?|do not(?: send it| do it)?|deny(?: it)?|stop)\s*)+$/;
export function intent(utterance) {
  const u = String(utterance || '').toLowerCase().replace(/[’`]/g, "'").replace(/[^a-z' ]+/g, ' ').replace(/\bplease\b/g, ' ').replace(/\s+/g, ' ').trim();
  return YES.test(u) ? 'approve' : NO.test(u) ? 'deny' : null;
}

// Call ONLY with the user's own voice transcription: never typed chat, model output or tool results. Returns null if it isn't an answer to exactly one pending approval; otherwise {text, ...} to show/say.
// Saying no is always passed on (denying can't hurt). Saying yes needs the channel in allowed_channels.
export async function answer(utterance, channel) {
  const said = intent(utterance), all = [...pending.values()];
  if (!said || all.length !== 1) return null;
  const a = all[0], base = { approval_id: a.approval_id, decision: said, channel };
  if (said === 'approve' && !a.allowed_channels?.includes(channel)) return { ...base, ok: false, needs_fingerprint: true, text: 'This one needs your fingerprint.' };
  const r = await rpc(said === 'approve' ? 'approval.confirm' : 'approval.deny', { approval_id: a.approval_id, channel, utterance: String(utterance).slice(0, 200) }, 10_000)
    .catch((e) => ({ ok: false, reason: e.message }));
  state.answeredAt = Date.now();
  if (r.ok === false) return { ...base, ok: false, text: `I couldn't ${said} it: ${r.reason || r.error}.` };
  return { ...base, ok: true, text: said === 'approve' ? 'Approved.' : 'Cancelled.' };
}

// What to say when the card goes up during a voice session.
export const prompt = (a) => (a.allowed_channels?.includes('voice')
  ? `Should I ${a.tool === 'tap' ? `tap ${a.target}` : a.tool}${a.text ? `: "${String(a.text).slice(0, 80)}"` : ''}? Say yes or no.`
  : 'Please approve that on your phone with your fingerprint.');

// The launcher's mirrored Approve button. The broker decides whether it accepts "tap" from us.
export async function tap(approvalId) {
  const a = pending.get(approvalId), base = { approval_id: approvalId, decision: 'approve', channel: 'tap' };
  if (!a) return { ...base, ok: false, text: 'That approval is no longer pending.' };
  if (!a.allowed_channels?.includes('tap')) return { ...base, ok: false, text: 'Approve it on the card on your phone.' };
  const r = await rpc('approval.confirm', { approval_id: approvalId, channel: 'tap' }, 10_000).catch((e) => ({ ok: false, reason: e.message }));
  return { ...base, ok: r.ok !== false, text: r.ok === false ? `Not approved: ${r.reason || r.error}.` : 'Approved.' };
}
