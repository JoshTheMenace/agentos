// content://sms, only texts newer than the last one we stored (first run: the newest `limit`).
import { INGESTORS } from '../ingest.js';
const last = (db) => db.prepare(`SELECT max(ts) t FROM event WHERE source='sms'`).get().t || 0;
export default { source: 'sms', every: 10 * 60e3, sync: (db, wm, opts) => ({ ...INGESTORS.sms(db, { ...opts, since: last(db) }), wm: { ts: last(db) } }) };
