// Run after bench.mjs is listening through adb forward :18793 and headless Chrome CDP :9393.
// proxy.mjs supplies the phone token. No human microphone or screen interaction.
for (let attempt = 0; ; attempt++) {
  try { if ((await fetch('http://127.0.0.1:18793/health', { signal: AbortSignal.timeout(500) })).ok) break; } catch {}
  if (attempt === 49) throw new Error('Isolated benchmark is not ready');
  await new Promise((r) => setTimeout(r, 200));
}
const tabs = await (await fetch('http://127.0.0.1:9393/json')).json();
const ws = new WebSocket(tabs.find((t) => t.type === 'page').webSocketDebuggerUrl);
let id = 0;
const send = (method, params = {}) => ws.send(JSON.stringify({ id: ++id, method, params }));
ws.onopen = () => {
  send('Runtime.enable');
  send('Page.navigate', { url: `http://127.0.0.1:8794/voice?auto&bench&run=${Date.now()}&wav=/battery.wav#token=bench` });
};
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.error) console.error(JSON.stringify(m.error));
  if (m.method === 'Runtime.consoleAPICalled') console.log(m.params.args.map((a) => a.value ?? a.description).join(' '));
  if (m.method === 'Runtime.exceptionThrown') console.error(m.params.exceptionDetails.text);
};
if (process.env.WARM) {
  setTimeout(() => send('Runtime.evaluate', { expression: "document.getElementById('end').click()" }), 25000);
  setTimeout(() => send('Runtime.evaluate', { expression: "document.getElementById('go').click()" }), 26000);
}
setTimeout(() => { send('Runtime.evaluate', { expression: "document.getElementById('end').click()" }); setTimeout(() => { ws.close(); }, 1000); }, +(process.env.MEASURE_MS || 50000));
