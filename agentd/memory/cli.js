#!/usr/bin/env node
// Demo CLI for AgentOS memory. Prints JSON. Output is redacted (cloud view) unless --local is given.
//   node cli.js grant contacts [--scope local]     node cli.js revoke sms
//   node cli.js ingest contacts|calendar|calllog|sms|notifications|pigeon [--limit N]
//   node cli.js ingest whatsapp --file "WhatsApp Chat with Alex.txt" [--me "Josh"]
//   node cli.js sync [source] [--force]            incremental connectors (all granted sources, or one); --force ignores watermarks
//   node cli.js search trip to moab                node cli.js entity Alex    node cli.js timeline --day 2026-10-26
//   node cli.js neighbors [--id 5 --depth 2]       node cli.js remember "Alex likes oat milk" --about Alex
//   node cli.js forget --event_id 12               node cli.js consent | ledger | stats
//   node cli.js graph [--out graph.json]           node cli.js serve [--port 8790]   (GET /graph.json, CORS on, localhost)
//   node cli.js call <tool> '{"json":"args"}'      global: --db <path> (default ~/.agentos/memory.db)
//   v2 (MEMORY_V2=1): facts [words] [--about Mom] [--history]   about   person Joe   pending   confirm <a> <b> --same|--different
//                     correct <fact_id> --obj "..." | --retract    facts-sync [--force]   facts-llm [--force] [--max N] (codex exec, gpt-6-luna; cache ~/.agentos/memory-llm-cache.json)
process.removeAllListeners('warning'); // hide node:sqlite's "experimental" banner

const argv = process.argv.slice(2), pos = [], flags = {};
for (let i = 0; i < argv.length; i++) {
  if (!argv[i].startsWith('--')) pos.push(argv[i]);
  else flags[argv[i].slice(2)] = argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[++i] : true;
}
const num = (v) => (v === undefined || v === true || isNaN(+v) ? v : +v);
for (const k in flags) flags[k] = num(flags[k]);

const M = await import('./index.js');
const S = await import('./store.js');
const db = S.open(flags.db);
const ctx = { db, actor: 'user:cli', cloud: !flags.local };
const [cmd, ...rest] = pos;
const out = (x) => console.log(JSON.stringify(x, null, 2));
const tool = async (name, args) => {
  const t0 = performance.now(); const r = await M.call(name, args, ctx);
  if (flags.time) console.error(`${name}: ${(performance.now() - t0).toFixed(1)} ms`);
  out(r);
};
const { db: _, ...opts } = flags;

switch (cmd) {
  case 'grant': await tool('consent_grant', { source: rest[0], ...(flags.scope && { scope: flags.scope }) }); break;
  case 'revoke': await tool('consent_revoke', { source: rest[0] }); break;
  case 'consent': await tool('consent_list', {}); break;
  case 'ledger': out(S.ledger(db)); break;
  case 'sync': { const C = await import('./connectors/index.js'); out(rest[0] ? C.sync(db, rest[0], { force: !!flags.force }) : C.syncAll(db, { force: !!flags.force })); break; }
  case 'ingest': try { out(M.ingest(rest[0], opts, ctx)); } catch (e) { out({ ok: false, error: e.message }); process.exitCode = 1; } break;
  case 'search': await tool('memory_search', { q: rest.join(' '), ...(flags.limit && { limit: flags.limit }), ...(flags.kind && { kinds: [flags.kind] }) }); break;
  case 'entity': await tool('memory_entity', /^\d+$/.test(rest[0]) ? { id: +rest[0] } : { name: rest.join(' ') }); break;
  case 'timeline': await tool('memory_timeline', { ...opts, ...(rest[0] && { day: rest[0] }) }); break;
  case 'neighbors': await tool('memory_neighbors', { ...(flags.id && { id: flags.id }), ...(flags.depth && { depth: flags.depth }) }); break;
  case 'remember': await tool('memory_remember', { text: rest.join(' '), ...(flags.about && { about: String(flags.about) }) }); break;
  case 'forget': await tool('memory_forget', { ...(flags.event_id && { event_id: flags.event_id }), ...(flags.entity_id && { entity_id: flags.entity_id }) }); break;
  case 'call': await tool(rest[0], JSON.parse(rest[1] || '{}')); break;
  case 'facts': out(M.facts({ q: rest.join(' ') || undefined, about: flags.about, history: !!flags.history }, ctx)); break;
  case 'about': out(M.about(ctx)); break;
  case 'person': out(M.person(/^\d+$/.test(rest[0]) ? +rest[0] : rest.join(' '), ctx)); break;
  case 'pending': out(M.identity.pending(ctx, flags.limit || 10)); break;
  case 'confirm': out(M.identity.confirm(+rest[0], +rest[1], !flags.different, { ...ctx, actor: 'user:owner' })); break;
  case 'correct': out(M.correct(+rest[0], { obj: flags.obj, retract: !!flags.retract }, ctx)); break;
  case 'facts-sync': out(M.syncFacts({ ...ctx, force: !!flags.force })); break;
  case 'facts-llm': out(await (await import('./extract-llm.js')).run(db, { force: !!flags.force, log: console.error, maxCalls: flags.max || Infinity, cache: flags.cache || `${process.env.AGENTOS_HOME || `${process.env.HOME}/.agentos`}/memory-llm-cache.json` })); break;
  case 'stats': out({ fts: db.fts, ...Object.fromEntries(['entity', 'alias', 'event', 'relation', 'consent'].map((t) => [t, db.prepare(`SELECT count(*) n FROM ${t}`).get().n])),
    by_source: db.prepare('SELECT source, kind, count(*) n FROM event GROUP BY 1, 2').all(), entities_by_type: db.prepare('SELECT type, count(*) n FROM entity GROUP BY 1').all() }); break;
  case 'graph': {
    const g = M.graph(ctx);
    if (flags.out) { (await import('node:fs')).writeFileSync(flags.out, JSON.stringify(g)); out({ ok: true, file: flags.out, nodes: g.nodes.length, edges: g.edges.length }); } else out(g);
    break;
  }
  case 'serve': {
    const port = flags.port || 8790;
    (await import('node:http')).createServer((req, res) => {
      const body = req.url.startsWith('/graph.json') ? M.graph(ctx) : req.url.startsWith('/consent') ? S.consentState(db) : null;
      res.writeHead(body ? 200 : 404, { 'content-type': 'application/json', 'access-control-allow-origin': '*' });
      res.end(JSON.stringify(body ?? { error: 'try /graph.json or /consent' }));
    }).listen(port, '127.0.0.1', () => console.error(`memory graph on http://127.0.0.1:${port}/graph.json`));
    break;
  }
  default: console.error((await import('node:fs')).readFileSync(new URL(import.meta.url), 'utf8').split('\n').slice(1, 11).join('\n')); process.exitCode = 1;
}
