#!/usr/bin/env node
// When the phone has no Wi-Fi (the hotspot dropped), give root Codex the Mac's internet over USB: a tiny HTTP CONNECT
// proxy here, `adb reverse` onto the phone's 127.0.0.1:18080 (where android-connect-proxy normally listens).
// Only Codex uses that port. Undo: Ctrl-C (or `node bench/usb-net.js off`), then the next app-server start restarts the phone proxy.
//   node bench/usb-net.js        # on (keeps running)
import { execFileSync } from 'node:child_process';
import { createServer } from 'node:http';
import { connect } from 'node:net';
const S = process.env.SERIAL || 'ZY22J3TK8S', PORT = 18080, PHONE = Number(process.env.PHONE_PORT || 18080); // PHONE_PORT=18081: bench Codex only (deploy/codex-bench.sh)
const adb = (...a) => execFileSync('adb', ['-s', S, ...a], { encoding: 'utf8' });
const off = () => { try { adb('reverse', '--remove', `tcp:${PHONE}`); } catch {} };
if (process.argv[2] === 'off') { off(); process.exit(0); }
const srv = createServer((_, res) => res.writeHead(405).end());
srv.on('connect', (req, sock, head) => {
  const [host, port] = req.url.split(':');
  const up = connect(Number(port) || 443, host, () => { sock.write('HTTP/1.1 200 Connection Established\r\n\r\n'); up.write(head); up.pipe(sock); sock.pipe(up); });
  const end = () => { up.destroy(); sock.destroy(); }; up.on('error', end); sock.on('error', end);
});
srv.listen(PORT, '127.0.0.1', () => {
  adb('shell', `su -c "pkill -f '[a]ndroid-connect-proxy ${PHONE}'; true"`); // free the port on the phone
  adb('reverse', `tcp:${PHONE}`, `tcp:${PORT}`);
  console.log(`usb-net on: phone 127.0.0.1:${PHONE} -> Mac`);
});
process.on('SIGINT', () => { off(); process.exit(0); }); process.on('SIGTERM', () => { off(); process.exit(0); });
