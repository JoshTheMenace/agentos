// CalendarContract events + attendees. Full window (newest 1000 before now+90d) every 6 h, else only events starting after
// the last full sync's horizon... which can't catch edits, so the window sync re-reads the next 14 days (cheap, idempotent).
import { INGESTORS } from '../ingest.js';
const FULL = 6 * 60 * 60e3;
export default { source: 'calendar', every: 10 * 60e3, sync(db, wm, opts) {
  const full = !wm.full || Date.now() - wm.full > FULL;
  const r = INGESTORS.calendar(db, full ? opts : { ...opts, limit: 200, days_ahead: 14, since: Date.now() - 864e5 });
  return { ...r, wm: { full: full ? Date.now() : wm.full } };
} };
