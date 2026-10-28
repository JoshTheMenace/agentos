// Evaluate JS inside the launcher's WebView over the Chrome DevTools protocol (WebView.setWebContentsDebuggingEnabled is on).
// Lets a review script put the page into a state (emit a fake agentd event, open a sheet) without agentd or a model turn.
// usage: node drive.mjs '<expression>' [...more]   (run inside scripts/withphone)
import { execSync } from 'node:child_process';
const S = process.env.ANDROID_SERIAL || 'ZY22J3TK8S';
const pid = execSync(`adb -s ${S} shell pidof local.agentos.home`).toString().trim().split(/\s+/).pop();
execSync(`adb -s ${S} forward tcp:9224 localabstract:webview_devtools_remote_${pid}`);
const pages = await (await fetch('http://127.0.0.1:9224/json')).json();
const page = pages.find((p) => /bar\.html/.test(p.url) && p.type === 'page');
if (!page) { console.error('no page', pages.map((p) => p.url)); process.exit(1); }
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((ok, no) => { ws.onopen = ok; ws.onerror = no; });
let id = 0; const call = (method, params) => new Promise((ok) => { const i = ++id; const h = (e) => { const m = JSON.parse(e.data); if (m.id === i) { ws.removeEventListener('message', h); ok(m.result); } }; ws.addEventListener('message', h); ws.send(JSON.stringify({ id: i, method, params })); });
for (const expr of process.argv.slice(2)) { const r = await call('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true }); console.log(r?.exceptionDetails ? 'ERR ' + JSON.stringify(r.exceptionDetails.exception?.description || r.exceptionDetails.text) : JSON.stringify(r?.result?.value ?? null)); }
ws.close();
