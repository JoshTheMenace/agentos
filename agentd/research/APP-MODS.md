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

Preview is required before every new L0/L1 spec. A clean typed/voice owner mask request, bound to the verified agentd caller and an active matching app lease/task, uses ORDINARY reviewer auto-approval. Remote, flagged/copied/tainted, unbound and button requests remain HUMAN. No owner tap is required for an eligible mask; no outward button is allowed. Persist validated specs; revalidate on restore. Store disabled state separately from the spec. List includes status/reason; remove is always allowed. Per-app/global disable is always allowed; enabling again requires a new preview and the appropriate review. Never relax Guard globally: LinkedIn alone gets a passive mask exception; button mods remain prohibited there. Block Guard.FORBIDDEN, AgentOS packages, system packages and money/security package names.

Each render callback catches exceptions, removes that mod's windows and persists disabled state. A callback failure is a no-op for the target app. Watch Android crash/ANR logs outside target apps, map exact package names, disable every active mod for that package, and never restart the target automatically. If watchdog health is lost, masks must disable rather than claim protection. Persist a render-in-progress marker to disable after an AgentOS process crash. A physical volume-down hold disables all mods. Do not synthesize FATAL/ANR for LinkedIn; test failure handling in an isolated fixture. Android [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo) distinguishes crash, native crash, ANR and low-memory death; querying other apps needs privileges, so the rooted logcat observer must be tightly scoped and consume no content logs.

L2 future gate: one reviewed interpreter, no model-supplied code. Versioned schema with package/version allowlist, limits on traversal/matches, preview-only mode, original properties saved per View and restored on rebind/remove, and each hook `try/catch` falling back to the original method. External watchdog must remove the scoped rules before a restart after FATAL/ANR. Native failures cannot be made no-crash by catch; this is why L2 is deferred.

## Proof / handoff

Record actual test results below after implementation. Screenshots: `launcher/shots/mods-*.png`. Separate phone-render latency from owner-approval wait and model/voice latency. No synthesized approval taps and no privileged production apply bypass for tests.

## Code walkthrough and review

`android/src/local/agentos/ModMask.java` contains the bounded matcher and painter. `item` chooses a child of the nearest scrollable ancestor, clips it to the app viewport, and refuses an oversized rectangle. It can inspect an offscreen marker still present in the tree so an item's visible remainder stays covered. If the app removes that marker from accessibility entirely, matching cannot identify the remaining content safely. Taps on a mask are consumed; an owner's vertical swipe requests only a collection scroll. No hidden Like/Send/etc. can be clicked through the mask.

`Mods.java` owns preview receipts, overlay lifetime, saved specs and disable reasons. It clears stale masks before rematching, checks secure-window state asynchronously, ignores its own overlay window events, and invalidates stale security checks when app content changes. Render exceptions disable only the affected mod. The logcat watchdog handles Java/native crash and ANR package markers; loss of the observer disables mods. `AgentA11y` connects events and the physical kill switch. `Broker.mod` validates, selects the ordinary-reviewer or human lane, and rechecks the preview after approval, so a kill/remove during a pending card wins.

`agentd/tools.js` and `plugins.js` expose the six Mods tools. Legacy `app_mod_*` definitions remain for compatibility; the model's Mods menu contains only the six current tools. `Plugins.java` applies the same toggle to the new broker methods.

Tests live in `android/mod-tests/`. Build with `sh android/mod-tests/build.sh` after `android/build.sh`. They use a separate instrumentation APK and a separate fictitious-feed APK, both temporary and signed with the existing development key. Tests call the renderer in isolation, not an approval-bypassing production RPC. The production broker's negative preview gate is tested separately. A forced invalid color after validation exercises the actual rendering exception handler. A genuine crash in the fictitious feed exercises the external observer; no real app is deliberately crashed. Each of ten mask cycles checks the framebuffer's actual mask color, not merely attached-window state. Screenshot sampling waits 350 ms to respect Android's screenshot rate limit; its timings are conservative upper bounds, including capture/PNG time.

The requested `code-golf` skill was absent from the supplied catalog and local Codex/Agents/Claude skill directories. A manual cleanup pass removed duplicate legacy tool descriptions from the model menu. No formatter was run.

## Using the mods (founder policy, 10-28)

Open LinkedIn and say/type **Hide Promoted posts in LinkedIn**. The agent finds a visible marker (scrolling if necessary), previews the exact mask and applies it through the low-risk reviewer. Do not ask the owner to tap for a clean owner-requested mask. The request now carries its turn's lease even if the app was already open; the reviewer sees the actual user words rather than an empty or unrelated task. Remote/Grok/Dots, notification-origin, flagged/copied/tainted, wrong-caller/task and button requests remain gated. Merely observing the marker is not request taint.

**List my mods**, **Remove the LinkedIn mod**, **Disable all mods**, a long-press on a mask/button, or a physical volume-down hold (at least one second) give the owner control. Removal/disable never needs approval. Exact examples are `android/mod-tests/linkedin-promoted.json` and `calculator-double.json`; button mods keep their existing human gate.

Limits: visual masking, no layout collapse/reorder, no removal from the accessibility tree/network, and possible brief exposure while content changes or a marker leaves the tree. Banking/security exclusions use the existing Guard lists plus package-name exclusions, not a complete classification of every Play Store app. No Frida/LSPosed compatibility, battery benchmark or voice-to-visible timing is claimed.

## Verified on ZY22J3TK8S

Final isolated suite: **PASS** (`INSTRUMENTATION_CODE: 0`), raw results in `android/mod-tests/results.json`.

| Check | Result |
| --- | --- |
| Ten apply/remove cycles | 10/10, exact mask pixel verified every cycle |
| Apply request → next-frame callback | median 76.5 ms; range 50–606 ms |
| Screenshot-confirmed visibility | median 581.5 ms; range 533–1347 ms, including the 350 ms sampling delay and capture/PNG time |
| Bad spec with code field | Rejected before rendering |
| Forced renderer exception | Overlay removed, mod disabled; target PID stayed 29751 |
| Target app restart | Mod reappeared without reapply |
| Per-app and global disable | Both passed |
| Genuine fixture Java FATAL | Matching mod automatically disabled, persisted reason `target crash/ANR` |
| Production apply without preview | Blocked before any approval card |
| Calculator Double button | Rendered in 94 ms; screenshot captured; no account/data action |
| LinkedIn | Installed; typed owner request previewed and applied; reviewer auto-approved, saved mod shown; no human tap |

The live typed-request timing is recorded below; voice timing remains unmeasured. Isolated renderer timings exclude model and review time. ANR/native-crash parser paths are implemented but were not deliberately induced. Test APKs and their phone-side screenshot files are removed after copying the two proof screenshots.

First normal agentd user turn (later rejected by the reviewer over the safety hint): exact LinkedIn preview succeeded at 6,805 ms; native HUMAN approval card shown at 6,857 ms from request start. Screenshot: `launcher/shots/mods-owner-approval.png`. This is request-to-card timing, not request-to-applied-mask timing. No owner tap was synthesized.

## Approval touch failure (owner report, 10-28)

Two distinct failures were found. First, the reviewer treated the summary's unsolicited "hold volume down to disable mods" help text as an unrequested instruction. The action summary now describes only the matching mask; the physical kill switch remains unchanged. A subsequent normal user turn was graded `tap`, not blocked.

The owner then reported an Approve tap that left the card open until timeout. The original logs did not record a click/rejection reason, so the exact physical event could not be reconstructed. A deterministic no-touch reproduction did confirm a window-lifetime bug: `WindowManager.addView` had registered a view before its first attachment; `ModMask.clear` skipped it because `isAttachedToWindow()` was false, then discarded the reference. Baseline: `attached=false parentBefore=true parentAfterClear=true`. This can leave untracked mod/preview windows above later UI.

Fix: remove every tracked window through WindowManager even before attachment (idempotently catch an already-removed view). Apply the same cleanup to buttons/chips. Island card expansion now holds mods, clears previews and removes their windows; refresh/preview cannot recreate them while the card is open. The hold releases when the card actually finishes collapsing or hides, rather than when its approval wait ends. No touch-security policy or allowed approval channel was relaxed.

`WindowTests` now reports `attached=false parentBefore=true parentAfterClear=false`, `card hold=true previewParent=null highlights=0`, and successful hold release after collapse. Direct predicate checks accept a settled finger event and reject early, obscured, partially obscured and accessibility-flagged events. They dispatch no touch and never invoke an approval click. Logs: `android/mod-tests/window-results.txt`. `ApprovalTouch` logs contain only event flags/source/tool/age and listener acceptance, for the next physical owner tap. The original physical event was not captured. The cleanup/covering regression is proven and fixed; no further physical tap was requested after the founder changed the policy.

Post-fix full renderer/crash suite: PASS again (10/10 pixel-verified mask cycles); final timing data is in `results.json` and the table above. The window-lifetime regression also passed without dispatching any touch.

## Clean owner mask policy and live proof (10-28)

`mod_apply` always acquires/carries the turn's lease and the server resolves the real caller UID. `Broker.modChannels` permits the reviewer only for mask specs from the same verified caller, a granted owner-requested typed/voice lease, matching task ID and target app, a live/unpaused grant, and no flagged/copied/explicit taint. It does not mistake a selector observation for an untrusted request. All other cases keep HUMAN. The reviewer explicitly treats an owner-requested removable visual mask as low risk. This is an ordinary auto-review, not a tap/voice impersonation or a new approval bypass. The model still writes only validated specs; unsafe packages and outward button actions remain refused.

Policy-only instrumentation: **14/14 PASS**, including remote origin, flagged/copied/explicit taint, missing task, wrong target, wrong caller, button action, missing lease and paused request. No approvals were dispatched. Window lifetime/card hold/touch-filter regression: **PASS again**, with no touch dispatch.

Live typed request: **Hide Promoted posts in LinkedIn. Scroll the feed to a Promoted post if needed, preview the mask, and apply it.** Opened LinkedIn, scrolled twice, previewed at 29,476 ms, requested review at 34,269 ms. Reviewer returned **auto** at 36,713 ms; approval resolved as **approved by reviewer**, then apply succeeded at 37,791 ms. Screenshot-confirmed masked content at **38,388 ms** from request start. Review latency was **2,444 ms**. No human or synthetic tap occurred. `app.mod.list` confirmed persistent mod `linkedin_promoted`, package `com.linkedin.android`, `shown:true`, `disabled:""`.

During the review card, WindowManager contained exactly **one** AgentOS window (the Island), with no mod/preview windows. Logs showed `approval overlay hold=true` and release after the card closed. The applied screenshot shows the promoted card masked and the navigation controls visible: `launcher/shots/mods-linkedin-applied.png`.

Broader live-model reviewer regression: **17/19** expected outcomes. The differing cases were a user-requested Maps address lookup (auto versus the test's human expectation), and an unrequested Buy action (tap versus the test's fingerprint/block expectation). Native money fingerprint floors were not changed. These results are separate from the passing mod-policy and window tests.

## Native-looking masks (10-28, after the first LinkedIn proof)

The first applied mask was a flat navy block on a white feed (`mods-linkedin-applied.png`). Now a mask reads as blank space of the app itself: `launcher/shots/mods-linkedin-polished.png` (scrolled, promoted post in view, top bar and bottom nav untouched).

- **Fill = the app's own background.** With no `color` in the spec (or the old example default `#101820`, which now means the same), the first render takes one accessibility screenshot with the mask hidden and takes the most common pixel along the item's inner edges (card padding and header row, not its image). Cached per package/orientation/theme in the saved styles, so later renders and restarts are instant. LinkedIn light sampled `#FFFFFF`. An explicit other color is honoured exactly (the renderer-exception test still forces a bad one).
- **Chip.** One small centred A2 pill: "Ad hidden" for promoted/sponsored/ad/suggested markers, else the mod label, with a tiny blue AgentOS dot. Tap: peek, the item shows for 6 s under a chip-sized window ("Tap to hide" ends it early). Long-press removes the mod, as before.
- **Scroll tracking.** Mask windows are reused and moved (`updateViewLayout`), not torn down on every event; events are coalesced, never postponed, so a drag's event stream cannot starve the re-placement. For 700 ms after a scroll event (or a swipe on the mask) the mask is re-placed every frame by re-reading the tracked marker/item/list nodes (three IPCs) instead of the 600-node walk, which took 200 to 600 ms while LinkedIn was busy; a recycled row fails the marker re-check and falls back to the full walk at rest. The app-side text search was tried and finds nothing in LinkedIn's custom views. Measured: the mask follows a 600 ms drag at 50 to 100 ms steps; 80 ms after the drag ends it sits on the item.
- **Clipping.** The item rectangle is clipped to its scrollable list's bounds, so the mask never covers the app's top bar or bottom navigation.
- **Secure check.** A window is still never masked before its first root FLAG_SECURE check; when content changes inside an already-checked window the last verdict holds while root is re-asked (about 130 ms), rather than blanking the mask on every event.

Safety unchanged: approval cards still hold mods, the volume-down and per-app/global kill switches, long-press removal, render-exception rollback (it fired once during this work, when a view added in the same frame was re-added; fixed by tracking attachment through the mask list), crash watchdog, and the preview-then-review flow. The re-apply was a normal typed owner request (`Hide Promoted posts in LinkedIn …`): preview 12.4 s, reviewer auto-approved, applied at 19.6 s, no card and no tap. The isolated renderer suite was not re-run after this change; its pixel check now samples 8 px inside the item's left edge (the centre holds the chip) with color `#102030`. `agentd/tools.js` no longer suggests a color in the mask example; the copy on the phone is updated but agentd has not been restarted.
