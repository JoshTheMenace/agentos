/* AgentOS home (A2 Calm Glass). One file, no framework. The native shell exposes `Native` (see launcher/Home.java);
   in a desktop browser the shims below keep the page alive for styling work. */
'use strict';
const $ = (s, r = document) => r.querySelector(s), $$ = (s, r = document) => [...r.querySelectorAll(s)];
const el = (tag, cls, html) => { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; };
// Re-render without replaying animations: cards that are still the same card (same classes, same shape) keep their DOM
// and only get new text/attributes/handlers; anything else is swapped in fresh (and animates in).
const same = (o, n) => o.className === n.className || (!!o.dataset?.k && o.dataset.k === n.dataset?.k); // keyed cards (data-k) stay put through a state change
function morph(o, n) {
  if (o.nodeName !== n.nodeName || o.childNodes.length !== n.childNodes.length || (o.nodeType === 1 && !same(o, n))) return o.replaceWith(n);
  if (o.nodeType === 3) { if (o.data !== n.data) o.data = n.data; return; }
  if (o.nodeType !== 1) return;
  for (const a of n.attributes) if (o.getAttribute(a.name) !== a.value) o.setAttribute(a.name, a.value);
  const nc = [...n.childNodes]; // a copy: replaceWith() below moves nodes out of n, which would shift a live list
  o.onclick = n.onclick; [...o.childNodes].forEach((c, i) => morph(c, nc[i]));
}
function swap(box, ...nodes) {
  const old = [...box.children];
  if (old.length === nodes.length && old.every((o, i) => same(o, nodes[i]))) old.forEach((o, i) => morph(o, nodes[i]));
  else box.replaceChildren(...nodes);
}
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const N = window.Native || { // browser shims
  info: () => JSON.stringify({ pkg: 'browser', dev: true, top: 28, bottom: 0, ime: 0 }), token: () => null, launch: () => false, haptic: () => {},
  settings: () => {}, shade: () => {}, calendarNext: () => null, get: (id) => setTimeout(() => app.rpcDone(id, { ok: false, error: 'browser' })),
  phone: (id) => setTimeout(() => app.rpcDone(id, { ok: false, error: 'browser' })), vaultAdd: () => {}, openApp: () => {}, appSummaries: () => '{}',
  apps: () => JSON.stringify([['local.agentos.pigeon', 'Pigeon'], ['local.agentos.beacon', 'Beacon'], ['com.android.chrome', 'Chrome'], ['com.android.camera2', 'Camera'], ['com.google.android.calendar', 'Calendar']].map(([pkg, label]) => ({ pkg, label }))),
};
const app = window.app = {};
const info = JSON.parse(N.info());
const hap = (k) => N.haptic(k || 'click');
const ICON = (pkg) => `https://agentos.home/icon/${pkg}`;
// App icon <img>; if the shell can't serve it (Mac, uninstalled app) the box shows the app's initial instead.
const icon = (pkg, extra = {}) => Object.assign(new Image(), { src: ICON(pkg), alt: '', ...extra, onerror() { this.hidden = true; this.parentNode && (this.parentNode.textContent = (appName(pkg)[0] || '?').toUpperCase()); } });
const ICOHTML = (pkg) => `<img src="${ICON(pkg)}" alt="" onerror="this.hidden=true;this.parentNode.textContent='${esc((appName(pkg)[0] || '?').toUpperCase())}'">`;
const AGENTD = 'ws://127.0.0.1:8787/', GRAPH = 'http://127.0.0.1:8787/memory/graph.json';
const FORBIDDEN = ['Settings', 'System UI', 'Magisk', 'Installer', 'AgentOS'];

// ---------- insets (edge to edge; the shell reports status/nav/IME heights in CSS px) ----------
app.insets = (t, b, i) => { const r = document.documentElement.style; r.setProperty('--top', t + 'px'); r.setProperty('--bottom', b + 'px'); r.setProperty('--ime', i + 'px'); };
app.insets(info.top, info.bottom, info.ime);

// ---------- agentd WebSocket ----------
const bus = {}; const on = (t, f) => (bus[t] ??= []).push(f); const emit = (m) => (bus[m.type] || []).forEach((f) => f(m));
let ws, wsOk = false;
function connect() {
  const tok = N.token('agentd');
  if (!tok) return;
  ws = new WebSocket(AGENTD, 'agentos.bearer.' + tok);
  ws.onopen = () => { wsOk = true; emit({ type: 'ws.open' }); };
  ws.onclose = () => { wsOk = false; emit({ type: 'ws.close' }); setTimeout(connect, 3000); };
  ws.onmessage = (e) => { try { emit(JSON.parse(e.data)); } catch {} };
}
const send = (m) => wsOk ? (ws.send(JSON.stringify(m)), true) : false;
connect();

// ---------- tool server RPC through agentd's allow-listed passthrough (firewall.set waits on the native tap card) ----------
let rid = 0; const pend = {};
const rpc = (method, params = {}) => new Promise((ok) => { const id = String(++rid); pend[id] = ok; if (!send({ type: 'rpc', id, method, params })) app.rpcDone(id, { ok: false, error: 'offline' }); });
// The owner line: this app's own uid straight to the broker (Blockers taps, Passwords). The broker knows it's the owner by uid, so no card.
const phone = (method, params = {}) => new Promise((ok) => { const id = String(++rid); pend[id] = ok; N.phone ? N.phone(id, method, JSON.stringify(params)) : app.rpcDone(id, { ok: false, error: 'no owner line' }); });
const own = async (method, params) => { const r = await phone(method, params); return r?.error === 'no owner line' ? rpc(method, params) : r; }; // owner line first (works while a plugin is off), else agentd
const nget = (path) => new Promise((ok) => { const id = String(++rid); pend[id] = ok; N.get(id, path); }); // agentd GET via the shell (cross-origin)
app.rpcDone = (id, r) => { pend[id]?.(r); delete pend[id]; };
on('rpc.result', (m) => app.rpcDone(m.id, m.result));

// ---------- state ----------
const S = { active: null, turn: false, pending: null, apps: [], names: {}, provider: localStorage.provider || 'codex', audit: [], sugg: [], step: null, home: null, trackers: null, tasks: new Map(), mods: null, vault: null, sched: [], queue: [] }; // tasks = agentd background workers (task.update) // active = latest task.state; home = a turn started from a home suggestion
const appName = (pkg) => S.names[pkg] || (pkg || '').split('.').pop().replace(/^\w/, (c) => c.toUpperCase());
const fmtT = (ts, sec) => new Date(ts).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', ...(sec ? { second: '2-digit' } : {}) }).replace(/\s?[AP]M/i, '');
const TOOLN = { act: 'Acting', open_app: 'Opening an app', tap: 'Tapping', type: 'Typing', swipe: 'Scrolling', back: 'Going back', screen: 'Reading the screen', wait: 'Waiting', 'notifications.reply': 'Replying', 'notifications.list': 'Reading notifications', notifications_reply: 'Replying', schedule_task: 'Scheduling', list_scheduled: 'Checking the schedule', cancel_scheduled: 'Cancelling a schedule', 'vault.fill': 'Signing in', vault_fill: 'Signing in', 'trackers.block': 'Blocking trackers', 'trackers.list': 'Scanning for trackers', 'firewall.set': 'Setting the firewall', 'app.mod.add': 'Adding a button', memory_search: 'Remembering', memory_recall: 'Remembering', 'task.start': 'Starting', phone_unlock: 'Unlocking', 'phone.unlock': 'Unlocking', build_app: 'Designing your app', list_my_apps: 'Checking your apps', remove_app: 'Removing an app' };
const toolName = (n) => TOOLN[n] || String(n || '').replace(/[._]/g, ' ').replace(/^\w/, (c) => c.toUpperCase());
const stepText = (s) => TOOLN[s] || s;
const ago = (ms) => { const s = Math.max(0, Math.round((Date.now() - ms) / 1000)); return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`; };
const one = (s) => String(s).replace(/\b1 (day|hour|minute|min|week)s\b/g, '1 $1'); // "1 days ago" -> "1 day ago"
const dur = (ms) => ms > 0 && ms < 30000 ? ` · ${(ms / 1000).toFixed(1)} s` : ''; // the stopwatch (DEMO-WOW #4): a measured finish; past 30 s nothing
const short = (s, n = 48) => (s = String(s || '')).length > n ? s.slice(0, n - 1) + '…' : s;

// ---------- clock ----------
function clock() {
  const d = new Date();
  $('#time').textContent = fmtT(d);
  $('#date').textContent = d.toLocaleDateString([], { weekday: 'long', month: 'long', day: 'numeric' });
}
clock(); setInterval(clock, 1000);

// ---------- audit lines -> text ----------
const auditLine = (e) => { // one human line per audit entry: what happened, in which app, and (as the colour) whether it was fine, approved or stopped
  const raw = /^[A-Z]\w*(Layout|View|Group|Bar|Pane|Container)$/.test(e.target || '') || /^(android|com|androidx)\./.test(e.target || ''); // class name, not a label
  const app_ = appName(e.pkg), tgt = e.target && !raw ? `"${short(e.target, 24)}"` : '', inApp = app_ ? ` in ${app_}` : '';
  const d = e.decision || '', bad = /denied|blocked|refused/.test(d), c = bad ? 'c' : d === 'approved' ? 'g' : 'a';
  const code = /code|canary/i.test(e.reason || ''), names = (e.target || '').replace(/^\w+ trackers: /, '').replace(/ \(.*$/, '');
  switch (e.tool) {
    case 'task.start': return [d === 'denied' ? `Access not granted · ${short(e.goal, 36)}` : 'Started · ' + short(e.goal, 40), bad ? 'c' : 'a'];
    case 'task.stop': return [e.steps ? `Finished · ${e.steps} step${e.steps === 1 ? '' : 's'}` : 'Finished', ''];
    case 'app.deepmod.preview': return [`Previewed a deep mod${inApp}`, c];
    case 'app.deepmod.apply': case 'app.deepmod.integrate': return [bad ? `Deep mod refused${inApp} · ${short(e.reason, 30)}` : `Applied a deep mod${inApp}`, c];
    case 'app.deepmod.apply.review': return [`Reviewed a deep mod${inApp}`, c];
    case 'app.deepmod.remove': case 'app.deepmod.rollback': return [`Removed a deep mod${inApp}`, ''];
    case 'app.deepmod.disable': return [`Deep mods off · ${/disconnected/.test(e.reason || '') ? 'app closed' : short(e.reason || '', 30)}`, ''];
    case 'task.pause': return [/human/.test(e.reason || '') ? 'Paused · you touched the screen' : 'Paused', ''];
    case 'task.resume': return ['Resumed', ''];
    case 'tap': return [bad ? (d === 'blocked' && !e.task ? `Stopped a tap outside a task${inApp}` : `Tap ${tgt} not approved${inApp}`) : `Tapped ${tgt}${inApp}`, c];
    case 'type': return [code ? `Stopped a message with a code from the screen${inApp}` : bad ? `Typing not approved${inApp}` : e.text ? `Typed "${short(e.text, 26)}"${inApp}` : `Typed${inApp}`, c];
    case 'press': return [`Pressed ${e.target || 'a key'}`, 'a'];
    case 'scroll': return [`Scrolled${inApp}`, 'a'];
    case 'intent': return [bad ? `Stopped an action outside a task${inApp}` : `${app_} · ${short(e.target, 30)}`, c];
    case 'open_app': return [bad ? `Stopped opening ${app_} outside a task` : `Opened ${app_}`, c];
    case 'firewall.set': return [d === 'approved' ? `Firewall · ${short(e.target || e.mode, 24)} · ${app_}` : `Firewall change not approved · ${app_}`, c];
    case 'firewall.clear': return ['Firewall cleared', c];
    case 'guard.flag': return [`Scam message caught${inApp} · hidden from the agent`, 'c'];
    case 'tracker.blocked': return [`Blocked ${short(e.domain, 28)} · ${app_}`, 'g'];
    case 'trackers.block': return [d === 'approved' ? `Blocked ${short(names, 30)} · ${app_}` : `Blocking trackers not approved · ${app_}`, c];
    case 'trackers.unblock': return [d === 'approved' ? `Unblocked trackers · ${app_}` : `Unblock not approved · ${app_}`, c];
    case 'vault.fill': return [d === 'approved' ? `Signed in to ${app_} with a saved password` : /phishing|belongs/.test(e.reason || '') ? `Stopped a password going to the wrong app · ${app_}` : `Sign-in to ${app_} not filled`, c];
    case 'vault.add': return [`Saved a password · ${short(e.entry || e.target, 24)}`, 'g'];
    case 'vault.remove': return ['Removed a saved password', ''];
    case 'notifications.reply': return [code ? 'Stopped a reply with a code from the screen' : bad ? `${tgt} not approved` : `${short(e.target, 20)} · "${short(e.text, 30)}"`, c];
    case 'notifications.autoreply': return [e.text === '(all)' ? 'Auto-reply off' : bad ? `Auto-reply not approved` : `Auto-reply on · "${short(e.text, 30)}"`, c];
    case 'phone.unlock': return ['Unlocked for your scheduled task · relocks after', 'b'];
    case 'schedule.created': return [`Scheduled · ${short(e.goal, 30)} · ${short(e.target, 22)}`, 'b'];
    case 'schedule.done': return [`Scheduled task done · ${short(e.goal, 36)}`, 'g'];
    case 'schedule.cancelled': return [`Scheduled task cancelled · ${short(e.goal, 30)}`, ''];
    case 'schedule.missed': return [`Missed a scheduled task · ${short(e.goal, 30)}`, 'c'];
    case 'app.mod.apply': return [bad ? `Refused a mod · ${short(e.reason, 40)}` : `Added "${e.label || ''}" to ${app_}`, c];
    case 'app.mod.press': return [`You used "${e.id || 'a mod'}"${inApp}`, ''];
    case 'app.mod.remove': return ['Removed a mod', ''];
    case 'audit.break': return ['Audit chain break marked', 'c'];
    case 'screen.capture': return [bad ? `Didn't look at ${app_ || 'the screen'} · ${short(e.reason, 34)}` : `Looked at your screen${inApp}${/blacked/.test(e.reason || '') ? ' · private parts hidden' : ''}`, bad ? 'c' : 'a'];
    case 'image.sent': return [`${{ camera: 'Camera photo', photos: 'Your photo', live: 'Live camera frame' }[e.target] || 'A picture'} sent to ${e.model || 'the AI'}${e.bytes ? ` · ${Math.round(e.bytes / 1024)} KB` : ''}`, 'b'];
    case 'remote.read': return [`${e.from || 'A remote assistant'} ${e.target === 'memory_lookup' ? 'searched your memory' : 'read your latest messages'}`, 'b'];
    default: { // direct Android API tools (contacts.find, sms.send, calendar.add …): "<verb> <what> · <where>"
      const m = /^(contacts|sms|calls|call|calendar|clock|maps|alarms?|device|media)\.(\w+)$/.exec(e.tool);
      if (m) {
        const where = { contacts: 'Contacts', sms: 'Messages', calls: 'Phone', call: 'Phone', calendar: 'Calendar', clock: 'Clock', maps: 'Maps', alarm: 'Clock', alarms: 'Clock', device: 'Settings', media: 'Media' }[m[1]];
        const arg = short(String(e.target || '').replace(e.tool, '').trim(), 30), t = (e.target || '').startsWith(e.tool) ? '' : short(e.target, 34);
        const what = m[2] === 'list' ? `Read recent ${m[1] === 'sms' ? 'texts' : m[1]}` : m[2] === 'find' ? `Looked up ${arg || 'a ' + m[1].replace(/s$/, '')}` : m[2] === 'send' ? `${t || 'Sent a text'}${e.text ? ` · "${short(e.text, 24)}"` : ''}` : m[1] === 'media' ? ({ pause: 'Paused playback', play: 'Resumed playback', next: 'Skipped a track', previous: 'Went back a track', status: 'Checked what is playing' })[arg] || `Media ${arg}` : m[2] === 'status' ? 'Checked phone status' : m[1] === 'device' ? `Set ${short(e.target, 30)}` : t || `${m[2].replace(/^\w/, (ch) => ch.toUpperCase())} ${arg}`;
        return [`${what} · ${where}`, c];
      }
      return [`${e.tool.replace(/[._]/g, ' ').replace(/^\w/, (ch) => ch.toUpperCase())} ${tgt} ${app_ || ''}`.trim(), c];
    } return [`${e.tool.replace(/[._]/g, ' ').replace(/^\w/, (ch) => ch.toUpperCase())} ${tgt} ${app_ || ''}`.trim(), c];
  }
};

// One snapshot on connect/resume; after that the broker's events (via agentd) keep it live: no polling.
const live = () => { renderStatus(); renderNow(); renderCenter(); if ($('#perms').classList.contains('on')) renderGrants(); };
const alive = (t) => t && t.expires > Date.now() ? t : null;
async function refresh() {
  const [a, l] = await Promise.all([rpc('audit.tail', { n: 500 }), rpc('approval.list')]);
  if (a?.entries) { S.audit = a.entries; S.since = a.since || 0; } // since = the last demo.reset line (counts start after it)
  if (l?.approvals) { S.active = alive(l.task); S.pending = l.approvals[0] || null; }
  live(); renderCalendar(); if (!S.mods) loadMods(); if (!S.vault) loadVault();
}
on('task.state', (m) => { if (m.state === 'stopped') { if (S.active?.task_id === m.task_id) S.active = null; } else S.active = alive(m); live(); });
const MOMENT = { 'notifications.reply': (e) => `Replied${e.target ? ' to ' + short(e.target, 20) : ''} · ${appName(e.pkg)}`, 'notifications.autoreply': (e) => `Auto-replied${e.target ? ' to ' + short(e.target, 20) : ''} · ${appName(e.pkg)}`, 'vault.fill': (e) => `Signed in to ${appName(e.pkg)} with your fingerprint`, 'trackers.block': (e) => `Blocked trackers · ${appName(e.pkg)}`, 'schedule.created': (e) => `Scheduled${e.when ? ' for ' + e.when : ''}`, 'app.mod.add': (e) => `Added “${short(e.label || 'a button', 20)}” to ${appName(e.pkg)}` };
S.moments = []; // things agents just did for you (from the audit): a 45 s card on Home
on('audit.append', (m) => {
  S.audit = [...S.audit.slice(-599), m]; if (m.tool === 'demo.reset') S.since = m.ts;
  const f = MOMENT[m.tool]; if (f && (!m.decision || m.decision === 'approved')) { const x = { text: f(m), ts: m.ts || Date.now() }; S.moments = [x, ...S.moments].slice(0, 2); hap('heavy'); setTimeout(() => { S.moments = S.moments.filter((y) => y !== x); renderNow(); }, 45000); }
  renderNow(); renderCenter();
});
const momentCard = (x) => el('div', 'glass now done', `<div class="hd"><span class="ok done"><svg width="12" height="12"><use href="#i-check"/></svg></span><div><div class="t">${esc(x.text)}</div><div class="s">${fmtT(x.ts)} · by your agent</div></div></div><div class="bar2"><i></i></div>`);
on('guard.flag', (m) => { S.audit = [...S.audit.slice(-199), { ...m, tool: 'guard.flag', ts: Date.now() }]; renderCenter(); });
setInterval(() => S.active && live(), 30000); // the minutes-left countdown

// ---------- home ----------
function renderStatus() {
  const orb = $('#sorb'), corb = $('#corb');
  const ws_ = [...S.tasks.values()].filter((w) => WORKING.has(w.state)), busy = S.turn || S.active || ws_.length;
  const cls = S.pending || ws_.some((w) => w.state === 'waiting') ? 'approval' : busy ? 'working' : '';
  orb.className = corb.className = 'orb ' + cls;
  $('#status').textContent = cls === 'approval' ? 'Waiting for you' : busy ? (ws_.length > 1 ? `${ws_.length} tasks running` : 'Working') : wsOk ? 'Ready' : 'Agent offline';
}
// Now: the live task (goal, current step, gentle progress). Hidden when nothing runs; a finished home task lingers with its answer.
const WORKING = new Set(['queued', 'running', 'waiting']);
function workerCard(w) {
  const on = WORKING.has(w.state), cls = w.state === 'waiting' ? 'approval' : on ? 'working' : w.state === 'done' ? 'done' : 'alert';
  const meta = [w.provider === 'local' ? 'On device' : '', on ? `${ago(w.created)}${w.steps ? ` · ${w.steps} step${w.steps > 1 ? 's' : ''}` : ''}` : w.state === 'done' ? dur(w.timing?.total_ms).slice(3) : ''].filter(Boolean).join(' · ');
  const c = el('button', `glass now ${cls}${on ? '' : ' done'}${w.gone ? ' gone' : ''}`, `<div class="hd">${on ? `<span class="orb ${cls}"></span>` : `<span class="ok ${cls}"><svg width="12" height="12"><use href="#i-${w.state === 'done' ? 'check' : 'x'}"/></svg></span>`}<div><div class="t">${esc(short(w.goal, 40))}</div><div class="s">${esc(short(on ? stepText(w.step) : w.result || w.error || stepText(w.step), 80))}</div></div>${meta ? `<span class="meta">${meta}</span>` : ''}${on ? `<button class="ib red" data-a="cancel"><svg width="14" height="14"><use href="#i-stop"/></svg></button>` : ''}</div><div class="bar2"><i></i></div>`);
  c.dataset.k = w.goal; c.onclick = (e) => { hap(); if (e.target.closest('[data-a]')) { send({ type: 'task.cancel', id: w.id }); return; } open('sheet'); };
  return c;
}
on('task.update', (m) => {
  const w = m.task; if (!w?.id) return;
  if (w.provider) device(w);
  for (const k of S.tasks.keys()) if (k.startsWith('opt')) S.tasks.delete(k); // the optimistic placeholder
  const prev = S.tasks.get(w.id); S.tasks.set(w.id, w);
  if (m.why === 'created') { body.append(el('div', 'msg me', esc(w.goal))); aiMsg = null; askAt = w.created; sheetEmpty(); } // the sheet becomes the task's transcript ("how's it going")
  if (!WORKING.has(w.state)) { if (prev && WORKING.has(prev.state)) { hap(w.state === 'done' ? 'heavy' : 'click'); body.append(el('div', 'msg ' + (w.state === 'done' ? 'ai' : 'err'), esc(w.result || w.error || stepText(w.step)))); scrollDown(); S.turn = false; S.step = null; steps = {}; setSheet({ done: 'Done', failed: 'Didn’t finish' }[w.state] || 'Stopped', w.state === 'done' ? 'done' : 'alert', w.timing?.total_ms); } setTimeout(() => { if (S.tasks.get(w.id) === w) { w.gone = true; renderNow(); setTimeout(() => { S.tasks.delete(w.id); renderNow(); renderCenter(); }, 400); } }, 45000); }
  renderStatus(); renderNow(); renderCenter(); renderVoiceTask();
});
setInterval(() => { if ([...S.tasks.values()].some((w) => WORKING.has(w.state))) { renderNow(); renderVoiceTask(); } }, 1000); // elapsed time
// Scheduled tasks (agentd schedule.js): the next ones show on Home when nothing runs; x cancels.
on('schedule.list', (m) => { S.sched = m.items || []; renderNow(); renderCenter(); });
const whenLabel = (at) => { // "in 18 min" | "Today 7:00 PM" | "Tomorrow 11:00 AM" | "Friday 9:00 AM"
  if (!at) return ''; const mins = Math.round((at - Date.now()) / 60000), d = new Date(at), days = Math.floor((at - new Date().setHours(0, 0, 0, 0)) / 864e5);
  const t = d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  return mins < 1 ? 'Now' : mins < 60 ? `in ${mins} min` : days === 0 ? `Today ${t}` : days === 1 ? `Tomorrow ${t}` : `${d.toLocaleDateString([], { weekday: 'long' })} ${t}`;
};
function schedCard(x) {
  const c = el('div', 'glass now sched', `<div class="hd"><span class="appic" style="background:var(--blue)"><svg width="18" height="18" style="color:#fff"><use href="#i-cal"/></svg></span><div><div class="t">${esc(short(x.prompt.replace(/^(remind me to|remember to)\s+/i, (m) => m[0].toUpperCase() + m.slice(1)), 40))}</div><div class="s">${esc(whenLabel(x.at) || x.next)}${x.repeat && x.repeat !== 'none' ? ' · ' + esc(x.repeat) : ''} · scheduled</div></div><button class="ib"><svg width="14" height="14"><use href="#i-x"/></svg></button></div>`);
  $('button', c).onclick = () => { hap(); send({ type: 'schedule.cancel', id: x.id }); };
  return c;
}
on('task.list', (m) => { // running ones, plus anything that finished in the last minute (the done card survives a trip through another app)
  S.tasks = new Map((m.tasks || []).filter((w) => WORKING.has(w.state) || Date.now() - w.created < 60000).map((w) => [w.id, w]));
  for (const w of S.tasks.values()) if (!WORKING.has(w.state)) setTimeout(() => { if (S.tasks.get(w.id) === w) { S.tasks.delete(w.id); renderNow(); } }, 45000);
  renderNow(); renderCenter();
});
function renderNow() {
  const box = $('#now'), t = S.active, h = S.home, ws_ = [...S.tasks.values()].sort((a, b) => WORKING.has(b.state) - WORKING.has(a.state) || b.created - a.created);
  const moments = S.moments.filter((x) => !ws_.some((w) => !WORKING.has(w.state) && Math.abs(w.created - x.ts) < 300000)).map(momentCard); // a done worker card already tells it
  if (ws_.length) return swap(box, ...ws_.slice(0, 3).map(workerCard), ...moments);
  if (moments.length && !t && !S.turn) return swap(box, ...moments, ...S.sched.slice(0, 2).map(schedCard));
  if (!t && !S.turn) { if (h?.done) { const c = el('button', 'glass now done', `<div class="hd"><span class="orb done"></span><div><div class="t">${esc(short(h.goal, 40))}</div><div class="s">${esc(short(h.text || 'Done', 60))}</div></div></div>`); c.onclick = () => { hap(); open('sheet'); }; return swap(box, c); } return swap(box, ...S.sched.slice(0, 2).map(schedCard)); }
  const goal = short(t?.goal || h?.goal || 'Thinking', 40), last = t && S.audit.filter((e) => e.task === t.task_id && !/^task\./.test(e.tool)).pop();
  const step = S.pending ? 'Waiting for you' : t?.paused ? 'Paused' : S.step || (last ? auditLine(last)[0] : 'Starting'), cls = S.pending ? 'approval' : 'working';
  const c = el('button', 'glass now ' + cls, `<div class="hd"><span class="orb ${cls}"></span><div><div class="t">${esc(goal)}</div><div class="s">${esc(step)}${t ? ' · ' + esc(t.apps.map(appName).join(', ')) : ''}</div></div>${t ? `<button class="ib" data-a="${t.paused ? 'resume' : 'pause'}"><svg width="14" height="14"><use href="#i-${t.paused ? 'play' : 'pause'}"/></svg></button>` : ''}<button class="ib red" data-a="stop"><svg width="14" height="14"><use href="#i-stop"/></svg></button></div><div class="bar2"><i></i></div>`);
  c.onclick = async (e) => { const a = e.target.closest('[data-a]')?.dataset.a; hap(); if (!a) return open('sheet'); if (a === 'stop') { S.queue.length = 0; send({ type: 'stop' }); S.turn = false; } if (t) await rpc('task.' + a, { task_id: t.task_id }); refresh(); };
  swap(box, c);
}
// Suggestions: agentd's feed (context) plus one from the real next calendar event. One tap starts the task; nothing else to read.
const SICON = { chat: '#i-msg', msg: '#i-msg', car: '#i-car', eye: '#i-eye', shield: '#i-shield', cal: '#i-cal', spark: '#i-spark' };
function renderSugg() {
  const items = [...S.sugg];
  const c = S.cal, mins = c ? Math.round((c.begin - Date.now()) / 60000) : 0;
  if (c && c.location && mins > 0 && mins <= 120 && !c.allDay) items.unshift({ id: 'leave', title: `Leave for ${short(c.title, 22)} soon`, detail: `${fmtT(c.begin)} · ${short(c.location, 30)}`, icon: 'car', goal: `Open directions to ${c.location} in Maps` });
  const asked = S.asked && Date.now() - S.asked.at < 30 * 60000 ? S.asked.names.map((n) => n.toLowerCase().split(/\W/)[0]) : [];
  const first = (n) => String(n || '').toLowerCase().split(/\W/)[0];
  for (const c of (S.pending || []).filter((c) => !later.has(c.a.id + ':' + c.b.id) && asked.includes(first(c.a.name))).slice(0, 2)) items.unshift({ id: `same:${c.a.id}:${c.b.id}`, title: sameQ(c), detail: 'I asked which one · tap to answer', icon: 'spark', view: 'know' });
  if (!items.length) return swap($('#sugg'), el('div', 'calm', `<svg width="14" height="14"><use href="#i-check"/></svg>All caught up`));
  swap($('#sugg'), ...items.slice(0, 4).map((it, i) => {
    const b = el('button', 'glass sg' + (it.view === 'know' ? ' same' : '')); b.style.animationDelay = i * .05 + 's';
    const ic = it.app && S.names[it.app] ? `<span class="appic">${ICOHTML(it.app)}</span>` : `<span class="appic" style="background:${it.icon === 'eye' || it.icon === 'shield' ? 'var(--green)' : it.icon === 'car' ? 'var(--blue)' : 'var(--purple)'}"><svg width="18" height="18"><use href="${SICON[it.icon] || '#i-spark'}"/></svg></span>`;
    b.innerHTML = `${ic}<div><div class="t">${esc(it.title)}</div><div class="s">${esc(one(it.detail || ''))}</div></div><span class="orb working sm"></span><svg width="16" height="16" class="chev"><use href="#i-chev"/></svg>`;
    b.onclick = () => startSuggestion(it, b); return b;
  }));
}
function startSuggestion(it, b) {
  hap('heavy');
  if (it.view === 'blockers') return openBlockers();
  if (it.view === 'activity') return go(1);
  if (it.view === 'know') return $('#toKnow').onclick();
  if (!it.goal) return;
  b.classList.add('busy');
  S.goal = it.goal; if (!send({ type: 'task.start', goal: it.goal })) return b.classList.remove('busy');
  S.tasks.set('opt' + Date.now(), { id: 'opt', goal: it.goal, state: 'queued', step: 'Starting', steps: 0, created: Date.now() }); renderNow(); // instant card; replaced by the first task.update // a background worker; progress arrives as task.update
  S.sugg = S.sugg.filter((x) => x !== it);
  setTimeout(() => { b.classList.add('gone'); setTimeout(renderSugg, 400); }, 500);
}
on('suggestions', (m) => { if (m.id) return; S.sugg = (m.items || []).filter((x) => x && typeof x === 'object' && !/^(blocked|trackers)$/.test(x.id)); renderSugg(); }); // keyboard replies carry an id; the home feed doesn't. For you = things to act on; the blocked/tracker tallies live in the command center
function renderCalendar() {
  const j = N.calendarNext(); const c = S.cal = j && JSON.parse(j);
  const cal = S.apps.find((a) => /calendar/i.test(a.pkg));
  $('#wcal').onclick = () => { hap(); cal && N.launch(cal.pkg); };
  renderSugg();
  $('#wcal').classList.toggle('quiet', !c);
  if (!c) { $('#calWhen').textContent = 'Next'; $('#calTitle').textContent = 'Nothing scheduled this week'; return; }
  const mins = Math.round((c.begin - Date.now()) / 60000), days = Math.floor((c.begin - new Date().setHours(0, 0, 0, 0)) / 864e5);
  $('#calWhen').textContent = mins <= 0 ? 'Now' : mins < 60 ? `Next · in ${mins} min` : days === 0 ? 'Today' : days === 1 ? 'Tomorrow' : new Date(c.begin).toLocaleDateString([], { weekday: 'long' });
  $('#calTitle').textContent = c.title || 'Untitled';
  $('#calSub').textContent = (c.allDay ? 'All day' : `${fmtT(c.begin)} – ${fmtT(c.end)}`) + (c.location ? ' · ' + short(c.location, 24) : '');
}

// apps + dock + drawer
function loadApps() {
  S.apps = JSON.parse(N.apps() || '[]');
  S.names = Object.fromEntries(S.apps.map((a) => [a.pkg, a.label]));
  const want = [[/pigeon|messag|mms/i], [/calendar/i], [/chrome|browser|maps/i], [/camera/i]];
  const dock = [];
  for (const [re] of want) { const a = S.apps.find((x) => re.test(x.pkg) && !dock.includes(x)); if (a) dock.push(a); }
  for (const a of S.apps) if (dock.length < 4 && !dock.includes(a)) dock.push(a);
  $('#dock').replaceChildren(...dock.map((a) => { const b = el('button', 'ico'); b.append(icon(a.pkg)); b.onclick = () => { hap(); N.launch(a.pkg); }; return b; }));
  renderGrid('');
}
function renderGrid(q) {
  q = q.trim().toLowerCase();
  $('#grid').replaceChildren(...S.apps.filter((a) => !q || a.label.toLowerCase().includes(q)).map((a) => {
    const b = el('button', 'app'); const i = el('i', 'ico'); i.append(icon(a.pkg, { loading: 'lazy' }));
    b.append(i, el('span', '', esc(a.label))); b.onclick = () => { hap(); N.launch(a.pkg); setTimeout(() => close('drawer'), 250); }; return b;
  }));
}
$('#q').oninput = (e) => renderGrid(e.target.value);
// ---------- my apps: micro-apps the agent built (agentd apps.js, GET /apps). Widget cards on Home + a row in the drawer; open = MicroApp
// (own sandboxed WebView, no network). The summary line is what the app last wrote with AOS.summary ("3 workouts this week").
S.myapps = []; S.summ = {}; S.fresh = null;
async function loadMyApps() {
  const r = await nget('/apps'); if (r?.apps) S.myapps = r.apps;
  try { S.summ = JSON.parse(N.appSummaries() || '{}'); } catch {}
  renderMyApps();
}
function appCard(a) {
  const live = S.summ[a.id], b = el('button', 'glass ma' + (a.id === S.fresh ? ' new' : ''), `<span class="emo">${esc(a.icon || '✨')}</span><div><div class="t">${esc(a.name)}</div><div class="s${live ? ' live' : ''}">${esc(live || a.description || '')}</div></div><span class="rm" hidden>Remove</span>`);
  b.dataset.id = a.id; return b;
}
function renderMyApps() {
  const n = S.myapps.length; // Home shows the 4 most recent (two rows fit above the ask bar; a swipe up here opens the drawer, which has them all)
  $('#myK').innerHTML = n ? `My apps${n > 4 ? `<span class="more">${n - 4} more in the drawer</span>` : ''}` : '';
  swap($('#myapps'), ...S.myapps.slice(0, 4).map(appCard));
  $('#mygrid').replaceChildren(...S.myapps.map((a) => { const b = el('button', 'app'); b.innerHTML = `<i class="ico"><span class="emo">${esc(a.icon || '✨')}</span></i><span>${esc(a.name)}</span>`; b.onclick = () => { hap(); N.openApp(a.id); setTimeout(() => close('drawer'), 250); }; return b; }));
}
{ const box = $('#myapps'); let hold = null; // tap opens; hold 600 ms arms Remove (3 s), a tap on it removes (owner only, agentd apps.remove)
  box.onpointerdown = (e) => { const c = e.target.closest('.ma'); if (!c) return; hold = setTimeout(() => { hold = null; hap('heavy'); const rm = $('.rm', c); rm.hidden = false; setTimeout(() => (rm.hidden = true), 3000); }, 600); };
  box.onpointerup = box.onpointercancel = box.onpointerleave = () => { clearTimeout(hold); hold = null; };
  box.onclick = (e) => { const c = e.target.closest('.ma'); if (!c) return; const rm = $('.rm', c);
    if (!rm.hidden) { hap(); c.style.opacity = .4; send({ type: 'apps.remove', id: c.dataset.id }); return; }
    hap(); N.openApp(c.dataset.id); }; }
on('apps.changed', (m) => { if (m.why === 'built') { S.fresh = m.id; hap('heavy'); setTimeout(() => { S.fresh = null; renderMyApps(); }, 20000); } loadMyApps(); });
on('tool.progress', (m) => { S.step = m.text; const s = steps[m.name]; if (s) $('span:last-child', s).textContent = `${toolName(m.name)} · ${m.text}`; renderNow(); }); // build_app: Designing / Building · 4 KB / Installing

// ---------- pager + gestures ----------
const pager = $('#pager'); let page = 0, W = innerWidth;
function go(p) {
  page = Math.max(0, Math.min(2, p)); pager.classList.remove('drag'); pager.style.transform = `translateX(${-page * W}px)`;
  $$('#dots i').forEach((d, i) => d.classList.toggle('on', i === page));
  if (page === 1) { renderCenter(); loadTrackers(); } if (page === 2) loadMemory();
}
let T = null;
pager.addEventListener('touchstart', (e) => { T = { x: e.touches[0].clientX, y: e.touches[0].clientY, t: Date.now(), axis: null }; }, { passive: true });
pager.addEventListener('touchmove', (e) => {
  if (!T) return; const dx = e.touches[0].clientX - T.x, dy = e.touches[0].clientY - T.y;
  if (!T.axis && (Math.abs(dx) > 12 || Math.abs(dy) > 12)) T.axis = Math.abs(dx) > Math.abs(dy) ? 'x' : 'y';
  if (T.axis === 'x') { pager.classList.add('drag'); pager.style.transform = `translateX(${-page * W + dx}px)`; }
}, { passive: true });
pager.addEventListener('touchend', (e) => {
  if (!T) return; const dx = e.changedTouches[0].clientX - T.x, dy = e.changedTouches[0].clientY - T.y, dt = Date.now() - T.t;
  if (T.axis === 'x') { const fling = Math.abs(dx) / dt > 0.5; go(page + (dx < -W / 4 || (fling && dx < -30) ? 1 : dx > W / 4 || (fling && dx > 30) ? -1 : 0)); }
  else if (T.axis === 'y' && page === 0) { if (dy < -70) open('drawer'); else if (dy > 90) N.shade(); }
  T = null;
}, { passive: true });
addEventListener('resize', () => { W = innerWidth; go(page); });

// ---------- overlays ----------
const stack = [];
function open(id) { const o = $('#' + id); if (o.classList.contains('on')) return; o.classList.add('on'); stack.push(id); if (id === 'sheet') { $('#scrim').classList.add('on'); sheetEmpty(); } hap('tick'); }
function close(id) { const o = $('#' + id); o.classList.remove('on'); const i = stack.indexOf(id); if (i >= 0) stack.splice(i, 1); if (id === 'sheet') { $('#scrim').classList.remove('on'); $('#askIn').blur(); } if (id === 'voice') voiceStop(); }
app.back = () => { if (stack.length) close(stack[stack.length - 1]); else if (page) go(0); };
app.home = () => { [...stack].forEach(close); go(0); };
app.resume = () => { W = innerWidth; if (wsOk) { refresh(); send({ type: 'task.list' }); loadMyApps(); } if (page === 2) loadMemory(); if ($('#vault').classList.contains('on')) loadVault(); };
$('#scrim').onclick = () => close('sheet');
$('#permsBack').onclick = () => close('perms');
$('#drawer .grab').onclick = () => close('drawer');
$('#toBlockers').onclick = $('#toBlockers2').onclick = () => { hap(); openBlockers(); };
$('#blockersBack').onclick = () => close('blockers');
$('#toMods').onclick = () => { hap(); openMods(); };
$('#modsBack').onclick = () => close('mods');
$('#toPlugins').onclick = () => { hap(); open('plugins'); renderPlugins(); send({ type: 'plugins.list' }); };
$('#pluginsBack').onclick = () => close('plugins');
$('#toRemote').onclick = () => { hap(); open('remote'); renderRemote(); send({ type: 'remote', action: 'status' }); };
$('#remoteBack').onclick = () => close('remote');
$('#toVault').onclick = () => { hap(); openVault(); };
$('#vaultBack').onclick = () => close('vault');
$('#vaultAdd').onclick = () => { hap('heavy'); N.vaultAdd(''); };
$('#toPerms').onclick = () => { hap(); openPerms(); };
$('#toSettings').onclick = () => { hap(); N.settings(''); };
$('#toMemory').onclick = () => { hap(); go(2); };
$('#cmd').onclick = (e) => { if (e.target.closest('.mic')) return; hap(); open('sheet'); setTimeout(() => $('#askIn').focus(), 350); };
$('#micBtn').onclick = () => { hap('heavy'); voiceStart(); };
// Prompt chips shown in the empty ask sheet (the Home row was removed 10-28).
const TRY = [['cal', 'At 7 PM, …', 'At 7 PM, '], ['spark', 'What did I miss?', 'What did I miss?'], ['msg', 'Reply I’m 10 min late', 'Reply that I’m 10 minutes late'], ['key', 'Sign me in', 'Sign me in to '], ['cloud', 'Local model', null]];

// ---------- command center ----------
function renderCenter() {
  const box = $('#tasks'), rows = [];
  const t = S.active;
  for (const w of S.tasks.values()) if (WORKING.has(w.state)) rows.push(el('div', 'rowi', `<span class="orb ${w.state === 'waiting' ? 'approval' : 'working'}"></span><div>${esc(short(w.goal, 34))}<span class="s">${esc(w.step)}</span></div>`));
  if (t && ![...S.tasks.values()].some((w) => w.lease_id === t.task_id)) rows.push(taskRow(t));
  if (S.turn && !t) rows.push(el('div', 'rowi', `<span class="orb working"></span><div>Thinking<span class="s">${{ codex: 'Codex', claude: 'Claude', local: 'On device' }[S.provider] || esc(S.provider)}</span></div>`));
  const running = rows.length; $('#kill').classList.toggle('off', !running && !S.turn);
  for (const x of S.sched) { // upcoming scheduled tasks (agentd schedule.js); x cancels, no card
    const r = el('div', 'rowi sched', `<span class="appic"><svg width="15" height="15"><use href="#i-cal"/></svg></span><div>${esc(short(x.prompt, 34))}<span class="s">${esc(whenLabel(x.at) || x.next)}${x.repeat !== 'none' ? ' · ' + esc(x.repeat) : ''}</span></div><div class="r"><button class="ib"><svg width="14" height="14"><use href="#i-x"/></svg></button></div>`);
    $('button', r).onclick = () => { hap(); r.style.opacity = .4; send({ type: 'schedule.cancel', id: x.id }); }; rows.push(r);
  }
  if (!rows.length) rows.push(el('div', 'empty', 'Nothing running'));
  box.replaceChildren(...rows);
  $('#taskCount').textContent = [running ? `${running} running` : '', S.sched.length ? `${S.sched.length} scheduled` : ''].filter(Boolean).join(' · ');
  $$('#prov button').forEach((b) => b.classList.toggle('on', b.dataset.p === S.provider));
  $('#provNote').textContent = { codex: 'OpenAI Codex in the cloud · default, fastest', claude: 'Claude Code · cloud', local: 'Qwen 0.8B running on this phone · works in airplane mode' }[S.provider] || '';
  const today = Math.max(new Date().setHours(0, 0, 0, 0), (S.since || 0) + 1); // after a demo reset, only what came since
  const day = S.audit.filter((e) => e.ts >= today), recent = day.slice(-60).reverse();
  $('#recentNote').textContent = day.length ? `${day.length >= 500 ? '500+' : day.length} today` : 'quiet today';
  $('#recent').replaceChildren(...recent.map((e) => { const [txt, c] = auditLine(e); const tg = e.tool === 'guard.flag' ? 'Caught' : /blocked|refused/.test(e.decision || '') ? 'Blocked' : e.decision === 'denied' ? 'Denied' : e.decision === 'approved' ? (/^(reviewer|policy)$/.test(e.approver || e.channel) ? 'Auto' : 'Approved') : '';
    return el('li', c === 'c' ? 'flag' : '', `<time>${fmtT(e.ts)}</time><i class="${c}"></i><span>${esc(txt)}</span>${tg ? `<em class="tg ${c === 'g' ? 'g' : ''}">${tg}</em>` : ''}`); }));
  if (!recent.length) $('#recent').replaceChildren(el('li', '', `<span class="empty">Nothing yet</span>`));
  $('#modNote').textContent = S.mods?.length ? `${S.mods.length} added` : 'none yet';
  $('#vaultNote').textContent = S.vault?.length ? `${S.vault.length} saved` : 'none yet';
  $('#permNote').textContent = t ? `${(t.apps || []).length} app${t.apps?.length === 1 ? '' : 's'} granted` : 'no grants';
  const nb = (S.trackers || []).reduce((n, a) => n + a.trackers.filter((t) => t.blocked).length, 0);
  $('#blockNote').textContent = S.trackers ? `${nb} blocked` : '';
  // Protected today: the security story in three calm numbers, from the real audit + tracker data (no pop-ups, just a tally).
  const ads = S.adsBlocked || 0, stopped = day.filter((e) => e.tool !== 'guard.flag' && /blocked|denied|refused/.test(e.decision || '')).length;
  const appr = day.filter((e) => e.decision === 'approved'), auto = appr.filter((e) => /reviewer|policy/.test(e.approver || e.channel || '')).length;
  const stat = (n, l, c, v) => `<button class="stat ${c}" data-v="${v}"><b>${n}</b><span>${l}</span></button>`; // a tile is the tap: Blockers or the Activity list
  $('#stats').innerHTML = stat(nb, nb === 1 ? 'tracker blocked' : 'trackers blocked', 'g', 'blockers') + stat(ads, 'ads blocked', 'g', 'blockers') + stat(stopped, stopped === 1 ? 'action stopped' : 'actions stopped', 'g', 'activity') + stat(appr.length, appr.length === 1 ? 'approval' : 'approvals' + (auto ? ` · ${auto} auto` : ''), 'b', 'activity');
  const open_ = (S.trackers || []).filter((a) => a.trackers.some((t) => !t.blocked)); // apps still sending to unblocked trackers: one row, tap = Blockers
  $('#watch').replaceChildren(...open_.length ? [el('button', 'rowi watch', `<span class="appic"><svg width="16" height="16"><use href="#i-eye"/></svg></span><div>${open_.length} app${open_.length > 1 ? 's' : ''} sent data to trackers<span class="s">${esc(open_.map((a) => a.label || a.pkg).slice(0, 3).join(', '))}</span></div><svg width="16" height="16" class="chev"><use href="#i-chev"/></svg>`)] : []);
  $('#shieldNote').textContent = Date.now() - S.armed < 4000 ? 'Tap to start fresh' : nb + ads + stopped + appr.length ? '' : 'all quiet';
}
async function loadTrackers() { let r = await phone('trackers.list', {}); if (!r?.apps) r = await rpc('trackers.list', {}); if (r?.apps) { S.trackers = r.apps.filter((a) => !/^com\.duolingo/.test(a.pkg)); S.adsBlocked = r.blocked_requests; } renderCenter(); } // owner line first: works without agentd; founder: no Duolingo on stage
function taskRow(t) {
  const r = el('div', 'rowi ' + (S.pending ? 'approval' : 'working'));
  const pct = Math.min(100, Math.round(100 * (t.steps || 0) / (t.max_steps || 40))), last = S.audit.filter((e) => e.task === t.task_id && !/^task\./.test(e.tool)).pop();
  r.innerHTML = `<span class="orb ${S.pending ? 'approval' : 'working'}"></span><div>${esc(short(t.goal, 34) || 'Task')}<span class="s">${esc(S.pending ? 'Waiting for you' : t.paused ? 'Paused' : last ? auditLine(last)[0] : 'Starting')} · ${esc((t.apps || []).map(appName).join(', '))}</span></div>
    <div class="r"><div class="mini"><i style="transform:scaleX(${pct / 100})"></i></div><button class="ib" data-a="${t.paused ? 'resume' : 'pause'}"><svg width="14" height="14"><use href="#i-${t.paused ? 'play' : 'pause'}"/></svg></button><button class="ib red" data-a="stop"><svg width="14" height="14"><use href="#i-stop"/></svg></button></div>`;
  r.onclick = async (e) => { const a = e.target.closest('[data-a]')?.dataset.a; if (!a) return; hap(); if (a === 'stop') send({ type: 'stop' }); await rpc('task.' + a, { task_id: t.task_id }); refresh(); };
  return r;
}
// Demo reset (owner, hidden): hold the "Protected today" title, then tap it again. The broker appends one demo.reset audit
// line (owner line only; the chain is never edited) and Protected today + Activity count from it. Ads blocked keeps counting.
{ const k = $('.shield .k'); let t, held = false; S.armed = 0;
  k.onpointerdown = () => { held = false; t = setTimeout(() => { held = true; S.armed = Date.now(); hap('heavy'); renderCenter(); setTimeout(renderCenter, 4000); }, 700); };
  k.onpointerup = k.onpointercancel = k.onpointerleave = () => clearTimeout(t);
  k.onclick = async () => { if (held) { held = false; return; } if (Date.now() - S.armed > 4000) return; S.armed = 0; hap(); const r = await phone('audit.demo_reset'); if (r?.since) S.since = r.since; renderCenter(); }; }
$('.shield').onclick = (e) => { const v = e.target.closest('[data-v]')?.dataset.v || (e.target.closest('.watch') && 'blockers'); if (!v) return; hap(); v === 'blockers' ? openBlockers() : $('#recent').closest('.mod').scrollIntoView({ behavior: 'smooth', block: 'start' }); };
$('#prov').onclick = (e) => { const p = e.target.dataset.p; if (!p) return; hap('tick'); S.provider = localStorage.provider = p; renderCenter(); };
$('#kill').onclick = async () => { hap('heavy'); S.queue.length = 0; send({ type: 'stop' }); await rpc('task.stop', {}); S.turn = false; S.pending = null; refresh(); };

// ---------- permissions ----------
function openPerms() { open('perms'); renderGrants(); renderFirewall(); renderSources($('#psources')); $('#never').replaceChildren(...FORBIDDEN.map((n) => el('span', 'chip', `<i></i>${n}`))); }
function renderGrants() {
  const t = S.active, box = $('#grants');
  $('#grantNote').textContent = t ? 'expires' : '';
  if (!t) return box.replaceChildren(el('div', 'empty', 'None'));
  const left = Math.max(0, Math.round((t.expires - Date.now()) / 60000));
  box.replaceChildren(...t.apps.map((pkg) => { const r = el('div', 'rowi', `<span class="appic">${ICOHTML(pkg)}</span><div>${esc(appName(pkg))}<span class="s">Tap · type</span></div><div class="r"><span class="tag a">${left} min</span><button class="tag">Revoke</button></div>`);
    $('button', r).onclick = async () => { hap(); send({ type: 'stop' }); await rpc('task.stop', { task_id: t.task_id }); refresh(); }; return r; }));
}
async function renderFirewall() {
  const r = await own('firewall.list', {}); const rules = r?.rules || [];
  const pkgs = [...new Set(['local.agentos.pigeon', 'local.agentos.beacon', 'com.termux', ...rules.map((x) => x.pkg)])].filter((p) => S.names[p] || rules.some((x) => x.pkg === p));
  $('#fw').replaceChildren(...pkgs.map((pkg) => {
    const mine = rules.filter((x) => x.pkg === pkg && /REJECT|DROP/i.test(x.action || 'REJECT'));
    const blocked = mine.some((x) => !x.dest || !/^name:/.test(x.dest)), nt = new Set(mine.map((x) => x.dest).filter((d) => /^name:/.test(d))).size; // tracker rules are per domain; a full block has no dest
    const b = el('button', 'ft ' + (blocked ? 'off' : 'on'), `<span class="c">${ICOHTML(pkg)}</span><div class="l">${esc(appName(pkg))}<span>${blocked ? 'Blocked' : nt ? `${nt} tracker${nt === 1 ? '' : 's'} blocked` : 'Allowed'}</span></div>`);
    b.onclick = async () => { hap(); b.className = 'ft wait'; $('.l span', b).textContent = 'Waiting…'; const res = await own('firewall.set', { pkg, mode: blocked ? 'allow' : 'block' }); if (res.decision !== 'approved') hap('heavy'); renderFirewall(); refresh(); };
    return b;
  }));
  if (!pkgs.length) $('#fw').replaceChildren(el('div', 'empty', 'No apps'));
}

// ---------- blockers: trackers seen and blocked per app ({apps:[{pkg,label,trackers:[{domain,company,blocked,hits,blocked_hits}]}]}) ----------
// Live while the view is open: trackers.list every 2 s (the broker reads the iptables REJECT counters), numbers ticked in place.
let bPoll = null;
async function openBlockers() {
  open('blockers'); renderBlockers(); await loadTrackers(); renderBlockers();
  clearInterval(bPoll); bPoll = setInterval(async () => { if (!$('#blockers').classList.contains('on')) return clearInterval(bPoll); if (document.hidden) return; await loadTrackers(); patchBlockers() || renderBlockers(); }, 2000);
}
const fmt = (n) => (n || 0).toLocaleString('en-US');
// "Facebook, Branch +4": the companies behind the domains, busiest first, as many as fit on the line.
const who = (ts) => { const c = [...new Set([...ts].sort((a, b) => (b.blocked ? b.blocked_hits : b.hits) - (a.blocked ? a.blocked_hits : a.hits)).map((t) => t.company || t.domain))], out = []; for (const n of c) { if (out.length && out.join(', ').length + n.length > 12) break; out.push(n); } return out.join(', ') + (c.length > out.length ? ` +${c.length - out.length}` : ''); };
const refused = (a) => a.trackers.reduce((n, t) => n + (t.blocked ? t.blocked_hits || 0 : 0), 0);
// Counter keys -> values; a structural change (new app, block/unblock) makes patchBlockers() fall back to a full render.
function bNums() { const m = { all: S.adsBlocked || 0 }; for (const a of S.trackers || []) { m[a.pkg] = refused(a); for (const t of a.trackers) m[`${a.pkg}|${t.domain}|${t.blocked ? 1 : 0}`] = t.blocked ? t.blocked_hits || 0 : t.hits || 0; } return m; }
function tick(e, to) { const from = +e.dataset.v || 0; if (from === to) return; e.dataset.v = to; e.classList.remove('up'); void e.offsetWidth; e.classList.add('up'); const t0 = performance.now(), step = (now) => { const k = Math.min(1, (now - t0) / 600), v = Math.round(from + (to - from) * (1 - (1 - k) ** 3)); e.textContent = fmt(v); if (k < 1) requestAnimationFrame(step); }; requestAnimationFrame(step); }
function patchBlockers() { const m = bNums(), els = [...$$('#blockers .n')]; if (els.length !== Object.keys(m).length || els.some((e) => !(e.dataset.k in m))) return false; for (const e of els) tick(e, m[e.dataset.k]); return true; }
// Block / Unblock go over the owner line: the tap IS the approval (no card, no fingerprint). Names must be on the tracker list (the broker checks).
async function blockApp(a, b, on) { hap('heavy'); if (b) b.textContent = on ? 'Blocking…' : 'Unblocking…'; const r = await phone(on ? 'trackers.block' : 'trackers.unblock', { pkg: a.pkg }); if (r?.decision !== 'approved') hap('heavy'); openBlockers(); }
function renderBlockers() {
  const box = $('#blist'), apps = (S.trackers || []).filter((a) => a.trackers?.length), N = (k, v) => `<span class="n" data-k="${esc(k)}" data-v="${v || 0}">${fmt(v)}</span>`;
  const nb = apps.reduce((n, a) => n + a.trackers.filter((t) => t.blocked).length, 0), ns = apps.reduce((n, a) => n + a.trackers.length, 0);
  $('#blockersNote').innerHTML = nb ? `${N('all', S.adsBlocked)} blocked` : ns ? `${ns} open` : '';
  $('#blegend').hidden = !apps.length;
  const all = $('#blockAll'); all.hidden = nb >= ns; all.onclick = async () => { hap('heavy'); all.textContent = 'Blocking…'; for (const a of apps) if (a.trackers.some((t) => !t.blocked)) await phone('trackers.block', { pkg: a.pkg }); all.textContent = 'Block all'; openBlockers(); };
  if (!apps.length) return box.replaceChildren(el('div', 'bempty', `<span class="ring"><svg width="30" height="30"><use href="#i-shield"/></svg></span>No trackers seen<small>Apps show up here when they call a tracker</small>`));
  box.replaceChildren(...apps.map((a) => {
    const open_ = a.trackers.filter((t) => !t.blocked), bk = a.trackers.filter((t) => t.blocked), m = el('div', 'mod bk');
    const sub = bk.length ? `${N(a.pkg, refused(a))} blocked · ${esc(who(bk))}${open_.length ? ` · ${open_.length} open` : ''}` : `${open_.length} open · ${esc(who(open_))}`; // the companies behind the domains
    m.innerHTML = `<div class="hd"><span class="appic">${ICOHTML(a.pkg)}</span><div>${esc(a.label || appName(a.pkg))}<span class="s">${sub}</span></div>${open_.length ? '<button class="pillbtn blue">Block</button>' : '<button class="tag unb">Unblock</button>'}</div>
      <ul>${a.trackers.map((t) => `<li class="${t.blocked ? 'off' : ''}"><i></i><b>${esc(t.domain)}</b>${t.company ? `<em>${esc(t.company)}</em>` : ''}${N(`${a.pkg}|${t.domain}|${t.blocked ? 1 : 0}`, t.blocked ? t.blocked_hits : t.hits)}</li>`).join('')}</ul>`;
    const b = $('.pillbtn', m); if (b) b.onclick = () => blockApp(a, b, true);
    const u = $('.unb', m); if (u) u.onclick = () => blockApp(a, u, false);
    return m;
  }));
}
// ---------- passwords: the vault ({entries:[{id, label, apps, username}]}; never the password). Add = native screen in local.agentos. ----------
async function openVault() { open('vault'); renderVault(); await loadVault(); }
async function loadVault() { const r = await phone('vault.list', {}); if (r?.entries) S.vault = r.entries; renderVault(); renderCenter(); }
function renderVault() {
  const box = $('#vlist'), es = S.vault || [];
  $('#vaultNote2').textContent = es.length ? `${es.length} saved` : '';
  if (!es.length) return box.replaceChildren(el('div', 'bempty', `<span class="ring blue"><svg width="30" height="30"><use href="#i-lock"/></svg></span>No saved logins<small>Add one; agents sign you in with your fingerprint and never see it</small>`));
  box.replaceChildren(...es.map((e, i) => { const pkg = e.apps?.[0] || ''; const r = el('div', 'glass pw', `<span class="appic">${ICOHTML(pkg)}</span><div>${esc(e.label || appName(pkg))}<span class="s">${esc(e.username || '')}</span><span class="fp"><svg width="12" height="12"><use href="#i-shield"/></svg>Fingerprint to use · only in ${esc(appName(pkg))}</span></div><button class="tag">Delete</button>`);
    r.style.animationDelay = i * .04 + 's';
    const b = $('button', r); b.onclick = async () => { hap(); if (b.textContent !== 'Sure?') { b.textContent = 'Sure?'; b.classList.add('c'); setTimeout(() => { b.textContent = 'Delete'; b.classList.remove('c'); }, 3000); return; } r.style.opacity = .4; await phone('vault.remove', { id: e.id }); loadVault(); };
    return r; }));
}
on('vault.changed', () => { if ($('#vault').classList.contains('on')) loadVault(); });
// ---------- mods: buttons agents added to apps ({mods:[{id, package, app, label, shown, created, by}]}) ----------
async function openMods() { open('mods'); renderMods(); await loadMods(); }
async function loadMods() { const r = await own('app.mod.list', {}); if (r?.mods) S.mods = r.mods; renderMods(); renderCenter(); }
function renderMods() {
  const box = $('#mlist'), mods = S.mods || [];
  $('#modsNote').textContent = mods.length ? `${mods.length} added` : '';
  if (!mods.length) return box.replaceChildren(el('div', 'bempty', `<span class="ring blue"><svg width="30" height="30"><use href="#i-mod"/></svg></span>Ask to add a button to any app<small>“Add a tip button to Calculator”</small>`));
  box.replaceChildren(...mods.map((m) => { const r = el('div', 'rowi mod', `<span class="appic">${ICOHTML(m.package)}</span><div>${esc(m.label)}<span class="s">${esc(m.app || appName(m.package))}${m.shown ? '' : ' · hidden'}</span></div><div class="r"><button class="tag">Remove</button></div>`);
    $('button', r).onclick = async () => { hap(); r.style.opacity = .4; await own('app.mod.remove', { id: m.id, by: 'settings' }); loadMods(); }; return r; }));
}
on('mod.changed', () => loadMods());
// ---------- promises (agentd proactive.js, PROACTIVE=1): tap = help me with it (a normal task; sends need approval) ----------
const PKIND = { commitment: 'You promised', request: 'Asked of you', reply: 'Waiting on you' };
on('ws.open', () => send({ type: 'promises.list' }));
on('promises', (m) => { S.promises = m.items || []; $('#toPromises').hidden = !!m.off; renderPromises(); });
function renderPromises() {
  const all = S.promises || [], n = all.filter((x) => x.state === 'open').length, box = $('#prlist');
  $('#promNote').textContent = $('#promisesNote').textContent = n ? `${n} open` : '';
  if (!all.length) return box.replaceChildren(el('div', 'bempty', `<span class="ring blue"><svg width="30" height="30"><use href="#i-check"/></svg></span>Nothing owed<small>Things you promise, and things people ask you, show up here</small>`));
  box.replaceChildren(...all.map((x, i) => {
    const on_ = x.state === 'open', r = el('div', 'glass pw prom' + (on_ ? '' : ' off'), `<span class="appic">${x.pkg ? ICOHTML(x.pkg) : `<svg width="18" height="18"><use href="#i-${x.kind === 'reply' ? 'msg' : 'check'}"/></svg>`}</span><div>${esc(short(x.kind === 'reply' ? `Reply to ${x.who}` : x.what || `${x.who}: ${x.quote}`, 40))}<span class="s">${esc(PKIND[x.kind])} · ${esc(short(x.who, 18))} · ${esc(x.dueLabel ? `due ${x.dueLabel}` : x.ago)}</span><span class="s q">“${esc(short(x.quote, 64))}”</span></div>`
      + (on_ ? `<button class="tag" data-a="done">Done</button><button class="tag" data-a="snooze">Later</button><button class="ib" data-a="dismiss"><svg width="14" height="14"><use href="#i-x"/></svg></button>` : `<button class="tag" data-a="open">${x.state === 'snoozed' ? 'Later' : x.state === 'dismissed' ? 'Hidden' : 'Done'} · undo</button>`));
    r.style.animationDelay = i * .04 + 's';
    r.onclick = (e) => { const a = e.target.closest('[data-a]')?.dataset.a; hap(); if (a) return send({ type: 'promises.update', id: x.id, action: a, hours: 3 }); if (on_) { close('promises'); startSuggestion({ goal: x.goal }, r); } };
    return r; }));
}
$('#toPromises').onclick = () => { hap(); open('promises'); renderPromises(); send({ type: 'promises.list' }); };
$('#promisesBack').onclick = () => close('promises');
// ---------- what I know (agentd memory v2, MEMORY_V2=1): "same person?" cards, facts with the app and day they came from
// (Fix edits inline, the x forgets), people with their linked cards. agentd takes all four messages from this app's uid only
// (fromOwner), so a model can't settle an identity or edit a fact through here; its own tools need a card.
const SRC = (s) => ({ calllog: 'Call log', sms: 'Texts', user: 'You told me', notifications: 'Notifications', contacts: 'Contacts' })[s] || String(s || '').replace(/^\w/, (c) => c.toUpperCase());
const day = (ts) => ts ? new Date(ts).toLocaleDateString([], { month: 'short', day: 'numeric' }) : '';
const GROUP = (f) => f.name !== 'you' || f.pred === 'relationship' ? 'People' : /^(favorite|likes|dislikes|prefers|diet|allergic)/.test(f.pred) ? 'Favorites' : /^(plan|wants|project)$/.test(f.pred) ? 'Plans' : 'Routines & life';
const cap = (s) => String(s || '').replace(/(^|[\s("])\p{Ll}+(?=[\s)".]|$)/gu, (m) => m[0] === m[0].toLowerCase() && /^[\s("]/.test(m[0]) ? m[0] + m[1].toUpperCase() + m.slice(2) : m[0].toUpperCase() + m.slice(1)); // "dennis" -> "Dennis"; "TJ Maxwell" untouched
const sent = (s) => String(s || '').replace(/^\p{Ll}/u, (c) => c.toUpperCase()); // "only the first name matches" -> "Only the first name matches"
const where = (x) => x.from ? (x.from === 'Contacts' ? ' in Contacts' : ` on ${x.from}`) : x.apps?.[0] ? ` on ${x.apps[0]}` : '';
const sameQ = (c) => `Is ${cap(c.a.name)}${where(c.a)} the same as ${cap(c.b.name)}${c.b.rel ? ` (your ${c.b.rel})` : where(c.b)}?`;
const later = new Set(); // "Not sure": hidden until the launcher restarts
function sameCard(c, cls = 'glass pw same') {
  const r = el('div', cls, `<div class="qq">${esc(sameQ(c))}</div><span class="s">${esc(c.why.map(sent).join(' · '))}</span><div class="acts"><button class="tag" data-a="yes">Yes, same person</button><button class="tag" data-a="no">No</button><button class="tag ghost" data-a="later">Not sure</button></div>`);
  r.onclick = (e) => { const a = e.target.closest('[data-a]')?.dataset.a; if (!a) return; hap(); e.target.classList.add('on'); r.classList.add('gone');
    if (a === 'later') later.add(c.a.id + ':' + c.b.id); else send({ type: 'memory.identity.confirm', a: c.a.id, b: c.b.id, same: a === 'yes' });
    setTimeout(() => { S.pending = S.pending.filter((x) => x !== c); renderKnow(); renderSugg(); }, 350); };
  return r;
}
// The owner's own facts in second person: "You work at Mindsmith", a routine or plan as itself ("Gym Tuesday and Thursday").
const YOU = { lives_in: 'You live in', works_at: 'You work at', likes: 'You like', dislikes: "You don't like", prefers: 'You prefer', wants: 'You want', allergic_to: "You're allergic to", project: "You're working on", occupation: "You're a" };
function mine(f) {
  const t = f.text.replace(/ \(since [^)]*\)$/, ''), obj = t.split(': ').slice(1).join(': ');
  return f.name !== 'you' || !obj ? t : /^(routine|plan)$/.test(f.pred) ? sent(obj) : YOU[f.pred] ? `${YOU[f.pred]} ${obj}` : sent(t.replace(/^You /, ''));
}
function factRow(f) {
  const old = f.status !== 'active', ed = S.edit?.id === f.id, r = el('div', 'rowi fact' + (old ? ' old' : ''));
  r.innerHTML = ed ? `<div><input value="${esc(S.edit.value)}" autocomplete="off"><span class="s">${esc(f.name === 'you' ? 'You' : f.name)} · ${esc(f.pred.replace(/^favorite:/, 'favorite ').replace(/_/g, ' '))}</span></div><div class="r"><button class="tag b" data-a="save">Save</button><button class="ib" data-a="cancel"><svg width="14" height="14"><use href="#i-x"/></svg></button></div>`
    : `<div>${esc(mine(f))}<span class="s">${esc(SRC(f.source))} · ${day(f.at)}${f.by === 'owner' ? ' · you fixed it' : ''}</span></div>` + (old ? '' : `<div class="r"><button class="tag" data-a="fix">Fix</button><button class="ib" data-a="forget" aria-label="Forget"><svg width="14" height="14"><use href="#i-x"/></svg></button></div>`);
  if (ed) { const i = $('input', r); i.oninput = () => { S.edit.value = i.value; }; i.onkeydown = (k) => k.key === 'Enter' && $('[data-a=save]', r).click(); setTimeout(() => i.focus(), 0); }
  r.onclick = (e) => { const a = e.target.closest('[data-a]')?.dataset.a; if (!a) return; hap();
    if (a === 'forget') { r.classList.add('gone'); return send({ type: 'memory.correct', id: f.id, retract: true }); }
    if (a === 'fix') { S.edit = { id: f.id, value: f.obj }; return renderKnow(); }
    const v = (S.edit?.value || '').trim(); S.edit = null; // save / cancel: the edit state lives in S so a redraw (agentd reconnect) keeps it
    if (a === 'save' && v && v !== f.obj) send({ type: 'memory.correct', id: f.id, obj: v }); else renderKnow(); };
  return r;
}
function renderKnow() {
  const box = $('#klist'), k = S.know, pend = (S.pending || []).filter((c) => !later.has(c.a.id + ':' + c.b.id));
  $('#knowNote').textContent = $('#knowNote2').textContent = !k ? '' : pend.length ? `${pend.length} to confirm` : k.facts.length ? `${k.facts.length} facts` : k.v2 ? 'nothing yet' : 'off';
  if (!$('#know').classList.contains('on')) return;
  if (!k) return box.replaceChildren(el('div', 'empty', 'Offline'));
  const parts = [];
  if (pend.length) { const m = el('div', 'mod', `<div class="k">Same person?<span>${pend.length}</span></div><div class="provnote" style="margin-bottom:8px">Your answer settles it; I never guess before sending</div>`); m.append(...pend.map((c) => sameCard(c))); parts.push(m); }
  const groups = new Map(); for (const f of k.facts) (groups.get(GROUP(f)) ?? groups.set(GROUP(f), []).get(GROUP(f))).push(f);
  const about = el('div', 'mod', '<div class="k">About you<span>Fix or forget any line</span></div>');
  for (const g of ['Favorites', 'People', 'Plans', 'Routines & life']) if (groups.get(g)) about.append(Object.assign(el('div', 'k', g), { style: 'margin:10px 0 2px;color:var(--l2)' }), ...groups.get(g).map(factRow));
  if (!k.facts.length) about.append(el('div', 'empty', k.v2 ? 'Nothing durable yet. Tell me things, or let the connectors read a little.' : 'Memory v2 is off on this phone (MEMORY_V2=1 in agentd.env)'));
  parts.push(about);
  const people = el('div', 'mod', `<div class="k">People<span>${k.people.length ? 'linked cards per person' : ''}</span></div>`);
  people.append(...k.people.map((p) => { const why = (p.linked || []).map((l) => sent(l.replace(/^(same person|possibly the same person): /, '').replace(/ \(\d+%\)$/, ''))).join(' · ');
    const maybe = (p.maybe || []).slice(0, 2).map((n, i) => `"${cap(n)}"${p.maybe_from?.[i] ? (p.maybe_from[i] === 'Contacts' ? ' in Contacts' : ` on ${p.maybe_from[i]}`) : ''}`);
    return el('div', 'rowi', `<span class="appic" style="background:var(--${p.relationship ? 'purple' : 'blue'})">${esc(cap(p.name)[0])}</span><div>${esc(cap(p.name))}${p.relationship ? ` <span class="tag">your ${esc(p.relationship)}</span>` : ''}<span class="s">${esc([p.from === 'Contacts' ? 'Contacts' : '', p.apps.slice(0, 3).join(' + '), (p.also || []).filter((n) => !p.maybe?.includes(n)).length ? `also "${p.also.filter((n) => !p.maybe?.includes(n)).slice(0, 2).map(cap).join('", "')}"` : '', maybe.length ? `maybe ${maybe.join(', ')}` : '', why].filter(Boolean).join(' · '))}</span></div>`); }));
  if (!k.people.length) people.append(el('div', 'empty', 'No one yet'));
  parts.push(people);
  box.replaceChildren(...parts);
}
on('ws.open', () => { send({ type: 'memory.about' }); send({ type: 'memory.identity' }); });
on('memory.about', (m) => { if (m.ok === false) return; S.know = m; renderKnow(); });
on('memory.identity', (m) => { if (m.ok === false) return; S.pending = m.pending || []; renderKnow(); renderSugg(); });
on('memory.identity.confirm', (m) => { if (!m.ok) hap('heavy'); });
on('memory.correct', (m) => { if (!m.ok) { hap('heavy'); renderKnow(); } });
on('memory.asked', (m) => { S.asked = { names: m.names || [], at: Date.now() }; send({ type: 'memory.identity' }); }); // "which Joe?": the matching cards go to Home
$('#toKnow').onclick = () => { hap(); open('know'); renderKnow(); send({ type: 'memory.about' }); send({ type: 'memory.identity' }); };
$('#knowBack').onclick = () => close('know');
// ---------- plugins: the owner's switches for groups of the agent's API tools (agentd/plugins.js) ----------
// The list comes from agentd (names, summaries); a switch goes over the owner line to the broker (plugins.set), which takes it
// only from this app's uid. agentd and models can't flip one. Off = the agent loses those API tools; it can still use the app on screen.
function renderPlugins() {
  const box = $('#plist'), ps = S.plugins;
  const off = ps ? ps.filter((x) => !x.on).length : 0;
  $('#pluginsNote').textContent = $('#plugNote').textContent = ps ? (off ? `${off} off` : 'All on') : '';
  if (!ps) return box.replaceChildren(el('div', 'empty', 'Offline'));
  const list = el('div', 'mod', '<div class="k">Direct tools<span>off = the agent uses the app on screen instead</span></div>');
  box.replaceChildren(el('div', 'mod', '<div class="k">Always on</div><div class="provnote">Screen control, alarms and timers, web search, notifications, memory questions</div>'), list);
  list.append(...ps.map((x) => { const r = el('div', 'rowi', `<span class="appic" style="background:var(--${x.on ? 'blue' : 'purple'})">${esc(x.name[0])}</span><div>${esc(x.name)}<span class="s">${esc(String(x.summary || '').split(/;|\s\(/)[0].replace(/[.,\s]+$/, ''))}</span></div><div class="r"><button class="toggle${x.on ? ' on' : ''}" aria-label="${x.on ? 'On' : 'Off'}"></button></div>`);
    const t = $('button', r); t.onclick = async () => { hap(); t.classList.toggle('on'); t.style.opacity = .5;
      const res = await phone('plugins.set', { id: x.id, on: !x.on }); if (!res?.ok) hap('heavy'); send({ type: 'plugins.list' }); }; return r; }));
}
on('plugins.list', (m) => { S.plugins = m.plugins; renderPlugins(); });
on('plugins.changed', () => send({ type: 'plugins.list' }));
// ---------- remote: outside assistants (Grok, Dots) over agentd's remote MCP (agentd/mcp-remote.js) ----------
// Everything goes over the websocket as {type:'remote', action}; agentd takes changes from this app's uid only. The phone dials
// out to a relay, so "Relay connected" means Grok can reach it; "Phone offline" means the line is down and callers get 503.
const remoteAct = (action, extra = {}) => { hap(); send({ type: 'remote', action, ...extra }); };
function renderRemote() {
  const box = $('#rlist'), r = S.remote, n = r?.paired?.length || 0;
  const state = !r ? '' : !r.on ? 'Off' : !r.enabled ? 'Paused' : r.relay === 'connected' ? 'Relay connected' : 'Phone offline';
  $('#remoteNote2').textContent = $('#remoteNote').textContent = !r ? '' : state === 'Relay connected' ? (n ? `${n} paired` : 'Ready') : state;
  if (!r) return box.replaceChildren(el('div', 'empty', 'Offline'));
  const orb = state === 'Relay connected' ? 'done' : state === 'Phone offline' ? 'approval' : 'alert';
  const st = el('div', 'mod', `<div class="k">Status</div><div class="rst"><span class="orb ${orb}"></span><div>${state}<span class="s">${esc(!r.on ? 'One step: put REMOTE_MCP=1 in ~/.agentos/agentd.env on the phone and restart agentd' : !r.enabled ? 'Every remote request is refused until you turn it back on' : state === 'Phone offline' ? 'Reconnecting to the relay · assistants get "try again"' : 'Assistants can ask; only you can approve, here on the phone')}</span></div></div>`);
  const sw = (label, sub, on, action) => { const row = el('div', 'rowi', `<div>${label}<span class="s">${sub}</span></div><div class="r"><button class="toggle${on ? ' on' : ''}" aria-label="${on ? 'On' : 'Off'}"></button></div>`); $('button', row).onclick = () => { $('button', row).classList.toggle('on'); remoteAct(action, { on: !on }); }; return row; };
  st.append(sw('Allow remote assistants', 'Kill switch · off stops their running tasks too', r.enabled, 'enable'), sw('Let them read', 'Latest messages and memory search, codes masked', r.reads, 'reads'));
  const paired = el('div', 'mod', `<div class="k">Paired assistants<span>${n || 'none yet'}</span></div>`);
  paired.append(...r.paired.map((p) => { const row = el('div', 'rowi', `<span class="appic" style="background:var(--${p.from === 'Grok' ? 'purple' : p.from === 'Dots' ? 'green' : 'blue'})">${esc(p.from[0])}</span><div>${esc(p.from)}<span class="s">${esc(p.client || '')} · ${p.used ? 'used ' + ago(p.used).replace(/ \d+s$/, '') + ' ago' : 'never used'}</span></div><div class="r"><button class="tag">Unpair</button></div>`);
    const b = $('button', row); b.onclick = () => { if (b.textContent !== 'Sure?') { hap(); b.textContent = 'Sure?'; b.classList.add('c'); setTimeout(() => { b.textContent = 'Unpair'; b.classList.remove('c'); }, 3000); return; } row.style.opacity = .4; remoteAct('unpair', { grant: p.grant }); }; return row; }));
  if (!n) paired.append(el('div', 'provnote', 'Pair one below, then ask it to "use AgentOS"'));
  const pair = el('div', 'mod', '<div class="k">Pair an assistant</div>');
  if (S.pairCode && S.pairCode.exp > Date.now()) { const left = Math.round((S.pairCode.exp - Date.now()) / 1000);
    pair.append(el('div', 'code', esc(S.pairCode.code)), el('div', 'provnote', `One-time code · ${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')} left`), el('span', 'url', esc(r.url || 'Set REMOTE_PUBLIC_URL on the phone for the address')), el('div', 'provnote', 'In Grok: Connectors › New › Custom, paste the address, then type the code on the sign-in page'));
  } else { const b = el('button', 'pairbtn' + (r.on ? '' : ' off'), '<svg width="16" height="16"><use href="#i-plus"/></svg>Pair an assistant'); b.onclick = () => remoteAct('pair'); pair.append(b, el('div', 'provnote', r.url ? `Address: ${r.url}` : 'Shows a one-time code (10 minutes) and the address to paste in Grok or ChatGPT')); }
  box.replaceChildren(st, paired, pair);
  if (n) { const rv = el('button', 'revoke', 'Revoke all · everyone must pair again'); rv.onclick = () => { if (rv.textContent !== 'Sure? Tap again') { hap(); rv.textContent = 'Sure? Tap again'; setTimeout(() => (rv.textContent = 'Revoke all · everyone must pair again'), 3000); return; } remoteAct('revoke'); }; box.append(rv); }
}
on('remote', (m) => { if (m.ok === false) { hap('heavy'); return; } if (m.action === 'pair') { S.pairCode = { code: m.code, exp: Date.now() + (m.expires_in || 600) * 1000 }; send({ type: 'remote', action: 'status' }); } else S.remote = m; renderRemote(); });
setInterval(() => { if ($('#remote').classList.contains('on') && S.pairCode) renderRemote(); }, 1000);
on('tracker.blocked', (m) => { S.audit = [...S.audit.slice(-199), { ...m, tool: 'tracker.blocked', ts: m.ts || Date.now() }]; renderCenter(); if ($('#blockers').classList.contains('on')) openBlockers(); });

// ---------- ask sheet ----------
const body = $('#shbody'); let aiMsg = null, steps = {};
const scrollDown = () => (body.scrollTop = body.scrollHeight);
let askAt = 0, dots = null;
// The stopwatch (DEMO-WOW #4): the header counts up quietly while working ("Thinking · 4 s") and ends on the measured time
// from agentd ("Done · 6.8 s"; past 30 s just "Done"). The island shows the same number.
let shState = 'Ask', shCls = '', shMs = 0;
function setSheet(state, cls, ms) { shState = state; shCls = cls || ''; shMs = ms || 0; shRender(); $('#shorb').className = 'orb ' + shCls; $('.shd').dataset.s = shCls; $('#shstop').hidden = !S.turn; if (cls !== 'working') { dots?.remove(); dots = null; } }
function shRender() { const s = shCls === 'working' && askAt ? Math.floor((Date.now() - askAt) / 1000) : 0; $('#shstate').textContent = shState + (shMs ? dur(shMs) : s >= 1 ? ` · ${s} s` : ''); $('#shdev').hidden = !S.onDevice; }
// agentd's router may answer on the phone's own model when there is no network: events then carry provider "local" (the user's toggle is untouched).
const device = (m) => { if (typeof m.provider === 'string') { S.onDevice = m.provider === 'local'; shRender(); } };
for (const t of ['turn.started', 'provider.selected', 'turn.done', 'text.delta', 'tool.request']) on(t, device);
setInterval(() => shCls === 'working' && shRender(), 1000);
const think = () => { if (!dots) body.append(dots = el('div', 'dots', '<i></i><i></i><i></i>')); body.append(dots); scrollDown(); }; // dots stay last
function sheetEmpty() {
  if (body.children.length) return $('#shtry')?.remove();
  if ($('#shtry')) return;
  const row = el('div', 'shtry', '', ); row.id = 'shtry';
  for (const [ic, label, text] of TRY) if (text !== null) { const b = el('button', 'chip', `<svg><use href="#i-${ic}"/></svg>${esc(label)}`); b.onclick = () => { hap('tick'); const inp = $('#askIn'); inp.value = text; inp.focus(); inp.setSelectionRange(text.length, text.length); }; row.append(b); }
  body.before(row);
}
function sendUser(text, p = pic, me) {
  if (!text && !p) return;
  if (!me) { setPic(null); me = el('div', 'msg me', (p ? `<img src="${p.url}">` : '') + esc(text)); }
  body.append(me); sheetEmpty(); scrollDown(); // re-appending moves a queued bubble below the answer it waited for
  if (S.turn) return S.queue.push([text, p, me]); // one turn at a time: a second ask goes when this one ends (agentd would refuse it as busy)
  aiMsg = null; S.home = null; askAt = Date.now();
  if (!send({ type: 'ask', text, provider: S.provider, ...(p && { images: [p.url], image_source: p.kind }) })) body.append(el('div', 'msg err', 'Agent offline'));
  else { S.turn = true; setSheet('Thinking', 'working'); think(); renderStatus(); renderNow(); }
  scrollDown();
}
// Attach (＋): Camera (system camera) or Photos (Android's photo picker), both through Home.pick -> app.picked(dataUrl, kind).
// In the ask sheet the picture becomes a chip on the next message; in voice it goes straight into the conversation.
let pic = null; // {url, kind}: the chip waiting on the next typed message
function setPic(p) { pic = p; $('#askPic').hidden = !p; if (p) $('#askPic img').src = p.url; }
$('#askPic button').onclick = () => { hap('tick'); setPic(null); };
const attMenu = $('#attMenu'), inVoice = () => $('#voice').classList.contains('on');
const attOpen = (e) => { e.stopPropagation(); hap('tick'); $('#attLive').hidden = !inVoice(); $('#attLive span').textContent = cam ? 'Stop live camera' : 'Live camera'; attMenu.hidden = !attMenu.hidden; };
$('#askAtt').onclick = $('#vatt').onclick = attOpen;
document.addEventListener('pointerdown', (e) => { if (!attMenu.hidden && !attMenu.contains(e.target)) attMenu.hidden = true; }, true);
attMenu.onclick = (e) => { const k = e.target.closest('[data-k]')?.dataset.k; if (!k) return; hap(); attMenu.hidden = true; if (k === 'live') return liveCam(); N.pick?.(k); };
app.picked = (url, kind) => {
  if (!url) return;
  if (!inVoice()) { open('sheet'); setPic({ url, kind }); return setTimeout(() => $('#askIn').focus(), 350); }
  send({ type: 'voice.image', image: url, source: kind }); $('#vsteps').append(el('li', '', `<img src="${url}">Shared a photo`)); V.state('Ask me about it');
};
$('#askForm').onsubmit = (e) => { e.preventDefault(); const t = $('#askIn').value.trim(); $('#askIn').value = ''; hap('tick'); sendUser(t); };
$('#shstop').onclick = () => { hap(); S.queue.length = 0; send({ type: 'stop' }); };
on('text.delta', (m) => { dots?.remove(); dots = null; if (!aiMsg) body.append(aiMsg = el('div', 'msg ai')); aiMsg.textContent += m.text; if (S.home) S.home.text = aiMsg.textContent; scrollDown(); });
on('visual', (m) => { if (!m.spec) return; const v = Viz.render(m.spec); // show tool: the chart lands before the sentence
  if ($('#voice').classList.contains('on')) { $('#vviz').replaceChildren(v); $('#voice').classList.add('live'); return; }
  dots?.remove(); dots = null; aiMsg = null; body.append(v); scrollDown(); });
on('voice.delegation', () => $('#vviz').replaceChildren()); on('turn.started', () => { if (!$('#voice').classList.contains('on')) $('#vviz').replaceChildren(); });
on('catchup', (m) => { dots?.remove(); dots = null; aiMsg = null; const ICO = { chat: 'msg', cal: 'cal', spark: 'spark', shield: 'shield' }, list = el('div', 'catch'); // "What did I miss?" rows land before the spoken answer
  list.append(...(m.cards || []).slice(0, 8).map((c) => { const r = el(c.goal ? 'button' : 'div', 'crow', `${ICO[c.icon] ? `<svg width="14" height="14"><use href="#i-${ICO[c.icon]}"/></svg>` : '<i></i>'}<b>${esc(c.title)}</b>${c.detail ? `<span class="s">${esc(c.detail)}</span>` : ''}`); if (c.goal) r.onclick = () => { hap('tick'); sendUser(c.goal); }; return r; }));
  body.append(list); scrollDown(); });
on('tool.request', (m) => {
  const a = m.args || {}, what = m.name === 'open_app' ? appName(a.pkg) : m.name === 'task.start' ? (a.apps || []).map(appName).join(', ') : a.text ? `“${short(a.text, 30)}”` : a.id != null ? `#${a.id}` : a.key || a.pkg || '';
  const line = m.name === 'open_app' && what ? `Opening ${what}` : `${toolName(m.name)} ${what}`.trim(); // "Opening Pigeon", not "Opening an app Pigeon"
  const s = el('div', 'step', `<span class="orb working"></span><span>${esc(line)}</span>`);
  body.append(s); steps[m.name] = s; aiMsg = null; S.turn = true; S.step = line; setSheet('Working', 'working'); think(); renderNow();
});
on('tool.result', (m) => {
  const s = steps[m.name]; if (!s) return;
  const d = m.decision || (m.ok ? 'auto' : 'failed'), bad = /denied|blocked|failed/.test(d);
  $('.orb', s).className = 'orb ' + (bad ? 'alert' : 'done');
  if (d !== 'auto') s.append(el('span', 'tag ' + (bad ? 'c' : 'g'), d === 'approved' ? 'Approved' : d));
  S.pending = null; renderStatus();
});
// Approvals happen on the phone's native card only. The sheet shows one quiet line, from the broker's events.
const waiting = (m) => { S.pending = m; setSheet('Waiting for your approval', 'approval'); live(); };
on('approval.shown', waiting); on('approval.needed', waiting); // approval.needed = agentd's guess when the broker stream is down
on('approval.resolved', (m) => {
  S.pending = null; const ok = m.decision === 'approved';
  setSheet(ok ? (m.approver === 'reviewer' ? 'Auto-approved ✓' : 'Approved') : m.decision === 'timeout' ? 'Timed out' : 'Denied', ok ? 'done' : 'alert'); live();
});
on('guard.alert', (m) => { body.append(el('div', 'step alert', `<svg width="14" height="14"><use href="#i-shield"/></svg><span>Suspicious message in ${esc(appName(m.source_pkg))}</span>`)); scrollDown(); });
on('error', (m) => { if (m.code === 'unknown_type') { if (m.message === 'task.start' && S.goal) { open('sheet'); sendUser(S.goal); } return; }
  if (m.retryable && m.code !== 'turn_failed') return setSheet('Reconnecting…', 'working'); // codex/agentd retries: one calm state, not a line per attempt
  body.append(el('div', 'msg err', esc(/proxy|websocket|network|connect|dns|stream|exited|timed? ?out/i.test(m.message) ? 'Couldn’t reach the AI. Check the connection and try again.' : short(m.message, 160)))); scrollDown(); }); // older agentd: run the suggestion as a plain ask
on('turn.done', (m) => { S.turn = false; S.pending = null; S.step = null; steps = {}; setSheet(m.status === 'completed' || m.status === 'done' ? 'Done' : { failed: 'Didn’t finish', interrupted: 'Stopped' }[m.status] || 'Done', 'done', m.ms); if (S.home) { S.home.done = true; setTimeout(() => { if (S.home?.done) { S.home = null; renderNow(); } }, 20000); } live(); const q = S.queue.shift(); if (q) sendUser(...q); });
on('ws.open', () => { refresh(); loadTrackers(); loadMemory(); loadMyApps(); send({ type: 'suggestions.watch' }); send({ type: 'task.list' }); send({ type: 'schedule.list' }); send({ type: 'plugins.list' }); send({ type: 'remote', action: 'status' }); }); on('ws.close', () => { S.turn = false; S.queue.length = 0; S.active = null; S.sugg = []; renderSugg(); for (const id in pend) app.rpcDone(id, { ok: false, error: 'offline' }); live(); });

// ---------- wake word (launcher Wake.java): a toggle in the command center, a small mic on the home status line ----------
const WHY = { voice: 'Paused · voice open', call: 'Paused · call', mic: 'Paused · mic in use', screen: 'Paused · screen off' };
app.wakeState = (w) => {
  const wk = $('#wk'), nokey = w.state === 'nokey', live = w.on && !nokey && w.state !== 'off';
  wk.style.display = live ? '' : 'none'; wk.classList.toggle('p', w.state !== 'listening'); // (SVG ignores `hidden`)
  const sub = nokey ? 'Needs a Picovoice key' : !w.on ? 'Off' : w.state === 'listening' ? 'Listening' : w.state === 'error' ? 'Couldn’t start' : WHY[w.why] || 'Starting';
  const r = el('div', 'rowi', `<span class="appic"><svg width="16" height="16"><use href="#i-mic"/></svg></span><div>“${esc(w.phrase || 'Hey Agent')}”<span class="s">${sub}</span></div><div class="r"><button class="toggle${w.on && !nokey ? ' on' : ''}" aria-label="Wake word"${nokey ? ' disabled' : ''}></button></div>`);
  $('button', r).onclick = () => { hap(); app.wakeState(JSON.parse(N.wake(w.on ? 'off' : 'on'))); };
  $('#wake').replaceChildren(r);
};
app.wake = () => { hap('heavy'); if (!$('#voice').classList.contains('on')) voiceStart(); };
app.wakeState(N.wake ? JSON.parse(N.wake('state')) : { state: 'nokey', phrase: 'Hey Agent' });
if (info.wake) setTimeout(app.wake, 400); // cold start by the wake word

// ---------- voice (WebRTC; hands-free listening, speaker echo gated, orb mutes or interrupts) ----------
let pc, mic, micTrack, cam, vad, voiceAudio, voiceLive = false, voiceIdle, voiceEpoch = 0;
const V = { state: (s) => ($('#vstate').textContent = s) };
async function voiceStart() {
  if (micTrack?.readyState === 'live') return;
  const epoch = ++voiceEpoch; clearTimeout(voiceIdle);
  N.wake?.('voice:1'); // the wake word lets go of the mic while voice mode is open
  open('voice'); $('#voice').classList.remove('live'); $('#vu').textContent = $('#va').textContent = ''; $('#vsteps').replaceChildren(); $('#vtask').replaceChildren(); V.state('Connecting');
  if (!wsOk) { N.wake?.('voice:0'); return V.state('Agent offline'); }
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: voiceConstraints });
    if (epoch !== voiceEpoch) return stream.getTracks().forEach((t) => t.stop());
    mic = stream; $('#spk').muted = false;
    micTrack = mic.getAudioTracks()[0];
    if (pc?.connectionState === 'connected' && voiceAudio) { voiceAudio.track = micTrack; voiceAudio.active = true; voiceAudio.muted = false; voiceAudio.apply(); }
    else voiceAudio = new VoiceAudio(micTrack, $('#spk'), (state) => { if (micTrack) { V.state(state); sorb.classList.toggle('muted', !micTrack.enabled); } });
    if (pc?.connectionState !== 'connected') $('#spk').srcObject = null;
    vad = speech(mic);
    if (pc?.connectionState === 'connected') { await pc.getSenders()[0].replaceTrack(micTrack); if (epoch === voiceEpoch) voiceAudio.apply(); return; }
    pc?.close(); pc = new RTCPeerConnection(); pc.addTrack(micTrack, mic);
    const dc = pc.createDataChannel('oai-events'); // must exist before createOffer
    dc.onopen = () => { if (epoch === voiceEpoch) V.state('Listening'); };
    dc.onmessage = (e) => voiceMessage(JSON.parse(e.data));
    pc.ontrack = (e) => { $('#spk').srcObject = e.streams[0]; };
    await pc.setLocalDescription(await pc.createOffer());
    await new Promise((ok) => { setTimeout(ok, 500); pc.onicegatheringstatechange = () => pc.iceGatheringState === 'complete' && ok(); });
    if (epoch === voiceEpoch) send({ type: 'voice.start', client_handoffs: true, sdp: pc.localDescription.sdp });
  } catch (e) { if (epoch === voiceEpoch) { voiceStop(); V.state('Mic unavailable'); } }
}
function voiceMessage(m) {
  voiceAudio?.event(m);
  if (/^(turn\.(created|done)|input_audio_buffer\.|output_audio_buffer\.|response\.|conversation\.item\.truncated|error)/.test(m.type) && !/delta/.test(m.type)) send({ type: 'voice.audio', event: m.type, role: m.turn?.role, mic: !!micTrack?.enabled });
  if (m.type === 'delegation.created') send({ type: 'voice.delegate', item_id: m.item.id, handoff_id: m.item.handoff_id, turn_id: m.item.user_bidi_turn_id, text: m.item.content.map((c) => c.text).join('') });
  if (m.type === 'turn.done' && m.turn?.role === 'user') send({ type: 'voice.heard', turn_id: m.turn.id, text: m.turn.transcript });
  if (!micTrack) return;
  if (m.type === 'turn.created' && m.turn?.role === 'user') { voiceLive = true; $('#va').textContent = ''; }
  if (m.type === 'output_transcript.added') { voiceLive = true; $('#va').textContent += m.item.text; }
  if (m.type === 'turn.done' && m.turn?.role === 'user') $('#vu').textContent = m.turn.transcript;
  if (voiceLive) { $('#voice').classList.add('live'); const tx = $('.tx'); tx.scrollTop = tx.scrollHeight; }
}
function voiceDrop() { clearTimeout(voiceIdle); voiceAudio = null; voiceLive = false; if (pc) { send({ type: 'voice.stop' }); pc.close(); pc = null; } }
function voiceStop() {
  ++voiceEpoch; if (cam) camOff(); vad?.(); vad = null; attMenu.hidden = true;
  if (voiceAudio) { voiceAudio.active = false; voiceAudio.apply(); }
  mic?.getTracks().forEach((t) => t.stop()); mic = micTrack = null; N.wake?.('voice:0'); $('#spk').muted = true; $('#wave').classList.remove('on');
  // Release the microphone for the wake word; keep only the connection warm for a quick return.
  clearTimeout(voiceIdle);
  if (pc?.connectionState === 'connected') { pc.getSenders()[0].replaceTrack(null).catch(() => {}); voiceIdle = setTimeout(voiceDrop, 60000); }
  else voiceDrop();
}
on('voice.answer', async (m) => { $('#vatt').hidden = !m.vision; if (pc) await pc.setRemoteDescription({ type: 'answer', sdp: m.sdp }); });
on('ws.close', () => { voiceStop(); voiceDrop(); });
on('voice.transcript', (m) => { if (voiceLive || !micTrack) return; $('#voice').classList.add('live'); const p = $(m.role === 'user' ? '#vu' : '#va'); p.innerHTML = esc(m.text) + (m.final ? '' : '<span class="cur"></span>'); if (m.role !== 'user') V.state('Speaking'); });
on('voice.delegation', (m) => { V.state('Working'); $('#vsteps').replaceChildren(); renderVoiceTask(); });
function renderVoiceTask() {
  if (!$('#voice').classList.contains('on')) return;
  const w = [...S.tasks.values()].filter((w) => w.id !== 'opt').sort((a, b) => WORKING.has(b.state) - WORKING.has(a.state) || b.created - a.created)[0];
  swap($('#vtask'), ...(w && Date.now() - w.created < 120000 ? [workerCard(w)] : []));
}
on('voice.result', (m) => { if (!voiceLive) { V.state('Speaking'); $('#va').textContent = m.text; } });
on('voice.seen', (m) => { if (m.text && $('#voice').classList.contains('on')) $('#vsteps').append(el('li', '', `<span class="orb done sm"></span>${m.source === 'screen' ? 'Looked at your screen' : 'Looked'}`)); });
on('voice.closed', () => { voiceStop(); voiceDrop(); V.state('Ended'); }); on('voice.error', (m) => { voiceStop(); voiceDrop(); V.state(short(m.message, 40)); });
on('tool.request', (m) => { if ($('#voice').classList.contains('on')) { $('#vsteps').append(el('li', '', `<span class="orb working sm"></span>${esc(toolName(m.name))}`)); } });
on('tool.result', (m) => { const li = $('#vsteps li:last-child'); if (li) $('.orb', li).className = 'orb sm ' + (/denied|blocked/.test(m.decision || '') ? 'alert' : 'done'); });
const sorb = $('#sorbBtn');
sorb.onclick = () => { if (!micTrack) return; hap('tick'); voiceAudio.toggle(); };
// Local speech detector (same as voice.html's meter): lights the orb while you talk and, with the live camera on, sends the
// frame at the start of each utterance (at most one per 3 s) so "what's this?" while pointing just works.
function speech(stream) {
  const ac = new AudioContext(), an = ac.createAnalyser(), buf = new Float32Array(1024); ac.createMediaStreamSource(stream).connect(an);
  let on = false, last = 0, shot = 0, remote;
  const i = setInterval(() => {
    if (!remote && $('#spk').srcObject) { remote = ac.createAnalyser(); ac.createMediaStreamSource($('#spk').srcObject).connect(remote); }
    if (remote) { remote.getFloatTimeDomainData(buf); voiceAudio?.sample(Math.sqrt(buf.reduce((s, v) => s + v * v, 0) / buf.length)); }
    an.getFloatTimeDomainData(buf); const t = Date.now(), loud = micTrack?.enabled && Math.sqrt(buf.reduce((s, v) => s + v * v, 0) / buf.length) > 0.02;
    if (loud) { last = t; if (!on) { on = true; sorb.classList.add('hot'); $('#wave').classList.add('on'); $('#voice').classList.add('live'); if (cam && t - shot > 3000) { shot = t; snap(); } } }
    else if (on && t - last > 600) { on = false; sorb.classList.remove('hot'); $('#wave').classList.remove('on'); }
  }, 30);
  return () => { clearInterval(i); ac.close(); };
}
$('#vend').onclick = () => { hap(); close('voice'); };
// Live camera (agentd/research/LIVE-VISION.md): a small preview; each time you start talking agentd gets one 768 px JPEG of
// what it sees (never a stream). agentd's vision model describes it and the voice answers. Tap the preview to stop.
$('#vcam').onclick = () => { hap(); camOff(); };
async function liveCam() {
  if (cam) return camOff();
  try {
    cam = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment', width: { ideal: 1280 } } });
    const v = $('#vcam'); v.srcObject = cam; v.hidden = false; await v.play();
    send({ type: 'voice.camera', on: true });
  } catch (e) { cam = null; V.state('Camera unavailable'); }
}
function camOff() { cam.getTracks().forEach((k) => k.stop()); cam = null; $('#vcam').hidden = true; send({ type: 'voice.camera', on: false }); }
function snap() {
  const v = $('#vcam'); if (!cam || !v.videoWidth) return;
  const k = Math.min(1, 768 / Math.max(v.videoWidth, v.videoHeight)), c = document.createElement('canvas');
  c.width = Math.round(v.videoWidth * k); c.height = Math.round(v.videoHeight * k); c.getContext('2d').drawImage(v, 0, 0, c.width, c.height);
  send({ type: 'voice.frame', image: c.toDataURL('image/jpeg', 0.7) });
}
$('#vkbd').onclick = () => { hap(); close('voice'); open('sheet'); setTimeout(() => $('#askIn').focus(), 350); };
(() => { const w = $('#wave'); for (let i = 0; i < 24; i++) { const b = el('i'); b.style.setProperty('--h', (0.3 + Math.abs(Math.sin(i * .7)) * .7).toFixed(2)); b.style.animationDelay = (i * .06) + 's'; b.style.animationDuration = (.9 + Math.random() * .6) + 's'; w.append(b); } })();

// ---------- memory graph ----------
let graphAt = 0;
async function loadMemory() {
  if (Date.now() - graphAt < 15000) return;
  try {
    const g = window.Native ? await nget('/memory/graph.json') : await (await fetch(GRAPH, { headers: { Authorization: 'Bearer ' + N.token('agentd') } })).json();
    if (!g.nodes) throw new Error(g.error || 'no graph');
    graphAt = Date.now(); $('#memNote').textContent = $('#memNote2').textContent = `${g.nodes.filter((n) => n.data.type === 'person').length} people`; // apps, places and sites aren't people
    for (const n of g.nodes) if (n.data.type === 'app' && /^\w+(\.\w+)+$/.test(n.data.label)) n.data.label = appName(n.data.label); // "com.spotify.music" -> "Spotify"
    drawGraph(g); renderSources($('#sources'), g.consents); S.consents = g.consents;
  } catch (e) { $('#memNote').textContent = $('#memNote2').textContent = 'offline'; }
}
function drawGraph(g) {
  const svg = $('#gsvg'), W = svg.clientWidth || innerWidth - 44, H = 320, cx = W / 2, cy = H / 2;
  // Draw the strongest ~28 links only (a hairball says nothing); test fixtures and local hosts never belong in the picture.
  const noise = (n) => /\btest\b|fixture|localhost|127\.0\.0\.1|^\d+\.\d+\.\d+\.\d+|agentos test/i.test(n.label || '');
  const kept = g.nodes.map((n) => n.data).filter((n) => n.type === 'me' || !noise(n)).sort((a, b) => (b.type === 'me') - (a.type === 'me') || (b.weight || 0) - (a.weight || 0)).slice(0, 28);
  const nodes = kept.map((n, i) => ({ ...n, x: cx + (Math.random() - .5) * 120, y: cy + (Math.random() - .5) * 120, r: n.type === 'me' ? 22 : 9 + Math.min(14, n.weight || 0) * .6 }));
  for (const [i, n] of nodes.entries()) { n.text = i < (['me', 'person', 'group'].includes(n.type) ? 14 : 6) && !/^unknown$/i.test(n.label || '') ? short((n.label || '').replace(/^Unknown /, ''), 14) : ''; n.half = 6 + n.text.length * 3.4; } // label, kept inside the box
  const byId = Object.fromEntries(nodes.map((n) => [n.id, n])), me = byId[g.me]; if (me) { me.x = cx; me.y = cy; }
  const edges = g.edges.map((e) => e.data).filter((e) => byId[e.source] && byId[e.target]);
  for (let k = 0; k < 260; k++) { // tiny force layout: repel, spring along edges, drift to center
    for (const a of nodes) for (const b of nodes) if (a !== b) { const dx = a.x - b.x, dy = a.y - b.y, d2 = dx * dx + dy * dy + 1, f = 9000 / d2; a.x += dx * f / Math.sqrt(d2) * .5; a.y += dy * f / Math.sqrt(d2) * .5; }
    for (const e of edges) { const a = byId[e.source], b = byId[e.target], dx = b.x - a.x, dy = b.y - a.y, d = Math.hypot(dx, dy) || 1, f = (d - (90 - Math.min(30, e.weight * 4))) * .02; a.x += dx / d * f; a.y += dy / d * f; b.x -= dx / d * f; b.y -= dy / d * f; }
    for (const n of nodes) { if (n === me) { n.x = cx; n.y = cy; continue; } n.x += (cx - n.x) * .01; n.y += (cy - n.y) * .01; n.x = Math.max(n.r + 4, Math.min(W - n.r - 4, n.x)); n.y = Math.max(n.r + 4, Math.min(H - n.r - 40, n.y)); }
  }
  const placed = []; // strongest first: a label that would sit on another one stays quiet
  for (const n of nodes) { if (!n.text) continue; const lx = Math.max(n.half, Math.min(W - n.half, n.x)), ly = n.y + n.r; if (placed.some((p) => Math.abs(p.y - ly) < 18 && Math.abs(p.x - lx) < p.half + n.half)) n.text = ''; else placed.push({ x: lx, y: ly, half: n.half }); }
  const ns = 'http://www.w3.org/2000/svg', mk = (t, a) => { const e = document.createElementNS(ns, t); for (const k in a) e.setAttribute(k, a[k]); return e; };
  svg.replaceChildren(...edges.map((e) => mk('line', { x1: byId[e.source].x, y1: byId[e.source].y, x2: byId[e.target].x, y2: byId[e.target].y, 'stroke-width': 1 + Math.min(4, e.weight / 2) })),
    ...nodes.map((n) => { const gg = mk('g', { class: ['me', 'person', 'group'].includes(n.type) ? n.type : 'other' });
      gg.append(mk('circle', { cx: n.x, cy: n.y, r: n.r }), Object.assign(mk('text', { x: Math.max(n.half, Math.min(W - n.half, n.x)), y: n.y + n.r + 13 }), { textContent: n.text }));
      gg.onclick = () => { hap('tick'); $$('g.sel', svg).forEach((x) => x.classList.remove('sel')); gg.classList.add('sel');
        const es = edges.filter((e) => e.source === n.id || e.target === n.id), last = es.map((e) => e.last_seen).sort().pop();
        $('#gdet').innerHTML = n.type === 'me' ? `<b>You</b> · ${es.length} people` : `<b>${esc(n.label)}</b> · ${es.reduce((s, e) => s + e.weight, 0)} ${esc(es[0]?.type || 'links')} via ${esc([...new Set(es.map((e) => e.via))].join(', '))}${last ? ' · ' + new Date(last).toLocaleDateString([], { month: 'short', day: 'numeric' }) : ''}`; };
      return gg; }));
}
function renderSources(box, consents = S.consents) {
  if (!consents) return box.replaceChildren(el('div', 'empty', 'Offline'));
  if (!consents.length) return box.replaceChildren(el('div', 'empty', 'None'));
  box.replaceChildren(...consents.map((c) => { const r = el('div', 'rowi', `<span class="appic" style="background:var(--purple)">${esc(c.source[0].toUpperCase())}</span><div>${esc(({ calllog: 'Call log', sms: 'Texts' })[c.source] || c.source.replace(/^\w/, (ch) => ch.toUpperCase()))}<span class="s">${c.events} item${c.events === 1 ? '' : 's'} · ${esc(c.scope)}</span></div><div class="r"><button class="toggle${c.active ? ' on' : ''}" aria-label="${c.active ? 'Allowed' : 'Off'}"></button></div>`);
    // The owner's toggle (agentd checks it's this app's uid). Turning a source off deletes what memory has from it, so that asks twice.
    const t = $('button', r), s = $('.s', r); t.onclick = () => { hap(); if (c.active && !t.dataset.sure) { t.dataset.sure = 1; s.textContent = `Tap again to forget ${c.events} item${c.events === 1 ? '' : 's'}`; setTimeout(() => t.dataset.sure && renderSources(box), 3000); return; }
      t.dataset.sure = ''; t.classList.toggle('on'); t.style.opacity = .5; if (!send({ type: 'memory.consent', source: c.source, action: c.active ? 'revoke' : 'grant', scope: c.scope })) renderSources(box); }; return r; }));
}
on('memory.consent', async (m) => { if (!m.ok) hap('heavy'); graphAt = 0; await loadMemory(); renderSources($('#psources')); setTimeout(() => { graphAt = 0; loadMemory(); }, 8000); }); // a grant re-reads the source; counts land a few seconds later

// ---------- boot ----------
loadApps(); renderCalendar(); live(); go(0);
// Browser-only styling aid (`index.html#demo` on the Mac, never on the phone): sample feed + a running task.
if (location.hash.startsWith('#demo')) { // styling aid: only reachable through install.sh dev <url>#demo
  emit({ type: 'suggestions', items: [{ id: 1, title: 'Reply to Sam: running late?', detail: 'Messages · 12 min ago', app: 'local.agentos.pigeon', goal: 'x' }, { id: 2, title: 'Leave for 3 PM meeting in 12 min', detail: '18 min drive · Maps', icon: 'car', goal: 'x' }, { id: 3, title: 'You promised Priya the Q3 numbers', detail: 'Due today · Messages', icon: 'spark', goal: 'x' }] });
  if (location.hash === '#demo') S.active = { task_id: 't1', goal: 'Reply to Mom that I am 10 minutes late', apps: ['local.agentos.pigeon'], expires: Date.now() + 5e5, steps: 3, max_steps: 40 }; S.step = 'Tap "Send"'; S.tasks.set('k1', { id: 'k1', goal: 'Reply to Mom that I am 10 minutes late', state: 'running', step: 'Tapping Send in Pigeon', created: 1 }); live();
  if (location.hash === '#demo-blockers') { open('blockers'); S.trackers = [{ pkg: 'com.handmark.expressweather', label: '1Weather', trackers: [{ domain: 'app-measurement.com', company: 'Google', blocked: true, hits: 14 }, { domain: 'ads.mopub.com', company: 'MoPub', blocked: false, hits: 6 }] }, { pkg: 'com.android.chrome', label: 'Chrome', trackers: [{ domain: 'doubleclick.net', company: 'Google', blocked: true, hits: 3 }] }]; renderBlockers(); }
  if (location.hash === '#demo-center') go(1);
}
