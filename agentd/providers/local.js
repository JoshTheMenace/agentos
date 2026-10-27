// Local adapter (stub): an OpenAI-compatible llama-server (`llama-server --jinja`) with tool calls.
// Meant for small, private jobs (routing, redaction, rewrites); kept minimal on purpose.
import { cfg } from '../config.js';
import { SYSTEM_PROMPT } from '../prompt.js';
import { TOOLS, runTool } from '../tools.js';

const tools = Object.entries(TOOLS).map(([name, t]) => ({ type: 'function', function: { name, description: t.description, parameters: t.schema } }));

export class Local {
  id = 'local';
  available() { return true; } // checked lazily: a dead server shows up as an error on the first ask

  async run(session, text, ctx) {
    const msgs = (session.local ??= [{ role: 'system', content: SYSTEM_PROMPT }]);
    msgs.push({ role: 'user', content: text });
    for (let step = 0; step < 15; step++) {
      const res = await fetch(cfg.localUrl, { method: 'POST', signal: ctx.signal, headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ messages: msgs, tools }) });
      if (!res.ok) throw new Error(`local model ${res.status}`);
      const { choices: [{ message: m }], usage } = await res.json();
      msgs.push(m);
      if (m.content) ctx.emit({ type: 'text.delta', text: m.content });
      if (!m.tool_calls?.length) return { status: 'completed', usage };
      for (const c of m.tool_calls) {
        let args = {}; try { args = JSON.parse(c.function.arguments || '{}'); } catch {}
        msgs.push({ role: 'tool', tool_call_id: c.id, content: (await runTool(c.function.name, args, ctx)).text });
      }
    }
    return { status: 'failed', error: 'too many steps' };
  }
}
