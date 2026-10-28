// World 2 = world.js plus the nuance cases memory v2 is for: two Joes, nickname vs full name, "Mom" vs her contact name,
// same phone on two apps (= one person), same name on two platforms (= not enough), a shared group chat with a bare
// first name, a move (Provo → Boise), a job change, changed preferences, owner notes, and trivia that must NOT be kept.
// Everything is synthetic. Times are relative to `now`.
import * as S from '../store.js';
import { noteMessage } from '../index.js';
import { build as build1 } from './world.js';

export function build(db, now = Date.now()) {
  build1(db, now);
  const day0 = new Date(now); day0.setHours(0, 0, 0, 0);
  const at = (d, hm) => { const [h, m] = hm.split(':').map(Number); const t = new Date(day0); t.setDate(t.getDate() + d); t.setHours(h, m, 0, 0); return t.getTime(); };
  const P = (src, name, aliases) => S.person(db, src, name, aliases);
  const app = (n) => S.entity(db, 'app', n);
  let seq = 1000;
  const ev = (source, kind, ts, { title, body, ext, who = [], end } = {}) =>
    S.addEvent(db, { source, kind, ts, ts_end: end ?? null, title, body, ext_id: ext || `${source}-${++seq}` }, who);

  // ---- more contacts
  for (const [name, phone, email] of [['Joe Smith', '+18015550160', 'joe.smith@gmail.com'], ['Rebecca Torres', '+18015550170'],
    ['Kai Nakamura', '+18015550180'], ['Chris Park', '+18015550190', 'chris.park@lumen.io'], ['Aunt Rosa', '+18015550105']])
    P('contacts', name, [{ kind: 'phone', value: phone }, { kind: 'email', value: email }]);

  // ---- SMS
  const sms = (phone, dir, ts, body, ext) => ev('sms', 'message', ts, { body, ext, who: [[P('sms', null, [{ kind: 'phone', value: phone }]), dir === 'in' ? 'from' : 'to'], [app('Messages'), 'app']] });
  sms('+18015550160', 'in', at(-7, '19:00'), "cousin! are you coming to grandma's 80th on the 12th? my mom is making tamales", 'joe_sms');
  sms('+18015550160', 'out', at(-7, '19:20'), "wouldn't miss it, I'll bring the cake", 'joe_sms_out');
  sms('+18015550102', 'in', at(-45, '10:00'), 'come visit me in Provo this weekend, the garden is blooming', 'mom_provo');
  sms('+18015550102', 'in', at(-20, '09:30'), 'we finally moved to Boise! new address is 12 Elm St, Boise ID. come see the new place', 'mom_boise');
  sms('+18015550102', 'out', at(-20, '09:45'), 'congrats!! will visit next month');
  sms('+18015550102', 'out', at(-30, '12:00'), "I'm allergic to shellfish remember, so no shrimp at thanksgiving please", 'owner_shellfish');
  sms('+18015550170', 'in', at(-8, '18:00'), "it's Becca, still on for pottery class Tuesday?", 'becca_sms');
  sms('+18015550111', 'in', at(-40, '13:00'), 'best burger in town is at Lucky 13, we should go', 'sam_burger');
  sms('+18015550111', 'in', at(-15, '13:00'), "fyi I'm vegetarian now so no steakhouse for the bday pls", 'sam_veg');
  sms('+18015550111', 'in', at(-33, '20:00'), 'my birthday is November 8 btw, mark it', 'sam_bday');
  sms('+18015550101', 'in', at(-50, '22:00'), 'ugh another late night at Adobe, this launch is killing me', 'alex_adobe');
  sms('+18015550101', 'in', at(-8, '12:00'), 'big news: I just started at Figma! first day was great', 'alex_figma');
  sms('+18015550101', 'out', at(-5, '23:00'), "I'm working on AgentOS for the hackathon next week, barely sleeping", 'owner_project');
  sms('+18015550104', 'in', at(-2, '08:50'), 'Running 5 min late!', 'priya_late');
  sms('+18015550104', 'out', at(-9, '15:00'), 'honestly Publik is my new favorite coffee place', 'owner_publik');
  sms('+18015550109', 'in', at(-10, '09:00'), 'I retired from Delta last week. 31 years! Golf every day now', 'dad_retired');
  sms('+18015550109', 'in', at(-3, '21:00'), 'k');
  sms('+18015550109', 'in', at(-4, '21:00'), 'lol');
  sms(`+18015553001`, 'in', at(-1, '12:00'), 'Your Uber is arriving now. Look for a silver Prius.');
  sms(`+18015553002`, 'in', at(-2, '19:30'), 'Your table at Takashi is ready.');
  sms('+18015550140', 'in', at(-60, '09:00'), 'Reminder: rent is $1,700 due on the 1st', 'rent_old');

  // ---- WhatsApp as a connector that knows numbers: "Sam P" and "Becca" merge into their contacts by phone
  const wa = (name, phone, ts, body, ext) => ev('whatsapp', 'message', ts, { body, ext, title: `WhatsApp: ${name}`, who: [[P('whatsapp', name, [{ kind: 'phone', value: phone }]), 'from'], [app('WhatsApp'), 'app']] });
  S.grant(db, 'whatsapp', { actor: 'user:eval' });
  wa('Sam P', '+18015550111', at(-6, '11:00'), 'can you send me the photos from the lake?', 'wa_samp');
  wa('Becca', '+18015550170', at(-3, '17:00'), 'bring your own clay tools tuesday, the studio ran out', 'wa_becca');

  // ---- chat apps via notifications (names only)
  const note = (pkg, appName, conversation, sender, text, when, ext, grp = false, dir = 'in') => {
    const id = noteMessage({ pkg, app: appName, conversation, sender, text, when, group: grp, dir }, { db });
    if (id && ext) db.prepare('UPDATE event SET ext_id=? WHERE id=?').run(ext, id);
  };
  const dc = (...a) => note('com.discord', 'Discord', ...a), sl = (...a) => note('com.Slack', 'Slack', ...a), fb = (...a) => note('com.facebook.orca', 'Messenger', ...a);
  dc('Joe', 'Joe', "I'll bring the quickdraws for red rock, got 12 of them", at(-4, '20:00'), 'joe_dc_draws');
  dc('Joe', 'Joe', 'psyched for Red Rock!!', at(-1, '20:30'), 'joe_dc_psyched');
  dc('Red Rock crew 🧗', 'Joe', 'anyone have a spare harness?', at(-1, '19:10'), 'joe_group', true);
  dc('Red Rock crew 🧗', 'Sam', 'I can bring a second rope', at(-1, '19:20'), 'sam_group', true);
  dc('Chris Park', 'Chris Park', 'gg last night, rematch friday?', at(-5, '23:00'), 'chris_dc');
  sl('#eng', 'Chris Park', 'deploy to staging is blocked on the migration', at(-2, '10:30'), 'chris_slack', true);
  sl('Becca T', 'Becca T', 'can you cover my on-call Thursday?', at(-2, '09:00'), 'slack_beccat');
  sl('#eng', 'me', 'Blue Copper is my favorite coffee place in SLC, fight me', at(-25, '12:00'), 'owner_bluecopper', true, 'out');
  fb('Linda Gimenes', 'Linda Gimenes', 'Rosa says bring the pie by 2', at(-2, '12:00'), 'mom_fb2');

  // ---- calendar
  const place = (n) => S.entity(db, 'place', n);
  const attJoe = [P('calendar', 'Joe Smith', [{ kind: 'email', value: 'joe.smith@gmail.com' }]), 'attendee'];
  ev('calendar', 'calendar', at(16, '17:00'), { end: at(16, '21:00'), title: "Grandma's 80th birthday", ext: 'grandma_cal', who: [attJoe, [place("Aunt Rosa's"), 'place']] });
  ev('calendar', 'calendar', at(-2, '18:00'), { end: at(-2, '19:30'), title: 'Pottery class', ext: 'pottery_cal', who: [[place('Clay Studio SLC'), 'place']] });
  ev('calendar', 'calendar', at(5, '18:00'), { end: at(5, '19:30'), title: 'Pottery class', who: [[place('Clay Studio SLC'), 'place']] });
  ev('calendar', 'calendar', at(-9, '18:00'), { end: at(-9, '19:30'), title: 'Pottery class', who: [[place('Clay Studio SLC'), 'place']] });

  // ---- owner notes (memory_remember)
  const me = db.me, noteAt = (d, body, ext, about) => S.addEvent(db, { source: 'user', kind: 'note', ts: at(d, '12:00'), body, ext_id: ext }, [[about ?? me, 'about']]);
  noteAt(-60, 'Joe Smith is my cousin', 'note_joe', db.prepare(`SELECT id FROM entity WHERE type='person' AND name='Joe Smith'`).get().id);
  noteAt(-60, 'My mom is Linda Gimenes', 'note_mom', db.prepare(`SELECT id FROM entity WHERE type='person' AND name='Mom'`).get().id);
  noteAt(-55, 'I prefer window seats on flights', 'note_window');
  noteAt(-50, 'I take my coffee with oat milk', 'note_oat');
  noteAt(-40, 'Kai Nakamura is my climbing partner', 'note_kai', db.prepare(`SELECT id FROM entity WHERE type='person' AND name='Kai Nakamura'`).get().id);
  S.rebuildRelations(db);
  return { now };
}

// Identity gold: pairs of card names that are the SAME person, and pairs that are DIFFERENT (or unknowable: never merge).
export const SAME = [
  ['Sam Patel', 'Sam P'], ['Rebecca Torres', 'Becca'], // same phone number: one card after ingest
  ['Mom', 'Linda Gimenes'], // email name + owner note "My mom is Linda Gimenes"
];
export const DIFF = [
  ['Joe Smith', 'Joe'], // Discord Joe: first name only
  ['Chris Park (Slack)', 'Chris Park (Discord)'], ['Chris Park', 'Chris Park (Discord)'], // same full name on two platforms: not enough
  ['Sam Patel', 'Sam'], ['Sam Lee', 'Sam'], // bare "Sam" in a group chat with two Sams in contacts
  ['Kai Nakamura', 'Kai'], // first name only
  ['Alex Rivera', 'Alex C'], ['Sam Patel', 'Sammy'], ['Sam Lee', 'Sammy'],
];
// name-only evidence (first name + last initial, email prefix): a "maybe" is right (reads combine, actions ask), a merge is not
export const MAYBE_OK = [['Rebecca Torres', 'Becca T'], ['Jordan Wu', 'Jordan W.'], ['Alexis Chen', 'Alex C']];
