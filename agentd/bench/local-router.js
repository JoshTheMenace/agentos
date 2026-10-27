#!/usr/bin/env node
// Accuracy + speed of the local model's router (providers/local.js plan()): request -> one JSON action. No phone actions.
//   LOCAL_URL=http://127.0.0.1:8081 node bench/local-router.js [--runs 1]
// Phrases are NOT the prompt's few-shot examples. A case passes when every expected field matches (titles/queries loosely).
import { plan } from '../providers/local.js';

const CASES = [
  ['Open Spotify and play something', { tool: 'play_music', app: /spotify/i, query: /^$|something|music/i }],
  ['play Taylor Swift on Spotify', { tool: 'play_music', app: /spotify/i, query: /taylor swift/i }],
  ['Put lunch with Sam on my calendar tomorrow at noon', { tool: 'add_event', title: /lunch.*sam/i, day: 'tomorrow', hour: 12, minute: 0 }],
  ['add a meeting with Alex to my calendar on Monday at 9:30 am', { tool: 'add_event', title: /meeting|alex/i, day: 'monday', hour: 9, minute: 30 }],
  ['schedule gym today at 6pm', { tool: 'add_event', title: /gym/i, day: 'today', hour: 18, minute: 0 }],
  ['Set an alarm for 7', { tool: 'set_alarm', hour: 7, minute: 0 }],
  ['set an alarm for 7:45 pm', { tool: 'set_alarm', hour: 19, minute: 45 }],
  ['wake me at 5 am', { tool: 'set_alarm', hour: 5, minute: 0 }],
  ['Start a 5 minute timer', { tool: 'set_timer', hours: 0, minutes: 5, seconds: 0 }],
  ['set a timer for 90 seconds', { tool: 'set_timer', check: (p) => p.hours * 3600 + p.minutes * 60 + p.seconds === 90 }],
  ['timer 1 hour', { tool: 'set_timer', hours: 1, minutes: 0, seconds: 0 }],
  ['Open Slack', { tool: 'open_app', app: /slack/i }],
  ['open messages', { tool: 'open_app', app: /messages/i }],
  ['launch the calculator', { tool: 'open_app', app: /calculator/i }],
  ['open chrome', { tool: 'open_app', app: /chrome/i }],
  ['Search Chrome for the weather in Salt Lake City', { tool: 'web_search', query: /weather.*salt lake city/i }],
  ['look up how tall is mount everest', { tool: 'web_search', query: /mount everest/i }],
  ['open Pigeon and read me the last message from Mom', { tool: 'look', app: /pigeon/i }],
  ['what is my latest email in Gmail', { tool: 'look', app: /gmail/i }],
  ['turn on do not disturb', { tool: 'other' }],
  // held out: phrasings unlike the few-shot examples
  ['remind me on my calendar to call grandma sunday at 4 pm', { tool: 'add_event', title: /grandma/i, day: 'sunday', hour: 16, minute: 0 }],
  ['book coffee with Priya wednesday 8:15am', { tool: 'add_event', title: /coffee|priya/i, day: 'wednesday', hour: 8, minute: 15 }],
  ['I need to get up at 6:15 tomorrow', { tool: 'set_alarm', hour: 6, minute: 15 }],
  ['countdown 3 minutes', { tool: 'set_timer', hours: 0, minutes: 3, seconds: 0 }],
  ['put on some lofi in spotify', { tool: 'play_music', app: /spotify/i, query: /lofi/i }],
  ['go to youtube music', { tool: 'open_app', app: /youtube music/i }],
  ['find pizza places near me', { tool: 'web_search', query: /pizza/i }],
  ['check my slack for messages from Jordan', { tool: 'look', app: /slack/i }],
  ['open the clock app', { tool: 'open_app', app: /clock/i }],
  ['take a selfie', { tool: 'other' }],
];

const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d; };
const ok = (p, want) => Object.entries(want).every(([k, v]) => k === 'check' ? v(p) : v instanceof RegExp ? v.test(String(p[k] ?? '')) : p[k] === v);
const runs = Number(arg('runs', 1)), times = [];
let pass = 0, n = 0;
await plan('open clock').catch(() => {}); // warm the cached prompt; not timed
for (let i = 0; i < runs; i++) for (const [text, want] of CASES) {
  const t0 = Date.now();
  let p; try { p = await plan(text); } catch (e) { p = { error: e.message }; }
  const ms = Date.now() - t0, good = ok(p, want);
  times.push(ms); n++; pass += good;
  console.log(`${good ? 'OK  ' : 'FAIL'} ${String(ms).padStart(5)}ms read ${String(p.read).padStart(4)} | ${text.padEnd(55)} ${JSON.stringify({ ...p, ms: undefined, read: undefined })}`);
}
times.sort((a, b) => a - b);
console.log(`\n${pass}/${n} correct; median ${times[times.length >> 1]} ms, max ${times.at(-1)} ms`);
