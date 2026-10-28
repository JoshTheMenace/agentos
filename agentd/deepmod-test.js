// Security checks against an isolated consent ledger and a fake phone, never the attached device.
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
const dir = mkdtempSync(join(tmpdir(), 'deepmod-test-'));
process.env.AGENTOS_HOME = dir;
process.env.MEMORY_DB = join(dir, 'memory.db');
process.env.PHONE_TOKEN = 'test-only';
let phoneCalls = 0, off = [];
globalThis.fetch = async () => { phoneCalls++; return Response.json({ result: { ok: true, off } }); };
const { TOOLS, runTool } = await import('./tools.js');
const { redactSummary } = await import('./deepmod-tools.js');
const { open, grant, revoke } = await import('./memory/store.js');
let cases = 0;
try {
  for (const name of Object.keys(TOOLS).filter((n) => n !== 'screen_read')) {
    const r = await runTool(name, {}, { provider: 'local', turn: { origin: 'deepmod' }, emit() {} });
    assert.equal(r.success, false, name); assert.match(r.text, /read-only/); cases++;
  }
  assert.equal(phoneCalls, 1, 'only the plugin switch read may reach the fake phone');
  const screen = { pkg: 'local.agentos.pigeon', nodes: [{ src: 'APP:local.agentos.pigeon', text: 'Call +1-801-555-1234. Code 123456', desc: 'email me@example.com' }] };
  assert.equal(redactSummary(screen).ok, false); cases++;
  let db = open(); grant(db, 'pigeon', { scope: 'local', actor: 'test' }); db.close();
  assert.equal(redactSummary(screen).ok, false); cases++;
  db = open(); grant(db, 'pigeon', { scope: 'cloud', actor: 'test' }); db.close();
  const safe = redactSummary(screen);
  assert.match(safe.nodes[0].text, /\[P:\d+\]/); assert.match(safe.nodes[0].text, /\[OTP\]/); assert.match(safe.nodes[0].desc, /\[E:\d+\]/); cases++;
  assert.match(screen.nodes[0].text, /123456/, 'source data is unchanged'); cases++;
  assert.equal(redactSummary({ ...screen, pkg: 'another.app' }).ok, false); cases++;
  assert.equal(redactSummary({ ...screen, secure: true }).ok, false); cases++;
  assert.equal(redactSummary({ ...screen, nodes: [...screen.nodes, { src: 'APP:another.app', text: 'private' }, { src: 'SYSTEM', text: 'private' }, { text: 'unknown source' }] }).nodes.length, 1); cases++;
  const huge = redactSummary({ ...screen, nodes: Array.from({ length: 150 }, () => ({ src: 'APP:local.agentos.pigeon', text: 'a'.repeat(1000) })) });
  assert.equal(huge.nodes.length, 120); assert.equal(huge.nodes.reduce((n, v) => n + v.text.length, 0), 6000); cases++;
  db = open(); revoke(db, 'pigeon', { actor: 'test' }); db.close();
  assert.equal(redactSummary(screen).ok, false); cases++;
  const tasks = await import('./tasks.js'), callsBeforeSummary = phoneCalls;
  tasks.init({ async run(session, prompt, ctx) {
    assert.equal(ctx.turn.origin, 'deepmod'); assert.equal(ctx.turn.untrusted, true);
    assert.equal(ctx.turn.deepmodContext, 'one-use-context');
    assert.doesNotMatch(prompt, /Recent phone context|Installed apps/);
    return { status: 'completed', text: 'Fixture summary' };
  } });
  await new Promise((resolve, reject) => {
    const off = tasks.onUpdate((t) => {
      if (!['done', 'failed'].includes(t.state)) return;
      off(); t.state === 'done' ? resolve() : reject(new Error(t.error));
    });
    tasks.start('Summarize the visible fixture', { origin: 'deepmod', remote: 'Pigeon Summarize control', deepmodContext: 'one-use-context' });
  });
  assert.equal(phoneCalls, callsBeforeSummary, 'summary worker must not fetch apps or unrelated phone context'); cases++;
  off = ['mods']; (await import('./plugins.js')).invalidate();
  const ctx = { provider: 'local', turn: { origin: 'typed' }, emit() {} };
  assert.equal((await runTool('deepmod_style', {}, ctx)).success, false); cases++;
  for (const name of ['deepmod_disable', 'deepmod_remove', 'deepmod_rollback']) {
    assert.equal((await runTool(name, {}, ctx)).success, true, `${name} remains available with Mods off`); cases++;
  }
  console.log(`${cases} summary scope/consent/redaction cases PASS`);
} finally { rmSync(dir, { recursive: true, force: true }); }
