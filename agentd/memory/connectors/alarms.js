// The next alarm clock, as Android publishes it (AlarmManager.getNextAlarmClock -> Settings.System.next_alarm_formatted).
// The Clock app's own database has no public provider, and this is what the lock screen shows anyway.
// One row that follows the phone: a changed alarm replaces it, no alarm (deleted or off) removes it.
import { root } from '../ingest.js';
import { addEvent } from '../store.js';

export default { source: 'alarms', every: 10 * 60e3, sync(db) {
  const next = root('settings get system next_alarm_formatted', 'next_alarm').trim(), title = `Next alarm: ${next}`;
  const live = next && next !== 'null';
  if (live && db.prepare(`SELECT 1 FROM event WHERE source='alarms' AND ext_id='next' AND title=?`).get(title)) return { rows: 1, added: 0 };
  const removed = db.prepare(`DELETE FROM event WHERE source='alarms'`).run().changes; // also the old one-row-per-alarm copies
  return { rows: +live, added: live && addEvent(db, { source: 'alarms', kind: 'alarm', ts: Date.now(), title, ext_id: 'next' }) ? 1 : 0, removed: live ? 0 : removed };
} };
