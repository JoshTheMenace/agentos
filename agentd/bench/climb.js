#!/usr/bin/env node
// Interleaved A/B and hill-climbing on the real phone. A variant is a set of env vars for the bench agentd (bench/up.sh):
// CODEX_SERVICE_TIER, CODEX_FINISH, CODEX_MODEL, or AGENTD_PROMPT_PATCH (prompt/tool-description edits, see codex.js).
// Rounds rotate through the variants (restart bench agentd, run each task once), so network drift hits them all alike.
//   node bench/climb.js --ab base,fast --rounds 3 [--tasks alarm,timer]   # compare named variants
//   node bench/climb.js --climb --rounds 2                                 # try each CANDIDATE against the best so far, keep wins
// Rows go to results/<prefix>-<variant>.jsonl (bench.js format); compare with `node bench/bench.js --summary ...`.
import { execFileSync } from 'node:child_process';
import { mkdirSync, rmSync, rmdirSync, writeFileSync } from 'node:fs';

const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d; };
const LOCK = '/tmp/agentos-device.lock', DIR = new URL('..', import.meta.url).pathname;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64');

// Prompt variants for --climb. Each is a patch on top of the best variant so far.
const P = (patch) => ({ AGENTD_PROMPT_PATCH: b64(patch) });
export const VARIANTS = {
  base: {},
  fast: { CODEX_SERVICE_TIER: 'priority' },
  finish: { CODEX_FINISH: '1' },
  all: { AGENTD_TOOLS: 'all' },          // tool plugins (plugins.js): every enabled tool upfront
  plugins: { AGENTD_TOOLS: 'plugins' },  // core + pre-selected plugins, the rest via load_plugin
};
const CANDIDATES = {
  // Tell it where the final reply goes (the `done` field) instead of a separate message.
  doneHint: { append: '- Finish in the same call: when an action call completes the request (set_alarm, set_timer, web_search, notifications_reply, or an act that ends with the result on screen), put your final reply in its `done` field. Then you are finished; don\'t send another message.' },
  // Blind batches: open_app + label steps in ONE act for apps whose layout is predictable (act's label targets).
  blindAct: { append: '- Fewest calls: when you can predict an app\'s screens (Calculator keys "2" "×" "=", a chat row with the person\'s name, a "Send" button), do the whole task as ONE act that starts with an open_app step and names targets by label. If a label isn\'t there, the batch stops and you get the screen to continue from.' },
  // Plan the whole task as one act when the screen allows it.
  oneAct: { append: '- Plan for the fewest calls: after open_app, put every remaining step you can already see into ONE act call (tap, type, tap Search/Send, tap the result). Never split steps into separate calls when one act can do them.' },
  // Shorter final replies (fewer output tokens on the last call).
  terse: { append: '- Final replies: one short sentence, no lists, no follow-up offers.' },
  // Few-shot: a worked example of the 2-call shape.
  fewShot: { append: '- Example. User: "Use Calculator to work out 12 times 3." → open_app com.google.android.calculator → act [tap 1, tap 2, tap ×, tap 3, tap =] → reply "12 × 3 = 36." Example. User: "Text Sam I\'m here" in a chat app → open_app → act [tap the chat, type the message into the box, tap Send] with done "Sent." when the call can finish.' },
};

const run = (cmd, args, env = {}) => execFileSync(cmd, args, { cwd: DIR, encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'], env: { ...process.env, ...env }, timeout: 30 * 60_000 });
async function locked(fn) {
  for (let i = 0; ; i++) { try { mkdirSync(LOCK); writeFileSync(`${LOCK}/pid`, String(process.pid)); break; } catch { if (i > 300) throw new Error('device lock busy 10 min'); await sleep(2000); } }
  try { return fn(); } finally { rmSync(`${LOCK}/pid`, { force: true }); rmdirSync(LOCK); }
}
// Restart the bench agentd with this variant's env (the code is copied once, then NOCOPY restarts).
let copied = false;
async function up(env) {
  await locked(() => run('sh', ['bench/snap.sh'], { ...(copied && { NOCOPY: '1' }), ...env }));
  copied = true;
}
const bench = (label, tasks) => { try { return run('node', ['bench/bench.js', '--label', label, '--runs', '1', '--tasks', tasks]); } catch (e) { return `FAIL bench.js: ${e.message.split('\n')[0]}`; } }; // bench.js locks per task

const rows = (label) => { try { return run('cat', [`bench/results/${label}.jsonl`]).trim().split('\n').map((l) => JSON.parse(l)); } catch { return []; } };
const med = (a) => { a = a.filter((x) => x != null).sort((x, y) => x - y); return a.length ? a[Math.floor((a.length - 1) / 2)] : null; };
// Score = per-task median time, summed over tasks; a failed run counts as 60 s. Lower is better.
function score(label) {
  const r = rows(label), tasks = [...new Set(r.map((x) => x.task))];
  return { s: tasks.reduce((a, t) => a + med(r.filter((x) => x.task === t).map((x) => x.ok ? x.ms : 60_000)), 0), ok: r.filter((x) => x.ok).length, n: r.length,
    calls: med(r.map((x) => x.calls)) };
}

async function compare(prefix, variants, rounds, tasks) {
  for (let i = 0; i < rounds; i++) for (const [name, env] of (i % 2 ? [...variants].reverse() : variants)) {
    console.log(`\n== round ${i + 1}/${rounds}: ${name}`);
    await up(env);
    console.log(bench(`${prefix}-${name}`, tasks).trim().split('\n').filter((l) => /OK|FAIL|success/.test(l)).join('\n'));
  }
  return Object.fromEntries(variants.map(([n]) => [n, score(`${prefix}-${n}`)]));
}

const tasks = arg('tasks', 'alarm,timer,chrome,calc,reply'), rounds = Number(arg('rounds', 2)), prefix = arg('prefix', 'r2');
if (process.argv.includes('--ab')) {
  const res = await compare(prefix, arg('ab').split(',').map((n) => [n, VARIANTS[n]]), rounds, tasks);
  console.log('\n', JSON.stringify(res, null, 1));
} else if (process.argv.includes('--climb')) {
  // Greedy: keep a patch if it beats the current best by > 5 % (sum of per-task medians) with no fewer passes.
  // --keep a,b: start from candidates already won; --only c,d: which to try (default: all others).
  const kept = arg('keep', '').split(',').filter(Boolean), only = arg('only', '').split(',').filter(Boolean);
  let best = { name: kept.length ? `climb-${kept.join('+')}` : 'climb0', env: JSON.parse(arg('base-env', '{}')), patch: { append: kept.map((k) => CANDIDATES[k].append).join('\n') } };
  for (const [cname, c] of Object.entries(CANDIDATES).filter(([k]) => !kept.includes(k) && (!only.length || only.includes(k)))) {
    const patch = { ...best.patch, append: [best.patch.append, c.append].filter(Boolean).join('\n') };
    const cand = { name: `climb-${cname}`, env: { ...best.env, ...P(patch) }, patch };
    const res = await compare(`${prefix}-${cname}`, [[best.name, best.patch.append ? { ...best.env, ...P(best.patch) } : best.env], [cand.name, cand.env]], rounds, tasks);
    const [b, x] = [res[best.name], res[cand.name]];
    const win = x.ok >= b.ok && x.s < b.s * 0.95;
    console.log(`\n${cname}: ${(x.s / 1000).toFixed(1)} s (${x.ok}/${x.n}) vs best ${(b.s / 1000).toFixed(1)} s (${b.ok}/${b.n}) -> ${win ? 'KEEP' : 'drop'}`);
    if (win) best = cand;
  }
  console.log(`\nwinner: ${best.name}\n${best.patch.append}`);
}
