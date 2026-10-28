// MediaStore images + videos: when a photo/video was taken and which album (Camera, Screenshots, WhatsApp Images...).
// Never the pixels, never the file name. Location: MediaStore redacts it on Android 10+, and we don't ask for it.
import { contentQuery, liveIds } from '../ingest.js';
import { addEvent, entity, prune } from '../store.js';

const KIND = { images: 'Photo', video: 'Video' };
// MediaStore rejects LIMIT in --sort, so the watermark is date_added (seconds; datetaken is often NULL).
export default { source: 'photos', every: 10 * 60e3, sync(db, wm) {
  let rows = 0, added = 0, ts = wm.ts || 0;
  for (const t of Object.keys(KIND)) {
    for (const r of contentQuery(`content://media/external/${t}/media`, ['_id', 'datetaken', 'date_added', 'bucket_display_name'],
      'date_added ASC', `photos_${t}`, `date_added > ${wm.ts || 0}`)) {
      rows++;
      const when = +r.datetaken || +r.date_added * 1000, album = r.bucket_display_name || 'Unknown';
      const what = /screenshot/i.test(album) ? 'Screenshot' : KIND[t];
      if (addEvent(db, { source: 'photos', kind: 'media', ts: when, title: `${what} · ${album}`, ext_id: `${t[0]}${r._id}` }, [[entity(db, 'album', album), 'album']])) added++;
      ts = Math.max(ts, +r.date_added || 0);
    }
  }
  // Deleted photos: every run (not hourly), so a shot deleted a minute after it was taken is gone from memory by the next
  // 10-minute tick. Two id-only queries, ~0.3 s. MediaStore drops a row as soon as its file is gone.
  const removed = prune(db, 'photos', Object.keys(KIND).flatMap((t) => liveIds(`content://media/external/${t}/media`, `photos_${t}`).map((id) => `${t[0]}${id}`)));
  return { rows, added, removed, wm: { ts } };
} };
