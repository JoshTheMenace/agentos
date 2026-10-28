// content://call_log/calls, only calls newer than the last one we stored.
import { INGESTORS } from '../ingest.js';
const last = (db) => db.prepare(`SELECT max(ts) t FROM event WHERE source='calllog'`).get().t || 0;
export default { source: 'calllog', every: 10 * 60e3, sync: (db, wm, opts) => ({ ...INGESTORS.calllog(db, { ...opts, since: last(db) }), wm: { ts: last(db) } }) };
