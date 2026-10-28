// Home suggestions: context.suggestions() (unanswered threads) + two broker-derived items. TODO(context): fold into context.js if you like; keep the message shape:
// {type:"suggestions", items:[{id, title, detail, app?, goal?, icon?, view?}]} pushed only to clients that sent {type:"suggestions.watch"}
// (the keyboard also reads "suggestions" replies and would choke on an unrequested one).
// Today it reads two real sources through the broker: today's blocked/denied actions (audit.tail) and trackers.list when it exists.
import { rpc } from './tools.js';
import * as context from './context.js';
import * as proactive from './proactive.js';

export async function items() {
  // PROACTIVE=1: the proactive cards (only threads that really need you, calendar conflicts/prep); else every unanswered thread
  const out = proactive.enabled() ? proactive.cards() : await context.suggestions().catch(() => []);
  const a = await rpc('audit.tail', { n: 120 }, 10_000).catch(() => null), today = Math.max(new Date().setHours(0, 0, 0, 0), (a?.since || 0) + 1); // a demo reset (audit.demo_reset) starts the count over
  const bad = (a?.entries || []).filter((e) => e.ts >= today && /denied|blocked/.test(e.decision || ''));
  if (bad.length) out.push({ id: 'blocked', title: `${bad.length} action${bad.length > 1 ? 's' : ''} blocked today`, detail: 'See what the agent was stopped from doing', icon: 'shield', view: 'activity' });
  const t = await rpc('trackers.list', {}, 5_000).catch(() => null), apps = (t?.apps || []).filter((x) => !/^com\.duolingo/.test(x.pkg) && x.trackers?.some((k) => !k.blocked)); // founder: no Duolingo in the demo
  if (apps.length) out.push({ id: 'trackers', title: `${apps.length} app${apps.length > 1 ? 's' : ''} sent data to trackers today`, detail: apps.map((x) => x.label || x.pkg).slice(0, 3).join(', '), icon: 'eye', goal: `Block the trackers in ${apps.map((x) => x.label || x.pkg).join(' and ')}` });
  return out;
}

// Sends the feed now and again (debounced) whenever the audit or trackers change, until `off()` is called.
export function watch(send, onEvent) {
  let t = null, dead = false;
  const push = async () => { const list = await items().catch(() => []); if (!dead) send({ type: 'suggestions', items: list }); };
  push();
  const off = onEvent((e) => { if (e.type === 'audit.append' || e.type === 'tracker.blocked' || e.type === 'promises') { clearTimeout(t); t = setTimeout(push, 1500); } });
  return () => { dead = true; clearTimeout(t); off(); };
}
