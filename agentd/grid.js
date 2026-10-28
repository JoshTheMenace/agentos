// Grid outlook (research/SMART-HOME-ENERGY.md, option A): "when should I run the dishwasher?" in one local read.
// Like a weather forecast for electricity: two public sources say when power is cheap (the utility's price card) and
// when it is clean (how much coal vs solar/wind the grid burns), and the agent picks the hour that is both.
//   - Price: Rocky Mountain Power (Utah) Electric Service Schedule No. 1, time-of-use option, sheet 1.2, filed Aug 5 2026,
//     effective Aug 10 2026, Docket 26-035-T08 (hard-coded below). Savings apply only on the TOU rate.
//   - Grid: U.S. EIA Open Data v2, balancing area PACE (PacifiCorp East = Utah): hourly demand + day-ahead forecast, and the
//     hourly fuel mix (~13 h behind). Carbon = our estimate from that mix (coal 1000, gas 430, oil 800 g/kWh, rest 0;
//     generation only, no imports), forecast as "same hour, latest day". Read-only; nothing is sent but the API key.
// Key: ~/.agentos/eia.key (mode 600, never logged or committed), else EIA's DEMO_KEY (few requests an hour). Cached in
// memory + ~/.agentos/grid-cache.json, refreshed at most hourly (the data moves hourly); if EIA fails: the tariff alone.
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { log } from './config.js';

const HOME = process.env.AGENTOS_HOME || join(homedir(), '.agentos'), CACHE = join(HOME, 'grid-cache.json'), H = 36e5;
const TARIFF = 'Rocky Mountain Power Utah Schedule 1 time-of-use, effective Aug 10 2026 (rockymountainpower.net, 001_Residential_Service.pdf)';
const PRICE = (d) => d.getMonth() >= 5 && d.getMonth() <= 8 ? { on: 33.6173, off: 7.4705 } : { on: 29.7498, off: 6.6111 }; // ¢/kWh: Jun–Sep, Oct–May
const G_PER_KWH = { COL: 1000, NG: 430, OIL: 800 };
const EIA = 'https://api.eia.gov/v2/electricity/rto';

// On-peak: 6–10 PM Mon–Fri except holidays (observed Fri before / Mon after when they fall on a weekend).
const nth = (y, m, wd, n) => { const d = new Date(y, m, 1); d.setDate(1 + (wd - d.getDay() + 7) % 7 + 7 * (n - 1)); return d; };
const lastMon = (y, m) => { const d = new Date(y, m + 1, 0); d.setDate(d.getDate() - (d.getDay() + 6) % 7); return d; };
const observed = (d) => (d.getDay() === 6 && d.setDate(d.getDate() - 1), d.getDay() === 0 && d.setDate(d.getDate() + 1), d);
const holidays = (y) => new Set([new Date(y, 0, 1), new Date(y, 6, 4), new Date(y, 6, 24), new Date(y, 11, 25)].map(observed)
  .concat([nth(y, 1, 1, 3), lastMon(y, 4), nth(y, 8, 1, 1), nth(y, 10, 4, 4)]).map((d) => d.toDateString()));
const onPeak = (d) => d.getDay() % 6 !== 0 && d.getHours() >= 18 && d.getHours() < 22 && !holidays(d.getFullYear()).has(d.toDateString());

const keyFile = join(HOME, 'eia.key');
const key = () => { try { return readFileSync(keyFile, 'utf8').trim() || 'DEMO_KEY'; } catch { return 'DEMO_KEY'; } };
const utc = (p) => Date.parse(`${p}:00:00Z`); // EIA hourly period "2026-10-29T02" (UTC)
async function eia(path, facets, start) {
  const q = `api_key=${key()}&frequency=hourly&data[0]=value&facets[respondent][]=PACE${facets}&start=${new Date(start).toISOString().slice(0, 13)}&sort[0][column]=period&sort[0][direction]=asc&length=5000`;
  const r = await fetch(`${EIA}/${path}/data/?${q}`, { signal: AbortSignal.timeout(8000) });
  if (!r.ok) throw new Error(`EIA ${path} HTTP ${r.status}`); // never the URL: it holds the key
  return (await r.json()).response?.data || [];
}

let cache = null; // {at, demand:{period:MW}, forecast:{period:MW}, mix:{period:{fuel:MW}}}
try { cache = existsSync(CACHE) ? JSON.parse(readFileSync(CACHE, 'utf8')) : null; } catch {}
let pending = null;
async function grid() {
  if (cache && Date.now() - cache.at < H) return cache;
  return (pending ??= (async () => {
    const [rd, fd] = await Promise.all([eia('region-data', '&facets[type][]=D&facets[type][]=DF', Date.now() - 30 * H), eia('fuel-type-data', '', Date.now() - 40 * H)]);
    const c = { at: Date.now(), demand: {}, forecast: {}, mix: {} };
    for (const x of rd) (x.type === 'DF' ? c.forecast : c.demand)[x.period] = +x.value;
    for (const x of fd) if (+x.value > 0) (c.mix[x.period] ??= {})[x.fueltype] = +x.value;
    if (!Object.keys(c.mix).length) throw new Error('EIA returned no fuel mix');
    writeFileSync(CACHE, JSON.stringify(cache = c));
    return c;
  })().catch((e) => { log(`grid: ${e.message}`); return cache; }).finally(() => { pending = null; }));
}
setTimeout(() => grid(), 5000).unref?.(); // warm (no request if the saved copy is under an hour old)

const g = (mix) => { const tot = Object.values(mix).reduce((a, b) => a + b, 0); return Math.round(Object.entries(mix).reduce((a, [f, v]) => a + (G_PER_KWH[f] || 0) * v, 0) / tot); };
const clock = (t) => new Date(t).toLocaleTimeString('en-US', { hour: 'numeric' }).replace(':00', '');
const day = (t, now) => { const d = Math.round((new Date(t).setHours(0, 0, 0, 0) - new Date(now).setHours(0, 0, 0, 0)) / 864e5);
  return d === 0 ? 'today' : d === 1 ? 'tomorrow' : new Date(t).toLocaleDateString('en-US', { weekday: 'short' }); };
const at = (t, now) => `${day(t, now)} ${clock(t)}`;
const local = (t) => { const d = new Date(t), p = (n) => String(n).padStart(2, '0'); return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:00`; };

// The next 36 hours, hour by hour, then the windows the agent needs.
export function outlook(c, now = Date.now()) {
  const byHour = {}; // local hour -> estimated g/kWh from the latest day of fuel mix
  const mixes = Object.entries(c?.mix || {}).sort(([a], [b]) => utc(a) - utc(b));
  for (const [p, mix] of mixes) byHour[new Date(utc(p)).getHours()] = g(mix);
  const t0 = new Date(now).setMinutes(0, 0, 0), hours = [];
  for (let t = t0; t < t0 + 36 * H; t += H) {
    const d = new Date(t), pk = onPeak(d), period = new Date(t).toISOString().slice(0, 13);
    hours.push({ t, peak: pk, cents: PRICE(d)[pk ? 'on' : 'off'], co2: byHour[d.getHours()], mw: c?.forecast?.[period] ?? c?.demand?.[period] });
  }
  const block = (i, ok, max = 6) => { let j = i; while (j + 1 < hours.length && j - i + 1 < max && ok(hours[j + 1])) j++;
    const a = hours[i], w = hours.slice(i, j + 1).map((h) => h.co2).filter((x) => x != null);
    return { from: at(a.t, now), to: clock(hours[j].t + H), remind_when: local(a.t), price_cents: +a.cents.toFixed(1), est_g_co2_kwh: w.length ? Math.round(w.reduce((x, y) => x + y) / w.length) : null }; }; // window average
  const off = hours.filter((h) => !h.peak && h.co2 != null), min = Math.min(...off.map((h) => h.co2));
  const ci = off.length ? hours.indexOf(off.find((h) => h.co2 <= min * 1.1)) : -1;
  const clean = (h) => !h.peak && h.co2 <= min * 1.25; let ci0 = ci; while (ci0 > 0 && ci - ci0 < 3 && clean(hours[ci0 - 1])) ci0--; // grow back up to 3 h
  const cleanest = ci < 0 ? null : block(ci0, clean);
  const pi = hours.findIndex((h) => h.peak), peak = pi < 0 ? null : block(pi, (h) => h.peak, 4);
  const cheap = hours.findIndex((h) => !h.peak), cheapest = block(cheap, (h) => !h.peak, 12);
  const oi = pi < 0 ? -1 : hours.findIndex((h, i) => i > pi && !h.peak), overnight = oi < 0 ? cheapest : block(oi, (h) => !h.peak, 10); // long loads (EV): after the evening peak
  const n = hours[0], next = hours.find((h) => h.peak !== n.peak), dirtiest = Math.max(...hours.map((h) => h.co2 ?? 0)) || null;
  const p = PRICE(new Date(now)), ratio = +(p.on / p.off).toFixed(1), latest = mixes.at(-1)?.[0];
  const dem = hours.filter((h) => h.mw), top = dem.length ? dem.reduce((a, b) => (b.mw > a.mw ? b : a)) : null;
  const good = !n.peak && (n.co2 == null || n.co2 <= min + 0.25 * (dirtiest - min)); // in the cleanest quarter of the day's range
  const reason = n.peak
    ? `Peak price until ${clock(next?.t ?? n.t + H)} (${p.on.toFixed(1)}¢ vs ${p.off.toFixed(1)}¢/kWh off-peak, ${ratio}×)${cleanest ? `; cleanest off-peak window ${cleanest.from}–${cleanest.to}, ~${cleanest.est_g_co2_kwh} vs ~${n.co2} g CO₂/kWh now (estimated)` : ''}.`
    : good ? `Off-peak now (${p.off.toFixed(1)}¢/kWh, ${ratio}× cheaper than the 6–10 PM peak)${n.co2 != null ? ` and the grid is near its cleanest (~${n.co2} g CO₂/kWh, estimated; cleanest ${cleanest?.from}–${cleanest?.to})` : ''}.`
    : `Off-peak now (${p.off.toFixed(1)}¢/kWh) but dirtier than later: cleanest window ${cleanest.from}–${cleanest.to}, ~${cleanest.est_g_co2_kwh} vs ~${n.co2} g CO₂/kWh now (estimated), same price.`;
  return { ok: true, now: { period: n.peak ? 'on-peak' : 'off-peak', price_cents: +n.cents.toFixed(1), until: clock(next?.t ?? t0 + 36 * H), est_g_co2_kwh: n.co2, good_time: good },
    cleanest, cheapest, overnight, next_peak: peak, peak_to_offpeak_ratio: ratio, dirtiest_est_g_co2_kwh: dirtiest,
    demand_peak: top && { mw: Math.round(top.mw), at: at(top.t, now) }, reason,
    data_age: latest ? `fuel mix through ${at(utc(latest), now)} (${Math.round((now - utc(latest)) / H)} h old; used as the pattern for the same hours ahead)` : 'EIA unavailable: price only, no carbon estimate',
    notes: 'Savings apply on the time-of-use rate (standard Schedule 1 is tiered, not timed). Carbon is our estimate from EIA\'s generation mix for PacifiCorp East (no imports), not a live measurement.',
    sources: [TARIFF, 'U.S. EIA Open Data v2, electricity/rto region-data + fuel-type-data, respondent PACE'] };
}

export const tool = { name: 'grid_outlook', tier: 'read', local: true, schema: { type: 'object', properties: {}, additionalProperties: false },
  description: 'Utah power outlook for the next 36 h (Rocky Mountain Power time-of-use prices + U.S. EIA grid data for PacifiCorp East): the current period (on/off-peak, until when), '
    + 'the cleanest off-peak window, the cheapest window from now, the overnight off-peak window (after the evening peak; for long loads like a car), the next peak, the peak/off-peak price ratio, estimated g CO₂/kWh, data age, a one-line reason. Each window has remind_when for schedule_task. '
    + 'Use for "when should I run the dishwasher / laundry / charge my car?" and "is now a good time to ...?". Read-only.' };
export const call = async () => outlook(await grid());
