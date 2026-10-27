# 05 — UI surfaces: launcher, AI keyboard, control rail, command center

Track: UI surfaces for AgentOS on Moto G 5G (2023), Android 13 / API 33, 720x1600 @ 280 dpi, rooted (Magisk).
Date: 2026-10-26. No phone commands were run for this report. Facts that are inferred or that I could not check are marked **UNVERIFIED**.

## TL;DR

- **Keep the prototype's toolchain** (javac + D8 + aapt2, 1.4 s builds, no Gradle) and its Java View kit (Glass, Orb, Island morph, sheet, edge light). Skip Compose: it needs Gradle, Kotlin and AndroidX, which slows each build from about 1.4 s to tens of seconds.
- **Biggest finding:** move every always-on surface (island, rail, approval sheet, keyboard strip) from `TYPE_APPLICATION_OVERLAY` to `TYPE_ACCESSIBILITY_OVERLAY`. On Android 13 that window type sits at layer 31. The status bar is 15, the IME 13, the nav bar 24 and app overlays 11. This fixes the prototype's "status bar draws over the island" problem. Android also treats these windows as trusted, so touches pass through them normally, and other apps' overlays cannot cover the permission prompt.
- **Launcher:** a native Java `HOME` Activity whose main area is a WebView (HTML/CSS). During development the WebView loads a Vite dev server over `adb reverse`, so UI changes appear in under a second without rebuilding the APK. For the demo, the HTML is bundled into the APK. Make it the default with `cmd package set-home-activity` or `cmd role add-role-holder android.app.role.HOME`.
- **AI keyboard:** first build an **AI suggestion strip that works on top of Gboard**. It uses the API 33 accessibility `InputMethod` (`FLAG_INPUT_METHOD_EDITOR`): `getSurroundingText` for context, `commitText` to insert. The strip appears in any app without replacing the user's keyboard. A full `InputMethodService` keyboard is an optional extra.
- **Estimate:** about 26 to 32 focused hours for this track. With parallel AI agents, about 12 to 16 wall-clock hours. No new SDK install is required. Optional: `brew install --cask android-commandlinetools` takes about 10 to 15 minutes. The Compose/Gradle path adds about 30 to 60 minutes of setup and slower builds.

---

## 1. What is reusable from the prototype (systemui-lab)

Source: `/Users/joshthemenace/.t3/scratch/2026-10-21-i-have-an-android-phone-178d4212/systemui-lab`

### Reuse as-is (copy into the new `local.agentos` package)

| Asset | What it gives us | Notes |
|---|---|---|
| `app/build.sh` | aapt2 compile/link → `javac --release 8` → D8 `--min-api 33` → zipalign → apksigner → verify. Measured 1.4 s. | It depends on pinned downloads in `discovery/tooling/` (158 MB: `platform-33-ext3_r03.zip` android.jar, `build-tools_r33.0.2` x86_64 under Rosetta, `r8-8.3.37.jar`), all SHA-256 pinned. Copy the folder or point at it. The script already handles `res/xml`, which the IME and accessibility configs need. |
| `Stage.java` view kit | `Glass` drawable (ink fill plus gradient hairline), `Orb` (breathing orb), `Bar`, `Dot` (timeline states), `Edge` (3 dp progress light), helpers `params/dp/row/col/text/pill/pillBg/clip`, Manrope variable font | About 200 lines of pure design system. This is the visual language for the rail, sheet and strip. |
| `Stage.Island` | Pill ↔ card morph using per-frame `updateViewLayout` (176x38 → 340x196 dp, overshoot). `set(title, sub, pct, state)` crossfades text, eases progress and drifts color (working = lamp, approval = sky, done = moss). Approval opens it automatically and done closes it. | Swap the window type to accessibility overlay (section 4). |
| `Stage.sheet` | Bottom sheet above the nav bar, rows stagger in, Approve → Approved → slides out | This becomes the approval prompt and the shell of the command center. |
| `Stage.bubble` | Draggable orb that snaps to an edge with overshoot, tap to unfurl | Basis for the control rail handle. |
| `Tour.java` | WebView inside an overlay (transparent background, CSS keyframes, `@font-face` from assets, JS off), AnimatedVectorDrawables (`res/drawable/avd_*.xml`), hand-drawn sparkline, `LayoutTransition` agent list, progress ring | It proves WebView plus CSS works inside our windows. Cost: about +50 MB PSS (75 vs 24 MB). |
| `Lab.java` | `TileService` Quick Settings tile, `requestAddTileService`, foreground-service notification with a Stop action, push-based state sync (no polling) | The QS tile becomes "Agent on/off". The notification Stop becomes a secondary kill switch. |
| `Tools.java` | Loopback JSON-RPC server on 127.0.0.1:7777 in MCP shape (`tools/list`, `tools/call`). It requires a bearer token, limits request size and sets timeouts. Includes CalendarContract tools. | Reuse it as the **UI event bus**: add `ui/event` and `ui/approve` methods (section 5). |
| `DuolingoTools.java` (pattern only) | Root `app_process` broker. It checks the caller's Unix-socket peer UID and lets only the app UID in. | The template for the root actuator (taps/typing via root) behind the permission system. |
| `StatusIcon.java` | A real right-side status-bar icon set over `IStatusBarService` via reflection as root (`app_process`), with a watchdog that removes it | Use it for a system-level "agent is acting" indicator, like the camera privacy dot. |
| `drive.sh`, `record.sh` | Event → island workflow plus "wait for Approve" loop; screen recording into a frame contact sheet | Demo-video tooling. |

### Device facts and gotchas already measured (do not re-learn them)

- Status bar is 77 px and the nav bar 84 px. Cross-window blur is **off** (`isCrossWindowBlurEnabled()==false`), so use tinted ink, not real blur.
- `TYPE_APPLICATION_OVERLAY` is clipped below the status bar unless you set `FLAG_LAYOUT_NO_LIMITS`. Even then the status bar draws on top, and taps in that strip go to SystemUI.
- `adb install -r` force-stops the app and removes every overlay. The controller must re-issue its UI state after a restart.
- `su -c` re-joins its arguments, so use `\"` or quote the whole `adb shell "su -c '...'"` string.
- MediaPlayer needed H.264 Constrained Baseline + faststart. Decode thumbnails at reduced size: 3 full-size PNGs pushed PSS to 94 MB.
- The prototype's state is demo-only. No live agent is connected yet.

### Not reusable for this track
Duolingo and Calendar tool logic (they belong to the tools track), the `Lab$Svc` pill (replaced by Stage) and `lesson-test.py`.

---

## 2. Toolchain on the Mac

Current state, checked: `/opt/homebrew/opt/openjdk` is **JDK 27**, `adb` comes from the `android-platform-tools` cask, and there is no SDK or `sdkmanager`. `/usr/bin/java` has no runtime, so always use the brew JDK path as `build.sh` does.

**Option A (recommended): no install.** The pinned build-tools 33 and android.jar already cover everything this track needs, because `InputMethodService`, `AccessibilityService`, `WebView`, `RoleManager` and `BiometricPrompt` are all platform APIs. That means no AndroidX. Risk: a `brew upgrade openjdk` could someday drop `--release 8`. Pin the JDK with `brew pin openjdk`.

**Option B (backup, about 10 to 15 minutes):**
```sh
brew install --cask android-commandlinetools   # sdkmanager, avdmanager, plus d8/r8/lint symlinked into /opt/homebrew/bin
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
yes | sdkmanager --licenses
sdkmanager "platforms;android-33" "build-tools;34.0.0"   # about 150-250 MB (UNVERIFIED size)
```
The cask needs a Java runtime. The page suggests `brew install --cask temurin`; the existing openjdk 27 should work with `JAVA_HOME` set (UNVERIFIED). Source: https://formulae.brew.sh/cask/android-commandlinetools

**Option C (only if we choose Compose):** add a Gradle project. AGP 9.x needs JDK 17 or newer and Gradle 9.1 or newer (https://developer.android.com/build/releases/agp-9-0-0-release-notes). Gradle 9.8 can run on Java 27 (https://docs.gradle.org/current/userguide/compatibility.html), but AGP is only tested up to roughly 9.5. Install `brew install openjdk@21` to be safe. Expect 30 to 60 minutes for the first Gradle and dependency download and 20 to 60 s incremental builds (UNVERIFIED on this Mac).

---

## 3. (a) Custom home launcher

### Technology choice

| | Plain Java Views (current) | Jetpack Compose | WebView canvas inside a native Activity |
|---|---|---|---|
| Build loop | 1.4 s APK build plus install | Gradle, about 20-60 s | **Under 1 s.** Edit HTML, the WebView reloads from a Mac dev server via `adb reverse` |
| Toolchain | Already works | Gradle, AGP, Kotlin, AndroidX | Already works |
| AI-written code quality | Good but verbose (layout in code) | Good | **Best.** Models are strongest at HTML/CSS. |
| Look and motion | Hand-rolled animators | Excellent | CSS transitions and keyframes, already proven in `Tour.java` |
| RAM | Low (24 MB idle) | Medium | About +50 MB (measured 75 MB with WebView) |
| Cold start | Instant | Fast | A few hundred ms the first time Chromium loads (UNVERIFIED on this phone). Home is normally kept resident. |
| Security | Simple | Simple | The JS bridge must be narrow and only bundled pages may load (below) |

**Recommendation: a hybrid with a native shell and a web canvas.**
- The launcher Activity is native Java. It handles the HOME intent, app listing (`LauncherApps`), launching, gestures and wallpaper. Its main content is one WebView showing an "agent feed" UI written in HTML/CSS/vanilla JS (or Preact). No framework build step is required.
- Anything security-critical or always-on stays **native** in the accessibility service: the approval prompt, the rail, the island and the keyboard strip. Model output and web content can never draw the permission UI. That is a clean story for the pitch: "the renderer is untrusted; the native broker decides."
- **Fallback:** if WebView feels slow or janky as home, render the same feed with the Stage View kit. Cards map one-to-one to Glass + text + pill.

### Layout: what makes it feel "agent-first"
- **Bottom: the intent bar** (thumb zone): "Ask or tell AgentOS…" with a mic. Tap opens the command center with the keyboard up.
- **Middle: the Now feed** of live cards.
  - Running tasks show an orb, the current step and progress.
  - Waiting-for-you cards show approvals.
  - Suggested next actions come from notifications (NotificationListenerService) and the calendar.
  - Device cards: Watch4 battery and heart rate, smart plug on/off.
- **Top: a glanceable header** with time, next event, and agent status ("2 tasks running, 1 waiting").
- **Apps are de-emphasised.** No icon grid on home. Swipe up opens type-to-launch search, like Kvaesitso (https://github.com/MM2-0/Kvaesitso, search-focused, Compose, GPL-3.0). Use it only as UX reference; do not copy GPL code.
- **Trust strip:** an always-visible line such as "Agent can: read calendar · tap in Messages (12 min left)". Tap it to see the permission ledger and revoke grants. This puts the security centerpiece on the home screen.

### Gestures (all inside our own window, so no system conflicts)
- Swipe up: app search.
- Swipe down: notification shade, via accessibility `performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)`.
- Long-press empty space: voice.
- Double-tap: lock (`GLOBAL_ACTION_LOCK_SCREEN`).
- Swipe right: memory and timeline. Swipe left: devices.
- Pressing HOME while on home calls `onNewIntent` and resets to the feed.

### Manifest essentials
```xml
<activity android:name=".Home" android:exported="true" android:launchMode="singleTask"
    android:stateNotNeeded="true" android:excludeFromRecents="true"
    android:theme="@android:style/Theme.DeviceDefault.NoActionBar.TranslucentDecor"
    android:windowSoftInputMode="adjustResize">
  <intent-filter>
    <action android:name="android.intent.action.MAIN"/>
    <category android:name="android.intent.category.HOME"/>
    <category android:name="android.intent.category.DEFAULT"/>
  </intent-filter>
</activity>
<queries><intent><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent></queries>
```
Use a theme with `android:windowShowWallpaper=true` and a transparent background to show the system wallpaper.

### Setting it as default via root (no chooser dialog)
```sh
# 1. Record the current home first so we can roll back:
cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
dumpsys role | grep -A2 android.app.role.HOME
# 2. Switch (either works on 13; the role command is the modern path):
cmd role add-role-holder android.app.role.HOME local.agentos
cmd package set-home-activity local.agentos/.Home
# 3. Roll back: same commands with Moto's launcher (probably com.motorola.launcher3, UNVERIFIED; use output of step 1)
```
`add-role-holder [--user USER_ID] ROLE PACKAGE [FLAGS]` is the exact Android 13 help text (AOSP `packages/modules/Permission/.../RoleShellCommand.java`). `set-home-activity` exists in Android 13's `PackageManagerShellCommand`. Whether it updates the role internally is UNVERIFIED, so confirm with `dumpsys role`.

### Launcher gotchas
- **Gesture navigation:** recents and the swipe-up animation stay inside Moto's stock launcher (its Quickstep module). Third-party launchers on gesture navigation have historically had glitchy home and recents animations. Niagara says Android 13 fixed the main bug (https://help.niagaralauncher.app/article/7-gesture-navigation). Test early. If it looks bad on camera, switch the phone to 3-button navigation for the demo.
- Use `LauncherApps.getActivityList(null, user)` plus `startMainActivity` to launch apps. Package visibility needs the `<queries>` block above.
- Widgets (`AppWidgetHost`) are skipped. Not worth the hours.

### WebView bridge security (required)
- Load only bundled assets: `loadUrl("https://appassets.local/index.html")` and serve files from `shouldInterceptRequest`. That is about 15 lines and avoids AndroidX's WebViewAssetLoader. Block other navigation in `shouldOverrideUrlLoading`. Call `setAllowFileAccess(false)`.
- Expose one `@JavascriptInterface` object with a few verbs (`launch(pkg)`, `ask(text)`, `openSheet()`, `revoke(grantId)`). Each verb **requests** an action; the native broker decides.
- The dev-server URL is allowed only in debug builds behind a constant. Remove it for the demo build.

---

## 4. (c) Persistent agent control rail + command center sheet

### Window type: use the accessibility service
The operator agent needs an AccessibilityService anyway (taps, tree reading, global actions). That service can add `TYPE_ACCESSIBILITY_OVERLAY` windows with `getSystemService(WindowManager)` from the service context. No SYSTEM_ALERT_WINDOW permission is needed.

Android 13 z-order from `WindowManagerPolicy.getWindowLayerFromTypeLw` (AOSP android13-release):

| Window | Layer |
|---|---|
| APPLICATION_OVERLAY (prototype) | 11 |
| INPUT_METHOD | 13 |
| STATUS_BAR | 15 |
| NOTIFICATION_SHADE (includes keyguard) | 17 |
| VOICE_INTERACTION | 21 |
| NAVIGATION_BAR | 24 |
| **ACCESSIBILITY_OVERLAY** | **31** |

What this means for us:
- The island can sit **over** the status bar and receive taps. This fixes the problem logged in DEVICE-RESULTS section 6.
- The rail stays visible above the keyboard and the nav bar.
- Android 12+ treats accessibility windows as trusted for touch occlusion, so they are exempt from the "untrusted touch" block. App overlays above about 0.8 opacity block pass-through touches (https://developer.android.com/privacy-and-security/risks/tapjacking).
- **Lock screen caution:** the overlays also draw above the keyguard. Hide the content when `KeyguardManager.isKeyguardLocked()` is true. Listen for `ACTION_SCREEN_OFF` and `ACTION_USER_PRESENT`.

### Rail design
- A 6 dp glowing handle on the right edge, about 25 to 60 percent of the screen height, colored by state: idle (dim), working (lamp), needs you (sky, pulsing), paused (grey).
- **Tap:** it expands into a vertical column of agent chips: an orb per task plus a mini progress bar. Reuse `bubble` snap and `LayoutTransition`.
- **Second tap or the "⌘" chip:** opens the command center sheet.
- **Long-press 600 ms:** stop all agents. Show a red flash and haptic, then the broker revokes every temporary grant.
- **Avoid edge-swipe gestures.** They collide with Android's back gesture. Whether `setSystemGestureExclusionRects` works for overlay windows is UNVERIFIED.
- **Hardware kill switch:** with `flagRequestFilterKeyEvents`, the service receives volume keys. Triple-press volume-down means "stop all", and this works even when the screen UI is compromised. This is a strong security demo.

### Command center sheet
A native frame built from Stage `sheet`, about 85 percent of the screen height. The content can be the same WebView bundle as the launcher.

Tabs:
1. **Now:** running tasks with a streaming step log (from Tour's log) plus Pause, Stop and Take over.
2. **Approvals:** each pending request is rendered from **structured fields, never model prose**: app, action, target, data leaving the device, and model or provider. It shows a screenshot crop with the target element highlighted. Scope buttons: *Once*, *15 min in this app*, *Always for this app*.
3. **Grants:** a live capability ledger with countdowns and one-tap revoke.
4. **Memory:** what the agent remembers, with delete.
5. **Devices:** watch and plug.

A text field and mic at the bottom hold focus. The sheet window needs `FLAG_NOT_FOCUSABLE` cleared while typing.

### Anti-spoofing for the approval prompt
- Only the native broker can create it. LLM text appears only inside a quoted "agent says" area.
- High-risk actions (send to a new contact, money, delete) require `BiometricPrompt`. That is a platform API but needs an Activity, so launch a transparent one.
- **Do not let the agent approve itself.** The broker pauses execution while a prompt is open and drops the agent's gesture or root-tap requests until it closes. Require hold-to-approve (about 500 ms). Whether `dispatchGesture` can hit our own accessibility overlay is UNVERIFIED, so treat it as possible.
- Persistent indicator: the status-bar icon from `StatusIcon.java` plus the edge light whenever the agent is actuating.

---

## 5. Wiring: one APK, one event bus

- **One package `local.agentos`** containing:
  - Home (Activity)
  - `AgentA11y` (AccessibilityService: overlays, strip, operator)
  - `AgentKbd` (optional InputMethodService)
  - Tools server
  - the root broker launched via `app_process`, as already done
- One process means shared state and one build. Privilege separation stays at the root-broker boundary, which is already implemented with a UID check.
- **Event bus:** extend `Tools.java`:
  - `ui/event {task, title, sub, pct, state}` drives the island, rail and feed.
  - `ui/approve {request}` **blocks** (long-poll) until the user decides, then returns `{decision, scope, expiry}`.
  - The agent runtime (on the Mac via `adb forward`, or on the phone) calls these with the bearer token. The launcher WebView gets updates through `evaluateJavascript`.
- Enable the services as root, appending to the list instead of overwriting it:
```sh
settings put secure enabled_accessibility_services "$(settings get secure enabled_accessibility_services):local.agentos/.AgentA11y"
settings put secure accessibility_enabled 1
appops set local.agentos ACCESS_RESTRICTED_SETTINGS allow   # Android 13 restricted-setting gate for sideloads (user-reported fix)
ime enable local.agentos/.AgentKbd && ime set local.agentos/.AgentKbd   # only if the full IME is built
```

---

## 6. (b) AI keyboard

### Two ways to get "AI in every text field"

**Path 1 (recommended first): AI strip over the user's existing keyboard.** It uses API 33's accessibility `InputMethod`.
- Declare `android:accessibilityFlags="flagInputMethodEditor|flagRetrieveInteractiveWindows"` and override `AccessibilityService.onCreateInputMethod()`.
- `InputMethod.onStartInput(EditorInfo, restarting)` provides the field type and the package of the focused field.
- `getCurrentInputConnection()` returns an `AccessibilityInputConnection` with `getSurroundingText`, `commitText`, `deleteSurroundingText`, `setSelection`, `performEditorAction` and `performContextMenuAction` (verified in AOSP `core/java/android/accessibilityservice/InputMethod.java`, android13-release).
- Find the keyboard's position with `getWindows()` and pick the window of `TYPE_INPUT_METHOD`. Dock a 44 dp accessibility-overlay strip just above it.
- Chips: **Reply ×3**, **Rewrite** (shorter / friendlier / formal), **Fix**, **Translate**, **Ask…**.
- Upside: the user keeps Gboard's swipe typing and autocorrect. About 200 to 300 lines.
- UNVERIFIED: that Moto's Android 13 binds the accessibility input method while Gboard is active, which the API is designed for. Test this in the first hour.
- **Fallbacks:**
  - `AccessibilityNodeInfo.ACTION_SET_TEXT` on the focused node. It replaces the whole field and works on standard EditText.
  - Clipboard plus `ACTION_PASTE`.

**Path 2 (optional, +5 to 7 hours): a real `InputMethodService` "AgentOS Keyboard".**
- Minimal QWERTY: a native View grid with shift, symbols, backspace and enter. `KeyboardView` is deprecated since API 29 but still in android.jar 33.
- An AI row on top and a globe key that calls `switchToPreviousInputMethod()` to return to Gboard.
- Manifest: a service with `android.permission.BIND_INPUT_METHOD`, the `android.view.InputMethod` action, and meta-data `android.view.im` pointing at `res/xml/method.xml` (https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method).
- Ghost-text completion: `setComposingText` accepts styled `Spanned` text per the AOSP doc ("The composing text with styles if necessary"). A grey `ForegroundColorSpan` could show the inline suggestion, with Tab or swipe to accept. Rendering varies by editor (Compose, WebView, Flutter) and is UNVERIFIED, so demo it in our own test app.

### Context sources
1. **Inside the field:**
   - At focus, read `EditorInfo.getInitialTextBeforeCursor(n, 0)` / `getInitialSelectedText` / `getInitialTextAfterCursor` (API 30+). No inter-process call is needed.
   - After that, keep a local copy updated from `onUpdateSelection` and our own commits.
   - Call `getSurroundingText(before, after, 0)` only when the local copy is invalid.
   - AOSP doc: these calls "trigger an IPC round-trip… Assume this method consumes a lot of time"; the editor "is given a couple seconds" and may return null or fewer characters.
2. **Around the field:** the accessibility tree of the active window, for example the last 10 chat bubbles in a messaging app. Prune it to text nodes and cap it at about 2 KB.
3. **Field metadata:** `inputType`, `imeOptions`, `hintText`, `packageName`, `hintLocales`.

### Latency strategy
- **Prefetch on focus:** when a messaging field gets focus, generate 3 short replies from the visible conversation right away. They are usually ready before the user looks down, so they feel instant.
- **Explicit actions stream:** use SSE and render tokens into the chip or a preview card as they arrive. Show a skeleton shimmer within 100 ms.
- **Passive suggestions:** debounce 300 ms after a typing pause or at a word boundary, keep `max_tokens` small (about 20), and route to the fastest or local model.
- **Cancel stale requests** with a generation counter plus `HttpURLConnection.disconnect()`.
- Cache by (package, hash of the last 200 characters).
- Keep a fixed system prompt so provider prompt caching applies.
- Keep all InputConnection and network calls off the UI thread.

### Privacy rules (part of the security pitch; enforce in code, not just policy)
- **Hard off:**
  - password variations (`TYPE_TEXT_VARIATION_PASSWORD`, `VISIBLE_PASSWORD`, `WEB_PASSWORD`, `TYPE_NUMBER_VARIATION_PASSWORD`)
  - `IME_FLAG_NO_PERSONALIZED_LEARNING` (incognito fields)
  - a package denylist: banking, authenticators, the Magisk app
- **Data minimisation:**
  - Cloud is used only on an explicit chip tap; passive mode is local-only or off.
  - Redact emails, phone numbers, card-like digit runs and OTPs with regex before anything leaves the device.
  - A "context chip" shows exactly what will be sent ("Messages · 6 lines · redacted 1 number").
- Write every outbound request to the audit ledger shown in the Grants tab.

---

## 7. Recommended plan and fallback

**Plan (in order):**
1. Bootstrap `local.agentos` by copying `build.sh` and the Stage kit. Build the AccessibilityService that hosts the island, rail and edge as `TYPE_ACCESSIBILITY_OVERLAY`. First check on device: the island renders over the status bar and is tappable.
2. Event bus (`ui/event`, blocking `ui/approve`) plus the native approval sheet with scopes and hold-to-approve. Volume-key kill switch.
3. Launcher: native HOME Activity plus a WebView feed served from the Vite dev server over `adb reverse tcp:5173 tcp:5173`, then bundled for the demo. Set it default via root.
4. AI strip over Gboard (accessibility InputMethod) with Reply, Rewrite and Fix, plus prefetch.
5. Command center tabs (Now, Approvals, Grants first).
6. Stretch: a full IME, ghost text, more gestures.

**Fallbacks:**
- Accessibility overlay misbehaves: go back to the proven `TYPE_APPLICATION_OVERLAY` and accept that the island sits below the status bar.
- Accessibility InputMethod does not bind: use `ACTION_SET_TEXT` or paste, or move straight to Path 2 (own IME).
- WebView home is janky: render the feed with native Views.
- Gesture navigation looks bad with a third-party home: use 3-button navigation for the demo.

## 8. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Accessibility InputMethod does not attach alongside Gboard on Moto | Medium (UNVERIFIED) | Test in hour 1. Fallbacks above. |
| Agent's injected gestures press our own Approve | Low-medium (UNVERIFIED) | Broker pauses actuation during prompts, hold-to-approve, biometric for high risk |
| Recents and gesture animation glitches with a custom HOME | Medium | 3-button nav, or demo from the launcher without recents |
| Accessibility service killed or disabled (crash, restricted settings) | Medium | Root re-enable script, `appops ACCESS_RESTRICTED_SETTINGS allow`, keep the service lean |
| RAM: WebView (+50 MB) plus local model on 3.5 GB | Medium | One WebView instance only, shared by home and sheet. Downsample images. Watch PSS. |
| Overlays visible on the lock screen leak task info | High if ignored | Hide on keyguard |
| JS bridge abuse | Low if bridge is narrow | Bundled assets only, request-only verbs |
| `brew upgrade` changes the JDK under `build.sh` | Low | `brew pin openjdk` |
| Overwriting `enabled_accessibility_services` disables other services | Medium | Append, as shown |

## 9. Estimate (focused hours; parallel agents compress wall-clock)

| Item | Hours |
|---|---|
| Package skeleton, merge Stage kit, accessibility service host, switch window type | 2-3 |
| Event bus (`ui/event`, blocking `ui/approve`) + agent-runtime glue | 2-3 |
| Rail (states, chips, long-press stop, volume kill switch) | 3-4 |
| Approval sheet (structured, scopes, hold, biometric Activity, ledger) | 4-5 |
| Launcher (HOME, app search, gestures, WebView feed, bridge, root default) | 6-8 |
| Command center tabs (Now, Approvals, Grants; Memory and Devices as stubs) | 3-4 |
| AI strip over Gboard (context, chips, streaming, prefetch, privacy rules) | 4-5 |
| Demo polish, recording, rollback scripts | 2-3 |
| **Total** | **26-35** |
| Optional: full IME + ghost text | +5-7 |
| Optional: Compose/Gradle setup | +1 setup, then slower loop |

## Sources
- AOSP Android 13 window layers: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/policy/WindowManagerPolicy.java
- AOSP accessibility InputMethod (API 33): https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/java/android/accessibilityservice/InputMethod.java ; docs https://developer.android.com/reference/android/accessibilityservice/InputMethod
- AOSP InputConnection (IPC cost, styled composing text): https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/java/android/view/inputmethod/InputConnection.java
- EditorInfo (`IME_FLAG_NO_PERSONALIZED_LEARNING`, initial surrounding text): https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/java/android/view/inputmethod/EditorInfo.java
- Creating an IME: https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method
- Role shell command: https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android13-release/service/java/com/android/role/RoleShellCommand.java ; RoleManager https://developer.android.com/reference/android/app/role/RoleManager
- Tapjacking and untrusted touches: https://developer.android.com/privacy-and-security/risks/tapjacking
- Restricted settings workaround (user-reported): https://gitlab.com/fdroid/fdroidclient/-/issues/2680
- Third-party launcher gestures: https://help.niagaralauncher.app/article/7-gesture-navigation
- Kvaesitso (search-first launcher reference): https://github.com/MM2-0/Kvaesitso
- Homebrew cmdline-tools: https://formulae.brew.sh/cask/android-commandlinetools ; AGP 9 requirements: https://developer.android.com/build/releases/agp-9-0-0-release-notes ; Gradle/Java matrix: https://docs.gradle.org/current/userguide/compatibility.html
