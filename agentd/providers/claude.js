// Claude adapter: Anthropic Messages API with a manual tool loop. Needs ANTHROPIC_API_KEY (Console key).
import { cfg } from '../config.js';
import { systemPrompt } from '../prompt.js';
import { TOOLS, runTool, toolset } from '../tools.js';
import { BY_ID } from '../plugins.js';

// Tools in a fixed order (core, loaded plugins, then deferred ones) so a combination always hits the same cache entry.
// Deferred tools stay out of the prompt until Claude's server-side tool search finds them (same response, no extra request).
const tool = (name, defer) => ({ name, description: TOOLS[name].description, input_schema: TOOLS[name].schema, ...(defer && { defer_loading: true }) });
let client;

export class Claude {
  id = 'claude';
  available() { return !!process.env.ANTHROPIC_API_KEY; }

  async run(session, text, ctx) {
    if (!this.available()) throw new Error('claude provider needs ANTHROPIC_API_KEY');
    client ??= new (await import('@anthropic-ai/sdk')).default();
    const msgs = (session.claude ??= []);
    msgs.push({ role: 'user', content: text });
    const ts = await toolset(ctx.turn.goal), deferred = ts.deferred.flatMap((id) => BY_ID[id].tools);
    const tools = [...ts.names.map((n) => tool(n)), ...deferred.map((n) => tool(n, true)), ...(deferred.length ? [{ type: 'tool_search_tool_bm25_20251119', name: 'tool_search_tool_bm25' }] : [])];
    const system = systemPrompt({ ...ts, native: true });
    const usage = { input_tokens: 0, output_tokens: 0, cache_read_input_tokens: 0, calls: 0, plugins: { mode: cfg.tools, loaded: ts.loaded, deferred: ts.deferred } };
    for (let step = 0; step < 40; step++) {
      if (ctx.signal.aborted) return { status: 'interrupted', usage };
      const r = await client.beta.messages.create({
        model: cfg.claudeModel, max_tokens: 16000, system, tools, messages: msgs,
        cache_control: { type: 'ephemeral' }, // automatic prompt caching: the tools + system prefix is re-read at 0.1x on every step
        output_config: { effort: cfg.claudeEffort },
        betas: ['server-side-fallback-2026-07-01'], fallbacks: 'default', // re-run refusals on Anthropic's recommended model
      }, { signal: ctx.signal });
      usage.input_tokens += r.usage.input_tokens; usage.output_tokens += r.usage.output_tokens; usage.cache_read_input_tokens += r.usage.cache_read_input_tokens || 0; usage.calls++;
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
