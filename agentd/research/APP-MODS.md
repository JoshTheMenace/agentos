# Realtime app mods (2026-10-27)

## Decision for the freeze

Ship L0 buttons plus L1 accessibility masks. No Frida, Zygisk, APK patching or app-process code in the demo. LinkedIn is installed on ZY22J3TK8S (Android 13). The owner explicitly authorized passive viewing and modification here; no likes, messages, connections, posts or account settings. A mask covers visible content; it does not remove a RecyclerView row or prevent downloading ads. We must describe it as masking, not collapsing.

| Layer | Declarative capabilities | Risk / limits | Freeze decision |
| --- | --- | --- | --- |
| L0 | Existing anchored press/compute buttons | App-button actions rechecked; no outward actions; no private apps | Keep Calculator tip button demo |
| L1 | Exact text/id/class match; mask matching node or containing scroll item | Separate AgentOS window; no target-process mutations. Accessibility omissions, recycled bounds and scrolling can reveal content or overmask | Build; highlight matched rectangles before approval; bounded matching; remove stale masks on events |
| L2 | Generic in-process interpreter: set visibility, color, size; reorder only with a tested layout adapter | Hook/native failure can crash despite Java catch; recycled state must be restored before every bind | Research only; own/permitted apps; never security, system or money apps |
| L3 | Existing per-UID iptables tracker/ad-domain rules | Shared first-party endpoints cannot distinguish promoted from organic posts; blocking may break app features | Keep existing firewall; do not use as feed filtering |

Accessibility supplies screen bounds, ids, text and collection relationships, not a general cross-process View mutation API ([AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo)). Accessibility overlays are a documented service capability ([AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)). Thus L1 cannot truthfully promise arbitrary restyling, reordering or removal of layout space.

## Engine comparison

These relative latency/battery/risk estimates are engineering judgments, not measurements on this phone.

| Engine | Crash risk | Setup on this Moto | Anti-tamper | Latency / battery |
| --- | --- | --- | --- | --- |
| Overlay only | Lowest target-app risk: interpreter lives in AgentOS; still guard broker callbacks | Existing enabled accessibility service; no reboot | No injected library; apps can still detect accessibility/overlays | Event-driven tree walk and window placement; bounded traversal; no continuous screenshots |
| Frida | Higher: runtime injection and broad hooks share target process; catch cannot contain native crashes | Root available; matching arm64 server + host/runtime versions; attach on each process restart | Frida docs explicitly mention server detection | Fast once attached; attach/start cost, JS runtime and hook frequency add overhead |
| LSPosed/Zygisk | Higher: hooks inside process; bad scope/startup hooks have wider blast radius | Requires compatible Magisk/Zygisk framework/module, scoped package config and reboot; not verified installed | Framework/module detection remains possible; no guarantee | Early hooks survive app launches; narrow Java hooks likely cheaper than broad JS tracing, but measure |

Frida's [Android setup](https://frida.re/docs/android/) documents root/server installation, ROM-specific crashes and detection. [LSPosed's upstream](https://github.com/LSPosed/LSPosed) describes ART hooks through Riru/Zygisk and its Android/Magisk requirements. A Magisk-rooted phone alone does not prove either engine works safely. Do not install a framework before the freeze merely to make this demo more impressive.

## Spec and safety contract

The model emits data only. L1 spec: `{id,package,label,action:{type:"mask",match:{text:"Promoted"},scope:"item",color:"#101820"}}`. Match is exactly one of text/id/class; text is exact, case-insensitive, with no regex/eval/scripts. `scope:"node"` masks one node; `scope:"item"` walks only to a bounded child of a scrollable collection. It fails open if no safe item exists. Preview uses the same matcher and highlights, is temporary, never presses app controls, and returns a hash binding apply to that exact spec. Any change requires another preview and approval.

Every first apply/replacement requires the existing native HUMAN approval card, bound to the complete spec. Preview is required before new L0/L1 specs. Persist validated specs; revalidate on restore. Store disabled state separately from the spec. List includes status/reason; remove is always allowed. Per-app/global disable is always allowed; enabling again requires a new preview/apply approval. Never relax Guard globally: LinkedIn alone gets a passive mask exception; button mods remain prohibited there. Block Guard.FORBIDDEN, AgentOS packages, system packages and money/security package names.

Each render callback catches exceptions, removes that mod's windows and persists disabled state. A callback failure is a no-op for the target app. Watch Android crash/ANR logs outside target apps, map exact package names, disable every active mod for that package, and never restart the target automatically. If watchdog health is lost, masks must disable rather than claim protection. Persist a render-in-progress marker to disable after an AgentOS process crash. A physical volume-down hold disables all mods. Do not synthesize FATAL/ANR for LinkedIn; test failure handling in an isolated fixture. Android [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo) distinguishes crash, native crash, ANR and low-memory death; querying other apps needs privileges, so the rooted logcat observer must be tightly scoped and consume no content logs.

L2 future gate: one reviewed interpreter, no model-supplied code. Versioned schema with package/version allowlist, limits on traversal/matches, preview-only mode, original properties saved per View and restored on rebind/remove, and each hook `try/catch` falling back to the original method. External watchdog must remove the scoped rules before a restart after FATAL/ANR. Native failures cannot be made no-crash by catch; this is why L2 is deferred.

## Proof / handoff

Record actual test results below after implementation. Screenshots: `launcher/shots/mods-*.png`. Separate phone-render latency from owner-approval wait and model/voice latency. No synthesized approval taps and no privileged production apply bypass for tests.

## Code walkthrough and review

`android/src/local/agentos/ModMask.java` contains the bounded matcher and painter. `item` chooses a child of the nearest scrollable ancestor, clips it to the app viewport, and refuses an oversized rectangle. It can inspect an offscreen marker still present in the tree so an item's visible remainder stays covered. If the app removes that marker from accessibility entirely, matching cannot identify the remaining content safely. Taps on a mask are consumed; an owner's vertical swipe requests only a collection scroll. No hidden Like/Send/etc. can be clicked through the mask.

`Mods.java` owns preview receipts, overlay lifetime, saved specs and disable reasons. It clears stale masks before rematching, checks secure-window state asynchronously, ignores its own overlay window events, and invalidates stale security checks when app content changes. Render exceptions disable only the affected mod. The logcat watchdog handles Java/native crash and ANR package markers; loss of the observer disables mods. `AgentA11y` connects events and the physical kill switch. `Broker.mod` validates before showing the native card and rechecks the preview after approval, so a kill/remove during a pending card wins.

`agentd/tools.js` and `plugins.js` expose the six Mods tools. Legacy `app_mod_*` definitions remain for compatibility; the model's Mods menu contains only the six current tools. `Plugins.java` applies the same toggle to the new broker methods.

Tests live in `android/mod-tests/`. Build with `sh android/mod-tests/build.sh` after `android/build.sh`. They use a separate instrumentation APK and a separate fictitious-feed APK, both temporary and signed with the existing development key. Tests call the renderer in isolation, not an approval-bypassing production RPC. The production broker's negative preview gate is tested separately. A forced invalid color after validation exercises the actual rendering exception handler. A genuine crash in the fictitious feed exercises the external observer; no real app is deliberately crashed. Each of ten mask cycles checks the framebuffer's actual mask color, not merely attached-window state. Screenshot sampling waits 350 ms to respect Android's screenshot rate limit; its timings are conservative upper bounds, including capture/PNG time.

The requested `code-golf` skill was absent from the supplied catalog and local Codex/Agents/Claude skill directories. A manual cleanup pass removed duplicate legacy tool descriptions from the model menu. No formatter was run.

## Owner approval and later demo steps

The isolated renderer tests do not constitute owner approval to activate a persistent LinkedIn mod. The owner-facing apply remains gated. A direct test RPC was denied by the reviewer (the broker returned only `denied by reviewer`, no rationale); the final request is made as an ordinary agentd user turn so it carries trusted task context.

1. Open LinkedIn's Home feed with a Promoted card visible. Stay out of Messaging and account settings for this rehearsal.
2. Say/type: **Hide Promoted posts in LinkedIn using a passive mask. Preview it, then ask me to approve.**
3. Inspect the blue rectangle; tap **Approve** on the native AgentOS card. No fingerprint is needed. If the card expired, repeat step 2; a new preview is required after five minutes or any kill/remove.
4. For the second real-app demo, open Calculator and ask **Add a Double button that presses multiply, 2, equals. Preview it, then ask me to approve.** The exact spec is `android/mod-tests/calculator-double.json`.
5. Ask **List my mods** / **Remove the LinkedIn mod**, or **Disable all mods**. A volume-down hold of at least one second disables all mods immediately and persists that state. Re-enabling requires preview and approval again.

Limits: visual masking, no layout collapse/reorder, no removal from the accessibility tree/network, and possible brief exposure while content changes or a marker leaves the tree. Banking/security exclusions use the existing Guard lists plus package-name exclusions; this is not a complete classification of every app in the Play Store. No Frida/LSPosed compatibility, battery benchmark, voice-to-visible timing, or live LinkedIn apply should be claimed without its corresponding run/owner tap.
