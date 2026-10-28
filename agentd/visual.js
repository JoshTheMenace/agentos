// Visual answers: the `show` tool. The model attaches ONE declarative chart spec to its answer (no HTML, no code), agentd
// checks and caps it, and every UI draws it itself (web/viz.js). Analogy: the model hands the launcher a recipe card,
// not a cooked dish: numbers, labels and a type; the launcher cooks it in the A2 look.
const S = (max) => ({ type: 'string', maxLength: max });
const num = { type: ['number', 'null'] };
const obj = (properties, required = []) => ({ type: 'object', properties, required, additionalProperties: false });
const arr = (items, max) => ({ type: 'array', items, maxItems: max });
const CAP = { labels: 31, series: 6, stats: 4, rows: 12, spans: 12 };
const TYPES = ['bar', 'line', 'stacked_bar', 'stats', 'list', 'timeline'];

export const tool = { name: 'show', tier: 'read', local: true,
  description: 'Show the user a chart or visual with your answer (drawn by the phone; you send only data). Use it for any data question: sleep, steps, heart rate, '
    + 'calendar load, power/grid windows, counts over days. Call it once, then write one or two sentences; the visual carries the numbers, so don\'t list them in text. '
    + 'Types: bar (one series per label, e.g. steps per day), line (trend), stacked_bar (series stacked per label, e.g. sleep stages per night: series deep/light/rem/awake in minutes), '
    + 'stats (2-4 big numbers only), list (label/value rows), timeline (today\'s windows: spans with from/to like "3 PM" or "15:00", kind good/peak/neutral). '
    + 'stats can be added to any chart (shown above it). unit is a short word for the values ("min", "h", "steps", "bpm", "¢/kWh"); for min the phone writes 7h 20m itself. '
    + 'highlight names one label to call out. Labels are short ("Mon", "Oct 22").',
  schema: obj({
    type: { type: 'string', enum: TYPES },
    title: S(40), unit: S(10), caption: { ...S(120), description: 'one short line under the visual, optional' },
    labels: arr(S(16), CAP.labels), series: arr(obj({ name: S(16), values: arr(num, CAP.labels) }, ['name', 'values']), CAP.series),
    stats: arr(obj({ label: S(24), value: { type: ['string', 'number'] }, unit: S(10) }, ['label', 'value']), CAP.stats),
    rows: arr(obj({ label: S(32), value: S(24), detail: S(40) }, ['label', 'value']), CAP.rows),
    spans: arr(obj({ from: S(16), to: S(16), label: S(24), kind: { type: 'string', enum: ['good', 'peak', 'neutral'] } }, ['from', 'to']), CAP.spans),
    highlight: S(16),
  }, ['type']) };

const str = (v, n) => (v == null ? undefined : String(v).slice(0, n).trim() || undefined);
const fin = (v) => (v == null || v === '' ? null : Number.isFinite(+v) ? Math.round(+v * 100) / 100 : null);
const list = (v, n) => (Array.isArray(v) ? v.slice(0, n) : []);

// "3 PM", "15:00", "3:30pm", "tomorrow 1 AM" -> minutes from today's midnight (tomorrow = +1440). null if unreadable.
export function clock(s) {
  const m = String(s || '').toLowerCase().match(/^\s*(tomorrow|today)?\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\s*$/);
  if (!m) return /^\s*now\s*$/i.test(s) ? now() : null;
  let h = +m[2]; const mm = +(m[3] || 0);
  if (m[4] === 'pm' && h < 12) h += 12; if (m[4] === 'am' && h === 12) h = 0;
  if (h > 24 || mm > 59) return null;
  return h * 60 + mm + (m[1] === 'tomorrow' ? 1440 : 0);
}
const now = () => { const d = new Date(); return d.getHours() * 60 + d.getMinutes(); };

// Clean copy or {error}: unknown fields dropped, arrays capped, numbers finite, every series as long as labels.
export function validate(a = {}) {
  const type = TYPES.includes(a.type) ? a.type : null;
  if (!type) return { error: `type must be one of ${TYPES.join(', ')}` };
  const v = { type, title: str(a.title, 40), unit: str(a.unit, 10), caption: str(a.caption, 120), highlight: str(a.highlight, 16) };
  v.stats = list(a.stats, CAP.stats).map((s) => ({ label: str(s?.label, 24) || '', value: typeof s?.value === 'number' ? fin(s.value) : str(s?.value, 16) || '', unit: str(s?.unit, 10) })).filter((s) => s.label && s.value !== null && s.value !== '');
  if (['bar', 'line', 'stacked_bar'].includes(type)) {
    v.labels = list(a.labels, CAP.labels).map((l) => str(l, 16) || '');
    v.series = list(a.series, CAP.series).map((s) => ({ name: str(s?.name, 16) || '', values: list(s?.values, CAP.labels).map(fin) }));
    if (!v.labels.length || !v.series.length) return { error: `${type} needs labels and at least one series` };
    for (const s of v.series) s.values = v.labels.map((_, i) => s.values[i] ?? null);
    if (type !== 'stacked_bar' && !v.series.some((s) => s.values.some((x) => x !== null))) return { error: 'no values' };
  } else if (type === 'list') {
    v.rows = list(a.rows, CAP.rows).map((r) => ({ label: str(r?.label, 32) || '', value: str(r?.value, 24) || '', detail: str(r?.detail, 40) })).filter((r) => r.label);
    if (!v.rows.length) return { error: 'list needs rows' };
  } else if (type === 'timeline') {
    v.spans = list(a.spans, CAP.spans).map((s) => ({ from: clock(s?.from), to: clock(s?.to), label: str(s?.label, 24), kind: ['good', 'peak', 'neutral'].includes(s?.kind) ? s.kind : 'neutral' }))
      .filter((s) => s.from !== null && s.to !== null && s.to > s.from && s.to - s.from <= 1440);
    if (!v.spans.length) return { error: 'timeline needs spans with readable from/to times ("3 PM", "15:00", "tomorrow 1 AM")' };
    v.now = now();
  } else if (!v.stats.length) return { error: 'stats needs 2-4 {label, value} items' };
  return v;
}

export async function call(args, ctx) {
  const spec = validate(args);
  if (spec.error) return { ok: false, error: spec.error };
  ctx.emit({ type: 'visual', spec });
  return { ok: true, shown: spec.type, note: 'The user sees it now. Reply in one or two sentences; don\'t repeat the numbers as a list.' };
}
