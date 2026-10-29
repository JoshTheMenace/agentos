// Social asset renderer. Two commands, both driven by one JSON file next to the asset:
//   node social/kit/render.mjs still <spec.json>   -> 1080x1350 PNG: wallpaper + label + phone frame + screenshot (+ redactions)
//   node social/kit/render.mjs pair <spec.json>    -> wide PNG (1600x1000): two framed screenshots side by side, a small "Before"/"After" tag over each
//   node social/kit/render.mjs video <edit.json>   -> MP4 (H.264) + poster: title card, cut phone recording in a frame with captions, end card
// Layers are drawn by frame.html in headless Chrome; ffmpeg stacks and cuts them. Paths in a spec are relative to the spec file.
import { execFileSync } from 'node:child_process';
import { readFileSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, resolve, basename } from 'node:path';
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
  const url = pathToFileURL(FRAME).href + '#' + encodeURIComponent(JSON.stringify({ w: W, h: H, ...layer }));
  execFileSync(CHROME, ['--headless=new', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1', '--default-background-color=00000000',
    '--allow-file-access-from-files', '--virtual-time-budget=3000', `--window-size=${W},${H}`, `--screenshot=${out}`, url], { stdio: 'ignore' });
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
  shoot({ mode: 'bg' }, `${tmp}/bg.png`);
  shoot({ mode: 'card', title: spec.title }, `${tmp}/title.png`);
  shoot({ mode: 'card', mark: spec.end?.mark || 'AgentOS', sub: spec.end?.sub }, `${tmp}/end.png`);
  shoot({ mode: 'fg', geo: g }, `${tmp}/phone.png`);
  const segs = spec.segments.map((s, i) => { const f = `${tmp}/cap${i}.png`; shoot({ mode: 'cap', caption: s.caption, badge: s.speed > 1 ? `${s.speed}×` : '', scrim: !!s.zoom, geo: g }, f); return { ...s, f }; });

  // Inputs: 0 wallpaper, 1 raw, 2 title, 3 end, 4 phone bezel, 5.. one caption layer per segment, then any other takes (segment "raw").
  // Per segment: wallpaper + recording (trimmed, sped up, blurred boxes, scaled into the screen) + bezel [+ push-in zoom] + caption.
  const T = spec.titleSec || 2, E = spec.end?.sec || 2.5, X = 0.35; // card lengths, crossfade
  const inputs = ['-loop', '1', '-i', `${tmp}/bg.png`, '-i', raw, '-loop', '1', '-t', T + X, '-i', `${tmp}/title.png`, '-loop', '1', '-t', E, '-i', `${tmp}/end.png`, '-loop', '1', '-i', `${tmp}/phone.png`];
  const f = [], cuts = []; let body = 0;
  const raws = [...new Set(segs.map((s) => s.raw).filter(Boolean))]; // a segment may cut from another take: "raw" on the segment
  segs.forEach((s) => inputs.push('-loop', '1', '-i', s.f)); raws.forEach((r) => inputs.push('-i', at(r)));
  segs.forEach((s, i) => {
    const sp = s.speed || 1, d = (s.to - s.from) / sp, dur = `trim=duration=${d.toFixed(3)},setpts=PTS-STARTPTS,fps=${FPS}`; body += d;
    // fps before trim: screenrecord writes no frames while the screen is still, so a cut that starts on a still screen ran late
    let v = `[${s.raw ? 5 + segs.length + raws.indexOf(s.raw) : 1}:v]fps=${FPS},trim=${s.from}:${s.to},setpts=(PTS-STARTPTS)/${sp},fps=${FPS}`;
    (s.redact || spec.redact || []).forEach((r, j) => { // blur a box (source px) on the recording
      f.push(`${v},split[m${i}_${j}][c${i}_${j}]`, `[c${i}_${j}]crop=${r.w}:${r.h}:${r.x}:${r.y},boxblur=${r.blur || 18}:3[b${i}_${j}]`);
      v = `[m${i}_${j}][b${i}_${j}]overlay=${r.x}:${r.y}`; });
    let z = ''; // zoom {k, y}: push in on the line y (source px) of the screen; ramps in over 0.4 s unless the previous segment had the same zoom
    if (s.zoom) { const { k, y } = s.zoom, prev = segs[i - 1]?.zoom, R = prev && prev.k === k && prev.y === y ? 1 : 12, fx = 2 * (g.sx + g.sw / 2), fy = 2 * (g.sy + y * g.sh / src.h);
      z = `,scale=${2 * W}:${2 * H},zoompan=z='1+${k - 1}*min(1,(on+1)/${R})':x='max(0,min(iw-iw/zoom,${fx}-iw/zoom/2))':y='max(0,min(ih-ih/zoom,${fy}-ih/zoom/2))':d=1:s=${W}x${H}:fps=${FPS}`; }
    f.push(`${v},scale=${g.sw}:${g.sh}:flags=lanczos[p${i}]`, `[0:v]${dur}[w${i}]`, `[4:v]${dur}[f${i}]`, `[${5 + i}:v]${dur}[k${i}]`,
      `[w${i}][p${i}]overlay=${g.sx}:${g.sy}[q${i}]`, `[q${i}][f${i}]overlay=0:0${z}[z${i}]`, `[z${i}][k${i}]overlay=0:0,format=yuv420p,setsar=1[s${i}]`);
    cuts.push(`[s${i}]`);
  });
  f.push(`${cuts.join('')}concat=n=${cuts.length}:v=1:a=0,fps=${FPS}[body]`,
    `[2:v]fps=${FPS},format=yuv420p,setsar=1[t]`, `[3:v]fps=${FPS},format=yuv420p,setsar=1[e]`,
    `[t][body]xfade=transition=fade:duration=${X}:offset=${T}[tb]`,
    `[tb][e]xfade=transition=fade:duration=${X}:offset=${(T + body - X).toFixed(3)},fade=out:st=${(T + body + E - X - 0.4).toFixed(3)}:d=0.4[out]`);
  const out = at(spec.out);
  execFileSync('ffmpeg', ['-y', '-v', 'error', ...inputs, '-filter_complex', f.join(';'), '-map', '[out]', '-an', '-c:v', 'libx264', '-preset', 'slow', '-crf', '19',
    '-pix_fmt', 'yuv420p', '-profile:v', 'high', '-movflags', '+faststart', out], { stdio: 'inherit' });
  const p = probe(out);
  execFileSync('ffmpeg', ['-y', '-v', 'error', '-ss', String(spec.posterAt ?? T + body * 0.8), '-i', out, '-frames:v', '1', at(spec.poster || basename(out, '.mp4') + '-poster.png')]);
  if (!spec.keepBuild) rmSync(tmp, { recursive: true, force: true });
  console.log(`${spec.out}: ${p.w}x${p.h}, ${p.d.toFixed(1)} s (title ${T} + body ${body.toFixed(1)} + end ${E})`);
} else { console.error('usage: render.mjs still|pair|video <spec.json>'); process.exit(1); }
