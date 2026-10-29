/* AgentOS micro-app helper (served at /ui.js). The only way an app reaches the phone: AgentOSApp, the bridge the MicroApp
   WebView injects (summary, haptic, close, uses, call). Storage is the app's own localStorage (one origin per app).
   `call` only works for capabilities the owner granted at install (the <meta name="agentos-uses"> list): the bridge refuses
   the rest and agentd checks again. Every answer comes back as data, never as code. */
window.AOS = (() => {
  const B = window.AgentOSApp || { summary() {}, haptic() {}, close() {}, uses() { return ''; }, call(id) { setTimeout(() => AOS._r(id, '{"ok":false,"error":"not on the phone"}')); } };
  const load = (k, d) => { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch { return d; } };
  const save = (k, v) => { localStorage.setItem(k, JSON.stringify(v)); return v; };
  const summary = (t) => B.summary(String(t ?? '').slice(0, 80));
  const haptic = (k) => B.haptic(k || 'click');
  const close = () => B.close();
  const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const el = (html) => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstChild; };
  const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  // toast("Logged"); undo("Deleted", () => restore()) shows an Undo button for 4 s.
  let tt, undoFn = null;
  const toastEl = () => document.getElementById('aos-toast') || document.body.appendChild(Object.assign(document.createElement('div'), { id: 'aos-toast' }));
  const toast = (t, ms = 1600) => { const e = toastEl(); e.className = 'on'; e.textContent = t; undoFn = null; clearTimeout(tt); tt = setTimeout(() => e.classList.remove('on'), ms); };
  const undo = (t, fn) => { toast(t, 4000); const e = toastEl(); e.classList.add('undo'); undoFn = fn; const b = el('<button>Undo</button>'); b.onclick = () => { haptic(); undoFn?.(); undoFn = null; e.classList.remove('on'); }; e.append(b); };
  // dates
  const pad = (n) => String(n).padStart(2, '0');
  const dayKey = (d = new Date()) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`; // local YYYY-MM-DD
  const lastDays = (n = 7) => Array.from({ length: n }, (_, i) => { const d = new Date(); d.setDate(d.getDate() - (n - 1 - i)); return d; });
  const dayName = (d) => ['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'][d.getDay()];
  const clock = (s) => `${Math.floor(s / 60)}:${pad(Math.max(0, Math.round(s)) % 60)}`; // 125 -> "2:05"
  const time = (t) => new Date(t).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  const ago = (t) => { const m = Math.round((Date.now() - t) / 60000); return m < 1 ? 'now' : m < 60 ? `${m} min ago` : m < 1440 ? `${Math.round(m / 60)} h ago` : dayKey(new Date(t)) === dayKey(new Date(Date.now() - 864e5)) ? 'Yesterday' : new Date(t).toLocaleDateString([], { month: 'short', day: 'numeric' }); };
  const svgIn = (el, cls = 'chart') => { if (el.tagName !== 'svg') { const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg'); s.setAttribute('class', cls); el.replaceChildren(s); el = s; } return el; };
  const width = (el) => Math.round(el.clientWidth || el.parentNode?.clientWidth || 320);
  // Bar chart into el (an <svg class="chart"> or any element): values[], labels[]; the last bar is highlighted.
  const bars = (el, values, labels = [], { unit = '' } = {}) => {
    el = svgIn(el);
    const W = width(el), H = 150, n = Math.max(1, values.length), max = Math.max(1, ...values), gap = 8, bw = (W - gap * (n + 1)) / n, base = H - 22;
    let s = `<line x1="0" y1="${base}" x2="${W}" y2="${base}"/>`;
    values.forEach((v, i) => { const h = Math.max(v > 0 ? 6 : 2, (base - 26) * v / max), x = gap + i * (bw + gap), y = base - h;
      s += `<rect x="${x}" y="${y}" width="${bw}" height="${h}" rx="${Math.min(7, bw / 2)}" class="${i === n - 1 ? 'hi' : ''}"/>`;
      if (v > 0 && n <= 14) s += `<text class="v" x="${x + bw / 2}" y="${y - 7}">${esc(v)}${esc(unit)}</text>`;
      if (labels[i] != null) s += `<text x="${x + bw / 2}" y="${H - 6}">${esc(labels[i])}</text>`; });
    el.setAttribute('viewBox', `0 0 ${W} ${H}`); el.innerHTML = s;
  };
  // Line / area chart: values[] (null = gap), labels[]; {area:true} fills under the line, {unit}, {min,max} fix the scale.
  const line = (el, values, labels = [], { area = false, unit = '', min, max } = {}) => {
    el = svgIn(el);
    const W = width(el), H = 150, n = Math.max(2, values.length), nums = values.filter((v) => v != null), lo = min ?? Math.min(...nums, 0), hi = Math.max(lo + 1, max ?? Math.max(...nums)), base = H - 22, top = 14;
    const X = (i) => 12 + i * (W - 24) / (n - 1), Y = (v) => base - (base - top) * (v - lo) / (hi - lo);
    let d = '', s = `<line x1="0" y1="${base}" x2="${W}" y2="${base}"/>`, last = -1;
    values.forEach((v, i) => { if (v == null) { d += ' '; return; } d += `${d.trim() && !d.endsWith(' ') ? 'L' : 'M'}${X(i).toFixed(1)},${Y(v).toFixed(1)}`; last = i; });
    if (area && last >= 0) { const first = values.findIndex((v) => v != null); s += `<path class="ar" d="${d}L${X(last)},${base}L${X(first)},${base}Z"/>`; }
    s += `<path class="ln" d="${d}"/>`;
    values.forEach((v, i) => { if (v == null) return; s += `<circle class="pt${i === last ? ' hi' : ''}" cx="${X(i)}" cy="${Y(v)}" r="3.5"/>`; if (i === last) s += `<text class="v" x="${X(i)}" y="${Y(v) - 10}">${esc(v)}${esc(unit)}</text>`; });
    labels.forEach((l, i) => { if (l != null && (n <= 8 || i % Math.ceil(n / 7) === 0 || i === n - 1)) s += `<text x="${X(i)}" y="${H - 6}">${esc(l)}</text>`; });
    el.setAttribute('viewBox', `0 0 ${W} ${H}`); el.innerHTML = s;
  };
  // Donut: parts [{v, label, color?}], {center:"68%", sub:"of goal"}; a legend is drawn below when labels are given.
  const donut = (el, parts, { center = '', sub = '' } = {}) => {
    const colors = ['var(--acc)', 'var(--purple)', 'var(--teal)', 'var(--orange)', 'var(--pink)', 'var(--green)'];
    const tot = Math.max(1e-9, parts.reduce((a, p) => a + (p.v || 0), 0)), r = 70, c = 2 * Math.PI * r; let off = 0, s = '';
    parts.forEach((p, i) => { const f = (p.v || 0) / tot, col = p.color || colors[i % colors.length]; s += `<circle cx="95" cy="95" r="${r}" stroke="${col}" stroke-dasharray="${(f * c).toFixed(1)} ${c}" stroke-dashoffset="${(-off * c).toFixed(1)}" transform="rotate(-90 95 95)"/>`; off += f; });
    if (!tot || tot < 1e-8) s = `<circle cx="95" cy="95" r="${r}" stroke="rgba(255,255,255,.1)"/>`;
    s += `<text class="c" x="95" y="${sub ? 100 : 104}">${esc(center)}</text>${sub ? `<text class="s" x="95" y="118">${esc(sub)}</text>` : ''}`;
    const svg = svgIn(el, 'chart donut'); svg.setAttribute('viewBox', '0 0 190 190'); svg.innerHTML = s;
    if (parts.some((p) => p.label)) { const lg = svg.parentNode.querySelector('.legend') || svg.parentNode.appendChild(el2('<div class="legend"></div>')); lg.innerHTML = parts.map((p, i) => `<span><i style="--c:${p.color || colors[i % colors.length]}"></i>${esc(p.label)}</span>`).join(''); }
  };
  const el2 = el;
  // Progress ring into el: fraction 0..1, with children (e.g. a .big number) kept in the middle.
  const ring = (el, frac) => { const r = 62, c = 2 * Math.PI * r, f = Math.max(0, Math.min(1, frac || 0));
    let svg = el.querySelector('svg'); if (!svg) { el.classList.add('ring'); el.insertAdjacentHTML('afterbegin', `<svg viewBox="0 0 150 150"><circle class="bg" cx="75" cy="75" r="${r}"/><circle class="fg" cx="75" cy="75" r="${r}" stroke-dasharray="${c}" stroke-dashoffset="${c}"/></svg>`); svg = el.querySelector('svg'); }
    svg.querySelector('.fg').setAttribute('stroke-dashoffset', c * (1 - f)); };
  // Calendar heatmap: data {"YYYY-MM-DD": number}, {weeks:16, max} -> columns are weeks (oldest left), rows Mon..Sun, today outlined.
  const heatmap = (el, data, { weeks = 16, max } = {}) => {
    const today = new Date(), end = new Date(today); end.setDate(end.getDate() + (7 - ((today.getDay() + 6) % 7) - 1)); // this week's Sunday
    const start = new Date(end); start.setDate(end.getDate() - weeks * 7 + 1);
    const top = max || Math.max(1, ...Object.values(data)), tk = dayKey(today); let s = '';
    for (let d = new Date(start); d <= end; d.setDate(d.getDate() + 1)) { const k = dayKey(d), v = data[k] || 0, l = v <= 0 ? 0 : Math.min(4, Math.ceil(4 * v / top));
      s += `<i class="${l ? `l${l}` : ''}${k === tk ? ' today' : ''}${d > today ? ' off' : ''}" title="${k}: ${v}"></i>`; }
    el.classList.add('heat'); el.innerHTML = s;
  };
  // Views + bottom nav: view("stats") shows .view[data-view=stats], marks nav buttons, scrolls to top. Nav buttons wire themselves.
  const view = (name, { back = false } = {}) => { document.querySelectorAll('.view').forEach((v) => { const on = v.dataset.view === name; v.classList.toggle('on', on); v.classList.toggle('back', on && back); });
    document.querySelectorAll('.nav button[data-view]').forEach((b) => b.classList.toggle('on', b.dataset.view === name)); scrollTo(0, 0); haptic('tick'); };
  addEventListener('click', (e) => { const b = e.target.closest('.nav button[data-view],[data-goto]'); if (b) view(b.dataset.view || b.dataset.goto, { back: b.hasAttribute('data-back') }); });
  // Bottom sheet: sheet(`<h2>Edit</h2>...`) opens and returns the panel element (bind inputs on it); sheet.close().
  const sheet = (html) => { let sc = document.getElementById('aos-scrim'), sh = document.getElementById('aos-sheet');
    if (!sh) { sc = document.body.appendChild(el('<div id="aos-scrim"></div>')); sh = document.body.appendChild(el('<div id="aos-sheet"></div>')); sc.onclick = sheet.close; }
    sh.innerHTML = html; requestAnimationFrame(() => { sc.classList.add('on'); sh.classList.add('on'); }); haptic('tick'); return sh; };
  sheet.close = () => { document.getElementById('aos-scrim')?.classList.remove('on'); document.getElementById('aos-sheet')?.classList.remove('on'); };
  // Swipe-to-delete: list rows <div class="item swipe" data-id="x"><div class="sw">…row content…</div><div class="del">Delete</div></div>; onDelete(id, rowEl).
  const swipe = (list, onDelete) => { let row, x0, dx;
    list.addEventListener('pointerdown', (e) => { row = e.target.closest('.swipe'); if (!row) return; x0 = e.clientX; dx = 0; list.querySelectorAll('.swipe.open').forEach((r) => r !== row && r.classList.remove('open')); });
    list.addEventListener('pointermove', (e) => { if (!row || x0 == null) return; dx = e.clientX - x0; if (Math.abs(dx) > 10) { row.classList.toggle('open', dx < -40); row.setPointerCapture?.(e.pointerId); } });
    list.addEventListener('pointerup', () => { x0 = null; row = null; });
    list.addEventListener('click', (e) => { const d = e.target.closest('.swipe .del'); if (!d) return; const r = d.closest('.swipe'); haptic('heavy'); r.classList.remove('open'); onDelete(r.dataset.id, r); }); };
  // Stepper: stepper(el, {value, min, max, step, unit, onChange}) renders − value +.
  const stepper = (box, { value = 0, min = 0, max = 99, step = 1, unit = '', onChange }) => { box.classList.add('stepper'); let v = value;
    const draw = () => (box.innerHTML = `<button data-d="-1">−</button><b>${v}${esc(unit)}</b><button data-d="1">+</button>`); draw();
    box.onclick = (e) => { const d = +e.target.closest('button')?.dataset.d; if (!d) return; const nv = Math.min(max, Math.max(min, +(v + d * step).toFixed(2))); if (nv === v) return; v = nv; haptic('tick'); draw(); onChange?.(v); }; return { get: () => v, set: (nv) => { v = nv; draw(); } }; };
  // Slider: an <input type="range" class="slider">; slider(input, onChange) keeps the fill in step and gives tick haptics.
  const slider = (inp, onChange) => { const paint = () => inp.style.setProperty('--p', `${100 * (inp.value - inp.min) / (inp.max - inp.min)}%`); paint();
    inp.oninput = () => { paint(); haptic('tick'); onChange?.(+inp.value); }; };
  // Empty state: empty(el, "🌱", "No plants yet", "Add your first one below").
  const empty = (box, emo, title, hint = '') => { box.className = 'empty rise'; box.innerHTML = `<span class="emo">${emo}</span><b>${esc(title)}</b>${hint ? `<small>${esc(hint)}</small>` : ''}`; };
  // Timer: const t = timer(25*60, (left) => ..., () => ...); t.pause(); t.resume(); t.stop(). Ends with a chime + two heavy haptics.
  let actx;
  const beep = () => { try { actx ??= new (window.AudioContext || window.webkitAudioContext)(); const t = actx.currentTime;
    [523, 659, 784, 1047].forEach((f, i) => { const o = actx.createOscillator(), g = actx.createGain(); o.frequency.value = f; o.type = 'sine'; g.gain.setValueAtTime(0, t + i * .16); g.gain.linearRampToValueAtTime(.4, t + i * .16 + .03); g.gain.exponentialRampToValueAtTime(.001, t + i * .16 + .5); o.connect(g).connect(actx.destination); o.start(t + i * .16); o.stop(t + i * .16 + .55); }); } catch {}
    haptic('heavy'); setTimeout(() => haptic('heavy'), 250); };
  addEventListener('pointerdown', () => { try { actx ??= new (window.AudioContext || window.webkitAudioContext)(); actx.resume(); } catch {} }, { once: true }); // unlock audio on the first tap
  const timer = (seconds, onTick, onDone) => { let end = Date.now() + seconds * 1000, left = seconds, iv = null;
    const tick = () => { left = Math.max(0, (end - Date.now()) / 1000); onTick?.(left); if (left <= 0) { clearInterval(iv); iv = null; beep(); onDone?.(); } };
    const run = () => { clearInterval(iv); iv = setInterval(tick, 250); tick(); }; run();
    return { pause() { clearInterval(iv); iv = null; left = Math.max(0, (end - Date.now()) / 1000); }, resume() { end = Date.now() + left * 1000; run(); }, stop() { clearInterval(iv); iv = null; }, get left() { return left; }, get running() { return !!iv; } }; };
  // ---- capabilities (owner-granted, see <meta name="agentos-uses">). Each returns a Promise; refused -> {ok:false, error}. ----
  const granted = () => String(B.uses?.() || '').split(/[,\s]+/).filter(Boolean);
  const can = (cap) => granted().includes(cap);
  const pend = new Map();
  const call = (cap, args = {}) => new Promise((ok) => { if (!can(cap)) return ok({ ok: false, error: `${cap} not granted by the owner` }); const id = uid(); pend.set(id, ok); B.call(id, cap, JSON.stringify(args)); setTimeout(() => { if (pend.delete(id)) ok({ ok: false, error: 'timed out' }); }, 45000); });
  const _r = (id, json) => { const ok = pend.get(id); pend.delete(id); try { ok?.(JSON.parse(json)); } catch { ok?.({ ok: false, error: 'bad reply' }); } };
  const calendar = (days = 7) => call('calendar.read', { days });                    // -> {ok, events:[{title, start, end, allDay, location}]}
  const health = (metric = 'sleep', days = 14) => call('health.read', { metric, days }); // metric: sleep|heart_rate|steps|exercise|hrv|stress -> {ok, ...the watch's daily rows}
  const energy = () => call('energy.read');                                          // -> {ok, ...the grid outlook: current period, cheapest/cleanest windows}
  const ask = (prompt) => call('ai', { prompt: String(prompt).slice(0, 4000) });      // -> {ok, text}: a small model answer, plain text (ask for JSON in the prompt if you need it; parse defensively)
  const notify = (text, { inMinutes, at } = {}) => call('notify', { text: String(text).slice(0, 200), at: at || Date.now() + (inMinutes || 1) * 60000 }); // -> {ok, id}: a phone notification at that time
  return { load, save, summary, haptic, close, toast, undo, dayKey, lastDays, dayName, clock, time, ago, esc, el, uid, bars, line, donut, ring, heatmap, view, sheet, swipe, stepper, slider, empty, timer, beep,
    can, granted, calendar, health, energy, ask, notify, _r };
})();
