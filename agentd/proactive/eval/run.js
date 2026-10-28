#!/usr/bin/env node
// Proactive eval: same 102 threads + 15 calendar days for every approach. No phone, no messages sent.
//   node proactive/eval/run.js rules|local|cloud|hybrid|calendar [--model gpt-6-luna] [--effort low] [--out name]
// --set main|blind|both. local: LOCAL_URL (default http://127.0.0.1:8091) = llama-server with the phone's Qwen gguf. cloud/hybrid: Codex app-server.
// Scores: strict precision/recall (kind must match), card precision (any card on a thread that deserves one), who/due
// accuracy on the hits, latency for the whole "day", tokens. dev = even ids (tuned on), test = odd ids (held out).
import { mkdirSync, writeFileSync } from 'node:fs';
import { NOW, THREADS, DAYS } from './cases.js';
import { BLIND } from './blind.js';
import * as D from '../detect.js';

const [approach = 'rules', ...rest] = process.argv.slice(2);
const opt = (k, d) => { const i = rest.indexOf(`--${k}`); return i >= 0 ? rest[i + 1] : d; };
const SET = opt('set', 'main'); // main (cases.js) | blind (blind.js: written by another agent that never saw detect.js) | both
const threads = (SET === 'blind' ? BLIND : SET === 'both' ? [...THREADS, ...BLIND] : THREADS).map((t) => ({ ...t, msgs: t.msgs.map(([from, text, ago]) => ({ from, text, when: NOW - ago * 36e5 })) }));
const usage = { in: 0, cached: 0, out: 0, reasoning: 0, calls: 0 };

async function codexBatch(list) {
  const { Codex } = await import('../../providers/codex.js');
  const codex = new Codex(), signal = AbortSignal.timeout(180_000);
  const r = await codex.quick('proactive', { model: opt('model', 'gpt-6-luna'), effort: opt('effort', 'low'), prompt: D.CLOUD_PROMPT, schema: D.CLOUD_SCHEMA, rotate: 1 }, D.cloudInput(list, NOW), signal);
  for (const c of codex.lastUsage?.calls || []) { usage.in += c.in; usage.cached += c.cached; usage.out += c.out; usage.reasoning += c.reasoning || 0; usage.calls++; }
  codex.stop?.(); setTimeout(() => process.exit(0), 500).unref?.();
  return new Map(r.items.map((x) => [x.id, x]));
}
async function local(t) {
  const res = await fetch(`${process.env.LOCAL_URL || 'http://127.0.0.1:8091'}/v1/chat/completions`, { method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ messages: [{ role: 'system', content: D.LOCAL_PROMPT }, { role: 'user', content: D.localInput(t, NOW) }], temperature: 0, max_tokens: 20,
      chat_template_kwargs: { enable_thinking: false }, response_format: { type: 'json_schema', json_schema: { name: 'k', schema: D.LOCAL_SCHEMA } } }) });
  const j = await res.json(); usage.in += j.usage?.prompt_tokens || 0; usage.out += j.usage?.completion_tokens || 0; usage.calls++;
  return JSON.parse(j.choices[0].message.content).kind;
}
const fromCloud = (t, x) => D.fromModel(t, x); // cloud: bots (isBot) never leave the phone

async function predict() {
  if (approach === 'rules') return threads.map((t) => D.rules(t, NOW));
  if (approach === 'local') { const out = []; for (const t of threads) out.push(D.prefilter(t) ? D.gate(t, D.fill(t, await local(t)), NOW) : { kind: 'none' }); return out; }
  if (approach === 'local-raw') { const out = []; for (const t of threads) out.push(D.fill(t, await local(t))); return out; }
  if (approach === 'cloud-raw' || approach === 'cloud') { const m = await codexBatch(approach === 'cloud' ? threads.filter((t) => !D.isBot(t)) : threads); return threads.map((t) => approach === 'cloud' ? D.gate(t, fromCloud(t, m.get(t.id)), NOW) : fromCloud(t, m.get(t.id))); }
  if (approach === 'hybrid') { const c = threads.filter((t) => D.prefilter(t)), m = await codexBatch(c); return threads.map((t) => D.gate(t, fromCloud(t, m.get(t.id)), NOW)); }
  throw new Error(`unknown approach ${approach}`);
}

const norm = (s) => String(s || '').toLowerCase().replace(/[^a-z]/g, '');
function score(rows) {
  const s = { n: rows.length, tp: 0, fp: 0, fn: 0, cardTp: 0, cardFp: 0, who: 0, dueN: 0, due: 0, fps: [], fns: [] };
  for (const { t, p } of rows) {
    const g = t.gold, act = (k) => k && k !== 'none';
    if (act(p.kind)) { if (p.kind === g.kind) s.tp++; else { s.fp++; s.fps.push(`${t.id}:${p.kind}≠${g.kind}`); } act(g.kind) ? s.cardTp++ : s.cardFp++; }
    if (act(g.kind) && p.kind !== g.kind) { s.fn++; if (!act(p.kind)) s.fns.push(`${t.id}:${g.kind}`); }
    if (act(g.kind) && p.kind === g.kind) { if (norm(p.who) === norm(g.who)) s.who++; if (g.due) { s.dueN++; if (p.due === g.due) s.due++; } }
  }
  const P = s.tp / (s.tp + s.fp || 1), R = s.tp / (s.tp + s.fn || 1);
  return { ...s, precision: +P.toFixed(3), recall: +R.toFixed(3), f1: +(2 * P * R / (P + R || 1)).toFixed(3), cardPrecision: +(s.cardTp / (s.cardTp + s.cardFp || 1)).toFixed(3), whoAcc: +(s.who / (s.tp || 1)).toFixed(3), dueAcc: +(s.due / (s.dueN || 1)).toFixed(3) };
}

if (approach === 'calendar') {
  let tp = 0, fp = 0, fn = 0; const miss = [];
  for (const d of DAYS) {
    const c = D.calendar(d.events, d.now), got = { leave: c.leave?.title ?? null, conflict: c.conflict?.map((e) => e.title) ?? null, prep: c.prep?.title ?? null };
    for (const k of ['leave', 'conflict', 'prep']) {
      const a = JSON.stringify(got[k]), b = JSON.stringify(d.gold[k]);
      if (got[k] && a === b) tp++; else { if (got[k]) fp++; if (d.gold[k]) fn++; if (a !== b) miss.push(`${d.id}.${k}: got ${a} want ${b}`); }
    }
  }
  console.log(JSON.stringify({ approach, days: DAYS.length, precision: +(tp / (tp + fp || 1)).toFixed(3), recall: +(tp / (tp + fn || 1)).toFixed(3), tp, fp, fn, miss }, null, 1));
  process.exit(0);
}

const t0 = Date.now(), preds = await predict(), ms = Date.now() - t0;
const rows = threads.map((t, i) => ({ t, p: preds[i] }));
const main = rows.filter((r) => r.t.id[0] !== 'b'), dev = score(main.filter((r) => +r.t.id.slice(-1) % 2 === 0)), test = score(main.filter((r) => +r.t.id.slice(-1) % 2 === 1)), blind = score(rows.filter((r) => r.t.id[0] === 'b')), all = score(rows);
const brief = (s) => ({ precision: s.precision, recall: s.recall, f1: s.f1, cardPrecision: s.cardPrecision, whoAcc: s.whoAcc, dueAcc: s.dueAcc, tp: s.tp, fp: s.fp, fn: s.fn });
const out = { approach, set: SET, model: approach.includes('local') ? 'qwen3.5-0.8b' : approach === 'rules' ? null : opt('model', 'gpt-6-luna'), effort: opt('effort', 'low'), ms, candidates: threads.filter((t) => D.prefilter(t)).length, usage,
  dev: brief(dev), test: brief(test), blind: brief(blind), all: brief(all), fps: all.fps, fns: all.fns };
console.log(JSON.stringify(out, null, 1));
mkdirSync(new URL('./results/', import.meta.url), { recursive: true });
writeFileSync(new URL(`./results/${opt('out', `${approach}-${SET}`)}.json`, import.meta.url), JSON.stringify({ ...out, preds: rows.map(({ t, p }) => ({ id: t.id, gold: t.gold, ...p })) }, null, 1));
process.exit(0);
