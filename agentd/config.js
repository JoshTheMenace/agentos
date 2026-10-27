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
  codexModel: env.CODEX_MODEL || null,
  codexEffort: env.CODEX_EFFORT || 'low', // phone control wants fast steps; raise for hard tasks
  codexCwd: env.CODEX_CWD || join(STATE, 'codex-cwd'),
  claudeBin: env.CLAUDE_BIN || 'claude',            // Claude Code CLI (subscription); preferred
  claudeCliModel: env.CLAUDE_CLI_MODEL || null,
  claudeModel: env.CLAUDE_MODEL || 'claude-opus-5-5', // API-key fallback (ANTHROPIC_API_KEY)
  claudeEffort: env.CLAUDE_EFFORT || 'medium',
  localUrl: env.LOCAL_URL || 'http://127.0.0.1:8080/v1/chat/completions',
  debug: !!env.AGENTD_DEBUG,
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
