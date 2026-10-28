#!/usr/bin/env node
// End-to-end test of mcp-remote.js as Grok/ChatGPT would use it: discovery -> DCR -> authorize with the pairing code ->
// PKCE token -> MCP initialize/tools/list/calls -> refresh -> kill switch -> revoke.
//   node remote-test.js http://127.0.0.1:8790 [--task "what app is on screen?"] [--code ABCD-EFGH] [--no-revoke]
// Without --code it makes one itself through owner() (same REMOTE_STATE / AGENTOS_HOME as the agentd under test).
import { createHash, randomBytes } from 'node:crypto';
import { owner } from './mcp-remote.js';

const args = process.argv.slice(2), opt = (k) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : undefined; };
const BASE = (args[0] || 'http://127.0.0.1:8790').replace(/\/$/, ''), TASK = opt('--task');
let pass = 0, fail = 0;
const check = (name, ok, extra = '') => { ok ? pass++ : fail++; console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${extra ? ` · ${extra}` : ''}`); };
const j = async (r) => { const t = await r.text(); try { return JSON.parse(t); } catch { return t; } };

// 1. An unauthenticated MCP call must be 401 with the resource metadata pointer (initialize too).
let r = await fetch(`${BASE}/mcp`, { method: 'POST', headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream' },
  body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 't', version: '1' } } }) });
const wa = r.headers.get('www-authenticate') || '';
check('unauthenticated initialize -> 401 + resource_metadata', r.status === 401 && /resource_metadata="/.test(wa), wa);
const prm = await j(await fetch(wa.match(/resource_metadata="([^"]+)"/)[1]));
check('protected resource metadata', prm.resource?.endsWith('/mcp') && prm.authorization_servers?.length === 1, JSON.stringify(prm.authorization_servers));
const as = await j(await fetch(`${prm.authorization_servers[0]}/.well-known/oauth-authorization-server`));
check('authorization server metadata (S256, DCR)', as.code_challenge_methods_supported?.includes('S256') && !!as.registration_endpoint);

// 2. DCR, then authorize with a wrong code, then the real one.
const redirect = 'https://grok.com/connectors/oauth/callback';
const reg = await j(await fetch(as.registration_endpoint, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ client_name: 'Grok', redirect_uris: [redirect], token_endpoint_auth_method: 'none' }) }));
check('dynamic client registration', !!reg.client_id);
const verifier = randomBytes(32).toString('base64url'), challenge = createHash('sha256').update(verifier).digest('base64url');
const q = { response_type: 'code', client_id: reg.client_id, redirect_uri: redirect, code_challenge: challenge, code_challenge_method: 'S256', state: 'xyz', scope: 'phone', resource: prm.resource };
r = await fetch(`${as.authorization_endpoint}?${new URLSearchParams(q)}`);
check('authorize page asks for the pairing code', r.status === 200 && /Pairing code/.test(await r.text()));
const code = opt('--code') || owner('pair').code;
const post = (c) => fetch(as.authorization_endpoint, { method: 'POST', redirect: 'manual', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ ...q, code: c, from: 'Grok' }) });
r = await post('ZZZZ-ZZZZ');
check('wrong pairing code is refused', r.status === 200 && /Wrong code/.test(await r.text()));
r = await post(code);
const loc = new URL(r.headers.get('location') || 'http://x/');
check('right code -> redirect with code + state', r.status === 302 && loc.searchParams.get('state') === 'xyz' && !!loc.searchParams.get('code'));
r = await post(code);
check('pairing code is single use', r.status === 200 && /expired/.test(await r.text()));

// 3. Token with PKCE (a bad verifier first).
const tok = (body) => fetch(as.token_endpoint, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(body) }).then(j);
const bad = await tok({ grant_type: 'authorization_code', code: loc.searchParams.get('code'), redirect_uri: redirect, client_id: reg.client_id, code_verifier: 'wrong' });
check('bad PKCE verifier is refused (and burns the code)', bad.error === 'invalid_grant');
// The code is single use, so pair again for the real exchange.
const code2 = opt('--code2') || owner('pair').code;
r = await post(code2);
const loc2 = new URL(r.headers.get('location'));
let t = await tok({ grant_type: 'authorization_code', code: loc2.searchParams.get('code'), redirect_uri: redirect, client_id: reg.client_id, code_verifier: verifier });
check('token exchange', !!t.access_token && !!t.refresh_token && t.token_type === 'Bearer');

// 4. MCP over Streamable HTTP with the token.
let id = 0;
const rpc = (method, params, token = t.access_token) => fetch(`${BASE}/mcp`, { method: 'POST', headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', authorization: `Bearer ${token}` },
  body: JSON.stringify({ jsonrpc: '2.0', id: ++id, method, params }) }).then(j);
const init = await rpc('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'test', version: '1' } });
check('initialize', init.result?.serverInfo?.name === 'agentos-phone' && !!init.result.instructions, init.result?.protocolVersion);
const tools = (await rpc('tools/list', {})).result?.tools?.map((x) => x.name) || [];
check('tools/list', ['run_task', 'task_result', 'task_status'].every((x) => tools.includes(x)), tools.join(','));
const st = await rpc('tools/call', { name: 'task_status', arguments: {} });
check('task_status (none yet)', Array.isArray(st.result?.structuredContent?.tasks));
const other = await rpc('tools/call', { name: 'task_result', arguments: { task_id: 'k1', wait_s: 0 } });
check("can't see tasks it didn't start", other.result?.structuredContent?.error === 'no such task');
if (tools.includes('phone_brief')) { const b = await rpc('tools/call', { name: 'phone_brief', arguments: {} }); check('phone_brief', !b.result?.isError, String(b.result?.structuredContent?.untrusted_phone_context || b.result?.content?.[0]?.text).slice(0, 90)); }
if (TASK) {
  const t0 = Date.now(), run = await rpc('tools/call', { name: 'run_task', arguments: { request: TASK } });
  const tid = run.result?.structuredContent?.task_id;
  check('run_task returns a task id at once', !!tid && Date.now() - t0 < 3000, `${tid} in ${Date.now() - t0} ms`);
  let res;
  for (let i = 0; i < 6; i++) {
    res = (await rpc('tools/call', { name: 'task_result', arguments: { task_id: tid, wait_s: 30 } })).result?.structuredContent;
    console.log(`  task_result: ${res?.state} · ${res?.step}${res?.owner_action_needed ? ' · OWNER NEEDED' : ''}`);
    if (res?.done) break;
  }
  check('task finished', res?.done, `${res?.state}: ${String(res?.result || res?.error).slice(0, 160)} (${Math.round((Date.now() - t0) / 1000)} s)`);
}

// 5. Refresh rotates; the old access token keeps working until expiry; a refresh token is not an access token.
const t2 = await tok({ grant_type: 'refresh_token', refresh_token: t.refresh_token, client_id: reg.client_id });
check('refresh', !!t2.access_token && t2.refresh_token !== t.refresh_token);
r = await fetch(`${BASE}/mcp`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${t.refresh_token}` }, body: '{"jsonrpc":"2.0","id":1,"method":"ping"}' });
check('refresh token rejected at /mcp', r.status === 401);
t = t2;

// 6. Static key (for header-only clients), kill switch, revoke.
const key = owner('key', { from: 'dots' }).key;
check('static key works', (await rpc('ping', {}, key)).result !== undefined);
owner('enable', { on: false });
await new Promise((ok) => setTimeout(ok, 300));
const off = await rpc('tools/list', {});
check('kill switch: calls refused', off.error?.code === -32001, off.error?.message);
owner('enable', { on: true });
check('back on', (await rpc('ping', {})).result !== undefined);
if (!args.includes('--no-revoke')) {
  owner('revoke');
  await new Promise((ok) => setTimeout(ok, 300));
  r = await fetch(`${BASE}/mcp`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${t.access_token}` }, body: '{"jsonrpc":"2.0","id":1,"method":"ping"}' });
  check('revoke: old token -> 401', r.status === 401);
  r = await fetch(`${BASE}/mcp`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${key}` }, body: '{"jsonrpc":"2.0","id":1,"method":"ping"}' });
  check('revoke: static key -> 401', r.status === 401);
}
console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
