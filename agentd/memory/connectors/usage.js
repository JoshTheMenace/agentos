// UsageStatsManager daily totals (dumpsys usagestats, "In-memory daily stats"): per app per day, how long and how many
// launches. One row per (app, day), replaced on every sync, so "how much Spotify today" is always current.
import { root } from '../ingest.js';
import { entity } from '../store.js';
import { isPrivate, upsertEvent } from './index.js';

const secs = (s) => s.split(':').reduce((a, x) => a * 60 + +x, 0);
export function parseUsage(out) {
  const daily = out.split(/^\s*In-memory daily stats\s*$/m)[1]?.split(/^\s*In-memory weekly stats\s*$/m)[0] || '';
  const days = [];
  for (const line of daily.split('\n')) {
    const d = line.match(/timeRange="(\d+)\/(\d+)\/(\d+),/);
    if (d) { days.push({ day: `${d[3]}-${d[1].padStart(2, '0')}-${d[2].padStart(2, '0')}`, apps: [] }); continue; }
    const m = line.match(/package=(\S+) totalTimeUsed="([\d:]+)" lastTimeUsed="([^"]+)".*appLaunchCount=(\d+)/);
    if (m && days.length) days.at(-1).apps.push({ pkg: m[1], secs: secs(m[2]), last: new Date(m[3]).getTime(), launches: +m[4] });
  }
  return days;
}

export default { source: 'usage', every: 30 * 60e3, sync(db) {
  let rows = 0, added = 0;
  for (const { day, apps } of parseUsage(root('dumpsys usagestats', 'usagestats'))) {
    for (const a of apps) {
      if (a.secs < 60 || isPrivate(a.pkg)) continue;
      rows++;
      const title = `Used ${a.pkg.split('.').at(-1)} ${Math.round(a.secs / 60)} min (${a.launches} opens)`;
      if (upsertEvent(db, { source: 'usage', kind: 'usage', ts: a.last, title, ext_id: `${a.pkg}|${day}` }, [[entity(db, 'app', a.pkg), 'used']])) added++;
    }
  }
  return { rows, added };
} };
