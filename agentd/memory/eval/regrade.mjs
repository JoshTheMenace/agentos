// Re-grade saved runs with the current questions.js (regexes fixed after a run): node memory/eval/regrade.mjs label...
import { readFileSync } from 'node:fs';
import { HELDOUT, QUESTIONS } from './questions.js';
const Q = new Map([...QUESTIONS, ...HELDOUT].map((q) => [q.id, q]));
const med = (a) => { a = [...a].sort((x, y) => x - y); return a[Math.floor((a.length - 1) / 2)]; };
for (const label of process.argv.slice(2)) {
  const rows = readFileSync(new URL(`./results/${label}.jsonl`, import.meta.url), 'utf8').trim().split('\n').map((l) => JSON.parse(l));
  for (const [set, rs] of [['main', rows.filter((r) => !r.id.startsWith('h_'))], ['heldout', rows.filter((r) => r.id.startsWith('h_'))]]) {
    if (!rs.length) continue;
    const ok = rs.filter((r) => { const q = Q.get(r.id); return r.status === 'completed' && q.answer.test(r.answer) && !(q.not && q.not.test(r.answer)); });
    console.log(JSON.stringify({ label, set, correct: `${ok.length}/${rs.length}`, median_s: +(med(rs.map((r) => r.ms)) / 1000).toFixed(1),
      tool_calls: +(rs.reduce((s, r) => s + r.tools.length, 0) / rs.length).toFixed(2), model_calls: +(rs.reduce((s, r) => s + (r.calls || 0), 0) / rs.length).toFixed(2) }));
  }
}
