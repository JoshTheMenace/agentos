import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import * as S from './store.js';
import * as M from './index.js';
import * as H from './samsung-health.js';
import { HEALTH_TABLES } from './health-schema.js';
import { fixture } from './samsung-synthetic.js';

const folder = fixture(mkdtempSync(join(tmpdir(), 'agentos-health-SYNTHETIC-'))), db = S.open(':memory:');
const ctx = { db, cloud: true, actor: 'user:test', now: Date.parse('2026-10-28T18:00:00Z') };
const path = (type) => join(folder, `${type}.20261028000000.csv`);
const ingest = () => M.ingest('samsung_health', { folder }, ctx);
const query = (metric) => H.query(db, { metric, from: '2026-10-08', to: '2026-10-28' }, ctx);

test('health import, private tools, consent, offsets and deletion', async () => {
  try {
    assert.throws(ingest, /no active consent/);
    assert.equal((await M.call('consent_grant', { source: 'samsung_health' }, { db })).ok, false);
    S.grant(db, 'samsung_health', { scope: 'local' });
    const r = ingest();
    assert.deepEqual(r.imported, { sleep: 21, heart_rate: 21, steps: 21, exercise: 7, hrv: 0, stress: 1 });
    assert.equal(r.ignored_files, 1);
    assert.throws(() => query('sleep'), /local-only/);
    assert.equal(H.query(db, { metric: 'sleep', from: '2026-10-08', to: '2026-10-28' }, { cloud: false }).rows.length, 21);
    S.grant(db, 'samsung_health', { scope: 'cloud' });
    assert.deepEqual(ingest().imported, r.imported);
    const s = H.summary(db, { days: 7 }, ctx);
    assert.equal(s.sleep.nights, 7);
    assert.equal(s.sleep.average_session_minutes_per_day, 480);
    assert.equal(s.sleep.average_asleep_minutes, 450);
    assert.equal(s.sleep.change_minutes, 60);
    assert.equal(s.sleep.last_night.length, 1);
    assert.deepEqual(s.sleep.last_night[0].stages, { awake: 30, deep: 90, rem: 90, light: 270 });
    assert.equal(query('steps').rows[0].day, '2026-10-08', 'day_time is a date, not an instant to offset');
    assert.equal(query('steps').rows[0].steps, 8000, 'no phone/watch double counting');
    assert.equal(query('heart_rate').rows[0].avg, 70);
    assert.equal(query('heart_rate').rows[0].max, 85);
    assert.equal(query('heart_rate').rows[0].resting, null, 'minimum is NOT resting');
    assert.equal(s.rule_of_two.private, true);
    assert.equal(s.rule_of_two.outward, false);
    assert.doesNotMatch(JSON.stringify(s), /FAKE|comment|datauuid|binning_data|sleep_id/);
    assert.equal(db.prepare('SELECT count(*) n FROM event').get().n, 0, 'no facts/FTS pollution');
    assert.equal((await M.call('health_summary', { days: 7 }, ctx)).source, 'samsung_health');
    assert.throws(() => H.summary(db, { days: 0 }, ctx), /days/);
    assert.throws(() => H.summary(db, { days: 1.5 }, ctx), /days/);
    assert.throws(() => H.query(db, { metric: 'sleep', from: '2026-02-30', to: '2026-03-01' }, ctx), /Dates/);
    assert.throws(() => H.query(db, { metric: 'sleep; DROP TABLE consent', from: '2026-01-01', to: '2026-01-02' }, ctx), /metric/);
    assert.equal(H.timestamp('2026-10-28 01:00:00.000'), Date.parse('2026-10-28T01:00:00Z'));
    assert.equal(H.offset('UTC-06:00'), -21600000);
    assert.equal(H.offset('+05:30'), 19800000);
    assert.equal(H.offset('-21600000'), -21600000);
    assert.deepEqual([...H.csv('a,b\r\n"x, y","quote ""hi""\nnext",\r\n')][1], ['x, y', 'quote "hi"\nnext', '']);
    // Optional version-dependent resting field: retain an explicit value, never infer it from min.
    const hrPath = path('com.samsung.health.heart_rate'), original = readFileSync(hrPath, 'utf8');
    writeFileSync(hrPath, original.replaceAll('binning_data', 'resting_heart_rate').replaceAll('FAKE-RAW-BINS-DO-NOT-IMPORT.json', '58'));
    ingest(); assert.equal(query('heart_rate').rows[0].resting, 58);
    assert.equal(H.summary(db, { days: 7 }, ctx).heart_rate.resting_change_bpm, 0);
    // Malformed import rolls back earlier files; existing rows survive intact.
    writeFileSync(hrPath, original + '"unterminated');
    assert.throws(ingest, /Unclosed/);
    assert.equal(query('heart_rate').rows[0].resting, 58);
    const deleted = S.revoke(db, 'samsung_health');
    assert.equal(deleted.health_sleep, 21);
    for (const table of HEALTH_TABLES) assert.equal(db.prepare(`SELECT count(*) n FROM ${table}`).get().n, 0);
    assert.equal(S.ledger(db).ok, true);
    assert.throws(() => query('sleep'), /no active consent/);
    S.grant(db, 'samsung_health');
    assert.equal(query('sleep').rows.length, 0, 'grant does not resurrect deleted health');
  } finally { db.close(); rmSync(folder, { recursive: true, force: true }); }
});

test('plugin routes health questions and emits the private lamp; disabled plugin refuses reads', async () => {
  const home = mkdtempSync(join(tmpdir(), 'agentos-health-tool-')), savedFetch = globalThis.fetch;
  const env = { AGENTOS_HOME: process.env.AGENTOS_HOME, MEMORY_DB: process.env.MEMORY_DB };
  process.env.AGENTOS_HOME = home; process.env.MEMORY_DB = join(home, 'memory.db');
  writeFileSync(join(home, 'phone.token'), 'SYNTHETIC-TEST-TOKEN');
  let off = [];
  globalThis.fetch = async () => ({ ok: true, json: async () => ({ result: { off } }) });
  try {
    const plugin = await import('../plugins.js'), runtime = await import('../tools.js');
    for (const q of ['How did I sleep this week?', "What's my resting heart rate trend?"]) {
      assert.ok(plugin.pick(q).includes('health'));
      assert.ok(plugin.toolset(q, new Set(), 'plugins').names.includes('health_summary'));
    }
    const own = S.open(process.env.MEMORY_DB); S.grant(own, 'samsung_health'); own.close();
    const emitted = [], ctx = { provider: 'codex', turn: { goal: 'How did I sleep this week?' }, emit: (e) => emitted.push(e) };
    const result = await runtime.runTool('health_summary', { days: 7 }, ctx);
    assert.equal(result.success, true);
    assert.equal(emitted.find((e) => e.type === 'tool.result').rule_of_two.private, true);
    off = ['health']; plugin.invalidate();
    assert.equal((await runtime.runTool('health_summary', {}, ctx)).success, false);
  } finally {
    globalThis.fetch = savedFetch;
    for (const [key, value] of Object.entries(env)) if (value === undefined) delete process.env[key]; else process.env[key] = value;
    rmSync(home, { recursive: true, force: true });
  }
});


test('real-export aliases and numeric JSON sidecars supply HR/HRV, never calibration stress or invented resting HR', () => {
  const dir = mkdtempSync(join(tmpdir(), 'agentos-health-sidecar-'));
  try {
    const write = (type, header, row) => writeFileSync(join(dir, `${type}.20261028000000.csv`), `${type},1,1\n${header}\n${row}\n`);
    write('com.samsung.shealth.tracker.heart_rate', 'datauuid,start_time,end_time,time_offset,heart_rate,min,max,binning_data',
      'FAKE-HR,2026-10-28 01:00:00,2026-10-28 02:00:00,UTC-06:00,0,40,95,FAKE-HR.json');
    write('com.samsung.health.hrv', 'datauuid,start_time,end_time,time_offset,binning_data',
      'FAKE-HRV,2026-10-28 01:00:00,2026-10-28 02:00:00,UTC-06:00,FAKE-HRV.json');
    write('com.samsung.shealth.exercise', 'datauuid,start_time,end_time,time_offset,duration,exercise_type,sensing_status',
      'FAKE-WORKOUT,2026-10-28 01:00:00,2026-10-28 02:00:00,UTC-06:00,1800000,1001,FAKE-WORKOUT.json');
    write('com.samsung.shealth.stress.histogram', 'create_time,base_hr,histogram', '2026-10-28 01:00:00,50,FAKE-CALIBRATION.json');
    const start = Date.parse('2026-10-28T01:00:00Z');
    const data = {
      'jsons/com.samsung.shealth.tracker.heart_rate/FAKE-HR.json': [{ start_time: start, heart_rate: 60, heart_rate_max: 70 }, { start_time: start + 60000, heart_rate: 80, heart_rate_max: 90 }],
      'jsons/com.samsung.health.hrv/FAKE-HRV.json': [{ start_time: start, sdnn: 40, rmssd: 50 }, { start_time: start + 60000, sdnn: 60, rmssd: 70 }],
      'jsons/com.samsung.shealth.exercise/FAKE-WORKOUT.json': { heart_rate: { rhr: 55 }, comment: 'FAKE PRIVATE STRING' },
    };
    execFileSync('python3', ['-c', 'import sys,json,zipfile; z=zipfile.ZipFile(sys.argv[1],"w"); [z.writestr(k,json.dumps(v)) for k,v in json.load(sys.stdin).items()]; z.close()', join(dir, 'jsons.zip')], { input: JSON.stringify(data) });
    const n = H.normalize(dir);
    assert.deepEqual(n.heart_rate, [{ day: '2026-10-27', resting: 55, avg: 70, max: 90, samples: 2 }]);
    assert.deepEqual(n.hrv, [{ day: '2026-10-27', sdnn_avg: 50, rmssd_avg: 60, samples: 2 }]);
    assert.equal(n.exercise.length, 1);
    assert.equal(n.stress.length, 0);
    assert.doesNotMatch(JSON.stringify(n), /FAKE PRIVATE/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
