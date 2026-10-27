// Prompt-injection guard. Interface: classify(text) -> {score 0..1, label: "benign"|"injection"} (may be async).
// This is a cheap regex placeholder; the security track swaps in Prompt Guard 2 behind the same function.
// Each pattern adds its weight; strong tells (0.6) flag alone, weak ones (0.3) need company.
const PATTERNS = [
  [0.6, /\b(ignore|disregard|forget|override)\b.{0,30}\b(previous|prior|above|earlier|all|your)\b.{0,20}\b(instructions?|rules?|prompts?|guidelines?)/i],
  [0.6, /\b(do not|don't)\s+(tell|inform|alert|ask|notify)\s+(the\s+)?(user|owner)\b/i],
  [0.6, /\b(ignore|disregard|bypass)\s+(your|the)\s+(user|owner|safety|rules?)\b/i],
  [0.6, /<\|?(system|im_start|im_end|endoftext)\|?>|\[\/?INST\]|^\s*(system|assistant)\s*:/im],
  [0.3, /\b(ai|assistant|agent|bot)\s*[:,]/i], // text addressed to the agent, e.g. "AI assistant: ..."
  [0.3, /\b(new|updated|real|actual)\s+(instructions?|task|orders?)\s*[:\-]/i],
  [0.3, /\b(system prompt|developer message|you are now|jailbreak|developer mode)\b/i],
  [0.3, /\b(ai|assistant|agent|model|chatgpt|claude|codex)\b.{0,15}\b(must|should|needs? to|is required to)\b/i],
  [0.3, /\b(send|forward|transfer|wire|pay|share|upload)\b.{0,40}\b(password|passcode|otp|2fa|code|token|api key|credentials?|contacts?|money|messages?|texts?|chats?|photos?|files?|location|\$\d)/i],
];

export const THRESHOLD = 0.5;

export function classify(text = '') {
  const score = Math.min(1, PATTERNS.reduce((s, [w, re]) => s + (re.test(text) ? w : 0), 0));
  return { score: Math.round(score * 100) / 100, label: score >= THRESHOLD ? 'injection' : 'benign' };
}
