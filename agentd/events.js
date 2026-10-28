// Broker events (GET /events, server-sent events): approval.shown/resolved, guard.flag, task.state.
// Approvals are a tap on the native card (or the fingerprint). The broker also accepts, per approval's allowed_channels:
// "voice" (the user SAYS a clear yes/no in a voice session) and "reviewer" (reviewer.js, ordinary actions only).
// Analogy: the broker rings a bell when it puts an approval card up; agentd listens and tells every UI.
import { cfg, log } from './config.js';
import { phoneAuth, rpc } from './tools.js';

export const FORWARD = new Set(['mod.changed', 'approval.shown', 'approval.resolved', 'guard.flag', 'task.state', 'audit.append', 'review',
  'task.update', 'tool.request', 'tool.result', 'guard.alert', 'task.started', 'approval.needed',
  'notification.posted', 'notification.removed', 'rule', 'tracker.blocked', 'vault.changed', 'schedule.list', 'plugins.changed', 'remote.read',
  'apps.changed', 'tool.progress', 'promises', 'memory.asked', 'visual']); // visual: show tool (visual.js), also from background (voice) tasks // 5th: micro-apps (apps.js) and a long tool's progress // 4th: Blockers and Passwords views refresh live // 3rd line: the notification feed and reply rules (context.js) // 2nd line: background tasks (tasks.js)
export const pending = new Map(); // approval_id -> approval.shown payload
export const flagged = new Set(); // task_ids the broker raised guard.flag for
export const state = { connected: false, answeredAt: 0 };
const listeners = new Set();
export const onEvent = (fn) => (listeners.add(fn), () => listeners.delete(fn));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Reviewer hook: setReviewer(fn) -> fn(approval) runs for every approval.shown (see reviewer.js).
let reviewer = null;
export const setReviewer = (fn) => { reviewer = fn; };

export function publish(e) { // also used for agentd's own events (review) so every UI hears them
  if (e.type === 'hello') { pending.clear(); for (const a of e.approvals || []) pending.set(a.approval_id, a); }
  if (e.type === 'guard.flag' && e.task_id) flagged.add(e.task_id);
  if (e.type === 'approval.shown') { pending.set(e.approval_id, e); reviewer?.(e); }
  if (e.type === 'approval.resolved') pending.delete(e.approval_id);
  for (const fn of listeners) fn(e);
}

// Stay subscribed forever; on every (re)connect the broker's "hello" resyncs the pending list.
export async function watch() {
  for (let wait = 1000, warned = false; ; wait = Math.min(wait * 2, 30_000)) {
    try {
      const res = await fetch(cfg.phoneUrl.replace(/\/rpc$/, '/events'), { headers: { authorization: phoneAuth(), accept: 'text/event-stream' } });
      if (!res.ok) throw new Error(`GET /events: HTTP ${res.status}`);
      state.connected = true; wait = 1000; warned = false;
      log('broker events connected'); // the first event is "hello" with the open approvals
      let buf = '', name = null;
      for await (const chunk of res.body.pipeThrough(new TextDecoderStream())) {
        buf += chunk;
        for (let i; (i = buf.indexOf('\n')) >= 0; buf = buf.slice(i + 1)) {
          const line = buf.slice(0, i).replace(/\r$/, '');
          if (line.startsWith('event:')) name = line.slice(6).trim();
          else if (line.startsWith('data:')) { try { const d = JSON.parse(line.slice(5)); publish({ type: d.type || name, ...d }); } catch {} }
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
  ? `Should I ${a.tool === 'tap' ? `tap ${a.target}` : a.tool === 'notifications.reply' ? String(a.target).toLowerCase() : a.tool}${a.text ? `: "${String(a.text).slice(0, 80)}"` : ''}? Say yes or no.`
  : 'Please approve that on your phone with your fingerprint.');
