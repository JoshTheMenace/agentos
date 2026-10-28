/* Visual answers (agentd visual.js -> `visual` event): one spec, drawn here as plain SVG/HTML in the A2 look.
   Viz.render(spec, {compact}) -> a .viz card. Compact (assistant bar) = chart only, tap to expand. No libraries. */
'use strict';
const Viz = (() => {
  const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  // Categorical hues in fixed order (validated on #1C1C1E: lightness, chroma, CVD and contrast all pass). Sleep stages keep
  // their own slot by name, so "deep" is always blue whatever order the model sends.
  const CAT = ['#0A84FF', '#1FA3B8', '#BF5AF2', '#C97A24', '#30D158', '#FF375F'];
  const SLOT = { deep: 0, light: 1, rem: 2, awake: 3 };
  const KIND = { good: '#30D158', peak: '#FF453A', neutral: '#8E8E93' };
  const W = 320, L1 = 'rgba(255,255,255,.92)', L3 = 'rgba(235,235,245,.38)', HAIR = 'rgba(255,255,255,.09)';
  const color = (s, i) => CAT[SLOT[String(s.name || '').toLowerCase()] ?? i % CAT.length];

  // Numbers the way a person says them: 7h 20m, 8.2K, 62 bpm.
  const fmt = (v, unit) => {
    if (v == null) return '';
    if (unit === 'min') { const h = Math.floor(v / 60), m = Math.round(v % 60); return v >= 60 ? `${h}h${m ? ` ${m}m` : ''}` : `${m}m`; }
    if (unit === 'h') return `${(+v).toFixed(v % 1 ? 1 : 0)}h`;
    const n = Math.abs(v) >= 10000 ? `${(v / 1000).toFixed(1).replace(/\.0$/, '')}K` : Math.abs(v) >= 1000 ? `${(v / 1000).toFixed(1).replace(/\.0$/, "")}K` : `${Math.round(v * 10) / 10}`;
    return unit && unit !== 'steps' ? `${n} ${unit}` : n;
  };
  const nice = (v) => { const p = 10 ** Math.floor(Math.log10(v || 1)), r = v / p; return (r <= 1 ? 1 : r <= 2 ? 2 : r <= 5 ? 5 : 10) * p; }; // 1, 2, 5 × 10^k
  // Round ticks: bars start at 0; lines zoom to the data (a flat 58-61 bpm line would otherwise sit on the ceiling of a 0-100 axis).
  const ticks = (min, max, unit, line) => {
    let lo = line ? min - (max - min || max * 0.1) * 0.6 : 0, hi = line ? max + (max - min || max * 0.1) * 0.4 : max;
    const step = unit === 'min' ? (hi - lo > 360 ? 120 : 60) : nice((hi - lo) / 4 || 1);
    lo = Math.floor(lo / step) * step; hi = Math.max(lo + step, Math.ceil(hi / step) * step);
    return { lo, hi, step };
  };
  const clock = (m) => { const h = Math.floor((m % 1440) / 60); return h === 0 ? '12 AM' : h < 12 ? `${h} AM` : h === 12 ? '12 PM' : `${h - 12} PM`; };
  const cap = (x, y, w, h, r) => { r = Math.min(r, h / 2, w / 2); return `M${x} ${y + h}V${y + r}a${r} ${r} 0 0 1 ${r}-${r}h${w - 2 * r}a${r} ${r} 0 0 1 ${r} ${r}V${y + h}Z`; };

  // Bars, stacked bars and lines share one frame: y gridlines + left ticks, x labels, 24 px max bar width, 2 px gaps.
  function chart(spec, compact) {
    const H = compact ? 64 : 132, padL = compact ? 0 : 34, padB = compact ? 0 : 20, top = 10;
    const n = spec.labels.length, stacked = spec.type === 'stacked_bar', line = spec.type === 'line';
    const totals = spec.labels.map((_, i) => (stacked ? spec.series.reduce((a, s) => a + (s.values[i] || 0), 0) : Math.max(...spec.series.map((s) => s.values[i] ?? -Infinity))));
    const vals = line ? spec.series.flatMap((s) => s.values).filter((v) => v != null) : totals.filter(Number.isFinite);
    const { lo, hi: max, step } = ticks(Math.min(...vals), Math.max(1, ...vals), spec.unit, line), ph = H - top - padB, pw = W - padL - 6;
    const x = (i) => padL + (pw / n) * (i + 0.5), y = (v) => top + ph - ((v - lo) / (max - lo)) * ph;
    const hi = spec.highlight ? spec.labels.findIndex((l) => l.toLowerCase() === spec.highlight.toLowerCase()) : -1;
    const star = hi >= 0 ? hi : totals.indexOf(Math.max(...totals.filter(Number.isFinite))); // the one labeled value
    let g = '';
    if (!compact) for (let v = lo + step; v <= max + 1e-9; v += step) { const yy = y(v); g += `<line x1="${padL}" x2="${W - 6}" y1="${yy}" y2="${yy}" stroke="${HAIR}"/><text x="${padL - 6}" y="${yy + 3.5}" text-anchor="end" class="t3">${fmt(v, /^(steps|bpm)$/.test(spec.unit) ? '' : spec.unit)}</text>`; }
    g += `<line x1="${padL}" x2="${W - 6}" y1="${y(lo)}" y2="${y(lo)}" stroke="rgba(255,255,255,.16)"/>`;
    if (line) {
      spec.series.forEach((s, si) => {
        const pts = s.values.map((v, i) => (v == null ? null : [x(i), y(v)]));
        const d = pts.reduce((a, p, i) => (p ? `${a}${a && pts[i - 1] ? 'L' : 'M'}${p[0]} ${p[1]}` : a), '');
        const c = color(s, si);
        g += `<path d="${d}" fill="none" stroke="${c}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round" pathLength="1" class="ln"/>`;
        pts.forEach((p, i) => { if (p && (i === star || i === pts.length - 1)) g += `<circle cx="${p[0]}" cy="${p[1]}" r="4" fill="${c}" stroke="#1C1C1E" stroke-width="2" class="dot" style="animation-delay:.5s"/>`; });
      });
      if (!compact && star >= 0 && spec.series[0].values[star] != null) g += `<text x="${x(star)}" y="${y(spec.series[0].values[star]) - 9}" text-anchor="middle" class="t1">${fmt(spec.series[0].values[star], spec.unit)}</text>`;
    } else {
      const bw = Math.min(24, (pw / n) * 0.62);
      spec.labels.forEach((_, i) => {
        let acc = 0, inner = '';
        spec.series.forEach((s, si) => {
          const v = s.values[i]; if (!v) return;
          const y1 = y(acc + v), h = y(acc) - y1 - (stacked && acc ? 2 : 0); acc += v; // 2 px gap between segments
          const last = stacked ? !spec.series.slice(si + 1).some((t) => t.values[i]) : true;
          inner += last ? `<path d="${cap(x(i) - bw / 2, y1, bw, Math.max(h, 1), 4)}" fill="${color(s, si)}"/>` : `<rect x="${x(i) - bw / 2}" y="${y1}" width="${bw}" height="${Math.max(h, 1)}" fill="${color(s, si)}"/>`;
          if (!stacked) return;
        });
        const dim = hi >= 0 && i !== hi ? ' opacity=".55"' : '';
        g += `<g class="vb" style="animation-delay:${(i * 0.04).toFixed(2)}s"${dim}>${inner}</g>`;
        if (!compact && i === star && totals[i] > 0) g += `<text x="${x(i)}" y="${y(totals[i]) - 6}" text-anchor="middle" class="t1 lbl">${fmt(totals[i], spec.unit)}</text>`;
      });
    }
    if (!compact) { const every = n > 8 ? Math.ceil(n / 7) : 1; spec.labels.forEach((l, i) => { if (i % every === 0 || i === n - 1) g += `<text x="${x(i)}" y="${H - 5}" text-anchor="middle" class="t3">${esc(l)}</text>`; }); }
    const legend = spec.series.length > 1 && !compact ? `<div class="leg">${spec.series.map((s, i) => `<span><i style="background:${color(s, i)}"></i>${esc(s.name)}</span>`).join('')}</div>` : '';
    return `<svg viewBox="0 0 ${W} ${H}" class="plot">${g}</svg>${legend}`;
  }

  // Today's windows on a clock track: pills for each span (good / peak / neutral), 6 h ticks, a "now" marker.
  function timeline(spec, compact) {
    const lo = Math.floor(Math.min(spec.now ?? 0, ...spec.spans.map((s) => s.from)) / 360) * 360, hiT = Math.ceil(Math.max(spec.now ?? 0, ...spec.spans.map((s) => s.to)) / 360) * 360;
    const span = Math.max(720, hiT - lo), H = compact ? 44 : 78, th = compact ? 22 : 30, top = compact ? 10 : 22, x = (m) => 6 + ((m - lo) / span) * (W - 12);
    let g = `<rect x="6" y="${top}" width="${W - 12}" height="${th}" rx="${th / 2}" fill="rgba(255,255,255,.07)"/>`;
    spec.spans.forEach((s, i) => {
      const x1 = x(s.from), w = x(s.to) - x1, c = KIND[s.kind];
      g += `<g class="vb sp" style="animation-delay:${(i * 0.08).toFixed(2)}s"><rect x="${x1 + 1}" y="${top + 2}" width="${Math.max(w - 2, 4)}" height="${th - 4}" rx="${(th - 4) / 2}" fill="${c}" fill-opacity=".32"/>`;
      if (!compact && s.label && w > s.label.length * 6.5 + 12) g += `<text x="${x1 + w / 2}" y="${top + th / 2 + 3.5}" text-anchor="middle" class="t1 sl">${esc(s.label)}</text>`;
      g += '</g>';
    });
    if (!compact) for (let m = lo; m <= lo + span; m += 360) g += `<text x="${x(m)}" y="${H - 6}" text-anchor="${m === lo ? 'start' : m === lo + span ? 'end' : 'middle'}" class="t3">${clock(m)}</text>`;
    if (spec.now != null && spec.now >= lo && spec.now <= lo + span) g += `<g class="now"><line x1="${x(spec.now)}" x2="${x(spec.now)}" y1="${top - 6}" y2="${top + th + 4}" stroke="#fff" stroke-width="2" stroke-linecap="round"/><circle cx="${x(spec.now)}" cy="${top - 8}" r="3.5" fill="#fff"/>${compact ? '' : `<text x="${x(spec.now)}" y="${top - 13}" text-anchor="middle" class="t3">now</text>`}</g>`;
    const kinds = [...new Set(spec.spans.map((s) => s.kind))];
    const legend = compact ? '' : `<div class="leg">${kinds.map((k) => `<span><i style="background:${KIND[k]}"></i>${{ good: 'Cheap & clean', peak: 'Peak price', neutral: 'Other' }[k]}</span>`).join('')}</div>`;
    return `<svg viewBox="0 0 ${W} ${H}" class="plot">${g}</svg>${legend}`;
  }

  const stats = (xs) => `<div class="stats n${xs.length}">${xs.map((s, i) => `<div class="st${String(typeof s.value === 'number' ? fmt(s.value, s.unit) : s.value).length > 6 ? ' long' : ''}" style="animation-delay:${(i * 0.06).toFixed(2)}s"><b>${typeof s.value === 'number' ? esc(fmt(s.value, s.unit)) : `${esc(s.value)}${s.unit ? `<small>${esc(s.unit)}</small>` : ''}`}</b><span>${esc(s.label)}</span></div>`).join('')}</div>`;
  const rows = (xs) => `<div class="rows">${xs.map((r, i) => `<div class="rw" style="animation-delay:${(i * 0.04).toFixed(2)}s"><span>${esc(r.label)}${r.detail ? `<small>${esc(r.detail)}</small>` : ''}</span><b>${esc(r.value)}</b></div>`).join('')}</div>`;

  function render(spec, { compact = false } = {}) {
    const el = document.createElement('div');
    el.className = `viz glass${compact ? ' compact' : ''}`;
    const body = spec.type === 'stats' ? stats(spec.stats) : spec.type === 'list' ? rows(spec.rows) : spec.type === 'timeline' ? timeline(spec, compact) : chart(spec, compact);
    el.innerHTML = `${spec.title ? `<div class="k">${esc(spec.title)}</div>` : ''}${spec.type !== 'stats' && spec.stats?.length && !compact ? stats(spec.stats) : ''}${body}${spec.caption && !compact ? `<p class="cap">${esc(spec.caption)}</p>` : ''}`;
    if (compact) el.onclick = () => { const full = render(spec); el.replaceWith(full); full.onclick = () => full.replaceWith(render(spec, { compact: true })); };
    return el;
  }
  return { render, fmt };
})();
