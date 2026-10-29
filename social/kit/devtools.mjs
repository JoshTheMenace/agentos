// Run JS in one of the launcher's WebViews over Chrome DevTools (home page or a micro-app) and print the result as JSON.
//   node social/kit/devtools.mjs <url-regex> '<expression>' [...]     e.g.  'agentos\.home/index'  or  'agentos\.app'  ('agentos\.home' alone also matches bar.html)
// Use it to find where to tap: rect('#askIn') gives the element's centre in phone pixels, ready for `adb shell input tap`.
import { execSync } from 'node:child_process';
const S = process.env.ANDROID_SERIAL || 'ZY22J3TK8S', [re, ...exprs] = process.argv.slice(2);
setTimeout(() => { console.error('devtools: timed out'); process.exit(1); }, 20000).unref(); // never hold the phone lock on a hung call
const pid = execSync(`adb -s ${S} shell pidof local.agentos.home`).toString().trim().split(/\s+/)[0];
execSync(`adb -s ${S} forward tcp:9224 localabstract:webview_devtools_remote_${pid}`);
const pages = (await (await fetch('http://127.0.0.1:9224/json')).json()).filter((p) => p.type === 'page' && new RegExp(re).test(p.url));
if (!pages.length) { console.error('no page matching', re); process.exit(1); }
const open = async (p) => { const ws = new WebSocket(p.webSocketDebuggerUrl); await new Promise((ok, no) => { ws.onopen = ok; ws.onerror = no; }); return ws; };
let id = 0; const callOn = (ws, method, params) => new Promise((ok) => { const i = ++id; ws.addEventListener('message', function h(e) { const m = JSON.parse(e.data); if (m.id === i) { ws.removeEventListener('message', h); ok(m.result); } }); ws.send(JSON.stringify({ id: i, method, params })); });
// A finished micro-app activity keeps its WebView alive for a while, so one app can show several pages: use the one on screen.
let ws = null;
for (const p of pages.slice().reverse()) { const w = await open(p); if (pages.length === 1 || (await callOn(w, 'Runtime.evaluate', { expression: 'document.visibilityState', returnByValue: true }))?.result?.value === 'visible') { ws = w; break; } w.close(); }
ws ??= await open(pages.at(-1));
const call = (method, params) => callOn(ws, method, params);
const helper = `window.rect = (q) => { const e = typeof q === 'string' ? document.querySelector(q) : q; if (!e) return null; const r = e.getBoundingClientRect(), k = devicePixelRatio;
  return { x: Math.round((r.left + r.width / 2) * k), y: Math.round((r.top + r.height / 2) * k), w: Math.round(r.width * k), h: Math.round(r.height * k), text: (e.innerText || e.value || '').slice(0, 60) }; }; 1`;
await call('Runtime.evaluate', { expression: helper });
for (const expression of exprs) { const r = await call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true }); console.log(r?.exceptionDetails ? 'ERR ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text) : JSON.stringify(r?.result?.value ?? null)); }
ws.close();
