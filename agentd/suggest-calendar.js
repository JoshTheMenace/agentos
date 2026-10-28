// A 60-second, read-only CalendarContract Instances snapshot. The existing memory
// connector reads Events, which omits recurring occurrences: never infer gaps from it.
// Run its supported contentQuery surface in a worker so su/content cannot block typing.
import { Worker, isMainThread, parentPort, workerData } from 'node:worker_threads';
import { active } from './memory/store.js';

export function calendarSnapshot(db) {
  let value, worker;
  const refresh = () => {
    const grant = active(db, 'calendar');
    if (grant?.scope !== 'cloud') { value = null; worker?.terminate(); worker = null; return; }
    if (worker) return;
    const from = new Date().setHours(0, 0, 0, 0), to = new Date(from).setDate(new Date(from).getDate() + 15);
    worker = new Worker(new URL(import.meta.url), { workerData: { from, to } });
    const current = worker;
    worker.on('message', (rows) => {
      if (active(db, 'calendar')?.ledger_id === grant.ledger_id) value = { rows, from, to, at: Date.now(), grant: grant.ledger_id };
    });
    worker.on('error', () => {}); // missing permission/provider means unknown, never an empty schedule
    worker.on('exit', () => { if (worker === current) worker = null; });
    worker.unref();
  };
  refresh();
  const timer = setInterval(refresh, 45_000); timer.unref(); // refresh before the 60-second freshness limit
  return {
    get(from, to) {
      const grant = active(db, 'calendar');
      if (grant?.scope !== 'cloud' || value?.grant !== grant.ledger_id) { value = null; return null; }
      return value && Date.now() - value.at < 60_000 && from >= value.from && to <= value.to ? value : null;
    },
    close() { clearInterval(timer); worker?.terminate(); },
  };
}

if (!isMainThread) {
  const { contentQuery } = await import('./memory/ingest.js');
  const { from, to } = workerData;
  if (!contentQuery('content://com.android.calendar/calendars', ['_id'], null, 'calendar_calendars', 'visible=1').length) throw Error('No readable calendars');
  const rows = contentQuery(`content://com.android.calendar/instances/when/${from}/${to}`,
    ['begin', 'end', 'allDay', 'availability', 'eventStatus', 'title', 'eventLocation'], 'begin', 'keyboard_instances', 'visible=1');
  const time = (s, allDay) => allDay === '1' ? +new Date(new Date(+s).toISOString().slice(0, 10) + 'T00:00') : +s;
  const events = rows.filter((e) => e.eventStatus !== '2').map((e) => ({ ts: time(e.begin, e.allDay), te: time(e.end, e.allDay), busy: e.availability !== '1', title: e.title, place: e.eventLocation }));
  if (events.some((e) => !Number.isFinite(e.ts) || !Number.isFinite(e.te) || e.te < e.ts)) throw Error('Invalid calendar instance');
  parentPort.postMessage(events);
}
