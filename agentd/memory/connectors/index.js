// Connectors: one module per data source, each syncing incrementally from a watermark, run on a gentle schedule.
// A connector is { source, every (ms), sync(db, wm, opts) -> {rows, added, wm} }. `wm` is whatever it saved last time
// (e.g. {ts}); it is reset when the source is re-granted, so a revoke+grant re-reads everything.
// Think of it as a mail carrier's route: every few minutes it visits each granted source and picks up only what's new.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import * as S from '../store.js';
import contacts from './contacts.js';
import calendar from './calendar.js';
import calllog from './calllog.js';
import sms from './sms.js';
import notifications from './notifications.js';
import photos from './photos.js';
import usage from './usage.js';
import chrome from './chrome.js';
import media from './media.js';
import alarms from './alarms.js';

export const CONNECTORS = Object.fromEntries([contacts, calendar, calllog, sms, notifications, photos, usage, chrome, media, alarms].map((c) => [c.source, c]));
// The founder's own sources, granted once by default for the demo. A revoke sticks: we only grant sources never seen in the ledger.
export const DEFAULT_GRANTS = Object.keys(CONNECTORS);

// Apps whose data never enters memory, whatever the source: money, passwords, 2FA, system security, and ourselves.
const PRIVATE = /(bank|banking|pay|wallet|finance|credit|crypto|trading|broker|1password|bitwarden|lastpass|keepass|dashlane|authenticator|passw|vault)/i;
const SKIP = new Set(['android', 'com.android.systemui', 'com.android.settings', 'com.google.android.gms', 'com.topjohnwu.magisk', 'com.termux',
  'com.google.android.permissioncontroller', 'com.android.permissioncontroller', 'local.agentos', 'local.agentos.home', 'local.agentos.keys']);
export const isPrivate = (pkg) => !pkg || SKIP.has(pkg) || PRIVATE.test(pkg);

// Replace-or-insert one event by (source, ext_id): daily totals and "now playing" rows change after they are first written.
export function upsertEvent(db, e, who) {
  db.prepare('DELETE FROM event WHERE source=? AND ext_id=?').run(e.source, e.ext_id);
  return S.addEvent(db, e, who);
}

// Texts, calls and photos can be deleted on the phone. Once an hour, diff our copies against the provider's live ids
// (one id-only query each) and drop the gone ones. Returns {removed, wm} to merge into the connector's result.
export function pruneHourly(db, source, wm, ids) {
  if (wm.pruned && Date.now() - wm.pruned < 60 * 60e3) return { removed: 0, wm: { pruned: wm.pruned } };
  return { removed: S.prune(db, source, ids()), wm: { pruned: Date.now() } };
}

const onPhone = process.platform === 'android';
// Raw bytes from a root command (for copying another app's SQLite file). adb exec-out keeps binary intact; adb shell wouldn't.
export const rootBytes = (cmd, fixture) => process.env.MEMORY_FIXTURES ? readFileSync(join(process.env.MEMORY_FIXTURES, fixture)) : onPhone ? execFileSync('su', ['-c', cmd], { maxBuffer: 256 << 20, timeout: 60_000 })
  : execFileSync('adb', [...(process.env.ANDROID_SERIAL ? ['-s', process.env.ANDROID_SERIAL] : []), 'exec-out', `su -c '${cmd}'`], { maxBuffer: 256 << 20, timeout: 60_000 });

const wmKey = (s) => `wm:${s}`;
export const watermark = (db, source) => JSON.parse(db.prepare('SELECT v FROM meta WHERE k=?').get(wmKey(source))?.v || 'null');

// Sync one granted source. force: ignore the watermark. Returns {source, rows, added, ms} and never leaves a half-written batch.
export function sync(db, source, { force = false, ...opts } = {}) {
  const c = CONNECTORS[source];
  if (!c) throw new Error(`no connector for ${source}`);
  S.requireGrant(db, source);
  const grant = S.active(db, source).ledger_id;
  let wm = watermark(db, source);
  if (force || wm?.grant !== grant) wm = { grant };
  const t0 = Date.now();
  const r = S.tx(db, () => {
    const r = c.sync(db, wm, opts);
    db.prepare('INSERT OR REPLACE INTO meta(k,v) VALUES(?,?)').run(wmKey(source), JSON.stringify({ ...(r.wm || wm), grant, at: Date.now(), rows: r.rows, added: r.added }));
    S.rebuildRelations(db);
    return r;
  });
  return { source, rows: r.rows, added: r.added, ...(r.removed && { removed: r.removed }), ms: Date.now() - t0 };
}

// Every granted connector that is due (or all of them). Errors are reported per source, never thrown.
export function syncAll(db, { force = false, due = false, log = () => {} } = {}) {
  const out = [];
  for (const c of Object.values(CONNECTORS)) {
    if (!S.active(db, c.source)) continue;
    const last = watermark(db, c.source)?.at || 0;
    if (due && !force && Date.now() - last < c.every) continue;
    try { out.push(sync(db, c.source, { force })); } catch (e) { out.push({ source: c.source, error: e.message }); }
    log(`memory sync ${JSON.stringify(out.at(-1))}`);
  }
  return out;
}

export function grantDefaults(db, actor = 'user:default') {
  const seen = new Set(S.consentState(db).map((c) => c.source));
  return DEFAULT_GRANTS.filter((s) => !seen.has(s)).map((s) => (S.grant(db, s, { actor, note: 'granted by default for the owner; revoke any time' }), s));
}

const lowBattery = () => { try { const o = execFileSync('dumpsys', ['battery'], { encoding: 'utf8', timeout: 5000 }); return +o.match(/level: (\d+)/)?.[1] < 15 && !/status: [25]/.test(o); } catch { return false; } };

// agentd: grant the defaults, then once a minute run whatever is due. Skipped on low battery. The timer never keeps node alive.
export function start({ db = S.open(), log = () => {}, firstAfter = 20_000 } = {}) {
  db.exec('PRAGMA busy_timeout=5000');
  // Under the Magisk supervisor node has no TZ, so dumpsys local times ("2026-10-27 15:56:19") would parse as UTC.
  if (onPhone && !process.env.TZ) try { process.env.TZ = execFileSync('getprop', ['persist.sys.timezone'], { encoding: 'utf8' }).trim(); } catch {}
  const granted = grantDefaults(db);
  if (granted.length) log(`memory: default grants ${granted.join(', ')}`);
  let busy = false;
  const tick = () => { if (busy || (onPhone && lowBattery())) return; busy = true; try { syncAll(db, { due: true, log }); } finally { busy = false; } };
  setTimeout(tick, firstAfter).unref();
  setInterval(tick, 60_000).unref();
  return { db, tick, now: (source) => (source ? [sync(db, source, { force: true })] : syncAll(db, { force: true, log })) };
}
