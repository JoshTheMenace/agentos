// content://call_log/calls, only calls newer than the last one we stored.
// Deleted calls: an hourly id diff (pruneHourly).
import { INGESTORS, liveIds } from '../ingest.js';
import { pruneHourly } from './index.js';
const last = (db) => db.prepare(`SELECT max(ts) t FROM event WHERE source='calllog'`).get().t || 0;
export default { source: 'calllog', every: 10 * 60e3, sync(db, wm, opts) {
  const r = INGESTORS.calllog(db, { ...opts, since: last(db) }), p = pruneHourly(db, 'calllog', wm, () => liveIds('content://call_log/calls', 'calllog'));
  return { ...r, removed: p.removed, wm: { ts: last(db), ...p.wm } };
} };
