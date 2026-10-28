// CalendarContract events + attendees. Full window (newest 1000 before now+90d) every 6 h, else the next 14 days. Each run
// replaces edited events (putEvent) and drops deleted ones (an id-only diff against the provider, deleted=1 counts as gone).
// CalendarContract has no public last-modified column, so edits further out than 14 days land with the 6-hourly full read;
// agentd's own add/update/delete_event calls are mirrored at once (memory.noteApi).
import { INGESTORS } from '../ingest.js';
const FULL = 6 * 60 * 60e3;
export default { source: 'calendar', every: 10 * 60e3, sync(db, wm, opts) {
  const full = !wm.full || Date.now() - wm.full > FULL;
  const r = INGESTORS.calendar(db, full ? opts : { ...opts, limit: 200, days_ahead: 14, since: Date.now() - 864e5 });
  return { ...r, wm: { full: full ? Date.now() : wm.full } };
} };
