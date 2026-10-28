/* AgentOS assistant bar (launcher Bar.java hosts this page in a small overlay window over any app).
   One row (orb · ask · x) and a two-line transcript. Asks are agentd `ask`s with the app underneath; the voice is the same
   GPT Live session, echo gate (voice-audio.js) and tap-to-interrupt as voice mode. Approvals never show here: the native
   card is the gate. Live mic/speaker levels drive the orb, the waveform and the edge ring through CSS variables. */
'use strict';
const $ = (s) => document.querySelector(s), esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const N = window.Native || { info: () => '{"pkg":"browser"}', token: () => null, ready: () => {}, size: () => {}, focus: () => {}, close: () => {}, haptic: () => {}, wake: () => '{}', voiceMode: () => {} };
const bar = window.bar = {}, hap = (k) => N.haptic(k || 'click');
const card = $('#card'), tx = $('#tx'), hd = $('#hd'), st = $('#st'), orb = $('#orb'), U = $('#u'), A = $('#a'), inp = $('#in'), mic = $('#mic'), row = $('#form'), field = $('.field');

// ---------- agentd WebSocket (one connection = one voice session on agentd's side) ----------
const bus = {}; const on = (t, f) => (bus[t] ??= []).push(f); const emit = (m) => (bus[m.type] || []).forEach((f) => f(m));
let ws, wsOk = false;
function connect() {
  const tok = N.token('agentd'); if (!tok) return;
  ws = new WebSocket('ws://127.0.0.1:8787/', 'agentos.bearer.' + tok);
  ws.onopen = () => { wsOk = true; emit({ type: 'ws.open' }); };
  ws.onclose = () => { wsOk = false; emit({ type: 'ws.close' }); setTimeout(connect, 3000); };
  ws.onmessage = (e) => { try { emit(JSON.parse(e.data)); } catch {} };
}
const send = (m) => wsOk ? (ws.send(JSON.stringify(m)), true) : false;
connect();

// ---------- transcript text: word-by-word reveal (streamed chunks may split a word; the open word just grows) ----------
const openWord = new WeakMap();
function reveal(el, chunk) {
  for (const t of String(chunk).split(/(\s+)/)) {
    if (!t) continue;
    if (/^\s+$/.test(t)) { el.append(t); openWord.delete(el); continue; }
    const w = openWord.get(el);
    if (w) { w.textContent += t; continue; }
    const s = document.createElement('span'); s.className = 'w'; s.textContent = t; el.append(s); openWord.set(el, s);
  }
}
const setText = (el, text) => { el.replaceChildren(); openWord.delete(el); if (text) reveal(el, text); };
const plain = (el) => el.textContent;

// ---------- state line: "Listening" · "Thinking · 4 s" · "Waiting for your approval" · "Done · 6.8 s" ----------
let cls = '', text = '', ms = 0, startAt = 0, turn = false, finishT;
const dur = (x) => (x > 0 && x < 30000 ? ` · ${(x / 1000).toFixed(1)} s` : '');
function state(t, c, m) {
  text = t; cls = c || ''; ms = m || 0;
  if (c === 'working' && !startAt) startAt = Date.now(); if (c !== 'working') startAt = 0;
  card.classList.toggle('busy', c === 'working');
  clearTimeout(finishT); card.classList.toggle('finish', c === 'done'); if (c === 'done') finishT = setTimeout(() => card.classList.remove('finish'), 1400); // the finish moment
  render();
}
function render() {
  const s = cls === 'working' && startAt ? Math.floor((Date.now() - startAt) / 1000) : 0;
  st.textContent = text + (ms ? dur(ms) : s >= 1 ? ` · ${s} s` : '');
  orb.className = 'orb sm ' + cls; hd.dataset.s = cls; $('#stop').hidden = !turn;
  const want = !!(text || plain(U) || plain(A) || $('#bviz').childElementCount);
  if (want !== tx.classList.contains('on')) { if (want) grow(96); tx.classList.toggle('on', want); }
  touch();
}
setInterval(() => cls === 'working' && render(), 1000);
// Window size: the overlay is cut to the card, so the app underneath stays touchable. Grow at once (so the card is never
// clipped while it opens), shrink only once the size has settled.
let sizeT, cur = 0;
const fit = () => { const h = Math.ceil(card.getBoundingClientRect().height) + 10; clearTimeout(sizeT); if (h > cur) { cur = h; N.size(h); } else if (h < cur) sizeT = setTimeout(() => { cur = h; N.size(h); }, 160); };
const grow = (extra) => { const h = Math.ceil(card.getBoundingClientRect().height) + 10 + extra; if (h > cur) { cur = h; N.size(h); } };
new ResizeObserver(fit).observe(card);

// ---------- open / close / idle ----------
let idleT, lastAct = 0, appNow = null; // appNow: the app underneath, from the shell (Bar.foreground) each time the bar opens
bar.app = (a) => { appNow = a; };
const busy = () => turn || (micTrack && Date.now() - lastAct < 45000) || document.activeElement === inp || inp.value;
function touch() { lastAct = Date.now(); clearTimeout(idleT); idleT = setTimeout(() => (busy() ? touch() : bar.close()), 30000); }
bar.open = (listen) => {
  if (!card.classList.contains('on')) { cur = 0; card.classList.remove('out'); card.classList.add('on'); setText(U, ''); setText(A, ''); $('#bviz').replaceChildren(); turn = false; state('', ''); inp.value = ''; row.classList.remove('typed'); field.classList.remove('typed'); }
  touch(); if (listen) voiceStart();
};
bar.close = (fromShell) => {
  clearTimeout(idleT); voiceStop(); inp.blur(); N.focus(false);
  if (fromShell) { card.classList.remove('on'); return; }
  card.classList.add('out'); card.classList.remove('on'); setTimeout(() => N.close(), 340);
};
bar.refocus = () => inp.focus(); // the shell made the window focusable: ask for the keyboard again
bar.blur = () => inp.blur();     // a tap on the app underneath
$('#x').onclick = () => { hap(); bar.close(); };
// Swipe the card down to close
let drag = null;
card.addEventListener('pointerdown', (e) => { if (e.target.closest('input,button')) return; drag = { y: e.clientY }; card.classList.add('drag'); });
card.addEventListener('pointermove', (e) => { if (!drag) return; const dy = Math.max(0, e.clientY - drag.y); card.style.transform = `translateY(${dy}px)`; });
const drop = (e) => { if (!drag) return; const dy = e.clientY - drag.y; drag = null; card.classList.remove('drag'); card.style.transform = ''; if (dy > 40) { hap(); bar.close(); } };
card.addEventListener('pointerup', drop); card.addEventListener('pointercancel', drop);

// ---------- typing: keyboard focus only while the field is active (Bar.focus toggles FLAG_NOT_FOCUSABLE) ----------
inp.onfocus = () => { N.focus(true); touch(); };
inp.onblur = () => { N.focus(false); if (!inp.value) { row.classList.remove('typed'); field.classList.remove('typed'); } };
inp.oninput = () => { row.classList.toggle('typed', !!inp.value); field.classList.toggle('typed', !!inp.value); touch(); };
row.onsubmit = (e) => { e.preventDefault(); const t = inp.value.trim(); if (!t) return; inp.value = ''; row.classList.remove('typed'); field.classList.remove('typed'); inp.blur(); hap('tick'); ask(t); };
function ask(t) {
  setText(U, t); setText(A, ''); $('#bviz').replaceChildren();
  if (!send({ type: 'ask', text: t, in_app: true, app: appNow })) return state('Agent offline', 'alert'); // in_app + app {pkg,label}: agentd leases the app on screen first
  turn = true; state('Thinking', 'working');
}
$('#stop').onclick = () => { hap(); send({ type: 'stop' }); };
const PRETTY = { open_app: 'Opening', tap: 'Tapping', type: 'Typing', scroll: 'Scrolling', screen_read: 'Looking at the screen', read_screen: 'Looking at the screen', back: 'Going back', home: 'Going home', device_set: 'Setting', wait: 'Waiting', task_start: 'Starting', act: 'Working' };
const toolName = (n) => PRETTY[n] || n.replace(/[._]/g, ' ').replace(/^\w/, (c) => c.toUpperCase());
on('text.delta', (m) => { reveal(A, m.text); render(); });
on('tool.request', (m) => { const a = m.args || {}; const what = m.name === 'open_app' ? (a.pkg || '').split('.').pop().replace(/^\w/, (c) => c.toUpperCase()) : a.text ? `“${String(a.text).slice(0, 24)}”` : ''; turn = true; state(`${toolName(m.name)} ${what || ''}`.trim(), 'working'); });
on('approval.shown', () => state('Waiting for your approval', 'approval')); on('approval.needed', () => state('Waiting for your approval', 'approval'));
on('approval.resolved', (m) => state(m.decision === 'approved' ? (m.approver === 'reviewer' ? 'Auto-approved ✓' : 'Approved') : m.decision === 'timeout' ? 'Timed out' : 'Denied', m.decision === 'approved' ? 'done' : 'alert'));
on('turn.done', (m) => { turn = false; state(m.status === 'completed' || m.status === 'done' ? 'Done' : { failed: 'Didn’t finish', interrupted: 'Stopped' }[m.status] || 'Done', 'done', m.ms); });
on('error', (m) => { if (m.code === 'unknown_type') return; if (m.retryable && m.code !== 'turn_failed') return state('Reconnecting…', 'working'); turn = false; state('Couldn’t reach the AI', 'alert'); });
on('visual', (m) => { if (!m.spec) return; grow(120); $('#bviz').replaceChildren(Viz.render(m.spec, { compact: true })); render(); }); // show tool
on('catchup', (m) => { setText(A, (m.cards || []).slice(0, 2).map((c) => c.title).join(' · ')); render(); });
// Background tasks (a voice delegation): step while working, the answer when done
const WORKING = new Set(['queued', 'running', 'paused', 'working']);
on('task.update', (m) => {
  const w = m.task; if (!w?.id) return;
  if (WORKING.has(w.state)) { turn = true; if (w.step) state(String(w.step).slice(0, 48), 'working'); }
  else { turn = false; if (w.result || w.error) setText(A, w.result || w.error); state({ done: 'Done', failed: 'Didn’t finish' }[w.state] || 'Stopped', w.state === 'done' ? 'done' : 'alert', w.timing?.total_ms); }
});
on('ws.close', () => { turn = false; voiceStop(); voiceDrop(); if (card.classList.contains('on')) state('Agent offline', 'alert'); });

// ---------- voice: GPT Live over WebRTC through agentd, the same gate and tap-to-interrupt as voice mode ----------
let pc, micStream, micTrack, vad, voiceAudio, voiceLive = false, voiceIdle, voiceEpoch = 0;
function micState(s) { state(s, s === 'Listening' ? 'approval' : s.startsWith('Speaking') ? 'working' : s === 'Muted' ? '' : cls); mic.classList.toggle('talk', s.startsWith('Speaking')); mic.classList.toggle('muted', s === 'Muted'); }
async function voiceStart() {
  if (micTrack?.readyState === 'live') return;
  const epoch = ++voiceEpoch; clearTimeout(voiceIdle);
  N.wake('voice:1'); mic.classList.add('live'); card.classList.add('live'); state('Connecting', 'approval');
  if (!wsOk) await new Promise((ok) => { const t = setTimeout(ok, 4000); on('ws.open', () => { clearTimeout(t); ok(); }); }); // a cold page: give the socket a moment
  if (!wsOk || epoch !== voiceEpoch) { if (epoch === voiceEpoch) { N.wake('voice:0'); mic.classList.remove('live'); card.classList.remove('live'); state('Agent offline', 'alert'); } return; }
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: voiceConstraints });
    if (epoch !== voiceEpoch) return stream.getTracks().forEach((t) => t.stop());
    micStream = stream; $('#spk').muted = false; micTrack = stream.getAudioTracks()[0];
    if (pc?.connectionState === 'connected' && voiceAudio) { voiceAudio.track = micTrack; voiceAudio.active = true; voiceAudio.muted = false; voiceAudio.apply(); }
    else voiceAudio = new VoiceAudio(micTrack, $('#spk'), (s) => micTrack && micState(s));
    if (pc?.connectionState !== 'connected') $('#spk').srcObject = null;
    vad = speech(stream);
    if (pc?.connectionState === 'connected') { await pc.getSenders()[0].replaceTrack(micTrack); if (epoch === voiceEpoch) voiceAudio.apply(); return; }
    pc?.close(); pc = new RTCPeerConnection(); pc.addTrack(micTrack, stream);
    const dc = pc.createDataChannel('oai-events');
    dc.onopen = () => { if (epoch === voiceEpoch) micState('Listening'); };
    dc.onmessage = (e) => voiceMessage(JSON.parse(e.data));
    pc.ontrack = (e) => { $('#spk').srcObject = e.streams[0]; };
    await pc.setLocalDescription(await pc.createOffer());
    await new Promise((ok) => { setTimeout(ok, 500); pc.onicegatheringstatechange = () => pc.iceGatheringState === 'complete' && ok(); });
    if (epoch === voiceEpoch) send({ type: 'voice.start', client_handoffs: true, sdp: pc.localDescription.sdp });
  } catch (e) { if (epoch === voiceEpoch) { voiceStop(); state('Mic unavailable', 'alert'); } }
}
function voiceMessage(m) {
  voiceAudio?.event(m);
  if (/^(turn\.(created|done)|input_audio_buffer\.|output_audio_buffer\.|response\.|conversation\.item\.truncated|error)/.test(m.type) && !/delta/.test(m.type)) send({ type: 'voice.audio', event: m.type, role: m.turn?.role, mic: !!micTrack?.enabled });
  if (m.type === 'delegation.created') send({ type: 'voice.delegate', item_id: m.item.id, handoff_id: m.item.handoff_id, turn_id: m.item.user_bidi_turn_id, text: m.item.content.map((c) => c.text).join('') });
  if (m.type === 'turn.done' && m.turn?.role === 'user') send({ type: 'voice.heard', turn_id: m.turn.id, text: m.turn.transcript });
  if (!micTrack) return;
  if (m.type === 'turn.created' && m.turn?.role === 'user') { voiceLive = true; setText(A, ''); }
  if (m.type === 'output_transcript.added') { voiceLive = true; reveal(A, m.item.text); }
  if (m.type === 'turn.done' && m.turn?.role === 'user') setText(U, m.turn.transcript);
  render();
}
function voiceDrop() { clearTimeout(voiceIdle); voiceAudio = null; voiceLive = false; if (pc) { send({ type: 'voice.stop' }); pc.close(); pc = null; } }
function voiceStop() {
  ++voiceEpoch; vad?.(); vad = null;
  if (voiceAudio) { voiceAudio.active = false; voiceAudio.apply(); }
  micStream?.getTracks().forEach((t) => t.stop()); micStream = micTrack = null; N.wake('voice:0'); $('#spk').muted = true;
  mic.className = 'mic'; card.classList.remove('live', 'you'); level(0, 0);
  clearTimeout(voiceIdle);
  if (pc?.connectionState === 'connected') { pc.getSenders()[0].replaceTrack(null).catch(() => {}); voiceIdle = setTimeout(voiceDrop, 60000); } // warm for a quick return
  else voiceDrop();
}
on('voice.answer', async (m) => { if (pc) await pc.setRemoteDescription({ type: 'answer', sdp: m.sdp }); });
on('voice.transcript', (m) => { if (voiceLive || !micTrack) return; const el = m.role === 'user' ? U : A; setText(el, m.text); if (!m.final) el.insertAdjacentHTML('beforeend', '<span class="cur"></span>'); if (m.role !== 'user') micState('Speaking · tap to interrupt'); render(); });
on('voice.delegation', () => { turn = true; state('Working', 'working'); });
on('voice.result', (m) => { if (!voiceLive && m.text) { setText(A, m.text); render(); } });
on('voice.closed', () => { voiceStop(); voiceDrop(); state('Ended', ''); }); on('voice.error', (m) => { voiceStop(); voiceDrop(); state(String(m.message || 'Voice error').slice(0, 40), 'alert'); });
mic.onclick = () => { hap('tick'); if (!micTrack) return voiceStart(); voiceAudio.toggle(); touch(); };
// Levels: you (--lv) and the assistant (--la), 0..1, smoothed; the orb scales, the bars rise, the edge ring brightens.
let lv = 0, la = 0;
function level(u, a) { lv += (u - lv) * (u > lv ? .6 : .25); la += (a - la) * (a > la ? .6 : .2); card.style.setProperty('--lv', lv.toFixed(3)); card.style.setProperty('--la', la.toFixed(3)); card.classList.toggle('you', lv > la && lv > .05); }
// Local speech detector: lights the orb while you talk; also feeds the gate the speaker level so it reopens the mic after quiet
function speech(stream) {
  const ac = new AudioContext(), an = ac.createAnalyser(), buf = new Float32Array(1024); ac.createMediaStreamSource(stream).connect(an);
  let hot = false, last = 0, remote;
  const rms = (a) => { a.getFloatTimeDomainData(buf); return Math.sqrt(buf.reduce((s, v) => s + v * v, 0) / buf.length); };
  const i = setInterval(() => {
    if (!remote && $('#spk').srcObject) { remote = ac.createAnalyser(); ac.createMediaStreamSource($('#spk').srcObject).connect(remote); }
    const ra = remote ? rms(remote) : 0; if (remote) voiceAudio?.sample(ra);
    const ru = micTrack?.enabled ? rms(an) : 0, t = Date.now(), loud = ru > 0.02;
    level(Math.min(1, ru * 9), Math.min(1, ra * 7));
    if (loud) { last = t; lastAct = t; if (!hot) { hot = true; mic.classList.add('hot'); } }
    else if (hot && t - last > 400) { hot = false; mic.classList.remove('hot'); }
  }, 60);
  return () => { clearInterval(i); ac.close(); mic.classList.remove('hot'); };
}
N.ready();
