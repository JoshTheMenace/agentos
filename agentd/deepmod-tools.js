import { open, redact, consentState, ledger } from './memory/store.js';
// Cooperative Pigeon runtime only. Raw payload bytes survive preview -> receipt -> signed commit.
const obj = (properties = {}, required = []) => ({ type: 'object', properties, required, additionalProperties: false });
const method = (op, tier, description, schema = obj()) => ({ method: `app.deepmod.${op}`, tier, description, schema,
  ...(tier !== 'read' && !['remove', 'disable', 'rollback'].includes(op) ? { pkg: 'local.agentos.pigeon' } : {}) });
export const TOOLS = {
  deepmod_style: method('style', 'act', 'Preferred for "theme Pigeon navy and gold and remove the promoted rows": one call inspects, builds a strict signed spec, previews, screenshots, reviews and commits, preserving the current spec. Open Pigeon first. Use palette:"navy-gold", filter_promoted:true. Optional font:"serif"|"sans-serif"|"monospace", scale .85–1.3; hide_decorations:true collapses the registered list decorations. Filtering requires developer-enabled synthetic fixtures. Same reviewer/HUMAN gates as preview/apply; no buttons or user data writes.', obj({ palette: { type: 'string', enum: ['navy-gold'] }, filter_promoted: { type: 'boolean' }, hide_decorations: { type: 'boolean' }, font: { type: 'string', enum: ['sans-serif', 'serif', 'monospace'] }, scale: { type: 'number', minimum: .85, maximum: 1.3 } })),
  deepmod_inspect: method('inspect', 'read', 'Inspect the running owned Pigeon cooperative runtime: exact build, revision, current spec, screen and supported tokens/targets. For theme/font/hide/promoted-row requests in Pigeon use deepmod tools, not overlay mods. Open Pigeon first. Promoted fixtures must be explicitly enabled by the developer; no real chat is an ad.'),
  deepmod_preview: method('preview', 'act', `Preview a signed Pigeon spec on its current screen, take before/after screenshots and pixel diff, verify native structure and restore automatically. Returns a receipt valid 30 seconds. Pass payload as a JSON STRING, max 16 KiB. Use schema:"agentos.deepmod/1", id:"pigeon-theme", package:"local.agentos.pigeon", capabilityVersion:1, baseRevision from deepmod_inspect. Optional theme:{colors:{bg:"#FF081A33",surface:"#FF122A4A",surface2:"#FF1C3555",text:"#FFFFF4D6",text2:"#FFD4C6A5",accent:"#FFD6AD45",onAccent:"#FF081A33"},font:{family:"serif",scale:1}}. Optional views:[{screen:"chat-list",match:{id:"pigeon_overflow"},visibility:"gone"}] (also pigeon_promotion, exact text "Demo promotion"). Optional lists:[{id:"pigeon_threads",exclude:{field:"promoted",equals:true}}] filters synthetic fixtures only. Optional buttons:[{id:"summarize-chat",screen:"chat",slot:"pigeon_chat_actions",label:"Summarize",action:{id:"agent.ask",template:"summarize-visible",context:"current-chat"}}] requires HUMAN approval; no agent approval taps. Update the whole effective spec. Never put code, URLs or arbitrary tasks in it.`, obj({ payload: { type: 'string' } }, ['payload'])),
  deepmod_apply: method('apply', 'act', 'Commit the exact bytes from a successful deepmod_preview receipt within 30 seconds. Broker reviews owner cosmetic requests normally; remote/tainted requests and added buttons need a person. Rechecks build, data, screen, epoch and revision, then screenshots and verifies; failure disables the candidate.', obj({ receipt: { type: 'string' } }, ['receipt'])),
  deepmod_list: method('list', 'read', 'Read Pigeon cooperative mod state (separate from overlay mods).'),
  deepmod_remove: method('remove', 'act', 'Restore built-in Pigeon appearance and all filtered synthetic rows, preserving conversations and composer; always allowed.'),
  deepmod_rollback: method('rollback', 'act', 'Restore the previous successfully committed Pigeon appearance with a fresh higher signed revision; no chat data changes.'),
  deepmod_disable: method('disable', 'act', 'Durable Pigeon deep-mod kill switch: increase epoch, cancel previews and restore defaults. Fresh preview required to reapply.'),
};
export const names = Object.keys(TOOLS);

// Use the existing consent ledger and redaction policy for the native summary's transient screen read.
export function redactSummary(screen) {
  if (screen.pkg !== 'local.agentos.pigeon' || screen.secure) return { ok: false, reason: 'An unprotected Pigeon screen is required' };
  const db = open();
  try {
    const c = consentState(db).find((c) => c.source === 'pigeon');
    if (!ledger(db).ok || !c?.active || c.scope !== 'cloud') return { ok: false, reason: 'Pigeon cloud consent was revoked' };
    let budget = 6000;
    const text = (s) => { const t = String(redact(db, s) || '').slice(0, budget); budget -= t.length; return t; };
    return { ...screen, nodes: (screen.nodes || []).filter((n) => n.src === 'APP:local.agentos.pigeon').slice(0, 120)
      .map((n) => ({ id: n.id, role: n.role, src: n.src, text: text(n.text), desc: text(n.desc), hint: text(n.hint) })) };
  } finally { db.close(); }
}
