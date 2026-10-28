#!/usr/bin/env node
// Memory v2 eval: the same ~85 questions (questions2.js) over the same world (world2.js) for several approaches, no model
// in the loop (the block the brain would hand the model is graded by regex). Metrics per approach:
//   accuracy per category, wrong-merge rate (DIFF pairs merged / all merged pairs; must be 0), missed SAME pairs,
//   trivia leaks (facts that should not exist), tokens per lookup, ms per lookup, freshness (a new message → answer, ms).
//   node memory/eval/v2.js [--approach A,B,C,D] [--llm] [-v]
// A = v1 store+brain (name merges at ingest, resolve.js links)      B = v2 identity + LLM-extracted facts only (extract-llm.js, cached)
// C = v2: evidence-scored identity + rule facts + "about you"        D = v2 identity + rule facts + LLM facts
import * as S from '../store.js';
import * as M from '../index.js';
import * as F from '../facts.js';
import * as I from '../identity.js';
import { groups as groups1, relink as relink1, MERGE } from '../resolve.js';
import * as L from '../extract-llm.js';
import { build, SAME, DIFF, MAYBE_OK } from './world2.js';
import { QUESTIONS2 } from './questions2.js';

const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d; };
const verbose = process.argv.includes('-v'), useLLM = process.argv.includes('--llm');
const APPROACHES = arg('approach', useLLM ? 'A,B,C,D' : 'A,C').split(',');
const CACHE = new URL('./results/llm-facts.json', import.meta.url).pathname;
const DESC = { A: 'v1 store+brain', B: 'v2 identity + LLM facts', C: 'v2 identity + rule facts', D: 'v2 identity + rules + LLM facts' };

function setup(ap) {
  S.flags.v2 = ap !== 'A';
  const db = S.open(':memory:');
  build(db);
  F.ensure(db);
  if (ap === 'C' || ap === 'D') F.sync(db);
  let llm = null;
  if (ap === 'B' || ap === 'D') llm = L.run(db, { cache: CACHE, log: verbose ? console.log : () => {} });
  if (S.flags.v2) I.relink(db);
  return { db, llm };
}

const cardId = (db, name) => db.prepare(`SELECT id FROM entity WHERE type='person' AND name=?`).get(name)?.id
  ?? db.prepare(`SELECT id FROM entity WHERE type='person' AND name=?`).get(name.replace(/ \(.*\)$/, ''))?.id; // v1 has no "(Discord)" cards: same card
function pairStatus(db, ap, a, b) {
  const [x, y] = [cardId(db, a), cardId(db, b)];
  if ((x && !y) || (y && !x)) return db.prepare(`SELECT 1 FROM alias WHERE kind='name' AND value=? AND entity_id=?`).get(x ? b : a, x || y) ? 'same' : 'missing'; // absorbed at ingest (same phone)
  if (!x || !y) return 'missing';
  if (x === y) return 'same';
  if (ap === 'A') { relink1(db); const g = groups1(db); if (g.canon(x) === g.canon(y)) return 'same'; const l = db.prepare('SELECT conf FROM entity_link WHERE (id=? AND canon=?) OR (id=? AND canon=?)').get(x, y, y, x); return l ? 'maybe' : 'none'; }
  I.relink(db); const g = I.groups(db);
  return g.canon(x) === g.canon(y) ? 'same' : g.softCanon(x) === g.softCanon(y) ? 'maybe' : 'none';
}

function identityMetrics(db, ap) {
  const rows = [];
  for (const [a, b] of SAME) rows.push({ a, b, want: 'same', got: pairStatus(db, ap, a, b) });
  for (const [a, b] of MAYBE_OK) rows.push({ a, b, want: 'maybe', got: pairStatus(db, ap, a, b) });
  for (const [a, b] of DIFF) rows.push({ a, b, want: 'none', got: pairStatus(db, ap, a, b) });
  const merged = rows.filter((r) => r.got === 'same'), wrong = merged.filter((r) => r.want === 'none');
  const missed = rows.filter((r) => r.want === 'same' && r.got !== 'same'), overMaybe = rows.filter((r) => r.want === 'maybe' && r.got === 'same');
  if (verbose) for (const r of rows) if (r.got !== r.want) console.log(`  identity ${r.want.padEnd(5)} got ${r.got.padEnd(5)} ${r.a} <> ${r.b}`);
  return { wrong_merges: `${wrong.length + overMaybe.length}/${merged.length}`, wrong_merge_rate: +(((wrong.length + overMaybe.length) / Math.max(1, merged.length)).toFixed(2)), missed_same: `${missed.length}/${SAME.length}`,
    pending_cards: S.flags.v2 ? I.pending(db, 20).length : 0, rows };
}

function run(ap) {
  const { db, llm } = setup(ap);
  const ctx = { db, cloud: true };
  const per = {}, rows = []; let chars = 0, ms = 0;
  for (const q of QUESTIONS2) {
    const t0 = performance.now();
    const lines = M.contextLines(q.q, ctx);
    ms += performance.now() - t0;
    const text = lines.map((l) => l.text).join('\n'), factText = lines.filter((l) => l.fact).map((l) => l.text).join('\n');
    chars += text.length;
    const ok = q.cat === 'trivia' ? !(q.leak && q.leak.test(factText)) : q.answer.test(text) && !(q.not && q.not.test(text));
    (per[q.cat] ??= [0, 0])[1]++; if (ok) per[q.cat][0]++;
    rows.push({ id: q.id, cat: q.cat, ok });
    if (verbose && !ok) console.log(`  ✗ ${q.id.padEnd(16)} ${text.replace(/\n/g, ' | ').slice(0, 220)}`);
  }
  // freshness: a new message arrives, then the question is asked
  const t1 = performance.now();
  M.noteMessage({ pkg: 'com.whatsapp', app: 'WhatsApp', conversation: 'Mom', sender: 'Mom', text: "update: we moved again, we're in Ogden now! 9 Pine Ct", when: Date.now(), dir: 'in' }, ctx);
  if (S.flags.v2) F.sync(db);
  const fresh = M.contextLines('where does Mom live now?', ctx).map((l) => l.text).join('\n');
  const freshMs = performance.now() - t1, freshOk = /ogden/i.test(fresh) && !/lives in: Boise \(since/.test(fresh);
  const id = identityMetrics(db, ap);
  const nFacts = db.prepare(`SELECT count(*) n FROM fact WHERE status='active'`).get().n;
  const leaks = db.prepare(`SELECT obj FROM fact`).all().filter((f) => /uber|prius|lyft|verification|482913|doordash|omw|\blol\b/i.test(f.obj)).length;
  const total = rows.filter((r) => r.ok).length;
  const out = { approach: ap, what: DESC[ap], accuracy: `${total}/${rows.length}`, ...Object.fromEntries(Object.entries(per).map(([k, [a, b]]) => [k, `${a}/${b}`])),
    wrong_merges: id.wrong_merges, missed_same: id.missed_same, pending_cards: id.pending_cards, facts: nFacts, trivia_facts: leaks,
    tokens_per_lookup: Math.round(chars / 4 / rows.length), ms_per_lookup: +(ms / rows.length).toFixed(2), fresh: freshOk ? `ok ${freshMs.toFixed(0)} ms` : `MISS ${freshMs.toFixed(0)} ms`,
    ...(llm && { llm: { calls: llm.calls, cached: llm.cached, tokens: llm.tokens, facts: llm.facts, bad: llm.bad } }) };
  db.close();
  return out;
}

const results = APPROACHES.map(run);
for (const r of results) console.log(JSON.stringify(r));
