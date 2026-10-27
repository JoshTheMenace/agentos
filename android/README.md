# android/ — `local.agentos`, the bouncer

This app is the only place where AgentOS acts on the phone or grants approvals (see `../ARCHITECTURE.md`).
Picture a bank teller. The model passes notes ("tap element 7"), and the teller checks each note against the rules.
It asks for your fingerprint when money moves and writes everything in a ledger.

## Files (`src/local/agentos/`)

| File | Job |
|---|---|
| `AgentA11y` | AccessibilityService. Hosts everything below and starts the tool server. Holding volume-down for 1 s stops the active task. |
| `Screen` | `screen.read`: compact node list (port of Buddy's `walk()`), `src` provenance label per node, FLAG_SECURE via root `dumpsys window`, password detection, redaction |
| `Actions` | tap / type / scroll / press / open_app / list_apps (the "hands"; no policy) |
| `Broker` | Leases, tiers T0–T4, Rule of Two per action, P3 secret block, pause/stop, approval wait, audit |
| `Guard` | Risky-button regex, private-app list, forbidden packages, `offLimits` (port of Buddy's `Guard`) |
| `PromptGuard` + `Pg2Tokenizer` | On-device Llama Prompt Guard 2 (22M) through ONNX Runtime: scores untrusted screen text for prompt injection (see "Prompt Guard" below) |
| `Approvals` + `ApproveActivity` | Keystore EC key (auth per use, BIOMETRIC_STRONG) + transparent Activity hosting `BiometricPrompt` with a CryptoObject |
| `ApprovalCard` | The native card (TYPE_ACCESSIBILITY_OVERLAY). All view code is here, with style hooks for the designer. |
| `AutoPause` | 1-px outside-touch watcher: a human touch pauses the task (port of Buddy's `AutoPause`) |
| `Audit` | Hash-chained `files/audit.jsonl` |
| `Firewall` | Root iptables/ip6tables `--uid-owner` rules in chain `AGENTOS_FW` |
| `Server` | `POST /rpc` on 127.0.0.1:7777, bearer token in `files/token` |
| `Root` | `su -c` helper (Magisk) |

`Guard`, `Screen` (walk), `AutoPause` and `res/xml/a11y.xml` contain code ported from
[buddy-android](https://github.com/ghorbelhamdi/buddy-android) under Apache-2.0. Each file's header keeps the notice and lists our changes.

## Build, install, test

```sh
android/build.sh                 # javac + D8 + aapt2, no Gradle; SDK from tooling/android-env.sh -> android/build/agentos.apk
android/install.sh               # takes /tmp/agentos-device.lock; install -r, Magisk root grant, appends the a11y service
python3 android/tools-client.py call screen.read
python3 android/tools-client.py call task.start '{"goal":"demo","apps":["local.agentos.pigeon"]}'
python3 android/tools-client.py e2e          # full test on our Pigeon + Beacon apps; asks you for fingerprints
python3 android/tools-client.py audit 20     # tail + independent chain check of the whole file on the Mac
```
`install.sh` does the following:
- `adb install -r`;
- `magisk --sqlite "REPLACE INTO policies ..."` gives the app's uid root (used only for `dumpsys window` and iptables);
- `appops ... ACCESS_RESTRICTED_SETTINGS allow` and the deviceidle whitelist;
- stops the old prototype's `local.agentos.uilab/.Tools`, which also binds :7777;
- appends `local.agentos/local.agentos.AgentA11y` to `enabled_accessibility_services` without removing anything.

The client forwards Mac port **17777** (`AGENTOS_PORT`) to the phone's 7777, because something else on the Mac already uses 7777.
It reads the token with `su` and never prints it.

## Interface (as built)

`POST /rpc {"method": "...", "params": {...}}` with `Authorization: Bearer <token>`. The reply is the result object itself.
It always has `ok`; errors are `{ok:false, error}`.

| Method | Params | Tier | Notes |
|---|---|---|---|
| `screen.read` | – | T0 | `{pkg, activity, secure, secure_reason, nodes:[{id, role, text?, desc?, hint?, password?, bounds:[l,t,r,b], actions[], risk?:"T3", src:"APP:<pkg>"\|"SYSTEM"}], truncated, source_pkg, untrusted:true}`. Ids are valid until the next read. Our own windows and the keyboard are skipped. Each `APP:*` node with text also gets `guard` (its Prompt Guard score), and the reply has `guard: {score, label, flagged_node_ids, screen_score, scored_nodes, ms}` (see below). |
| `guard.classify` | `{text}` | T0 | `{score, label: BENIGN\|MALICIOUS, cached, ms, model}`. Long text is split into 510-token windows; the max wins. |
| `list_apps` / `wait {ms≤5000}` | | T0 | `wait` returns `screen` |
| `task.start` | `{goal, apps[], ttl_s?=600 (max 3600), max_steps?=40 (max 200)}` | – | → `{task_id, apps, expires (epoch ms), max_steps, refused_apps}`. Forbidden apps are refused. A new start replaces the old task. |
| `task.stop {task_id?}` / `task.pause` / `task.resume` | | – | Stop also denies an open approval |
| `tap {id}` | | T1, or T3 if the label is risky or the screen is secure | |
| `type {id, text, submit?}` | | T2; T3 with `submit`; T4 on a password field or a secure screen | |
| `scroll {id?, dir}` | dir = up/down/left/right | T1 | `press {key: back\|home\|recents}` T1, `open_app {pkg}` T1 |
| `firewall.list` | | T0 | `{rules:[{pkg, uid, family, dest, action}]}` |
| `firewall.set {pkg, mode, domains?[]}` | | T3 | `block` alone = cut the app off; `block`+domains = block those IPs; `allow` alone = remove its rules; `allow`+domains = only those. |
| `firewall.clear` | | T3 | Flushes `AGENTOS_FW`: undoes every rule we added |
| `audit.tail {n}` | | T0 | `{entries, count, chain_ok, broken_at, head, pubkey}` |

Every action returns `{ok, decision: auto|approved|denied|blocked, reason, tier, rule_of_two:{untrusted, private, outward}, screen?}`.
Differences from ARCHITECTURE.md:
- There is no `approval_pending` reply. The call simply **blocks** while the card is up: 60 s by default, or `approval_timeout_s` (10–120) on any action. Use a client timeout 15 s longer than that.
- Extra fields: `secure_reason`, `src`, `risk`, `submit`, `firewall.clear`, `guard`, `guard.classify`.

## Policy, in one screen

- **Lease:** every action except `firewall.*` needs an active task. The target app must be in the lease, the lease must not have expired or reached `max_steps`, and the task must not be paused.
- **T4 (always blocked):**
  - packages: Settings, SystemUI, Magisk, the permission controller, the package installer, and AgentOS itself;
  - `offLimits` nodes (our windows);
  - password fields, typing on secure screens, and a locked phone;
  - **P3:** typing text that contains a code read from the screen in this task (a canary `AGENTOS-CANARY-*`, a 6–8 digit OTP, or a 13–19 digit card number).
- **Rule of Two (per action):**
  - *untrusted*: this task has read the screen;
  - *private*: a messaging, mail, social, finance or store app (Buddy's list), or a secure screen;
  - *outward*: T3.
  - Every T3 action needs a fingerprint. A T2 (type) needs one when *untrusted* and *private* are both true.
- **Approval:** the card shows tool, app, exact target label, exact text, task, tier, reason and the three chips.
  1. **Approve** opens `ApproveActivity`. A fingerprint unlocks the Keystore key, which signs the canonical action JSON `{"tool","pkg","target","text","task_id","nonce"}` (in that order) with `SHA256withECDSA`, i.e. ECDSA over SHA-256 of those bytes.
  2. The broker verifies the signature against `files/approval.pub`.
  3. It re-reads the target node and refuses if the label or package changed.
  4. Then it acts.
  - The audit line stores `canonical`, `sig` (DER, base64) and `pubkey_sha256`, so an approval can be checked offline: `openssl dgst -sha256 -verify pub.pem -signature sig.der canon.txt`.
- **Card hardening:**
  - touches flagged as accessibility-injected (`0x800`) are swallowed;
  - `setFilterTouchesWhenObscured`;
  - only one action runs at a time, so nothing else can act while the card is up;
  - the card hides itself before the fingerprint prompt so it can't cover it.
- **Pause and stop:** a human touch auto-pauses the task (resume with `task.resume`). Stop works through `task.stop` or by holding volume-down for 1 s.

### Firewall reversal
All rules live in one chain, so undoing them is simple:
- `firewall.clear` (needs a fingerprint);
- or, as root: `iptables -w -F AGENTOS_FW; ip6tables -w -F AGENTOS_FW`;
- to remove the chain completely: `for t in iptables ip6tables; do $t -w -D OUTPUT -j AGENTOS_FW; $t -w -F AGENTOS_FW; $t -w -X AGENTOS_FW; done`.

Loopback is never filtered (`-o lo -j RETURN`), so agentd can always reach :7777.

## What works / known limits

### Device results (Moto G 5G 2023, 2026-10-26, four `e2e` runs)

**Verified on the phone:**
- Install: Magisk root grant, the a11y service appended, the server on **127.0.0.1**:7777 (`curl` from adb shell gets 401 without a token).
- `screen.read`: about 0.9 s cold, including root `dumpsys`. It returns pkg, activity, `secure`, and nodes with `src`.
- Policy:
  - acting with no task → blocked;
  - Settings → refused from the lease;
  - a Pigeon contact tap → auto (T1);
  - typing the login code it had just read → **blocked (P3, T4)**;
  - a benign draft → auto (T2);
  - after `task.stop`, actions are blocked.
- A T3 "Send" tap shows the native card with the exact action and the Rule-of-Two chips (untrusted ●, private ○, outward ●).
- Approve launches the transparent `ApproveActivity`, and the system **BiometricPrompt** takes focus. The Android 13 background-start rules didn't block it.
- A fingerprint unlocked the auth-per-use key for `local.agentos` (`dumpsys fingerprint`: `acceptCrypto` 0 → 1).
- Timeouts deny and close the prompt.
- `firewall.list` and chain setup work, including the `-o lo` RETURN rule. The `--uid-owner` REJECT rule strings were applied and listed correctly as root (tested by hand on Beacon, then removed).
- `audit.tail` reports `chain_ok`, and the Mac independently re-verifies the whole file.

**Not yet seen end to end:** a full *approved* action (fingerprint → signature verified → act).
- The one accepted fingerprint landed on a prompt whose request had already timed out (a `singleTask` bug, now fixed). The broker correctly ignored that signature.
- Later prompts (run 4: two 90 s windows, each confirmed on screen for ~88 s, then cancelled) got no touch; `dumpsys fingerprint` acquire counters did not move.
- `firewall.set`/`firewall.clear` through the API have therefore only been exercised up to the approval.
- To finish, run `python3 android/tools-client.py e2e --press-approve` while someone is at the phone and touches the sensor when each prompt appears.

Known limits:
- `task.start` is not approved by the human. Any token holder can open a lease (Settings/SystemUI/Magisk/AgentOS are still refused).
- The BiometricPrompt text is drawn by our app, not by secure hardware. A compromised *app process* could show one thing and sign another. Android Protected Confirmation isn't used.
- Root (the referee) is above all of this. The signatures make tampering evident; they can't prevent it.
- Domain firewall rules resolve to IPs once, at set time, so they miss CDNs and IP changes. The SNI string match from `research/04` 3c isn't implemented.
- A fingerprint prompt closes when its request ends (timeout, Deny, Stop). A late signature for a finished request is ignored.
- If root is unavailable, FLAG_SECURE can't be checked, so every screen counts as secure: typing is blocked and taps need approval.
- Taint is coarse. Any `screen.read` during a task marks it untrusted (honest for a phone agent, which is almost always A+B+C).
- The provenance index and P1/P2 copy checks from `research/04` 3d aren't built. Only P3 (secrets) and P4 (tainted + outward) are.
- Settle detection waits for the first accessibility event, then 300 ms of quiet (max 2 s). Slow apps may need an extra `wait`.
