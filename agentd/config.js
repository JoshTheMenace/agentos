// Shared settings. Everything is an env var so the same code runs on the Mac and in Termux.
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';

const env = process.env;
const STATE = env.AGENTOS_HOME || join(homedir(), '.agentos');
const onPhone = process.platform === 'android';

export const cfg = {
  host: env.AGENTD_HOST || '127.0.0.1',
  port: Number(env.AGENTD_PORT || 8787),
  tokenFile: env.AGENTD_TOKEN_FILE || join(STATE, 'agentd.token'),
  phoneUrl: env.PHONE_URL || 'http://127.0.0.1:7777/rpc',
  // On the phone the Android app owns the token; on the Mac mock-phone.js writes this file.
  phoneTokenFile: env.PHONE_TOKEN_FILE || (onPhone ? '/data/data/local.agentos/files/token' : join(STATE, 'phone.token')),
  provider: env.AGENTD_PROVIDER || 'codex',
  codexBin: env.CODEX_BIN || 'codex',
  codexModel: env.CODEX_MODEL || 'gpt-6.1-terra', // fastest at phone control in bench/RESULTS.md (vs gpt-6.1-sol, gpt-6-luna)
  codexEffort: env.CODEX_EFFORT || 'low', // phone control wants fast steps; raise for hard tasks
  codexTier: env.CODEX_SERVICE_TIER ?? 'priority', // 'priority' = Codex "Fast" mode (1.5x on terra, more quota); '' = standard (bench/RESULTS.md round 2)
  codexFinish: (env.CODEX_FINISH ?? '1') === '1', // finishing tools take a `done` reply and end the turn on success (no extra model call)
  promptPatch: env.AGENTD_PROMPT_PATCH || '', // bench/climb.js only: base64 JSON {replace:[[from,to]], append, tools:{name:description}}
  screenFormat: env.AGENTD_SCREEN || 'compact', // what the model sees of a screen: 'compact' lines or the raw 'json' nodes (bench/RESULTS.md)
  suggestModel: env.SUGGEST_MODEL || 'gpt-6-luna', // keyboard suggestions: small and fast, no tools
  suggestEffort: env.SUGGEST_EFFORT || 'low',
  suggestProvider: env.SUGGEST_PROVIDER || 'codex', // 'local' = llama-server for mode "reply" (fix/rewrite stay on Codex)
  reviewModel: env.REVIEW_MODEL || 'codex-auto-review', // approval reviewer (what Codex's own Auto-review uses)
  reviewMs: Number(env.REVIEW_MS || 4000),             // slower than this -> the human decides
  codexCwd: env.CODEX_CWD || join(STATE, 'codex-cwd'),
  claudeBin: env.CLAUDE_BIN || 'claude',            // Claude Code CLI (subscription); preferred
  claudeCliModel: env.CLAUDE_CLI_MODEL || null,
  claudeModel: env.CLAUDE_MODEL || 'claude-opus-5-5', // API-key fallback (ANTHROPIC_API_KEY)
  claudeEffort: env.CLAUDE_EFFORT || 'medium',
  localUrl: env.LOCAL_URL || 'http://127.0.0.1:8081', // llama-server (deploy/local-model)
  debug: !!env.AGENTD_DEBUG,
  clientPkgs: (env.AGENTD_CLIENT_PKGS ?? 'local.agentos,local.agentos.keys,local.agentos.home').split(',').filter(Boolean), // apps that get the WS token

};

// Read a token file; if it is missing and `create` is set, make a new 256-bit token (mode 600).
export function fileToken(path, create = false) {
  if (existsSync(path)) return readFileSync(path, 'utf8').trim();
  if (!create) return null;
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
  const t = randomBytes(32).toString('hex');
  writeFileSync(path, t + '\n', { mode: 0o600 });
  chmodSync(path, 0o600);
  return t;
}

export const agentdToken = () => env.AGENTD_TOKEN || fileToken(cfg.tokenFile, true);

// The Android app's token lives in its private dir, so in Termux we read it once through su.
export function phoneToken() {
  if (env.PHONE_TOKEN) return env.PHONE_TOKEN;
  try { const t = fileToken(cfg.phoneTokenFile); if (t) return t; } catch {}
  try { return execFileSync('su', ['-c', `cat ${cfg.phoneTokenFile}`], { encoding: 'utf8', timeout: 5000, stdio: ['ignore', 'pipe', 'ignore'] }).trim() || null; } catch { return null; }
}

export const log = (...a) => console.error(new Date().toISOString().slice(11, 23), ...a);

// Give each allowed client app (launcher, keyboard) its own copy of the agentd token in ITS private dir:
// /data/data/<pkg>/files/agentd.token, owned by the app's uid, mode 600, with the dir's SELinux label. Only that app
// (and root) can read it. The token goes in on stdin, never argv. Apps that aren't installed are skipped.
export function provisionClients(token) {
  for (const pkg of cfg.clientPkgs) {
    if (!/^[\w.]+$/.test(pkg)) continue;
    const d = `/data/data/${pkg}`, f = `${d}/files/agentd.token`;
    const sh = `[ -d ${d} ] || exit 3; u=$(stat -c %u ${d}); mkdir -p ${d}/files; cat > ${f}; chown $u:$u ${d}/files ${f}; chmod 600 ${f}; chcon $(stat -c %C ${d}) ${d}/files ${f}`;
    try { execFileSync('su', ['-c', sh], { input: token + '\n', timeout: 5000, stdio: ['pipe', 'ignore', 'ignore'] }); log(`agentd token -> ${pkg}`); }
    catch (e) { if (e.status !== 3) log(`could not give the agentd token to ${pkg}: ${e.message.split('\n')[0]}`); }
  }
}
