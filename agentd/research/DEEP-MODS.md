# Deep mods for apps we own

Research/design, 2026-10-28. Judging is today at 5 PM; code freeze is noon. This task made no device changes and ran no adb commands. All APIs, specs, timings and tests proposed below are unimplemented unless explicitly identified as existing evidence.

## Recommendation

Build a small cooperative patch runtime into **Pigeon and Beacon only**. The AI writes JSON; the Android broker validates, reviews and signs it; the target app changes its actual Java Views and render-time list projection. This delivers navy/gold themes, alternate fonts, collapsed hidden views, filtered rows and a native Summarize button without Zygisk, Frida, a reboot or executable model output. It needs one source-built APK update per app, then requests apply at runtime without rebuilding.

Keep the existing L0/L1 overlay mods intact. Use a separate `app.deepmod.*` namespace so an overlay mask cannot be mistaken for true row removal. Frida is an optional, bounded experiment after the cooperative version works. Defer LSPosed installation until after judging; even the last overnight slot does not make a boot-framework change necessary for these fixtures.

This follows [ARCHITECTURE.md](../../ARCHITECTURE.md), rule 4, and the S2/S3 split in NIGHT-PLAN.md as read for this task: code-level inspection/injection targets only `local.agentos.pigeon` and `local.agentos.beacon`. Ownership of the phone does not authorize inspection of other apps. STATUS.md says Pigeon/Beacon stay test fixtures rather than stage apps, so this is a capability proof; it does not expand the live demo to third-party internals. The delivered overlay engine and its measured results remain documented separately in [APP-MODS.md](APP-MODS.md).

## What the source actually supports

| Source | Finding | Consequence |
| --- | --- | --- |
| [Pigeon Main.java](../../demo-apps/pigeon/src/local/agentos/pigeon/Main.java) | `showList()` builds sorted chat rows in a LinearLayout inside a ScrollView; `showChat()`, `header()`, `row()`, `bubble()`, `text()` and `color()` construct the UI. Messages persist in `files/chats.json`. | Apply theme tokens at construction, filter the sorted projection before `items.addView(row(c))`, and add a native button to a registered header slot. Preserve message data and drafts. |
| [Beacon Main.java](../../demo-apps/beacon/src/local/agentos/beacon/Main.java) | `onCreate()` builds Views; `done()` appends request-log rows and caps visible children at 40; live callbacks change weather/error colors. Notes live in preferences. | Theme both initial and later rendering. Keep a bounded backing log if filtering must restore hidden entries. Do not change networking, heartbeat counters or notes. |
| Both apps | No application `setId()` calls, semantic mod registry or promoted-row data today. Neither uses RecyclerView. | Add resource IDs and a small explicit capability registry. Add clearly named synthetic promoted fixtures; don't claim filtering already exists. Never infer that a chat from an unknown sender is an ad. |
| [Build script](../../demo-apps/build.sh) | SDK-free aapt2/javac/D8 APKs; Java 8 source; demo signing key is separate from the broker's build process. | Share Java source through the build input, no new Gradle stack. Verify actual signing certificates before updates; don't assume signature permissions work between these APKs. |
| Existing mods / agentd | `mod_preview/app.mod.preview`, apply/remove/disable, audit and physical volume-down kill exist. `ask` starts ordinary agent work; there is no dedicated Summarize mod RPC. | Reuse policy and task infrastructure, add a narrow broker action bridge. Do not hand demo apps the agentd bearer token or add them to the broad `AGENTD_CLIENT_PKGS` list. |

Phone facts come from STATUS.md and [the earlier baseline](../../research/00-device-baseline.md): Android 13 arm64, Magisk 30.6, Zygisk off, LSPosed absent, Frida 17.24.3 present. The baseline's old “no modules” line is stale: STATUS.md records `agentos-no-ota` and the agentd service script. Inventory again before any later install; do not remove existing modules or service scripts.

## Engine comparison

These are design judgments, not new phone measurements. All three can modify actual Views in these owned apps. Fidelity depends on correct app lifecycle and data integration, not merely successful attachment.

| Criterion | Frida 17.24.3 | LSPosed / Zygisk | Cooperative signed spec |
| --- | --- | --- | --- |
| Fidelity | Real color/font/visibility and child insertion. List filtering needs app-specific render/data hooks; one-time hiding returns when the app rebuilds. | Real View and method changes, including render/data hooks before screen construction. Still needs app-specific adapters. | Full fidelity for the advertised operations: token-aware construction, `GONE`, filtered data projection and actual Button children. Limited deliberately to the app's capability registry. |
| Stability | Must manage class loading, main thread, Activity lifetime and process restarts. Reflection-only work cannot establish hook stability. | Early, persistent hooks survive launches, but framework/ART/module compatibility and scope add dependencies. | Public Android APIs and direct source call sites; no ART offsets or injection. Deterministic handling of new rows and recreated screens. |
| Latency | Attached changes can reach the next layout; attachment/bridge startup adds overhead. Reattach after process death. | No per-launch host attach; synchronous hooks add work to the app's render path. Framework setup needs reboot. | Local validation/IPC plus next layout, with no attach. Aim for <100 ms mutation-to-frame p50, <250 ms p95 on small screens; measure. |
| Crash / ANR risk | Java errors, JNI/native crashes, stale wrappers and blocking callbacks. Frida documents ROM-specific system crashes; target scoping does not guarantee device isolation. | App Java/native failures plus zygote/framework startup failure and bootloop risk. Package scope narrows module hooks, not the framework's boot footprint. | Parser/renderer bugs can crash the fixture or cause an ANR. Bounded input, off-main parsing and transactional rendering reduce this; no added boot component. |
| Rollback | Restore properties/remove children before unload; unload alone does not undo View mutations. Disable reattach, then force-stop/relaunch only the fixture if necessary. | Disable mod payload immediately; restart scoped app to shed hooks. Framework uninstall/disable or Zygisk change normally requires reboot. | Disable flag/epoch, restore prior spec or built-in defaults, rerender. No reboot; preserve chat/notes files. |
| Safety controls | Developer-owned fixed bundle; exact package/certificate/version/PID gate; attach only to running fixture; loopback/USB access; no model access to server, JS, root or generic RPC. | Exact two-package scope and process checks; no framework/SystemUI/agentd/Termux hooks; signed bounded specs; pretested recovery. No model install/scope controls. | Signed data plus authenticated IPC, capability/version allowlists, bounds, fresh previews, persistent disable and independent app-side timeout. No script/dex/class/method/URL input. |
| Setup / power | Server exists, but correct client/core/bridge pairing and own-app tests remain. No reboot needed for attach. Avoid heap scans/polling and stop experiment sessions afterward. | Enable Zygisk, install a verified compatible framework, reboot, install/configure own module and restart fixture. At least one reboot; allow another for isolation/rollback. | Build/install two apps and broker bridge once; no root requirement in the runtime. Foreground-only callbacks and a short liveness heartbeat. |

The Frida setup and crash caveat are documented in [Frida's Android guide](https://frida.re/docs/android/). Main-thread scheduling and Java method replacement are separate APIs in [Frida's JavaScript reference](https://frida.re/docs/javascript-api/). LSPosed's [upstream instructions](https://github.com/LSPosed/LSPosed#install) list Android 8.1–14, Magisk 24+ and reboot; those broad requirements do **not** verify this Moto/Magisk 30.6 combination. The former JingMatrix LSPosed URL now redirects to [Vector](https://github.com/JingMatrix/Vector), whose own instructions also require Zygisk and reboot. Pin an exact project/release/hash after compatibility review, not a random “latest LSPosed” ZIP. Neither framework was installed or tested here.

### Frida 17 and the earlier reflection-only bridge

The earlier scratch workspace contains `debug-button/bridge-reflection.patch` at `/Users/joshthemenace/.t3/scratch/2026-10-21-i-have-an-android-phone-178d4212/`. I inspected only its bridge patch, not third-party app internals. It introduces `FRIDA_JAVA_BRIDGE_REFLECTION_ONLY`, skips JVMTI/ART class-model setup in `compileModule()` and uses `nullUnwrap` instead of ART handle unwrapping. This is a local bridge modification, not evidence of an upstream supported safety mode or successful lifecycle hooks in Pigeon/Beacon.

Treat it as a compatibility lead for class/member lookup and calls. Do not infer that `.implementation`, `Java.choose`, listener generation or ART hook installation works because reflection works. A reflection-only one-shot View edit can demonstrate a change but does not reliably survive `showList()`, chat navigation or Beacon's later `done()` callbacks. No raw ART offsets, hidden Activity enumeration or global framework hooks are needed for the recommended design.

Since Frida 17, custom agents must explicitly import/bundle `frida-java-bridge`; the frida-tools REPL and frida-trace include bridges. A server install alone does not provide the custom agent's `Java` global. Pin the bridge package/source hash separately from core/server 17.24.3, including the reflection patch if evaluated. Do not accidentally use the baseline's older 17.2.14 copy. See [Frida 17 release notes](https://frida.re/news/2025/05/17/frida-17-0-0-released/) and [bridge packaging](https://frida.re/docs/bridges/).

An optional Frida spike should attach to an already running owned fixture, perform one bounded main-thread change, restore it, unload, and confirm restart recovery. Lifecycle-hook experiments are a separate gate. No spawn gating, system-server attachment, untrusted scripts or automatic reinjection loop. If the bridge cannot pass this on fixtures, stop; don't change ART, SELinux or Magisk to rescue it tonight.

## Proposed declarative contract

`app.deepmod.inspect {package}` returns the pinned app build/certificate, current screen/revision and **registered** theme tokens, view IDs, list fields, insertion slots and actions. Return UI text as `{source_pkg, trusted:false}`. Registry capabilities are developer-authored; screen text cannot create capabilities.

The AI emits the following payload. IDs/fields here are **proposed additions**, not existing resources. This example applies to a future mod-enabled Pigeon build with a synthetic promoted-row fixture. The real installed build digest comes from inspect, not from model guessing.

```json
{
  "schema": "agentos.deepmod/1",
  "id": "pigeon-navy-gold",
  "package": "local.agentos.pigeon",
  "capabilityVersion": 1,
  "baseRevision": 0,
  "theme": {
    "colors": {
      "bg": "#FF081A33",
      "surface": "#FF122A4A",
      "surface2": "#FF1C3555",
      "text": "#FFFFF4D6",
      "text2": "#FFD4C6A5",
      "accent": "#FFD6AD45",
      "onAccent": "#FF081A33"
    },
    "font": { "family": "serif", "scale": 1.0 }
  },
  "views": [
    { "screen": "chat-list", "match": { "id": "pigeon_overflow" }, "visibility": "gone" },
    { "screen": "chat-list", "match": { "text": "Demo promotion" }, "visibility": "gone" }
  ],
  "lists": [
    { "id": "pigeon_threads", "exclude": { "field": "promoted", "equals": true } }
  ],
  "buttons": [
    {
      "id": "summarize-chat",
      "screen": "chat",
      "slot": "pigeon_chat_actions",
      "label": "Summarize",
      "action": { "id": "agent.ask", "template": "summarize-visible", "context": "current-chat" }
    }
  ]
}
```

Each field is optional except schema/id/package/capabilityVersion/baseRevision. The four example requests can produce separate small specs; this combined example shows the operation vocabulary. For tonight, use **one effective document per app**: inspect returns it, the model updates it, preview checks `baseRevision`, and commit replaces the whole document. No multi-mod priority/merge system. IDs identify the document and inserted controls; replacement must never duplicate buttons.

Validation rules:

- Strict schema, duplicate JSON keys rejected, unknown keys rejected, maximum 16 KiB UTF-8, depth 8, 32 operations and two buttons per screen. Bound traversal to 512 registered Views and 40 visible list items; reject excess rather than partially apply. Theme entries count toward the operation bound. No code, regex, paths, URLs, classes, methods, reflection selectors, intents or shell fragments.
- Only the two exact packages, known signing certificate digests and tested APK/build digests. Check package manager identity at inspect, preview and apply, and invalidate on package replacement. A versionCode of 1 alone is insufficient because today's build script reuses it.
- Colors are opaque `#AARRGGBB`, alpha `FF`; token names must be advertised by that app. Restrict fonts to `sans-serif`, `serif`, `monospace` or developer-bundled resource names; no downloaded fonts. Scale is finite, 0.85–1.3 times the app's original sp size, preserving system font scaling and bold/italic. Restrict theme changes to registered surfaces; protect error/status readability.
- A selector has exactly one of `id` or `text`. ID resolves only within the declared screen's registered mod targets; list repeats use the list operation instead. Text is exact, case-sensitive, bounded to 80 characters, and must identify one registered, non-editable decorative View. Zero/multiple matches reject; never hide an ancestor guessed from text. Text is an observation, not an instruction or action argument.
- `gone` uses real `View.GONE`; rollback restores the original visibility rather than blindly making every View visible. Navigation, composer, Send, kill/undo, security and approval controls are not hideable or insertion targets. Registry scope is stronger than package-name exclusions.
- List filters accept one advertised boolean/string field and equality only. Pigeon's future `promoted` flag belongs only to seeded test records. Filter the **display projection**, preserving sort order, stable record IDs and persisted source records. Beacon may advertise `kind == "analytics"` for log presentation, but hiding a log entry never stops its request or changes counters. Don't label this as tracker blocking or network ad removal.
- Buttons occupy a developer-provided slot with fixed layout and a 48 dp minimum touch target. Labels are plain text, at most 24 characters. Actions must be app-advertised capability IDs; arguments use fixed enums or opaque current-screen handles. No free-form task string or click sequence. Allow only read/summarize for this version.

### Signing and delivery

Proposed flow: `plain-language request → agentd tool → Android Broker → authenticated Binder IPC → owned app renderer`. agentd proposes data; the broker remains the authority. A shared Java interpreter runs only as a narrow renderer inside each owned app, not as another independent action broker.

1. Add a small bound Binder endpoint in each fixture and a broker endpoint for button callbacks. Check `Binder.getCallingUid()` during the transaction **before** posting work to a Handler. Pin package plus certificate, reject shared/unexpected UIDs and explicitly bind named components. A bound service is Android's supported IPC surface; see [bound services](https://developer.android.com/develop/background-work/services/bound-services). Never trust an Intent extra saying “owner” or “approved.”
2. Existing demo/broker APK signing keys may differ. Pin each peer's actual certificate. Use a signature permission only if actual keys match; it cannot replace these package checks. Android documents [signature permissions and app signing](https://developer.android.com/studio/publish/app-signing). Do not distribute the committed demo keystore as the patch-authority key.
3. Use a separate broker-owned Android Keystore EC P-256 signing key with `SHA256withECDSA`, distinct from the fingerprint approval key. During explicit initial integration, apps bind to the certificate-pinned broker and enroll its public patch key; no unauthenticated public-key download or silent key rotation. The private key and bearer tokens never leave the broker. [Keystore](https://developer.android.com/privacy-and-security/keystore) provides non-exportable key operations; it does not make a compromised rooted OS trustworthy.
4. The broker creates an envelope containing `payloadB64` (exact UTF-8 bytes), SHA-256 payload digest, target certificate/build, revision, task ID, mode (`preview`/`commit`/`rollback`), nonce, expiry and disable epoch. Sign a versioned fixed-order envelope encoding with the signature omitted; verify before parsing/rendering. Signing exact payload bytes avoids divergent JSON canonicalizers. Reject duplicate keys in the envelope too.
5. Apps verify signature, target/build, fresh nonce/expiry, monotonic revision, mode and epoch. Persist the committed revision/epoch so replay cannot re-enable a killed mod. A rollback is a newly signed higher revision containing prior payload bytes, not permission to accept old signed envelopes. Patch signatures attest broker validation; they do not assert a fingerprint or authorize future button actions.

## Rendering and the Summarize button

Share one modest `DeepMods.java` interpreter through the existing build script. Add `res/values/ids.xml`, semantic targets/slots and small adapters at construction points. Pigeon exposes `chat-list`/`chat` roots; Beacon exposes its main root/log. Token resolution replaces direct `getColor()` use and the helpers' hardcoded font choices; cover avatars/icons, hint colors, bubbles and Beacon's success/error updates explicitly. Never treat a resource ID integer as an ARGB value: Pigeon `pill()` takes a resource ID while Beacon `pill()` takes ARGB.

Parse/validate off the UI thread. On the main thread, build a candidate subtree from the current screen state and replace the app-content root only after successful construction. Preserve open chat, unsent composer text, selection, scroll anchor and Beacon notes; suppress persistence listeners while restoring inputs. Do not call Activity.recreate(), `onCreate()` or reset/seed methods to apply a patch. These would lose state or repeat network scheduling. Reattach live references after a root swap and reject callbacks from older render generations. For newly appended rows/bubbles, invoke the same renderer directly, with no polling or global `View` hooks.

Beacon currently discards rows beyond 40. Introduce a 40-record backing ring for deterministic rerender/filter undo. Filtering changes visible rows, not the ring, counters or network tick. Pigeon's filter runs on an in-memory sorted list, never `JSONArray.remove()` or `chats.json` rewriting. Runtime metadata should use a separate mod-target registry rather than hijacking arbitrary `View.setTag()` state.

The inserted Summarize control is a real Android Button in the app's View tree and accessibility tree. In preview it is disabled. On physical activation it calls the narrow broker endpoint with only its signed spec/action ID and current-context handle. The broker verifies caller, active revision/epoch, plugin status and current foreground context, then maps `agent.ask` + `summarize-visible` to the **existing ask/task entry path** with a fixed developer-authored summarize instruction. This bridge is new work; a dedicated summary backend does not already exist.

Context is a bounded read of the current visible chat/notes under existing memory/privacy consent, labeled `{source_pkg, trusted:false}` and passed through Prompt Guard. Keep Pigeon's canary text untrusted. No memory ingestion, upload to a new service or automatic send. If existing provider/consent rules cannot permit the read, explain and leave the button inert. Return a summary through the existing agent result UI. Do not mark the tap as a clean owner-written prompt or grant broad owner authority. No native direct network calls in Pigeon are needed.

Adding a button retains the current HUMAN mod gate. Clean typed/voice owner theme/hide/filter requests use ordinary reviewer auto-approval after preview, bound to the verified caller/task/app lease. Remote, copied/tainted, flagged or unbound requests retain HUMAN. Recheck policy at execution; installation of a button is never standing approval to send, pay, delete or use vault data. This version doesn't advertise those actions.

## Preview and screenshot verification

Proposed tools: `app.deepmod.inspect`, `.preview`, `.apply`, `.list`, `.remove`, `.disable`. Keep overlay tools unchanged. Preview is temporary mutation of the actual owned app, not a simulated overlay. Require its matching foreground screen; do not navigate or invoke controls just to satisfy a selector.

1. Inspect obtains app identity, capability version, base revision and a screen-generation/context handle. Capture an unmodified baseline using the broker's existing screenshot path. Never run `uiautomator dump`: STATUS.md records accessibility rebinding and broker/event interruption.
2. Validate and authorize a short preview session (at most 10 s), then send a signed preview envelope. Save screen state and last-good content tree. Build/render candidate on the next layout; no button callbacks, writes to chat/notes, network refreshes or persistence during preview. On expiry, error, app backgrounding or broker loss, restore the baseline automatically.
3. After layout/animations settle, capture the candidate through the same screenshot path. Respect at least 350 ms between captures, as existing mod tests do. Associate both images with the exact payload hash, build/revision, screen generation, orientation, font scale, timestamp and affected bounds. Keep captures local with short retention and don't put message bodies in audit logs.
4. Produce a side-by-side image and absolute pixel-diff heatmap. Mask only explicit volatile regions (status clock, caret, Beacon timestamps/live values) shared across both images. If live content changes outside those masks, invalidate and recapture rather than attribute the change to the patch. Never mask the target region. Compare changed pixels inside declared regions and unexpected changes elsewhere; a vision review explains the result but cannot override a failed structural check.
5. Add structural assertions: requested token/font applied; hidden target is `GONE` and contributes no layout space; filtered record IDs absent and source data unchanged; neighboring row order retained; exactly one real Button in the declared slot with expected action, disabled in preview. Screenshots alone cannot prove filtering versus masking or action safety. Test navigation/draft/notes preservation separately.
6. Return a broker preview receipt binding payload hash, base revision, app build, screen generation and evidence digest, valid for 30 s. Auto-restore preview, then apply the **same bytes** only after the appropriate review. Changes in payload, build, revision, screen, disable epoch or receipt expiry require a fresh preview. Apply takes the same serialization lock as disable and checks epoch again immediately before root swap, so a pending approval cannot resurrect a killed mod.
7. Capture the committed result and rerun structural checks. On mismatch restore last-good/default and persist a failure reason. Audit proposal hash, review channel, target build, match counts, receipt, verification result and rollback; no private screenshot/text in the hash-chain log.

Measure request-to-preview, review wait, apply-to-frame and screenshot-confirmed latency separately. Budget 1–3 s for preview/capture/diff overhead plus model/voice/reviewer time, to be measured. Existing overlay timings are not measurements of any deep-mod engine.

## Kill switch and rollback

- Extend the existing physical volume-down hold (at least one second), Command center disable and plugin-disable path to persist a higher global/app disable epoch **before** canceling previews, pending approvals and button callbacks. Disable/remove remains always allowed without approval. A physical re-enable needs a fresh preview and normal policy; an old spec cannot silently re-enable itself.
- Apps keep an unmodifiable Reset appearance action outside mod targets. While foreground and modded, renew a broker liveness grant every 2 s with a 5 s deadline. Binder death disables immediately; timeout restores built-in rendering and disables buttons. Check grant/epoch on resume before the first modded frame. Broker restart defaults to disabled until synchronization; never replay cached active specs blindly. No boot receiver or service.d script is added.
- Before applying a candidate, atomically persist its pending marker and preserve last-good; clear pending after structural/frame checks succeed. On the next cold launch, an uncleared marker disables that revision and starts vanilla without retry. Use app-private [AtomicFile](https://developer.android.com/reference/android/util/AtomicFile) with explicit serialization; atomic files don't provide thread locking themselves.
- Preserve last-good payload and vanilla defaults independently of chat/notes storage. Rollback restores render state and removes injected controls, not user data. Normal apply exceptions revert immediately. Do not catch arbitrary VM/native failure and promise continuation: a separate broker observer handles crash/native-crash/ANR for exactly the two fixture packages, disables them durably and never auto-relaunches a failing app. An Lmkd kill is not proof that a patch crashed.
- If the target UI thread hangs, in-app timeout cannot run. The developer can force-stop only that fixture over adb. Reinstall the saved known-good APK with matching certificate/version rules if necessary, without uninstall or `pm clear`. Deep mods never alter USB debugging, Wi-Fi, firewall, default launcher/IME, Magisk, Termux, agentd watchdog or boot images.

## Staged tests and recovery

The recommended route avoids adding a boot path or changing connectivity. No plan can guarantee that a boot-framework experiment cannot disconnect or bootloop the phone; that guarantee is the reason to exclude LSPosed from the deadline path.

| Stage | Work for the later implementation task | Gate / stop condition |
| --- | --- | --- |
| 0. Offline first | Implement schema/signature/replay checks, capabilities and renderer against synthetic fixtures. Build both APKs locally. Test malformed/oversized/duplicate-key JSON, invalid colors/fonts, wrong package/cert/build/signature, stale revisions, ambiguous text, protected targets and forbidden actions. | Every negative case rejects before mutation. No device commands needed. |
| 1. Prepare recovery | After other phone work finishes, acquire `scripts/withphone` for every state-changing adb step. Use USB serial ZY22J3TK8S, confirm authorized adb and root, battery >50%, record broker/agentd health and existing modules/settings. Save exact installed fixture APKs/certificates/version info and fixture chat/notes backups locally. Confirm reset/disable endpoint and recovery filenames before replacing anything. | Do not proceed if USB recovery is unavailable, current APK signatures don't match or other work holds the phone. Never repair an install by uninstalling. |
| 2. One app | Install only cooperative Pigeon update; verify vanilla first. Then navy/gold, font, one decorative hide, synthetic promoted filtering, each followed by remove/restore. | Real collapse/no mask; no source-data deletion; repeated list/chat navigation, incoming test message, draft/selection/scroll preserved; no crash or broker disruption. |
| 3. Lifecycle / failure | Ten apply/remove cycles, orientation/font-scale change, background/resume, fixture force-stop/relaunch, malformed persisted spec, genuine test-only fixture crash, broker/session loss, expired preview and kill during pending apply. | No auto-retry crash loop, no duplicate button, disabled state survives restarts; recovery succeeds without reboot. Failure disables deep mods and ends the experiment. |
| 4. Beacon | Install and verify vanilla, theme, new rows, filter/remove against its ring. Test without initiating new demo traffic: deterministic instrumentation with synthetic records first; any live check preserves existing request behavior and privacy rules. | No extra tick/thread on apply; notes unchanged; live color updates obey theme; restoring filter restores all retained rows; counters retain actual totals. |
| 5. AI and button | Typed/voice owner requests through ordinary broker policy; negative remote/tainted/caller tests; add Summarize with normal HUMAN gate, then owner activation. No approval taps synthesized by an agent. | Correct current scope, Prompt Guard labels, no new agentd credential, no outward action, screenshot and structural evidence saved. |
| 6. Optional Frida | Only after cooperative recovery passes and other device tracks finish: running owned-app attach, one edit, restore/unload, fixture restart. Stop server/session created by this test without killing unrelated sessions. | Pin exact core/client/bridge; stop at any native fault, UI/broker interruption or need for OS changes. Never escalate to a reboot workaround. |
| 7. Optional LSPosed, after judging | Verify exact release compatibility on a spare device first. Audit module scripts/native payload, checksum and scope; enable Zygisk and install in an isolated session with recorded original settings. Reboot only after all other work is done and recovery is ready. Validate vanilla boot before enabling the own-app module. | If it is still before judging or recovery is unproven, skip. No framework upgrade, Riru/Zygisk replacement, ART changes or boot flashing to make this work. |

### Concrete adb recovery path (future operator only; not executed here)

For a stuck cooperative fixture, force-stop it, then install its saved known-good APK. Repeat for Beacon only if it is affected. Backups must have been prepared in stage 1; the paths below are proposed local backup names.

```sh
scripts/withphone adb -s ZY22J3TK8S shell am force-stop local.agentos.pigeon
scripts/withphone adb -s ZY22J3TK8S install -r /tmp/agentos-deepmods-backup/pigeon.apk
```

The old APK ignores the separate mod file. Preserve the existing versionCode in the first integration or deliberately prepare a tested rollback version; don't improvise downgrade flags. Never `pm clear`, uninstall, reset chats, disable the broker or restart adb as a recovery shortcut.

If LSPosed is eventually tested, prepare its **actual installed module ID** before reboot. A targeted root command to create `/data/adb/modules/EXACT_MODULE_ID/disable`, followed by a controlled reboot, disables that framework module. `EXACT_MODULE_ID` is a placeholder, not an instruction to run literally. Record and restore the original Zygisk setting through Magisk as part of recovery. Disabling a module takes effect on restart; it cannot remove already loaded zygote code in place. [Magisk's module guide](https://topjohnwu.github.io/Magisk/guides.html) defines the `disable` flag.

If adb is unavailable during boot, recovery depends on the device's physical safe-mode sequence, not agentd, Wi-Fi or the launcher. Establish that sequence and an operator-present recovery window on this Moto before attempting the framework. [Magisk's FAQ](https://topjohnwu.github.io/Magisk/faq.html) describes early volume-down safe mode and `magisk --remove-modules`; the latter removes **all** modules and reboots, so it is a last-resort tradeoff that would also remove the existing OTA suppression. The current FAQ's automatic bootloop protection is not proof that installed 30.6 has the same behavior. Do not rely on it, a custom recovery being installed, or adb being reachable before boot. If these prerequisites cannot be verified, no LSPosed install.

## Implementation estimate and handoff

Developer-hours, assuming existing build tools work; includes targeted verification, excludes unrelated feature work and any deadline-driven rush. Fable owns the visible UI integration per ARCHITECTURE.md; this research task implements none of it.

| Deliverable | Estimate |
| --- | --- |
| Shared validator/runtime, stable IDs/registry, Pigeon colors/font/hide/filter and synthetic fixtures | 2–3 h |
| Broker Binder bridge, peer identity, Keystore envelope, revision/epoch and preview receipts | 2–3 h |
| Beacon adapter/ring, state-preserving rerender and fixed-action Summarize bridge | 2–3 h |
| Screenshot diff, policy/lifecycle/failure tests, recovery rehearsal and evidence | 2–3 h |
| **Complete cooperative version** | **8–12 h total**, with a useful Pigeon-only theme/font proof in ~3–5 h including minimum broker/signing work; don't call that proof the full engine. |
| Frida one-shot compatibility spike | 1–2 h; production lifecycle filtering/buttons and rollback another 4–8 h, with unbounded bridge troubleshooting risk. |
| LSPosed framework evaluation/install/recovery window | 1–3 h if compatible, plus 4–8 h for a scoped module/spec adapter; no reliable upper bound if boot/ART compatibility fails. Post-judging only. |

Before noon, prioritize the cooperative Pigeon slice only if it can complete its rollback gates; otherwise leave this as a design and keep the existing overlays. The full design does not fit a short last-minute slot. Implementation touches demo app resources/Main adapters/build input, a new shared interpreter, a broker deep-mod coordinator/IPC endpoint, existing mod kill/plugin routing, and agentd tool metadata. Keep it separate from overlay matching code and add no dynamic code loader. Completion evidence must distinguish designed features, offline tests, phone rendering and end-to-end voice timing.

Document-only change: no implementation or device test is claimed. The requested `code-golf` skill was absent from the supplied catalog and searched local Codex/Agents/Claude skill folders; a manual simplification pass chose one effective spec per app, one shared interpreter and fixed enum actions. No formatter or Prettier was run.
