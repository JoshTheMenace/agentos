// Samsung's personal-data CSV export, never a live sensor/API connector. See SAMSUNG-HEALTH.md.
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import * as S from './store.js';

const SOURCE = 'samsung_health', DAY = 864e5;
const round = (v) => v == null ? null : Math.round(v * 10) / 10;
const mean = (xs) => xs.length ? round(xs.reduce((a, b) => a + b, 0) / xs.length) : null;
const number = (v) => v == null || String(v).trim() === '' || !Number.isFinite(+v) ? null : +v;
const positive = (v) => number(v) > 0 ? +v : null;
const bounded = (v, max) => number(v) !== null && +v >= 0 && +v <= max ? +v : null;
const date = (ms) => new Date(ms).toISOString().slice(0, 10);
const hash = (s) => createHash('sha256').update(s).digest('hex');
export function timestamp(v) {
  if (number(v) !== null) return +v;
  if (!/^\d{4}-\d\d-\d\d[ T]\d\d:\d\d/.test(v || '')) return NaN;
  return Date.parse(v.replace(' ', 'T') + (/(Z|[+-]\d\d:\d\d)$/.test(v) ? '' : 'Z'));
}
export function offset(v) {
  if (v == null || v === '') return 0;
  if (number(v) !== null && Math.abs(+v) <= DAY) return +v;
  const m = String(v).match(/^(?:UTC|GMT)?([+-])(\d\d):?(\d\d)$/);
  if (!m || +m[2] > 23 || +m[3] > 59) throw new Error('Invalid Samsung time_offset');
  return (m[1] === '-' ? -1 : 1) * (+m[2] * 60 + +m[3]) * 60000;
}
// RFC 4180 quoting, including embedded newlines and trailing commas. Only one CSV is held at a time.
export function* csv(text) {
  let row = [], value = '', quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (c === '"') { if (quoted && text[i + 1] === '"') { value += '"'; i++; } else quoted = !quoted; }
    else if (!quoted && (c === ',' || c === '\n')) {
      row.push(value.replace(/\r$/, '')); value = '';
      if (c === '\n') { yield row; row = []; }
    } else value += c;
  }
  if (quoted) throw new Error('Unclosed CSV quote');
  if (value || row.length) yield [...row, value.replace(/\r$/, '')];
}
function* files(folder) {
  for (const e of readdirSync(folder, { withFileTypes: true })) {
    if (e.isDirectory() && e.name !== 'jsons') yield* files(join(folder, e.name));
    else if (e.isFile() && /^com\.samsung\..+\.csv$/i.test(e.name)) yield join(folder, e.name);
  }
}
const TYPES = {
  'com.samsung.shealth.sleep': 'sleep', 'com.samsung.health.sleep': 'sleep',
  'com.samsung.health.sleep_stage': 'stage', 'com.samsung.shealth.sleep_stage': 'stage',
  'com.samsung.shealth.tracker.heart_rate': 'hr', 'com.samsung.health.hrv': 'hrv', 'com.samsung.shealth.stress': 'stress',
  'com.samsung.health.heart_rate': 'hr', 'com.samsung.shealth.heart_rate': 'hr',
  'com.samsung.shealth.tracker.pedometer_day_summary': 'steps', 'com.samsung.shealth.step_daily_trend': 'steps',
  'com.samsung.health.exercise': 'exercise', 'com.samsung.shealth.exercise': 'exercise',
};
export function normalize(folder) {
  const sleep = new Map(), exercise = new Map(), stages = new Map(), hr = new Map(), steps = new Map(), seen = new Set();
  const hrv = new Map(), stress = new Map();
  const sidecars = existsSync(join(folder, 'jsons.zip')) ? JSON.parse(execFileSync('python3', [fileURLToPath(new URL('./samsung-sidecars.py', import.meta.url)), folder], { maxBuffer: 64 << 20, stdio: ['ignore', 'pipe', 'pipe'] })) : {};
  const detail = (ref) => sidecars[String(ref || '').split('/').at(-1)] || [];
  let rows = 0, skipped = 0, supported = 0, ignored_files = 0;
  for (const file of files(folder)) {
    const type = Object.keys(TYPES).find((t) => new RegExp(`^${t.replaceAll('.', '\\.')}\\.\\d+\\.csv$`).test(file.split('/').at(-1)));
    if (!type) { ignored_files++; continue; }
    supported++;
    const records = csv(readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
    records.next(); // metadata, not the header
    const header = records.next().value?.map((k) => k.trim().split('.').at(-1));
    if (!header?.includes('start_time') && !header?.includes('day_time')) throw new Error(`Missing time column for ${type}`);
    for (const cells of records) {
      if (cells.every((v) => !v)) continue;
      rows++;
      const r = Object.fromEntries(header.map((k, i) => [k, cells[i] ?? '']));
      const start = timestamp(r.start_time), end = timestamp(r.end_time), off = offset(r.time_offset);
      const kind = TYPES[type], id = hash(`${kind}:${r.datauuid || r.uuid || JSON.stringify(r)}`);
      if (kind === 'steps') {
        const t = timestamp(r.day_time), count = bounded(r.step_count || r.count, 1000000);
        if (!Number.isFinite(t) || count === null) { skipped++; continue; }
        // day_time encodes a calendar date at UTC midnight: DO NOT shift it by time_offset.
        const day = date(t), merged = r.source_type === '-2' || !!r.source_info;
        const old = steps.get(day), updated = timestamp(r.update_time) || 0;
        if (!old || +merged > +old.merged || merged === old.merged && (count > old.steps || count === old.steps && updated > old.updated))
          steps.set(day, { day, steps: Math.round(count), coverage: merged ? 'all_devices' : 'largest_device_total', merged, updated });
        continue;
      }
      if (!Number.isFinite(start) || (!['hr', 'hrv', 'stress'].includes(kind) && (!Number.isFinite(end) || end <= start))) { skipped++; continue; }
      if (kind === 'hr') {
        if (seen.has(id)) continue;
        seen.add(id);
        const bins = detail(r.binning_data), points = Array.isArray(bins) && bins.some((p) => positive(p.heart_rate)) ? bins : [r];
        for (const p of points) {
          const t = timestamp(p.start_time), day = date((Number.isFinite(t) ? t : start) + off);
          const avg = bounded(p.heart_rate, 300), max = bounded(p.heart_rate_max ?? p.max, 300), resting = bounded(p.resting_heart_rate, 300);
          const h = hr.get(day) || { day, sum: 0, samples: 0, max: null, resting: [] };
          if (avg > 0) { h.sum += avg; h.samples++; h.max = Math.max(h.max || 0, max || avg, avg); }
          if (resting > 0) h.resting.push(resting);
          if (h.samples || h.resting.length) hr.set(day, h);
        }
      } else if (kind === 'hrv' || kind === 'stress') {
        if (seen.has(id)) continue;
        seen.add(id);
        const bins = detail(r.binning_data), points = Array.isArray(bins) && bins.length ? bins : [r], map = kind === 'hrv' ? hrv : stress;
        for (const p of points) {
          const t = timestamp(p.start_time), day = date((Number.isFinite(t) ? t : start) + off);
          const h = map.get(day) || { day, sdnn: [], rmssd: [], scores: [], max: null };
          const sdnn = bounded(p.sdnn, 2000), rmssd = bounded(p.rmssd, 2000), score = bounded(p.score, 100);
          if (sdnn > 0) h.sdnn.push(sdnn);
          if (rmssd > 0) h.rmssd.push(rmssd);
          if (score !== null) { h.scores.push(score); h.max = Math.max(h.max || 0, bounded(p.score_max ?? p.max, 100) ?? score, score); }
          if (h.sdnn.length || h.rmssd.length || h.scores.length) map.set(day, h);
        }
      } else if (kind === 'stage') {
        const stage = { 40001: 'awake', 40002: 'light', 40003: 'deep', 40004: 'rem' }[r.stage];
        if (!stage || !r.sleep_id || seen.has(id)) continue;
        seen.add(id);
        const list = stages.get(r.sleep_id) || [];
        list.push({ start, end, stage }); stages.set(r.sleep_id, list);
      } else {
        const entry = { id, day: date((kind === 'sleep' ? end : start) + off), start, end, offset: off,
          duration_minutes: round((end - start) / 60000) };
        const updated = timestamp(r.update_time) || 0, map = kind === 'sleep' ? sleep : exercise;
        if (map.has(id) && map.get(id).updated > updated) continue;
        if (kind === 'sleep') Object.assign(entry, { sleep_id: r.datauuid || r.uuid, stages: {}, asleep_minutes: null,
          efficiency: bounded(r.efficiency, 100), score: bounded(r.sleep_score || r.score, 100) });
        else Object.assign(entry, { type: bounded(r.exercise_type, 100000), duration_minutes: round(positive(r.duration) ? +r.duration / 60000 : entry.duration_minutes),
          distance_m: bounded(r.distance, 1e7), calories_kcal: bounded(r.calorie, 1e6) });
        if (kind === 'exercise') {
          const resting = bounded(detail(r.sensing_status)?.heart_rate?.rhr, 300);
          if (resting > 0) {
            const h = hr.get(entry.day) || { day: entry.day, sum: 0, samples: 0, max: null, resting: [] };
            h.resting.push(resting); hr.set(entry.day, h);
          }
        }
        map.set(id, { ...entry, updated });
      }
    }
  }
  if (!supported) throw new Error('No supported Samsung Health CSVs found (expected datatype.TIMESTAMP.csv)');
  for (const s of sleep.values()) {
    let cursor = s.start, covered = 0;
    for (const st of (stages.get(s.sleep_id) || []).sort((a, b) => a.start - b.start)) {
      const a = Math.max(cursor, st.start, s.start), b = Math.min(st.end, s.end);
      if (b <= a) continue;
      s.stages[st.stage] = (s.stages[st.stage] || 0) + (b - a) / 60000;
      covered += b - a; cursor = b;
    }
    if (covered >= s.end - s.start - 60000) s.asleep_minutes = round((s.end - s.start) / 60000 - (s.stages.awake || 0));
    s.stages = Object.fromEntries(Object.entries(s.stages).map(([k, v]) => [k, round(v)]));
    delete s.sleep_id; delete s.updated;
  }
  return { sleep: [...sleep.values()], heart_rate: [...hr.values()].map((h) => ({ day: h.day, resting: mean(h.resting), avg: h.samples ? round(h.sum / h.samples) : null, max: h.max, samples: h.samples })),
    steps: [...steps.values()].map(({ merged, updated, ...r }) => r), exercise: [...exercise.values()].map(({ updated, ...r }) => r),
    hrv: [...hrv.values()].map((h) => ({ day: h.day, sdnn_avg: mean(h.sdnn), rmssd_avg: mean(h.rmssd), samples: Math.max(h.sdnn.length, h.rmssd.length) })),
    stress: [...stress.values()].map((h) => ({ day: h.day, avg: mean(h.scores), max: h.max, samples: h.scores.length })),
    report: { rows, skipped, ignored_files, supported_files: supported } };
}
// Called inside the ingestor transaction (or the phone receiver transaction). Re-import replaces a day/session, never adds totals twice.
export function save(db, data) {
  S.requireGrant(db, SOURCE);
  const counts = {};
  for (const metric of ['sleep', 'heart_rate', 'steps', 'exercise', 'hrv', 'stress']) {
    const cols = db.prepare(`PRAGMA table_info(health_${metric})`).all().map((c) => c.name);
    const put = db.prepare(`INSERT OR REPLACE INTO health_${metric}(${cols}) VALUES(${cols.map(() => '?')})`);
    for (const r of data[metric]) put.run(...cols.map((c) => c === 'stages' ? JSON.stringify(r[c]) : r[c] ?? null));
    counts[metric] = data[metric].length;
  }
  return { source: SOURCE, ...data.report, imported: counts };
}
export function ingest(db, { folder } = {}) { S.requireGrant(db, SOURCE); return save(db, normalize(folder)); }

function access(db, ctx) {
  S.requireGrant(db, SOURCE);
  if (!S.readable(db, ctx.cloud !== false).includes(SOURCE)) throw new Error('Samsung Health consent is local-only; use an on-device model.');
}
function dayArg(s) {
  if (typeof s !== 'string' || !/^\d{4}-\d\d-\d\d$/.test(s) || !Number.isFinite(Date.parse(s)) || date(Date.parse(s)) !== s) throw new Error('Dates must be YYYY-MM-DD');
  return s;
}
const envelope = (data) => ({ source: SOURCE, private: true, rule_of_two: { private: true, untrusted: false, outward: false },
  note: 'Imported wearable data; missing values are unknown. HR avg is the mean of exported measurements, not a continuous time-weighted mean. Resting HR is only an explicitly exported value. Sleep is grouped by local wake date. Steps are never added across devices.', ...data });
function read(db, metric, from, to) {
  const rows = db.prepare(`SELECT * FROM health_${metric} WHERE day>=? AND day<=? ORDER BY day${metric === 'sleep' || metric === 'exercise' ? ', start' : ''} LIMIT 1001`).all(from, to);
  if (rows.length > 1000) throw new Error('Too many sessions; request a shorter range');
  return rows.map(({ id, ...r }) => {
    if (r.stages) r.stages = JSON.parse(r.stages);
    // Numeric allowlist only: no export identifiers, arbitrary text, names, GPS or raw bins reach any model.
    return r;
  });
}
export function query(db, { metric, from, to }, ctx = {}) {
  access(db, ctx);
  if (!['sleep', 'heart_rate', 'steps', 'exercise', 'hrv', 'stress'].includes(metric)) throw new Error('metric must be sleep, heart_rate, steps, exercise, hrv or stress');
  dayArg(from); dayArg(to);
  if (from > to || Date.parse(to) - Date.parse(from) >= 366 * DAY) throw new Error('Use an ordered date range of at most 366 days');
  return envelope({ metric, from, to, rows: read(db, metric, from, to) });
}
export function summary(db, { days = 7 } = {}, ctx = {}) {
  access(db, ctx);
  if (!Number.isInteger(days) || days < 1 || days > 90) throw new Error('days must be an integer from 1 to 90');
  const now = ctx.now ?? Date.now(), today = new Date(now), end = date(now - today.getTimezoneOffset() * 60000);
  const from = date(Date.parse(end) - (days - 1) * DAY), priorFrom = date(Date.parse(from) - days * DAY), priorTo = date(Date.parse(from) - DAY);
  const sleep = read(db, 'sleep', from, end), previous = read(db, 'sleep', priorFrom, priorTo);
  const nights = (xs, key) => {
    const days = new Map();
    for (const r of xs) days.set(r.day, r[key] === null || days.get(r.day) === null ? null : (days.get(r.day) || 0) + r[key]);
    return [...days.values()].filter((n) => n !== null);
  };
  const avg = mean(nights(sleep, 'duration_minutes')), before = mean(nights(previous, 'duration_minutes'));
  const hr = read(db, 'heart_rate', from, end), oldHr = read(db, 'heart_rate', priorFrom, priorTo), steps = read(db, 'steps', from, end);
  const resting = mean(hr.map((r) => r.resting).filter((n) => n !== null)), oldResting = mean(oldHr.map((r) => r.resting).filter((n) => n !== null));
  return envelope({ from, to: end, sleep: { nights: nights(sleep, 'duration_minutes').length, average_session_minutes_per_day: avg,
    average_asleep_minutes: mean(nights(sleep, 'asleep_minutes')), previous_average_session_minutes: before,
    change_minutes: avg !== null && before !== null ? round(avg - before) : null,
    last_night: sleep.filter((r) => r.day === end), latest: sleep.at(-1) || null },
    heart_rate: { daily: hr, average_resting: resting, resting_change_bpm: resting !== null && oldResting !== null ? round(resting - oldResting) : null },
    steps: { daily: steps, average: mean(steps.map((r) => r.steps)) }, exercise: read(db, 'exercise', from, end), hrv: read(db, 'hrv', from, end), stress: read(db, 'stress', from, end) });
}
const obj = (properties, required = []) => ({ type: 'object', properties, required, additionalProperties: false });
export const tools = [
  { name: 'health_summary', tier: 'read', description: 'Read a ROLLING period of imported Samsung Health: sleep averages, trend vs previous period, last night, daily HR and steps. NOT for this week or last week: those are calendar weeks; use health_query instead. Private data, read-only, computed on phone. Missing data stays unknown. Use for sleep/fitness questions instead of memory_ask.',
    schema: obj({ days: { type: 'integer', minimum: 1, maximum: 90, description: 'Days through today, default 7. For a calendar week use health_query.' } }) },
  { name: 'health_query', tier: 'read', description: 'Read Samsung Health sleep sessions, daily heart_rate (explicit resting/avg/max), steps, exercise, hrv (SDNN/RMSSD), or stress by inclusive local calendar dates. For this week use Monday through today; last week is previous Monday through Sunday. Private data, computed on phone. No medical diagnosis.',
    schema: obj({ metric: { type: 'string', enum: ['sleep', 'heart_rate', 'steps', 'exercise', 'hrv', 'stress'] }, from: { type: 'string', description: 'YYYY-MM-DD inclusive' }, to: { type: 'string', description: 'YYYY-MM-DD inclusive' } }, ['metric', 'from', 'to']) },
];
