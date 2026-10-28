// Chrome has no content provider, so on this rooted dev phone we copy its History SQLite file to a temp path, read the
// copy, delete it. Read-only: Chrome's own files are never opened for writing. We keep page title + URL (query strings
// are stripped by redact()) per visit, and skip private-looking hosts (bank, pay, wallet...).
import { DatabaseSync } from 'node:sqlite';
import { readFileSync, unlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { addEvent, entity } from '../store.js';
import { isPrivate, rootBytes } from './index.js';

const HISTORY = '/data/data/com.android.chrome/app_chrome/Default/History';
const toMs = (t) => t - 11644473600000; // Chrome: microseconds since 1601; we read it /1000 so it fits a JS number
const toChrome = (ms) => (ms + 11644473600000) * 1000;

export default { source: 'chrome', every: 10 * 60e3, sync(db, wm, { file, limit = 500 } = {}) {
  const tmp = join(tmpdir(), `agentos-chrome-${process.pid}.db`); // nested su may lack DAC override: then read as Chrome's own uid
  writeFileSync(tmp, file ? readFileSync(file) : rootBytes(`cat ${HISTORY} 2>/dev/null || su $(stat -c %u ${HISTORY}) -c cat ${HISTORY}`, 'chrome_history.db'), { mode: 0o600 });
  let rows = 0, added = 0, ts = wm.ts || 0;
  try {
    const h = new DatabaseSync(tmp, { readOnly: true });
    for (const v of h.prepare(`SELECT v.id, v.visit_time/1000 AS visit_time, u.url, u.title FROM visits v JOIN urls u ON u.id=v.url
      WHERE v.visit_time > ? AND u.url NOT LIKE 'chrome%' ORDER BY v.visit_time ASC LIMIT ?`).all(toChrome(wm.ts || 0), limit)) {
      rows++;
      const when = toMs(v.visit_time), host = v.url.match(/^\w+:\/\/([^/?#]+)/)?.[1] || '';
      ts = Math.max(ts, when);
      if (!host || isPrivate(host)) continue;
      if (addEvent(db, { source: 'chrome', kind: 'web', ts: when, title: v.title || host, body: v.url, ext_id: String(v.id) }, [[entity(db, 'site', host), 'site']])) added++;
    }
    h.close();
  } finally { unlinkSync(tmp); }
  return { rows, added, wm: { ts } };
} };
