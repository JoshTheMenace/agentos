# AgentOS architecture (contract for all builders)

Agent-native mods for the rooted Moto G 5G (2023), Android 13. Research lives in `research/`; read the report for your area before building.

## The three parts

Think of a restaurant. **agentd** is the waiter: it takes your order, talks to the kitchen (the model) and brings results. The **Android app** is the kitchen door with a bouncer: every real action on the phone goes through it, and it alone decides what's allowed. The **UI** is the dining room.

```
 Launcher WebView / Island / Keyboard / Voice
          │  WebSocket ws://127.0.0.1:8787 (bearer token)
          ▼
      agentd (Node, Termux, non-root)
       ├─ provider router: codex (app-server, stdio) │ claude (API key) │ local (llama-server)
       ├─ Prompt Guard: classifies untrusted screen/message text
       │  HTTP JSON-RPC http://127.0.0.1:7777 (bearer token)
          ▼
 local.agentos Android app  ← the ONLY place actions happen and approvals are granted
   AccessibilityService (read screen, tap/type) · Broker (leases, risk tiers, Rule of Two)
   Approvals (Keystore key + BiometricPrompt signs the exact action) · hash-chained Audit log
   Firewall (root iptables owner-uid rules) · Island/overlays (TYPE_ACCESSIBILITY_OVERLAY)
```

## Rules every builder follows
1. **Models never get a shell, root or adb.** Codex runs as `app-server` with `-c features.shell_tool=false` and a read-only sandbox. Codex's own approval requests are always declined. Phone actions reach it only as `dynamicTools`.
2. **Only the Android app executes actions and grants approvals.** agentd and web UIs can *show* an approval but can't grant one. Outward actions (send, pay, delete, install, network rule changes) need a fingerprint-signed approval bound to the exact action hash.
3. **Untrusted content is labeled.** All screen/message text carries `{source_pkg, trusted:false}`, and Prompt Guard scores it. Untrusted text can't choose recipients or arguments of outward actions without approval (Rule of Two).
4. **Ethics:** the agent may *operate* the user's real apps (e.g. their Messages app) under the broker's rules. *Modifying or inspecting internals* (Frida, traffic inspection) is only for apps we own (`demo-apps/`) or have permission for. Don't use Duolingo in demos.
5. **One device user at a time:** `mkdir /tmp/agentos-device.lock` before any adb command that installs or changes state, `rmdir` after. Read-only adb is fine without the lock. Quote root commands: `adb shell "su -c '...'"`.
6. **Build:** the Android app uses the SDK-free javac + D8 path from `systemui-lab` (see `research/05`). `tooling/android-env.sh` provides the SDK if a component truly needs it.
7. Keep code small and readable. All UI/animation/frontend work is done by Fable agents.

## Interfaces

**Tool server (Android app, :7777)** `POST /rpc {method, params}` with header `Authorization: Bearer <token>`. The token is in `/data/data/local.agentos/files/token`; agentd reads it via `su` once at start.
- `screen.read {}` → `{pkg, activity, secure:bool, nodes:[{id, role, text, desc, bounds, actions[]}], untrusted:true}` (compact format ported from buddy-android `walk()`)
- `tap {id}` · `type {id, text}` · `scroll {id?, dir}` · `press {key: back|home|recents}` · `open_app {pkg}` · `list_apps {}` · `wait {ms}`
- `task.start {goal, apps[]}` → lease `{task_id, apps, expires, max_steps}` · `task.stop {task_id}` · `task.pause/resume`
- `firewall.list {}` · `firewall.set {pkg, mode: allow|block, domains?[]}` (outward tier → approval)
- `audit.tail {n}`
- Every action returns `{ok, decision: auto|approved|denied|blocked, reason?, screen?}`. When it returns `approval_pending`, the app shows the native approval card and the call blocks until the user decides or the request times out (60 s).

**agentd WebSocket (:8787)**, JSON messages:
- client→agentd: `{type:"ask", text, provider?: "codex"|"claude"|"local"}` · `{type:"stop"}` · `{type:"voice.start"|"voice.stop"}` · `{type:"suggest", context}` (keyboard)
- agentd→client: `text.delta` · `tool.request {name,args,tier}` · `tool.result` · `approval.needed {summary, rule_of_two:{untrusted,private,outward}}` · `guard.alert {source_pkg, score, excerpt}` · `turn.done` · `suggestions {items[]}` · `error`

## Repo layout
`android/` app `local.agentos` · `agentd/` Node bridge · `web/` launcher + command-center UI · `demo-apps/` our own test apps · `research/` · `design/` · `pitch/` · `spikes/` · `tooling/`

## Phone facts
Codex 0.163.0 (signed in, ChatGPT Pro) is at `/data/local/tmp/android-agent-lab/`. Run it via `su -c 'sh /data/local/tmp/agentos-codex-env.sh <args>'`; it needs `android-connect-proxy 18080` running (see `research/00-device-baseline.md`). Termux has Node 25, Python 3.12 and Claude Code. Gboard is the current IME. `dev.phoneagent.app` currently holds the ASSISTANT role.
