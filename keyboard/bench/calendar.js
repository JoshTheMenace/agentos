// Copy beside agentd's config.js in an isolated on-phone copy, like model.js.
// node kb-calendar.js screen.json [rounds=3]; screen.json is the keyboard-shaped Pigeon context.
import { readFileSync } from 'node:fs';
import { cfg } from './config.js';
import { Codex } from './providers/codex.js';
import { rpc } from './tools.js';
import { open } from './memory/store.js';
import { createSuggestFacts, calendarReplies, FACTS_RULE } from './suggest-facts.js';
import { calendarSnapshot } from './suggest-calendar.js';

const [file, rounds = '3'] = process.argv.slice(2), contexts = [].concat(JSON.parse(readFileSync(file, 'utf8')));
const db = open(), calendar = calendarSnapshot(db), codex = new Codex(), factsFor = createSuggestFacts({ db, rpc, calendar });
cfg.suggestHedge = 3000;
await codex.warmSuggest();
for (const m of contexts) for (let i = 0; i < +rounds; i++) for (const enabled of i % 2 ? [true, false] : [false, true]) {
  const t = performance.now(), facts = enabled ? await factsFor(m, m.screen) : null, prep = performance.now() - t;
  const input = (facts ? FACTS_RULE + '\n' : '') + JSON.stringify({ mode: 'reply', before: m.before || '', after: '', field: m.field,
    ...(facts && { trusted_facts: facts }), untrusted_screen_data: { source_pkg: m.field.pkg, trusted: false, lines: m.screen } });
  const timing = {}, items = calendarReplies(facts, m, m.screen) || await codex.suggest(input, AbortSignal.timeout(30_000), timing).catch((e) => [`ERROR: ${e.message}`]);
  console.log(JSON.stringify({ question: m.screen.at(-1), enabled, round: i, prep: +prep.toFixed(1), ms: Math.round(performance.now() - t), timing, facts, items }));
}
process.exit(0);
