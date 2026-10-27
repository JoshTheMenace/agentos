/* AgentOS home (A2 Calm Glass). One file, no framework. The native shell exposes `Native` (see launcher/Home.java);
   in a desktop browser the shims below keep the page alive for styling work. */
'use strict';
const $ = (s, r = document) => r.querySelector(s), $$ = (s, r = document) => [...r.querySelectorAll(s)];
const el = (tag, cls, html) => { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; };
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const N = window.Native || { // browser shims
  info: () => JSON.stringify({ pkg: 'browser', dev: true, top: 28, bottom: 0, ime: 0 }), token: () => null, launch: () => false, haptic: () => {},
  settings: () => {}, shade: () => {}, calendarNext: () => null, rpc: (id) => setTimeout(() => app.rpcDone(id, { ok: false, error: 'browser' })),
  apps: () => JSON.stringify([['local.agentos.pigeon', 'Pigeon'], ['local.agentos.beacon', 'Beacon'], ['com.android.chrome', 'Chrome'], ['com.android.camera2', 'Camera'], ['com.google.android.calendar', 'Calendar']].map(([pkg, label]) => ({ pkg, label }))),
};
const app = window.app = {};
const info = JSON.parse(N.info());
const hap = (k) => N.haptic(k || 'click');
const ICON = (pkg) => `https://agentos.home/icon/${pkg}`;
const AGENTD = 'ws://127.0.0.1:8787/', GRAPH = 'http://127.0.0.1:8787/memory/graph.json';
const LEASE_S = 600; // task.start default ttl on the phone
const FORBIDDEN = ['Settings', 'System UI', 'Magisk', 'Installer', 'AgentOS'];

// ---------- insets (edge to edge; the shell reports status/nav/IME heights in CSS px) ----------
app.insets = (t, b, i) => { const r = document.documentElement.style; r.setProperty('--top', t + 'px'); r.setProperty('--bottom', b + 'px'); r.setProperty('--ime', i + 'px'); };
app.insets(info.top, info.bottom, info.ime);

// ---------- tool server RPC (async through the shell; approvals block up to 60 s natively) ----------
let rid = 0; const pend = {};
const rpc = (method, params = {}) => new Promise((ok) => { const id = String(++rid); pend[id] = ok; N.rpc(id, method, JSON.stringify(params)); });
app.rpcDone = (id, r) => { pend[id]?.(r); delete pend[id]; };

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

// ---------- state ----------
const S = { tasks: [], active: null, turn: false, pending: null, apps: [], names: {}, provider: localStorage.provider || 'codex', audit: [] };
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

// ---------- audit -> tasks / recent ----------
function parseAudit(entries) {
  const m = new Map();
  for (const e of entries) {
    if (e.tool === 'task.start') m.set(e.task, { id: e.task, goal: e.goal || '', apps: e.apps || [], start: e.ts, done: false, steps: 0, last: null, paused: false, denied: 0, approved: 0 });
    const t = m.get(e.task); if (!t) continue;
    if (e.tool === 'task.stop') { t.done = true; t.end = e.ts; t.steps = e.steps ?? t.steps; }
    else if (e.tool === 'task.pause') t.paused = true;
    else if (e.tool === 'task.resume') t.paused = false;
    else if (e.tool !== 'task.start') { t.last = e; t.steps++; if (e.decision === 'approved') t.approved++; if (e.decision === 'denied') t.denied++; }
  }
  return [...m.values()];
}
const auditLine = (e) => {
  const app_ = appName(e.pkg), tgt = e.target ? `"${short(e.target, 24)}"` : '';
  switch (e.tool) {
    case 'task.start': return ['Started · ' + short(e.goal, 40), 'a'];
    case 'task.stop': return [`Stopped · ${e.steps ?? 0} steps`, ''];
    case 'task.pause': return ['Paused', ''];
    case 'task.resume': return ['Resumed', ''];
    case 'tap': return [`Tap ${tgt} · ${app_}`, e.decision === 'approved' ? 'g' : e.decision === 'denied' || e.decision === 'blocked' ? 'c' : e.tier?.startsWith('T3') ? 'b' : 'a'];
    case 'type': return [`Type in ${app_}`, e.decision === 'blocked' ? 'c' : 'a'];
    case 'open_app': return [`Open ${app_}`, 'a'];
    case 'firewall.set': return [`Firewall · ${app_} ${e.mode || ''}`, e.decision === 'approved' ? 'g' : 'c'];
    case 'firewall.clear': return ['Firewall cleared', e.decision === 'approved' ? 'g' : 'c'];
    default: return [`${e.tool} ${tgt} ${app_ || ''}`.trim(), e.decision === 'approved' ? 'g' : e.decision === 'denied' || e.decision === 'blocked' ? 'c' : 'a'];
  }
};

async function refresh() {
  const r = await rpc('audit.tail', { n: 80 });
  if (r?.entries) {
    S.audit = r.entries;
    S.tasks = parseAudit(r.entries);
    S.active = S.tasks.filter((t) => !t.done && Date.now() - t.start < LEASE_S * 1000).pop() || null;
  }
  renderStatus(); renderCenter(); if ($('#perms').classList.contains('on')) renderGrants();
  renderCalendar();
}
setInterval(() => document.visibilityState === 'visible' && refresh(), 20000);

// ---------- home ----------
function renderStatus() {
  const orb = $('#sorb'), corb = $('#corb');
  const cls = S.pending ? 'approval' : S.turn || S.active ? 'working' : '';
  orb.className = corb.className = 'orb ' + cls;
  $('#status').textContent = S.pending ? 'Waiting for you' : S.turn || S.active ? '1 task running' : wsOk ? 'Ready' : 'Agent offline';
  const t = S.active, left = t ? Math.max(0, Math.round((t.start + LEASE_S * 1000 - Date.now()) / 60000)) : 0;
  $('#trustTxt').innerHTML = t ? `Agent can tap in ${esc(t.apps.map(appName).join(', ') || 'an app')} <b>· ${left} min</b>` : 'No active grants';
}
function renderCalendar() {
  const j = N.calendarNext(); const c = j && JSON.parse(j);
  const cal = S.apps.find((a) => /calendar/i.test(a.pkg));
  $('#calBtn').hidden = !cal; $('#calBtn').onclick = () => { hap(); N.launch(cal.pkg); };
  if (!c) { $('#calWhen').textContent = 'Next'; $('#calTitle').textContent = 'Nothing scheduled'; $('#calSub').textContent = 'This week'; return; }
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
  $('#dock').replaceChildren(...dock.map((a) => { const b = el('button', 'ico'); b.append(Object.assign(new Image(), { src: ICON(a.pkg), alt: a.label })); b.onclick = () => { hap(); N.launch(a.pkg); }; return b; }));
  renderGrid('');
}
function renderGrid(q) {
  q = q.trim().toLowerCase();
  $('#grid').replaceChildren(...S.apps.filter((a) => !q || a.label.toLowerCase().includes(q)).map((a) => {
    const b = el('button', 'app'); const i = el('i', 'ico'); i.append(Object.assign(new Image(), { src: ICON(a.pkg), loading: 'lazy' }));
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
app.resume = () => { W = innerWidth; refresh(); if (page === 2) loadMemory(); };
$('#scrim').onclick = () => close('sheet');
$('#permsBack').onclick = () => close('perms');
$('#drawer .grab').onclick = () => close('drawer');
$('#trust').onclick = () => { hap(); openPerms(); };
$('#toPerms').onclick = () => { hap(); openPerms(); };
$('#toSettings').onclick = () => { hap(); N.settings(''); };
$('#cmd').onclick = (e) => { if (e.target.closest('.mic')) return; hap(); open('sheet'); setTimeout(() => $('#askIn').focus(), 350); };
$('#micBtn').onclick = () => { hap('heavy'); voiceStart(); };

// ---------- command center ----------
function renderCenter() {
  const box = $('#tasks'), rows = [];
  const t = S.active;
  if (t) rows.push(taskRow(t));
  if (S.turn && !t) rows.push(el('div', 'rowi', `<span class="orb working"></span><div>Thinking<span class="s">${esc(S.provider)}</span></div>`));
  if (!rows.length) rows.push(el('div', 'empty', 'Nothing running'));
  box.replaceChildren(...rows);
  $('#taskCount').textContent = rows.length && !box.querySelector('.empty') ? `${rows.length} running` : '';
  $$('#prov button').forEach((b) => b.classList.toggle('on', b.dataset.p === S.provider));
  const today = new Date().setHours(0, 0, 0, 0);
  const recent = S.audit.filter((e) => e.ts >= today).slice(-12).reverse();
  $('#recentNote').textContent = recent.length ? 'today' : 'quiet today';
  $('#recent').replaceChildren(...recent.map((e) => { const [txt, c] = auditLine(e); return el('li', '', `<time>${fmtT(e.ts, true)}</time><i class="${c}"></i><span>${esc(txt)}</span>`); }));
}
function taskRow(t) {
  const r = el('div', 'rowi ' + (S.pending ? 'approval' : 'working'));
  const pct = Math.min(100, Math.round(100 * t.steps / 40));
  r.innerHTML = `<span class="orb ${S.pending ? 'approval' : 'working'}"></span><div>${esc(short(t.goal, 34) || 'Task')}<span class="s">${esc(S.pending ? 'Waiting for you' : t.paused ? 'Paused' : t.last ? auditLine(t.last)[0] : 'Starting')} · ${esc(t.apps.map(appName).join(', '))}</span></div>
    <div class="r"><div class="mini"><i style="transform:scaleX(${pct / 100})"></i></div><button class="ib" data-a="${t.paused ? 'resume' : 'pause'}"><svg width="14" height="14"><use href="#i-${t.paused ? 'play' : 'pause'}"/></svg></button><button class="ib red" data-a="stop"><svg width="14" height="14"><use href="#i-stop"/></svg></button></div>`;
  r.onclick = async (e) => { const a = e.target.closest('[data-a]')?.dataset.a; if (!a) return; hap(); if (a === 'stop') send({ type: 'stop' }); await rpc('task.' + a, { task_id: t.id }); refresh(); };
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
  const left = Math.max(0, Math.round((t.start + LEASE_S * 1000 - Date.now()) / 60000));
  box.replaceChildren(...t.apps.map((pkg) => { const r = el('div', 'rowi', `<span class="appic"><img src="${ICON(pkg)}" alt=""></span><div>${esc(appName(pkg))}<span class="s">Tap · type</span></div><div class="r"><span class="tag a">${left} min</span><button class="tag">Revoke</button></div>`);
    $('button', r).onclick = async () => { hap(); send({ type: 'stop' }); await rpc('task.stop', { task_id: t.id }); refresh(); }; return r; }));
}
async function renderFirewall() {
  const r = await rpc('firewall.list', {}); const rules = r?.rules || [];
  const pkgs = [...new Set(['local.agentos.pigeon', 'local.agentos.beacon', 'com.termux', ...rules.map((x) => x.pkg)])].filter((p) => S.names[p] || rules.some((x) => x.pkg === p));
  $('#fw').replaceChildren(...pkgs.map((pkg) => {
    const blocked = rules.some((x) => x.pkg === pkg && /REJECT|DROP/i.test(x.action || 'REJECT'));
    const b = el('button', 'ft ' + (blocked ? 'off' : 'on'), `<span class="c"><img src="${ICON(pkg)}" alt=""></span><div class="l">${esc(appName(pkg))}<span>${blocked ? 'Blocked' : 'Allowed'}</span></div>`);
    b.onclick = async () => { hap(); b.className = 'ft wait'; $('.l span', b).textContent = 'Fingerprint…'; const res = await rpc('firewall.set', { pkg, mode: blocked ? 'allow' : 'block' }); if (res.decision !== 'approved') hap('heavy'); renderFirewall(); refresh(); };
    return b;
  }));
  if (!pkgs.length) $('#fw').replaceChildren(el('div', 'empty', 'No apps'));
}

// ---------- ask sheet ----------
const body = $('#shbody'); let aiMsg = null, steps = {}, apprCard = null;
const scrollDown = () => (body.scrollTop = body.scrollHeight);
function setSheet(state, cls) { $('#shstate').textContent = state; $('#shorb').className = 'orb ' + (cls || ''); $('#shstop').hidden = !S.turn; }
function sendUser(text) {
  if (!text) return;
  body.append(el('div', 'msg me', esc(text))); aiMsg = null;
  if (!send({ type: 'ask', text, provider: S.provider })) body.append(el('div', 'msg err', 'Agent offline'));
  else { S.turn = true; setSheet('Thinking', 'working'); renderStatus(); }
  scrollDown();
}
$('#askForm').onsubmit = (e) => { e.preventDefault(); const t = $('#askIn').value.trim(); $('#askIn').value = ''; hap('tick'); sendUser(t); };
$('#shstop').onclick = () => { hap(); send({ type: 'stop' }); };
on('text.delta', (m) => { if (!aiMsg) body.append(aiMsg = el('div', 'msg ai')); aiMsg.textContent += m.text; scrollDown(); });
on('tool.request', (m) => {
  const a = m.args || {}, what = m.name === 'open_app' ? appName(a.pkg) : m.name === 'task.start' ? (a.apps || []).map(appName).join(', ') : a.text ? `“${short(a.text, 30)}”` : a.id != null ? `#${a.id}` : a.key || a.pkg || '';
  const s = el('div', 'step', `<span class="orb working"></span><span>${esc(m.name.replace(/[._]/g, ' '))} ${esc(what)}</span>`);
  body.append(s); steps[m.name] = s; aiMsg = null; S.turn = true; setSheet('Working', 'working'); scrollDown();
});
on('tool.result', (m) => {
  const s = steps[m.name]; if (!s) return;
  const d = m.decision || (m.ok ? 'auto' : 'failed'), bad = /denied|blocked|failed/.test(d);
  $('.orb', s).className = 'orb ' + (bad ? 'alert' : 'done');
  if (d !== 'auto') s.append(el('span', 'tag ' + (bad ? 'c' : 'g'), d === 'approved' ? 'Approved' : d));
  if (S.pending && apprCard) { apprCard.remove(); apprCard = null; }
  S.pending = null; setSheet('Working', 'working'); renderStatus();
});
on('approval.needed', (m) => {
  S.pending = m; renderStatus();
  const r2 = m.rule_of_two || {}, chips = [['untrusted', 'Untrusted input'], ['private', 'Private data'], ['outward', 'Outward action']].map(([k, l]) => `<span class="chip ${r2[k] ? 'lit' : ''}"><i></i>${l}</span>`).join('');
  const title = /send|submit|post/i.test(m.summary || '') ? 'Send it?' : 'Allow?';
  apprCard?.remove();
  apprCard = el('div', 'glass appr', `<div class="t">${title}</div><div class="q">${esc(short(m.summary, 140))}</div><div class="r2">${chips}</div>
    <div class="acts"><span class="finger"><svg width="22" height="22"><use href="#i-finger"/></svg>Fingerprint · or say yes</span><button class="btn deny">Deny</button><button class="btn ok">Approve</button></div>`);
  $('.ok', apprCard).onclick = () => { hap('heavy'); apprCard.classList.add('sent'); sendUser('Yes, send it.'); };
  $('.deny', apprCard).onclick = () => { hap(); apprCard.classList.add('sent'); sendUser('No, don’t.'); };
  body.append(apprCard); setSheet('Waiting for you', 'approval'); scrollDown();
  if (!$('#sheet').classList.contains('on') && !$('#voice').classList.contains('on')) open('sheet');
});
on('guard.alert', (m) => { body.append(el('div', 'step alert', `<svg width="14" height="14"><use href="#i-shield"/></svg><span>Ignored instructions in ${esc(appName(m.source_pkg))}</span>`)); scrollDown(); });
on('error', (m) => { if (m.code !== 'busy') body.append(el('div', 'msg err', esc(m.message))); scrollDown(); });
on('turn.done', (m) => { S.turn = false; S.pending = null; apprCard?.remove(); apprCard = null; steps = {}; setSheet(m.status === 'completed' || m.status === 'done' ? 'Done' : m.status || 'Done', 'done'); renderStatus(); refresh(); });
on('ws.open', renderStatus); on('ws.close', () => { S.turn = false; renderStatus(); });

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
    const g = window.Native ? await rpc('agentd:/memory/graph.json') : await (await fetch(GRAPH, { headers: { Authorization: 'Bearer ' + N.token('agentd') } })).json();
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
loadApps(); refresh(); go(0);
