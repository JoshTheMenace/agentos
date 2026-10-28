import { test } from 'node:test';
import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
const state = globalThis.voiceTest = { tasks: [], listeners: new Set() };
const modules = {
  './config.js': `export const cfg = {voiceVision:false, reviewModel:'off'}; export const log = () => {};`,
  './tasks.js': `const s=globalThis.voiceTest; export const onUpdate=f=>(s.listeners.add(f),()=>s.listeners.delete(f));
    export const list=()=>s.tasks; export const start=goal=>{const t={id:'k'+s.tasks.length,goal,state:'running'};s.tasks.push(t);return t};
    export const describe=()=>''; export const pick=()=>null; export const stop=async()=>{}; export const owns=()=>false;`,
  './events.js': `export const onEvent=()=>()=>{}; export const pending=new Map(); export const intent=()=>false; export const answer=async()=>null; export const state={};`,
  './tools.js': `export const rpc=async()=>({});`,
};
const hooks = registerHooks({ resolve(specifier, context, next) {
  return context.parentURL?.endsWith('/agentd/voice.js') && modules[specifier]
    ? { url: 'data:text/javascript,' + encodeURIComponent(modules[specifier]), shortCircuit: true } : next(specifier, context);
} });
const { Voice } = await import('../../agentd/voice.js');
hooks.deregister();
function fixture(direct = true) {
  const starts = [], stopped = [], sent = [], said = [], notes = [];
  const codex = {
    voiceStart: (_, on) => new Promise((resolve) => starts.push({ on, resolve })),
    voiceStop: async (id) => { stopped.push(id); },
    voiceSay: async (_, text) => { said.push(text); },
    voiceText: async (_, text) => { notes.push(text); },
  };
  const voice = new Voice(codex, (m) => sent.push(m));
  const start = async (id) => { const p = voice.handle({ type: 'voice.start', client_handoffs: direct, sdp: 'offer' }); await new Promise(setImmediate); starts.at(-1).resolve({ threadId: id, sdp: 'answer' }); await p; };
  return { voice, starts, stopped, sent, said, notes, start };
}
test('closing during setup stops the late session and ignores its events', async () => {
  const f = fixture(), pending = f.voice.handle({ type: 'voice.start', sdp: 'offer' });
  f.voice.close(); f.starts[0].resolve({ threadId: 'late', sdp: 'answer' }); await pending;
  f.starts[0].on({ method: 'thread/realtime/started', params: {} });
  assert.deepEqual(f.stopped, ['late']); assert.equal(f.sent.length, 0); assert.equal(f.voice.active, false);
  f.voice.dispose();
});
test('new session wins even if the previous setup finishes afterwards', async () => {
  const f = fixture(), old = f.voice.handle({ type: 'voice.start' });
  await f.start('new'); f.starts[0].resolve({ threadId: 'old', sdp: 'answer' }); await old;
  f.starts[0].on({ method: 'thread/realtime/closed', params: {} });
  assert.equal(f.voice.active, true); assert.deepEqual(f.stopped, ['old']);
  assert.equal(f.sent.filter((m) => m.type === 'voice.answer').length, 1); f.voice.dispose();
});
test('both handoff identities dedupe in either order and reset for a new session', async () => {
  const f = fixture(); state.tasks = []; await f.start('one');
  const page = { type: 'voice.delegate', item_id: 'item1', handoff_id: 'handoff_1', text: 'Read the battery' };
  await f.voice.handle(page);
  f.starts[0].on({ method: 'thread/realtime/itemAdded', params: { item: { type: 'handoff_request', handoff_id: 'handoff_1', input_transcript: page.text } } });
  assert.equal(state.tasks.length, 1); assert.equal(f.said.length, 0); assert.equal(f.notes.length, 1);
  await f.start('two');
  f.starts[1].on({ method: 'thread/realtime/itemAdded', params: { item: { type: 'handoff_request', handoff_id: 'handoff_1', input_transcript: page.text } } });
  await f.voice.handle(page); assert.equal(state.tasks.length, 2); f.voice.dispose();
});
test('status streams quietly; the terminal result is spoken', async () => {
  const f = fixture(); await f.start('status');
  for (const listener of state.listeners) listener({ id: 'k1', goal: 'Read the battery', state: 'running', step: 'Checking battery' }, 'step');
  assert.equal(f.said.length, 0); assert.match(f.notes[0], /Checking battery/);
  for (const listener of state.listeners) listener({ id: 'k1', result: 'Battery is full.' }, 'done');
  assert.match(f.said[0], /Task finished: Battery is full/); f.voice.dispose();
});
test('missing handoff starts once; late handoff cannot repeat the job', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const f = fixture(); state.tasks = []; await f.start('fallback');
  f.voice.handle({ type: 'voice.heard', turn_id: 'turn1', text: 'Read the phone battery' });
  t.mock.timers.tick(600); assert.equal(state.tasks.length, 1);
  await f.voice.handle({ type: 'voice.delegate', item_id: 'late', handoff_id: 'handoff_1', turn_id: 'turn1', text: 'Read the phone battery' });
  f.starts[0].on({ method: 'thread/realtime/itemAdded', params: { item: { type: 'handoff_request', handoff_id: 'handoff_1', input_transcript: 'Read the phone battery' } } });
  assert.equal(state.tasks.length, 1);
  f.voice.handle({ type: 'voice.heard', turn_id: 'turn2', text: 'Read the phone battery' });
  t.mock.timers.tick(600); assert.equal(state.tasks.length, 2); f.voice.dispose();
});
test('a handoff before the user transcript prevents the fallback; close cancels it', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const f = fixture(); state.tasks = []; await f.start('early');
  await f.voice.handle({ type: 'voice.delegate', item_id: 'early', handoff_id: 'handoff_1', turn_id: 'turn1', text: 'Read the battery' });
  f.voice.handle({ type: 'voice.heard', turn_id: 'turn1', text: 'Read the battery' });
  t.mock.timers.tick(600); assert.equal(state.tasks.length, 1);
  f.voice.handle({ type: 'voice.heard', turn_id: 'turn2', text: 'Read the battery' });
  f.voice.close(); t.mock.timers.tick(600); assert.equal(state.tasks.length, 1); f.voice.dispose();
});
test('a sideband report after a fallback cannot swallow a new repeated user turn', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const f = fixture(); state.tasks = []; await f.start('repeated');
  f.voice.handle({ type: 'voice.heard', turn_id: 'turn1', text: 'Read the phone battery' });
  t.mock.timers.tick(600); assert.equal(state.tasks.length, 1);
  f.starts[0].on({ method: 'thread/realtime/itemAdded', params: { item: { type: 'handoff_request', handoff_id: 'handoff_2', input_transcript: 'Check how much charge is left' } } });
  assert.equal(state.tasks.length, 1);
  await f.voice.handle({ type: 'voice.delegate', item_id: 'item2', handoff_id: 'handoff_2', turn_id: 'turn2', text: 'Read the phone battery' });
  assert.equal(state.tasks.length, 2); f.voice.dispose();
});
test('legacy clients still receive one sideband handoff', async () => {
  const f = fixture(false); state.tasks = []; await f.start('legacy');
  f.starts[0].on({ method: 'thread/realtime/itemAdded', params: { item: { type: 'handoff_request', handoff_id: 'handoff_1', input_transcript: 'Read the battery' } } });
  await f.voice.handle({ type: 'voice.delegate', item_id: 'item1', handoff_id: 'handoff_1', text: 'Read the battery' });
  assert.equal(state.tasks.length, 1); f.voice.dispose();
});
