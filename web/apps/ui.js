/* AgentOS micro-app helper (served at /ui.js). The only way an app reaches the phone: AgentOSApp (a 3-method bridge the
   MicroApp WebView injects: summary, haptic, close). Storage is the app's own localStorage (one origin per app). */
window.AOS = (() => {
  const B = window.AgentOSApp || { summary() {}, haptic() {}, close() {} };
  const load = (k, d) => { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch { return d; } };
  const save = (k, v) => { localStorage.setItem(k, JSON.stringify(v)); return v; };
  const summary = (t) => B.summary(String(t ?? '').slice(0, 80));
  const haptic = (k) => B.haptic(k || 'click');
  const close = () => B.close();
  let tt;
  const toast = (t) => { const e = document.getElementById('aos-toast') || document.body.appendChild(Object.assign(document.createElement('div'), { id: 'aos-toast' })); e.textContent = t; e.classList.add('on'); clearTimeout(tt); tt = setTimeout(() => e.classList.remove('on'), 1600); };
  const pad = (n) => String(n).padStart(2, '0');
  const dayKey = (d = new Date()) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`; // local YYYY-MM-DD
  const lastDays = (n = 7) => Array.from({ length: n }, (_, i) => { const d = new Date(); d.setDate(d.getDate() - (n - 1 - i)); return d; });
  const dayName = (d) => ['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'][d.getDay()];
  const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  // Bar chart into el (an <svg class="chart"> or any element): values[], labels[]; the last bar is highlighted.
  const bars = (el, values, labels = [], { unit = '' } = {}) => {
    if (el.tagName !== 'svg') { const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg'); s.setAttribute('class', 'chart'); el.replaceChildren(s); el = s; }
    const W = Math.round(el.clientWidth || el.parentNode?.clientWidth || 320), H = 150, n = Math.max(1, values.length), max = Math.max(1, ...values), gap = 8, bw = (W - gap * (n + 1)) / n, base = H - 22;
    let s = `<line x1="0" y1="${base}" x2="${W}" y2="${base}"/>`;
    values.forEach((v, i) => { const h = Math.max(v > 0 ? 6 : 2, (base - 26) * v / max), x = gap + i * (bw + gap), y = base - h;
      s += `<rect x="${x}" y="${y}" width="${bw}" height="${h}" rx="${Math.min(7, bw / 2)}" class="${i === n - 1 ? 'hi' : ''}"/>`;
      if (v > 0) s += `<text class="v" x="${x + bw / 2}" y="${y - 7}">${esc(v)}${esc(unit)}</text>`;
      if (labels[i] != null) s += `<text x="${x + bw / 2}" y="${H - 6}">${esc(labels[i])}</text>`; });
    el.setAttribute('viewBox', `0 0 ${W} ${H}`); el.innerHTML = s;
  };
  // Progress ring into el: fraction 0..1, with children (e.g. a .big number) kept in the middle.
  const ring = (el, frac) => { const r = 62, c = 2 * Math.PI * r, f = Math.max(0, Math.min(1, frac || 0));
    let svg = el.querySelector('svg'); if (!svg) { el.classList.add('ring'); el.insertAdjacentHTML('afterbegin', `<svg viewBox="0 0 150 150"><circle class="bg" cx="75" cy="75" r="${r}"/><circle class="fg" cx="75" cy="75" r="${r}" stroke-dasharray="${c}" stroke-dashoffset="${c}"/></svg>`); svg = el.querySelector('svg'); }
    svg.querySelector('.fg').setAttribute('stroke-dashoffset', c * (1 - f)); };
  return { load, save, summary, haptic, close, toast, dayKey, lastDays, dayName, esc, bars, ring };
})();
