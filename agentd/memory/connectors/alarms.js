// The next alarm clock, as Android publishes it (AlarmManager.getNextAlarmClock -> Settings.System.next_alarm_formatted).
// The Clock app's own database has no public provider, and this is what the lock screen shows anyway.
import { root } from '../ingest.js';
import { addEvent } from '../store.js';

export default { source: 'alarms', every: 10 * 60e3, sync(db) {
  const next = root('settings get system next_alarm_formatted', 'next_alarm').trim();
  if (!next || next === 'null') return { rows: 0, added: 0 };
  return { rows: 1, added: addEvent(db, { source: 'alarms', kind: 'alarm', ts: Date.now(), title: `Next alarm: ${next}`, ext_id: `next|${next}` }) ? 1 : 0 };
} };
