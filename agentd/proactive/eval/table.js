// Summary table of every results/*.json: precision / recall per set (main-dev, main-test, blind), latency, tokens.
import { readdirSync, readFileSync } from 'node:fs';
const dir = new URL('./results/', import.meta.url), pct = (x) => `${Math.round(x * 100)}%`;
for (const f of readdirSync(dir).filter((f) => f.endsWith('.json')).sort()) {
  const j = JSON.parse(readFileSync(new URL(f, dir)));
  const cell = (k) => j[k]?.tp + j[k]?.fp + j[k]?.fn ? `${pct(j[k].precision)}/${pct(j[k].recall)}` : '-';
  console.log(`| ${f.replace('.json', '')} | ${j.model || 'regex'} | ${cell('dev')} | ${cell('test')} | ${cell('blind')} | ${pct(j.all.precision)}/${pct(j.all.recall)} | ${pct(j.all.cardPrecision)} | ${pct(j.all.dueAcc)} | ${(j.ms / 1000).toFixed(1)} s | ${j.usage.calls} | ${j.usage.in}/${j.usage.out} |`);
}
