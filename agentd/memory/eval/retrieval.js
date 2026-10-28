#!/usr/bin/env node
// Retrieval eval (no model): for each question, does the right evidence land in the top k, and is the right person on top?
//   node memory/eval/retrieval.js [--mode baseline|brain|both] [--k 5] [-v]
// baseline = memory_search with the user's words (what the model got before); brain = memory.recall (plan + hybrid rank).
import * as S from '../store.js';
import * as M from '../index.js';
import { build } from './world.js';
import { HELDOUT, QUESTIONS } from './questions.js';

const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d; };
const SET = arg('set') === 'heldout' ? HELDOUT : QUESTIONS, K = Number(arg('k', 5)), verbose = process.argv.includes('-v');
const db = S.open(':memory:');
build(db);
const extOf = (id) => db.prepare('SELECT ext_id FROM event WHERE id=?').get(id)?.ext_id;
const canonName = (name) => name; // the brain reports canonical names; baseline gets whatever card name it finds

async function run(mode) {
  let hit = 0, n = 0, who = 0, nWho = 0, mrr = 0, ms = 0;
  for (const q of SET) {
    const t0 = performance.now();
    let ids, top;
    if (mode === 'baseline') {
      const r = await M.call('memory_search', { q: q.q, limit: 10 }, { db, cloud: true });
      ids = (r.results || []).map((e) => e.id);
      top = r.entities?.find((e) => e.type === 'person')?.name || (r.results || [])[0]?.who?.find((w) => !/\(app\)|\(group\)|\(place\)/.test(w))?.replace(/ #\d+.*$/, '');
    } else {
      const r = M.ask(q.q, { db, cloud: true });
      ids = r.events.map((e) => e.id);
      top = r.people[0]?.name;
    }
    ms += performance.now() - t0;
    const exts = ids.slice(0, K).map(extOf), rank = ids.map(extOf).findIndex((x) => q.gold.some((g) => (g.endsWith('*') ? x?.startsWith(g.slice(0, -1)) : g === x)));
    if (q.gold.length) { n++; if (rank >= 0 && rank < K) hit++; if (rank >= 0) mrr += 1 / (rank + 1); }
    if (q.who) { nWho++; if (canonName(top) === q.who) who++; else if (verbose) console.log(`  person miss ${q.id}: ${top}`); }
    if (verbose) console.log(`${q.gold.length && rank >= 0 && rank < K ? '✓' : q.gold.length ? '✗' : '·'} ${q.id.padEnd(16)} rank=${rank} top=${top ?? '-'} ${verbose ? exts.join(',') : ''}`);
  }
  return { mode, [`hit@${K}`]: `${hit}/${n}`, mrr: +(mrr / n).toFixed(3), [`person@1`]: `${who}/${nWho}`, avg_ms: +(ms / SET.length).toFixed(1) };
}

const mode = arg('mode', M.recall ? 'both' : 'baseline');
for (const m of mode === 'both' ? ['baseline', 'brain'] : [mode]) console.log(JSON.stringify(await run(m)));
