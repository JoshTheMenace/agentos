#!/usr/bin/env node
// Mac-only broker/model mocks: no Codex session, phone connection or approval taps.
import assert from 'node:assert/strict';

const calls = [];
let refused = false, pass = 0;
process.env.PHONE_URL = 'http://reviewer.invalid/rpc';
globalThis.fetch = async (url, { body }) => {
  assert.equal(url, process.env.PHONE_URL);
  calls.push(JSON.parse(body));
  return Response.json({ result: { ok: !refused } });
};
process.env.PHONE_TOKEN = 'reviewer-test-only';
const { grade, onApproval } = await import('./reviewer.js');
const clean = { outcome: 'auto', risk_level: 'low', user_authorization: 'high', tainted: false, rationale: 'Sends the reply you requested.' };
const codex = (v = clean) => ({ quick: async () => v });
const check = (name, actual, expected) => { assert.deepEqual(actual, expected, name); pass++; };
const req = { user: ['Reply to Mom: yes'], transcript: [], action: { tool: 'notifications.reply', app: 'local.agentos.pigeon', args: { text: 'yes' } } };
const approval = { kind: 'action', approval_id: 'mock-card', task_id: 'mock-task', tool: 'notifications.reply', pkg: 'local.agentos.pigeon', target: 'Mom', text: 'yes', allowed_channels: ['reviewer', 'tap'] };

for (const [key, value] of [['risk_level', 'medium'], ['risk_level', 'high'], ['risk_level', 'critical'], ['tainted', true], ['user_authorization', 'low'], ['user_authorization', 'unknown']])
  check(`auto held for ${key}=${value}`, (await grade(codex({ ...clean, [key]: value }), req)).decision, 'tap');
for (const floor of ['auto', 'tap', 'fingerprint', 'block'])
  check(`broker floor ${floor}`, (await grade(codex(), { ...req, floor })).decision, floor);
for (const outcome of ['tap', 'fingerprint', 'block'])
  check(`model ${outcome} preserved`, (await grade(codex({ ...clean, outcome }), req)).decision, outcome);
check('model failure', (await grade({ quick: async () => { throw new Error('offline'); } }, req)).decision, 'human');
let aborted = false;
const late = { quick: (_, job, text, signal) => new Promise((resolve) => signal.addEventListener('abort', () => { aborted = true; resolve(clean); })) };
check('timeout falls back', (await grade(late, req, 1)).decision, 'human');
check('timeout interrupts model', aborted, true);

const run = async (a = {}, ctx = {}, v = clean) => {
  calls.length = 0;
  const events = [];
  await onApproval(codex(v), { ...approval, ...a }, { turn: { goal: 'Reply to Mom: yes' }, ...ctx }, (e) => events.push(e));
  return { methods: calls.map((c) => c.method), events };
};
check('ordinary clean send', (await run()).methods, ['approval.confirm']);
check('exact approval binding', calls[0].params, { approval_id: 'mock-card', channel: 'reviewer', rationale: clean.rationale });
for (const [name, a, ctx] of [
  ['fingerprint floor', { allowed_channels: ['fingerprint'] }, {}],
  ['tap-only broker', { allowed_channels: ['tap'] }, {}],
  ['missing broker channels', { allowed_channels: undefined }, {}],
  ['guard flag', {}, { flagged: true }],
  ['broker fingerprint reason', { fingerprint_because: 'money' }, {}],
  ['remote request', {}, { turn: { goal: 'Reply yes', remote: 'Grok' } }],
  ['proactive draft', {}, { drafted: true }],
  ['lease card', { kind: 'lease' }, {}],
  ['already escalated', { escalated: true }, {}],
]) check(`${name} never confirmed`, (await run(a, ctx)).methods, []);
check('tainted never confirmed', (await run({}, {}, { ...clean, tainted: true })).methods, []);
const { flagged: liveFlags } = await import('./events.js');
for (const source of ['broker', 'turn']) {
  calls.length = 0;
  const turn = { goal: 'Reply to Mom: yes' };
  let resolve;
  const reviewing = onApproval({ quick: () => new Promise((r) => { resolve = r; }) }, approval, { turn, flagged: false }, () => {});
  if (source === 'broker') liveFlags.add(approval.task_id); else turn.alerted = new Set(['injection']);
  resolve(clean);
  await reviewing;
  check(`${source} flagged during review never confirmed`, calls, []);
  liveFlags.delete(approval.task_id);
}
check('block denied', (await run({}, {}, { ...clean, outcome: 'block' })).methods, ['approval.deny']);
check('money escalated', (await run({}, {}, { ...clean, outcome: 'fingerprint' })).methods, ['approval.escalate']);
check('fingerprint not re-escalated', (await run({ allowed_channels: ['fingerprint'] }, {}, { ...clean, outcome: 'fingerprint' })).methods, []);
refused = true;
check('broker refusal respected', (await run()).events[0].action, 'approval.confirm refused');
console.log(`${pass}/${pass} mock checks passed`);
