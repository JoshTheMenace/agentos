// Home suggestions: context.suggestions() (unanswered threads) and proactive cards; only things to act on (the blocked/tracker tallies live in the launcher's command center). TODO(context): fold into context.js if you like; keep the message shape:
// {type:"suggestions", items:[{id, title, detail, app?, goal?, icon?, view?}]} pushed only to clients that sent {type:"suggestions.watch"}
// (the keyboard also reads "suggestions" replies and would choke on an unrequested one).
import * as context from './context.js';
import * as proactive from './proactive.js';
import * as activity from './activity.js';
import { rpc } from './tools.js';

export async function items() {
  // PROACTIVE=1: the proactive cards (only threads that really need you, calendar conflicts/prep); else every unanswered thread
  const nl = await rpc('notifications.list', { n: 40 }, 3000).catch(() => ({}));
  const toLookAt = await activity.digest(nl.notifications, rpc);
  return [...(proactive.enabled() ? proactive.cards() : await context.suggestions().catch(() => [])), ...activity.cards(toLookAt)];
}

// Sends the feed now and again (debounced) whenever the audit or trackers change, until `off()` is called.
export function watch(send, onEvent) {
  let t = null, dead = false;
  const push = async () => { const list = await items().catch(() => []); if (!dead) send({ type: 'suggestions', items: list }); };
  push();
  const off = onEvent((e) => { if (e.type === 'audit.append' || e.type === 'tracker.blocked' || e.type === 'promises' || e.type === 'notification.posted' || e.type === 'notification.removed') { clearTimeout(t); t = setTimeout(push, 1500); } });
  return () => { dead = true; clearTimeout(t); off(); };
}
