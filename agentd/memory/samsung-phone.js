// Owner CLI receiver: stdin carries only normalized metrics, never raw CSVs. Runs as Termux uid.
import { readSync } from 'node:fs';
import * as S from './store.js';
import { save } from './samsung-health.js';
const [action, scope = 'cloud', bytes = '0'] = process.argv.slice(2), db = S.open();
function payload() {
  if (!Number.isSafeInteger(+bytes) || +bytes < 1 || +bytes > (32 << 20)) throw new Error('Invalid health payload size');
  const buf = Buffer.alloc(+bytes);
  for (let at = 0; at < buf.length;) {
    const n = readSync(0, buf, at, buf.length - at);
    if (!n) throw new Error('Incomplete health payload');
    at += n;
  }
  return JSON.parse(buf.toString('utf8'));
}
try {
  db.exec('PRAGMA busy_timeout=5000');
  const result = action === 'import' ? S.tx(db, () => save(db, payload()))
    : action === 'grant' ? S.grant(db, 'samsung_health', { actor: 'user:cli', scope })
    : action === 'revoke' ? S.revoke(db, 'samsung_health', { actor: 'user:cli' })
    : action === 'status' ? S.active(db, 'samsung_health') || { active: false } : null;
  if (!result) throw new Error('Unknown health CLI action');
  console.log(JSON.stringify(result));
} catch (e) { console.error(e.message); process.exitCode = 1; } finally { db.close(); }
