// Keyboard image insert ("✨ make an image of a cat in a party hat"): one short-lived `codex exec` with image generation ON.
// agentd's long-lived app-server keeps `features.image_generation=false` (rule: the model's only hands are our tools),
// so this is a separate, tool-less, read-only process per image. Codex saves the PNG under $CODEX_HOME/generated_images/
// <thread_id>/; on the phone that home belongs to the root lab Codex, so the file is read through `su` when needed.
import { spawn, execFileSync } from 'node:child_process';
import { readFileSync, readdirSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { join } from 'node:path';
import { homedir } from 'node:os';
import { cfg, log } from './config.js';

const HOMES = [process.env.CODEX_HOME, join(homedir(), '.codex'), '/data/local/tmp/android-agent-lab/codex-home'].filter(Boolean);

/** Resolves to a PNG Buffer; rejects on failure or abort. onProgress(text) gets the model's interim lines. */
export function generate(prompt, signal, onProgress) {
  return new Promise((resolve, reject) => {
    const text = `Create a picture: ${String(prompt).slice(0, 400)}. You MUST call the image_generation tool once to produce it (no commands, no skills); then reply with the saved path.`;
    const p = spawn(cfg.codexBin, ['exec', '--skip-git-repo-check', '--json', '-c', 'features.image_generation=true', '-c', 'sandbox_mode="read-only"', '-c', 'approval_policy="never"', '-m', cfg.suggestModel, text],
      { cwd: cfg.codexCwd, stdio: ['ignore', 'pipe', 'pipe'] });
    let threadId = '', said = '';
    const fail = (why) => { p.kill(); reject(new Error(why)); };
    const timer = setTimeout(() => fail('image generation timed out'), 180_000);
    signal?.addEventListener('abort', () => fail('stale'), { once: true });
    createInterface({ input: p.stdout }).on('line', (l) => {
      let m; try { m = JSON.parse(l); } catch { return; }
      if (m.type === 'thread.started' && /^[\w-]+$/.test(m.thread_id)) threadId = m.thread_id;
      if (m.item?.type === 'agent_message') { said = m.item.text; onProgress?.(said); }
    });
    p.stderr.on('data', (d) => cfg.debug && log('[imagegen]', String(d).trim()));
    p.on('close', (code) => {
      clearTimeout(timer);
      try {
        const file = said.match(/[\w./-]*\/generated_images\/[\w./-]+\.png/)?.[0] || (threadId && newest(threadId)); // strict: the path goes to `su -c`
        if (!file) return reject(new Error(code ? `codex exited ${code}` : 'no image came back'));
        resolve(read(file));
      } catch (e) { reject(e); }
    });
  });
}

function newest(threadId) {
  for (const h of HOMES) {
    const dir = join(h, 'generated_images', threadId);
    const list = (ls(dir) || []).filter((f) => f.endsWith('.png'));
    if (list.length) return join(dir, list.sort().at(-1));
  }
  return null;
}
const ls = (dir) => { try { return readdirSync(dir); } catch { try { return execFileSync('su', ['-c', `ls ${dir}`]).toString().split('\n').filter(Boolean); } catch { return null; } } };
const read = (f) => { try { return readFileSync(f); } catch { return execFileSync('su', ['-c', `cat ${f}`], { maxBuffer: 64 << 20 }); } }; // root Codex's home
