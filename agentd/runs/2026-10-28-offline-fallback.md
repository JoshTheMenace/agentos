# Automatic offline fallback (DEMO-WOW #5)

Normal Codex asks and background workers now use Qwen3.5-0.8B when the phone cannot reach the internet. The next online ask uses Codex again; the selected provider toggle never changes. Explicit Local/Claude choices remain explicit.

`providers/router.js` caches parallel HTTPS HEAD probes to chatgpt.com and Google's connectivity endpoint (1 s deadline). Any HTTP response proves internet connectivity, including cloud errors. The online cache lasts 5 s, offline 1 s; a background refresh every 3 s usually keeps online asks off the probe path. Interface changes invalidate the cache. Two endpoints avoid downgrading solely because chatgpt.com is slow.

Native Codex connection errors force a fresh probe. A confirmed disconnect interrupts the cloud turn before starting local. A turn that has emitted an answer or requested a non-read tool is never replayed. Local gets the user's original goal rather than the cloud worker's longer brief/context. The existing broker/guard/approval path still handles every local action.

`local.js` bounds its health check to 700 ms and shares concurrent starts. `llama.sh` sets `oom_score_adj=1000` before exec, so new model threads inherit it; agentd retains its own priority. Confirmed `/proc/<llama-pid>/oom_score_adj` was 1000 during the cold-start test.

UI contract: `turn.started`, streamed events and `turn.done` carry `provider`; workers include it in `task.update` and `task.list`. Fable's label handoff is in `launcher/POLISH-TODO.md`. Voice workers share this router, but GPT Live audio/transcription still requires internet. Photos fail clearly offline.

## Phone verification

Used the live agentd, requesting only battery level. A test file `~/.agentos/force-offline` selected local without changing phone networking. The harness removed it in `finally`, including failed attempts. `AGENTD_FORCE_OFFLINE=1` is also supported for isolated test processes.

| Request | Provider | End to end |
|---|---|---|
| Ordinary `ask`, llama-server stopped first | local | 24.292 s |
| Background worker (same path used by voice delegation) | local | 3.391 s |
| Warm ordinary `ask` | local | 2.271 s |
| Same-chat recovery: flag set | local | 2.948 s |
| Same-chat recovery: flag removed, next ordinary ask | codex | 8.923 s |

Local selection reached the client in 34 ms cold / 11 ms warm; model startup and inference account for the remaining time. Standalone phone reachability: first probe 642 ms, cached checks 1.5–2.1 ms.

The recovery test kept the same WebSocket conversation and never changed the selected provider. Afterwards chatgpt.com was reachable (HTTP 403 is its response to an anonymous HEAD), `/health` returned `ok`, the test flag was absent, and llama-server stayed warm for the demo. Airplane mode and Wi-Fi were never toggled.

`node --test agentd/providers/router-test.js`: 12 passed, covering cached reachability, concurrent probes, interface changes, force-offline, recovery, explicit providers, connection-error interruption, replay prevention, cancellation, and offline photos. `node --check` passed for all changed agentd JS; `sh -n` passed for llama.sh. Code-golf pass removed an unused return and retained all covered behavior. No Prettier.

Deploy the new router module before server.js: a concurrent partial deploy briefly copied the importing server first and broke its startup. Dependencies were installed and the live daemon was restored, then all changed runtime files were deployed together under `scripts/withphone`.
