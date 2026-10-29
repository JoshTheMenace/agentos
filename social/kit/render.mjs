// Social asset renderer. Two commands, both driven by one JSON file next to the asset:
//   node social/kit/render.mjs still <spec.json>   -> 1080x1350 PNG: wallpaper + label + phone frame + screenshot (+ redactions)
//   node social/kit/render.mjs pair <spec.json>    -> wide PNG (1600x1000): two framed screenshots side by side, a small "Before"/"After" tag over each
//   node social/kit/render.mjs video <edit.json>   -> MP4 (H.264) + poster: the cut phone recording in a frame, a camera that zooms toward each tap, captions, optional title/end cards
// Layers are drawn by frame.html in headless Chrome; ffmpeg stacks and cuts them. Paths in a spec are relative to the spec file.
import { execFileSync } from 'node:child_process';
import { readFileSync, mkdirSync, rmSync, existsSync } from 'node:fs';
import { dirname, resolve, basename, extname } from 'node:path';
import { pathToFileURL } from 'node:url';

const CHROME = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const FRAME = resolve(dirname(new URL(import.meta.url).pathname), 'frame.html');
const [cmd, specPath] = process.argv.slice(2);
const spec = JSON.parse(readFileSync(specPath, 'utf8')), dir = dirname(resolve(specPath)), at = (p) => resolve(dir, p);
const W = spec.w || (cmd === 'pair' ? 1600 : 1080), H = spec.h || (cmd === 'pair' ? 1000 : 1350), FPS = 30;

// Phone geometry on the canvas. "bleed": a big phone that runs off the bottom (stills); "full": the whole phone fits (video).
function geometry(src, kind, o = {}) {
  const g = kind === 'bleed' ? { sw: 620, sy: 270, bezel: 16, radius: 76, capTop: 70, capH: 150 }
                             : { sh: 1010, sy: 300, bezel: 12, radius: 58, capTop: 34, capH: 236 };
  Object.assign(g, o);
  if (g.sh && !o.sw) g.sw = Math.round(g.sh * src.w / src.h / 2) * 2; else g.sh = Math.round(g.sw * src.h / src.w / 2) * 2;
  g.sx = Math.round((W - g.sw) / 2); return g;
}

function shoot(layer, out) {
  const l = { w: W, h: H, ...layer }, url = pathToFileURL(FRAME).href + '#' + encodeURIComponent(JSON.stringify(l));
  execFileSync(CHROME, ['--headless=new', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1', '--default-background-color=00000000',
    '--allow-file-access-from-files', '--virtual-time-budget=3000', `--window-size=${l.w},${l.h}`, `--screenshot=${out}`, url], { stdio: 'ignore' });
}
const probe = (f) => { const [w, h, d] = execFileSync('ffprobe', ['-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=width,height:format=duration', '-of', 'csv=p=0:s=,', f]).toString().trim().split(/[,\n]/).map(Number); return { w, h, d }; };

if (cmd === 'still') {
  const shot = at(spec.shot), src = probe(shot);
  shoot({ mode: 'still', shot: pathToFileURL(shot).href, src, label: spec.label, redact: spec.redact, geo: geometry(src, 'bleed', spec.geo) }, at(spec.out));
  console.log(spec.out);
} else if (cmd === 'pair') { // spec: {w, h, phones: [{shot, label, redact?}], redact? (shared), geo?: {sw, sy, gap}, out}
  const o = { sw: 460, sy: 120, gap: 160, ...spec.geo }, phones = spec.phones.map((p, i) => {
    const shot = at(p.shot), src = probe(shot), g = geometry(src, 'bleed', { sw: o.sw, sy: o.sy, bezel: 12, radius: 58 });
    g.sx = Math.round(W / 2 + (i - (spec.phones.length - 1) / 2) * (o.sw + o.gap) - o.sw / 2);
    return { shot: pathToFileURL(shot).href, src, label: p.label, redact: p.redact || spec.redact, geo: g }; });
  shoot({ mode: 'pair', phones }, at(spec.out));
  console.log(spec.out);
} else if (cmd === 'video') {
  const raw = at(spec.raw), src = probe(raw), g = geometry(src, 'full', spec.geo), tmp = at('.build');
  rmSync(tmp, { recursive: true, force: true }); mkdirSync(tmp);
  const RN = 14, RS = 160; // tap ripple: 14 phases (one per frame at 30 fps), 160 px cells
  shoot({ mode: 'bg' }, `${tmp}/bg.png`);
  if (spec.title) shoot({ mode: 'card', title: spec.title }, `${tmp}/title.png`);
  if (spec.end) shoot({ mode: 'card', mark: spec.end.mark || 'AgentOS', sub: spec.end.sub }, `${tmp}/end.png`);
  shoot({ mode: 'fg', geo: g }, `${tmp}/phone.png`);
  shoot({ mode: 'ripple', n: RN, size: RS, w: RN * RS, h: RS }, `${tmp}/ripple.png`);

  // The camera. Each tap (from the take's .taps log, written by kit/rec.sh), "focus" entry or legacy segment "zoom" is a "look":
  // zoomed z× on a canvas point p from output time a to b. About `lead` s before a the view eases in, after b it eases out over `out` s;
  // looks that would bounce (out + settle before the next lead) merge into one held zoom that pans. Easing is smoothstep, never linear.
  const follow = spec.camera !== 'off', CAM = { zoom: 1.8, lead: 0.5, hold: 1, out: 0.7, settle: 1, ...(typeof spec.camera === 'object' ? spec.camera : {}) };
  const canvas = (x, y) => [g.sx + x * g.sw / src.w, g.sy + y * g.sh / src.h]; // phone px -> canvas px
  const logs = {}, tapsOf = (r) => logs[r] ??= (existsSync(at(basename(r, extname(r)) + '.taps')) ? readFileSync(at(basename(r, extname(r)) + '.taps'), 'utf8').split('\n') : [])
    .map((l) => l.trim().split(/\s+/)).filter((w) => w[0] === 'tap' || w[0] === 'swipe').map(([, t, x, y]) => ({ t: +t + (spec.tapShift || 0), x: +x, y: +y }));
  const looks = [], caps = [], segs = []; let body = 0;
  spec.segments.forEach((s, i) => {
    const sp = s.speed || 1, d = (s.to - s.from) / sp + (s.hold || 0), start = body, r = s.raw || spec.raw, k = s.camera?.zoom || CAM.zoom, ot = (t) => start + (t - s.from) / sp;
    const f = `${tmp}/cap${i}.png`; shoot({ mode: 'cap', caption: s.caption, style: spec.captions, badge: s.speed > 1 ? `${s.speed}×` : '', scrim: !!s.caption && (follow || !!s.zoom), geo: g }, f);
    if (follow && s.camera !== 'off') {
      tapsOf(r).filter((t) => t.t >= s.from && t.t <= s.to).forEach((t) => { const a = ot(t.t); looks.push({ a, b: a + CAM.hold, z: k, p: canvas(t.x, t.y) }); caps.push({ i, A: a - start, p: canvas(t.x, t.y) }); });
      [].concat(s.focus || []).forEach((o) => looks.push({ a: ot(o.at), b: o.until ? ot(o.until) : ot(o.at) + CAM.hold, z: o.zoom || k, p: canvas(o.x, o.y) }));
    }
    if (s.zoom) looks.push({ a: start, b: start + d, z: s.zoom.k, p: canvas(src.w / 2, s.zoom.y) });
    segs.push({ ...s, i, sp, d, start, r }); body += d;
  });
  const K = []; // keyframes [t, z, cx, cy] on the output timeline; between two the camera eases
  const key = (t, z, p) => { t = Math.max(0, t); if (K.length && K.at(-1)[0] >= t) K.pop(); K.push([t, z, p[0], p[1]]); };
  let prev; for (const L of looks.sort((u, v) => u.a - v.a)) {
    if (prev && L.a - CAM.lead < prev.b + CAM.out + CAM.settle) { // stay in: hold on prev, then pan (longer for far points)
      const pan = Math.max(CAM.lead, Math.hypot(L.p[0] - prev.p[0], L.p[1] - prev.p[1]) / 1200);
      key(Math.max(prev.a, Math.min(prev.b, L.a - pan)), prev.z, prev.p); key(L.a, L.z, L.p); L.b = Math.max(L.b, prev.b);
    } else { if (prev) { key(prev.b, prev.z, prev.p); key(prev.b + CAM.out, 1, prev.p); } key(L.a - CAM.lead, 1, L.p); key(L.a, L.z, L.p); }
    prev = L; }
  if (prev) { key(prev.b, prev.z, prev.p); key(prev.b + CAM.out, 1, prev.p); }
  const ease = (u) => `(${u})*(${u})*(3-2*(${u}))`; // smoothstep of a clipped 0..1 ramp
  const camExpr = (T, j, lo, hi) => { // piecewise value j (1 z, 2 cx, 3 cy) at time T, using the keyframes that touch [lo, hi]
    const ks = K.filter((k, n) => k[0] >= lo - 0.001 && k[0] <= hi + 0.001 || (K[n + 1] && K[n + 1][0] > lo && k[0] < lo) || (K[n - 1] && K[n - 1][0] < hi && k[0] > hi));
    if (!ks.length) return null;
    let e = String(ks.at(-1)[j]);
    for (let n = ks.length - 2; n >= 0; n--) { const [t0, ...a] = ks[n], [t1, ...b] = ks[n + 1], v0 = a[j - 1], v1 = b[j - 1];
      e = `if(lt(${T},${t1.toFixed(3)}),${v0 === v1 ? v0 : `(${v0}+${(v1 - v0).toFixed(2)}*${ease(`clip((${T}-${t0.toFixed(3)})/${(t1 - t0).toFixed(3)},0,1)`)})`},${e})`; }
    return e; };

  // Inputs: 0 wallpaper, 1 raw, 2 title (or wallpaper), 3 end (or wallpaper), 4 phone bezel, 5 ripple strip, 6.. one caption layer per segment, then any other takes.
  // Per segment: wallpaper + recording (trimmed, sped up, blurred boxes, scaled into the screen) + bezel + tap ripples [+ camera] + caption.
  const T = spec.title ? spec.titleSec || 2 : 0, E = spec.end ? spec.end.sec || 2.5 : 0, X = 0.35; // card lengths, crossfade
  const inputs = ['-loop', '1', '-i', `${tmp}/bg.png`, '-i', raw, '-loop', '1', '-t', T + X, '-i', `${tmp}/${spec.title ? 'title' : 'bg'}.png`, '-loop', '1', '-t', E, '-i', `${tmp}/${spec.end ? 'end' : 'bg'}.png`,
    '-loop', '1', '-i', `${tmp}/phone.png`, '-loop', '1', '-framerate', String(FPS), '-i', `${tmp}/ripple.png`];
  const f = [], cuts = [], N0 = 6 + segs.length;
  const raws = [...new Set(segs.map((s) => s.raw).filter(Boolean))]; // a segment may cut from another take: "raw" on the segment
  segs.forEach((s) => inputs.push('-loop', '1', '-i', `${tmp}/cap${s.i}.png`)); raws.forEach((r) => inputs.push('-i', at(r)));
  if (caps.length) f.push(`[5:v]split=${caps.length}${caps.map((c, n) => `[rp${n}]`).join('')}`);
  segs.forEach((s) => {
    const { i, sp, d } = s, dur = `trim=duration=${d.toFixed(3)},setpts=PTS-STARTPTS,fps=${FPS}`;
    // fps before trim: screenrecord writes no frames while the screen is still, so a cut that starts on a still screen ran late
    let v = `[${s.raw ? N0 + raws.indexOf(s.raw) : 1}:v]fps=${FPS},trim=${s.from}:${s.to},setpts=(PTS-STARTPTS)/${sp},fps=${FPS}${s.hold ? `,tpad=stop_mode=clone:stop_duration=${s.hold}` : ''}`; // hold: the last frame stays that long (a still screen at the end of a take has no frames to cut)
    (s.redact || spec.redact || []).forEach((r, j) => { // blur a box (source px) on the recording
      f.push(`${v},split[m${i}_${j}][c${i}_${j}]`, `[c${i}_${j}]crop=${r.w}:${r.h}:${r.x}:${r.y},boxblur=${r.blur || 18}:3[b${i}_${j}]`);
      v = `[m${i}_${j}][b${i}_${j}]overlay=${r.x}:${r.y}`; });
    f.push(`${v},scale=${g.sw}:${g.sh}:flags=lanczos[p${i}]`, `[0:v]${dur}[w${i}]`, `[4:v]${dur}[f${i}]`, `[${6 + i}:v]${dur}[k${i}]`,
      `[w${i}][p${i}]overlay=${g.sx}:${g.sy}[q${i}]`, `[q${i}][f${i}]overlay=0:0[r${i}_0]`);
    let rr = `[r${i}_0]`; caps.forEach((c, n) => { if (c.i !== i) return; const A = c.A.toFixed(3); // the ripple strip: cell = frames since the tap
      f.push(`[rp${n}]${dur},crop=${RS}:${RS}:'${RS}*clip(floor((t-${A})*${FPS}),0,${RN - 1})':0[rc${n}]`,
        `${rr}[rc${n}]overlay=${Math.round(c.p[0] - RS / 2)}:${Math.round(c.p[1] - RS / 2)}:enable='between(t,${A},${(c.A + RN / FPS).toFixed(3)})'[r${i}_${n + 1}]`); rr = `[r${i}_${n + 1}]`; });
    const t = `(${s.start.toFixed(3)}+on/${FPS})`, zx = camExpr(t, 1, s.start, s.start + d); // zoompan runs on a 2x canvas for sub-pixel moves
    const z = zx && zx !== '1' ? `,scale=${2 * W}:${2 * H},zoompan=z='${zx}':x='max(0,min(iw-iw/zoom,2*(${camExpr(t, 2, s.start, s.start + d)})-iw/zoom/2))':y='max(0,min(ih-ih/zoom,2*(${camExpr(t, 3, s.start, s.start + d)})-ih/zoom/2))':d=1:s=${W}x${H}:fps=${FPS}` : '';
    f.push(`${rr}null${z}[z${i}]`, `[z${i}][k${i}]overlay=0:0,format=yuv420p,setsar=1[s${i}]`);
    cuts.push(`[s${i}]`);
  });
  f.push(`${cuts.join('')}concat=n=${cuts.length}:v=1:a=0,fps=${FPS}[body]`);
  const parts = [{ l: '[body]', d: body }]; let outL = '[body]';
  if (spec.title) { f.push(`[2:v]fps=${FPS},format=yuv420p,setsar=1[t]`); parts.unshift({ l: '[t]', d: T }); }
  if (spec.end) { f.push(`[3:v]fps=${FPS},format=yuv420p,setsar=1[e]`); parts.push({ l: '[e]', d: E }); }
  let off = 0; for (let n = 1; n < parts.length; n++) { off += parts[n - 1].d - X; const o = n === parts.length - 1 ? '[out]' : `[x${n}]`;
    f.push(`${n === 1 ? parts[0].l : `[x${n - 1}]`}${parts[n].l}xfade=transition=fade:duration=${X}:offset=${off.toFixed(3)}${o}`); outL = o; }
  if (spec.end) { f.push(`${outL}fade=out:st=${(off + E - 0.4).toFixed(3)}:d=0.4[final]`); outL = '[final]'; }
  const out = at(spec.out);
  execFileSync('ffmpeg', ['-y', '-v', 'error', ...inputs, '-filter_complex', f.join(';'), '-map', outL, '-an', '-c:v', 'libx264', '-preset', 'slow', '-crf', '19',
    '-pix_fmt', 'yuv420p', '-profile:v', 'high', '-movflags', '+faststart', out], { stdio: 'inherit' });
  const p = probe(out);
  execFileSync('ffmpeg', ['-y', '-v', 'error', '-ss', String(spec.posterAt ?? T + body * 0.8), '-i', out, '-frames:v', '1', at(spec.poster || basename(out, '.mp4') + '-poster.png')]);
  if (!spec.keepBuild) rmSync(tmp, { recursive: true, force: true });
  console.log(`${spec.out}: ${p.w}x${p.h}, ${p.d.toFixed(1)} s (title ${T} + body ${body.toFixed(1)} + end ${E}), ${looks.length} camera looks, ${caps.length} taps`);
} else { console.error('usage: render.mjs still|pair|video <spec.json>'); process.exit(1); }
