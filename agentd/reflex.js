// Reflexes: a clear one-step ask ("scroll down", "go back", "open Calculator", "flashlight on", "pause the music") runs its
// one tool call at once, without the agent's planning turn. Analogy: pulling your hand off a hot stove before thinking.
// The call still goes through runTool like any model call (lease, tier, approval card, audit, guard): only the planning is
// skipped, never the gate. Anything else (two steps, "and", a question, an unknown app) gets the normal agent turn.
// Two ways in: a strict parser for plain phrasings (instant), else a tiny warm model call for short reflex-like asks
// ("can you scroll a little further down") that answers one tool or "none" (~1.3 s; agentd/research/FAST-DECISIONS.md).
import { appList, runTool } from './tools.js';
import { log } from './config.js';

const NUM = { a: 1, an: 1, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, ten: 10, fifteen: 15, twenty: 20, thirty: 30, forty: 40, 'forty-five': 45, sixty: 60, ninety: 90 };
const FILL = new Set(['the', 'page', 'it', 'a', 'little', 'bit', 'more', 'further', 'some', 'way', 'farther', 'me']);
const UNIT = { second: 1, minute: 60, hour: 3600 };
const scroll = (dir = 'down') => ({ tool: 'scroll', args: { dir }, ui: true, say: `Scrolled ${dir}.` });
const press = (key) => ({ tool: 'press', args: { key }, ui: true, say: key === 'back' ? 'Went back.' : 'Went home.' });
const torch = (on) => ({ tool: 'phone_settings', args: { torch: on }, say: `Flashlight ${on ? 'on' : 'off'}.` });
const media = (action) => ({ tool: 'media', args: { action }, say: { play: 'Playing.', pause: 'Paused.', next: 'Next track.', previous: 'Previous track.' }[action] });
const volume = (v, delta) => ({ tool: 'phone_settings', args: { volume: v }, delta, say: delta ? `Volume ${delta > 0 ? "up" : "down"}.` : v ? `Volume ${v}%.` : "Muted." });
const timer = (n, unit) => n > 0 && UNIT[unit] && { tool: 'set_timer', args: { seconds: n * UNIT[unit] }, say: `Timer set for ${n} ${unit}${n === 1 ? '' : 's'}.` };
function alarm(h, m = 0, ap) {
  if (h > 23 || m > 59 || (!ap && h <= 12)) return null; // "alarm for 7": morning or evening? the agent asks
  const hour = ap === 'a' || ap === 'p' ? (h % 12) + (ap === 'p' ? 12 : 0) : h; // ap '24': already a 24 h hour
  return { tool: 'set_alarm', args: { hour, minute: m }, say: `Alarm set for ${(hour % 12) || 12}:${String(m).padStart(2, '0')} ${hour < 12 ? 'AM' : 'PM'}.` };
}
async function open(name, fg) {
  const apps = (await appList()).split('\n').map((l) => l.split('=')).filter((a) => a[1]), n = name.toLowerCase().replace(/ app$/, '');
  const hit = apps.filter(([l]) => l.toLowerCase() === n), pre = apps.filter(([l]) => l.toLowerCase().startsWith(n));
  const [label, pkg] = hit[0] || (pre.length === 1 ? pre[0] : []);
  if (!pkg) return null;
  return pkg === fg?.pkg ? { say: `${label} is already open.` } : { tool: 'open_app', args: { pkg }, ui: true, say: `Opened ${label}.` };
}

// text -> {tool, args, say, ui?, delta?} | null. inApp: asked from the bar over fg (scroll/back/home act on that app).
export async function parse(text, fg) {
  const t = String(text).toLowerCase().replace(/[.!?]+$/, '').replace(/^(hey |ok |okay )?(please |can you |could you )?/, '').replace(/,? please$/, '').trim();
  let m;
  const dir = fg && (m = t.match(/^(?:scroll|swipe|page)\b(.*)$/)) && m[1].split(' ').filter((w) => w && !FILL.has(w)).join(' '); // "scroll a little further down"
  if (dir === '' || /^(down|up|left|right)$/.test(dir)) return scroll(dir || 'down');
  if (fg && /^(?:go |press )?back$|^(?:go|take me) back(?: a page| one page)?$/.test(t)) return press('back');
  if (fg && /^(?:go |press )?home$|^go (?:to )?(?:the )?home(?: ?screen)?$/.test(t)) return press('home');
  if ((m = t.match(/^(?:turn|switch) (on|off) (?:the )?(?:flashlight|torch)$|^(?:turn |switch )?(?:the )?(?:flashlight|torch) (on|off)$/))) return torch((m[1] || m[2]) === 'on');
  if ((m = t.match(/^(?:turn (?:the )?)?volume (up|down)$|^turn (?:it|the volume) (up|down)$/))) return volume(null, (m[1] || m[2]) === 'up' ? 10 : -10);
  if (t === 'mute') return volume(0);
  if ((m = t.match(/^set (?:the )?volume to (\d{1,3})(?: ?%| percent)?$/)) && +m[1] <= 100) return volume(+m[1]);
  if ((m = t.match(/^(play|pause|resume|stop)(?: (?:the )?(?:music|song|video|playback|podcast))?$/))) return media(m[1] === 'resume' ? 'play' : m[1] === 'stop' ? 'pause' : m[1]);
  if ((m = t.match(/^(?:(next|previous)(?: (?:song|track))?|skip(?: (?:this|the))?(?: song| track)?)$/))) return media(m[1] || 'next');
  if ((m = t.match(/^(?:set |start )?(?:a )?timer for (\S+) (second|minute|hour)s?$|^(?:set |start )?(?:a )?(\S+?)[ -](second|minute|hour) timer$/))) return timer(NUM[m[1] || m[3]] ?? Number(m[1] || m[3]), m[2] || m[4]) || null;
  if ((m = t.match(/^(?:set |make )?(?:an )?alarm for (\d{1,2})(?::(\d{2}))? ?([ap])?\.?(?:m\.?)?$/))) return alarm(+m[1], +(m[2] || 0), m[3]);
  if ((m = t.match(/^(?:open|launch|start)(?: up)? (?:the )?([a-z0-9][\w .&'+-]{0,30})$/))) return open(m[1], fg);
  return null;
}

// Short, single-action-looking asks the parser missed go to the model picker; everything else skips it (no added latency).
const MAYBE = /\b(scroll|swipe|back|home|open|launch|flash ?light|torch|volume|louder|quieter|softer|mute|play|pause|resume|next|skip|previous|timer|alarm)\b/;
const NOT = /\b(and|then|also|after|tell|what|who|why|how|when|where|read|send|text|reply|message|email|call|post|buy|pay|delete|search|find)\b|[,;]/;
const SCHEMA = { type: 'object', additionalProperties: false, required: ['tool', 'dir', 'key', 'on', 'volume', 'delta', 'action', 'app', 'seconds', 'hour', 'minute'], properties: {
  tool: { enum: ['none', 'scroll', 'press', 'torch', 'volume', 'media', 'open', 'timer', 'alarm'] }, dir: { enum: ['up', 'down', 'left', 'right', null] }, key: { enum: ['back', 'home', null] },
  on: { type: ['boolean', 'null'] }, volume: { type: ['integer', 'null'] }, delta: { type: ['integer', 'null'] }, action: { enum: ['play', 'pause', 'next', 'previous', null] },
  app: { type: ['string', 'null'] }, seconds: { type: ['integer', 'null'] }, hour: { type: ['integer', 'null'] }, minute: { type: ['integer', 'null'] } } };
const PROMPT = `You map one phone request to at most ONE action, or "none". Reply with the JSON only.
Actions: scroll {dir} (the app on screen) · press {key: back|home} · torch {on} · volume {volume 0-100 or delta ±10} · media {action}
· open {app: the app's name as the user said it} · timer {seconds} · alarm {hour 0-23, minute} (only if AM/PM is clear).
Say "none" if it needs more than one action, reading or answering anything, typing or sending, a choice you would have to guess,
or is not clearly one of these. Unused fields are null.`;
let codex;
export const init = (c) => { codex = c; pick('scroll down', null, AbortSignal.timeout(60_000)).catch(() => {}); }; // warm the thread
async function pick(text, fg, signal) {
  const job = { model: 'gpt-6.1-terra', effort: 'low', prompt: PROMPT, schema: SCHEMA, rotate: 20 };
  const d = await codex.quick('reflex', job, JSON.stringify({ request: text, app_on_screen: fg?.app || null }), signal);
  return d.tool === 'scroll' && fg ? scroll(d.dir || 'down') : d.tool === 'press' && fg && d.key ? press(d.key) : d.tool === 'torch' && d.on != null ? torch(d.on)
    : d.tool === 'volume' ? (d.delta ? volume(null, Math.sign(d.delta) * 10) : d.volume != null ? volume(Math.max(0, Math.min(100, d.volume))) : null)
    : d.tool === 'media' && d.action ? media(d.action) : d.tool === 'open' && d.app ? open(d.app, fg) : d.tool === 'timer' && d.seconds > 0 ? timer(d.seconds, 'second')
    : d.tool === 'alarm' && d.hour != null ? alarm(d.hour, d.minute || 0, '24') : null;
}

// One reflex for this ask: {status, text, reflex} when it ran (or was refused by the broker), null = take the agent turn.
export async function run(text, fg, ctx) {
  const words = String(text).trim().split(/\s+/).length, t0 = Date.now();
  let rx = await parse(text, fg), how = 'parse';
  if (!rx && codex && ctx.provider === 'codex' && words <= 9 && MAYBE.test(text.toLowerCase()) && !NOT.test(text.toLowerCase())) {
    rx = await pick(text, fg, AbortSignal.any([ctx.signal, AbortSignal.timeout(4000)])).catch((e) => (log(`reflex pick: ${e.message}`), null)); how = 'model';
  }
  if (!rx) return null;
  if (rx.ui) ctx.turn.pkg = fg?.pkg; // scroll/back/home act on the app in front: runTool leases it
  let ok = true, out = {};
  if (rx.delta) { const st = JSON.parse((await runTool('phone_status', {}, ctx)).text); rx.args.volume = Math.max(0, Math.min(100, (st.volume ?? 50) + rx.delta)); }
  if (rx.tool) { const r = await runTool(rx.tool, rx.ui ? { ...rx.args, screen: false } : rx.args, ctx); ok = r.success; out = JSON.parse(r.text); }
  log(`reflex ${how} "${String(text).slice(0, 40)}" -> ${rx.tool || 'none'} ${JSON.stringify(rx.args || {})} ${ok ? 'ok' : out.decision || out.error} ${Date.now() - t0} ms`);
  if (!ok && out.decision !== 'denied') return null; // blocked or failed (e.g. the app in front changed): the agent looks at the screen
  const say = ok ? rx.say : 'Not done: you said no.';
  ctx.emit({ type: 'text.delta', text: say });
  return { status: 'completed', text: say, reflex: `${how}:${rx.tool || 'none'}` };
}
