// Claude adapter: Anthropic Messages API with a manual tool loop. Needs ANTHROPIC_API_KEY (Console key).
import { cfg } from '../config.js';
import { SYSTEM_PROMPT } from '../prompt.js';
import { TOOLS, runTool } from '../tools.js';

const tools = Object.entries(TOOLS).map(([name, t]) => ({ name, description: t.description, input_schema: t.schema }));
let client;

export class Claude {
  id = 'claude';
  available() { return !!process.env.ANTHROPIC_API_KEY; }

  async run(session, text, ctx) {
    if (!this.available()) throw new Error('claude provider needs ANTHROPIC_API_KEY');
    client ??= new (await import('@anthropic-ai/sdk')).default();
    const msgs = (session.claude ??= []);
    msgs.push({ role: 'user', content: text });
    const usage = { input_tokens: 0, output_tokens: 0 };
    for (let step = 0; step < 40; step++) {
      if (ctx.signal.aborted) return { status: 'interrupted', usage };
      const r = await client.beta.messages.create({
        model: cfg.claudeModel, max_tokens: 16000, system: SYSTEM_PROMPT, tools, messages: msgs,
        output_config: { effort: cfg.claudeEffort },
        betas: ['server-side-fallback-2026-07-01'], fallbacks: 'default', // re-run refusals on Anthropic's recommended model
      }, { signal: ctx.signal });
      usage.input_tokens += r.usage.input_tokens; usage.output_tokens += r.usage.output_tokens;
      if (r.stop_reason === 'refusal') { msgs.pop(); return { status: 'failed', error: 'Claude declined this request', usage }; }
      msgs.push({ role: 'assistant', content: r.content }); // keep thinking blocks as-is
      for (const b of r.content) if (b.type === 'text' && b.text) ctx.emit({ type: 'text.delta', text: b.text });
      if (r.stop_reason === 'pause_turn') continue;
      const calls = r.content.filter((b) => b.type === 'tool_use');
      if (!calls.length) return { status: 'completed', usage };
      const results = [];
      for (const c of calls) {
        const out = await runTool(c.name, c.input, ctx);
        results.push({ type: 'tool_result', tool_use_id: c.id, content: out.text, is_error: !out.success });
      }
      msgs.push({ role: 'user', content: results });
    }
    return { status: 'failed', error: 'too many steps', usage };
  }
}
