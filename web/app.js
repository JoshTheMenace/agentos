/* AgentOS home (A2 Calm Glass). One file, no framework. The native shell exposes `Native` (see launcher/Home.java);
   in a desktop browser the shims below keep the page alive for styling work. */
'use strict';
const $ = (s, r = document) => r.querySelector(s), $$ = (s, r = document) => [...r.querySelectorAll(s)];
const el = (tag, cls, html) => { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; };
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const N = window.Native || { // browser shims
  info: () => JSON.stringify({ pkg: 'browser', dev: true, top: 28, bottom: 0, ime: 0 }), token: () => null, launch: () => false, haptic: () => {},
  settings: () => {}, shade: () => {}, calendarNext: () => null, get: (id) => setTimeout(() => app.rpcDone(id, { ok: false, error: 'browser' })),
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
const nget = (path) => new Promise((ok) => { const id = String(++rid); pend[id] = ok; N.get(id, path); }); // agentd GET via the shell (cross-origin)
app.rpcDone = (id, r) => { pend[id]?.(r); delete pend[id]; };
on('rpc.result', (m) => app.rpcDone(m.id, m.result));

// ---------- state ----------
const S = { active: null, turn: false, pending: null, apps: [], names: {}, provider: localStorage.provider || 'codex', audit: [], sugg: [], step: null, home: null, trackers: null, tasks: new Map(), mods: null }; // tasks = agentd background workers (task.update) // active = latest task.state; home = a turn started from a home suggestion
const appName = (pkg) => S.names[pkg] || (pkg || '').split('.').pop().replace(/^\w/, (c) => c.toUpperCase());
const fmtT = (ts, sec) => new Date(ts).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', ...(sec ? { second: '2-digit' } : {}) }).replace(/\s?[AP]M/i, '');
const short = (s, n = 48) => (s = String(s || '')).length > n ? s.slice(0, n - 1) + '…' : s;

// ---------- clock ----------
function clock() {
  const d = new Date();
  $('#time').textContent = fmtT(d);
  $('#date').textContent = d.toLocaleDateString([], { weekday: 'long', month: 'long', day: 'numeric' });
}
clock(); setInterval(clock, 1000);

// ---------- audit lines -> text ----------
const auditLine = (e) => {
  const raw = /^[A-Z]\w*(Layout|View|Group|Bar|Pane|Container)$/.test(e.target || '') || /^(android|com|androidx)\./.test(e.target || ''); // class name, not a label
  const app_ = appName(e.pkg), tgt = e.target && !raw ? `"${short(e.target, 24)}"` : '';
  switch (e.tool) {
    case 'task.start': return ['Started · ' + short(e.goal, 40), 'a'];
    case 'task.stop': return [`Stopped · ${e.steps ?? 0} steps`, ''];
    case 'task.pause': return ['Paused', ''];
    case 'task.resume': return ['Resumed', ''];
    case 'tap': return [tgt ? `Tap ${tgt} · ${app_}` : `Tap in ${app_}`, e.decision === 'approved' ? 'g' : e.decision === 'denied' || e.decision === 'blocked' ? 'c' : e.tier?.startsWith('T3') ? 'b' : 'a'];
    case 'type': return [`Type in ${app_}`, e.decision === 'blocked' ? 'c' : 'a'];
    case 'open_app': return [`Open ${app_}`, 'a'];
    case 'firewall.set': return [`Firewall · ${app_} ${e.mode || ''}`, e.decision === 'approved' ? 'g' : 'c'];
    case 'firewall.clear': return ['Firewall cleared', e.decision === 'approved' ? 'g' : 'c'];
    case 'guard.flag': return [`Suspicious text in ${app_} · hidden from the agent`, 'c'];
    case 'tracker.blocked': return [`Blocked ${short(e.domain, 28)} · ${app_}`, 'g'];
    case 'notifications.reply': return [`Reply ${tgt} · ${app_}`, e.decision === 'approved' ? 'g' : e.decision === 'denied' || e.decision === 'blocked' ? 'c' : 'a'];
    case 'notifications.autoreply': return [`Auto-reply ${tgt} · ${app_}`, e.decision === 'blocked' || e.decision === 'denied' ? 'c' : 'g'];
    default: return [`${e.tool.replace(/[._]/g, ' ').replace(/^\w/, (c) => c.toUpperCase())} ${tgt} ${app_ || ''}`.trim(), e.decision === 'approved' ? 'g' : e.decision === 'denied' || e.decision === 'blocked' ? 'c' : 'a'];
  }
};

// One snapshot on connect/resume; after that the broker's events (via agentd) keep it live: no polling.
const live = () => { renderStatus(); renderNow(); renderCenter(); if ($('#perms').classList.contains('on')) renderGrants(); };
const alive = (t) => t && t.expires > Date.now() ? t : null;
async function refresh() {
  const [a, l] = await Promise.all([rpc('audit.tail', { n: 80 }), rpc('approval.list')]);
  if (a?.entries) S.audit = a.entries;
  if (l?.approvals) { S.active = alive(l.task); S.pending = l.approvals[0] || null; }
  live(); renderCalendar(); if (!S.mods) loadMods();
}
on('task.state', (m) => { if (m.state === 'stopped') { if (S.active?.task_id === m.task_id) S.active = null; } else S.active = alive(m); live(); });
on('audit.append', (m) => { S.audit = [...S.audit.slice(-199), m]; renderNow(); renderCenter(); });
on('guard.flag', (m) => { S.audit = [...S.audit.slice(-199), { ...m, tool: 'guard.flag', ts: Date.now() }]; renderCenter(); });
setInterval(() => S.active && live(), 30000); // the minutes-left countdown

// ---------- home ----------
function renderStatus() {
  const orb = $('#sorb'), corb = $('#corb');
  const cls = S.pending ? 'approval' : S.turn || S.active ? 'working' : '';
  orb.className = corb.className = 'orb ' + cls;
  $('#status').textContent = S.pending ? 'Waiting for you' : S.turn || S.active ? 'Working' : wsOk ? 'Ready' : 'Agent offline';
}
// Now: the live task (goal, current step, gentle progress). Hidden when nothing runs; a finished home task lingers with its answer.
const WORKING = new Set(['queued', 'running', 'waiting']);
function workerCard(w) {
  const on = WORKING.has(w.state), cls = w.state === 'waiting' ? 'approval' : on ? 'working' : w.state === 'done' ? 'done' : 'alert';
  const c = el('button', `glass now ${cls}${on ? '' : ' done'}`, `<div class="hd"><span class="orb ${cls}"></span><div><div class="t">${esc(short(w.goal, 40))}</div><div class="s">${esc(short(on ? w.step : w.result || w.error || w.step, 70))}</div></div>${on ? `<button class="ib red" data-a="cancel"><svg width="14" height="14"><use href="#i-stop"/></svg></button>` : ''}</div><div class="bar2"><i></i></div>`);
  c.onclick = (e) => { hap(); if (e.target.closest('[data-a]')) { send({ type: 'task.cancel', id: w.id }); return; } open('sheet'); };
  return c;
}
on('task.update', (m) => { const w = m.task; if (!w?.id) return; S.tasks.set(w.id, w); if (!WORKING.has(w.state)) setTimeout(() => { if (S.tasks.get(w.id) === w) { S.tasks.delete(w.id); renderNow(); renderCenter(); } }, 20000); renderNow(); renderCenter(); });
on('task.list', (m) => { S.tasks = new Map((m.tasks || []).filter((w) => WORKING.has(w.state)).map((w) => [w.id, w])); renderNow(); renderCenter(); });
function renderNow() {
  const box = $('#now'), t = S.active, h = S.home, ws_ = [...S.tasks.values()].sort((a, b) => WORKING.has(b.state) - WORKING.has(a.state) || b.created - a.created);
  if (ws_.length) return box.replaceChildren(...ws_.slice(0, 3).map(workerCard));
  if (!t && !S.turn) { if (h?.done) { const c = el('button', 'glass now done', `<div class="hd"><span class="orb done"></span><div><div class="t">${esc(short(h.goal, 40))}</div><div class="s">${esc(short(h.text || 'Done', 60))}</div></div></div>`); c.onclick = () => { hap(); open('sheet'); }; return box.replaceChildren(c); } return box.replaceChildren(); }
  const goal = short(t?.goal || h?.goal || 'Thinking', 40), last = t && S.audit.filter((e) => e.task === t.task_id && !/^task\./.test(e.tool)).pop();
  const step = S.pending ? 'Waiting for you' : t?.paused ? 'Paused' : S.step || (last ? auditLine(last)[0] : 'Starting'), cls = S.pending ? 'approval' : 'working';
  const c = el('button', 'glass now ' + cls, `<div class="hd"><span class="orb ${cls}"></span><div><div class="t">${esc(goal)}</div><div class="s">${esc(step)}${t ? ' · ' + esc(t.apps.map(appName).join(', ')) : ''}</div></div>${t ? `<button class="ib" data-a="${t.paused ? 'resume' : 'pause'}"><svg width="14" height="14"><use href="#i-${t.paused ? 'play' : 'pause'}"/></svg></button>` : ''}<button class="ib red" data-a="stop"><svg width="14" height="14"><use href="#i-stop"/></svg></button></div><div class="bar2"><i></i></div>`);
  c.onclick = async (e) => { const a = e.target.closest('[data-a]')?.dataset.a; hap(); if (!a) return open('sheet'); if (a === 'stop') { send({ type: 'stop' }); S.turn = false; } if (t) await rpc('task.' + a, { task_id: t.task_id }); refresh(); };
  box.replaceChildren(c);
}
// Suggestions: agentd's feed (context) plus one from the real next calendar event. One tap starts the task; nothing else to read.
const SICON = { chat: '#i-msg', msg: '#i-msg', car: '#i-car', eye: '#i-eye', shield: '#i-shield', cal: '#i-cal', spark: '#i-spark' };
function renderSugg() {
  const items = [...S.sugg];
  const c = S.cal, mins = c ? Math.round((c.begin - Date.now()) / 60000) : 0;
  if (c && c.location && mins > 0 && mins <= 120 && !c.allDay) items.unshift({ id: 'leave', title: `Leave for ${short(c.title, 22)} soon`, detail: `${fmtT(c.begin)} · ${short(c.location, 30)}`, icon: 'car', goal: `Open directions to ${c.location} in Maps` });
  $('#forK').textContent = items.length ? 'For you' : '';
  $('.feed').classList.toggle('none', !items.length);
  if (!items.length) return $('#sugg').replaceChildren(el('div', 'calm', `<svg width="14" height="14"><use href="#i-check"/></svg>All caught up`));
  $('#sugg').replaceChildren(...items.slice(0, 4).map((it, i) => {
    const b = el('button', 'glass sg'); b.style.animationDelay = i * .05 + 's';
    const ic = it.app && S.names[it.app] ? `<span class="appic">${ICOHTML(it.app)}</span>` : `<span class="appic" style="background:${it.icon === 'eye' || it.icon === 'shield' ? 'var(--green)' : it.icon === 'car' ? 'var(--blue)' : 'var(--purple)'}"><svg width="18" height="18"><use href="${SICON[it.icon] || '#i-spark'}"/></svg></span>`;
    b.innerHTML = `${ic}<div><div class="t">${esc(it.title)}</div><div class="s">${esc(it.detail || '')}</div></div><span class="orb working sm"></span><svg width="16" height="16" class="chev"><use href="#i-chev"/></svg>`;
    b.onclick = () => startSuggestion(it, b); return b;
  }));
}
function startSuggestion(it, b) {
  hap('heavy');
  if (it.view === 'blockers') return openBlockers();
  if (it.view === 'activity') return go(1);
  if (!it.goal) return;
  b.classList.add('busy');
  S.goal = it.goal; if (!send({ type: 'task.start', goal: it.goal })) return b.classList.remove('busy'); // a background worker; progress arrives as task.update
  S.sugg = S.sugg.filter((x) => x !== it);
  setTimeout(() => { b.classList.add('gone'); setTimeout(renderSugg, 400); }, 500);
}
on('suggestions', (m) => { if (m.id) return; S.sugg = (m.items || []).filter((x) => x && typeof x === 'object'); renderSugg(); }); // keyboard replies carry an id; the home feed doesn't
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

// ---------- pager + gestures ----------
const pager = $('#pager'); let page = 0, W = innerWidth;
function go(p) {
  page = Math.max(0, Math.min(2, p)); pager.classList.remove('drag'); pager.style.transform = `translateX(${-page * W}px)`;
  $$('#dots i').forEach((d, i) => d.classList.toggle('on', i === page));
  if (page === 1) renderCenter(); if (page === 2) loadMemory();
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
function open(id) { const o = $('#' + id); if (o.classList.contains('on')) return; o.classList.add('on'); stack.push(id); if (id === 'sheet') $('#scrim').classList.add('on'); hap('tick'); }
function close(id) { const o = $('#' + id); o.classList.remove('on'); const i = stack.indexOf(id); if (i >= 0) stack.splice(i, 1); if (id === 'sheet') { $('#scrim').classList.remove('on'); $('#askIn').blur(); } if (id === 'voice') voiceStop(); }
app.back = () => { if (stack.length) close(stack[stack.length - 1]); else if (page) go(0); };
app.home = () => { [...stack].forEach(close); go(0); };
app.resume = () => { W = innerWidth; wsOk && refresh(); if (page === 2) loadMemory(); };
$('#scrim').onclick = () => close('sheet');
$('#permsBack').onclick = () => close('perms');
$('#drawer .grab').onclick = () => close('drawer');
$('#toBlockers').onclick = $('#toBlockers2').onclick = () => { hap(); openBlockers(); };
$('#blockersBack').onclick = () => close('blockers');
$('#toMods').onclick = () => { hap(); openMods(); };
$('#modsBack').onclick = () => close('mods');
$('#toPerms').onclick = () => { hap(); openPerms(); };
$('#toSettings').onclick = () => { hap(); N.settings(''); };
$('#cmd').onclick = (e) => { if (e.target.closest('.mic')) return; hap(); open('sheet'); setTimeout(() => $('#askIn').focus(), 350); };
$('#micBtn').onclick = () => { hap('heavy'); voiceStart(); };

// ---------- command center ----------
function renderCenter() {
  const box = $('#tasks'), rows = [];
  const t = S.active;
  for (const w of S.tasks.values()) if (WORKING.has(w.state)) rows.push(el('div', 'rowi', `<span class="orb ${w.state === 'waiting' ? 'approval' : 'working'}"></span><div>${esc(short(w.goal, 34))}<span class="s">${esc(w.step)}</span></div>`));
  if (t && ![...S.tasks.values()].some((w) => w.lease_id === t.task_id)) rows.push(taskRow(t));
  if (S.turn && !t) rows.push(el('div', 'rowi', `<span class="orb working"></span><div>Thinking<span class="s">${esc(S.provider)}</span></div>`));
  if (!rows.length) rows.push(el('div', 'empty', 'Nothing running'));
  box.replaceChildren(...rows);
  $('#taskCount').textContent = rows.length && !box.querySelector('.empty') ? `${rows.length} running` : '';
  $$('#prov button').forEach((b) => b.classList.toggle('on', b.dataset.p === S.provider));
  const today = new Date().setHours(0, 0, 0, 0);
  const recent = S.audit.filter((e) => e.ts >= today).slice(-60).reverse(), bad = recent.filter((e) => e.tool === 'guard.flag' || /denied|blocked/.test(e.decision || '')).length;
  $('#recentNote').textContent = recent.length ? `${recent.length} today${bad ? ` · ${bad} blocked` : ''}` : 'quiet today';
  $('#recent').replaceChildren(...recent.map((e) => { const [txt, c] = auditLine(e); const tg = e.tool === 'guard.flag' ? 'Flagged' : e.decision === 'blocked' ? 'Blocked' : e.decision === 'denied' ? 'Denied' : e.decision === 'approved' ? (e.approver === 'reviewer' || e.channel === 'reviewer' ? 'Auto' : 'Approved') : '';
    return el('li', c === 'c' ? 'flag' : '', `<time>${fmtT(e.ts, true)}</time><i class="${c}"></i><span>${esc(txt)}</span>${tg ? `<em class="tg ${c === 'g' ? 'g' : ''}">${tg}</em>` : ''}`); }));
  if (!recent.length) $('#recent').replaceChildren(el('li', '', `<span class="empty">Nothing yet</span>`));
  $('#modNote').textContent = S.mods?.length ? `${S.mods.length}` : '';
  $('#blockNote').textContent = S.trackers ? `${S.trackers.reduce((n, a) => n + a.trackers.filter((t) => t.blocked).length, 0)} blocked` : '';
}
function taskRow(t) {
  const r = el('div', 'rowi ' + (S.pending ? 'approval' : 'working'));
  const pct = Math.min(100, Math.round(100 * (t.steps || 0) / (t.max_steps || 40))), last = S.audit.filter((e) => e.task === t.task_id && !/^task\./.test(e.tool)).pop();
  r.innerHTML = `<span class="orb ${S.pending ? 'approval' : 'working'}"></span><div>${esc(short(t.goal, 34) || 'Task')}<span class="s">${esc(S.pending ? 'Waiting for you' : t.paused ? 'Paused' : last ? auditLine(last)[0] : 'Starting')} · ${esc((t.apps || []).map(appName).join(', '))}</span></div>
    <div class="r"><div class="mini"><i style="transform:scaleX(${pct / 100})"></i></div><button class="ib" data-a="${t.paused ? 'resume' : 'pause'}"><svg width="14" height="14"><use href="#i-${t.paused ? 'play' : 'pause'}"/></svg></button><button class="ib red" data-a="stop"><svg width="14" height="14"><use href="#i-stop"/></svg></button></div>`;
  r.onclick = async (e) => { const a = e.target.closest('[data-a]')?.dataset.a; if (!a) return; hap(); if (a === 'stop') send({ type: 'stop' }); await rpc('task.' + a, { task_id: t.task_id }); refresh(); };
  return r;
}
$('#prov').onclick = (e) => { const p = e.target.dataset.p; if (!p) return; hap('tick'); S.provider = localStorage.provider = p; renderCenter(); };
$('#kill').onclick = async () => { hap('heavy'); send({ type: 'stop' }); await rpc('task.stop', {}); S.turn = false; S.pending = null; refresh(); };

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
  const r = await rpc('firewall.list', {}); const rules = r?.rules || [];
  const pkgs = [...new Set(['local.agentos.pigeon', 'local.agentos.beacon', 'com.termux', ...rules.map((x) => x.pkg)])].filter((p) => S.names[p] || rules.some((x) => x.pkg === p));
  $('#fw').replaceChildren(...pkgs.map((pkg) => {
    const blocked = rules.some((x) => x.pkg === pkg && /REJECT|DROP/i.test(x.action || 'REJECT'));
    const b = el('button', 'ft ' + (blocked ? 'off' : 'on'), `<span class="c">${ICOHTML(pkg)}</span><div class="l">${esc(appName(pkg))}<span>${blocked ? 'Blocked' : 'Allowed'}</span></div>`);
    b.onclick = async () => { hap(); b.className = 'ft wait'; $('.l span', b).textContent = 'Waiting…'; const res = await rpc('firewall.set', { pkg, mode: blocked ? 'allow' : 'block' }); if (res.decision !== 'approved') hap('heavy'); renderFirewall(); refresh(); };
    return b;
  }));
  if (!pkgs.length) $('#fw').replaceChildren(el('div', 'empty', 'No apps'));
}

// ---------- blockers: trackers seen and blocked per app ({apps:[{pkg,label,trackers:[{domain,company,blocked,hits}]}]}) ----------
async function openBlockers() { open('blockers'); renderBlockers(); const r = await rpc('trackers.list', {}); S.trackers = r?.apps || []; renderBlockers(); renderCenter(); }
function renderBlockers() {
  const box = $('#blist'), apps = (S.trackers || []).filter((a) => a.trackers?.length);
  const nb = apps.reduce((n, a) => n + a.trackers.filter((t) => t.blocked).length, 0), ns = apps.reduce((n, a) => n + a.trackers.length, 0);
  $('#blockersNote').textContent = ns ? `${nb} of ${ns} blocked` : '';
  if (!apps.length) return box.replaceChildren(el('div', 'bempty', `<span class="ring"><svg width="30" height="30"><use href="#i-shield"/></svg></span>No trackers seen<small>Ask “any trackers?” in any app</small>`));
  box.replaceChildren(...apps.map((a) => {
    const open_ = a.trackers.filter((t) => !t.blocked), m = el('div', 'mod bk');
    m.innerHTML = `<div class="hd"><span class="appic">${ICOHTML(a.pkg)}</span><div>${esc(a.label || appName(a.pkg))}<span class="s">${a.trackers.length - open_.length} blocked${open_.length ? ` · ${open_.length} open` : ''}</span></div>${open_.length ? '<button class="pillbtn blue">Block</button>' : '<span class="tag g">Protected</span>'}</div>
      <ul>${a.trackers.map((t) => `<li class="${t.blocked ? 'off' : ''}"><i></i><b>${esc(t.domain)}</b>${t.company ? `<em>${esc(t.company)}</em>` : ''}<span>${t.hits || 0}</span></li>`).join('')}</ul>`;
    const b = $('.pillbtn', m); if (b) b.onclick = async () => { hap(); b.textContent = 'Waiting…'; const r = await rpc('firewall.set', { pkg: a.pkg, mode: 'block', domains: open_.map((t) => t.domain) }); if (r?.decision !== 'approved') hap('heavy'); openBlockers(); };
    return m;
  }));
}
// ---------- mods: buttons agents added to apps ({mods:[{id, package, app, label, shown, created, by}]}) ----------
async function openMods() { open('mods'); renderMods(); await loadMods(); }
async function loadMods() { const r = await rpc('app.mod.list', {}); if (r?.mods) S.mods = r.mods; renderMods(); renderCenter(); }
function renderMods() {
  const box = $('#mlist'), mods = S.mods || [];
  $('#modsNote').textContent = mods.length ? `${mods.length} added` : '';
  if (!mods.length) return box.replaceChildren(el('div', 'bempty', `<span class="ring blue"><svg width="30" height="30"><use href="#i-mod"/></svg></span>Ask to add a button to any app<small>“Add a tip button to Calculator”</small>`));
  box.replaceChildren(...mods.map((m) => { const r = el('div', 'rowi mod', `<span class="appic">${ICOHTML(m.package)}</span><div>${esc(m.label)}<span class="s">${esc(m.app || appName(m.package))}${m.shown ? '' : ' · hidden'}</span></div><div class="r"><button class="tag">Remove</button></div>`);
    $('button', r).onclick = async () => { hap(); r.style.opacity = .4; await rpc('app.mod.remove', { id: m.id, by: 'settings' }); loadMods(); }; return r; }));
}
on('mod.changed', () => loadMods());
on('tracker.blocked', (m) => { S.audit = [...S.audit.slice(-199), { ...m, tool: 'tracker.blocked', ts: m.ts || Date.now() }]; renderCenter(); if ($('#blockers').classList.contains('on')) openBlockers(); });

// ---------- ask sheet ----------
const body = $('#shbody'); let aiMsg = null, steps = {};
const scrollDown = () => (body.scrollTop = body.scrollHeight);
function setSheet(state, cls) { $('#shstate').textContent = state; $('#shorb').className = 'orb ' + (cls || ''); $('#shstop').hidden = !S.turn; }
function sendUser(text) {
  if (!text) return;
  body.append(el('div', 'msg me', esc(text))); aiMsg = null; S.home = null;
  if (!send({ type: 'ask', text, provider: S.provider })) body.append(el('div', 'msg err', 'Agent offline'));
  else { S.turn = true; setSheet('Thinking', 'working'); renderStatus(); }
  scrollDown();
}
$('#askForm').onsubmit = (e) => { e.preventDefault(); const t = $('#askIn').value.trim(); $('#askIn').value = ''; hap('tick'); sendUser(t); };
$('#shstop').onclick = () => { hap(); send({ type: 'stop' }); };
on('text.delta', (m) => { if (!aiMsg) body.append(aiMsg = el('div', 'msg ai')); aiMsg.textContent += m.text; if (S.home) S.home.text = aiMsg.textContent; scrollDown(); });
on('tool.request', (m) => {
  const a = m.args || {}, what = m.name === 'open_app' ? appName(a.pkg) : m.name === 'task.start' ? (a.apps || []).map(appName).join(', ') : a.text ? `“${short(a.text, 30)}”` : a.id != null ? `#${a.id}` : a.key || a.pkg || '';
  const s = el('div', 'step', `<span class="orb working"></span><span>${esc(m.name.replace(/[._]/g, ' '))} ${esc(what)}</span>`);
  body.append(s); steps[m.name] = s; aiMsg = null; S.turn = true; S.step = `${m.name.replace(/[._]/g, ' ')} ${what}`.trim().replace(/^\w/, (c) => c.toUpperCase()); setSheet('Working', 'working'); scrollDown(); renderNow();
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
on('error', (m) => { if (m.code === 'unknown_type') { if (m.message === 'task.start' && S.goal) { open('sheet'); sendUser(S.goal); } return; } body.append(el('div', 'msg err', esc(m.message))); scrollDown(); }); // older agentd: run the suggestion as a plain ask
on('turn.done', (m) => { S.turn = false; S.pending = null; S.step = null; steps = {}; setSheet(m.status === 'completed' || m.status === 'done' ? 'Done' : m.status || 'Done', 'done'); if (S.home) { S.home.done = true; setTimeout(() => { if (S.home?.done) { S.home = null; renderNow(); } }, 20000); } live(); });
on('ws.open', () => { refresh(); send({ type: 'suggestions.watch' }); send({ type: 'task.list' }); }); on('ws.close', () => { S.turn = false; S.active = null; S.sugg = []; renderSugg(); for (const id in pend) app.rpcDone(id, { ok: false, error: 'offline' }); live(); });

// ---------- voice (WebRTC per agentd/voice.html; hold the orb to talk) ----------
let pc, mic, micTrack;
const V = { state: (s) => ($('#vstate').textContent = s) };
async function voiceStart() {
  open('voice'); $('#vu').textContent = $('#va').textContent = ''; $('#vsteps').replaceChildren(); V.state('Connecting');
  if (!wsOk) return V.state('Agent offline');
  try {
    mic = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
    micTrack = mic.getAudioTracks()[0]; micTrack.enabled = false;
    pc = new RTCPeerConnection(); pc.addTrack(micTrack, mic);
    const dc = pc.createDataChannel('oai-events'); // must exist before createOffer
    dc.onmessage = (e) => { const m = JSON.parse(e.data); if (m.type === 'delegation.created') send({ type: 'voice.delegate', item_id: m.item.id, text: m.item.content.map((c) => c.text).join('') }); };
    pc.ontrack = (e) => { $('#spk').srcObject = e.streams[0]; };
    await pc.setLocalDescription(await pc.createOffer());
    await new Promise((ok) => { setTimeout(ok, 500); pc.onicegatheringstatechange = () => pc.iceGatheringState === 'complete' && ok(); });
    send({ type: 'voice.start', sdp: pc.localDescription.sdp });
  } catch (e) { V.state('Mic unavailable'); }
}
function voiceStop() { send({ type: 'voice.stop' }); pc?.close(); pc = null; mic?.getTracks().forEach((t) => t.stop()); mic = micTrack = null; $('#wave').classList.remove('on'); }
on('voice.answer', async (m) => { if (pc) await pc.setRemoteDescription({ type: 'answer', sdp: m.sdp }); });
on('voice.started', () => V.state('Hold to talk'));
on('voice.transcript', (m) => { const p = $(m.role === 'user' ? '#vu' : '#va'); p.innerHTML = esc(m.text) + (m.final ? '' : '<span class="cur"></span>'); if (m.role !== 'user') V.state('Speaking'); });
on('voice.delegation', (m) => { V.state('Working'); $('#vsteps').replaceChildren(); });
on('voice.result', (m) => { V.state('Speaking'); $('#va').textContent = m.text; });
on('voice.closed', () => V.state('Ended')); on('voice.error', (m) => V.state(short(m.message, 40)));
on('tool.request', (m) => { if ($('#voice').classList.contains('on')) { $('#vsteps').append(el('li', '', `<span class="orb working sm"></span>${esc(m.name.replace(/[._]/g, ' '))}`)); } });
on('tool.result', (m) => { const li = $('#vsteps li:last-child'); if (li) $('.orb', li).className = 'orb sm ' + (/denied|blocked/.test(m.decision || '') ? 'alert' : 'done'); });
const sorb = $('#sorbBtn');
sorb.onpointerdown = (e) => { e.preventDefault(); if (!micTrack) return; sorb.setPointerCapture(e.pointerId); micTrack.enabled = true; sorb.classList.add('hot'); $('#wave').classList.add('on'); V.state('Listening'); hap('tick'); };
sorb.onpointerup = sorb.onpointercancel = () => { if (!micTrack) return; micTrack.enabled = false; sorb.classList.remove('hot'); $('#wave').classList.remove('on'); V.state('Thinking'); };
$('#vend').onclick = () => { hap(); close('voice'); };
$('#vkbd').onclick = () => { hap(); close('voice'); open('sheet'); setTimeout(() => $('#askIn').focus(), 350); };
(() => { const w = $('#wave'); for (let i = 0; i < 24; i++) { const b = el('i'); b.style.setProperty('--h', (0.3 + Math.abs(Math.sin(i * .7)) * .7).toFixed(2)); b.style.animationDelay = (i * .06) + 's'; b.style.animationDuration = (.9 + Math.random() * .6) + 's'; w.append(b); } })();

// ---------- memory graph ----------
let graphAt = 0;
async function loadMemory() {
  if (Date.now() - graphAt < 15000) return;
  try {
    const g = window.Native ? await nget('/memory/graph.json') : await (await fetch(GRAPH, { headers: { Authorization: 'Bearer ' + N.token('agentd') } })).json();
    if (!g.nodes) throw new Error(g.error || 'no graph');
    graphAt = Date.now(); $('#memNote').textContent = `${g.nodes.length - 1} people`;
    drawGraph(g); renderSources($('#sources'), g.consents); S.consents = g.consents;
  } catch (e) { $('#memNote').textContent = 'offline'; }
}
function drawGraph(g) {
  const svg = $('#gsvg'), W = svg.clientWidth || 360, H = 320, cx = W / 2, cy = H / 2;
  const nodes = g.nodes.map((n) => ({ ...n.data, x: cx + (Math.random() - .5) * 120, y: cy + (Math.random() - .5) * 120, r: n.data.type === 'me' ? 22 : 11 + Math.min(14, n.data.weight || 0) * .6 }));
  const byId = Object.fromEntries(nodes.map((n) => [n.id, n])), me = byId[g.me]; if (me) { me.x = cx; me.y = cy; }
  const edges = g.edges.map((e) => e.data).filter((e) => byId[e.source] && byId[e.target]);
  for (let k = 0; k < 260; k++) { // tiny force layout: repel, spring along edges, drift to center
    for (const a of nodes) for (const b of nodes) if (a !== b) { const dx = a.x - b.x, dy = a.y - b.y, d2 = dx * dx + dy * dy + 1, f = 5200 / d2; a.x += dx * f / Math.sqrt(d2) * .5; a.y += dy * f / Math.sqrt(d2) * .5; }
    for (const e of edges) { const a = byId[e.source], b = byId[e.target], dx = b.x - a.x, dy = b.y - a.y, d = Math.hypot(dx, dy) || 1, f = (d - (90 - Math.min(30, e.weight * 4))) * .02; a.x += dx / d * f; a.y += dy / d * f; b.x -= dx / d * f; b.y -= dy / d * f; }
    for (const n of nodes) { if (n === me) { n.x = cx; n.y = cy; continue; } n.x += (cx - n.x) * .01; n.y += (cy - n.y) * .01; n.x = Math.max(n.r + 4, Math.min(W - n.r - 4, n.x)); n.y = Math.max(n.r + 4, Math.min(H - n.r - 18, n.y)); }
  }
  const ns = 'http://www.w3.org/2000/svg', mk = (t, a) => { const e = document.createElementNS(ns, t); for (const k in a) e.setAttribute(k, a[k]); return e; };
  svg.replaceChildren(...edges.map((e) => mk('line', { x1: byId[e.source].x, y1: byId[e.source].y, x2: byId[e.target].x, y2: byId[e.target].y, 'stroke-width': 1 + Math.min(4, e.weight / 2) })),
    ...nodes.map((n) => { const gg = mk('g', { class: ['me', 'person', 'group'].includes(n.type) ? n.type : 'other' });
      gg.append(mk('circle', { cx: n.x, cy: n.y, r: n.r }), Object.assign(mk('text', { x: n.x, y: n.y + n.r + 13 }), { textContent: short(n.label.replace(/^Unknown /, ''), 14) }));
      gg.onclick = () => { hap('tick'); $$('g.sel', svg).forEach((x) => x.classList.remove('sel')); gg.classList.add('sel');
        const es = edges.filter((e) => e.source === n.id || e.target === n.id), last = es.map((e) => e.last_seen).sort().pop();
        $('#gdet').innerHTML = n.type === 'me' ? `<b>You</b> · ${es.length} people` : `<b>${esc(n.label)}</b> · ${es.reduce((s, e) => s + e.weight, 0)} ${esc(es[0]?.type || 'links')} via ${esc([...new Set(es.map((e) => e.via))].join(', '))}${last ? ' · ' + new Date(last).toLocaleDateString([], { month: 'short', day: 'numeric' }) : ''}`; };
      return gg; }));
}
function renderSources(box, consents = S.consents) {
  if (!consents) return box.replaceChildren(el('div', 'empty', 'Offline'));
  if (!consents.length) return box.replaceChildren(el('div', 'empty', 'None'));
  box.replaceChildren(...consents.map((c) => el('div', 'rowi', `<span class="appic" style="background:var(--purple)">${esc(c.source[0].toUpperCase())}</span><div>${esc(c.source)}<span class="s">${c.events} items · ${esc(c.scope)}</span></div><div class="r"><span class="tag ${c.active ? 'g' : 'c'}">${c.active ? 'Allowed' : 'Off'}</span></div>`)));
}

// ---------- boot ----------
loadApps(); renderCalendar(); live(); go(0);
// Browser-only styling aid (`index.html#demo` on the Mac, never on the phone): sample feed + a running task.
if (location.hash.startsWith('#demo')) { // styling aid: only reachable through install.sh dev <url>#demo
  emit({ type: 'suggestions', items: [{ id: 1, title: 'Reply to Sam: running late?', detail: 'Messages · 12 min ago', app: 'local.agentos.pigeon', goal: 'x' }, { id: 2, title: 'Leave for 3 PM meeting in 12 min', detail: '18 min drive · Maps', icon: 'car', goal: 'x' }, { id: 3, title: '2 apps sent data to trackers today', detail: '1Weather, Chrome', icon: 'eye', goal: 'x' }] });
  if (location.hash === '#demo') S.active = { task_id: 't1', goal: 'Reply to Mom that I am 10 minutes late', apps: ['local.agentos.pigeon'], expires: Date.now() + 5e5, steps: 3, max_steps: 40 }; S.step = 'Tap "Send"'; S.tasks.set('k1', { id: 'k1', goal: 'Reply to Mom that I am 10 minutes late', state: 'running', step: 'Tapping Send in Pigeon', created: 1 }); live();
  if (location.hash === '#demo-blockers') { open('blockers'); S.trackers = [{ pkg: 'com.handmark.expressweather', label: '1Weather', trackers: [{ domain: 'app-measurement.com', company: 'Google', blocked: true, hits: 14 }, { domain: 'ads.mopub.com', company: 'MoPub', blocked: false, hits: 6 }] }, { pkg: 'com.android.chrome', label: 'Chrome', trackers: [{ domain: 'doubleclick.net', company: 'Google', blocked: true, hits: 3 }] }]; renderBlockers(); }
  if (location.hash === '#demo-center') go(1);
}
