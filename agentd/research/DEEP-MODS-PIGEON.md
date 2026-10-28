# Limited cooperative Pigeon proof

Implemented 2026-10-28. This is the Pigeon-only slice of DEEP-MODS.md, not the full engine. No injection, reboot, Beacon adapter, third-party internals, downloaded fonts or executable specs.

`Contract.java` is shared by the broker and Pigeon build. It validates strict bounded JSON (including duplicate-key rejection), advertised colors/fonts, two decorative targets, the synthetic promoted-row projection and one fixed Summarize action. Each peer checks the other's actual package, UID and pinned APK certificate at the Binder transaction boundary. A separate Android Keystore P-256 key signs exact payload bytes plus build, revision, task, nonce, expiry and disable epoch. A developer explicitly enrolls the exact Pigeon APK digest; a new APK requires enrollment again.

`DeepRuntime.java` registers the app's existing Views and original styling, changes their properties in place and detaches only fixture rows. It preserves the live composer, selection, scroll views, message Views, keyboard image attachments and conversation file. Newly constructed Views use the same registry. An empty chat-action slot and a GONE reset control consume no space in vanilla mode. Fixtures are opt-in, so other agents' normal keyboard and notification flows retain today's Pigeon behavior:

```sh
scripts/withphone adb -s ZY22J3TK8S shell am start -n local.agentos.pigeon/.Main --ez deepmod_fixtures true
```

Two clearly named **Promoted · Synthetic … offer** rows exist only in the rendering list, never in `chats.json`. A fresh launch defaults to no fixtures. `--ez deepmod_fixtures false` turns them off on the running fixture.

`DeepMods.java` previews actual native Views, captures baseline/candidate PNGs using AccessibilityService.takeScreenshot, saves side-by-side and absolute RGB-difference images, and verifies source hash, real row absence/order, actual colors/typefaces/sizes, GONE targets and native button structure. Content-root bounds, display configuration, generation, payload/build/epoch and source hashes accompany evidence. System bars are outside the declared content region. Evidence stays in broker-private storage for ten minutes; only hashes go into audit. A receipt lasts 30 seconds and is bound to the task and identical bytes. Apply rechecks it after review, captures the committed frame and reruns structural checks. Exceptions disable the candidate.

`app.deepmod.*` tools include inspect, preview, apply, list, remove, rollback, disable and a convenience `style` operation. `deepmod_style` runs inspect → strict preset spec → preview → ordinary review → commit in one call, avoiding model delay between preview and commit. The exact request “theme Pigeon navy and gold and remove the promoted rows” selects it. Raw payload tools remain available for individual capabilities.

Clean typed/voice owner cosmetics retain ordinary reviewer policy. Remote, copied/flagged, unbound or button commits retain HUMAN; no test taps an approval card. A disabled button can be previewed by a clean task, then automatically disappears. The real Button requires a physical touch, carries only its fixed action/revision/epoch/context, and cannot authorize sends. Agentd checks existing Pigeon cloud consent, validates its ledger, and starts the existing task queue with an untrusted origin. That worker can call only a one-use current-Pigeon-screen read, bounded to 120 nodes/6,000 text characters from Pigeon only; system/other-app nodes and unrelated task context are excluded. Existing redaction and Prompt Guard run before model delivery. Missing/revoked consent leaves the button disabled. No new bearer token reaches Pigeon.

Kill/reset/removal are always allowed. The physical volume-down path and command-center `app.mod.disable` both reach the deep-mod kill; switching Mods off also raises the durable epoch, cancels outstanding deep-mod approvals and restores built-in Views. A heartbeat renews a five-second grant every two seconds; Binder death, grant loss, app backgrounding or broker restart restores vanilla. Foreground-only operation is deliberate for this proof: returning to Pigeon does not silently replay a mod. A pending AtomicFile marker makes an interrupted candidate start vanilla on the next cold launch. The existing broker crash/ANR observer reaches the Pigeon disable hook; it does not relaunch the fixture. Rollback signs the prior appearance at a higher revision and never restores or deletes chat data.

Build/integration:

```sh
./demo-apps/build.sh pigeon
./android/build.sh
# Install APK updates through scripts/withphone; do not uninstall or clear Pigeon.
scripts/withphone python3 android/tools-client.py call app.deepmod.integrate
```

Integration is shell-UID-only, not a model tool. Recovery APKs and the pre-update fixture conversation backup are in `/tmp/agentos-deepmods-backup/`; reinstall a matching saved APK with `install -r`, never `pm clear`. The current keyboard, launcher, firewall, Magisk modules, connectivity and boot/watchdog settings are unchanged by this proof.

Verification commands:

```sh
android/deepmod-tests/host.sh  # after the broker build: contract/signature and production policy
node agentd/deepmod-test.js   # isolated DB and fake phone; no attached-device calls
node --check agentd/deepmod-tools.js
node --check agentd/deepmod-actions.js
node --check agentd/tools.js
node --check agentd/plugins.js
node --check agentd/tasks.js
node --check agentd/server.js
```

The requested code-golf skill was absent from the catalog and local skill folders. Manual simplification kept one effective document, one shared validator, in-place rendering and fixed enum actions. No Prettier or formatter was run.

## Recorded evidence

- Offline: 39 contract/signature cases, 15 production broker-policy cases, 106 summary scope/consent/redaction/undo cases passed. Both APKs built; changed agentd files passed syntax checks before deployment.
- Phone: exact plain-language request succeeded, native preview and structural checks passed, reviewer auto-approved in 2.990 s, commit reached revision 2. Convenience tool duration 6.898 s; full agent turn 25.476 s (resumed implementation). Conversation hash remained `4ed45e55fe7206cd8fea5fc5f5ed32efbbfdabe0056e3dfe43fd4ec3ef85b130`; visible rows went from five to three, retaining real-row order.
- Before/preview/commit, side-by-side, diff and metadata: `demo-apps/shots/deepmods/`. The screenshots show the actual app View tree, not an overlay mask.
- The first cold reviewer exceeded its four-second limit and left the card untouched until timeout. A later normal reviewer attempt approved; the convenience tool retains the same gate.
- Serif and both decorative GONE targets passed on the phone. Backgrounding by subsequent shared-phone test sessions restored vanilla. A later rollback setup hit the ordinary reviewer timeout; its waiting card was canceled through disable, never tapped.
- Ten native preview/automatic-restore cycles passed, including repeated typeface changes with unchanged source data. Disable invalidated the last receipt. A new signed rollback to vanilla increased the revision; restoring a prior nonempty committed spec was not verified because the ordinary reviewer timed out during setup.
- The native Summarize preview contained exactly one disabled Button and restored to zero buttons without changing conversations. Its screenshot is `demo-apps/shots/deepmods/button-preview.png`.
- Reinstalled the saved original Pigeon APK briefly, captured vanilla, reinstalled the final APK, and compared the content rectangle: **0 changed pixels / 1,036,080**. Both images are in `demo-apps/shots/deepmods/vanilla-{original,current}.png`. Matching certificates/version retained all data; no uninstall or clear.
- Final cleanup: disable, fixtures off, then three cold launches with successful vanilla/structural assertions. The conversation file is byte-identical to the pre-resume backup. One earlier cold-launch inspection timed out after a one-second startup wait; the three checks with two seconds of startup settling passed. Agentd reconnected to the broker after the final module deployment.
- A committed Summarize button and physical activation require HUMAN approval and are not claimed without it. No voice timing is claimed.

## Proof boundaries

This uses one declared screenshot region, the Pigeon content root, with structural checks and an unchanged conversation hash. It does not implement the full design's per-target unexpected-pixel classifier; the existing task island can appear in the screenshots. It also does not claim the full orientation/font-scale/crash/ANR test matrix. Appearance is intentionally foreground-only and starts vanilla on cold launch. The summary callback is implemented, consent checked and restricted to one current-chat read; only its disabled native-button preview can be tested without the owner's HUMAN approval.

On resuming the interrupted implementation, preview verification was moved after layout/capture, display configuration is checked before apply, and a pending marker survives until the broker acknowledges verification. A disable epoch check rejects previews that waited across a kill. Undo tools remain callable when the Mods plugin is off.
