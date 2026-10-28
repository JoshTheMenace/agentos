// What's playing: active media sessions (dumpsys media_session: track, artist, app). Polled every 2 minutes, so a song
// is one row per 20-minute slot even if we see it several times. Players' ongoing notifications never reach the
// notification feed (Notifs.java drops ongoing ones), so this poll is the only "listening" signal.
import { root } from '../ingest.js';
import { addEvent, entity } from '../store.js';
import { isPrivate } from './index.js';

export function parseSessions(out) {
  const stack = out.split(/Sessions Stack - have \d+ sessions:/)[1]?.split(/\n(?=\S)/)[0] || '';
  return stack.split(/\n  (?=\S)/).map((b) => ({ pkg: b.match(/package=(\S+)/)?.[1], playing: /state=PlaybackState \{state=3\b/.test(b),
    desc: b.match(/metadata: size=\d+, description=(.*)$/m)?.[1]?.trim() })).filter((s) => s.pkg && s.desc && s.desc !== 'null');
}

export default { source: 'media', every: 2 * 60e3, sync(db) {
  let rows = 0, added = 0;
  const slot = Math.floor(Date.now() / (20 * 60e3));
  for (const s of parseSessions(root('dumpsys media_session', 'media_session'))) {
    if (!s.playing || isPrivate(s.pkg)) continue;
    rows++;
    const [title, artist] = s.desc.split(', ');
    const who = [[entity(db, 'app', s.pkg), 'player'], ...(artist ? [[entity(db, 'artist', artist), 'artist']] : [])];
    if (addEvent(db, { source: 'media', kind: 'listen', ts: Date.now(), title: artist ? `${title} · ${artist}` : title, ext_id: `${s.pkg}|${s.desc}|${slot}` }, who)) added++;
  }
  return { rows, added };
} };
