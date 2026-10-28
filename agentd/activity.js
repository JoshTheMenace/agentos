// Non-message activity delivered by Android's listener. No app opening or outward actions.
import { createHash } from 'node:crypto';
import { classify } from './guard.js';

const APPS = { 'com.linkedin.android': 'LinkedIn', 'com.instagram.android': 'Instagram', 'com.Slack': 'Slack' };
const WINDOW = 12 * 36e5;
const EVENT = /\b(?:commented|replied|mentioned|tagged|reacted|liked|reposted|shared|followed|started following|accepted|celebrated|asked you)\b/i;
const clean = (s) => String(s || '').replace(/\s+/g, ' ').trim();
const mask = (s) => s.replace(/(?<![\d.:])\d{4,8}(?![\d.:])/g, '[code]');

export async function digest(rows, rpc, now = Date.now()) {
  const items = [], deadline = Date.now() + 2000;
  for (const n of rows || []) {
    if (!APPS[n.pkg] || n.message !== false || n.from_me || !Number.isFinite(n.when) || n.when > now || now - n.when > WINDOW) continue;
    const body = clean(n.text), sender = clean(n.sender), text = body.startsWith(sender) ? body : `${sender}: ${body}`;
    const event = EVENT.exec(body);
    if (!event || n.guard?.label === 'MALICIOUS' || /\[withheld/.test(text) || classify(text).label !== 'benign') continue;
    // Score sender + body together; a pending/unavailable listener verdict must not bypass Prompt Guard.
    const remaining = deadline - Date.now();
    if (remaining <= 0) break;
    const g = await rpc('guard.classify', { text }, remaining).catch(() => null);
    if (g?.ok !== true || g.label !== 'BENIGN') continue;
    const mention = /\b(?:mentioned|tagged) you\b/i.test(body);
    const question = /\b(?:asked you|(?:commented|replied) (?:on|to) your (?:post|comment|photo|video|story))\b[^]*\?/i.test(body);
    items.push({ id: createHash('sha256').update(`${n.pkg}:${n.key || text}`).digest('hex').slice(0, 16),
      app: APPS[n.pkg], pkg: n.pkg, source_pkg: n.pkg, source: 'notification', trusted: false,
      who: mask(body.slice(0, event.index).replace(/[.:\s]+$/, '') || sender), what: mask(body.slice(event.index)), text: mask(text),
      when: new Date(n.when).toISOString(), actionable: !n.removed && (mention || question) });
  }
  return items.sort((a, b) => b.when.localeCompare(a.when)).slice(0, 12);
}
export const row = (x) => ({ id: `activity-${x.id}`, icon: 'eye', title: `To look at · ${x.app}`,
  detail: x.text.slice(0, 110), app: x.pkg });
export const cards = (items) => items.filter((x) => x.actionable).slice(0, 2).map((x) => ({ ...row(x),
  goal: `Use catch_up to summarize activity delivered by my notifications from ${x.app}. Do not open apps or send anything.` }));
