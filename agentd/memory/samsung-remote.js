// Use the existing adb/Termux transport and shared phone lock; never copy/replace the live memory.db.
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { normalize } from './samsung-health.js';
export function remote(action, { folder, scope = 'cloud' } = {}) {
  if (!['import', 'grant', 'revoke', 'status'].includes(action) || !['cloud', 'local'].includes(scope)) throw new Error('Invalid health action/scope');
  const prefix = '/data/data/com.termux/files', home = `${prefix}/home`, bin = `${prefix}/usr/bin`;
  const command = (op, bytes) => `su 10401 -g 10401 -G 3003 -c 'export HOME=${home} PREFIX=${prefix}/usr PATH=${bin} LD_PRELOAD=${prefix}/usr/lib/libtermux-exec.so; cd ${home}/agentos/agentd; exec ${bin}/node --no-warnings memory/samsung-phone.js ${op} ${scope} ${bytes}'`;
  const invoke = (op, input) => {
    const r = spawnSync(fileURLToPath(new URL('../../scripts/withphone', import.meta.url)),
      ['adb', '-s', process.env.ANDROID_SERIAL || process.env.SERIAL || 'ZY22J3TK8S', 'shell', '-T', command(op, Buffer.byteLength(input || ''))],
      { input, encoding: 'utf8', maxBuffer: 32 << 20, timeout: 180000 });
    if (r.error || r.status !== 0) throw new Error(r.error?.message || r.stderr.trim() || r.stdout.trim() || 'Phone health command failed');
    return JSON.parse(r.stdout);
  };
  // Check consent before reading the founder's folder. The receiver checks again inside its write transaction.
  if (action === 'import' && !invoke('status').active) throw new Error('Grant first: node agentd/memory/cli.js consent grant samsung_health');
  return invoke(action, action === 'import' ? JSON.stringify(normalize(folder)) : undefined);
}
