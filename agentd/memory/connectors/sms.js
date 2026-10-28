// content://sms, only texts newer than the last one we stored (first run: the newest `limit`).
// Deleted texts: an hourly id diff (pruneHourly).
import { INGESTORS, liveIds } from '../ingest.js';
import { pruneHourly } from './index.js';
const last = (db) => db.prepare(`SELECT max(ts) t FROM event WHERE source='sms'`).get().t || 0;
export default { source: 'sms', every: 10 * 60e3, sync(db, wm, opts) {
  const r = INGESTORS.sms(db, { ...opts, since: last(db) }), p = pruneHourly(db, 'sms', wm, () => liveIds('content://sms', 'sms'));
  return { ...r, removed: p.removed, wm: { ts: last(db), ...p.wm } };
} };
