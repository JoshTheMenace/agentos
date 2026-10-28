// Micro-apps: "I want to track my workouts" -> one self-contained HTML file written by a model, kept in ~/.agentos/apps/<id>/,
// shown on the home screen and run full screen by the launcher's MicroApp WebView (own origin per app, no network, a
// 3-method bridge). Analogy: a 3D printer for little tools. The model only designs the file; the launcher's sandbox decides
// what the file may touch (nothing but its own storage). agentd serves the files over GET /apps (bearer token).
import { existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { cfg, log } from './config.js';

const DIR = join(process.env.AGENTOS_HOME || join(homedir(), '.agentos'), 'apps');
const S = { type: 'string' }, obj = (props, required = []) => ({ type: 'object', properties: props, required, additionalProperties: false });
export const tools = [
  { name: 'build_app', tier: 'act', description: 'Build a small custom app for the user and put it on their home screen: trackers (workouts, water, habits, plants, '
    + 'mood), counters, logs, checklists, timers, dice rollers, calculators, scoreboards... Pass the request in the user\'s words plus what they implied; the app is '
    + 'designed and written for them (about half a minute, no questions: pick sensible defaults). To change an app that exists ("add a rest timer to my workout app"), '
    + 'pass update = its name; its current code is updated in place. Returns {id, name}. Then tell the user it is on their home screen.',
    schema: obj({ request: { ...S, description: 'what the app should do, in the user\'s words' }, update: { ...S, description: 'name or id of an existing app to change' } }, ['request']) },
  { name: 'list_my_apps', tier: 'read', description: 'The custom apps built for the user: {apps:[{id, name, description}]}.', schema: obj({}) },
  { name: 'remove_app', tier: 'act', description: 'Remove one of the user\'s custom apps by name or id (only when they ask).', schema: obj({ app: S }, ['app']) },
];

// The generator's whole brief: what a micro-app is, the runtime it may use (web/apps/ui.css + ui.js), and the look.
export const GEN = `You write one self-contained micro-app for AgentOS, an AI-native phone OS with a calm dark iOS-like design. The user asked for it in one sentence; you build the whole thing, no questions.
Output ONLY the complete HTML file. No code fences, no commentary before or after.

# File shape
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<title>Name</title>                                  (2-3 words, e.g. "Workout Tracker", "Water", "Plant Care")
<meta name="agentos-icon" content="🏋️">            (exactly one emoji, shown as the app icon)
<meta name="description" content="one short line">
<link rel="stylesheet" href="/ui.css"><style>/* at most ~20 lines of your own CSS, only if needed */</style></head>
<body class="acc-blue">...markup...<script src="/ui.js"></script><script>/* your code */</script></body></html>

# Runtime (already there; do not reimplement)
- Storage: AOS.load(key, default) / AOS.save(key, value) — JSON, persists forever, private to this app. Keep one state object (e.g. {entries:[...]}), save after every change.
- Home widget: AOS.summary("3 workouts this week") — one short line shown under the app on the home screen. Call it on load and after every change, always with a real number/status.
- AOS.haptic() on every tap that changes data. AOS.toast("Logged") for confirmation. Never alert/prompt/confirm (they do nothing here): use inline inputs, and a two-tap "Sure?" for deletes.
- AOS.dayKey(date) -> "YYYY-MM-DD" local; AOS.lastDays(7) -> Date[]; AOS.dayName(d) -> "Mo"; AOS.esc(s) escapes text for innerHTML.
- Charts: AOS.bars(svgOrDiv, [values], [labels], {unit}) draws a bar chart (last bar highlighted); AOS.ring(el, 0..1) draws a progress ring around el's children.

# Components (classes from /ui.css; combine, don't restyle)
Layout: .hdr (h1 + .sub), .k eyebrow, .card (glass), .card.tint (accent-tinted), .card.center, .stack, .row/.row.between, .grid2/.grid3, .grow.
Numbers: .big (huge number, <small> unit), .stat (b + span), .ring, .bar>i (width %), .week>.d(.on/.today)>i+span (7-day dots).
Controls: .btn (.primary .ghost .danger .wide .lg .round), .chips>.chip(.on), .tabs>button(.on), .input (wrap an <input>), .toggle(.on), .check(.on), .fab (bottom fixed bar; .fab.right = round +).
Lists: .list>.item (div.grow with .t and .s, .val on the right, .ic emoji square, .tag/.tag.acc/.g/.a/.c, .dot), .item.done.
Other: .empty>.emo+text+small (empty state), .muted, .tiny, .pop / .rise animations. Accent: body class acc-blue|green|orange|red|pink|purple|teal (pick one that fits: water=teal, plants=green, fitness=orange or pink, dice=purple, habits=green).
Chart: <svg class="chart"></svg> then AOS.bars(el, values, labels).

# Product rules
- Phone, portrait, 360 px wide, thumbs: touch targets >= 44 px, the main action big and at the bottom (.fab), one screen, no scrolling needed for the main action. Never restyle body padding or .fab: the runtime keeps the last block (empty states too) clear of the bar.
- Open with the most useful thing: today's number/status at the top (.big in a .card.tint), then the one-tap actions, then history/chart. Good empty state with a hint.
- Sensible defaults from the request (units, goals, presets as chips). Editing a goal or preset: inline, no dialogs. Everything local time; show relative dates ("Today", "Mon").
- Instant feedback: re-render after every change (one render() function from state), haptic + toast on logs. No page reloads.
- Vanilla JS only, no modules, no imports, no fetch, no external URLs, no images (emoji and inline SVG only), no frameworks. Keep the file under ~220 lines. Use innerHTML with AOS.esc for user text.
- Be a product designer: clear hierarchy, few words, delightful details (streaks, totals, a small chart) but nothing that distracts from the main action.`;

const view = (m) => ({ id: m.id, name: m.name, icon: m.icon, description: m.description, updated: m.updated });
export function list() {
  if (!existsSync(DIR)) return [];
  return readdirSync(DIR).map((id) => { try { return JSON.parse(readFileSync(join(DIR, id, 'manifest.json'), 'utf8')); } catch { return null; } })
    .filter((m) => m?.id).sort((a, b) => b.updated - a.updated);
}
export const html = (id) => readFileSync(join(DIR, id, 'index.html'), 'utf8');
const slug = (s) => String(s || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 24) || 'app';
const find = (q) => { // by id, exact name, or either containing the other ("workout" ~ "Workout Tracker")
  const w = String(q || '').toLowerCase().trim(), apps = list();
  return apps.find((a) => a.id === w || a.name.toLowerCase() === w) || apps.find((a) => a.name.toLowerCase().includes(w) || w.includes(a.name.toLowerCase().replace(/\s+(tracker|log|logger|app|counter)$/, '')));
};
const strip = (s) => String(s || '').replace(/^\s*```(?:html)?\s*/i, '').replace(/\s*```\s*$/, '').trim();
const meta = (page, name) => (page.match(new RegExp(`<meta[^>]+name=["']${name}["'][^>]*>`, 'i'))?.[0].match(/content=["']([^"']*)["']/i)?.[1] || '').trim();
const EMOJI = /\p{Extended_Pictographic}(?:️|‍\p{Extended_Pictographic})*/u;

let codex = null, io = null; // io: {publish}
export const init = (c, deps) => { codex = c; io = deps; mkdirSync(DIR, { recursive: true }); log(`apps: ${list().length} micro-apps (${DIR})`); };

export async function call(name, args, ctx) {
  if (name === 'list_my_apps') return { ok: true, apps: list().map(({ id, name, description }) => ({ id, name, description })) };
  if (name === 'remove_app') {
    const a = find(args.app);
    if (!a) return { ok: false, error: `no app matching "${args.app}"`, apps: list().map((x) => x.name) };
    rmSync(join(DIR, a.id), { recursive: true, force: true });
    io?.publish({ type: 'apps.changed', why: 'removed', id: a.id, app: view(a) });
    return { ok: true, removed: view(a) };
  }
  // build_app
  const req = String(args.request || '').trim().slice(0, 1500);
  if (!req) return { ok: false, error: 'request is required' };
  const prev = args.update ? find(args.update) : null;
  if (args.update && !prev) return { ok: false, error: `no app matching "${args.update}"; call again without update to build a new one`, apps: list().map((x) => x.name) };
  const t0 = Date.now(), progress = (text) => ctx.emit({ type: 'tool.progress', name: 'build_app', text, ms: Date.now() - t0 });
  progress(prev ? `Updating ${prev.name}` : 'Designing');
  const input = prev ? `Update this app. Change requested: ${req}\n\nCurrent index.html:\n${html(prev.id)}\n\nOutput the complete updated file (keep the stored data compatible).` : `Build this app: ${req}`;
  let out, bytes = 0, shown = 0;
  try {
    out = await codex.quick('apps', { model: cfg.appsModel, effort: cfg.appsEffort, prompt: GEN, rotate: 1 }, input, ctx.signal,
      (d) => { bytes += d.length; if (bytes - shown > 1200) { shown = bytes; progress(`Building · ${Math.round(bytes / 1024)} KB`); } });
  } catch (e) { return { ok: false, error: `build failed: ${e.message}` }; }
  const page = strip(out);
  if (!/<html[\s>]/i.test(page) || !/<\/html>\s*$/i.test(page) || !/<script/i.test(page)) return { ok: false, error: 'the generator did not return a complete HTML file; call build_app once more' };
  const bad = [...page.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)].map((m) => { try { new Function(m[1]); } catch (e) { return e.message; } }).find(Boolean); // a typo must not replace a working app
  if (bad) return { ok: false, error: `the generated script has a syntax error (${bad}); call build_app once more` };
  const title = (page.match(/<title>([^<]*)<\/title>/i)?.[1] || '').trim();
  const label = (prev?.name || title || 'App').slice(0, 32);
  let id = prev?.id || slug(label);
  if (!prev) for (let n = 2; existsSync(join(DIR, id)); n++) id = `${slug(label)}-${n}`;
  const m = { id, name: label, icon: meta(page, 'agentos-icon').match(EMOJI)?.[0] || prev?.icon || '✨', description: (meta(page, 'description') || prev?.description || req).slice(0, 90),
    request: prev ? `${prev.request}\n+ ${req}`.slice(0, 2000) : req, created: prev?.created || Date.now(), updated: Date.now(), bytes: page.length, gen_ms: Date.now() - t0 };
  progress('Installing');
  const d = join(DIR, id); mkdirSync(d, { recursive: true });
  writeFileSync(join(d, 'index.html.tmp'), page); renameSync(join(d, 'index.html.tmp'), join(d, 'index.html'));
  writeFileSync(join(d, 'manifest.json'), JSON.stringify(m, null, 1));
  log(`apps: ${prev ? 'updated' : 'built'} ${id} (${page.length} B, ${Date.now() - t0} ms)`);
  io?.publish({ type: 'apps.changed', why: prev ? 'updated' : 'built', id, app: view(m) });
  return { ok: true, ...view(m), ms: Date.now() - t0, note: `${label} is on the home screen now.` };
}
