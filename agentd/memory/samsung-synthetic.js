// GENERATED TEST DATA ONLY. No person/device data. Write outside the repo; never reuse for a real export.
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
const DAY = 864e5;
const utc = (ms) => new Date(ms).toISOString().replace('T', ' ').replace('Z', '');
export function fixture(folder, anchor = '2026-10-28') {
  mkdirSync(folder, { recursive: true, mode: 0o700 });
  writeFileSync(join(folder, 'SYNTHETIC-ONLY.txt'), 'FAKE AgentOS test data. Generated arithmetic, no real health records.\n');
  const write = (type, columns, rows, prefix = '') => {
    const quote = (v) => `"${String(v ?? '').replaceAll('"', '""')}"`;
    writeFileSync(join(folder, `${type}.20261028000000.csv`), '\uFEFF' + `${type},6,1\r\n` + columns.map((c) => prefix + c).join(',') + '\r\n'
      + rows.map((r) => r.map(quote).join(',') + ',\r\n').join(''), { mode: 0o600 });
  };
  const sleeps = [], stages = [], hearts = [], steps = [], workouts = [];
  for (let i = 0; i < 21; i++) {
    const day = Date.parse(anchor) - (20 - i) * DAY, end = day + 13 * 36e5, start = end - (i < 14 ? 7 : 8) * 36e5, id = `FAKE-SLEEP-${i}`;
    sleeps.push([id, utc(start), utc(end), 'UTC-06:00', 90, i < 14 ? 70 : 85, 'FAKE, quoted "comment"\nnot personal data']);
    let at = start;
    for (const [stage, minutes] of [[40001, 30], [40003, 90], [40004, 90], [40002, (end - start) / 60000 - 210]]) {
      stages.push([`FAKE-STAGE-${i}-${stage}`, id, utc(at), utc(at + minutes * 60000), '-21600000', stage]); at += minutes * 60000;
    }
    for (const [j, rate] of [60, 70, 80].entries()) hearts.push([`FAKE-HR-${i}-${j}`, utc(day + (14 + j) * 36e5), utc(day + (14 + j) * 36e5 + 60000), '-21600000', rate, rate + 5, rate - 5, 'FAKE-RAW-BINS-DO-NOT-IMPORT.json']);
    steps.push([`FAKE-STEPS-${i}`, day, 8000 + i * 100, 'UTC-06:00', -2]);
    steps.push([`FAKE-PHONE-STEPS-${i}`, day, 2000, 'UTC-06:00', -1]);
    if (i % 3 === 0) workouts.push([`FAKE-EXERCISE-${i}`, utc(day + 20 * 36e5), utc(day + 20.5 * 36e5), '-21600000', 1800000, 1001, 2200, 120]);
  }
  write('com.samsung.shealth.sleep', ['datauuid', 'start_time', 'end_time', 'time_offset', 'efficiency', 'sleep_score', 'comment'], sleeps, 'com.samsung.health.sleep.');
  write('com.samsung.health.sleep_stage', ['datauuid', 'sleep_id', 'start_time', 'end_time', 'time_offset', 'stage'], stages);
  write('com.samsung.health.heart_rate', ['datauuid', 'start_time', 'end_time', 'time_offset', 'heart_rate', 'max', 'min', 'binning_data'], hearts, 'com.samsung.health.heart_rate.');
  write('com.samsung.shealth.tracker.pedometer_day_summary', ['datauuid', 'day_time', 'step_count', 'time_offset', 'source_type'], steps);
  write('com.samsung.health.exercise', ['datauuid', 'start_time', 'end_time', 'time_offset', 'duration', 'exercise_type', 'distance', 'calorie'], workouts);
  write('com.samsung.shealth.stress', ['start_time', 'score'], [[utc(Date.parse(anchor)), 40]]);
  write('com.samsung.health.oxygen_saturation', ['start_time', 'spo2'], [[utc(Date.parse(anchor)), 98]]);
  return folder;
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (!process.argv[2]) throw new Error('Usage: node samsung-synthetic.js /tmp/agentos-health-synthetic [YYYY-MM-DD]');
  console.log(fixture(process.argv[2], process.argv[3]));
}
