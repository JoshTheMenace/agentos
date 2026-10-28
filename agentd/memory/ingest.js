// Ingestors. Each one checks for an active consent grant first, then reads its source and writes entities/events.
// Phone data comes from root `content query` / `dumpsys` / `cat`: via `su` in Termux, or `adb shell su` on the Mac.
// For tests set MEMORY_FIXTURES=<dir> and each shell command is answered from <dir>/<name>.txt instead.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { basename, join } from 'node:path';
import { addEvent, gcEntities, normPhone, person, entity, rebuildRelations, requireGrant, tx } from './store.js';

const onPhone = process.platform === 'android';
const opt = { encoding: 'utf8', maxBuffer: 64 << 20, timeout: 60_000, stdio: ['ignore', 'pipe', 'pipe'] };
export function root(cmd, fixture) {
  if (process.env.MEMORY_FIXTURES) return readFileSync(join(process.env.MEMORY_FIXTURES, `${fixture}.txt`), 'utf8');
  return onPhone ? execFileSync('su', ['-c', cmd], opt)
    : execFileSync('adb', [...(process.env.ANDROID_SERIAL ? ['-s', process.env.ANDROID_SERIAL] : []), 'shell', `su -c '${cmd}'`], opt);
}

// `content query` prints "Row: N a=1, b=x, c=NULL" (values may contain newlines). We know the column order,
// so each value runs up to ", <next column>=".
export function contentQuery(uri, cols, sort, fixture, where) {
  const out = root(`content query --uri ${uri} --projection ${cols.join(':')}${where ? ` --where "${where}"` : ''}${sort ? ` --sort "${sort}"` : ''}`, fixture);
  if (/^Error|Exception/m.test(out.slice(0, 300))) throw new Error(`content query ${uri} failed: ${out.split('\n')[0]}`);
  return out.split(/^Row: \d+ /m).slice(1).map((chunk) => {
    const row = {}; let rest = chunk.replace(/\n$/, '');
    cols.forEach((c, i) => {
      rest = rest.slice(c.length + 1);
      const end = i < cols.length - 1 ? rest.indexOf(`, ${cols[i + 1]}=`) : rest.length;
      const v = rest.slice(0, end); rest = rest.slice(end + 2);
      row[c] = v === 'NULL' ? null : v;
    });
    return row;
  });
}

const n = (x) => Number(x) || 0;

function contacts(db) {
  let rows = 0;
  for (const r of contentQuery('content://com.android.contacts/data/phones', ['contact_id', 'display_name', 'data1', 'data4'], null, 'contacts_phones'))
    rows++, person(db, 'contacts', r.display_name, [{ kind: 'phone', value: r.data4 || r.data1 }]);
  for (const r of contentQuery('content://com.android.contacts/data/emails', ['contact_id', 'display_name', 'data1'], null, 'contacts_emails'))
    rows++, person(db, 'contacts', r.display_name, [{ kind: 'email', value: r.data1 }]);
  return { rows };
}

function calendar(db, { limit = 1000, days_ahead = 90, since } = {}) { // newest `limit` events starting in (since, now + days_ahead)
  const mine = new Set(contentQuery('content://com.android.calendar/calendars', ['_id', 'ownerAccount'], null, 'calendar_calendars')
    .map((c) => c.ownerAccount?.toLowerCase()));
  const events = contentQuery('content://com.android.calendar/events', ['_id', 'title', 'dtstart', 'dtend', 'eventLocation', 'deleted'],
    `dtstart DESC LIMIT ${limit}`, 'calendar_events', `dtstart < ${Date.now() + days_ahead * 864e5}${since ? ` AND dtstart > ${since}` : ''}`).filter((e) => e.deleted !== '1' && e.dtstart);
  const who = {};
  for (const a of contentQuery('content://com.android.calendar/attendees', ['event_id', 'attendeeName', 'attendeeEmail'], null, 'calendar_attendees')) {
    const email = a.attendeeEmail?.toLowerCase();
    if (!email || mine.has(email)) continue; // that's the user themselves
    (who[a.event_id] ??= []).push([person(db, 'calendar', a.attendeeName || email.split('@')[0], [{ kind: 'email', value: email }]), 'attendee']);
  }
  let added = 0;
  for (const e of events) {
    const place = e.eventLocation?.trim() ? [[entity(db, 'place', e.eventLocation.trim().slice(0, 80)), 'place']] : [];
    if (addEvent(db, { source: 'calendar', kind: 'calendar', ts: n(e.dtstart), ts_end: n(e.dtend) || null, title: e.title, ext_id: e._id },
      [...(who[e._id] || []), ...place])) added++;
  }
  gcEntities(db); // attendees of events past the limit
  return { rows: events.length, added };
}

const CALL = { 1: 'Incoming call', 2: 'Outgoing call', 3: 'Missed call', 4: 'Voicemail', 5: 'Rejected call', 6: 'Blocked call' };
function calllog(db, { limit = 500, since } = {}) {
  const rows = contentQuery('content://call_log/calls', ['_id', 'number', 'name', 'type', 'date', 'duration'], `date DESC LIMIT ${limit}`, 'calllog', since && `date > ${since}`);
  let added = 0;
  for (const r of rows) {
    const id = person(db, 'calllog', r.name, [{ kind: 'phone', value: r.number }]);
    const title = `${CALL[r.type] || 'Call'}${n(r.duration) ? ` (${Math.round(n(r.duration) / 60)} min)` : ''}`;
    if (addEvent(db, { source: 'calllog', kind: 'call', ts: n(r.date), ts_end: n(r.date) + n(r.duration) * 1000, title, ext_id: r._id },
      [[id, r.type === '2' ? 'to' : 'from']])) added++;
  }
  return { rows: rows.length, added };
}

function sms(db, { limit = 200, since } = {}) {
  const rows = contentQuery('content://sms', ['_id', 'address', 'date', 'type', 'body'], `date DESC LIMIT ${Math.min(limit, 1000)}`, 'sms', since && `date > ${since}`);
  let added = 0;
  for (const r of rows) {
    const id = person(db, 'sms', null, [{ kind: 'phone', value: r.address }]);
    if (addEvent(db, { source: 'sms', kind: 'message', ts: n(r.date), body: r.body, ext_id: r._id }, [[id, r.type === '2' ? 'to' : 'from']])) added++;
  }
  return { rows: rows.length, added };
}

// Posted notifications from `dumpsys notification --noredact`. Drops system, banking/2FA-ish packages and OTPs.
const DENY = /^(android|com\.android\.systemui|com\.google\.android\.gms|com\.termux.*|.*(bank|auth|authenticator|wallet|pay|1password|bitwarden|lastpass).*)$/i;
function notifications(db) {
  const out = root('dumpsys notification --noredact', 'notifications');
  let rows = 0, added = 0;
  for (const block of out.split(/NotificationRecord\(/).slice(1)) {
    const pkg = block.match(/pkg=(\S+)/)?.[1];
    const val = (k) => block.match(new RegExp(`android\\.${k}=\\w+ \\((.*)\\)\\s*$`, 'm'))?.[1];
    const title = val('title'), text = val('bigText') || val('text');
    if (!pkg || DENY.test(pkg) || (!title && !text) || /\b(code|otp|passcode|verification)\b/i.test(text || '')) continue;
    rows++;
    const ts = n(block.match(/\bwhen=(\d{12,})/)?.[1]) || Date.now();
    if (addEvent(db, { source: 'notifications', kind: 'notification', ts, title, body: text }, [[entity(db, 'app', pkg), 'app']])) added++;
  }
  return { rows, added };
}

// WhatsApp "Export chat" .txt. No official format: Android "12/31/24, 9:41 PM - Name: text", iOS "[31/12/2024, 21:41:05] Name: text".
// Lines that don't start a message continue the previous one. dd/mm vs mm/dd is guessed from the whole file.
const WA = /^\u200e?\[?(\d{1,2})[/.](\d{1,2})[/.](\d{2,4}),? (\d{1,2}):(\d{2})(?::(\d{2}))?(?:[\s\u202f]?([AaPp])\.?[Mm]\.?)?\]?(?: -|:)? (.*)$/;
export function parseWhatsApp(text) {
  const msgs = [];
  for (const line of text.replace(/\r/g, '').split('\n')) {
    const m = line.match(WA);
    if (!m) { if (msgs.length && line) msgs.at(-1).text += '\n' + line; continue; }
    const [, a, b, y, h, mi, s, ap, rest] = m;
    const sep = rest.indexOf(': ');
    msgs.push({ a: +a, b: +b, y: +y < 100 ? 2000 + +y : +y, h: ap ? (+h % 12) + (/p/i.test(ap) ? 12 : 0) : +h, mi: +mi, s: +(s || 0),
      who: sep > 0 ? rest.slice(0, sep).replace(/[\u200e\u202a\u202c]/g, '').trim() : null, text: (sep > 0 ? rest.slice(sep + 2) : rest).replace(/\u200e/g, '') });
  }
  const dayFirst = msgs.some((m) => m.a > 12);
  return msgs.map(({ a, b, y, h, mi, s, who, text }) => ({
    ts: new Date(y, (dayFirst ? b : a) - 1, dayFirst ? a : b, h, mi, s).getTime(), who, text,
    media: /^<?(Media omitted|image omitted|video omitted|audio omitted|sticker omitted|document omitted)>?$/i.test(text.trim()),
  }));
}

function whatsapp(db, { file, me, chat } = {}) {
  if (!file) throw new Error('whatsapp needs file=<path to exported .txt>');
  chat ??= basename(file).replace(/^WhatsApp Chat (with|-) /i, '').replace(/\.txt$/i, '');
  let msgs = parseWhatsApp(readFileSync(file, 'utf8')).filter((m) => m.who); // system lines have no sender
  const senders = [...new Set(msgs.map((m) => m.who))].filter((s) => s !== chat);
  me ??= senders.find((s) => db.prepare(`SELECT 1 FROM alias WHERE kind='wa_name' AND value=? AND entity_id=?`).get(s, db.me));
  const others = senders.filter((s) => s !== me);
  const group = others.length >= 2 ? entity(db, 'group', chat) : null;
  if (group) msgs = msgs.filter((m) => m.who !== chat); // iOS puts group system lines under the group's name
  else me ??= others[0]; // 1:1 chat: whoever isn't the chat name is me
  if (me) db.prepare('INSERT OR IGNORE INTO alias VALUES(?,?,?,?)').run(db.me, 'wa_name', me, 'whatsapp'); // recognise me in later group imports
  const other = !group ? person(db, 'whatsapp', chat, [{ kind: 'wa_name', value: chat }]) : null;
  let added = 0;
  for (const [i, m] of msgs.entries()) {
    const mine = m.who === me;
    const who = mine ? (group ? [[group, 'group']] : [[other, 'to']])
      : [[person(db, 'whatsapp', m.who, [{ kind: 'wa_name', value: m.who }]), 'from'], ...(group ? [[group, 'group']] : [])];
    if (addEvent(db, { source: 'whatsapp', kind: m.media ? 'media' : 'message', ts: m.ts, body: m.media ? '[media]' : m.text,
      title: `WhatsApp: ${chat}`, ext_id: `${chat}#${i}` }, who)) added++;
  }
  return { rows: msgs.length, added, me: me ? 'detected' : 'unknown (pass me=<your name> for groups)' };
}

// Our own Pigeon app: files/chats.json = [{name, msgs:[{text, me, who?, ts}]}]. `who` means a group message.
function pigeon(db, { file } = {}) {
  // If root lacks CAP_DAC_OVERRIDE (seen when su is nested under another su), read it as the app's own uid instead.
  const D = '/data/data/local.agentos.pigeon';
  const chats = JSON.parse(file ? readFileSync(file, 'utf8') : root(`cat ${D}/files/chats.json 2>/dev/null || su $(stat -c %u ${D}) -c cat ${D}/files/chats.json`, 'pigeon'));
  let rows = 0, added = 0;
  for (const c of chats) {
    const isGroup = c.msgs.some((m) => m.who);
    const chatId = isGroup ? entity(db, 'group', c.name) : person(db, 'pigeon', /unknown/i.test(c.name) ? null : c.name, [{ kind: 'pigeon_name', value: c.name }]);
    for (const [i, m] of c.msgs.entries()) {
      rows++;
      const who = m.me ? [[chatId, isGroup ? 'group' : 'to']]
        : isGroup ? [[person(db, 'pigeon', m.who, [{ kind: 'pigeon_name', value: m.who }]), 'from'], [chatId, 'group']] : [[chatId, 'from']];
      if (addEvent(db, { source: 'pigeon', kind: 'message', ts: n(m.ts), body: m.text, title: `Pigeon: ${c.name}`, ext_id: `${c.name}#${i}` }, who)) added++;
    }
  }
  return { rows, added };
}

export const INGESTORS = { contacts, calendar, calllog, sms, notifications, whatsapp, pigeon };

export function ingest(db, source, opts = {}) {
  if (!INGESTORS[source]) throw new Error(`no ingestor for ${source}`);
  requireGrant(db, source);
  const t0 = Date.now();
  const r = tx(db, () => { const r = INGESTORS[source](db, opts); rebuildRelations(db); return r; });
  return { source, ...r, ms: Date.now() - t0 };
}
