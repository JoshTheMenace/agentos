# AgentOS deck: speaker notes and sources (DRAFT, 2026-10-28)

Deck: `pitch/deck/index.html` (open in a browser, press **P** to present, arrows to move) and `pitch/deck/AgentOS-deck.pdf`.
Re-export: `"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --no-pdf-header-footer --print-to-pdf=pitch/deck/AgentOS-deck.pdf file://$PWD/pitch/deck/index.html`

Status key: **MEASURED** = a number from a results file in this repo, taken on the real phone or in our harness. **REPO** = checked in the repo or git. **SECONDARY** = press/aggregator, say with attribution. **UNVERIFIED** = soften or cut if challenged. Screenshot sources are in `SOURCES.md`.

The founder is writing the live demo order; these notes only cover the slides.

---

## Slide 1 · The problem (~35 s)

Suggested words: "Everyone's phone has an assistant, and it still can't do anything for you. The new agents that *can* act in your apps have a different problem: they read whatever is on the screen, so a stranger's text becomes a command. Researchers tested five open-source phone agents against seven attacks this July, and every one fell to at least six. The people an agent would help most, someone who can't easily tap a touchscreen, are the ones a hijacked agent hurts most. We think the fix is a gate in the OS, not a smarter model."

| On the slide | Source | Status |
|---|---|---|
| 5 phone agents · 7 attacks · every agent fell to at least 6 | arXiv 2607.00333 (Zhang et al., Jul 2026), via CSA research note; `research/08-pitch-utah.md` §4, `pitch/claims-check.md` B1 | SECONDARY. Read the paper's results table before saying "5", "7", "6" out loud. |
| "6 apps", flagship phone agent, partner apps only, cloud-only | Gemini task automation on Galaxy S26: 6 partner apps, hands back before checkout (9to5Google 2026-03-12), `agentd/research/DEMO-WOW.md` "Where we can visibly beat them" | SECONDARY, marked "reported" on the slide. Say "today"; it may change fast. "No rules outside the model" is our characterization, not a quote. |
| Screenshot: attack message flagged | `pitch/rehearsal/07-security-alert.png`: Pigeon, **our own test app**, Prompt Guard 2 on-device flag card | REPO. Always say it is our test app, no real contacts. |

Do not say: "unhackable", "we beat the paper". Say "we block these attack types in our test setup".

## Slide 2 · The solution, running today (~45 s)

Suggested words: "This is AgentOS on a budget Moto G. Home shows what agents are doing and what the OS stopped. Risky steps get a card: a tap for most things, a fingerprint for payments and passwords. Ask 'any trackers?' in a real app, say 'block them', and a kernel firewall blocks them by company while the app keeps working. Put it in airplane mode and the phone's own small model keeps going. Speed is a feature: this week median task time went from about 30 seconds to about 11 on the same phone. Before this week we had an island prototype and Codex running on the phone. Everything else you see was built in three days, 118 commits, by one founder directing AI coding agents."

| On the slide | Source | Status |
|---|---|---|
| ~30 s → ~11 s median task time, ~2.7× | `agentd/bench/RESULTS.md` round 1 table: median ~30 s → ~11 s, 15 of 16 runs passed; real apps (Clock, Chrome, Calculator, Pigeon), request to `turn.done`, lease card off | MEASURED (10-27). Round 2's prompt tweaks got alarm to 6.0 s but were "not a production default" at the time of that file; commit c3474c7 later made fast mode + finish the default. Quote 7.5 s, the round-1 table number. |
| 7.5 s "Set an alarm for 7 AM" | same file, same table (33.0 s → 7.5 s, 8 → 2 model calls) | MEASURED. Typed request, not voice: voice adds its own time (not measured yet, see slide 3 notes). |
| 118 commits, Oct 26 to Oct 28 | `git log` on this branch at 02:50 on 10-28: 119 commits incl. the initial commit (1 on 10-26, 67 on 10-27, 51 on 10-28) | REPO. Recount before 5 PM: `git log --oneline | wc -l`. |
| 19.8k lines of Java and JavaScript | `wc -l` on 10-28 over `*.java`, `*.js`, `*.kt` in `android/ agentd/ keyboard/ launcher/ web/`, excluding node_modules and the build copy of web/app.js: 20,652 − 845 = 19,807 | REPO, our count. Not all of it was written this week (see "before"). |
| Before this week: island overlay prototype, SystemUI experiments, Codex running on the phone | `pitch/claims-check.md` C5; `research/05`, `research/00` | REPO. Don't name the third-party app used in the Frida experiments. |
| Built this week list | git log 10-26 21:47 → 10-28 (`git log --format='%ad %s'`), STATUS.md "Done" | REPO. All on the phone; the "watch" word on slide 3 is a built Wear OS app tested on the phone as a stand-in, not yet paired to a real watch (STATUS "Waiting on the user"). |
| "Budget Android phone" | Moto G 5G (2023), Android 13, rooted (`research/00-device-baseline.md`) | REPO. Price not checked: don't say "$150" (claims-check C2). |
| Screenshots | home, approval card, blockers: `launcher/shots/polish-night-2/` (10-28 02:40); offline: `pitch/rehearsal/11-local-airplane.png` (airplane mode, on-device Qwen3.5-0.8B) | REPO. The offline shot also shows a denied firewall approval and a cancelled fingerprint: that is a real log, not an error. |

Honest framing if asked: root is the lab (slide 5). The phone is one device; nothing is in a store.

## Slide 3 · Hard tech (~45 s)

Suggested words: "The model is the brains, and we assume it can be fooled. So it never gets a shell or root; it only sees phone tools. Everything it proposes goes through one gate, an Android app that owns the accessibility layer, the keystore and the kernel firewall. Three rules do most of the work. Rule of Two: untrusted text plus private data plus an outward action means a person decides, and a code read from the screen can never be typed out. Prompt Guard 2 runs on the phone and labels suspicious text before the model sees it. And a fingerprint signs the exact action, app, button, text, so the log can be checked offline. The same gate lets us swap brains: Codex, Claude, or a 0.8-billion-parameter model on the phone that now drives 26 of 30 real tasks and takes over automatically when the network drops."

| On the slide | Source | Status |
|---|---|---|
| Models get no shell, no root; only phone tools ("16 phone tools") | `ARCHITECTURE.md` rules 1–2; commit 2a570ca "16-tool core + 12 owner-toggled plugins" | REPO (design and config). Gotcha from `agentd/README.md`: Codex still sees some non-shell tools; its own approval requests are declined. |
| Rule of Two; screen codes never typed (P3) | `ARCHITECTURE.md` rule 3; `android/README.md` e2e (OTP block, P3); `pitch/claims-check.md` C10 | REPO, verified on phone for the OTP case. |
| 6/6 injections flagged, 0 false flags | `android/guard-tests/README.md` table (10-27): agentd regex after 6/6 precision 1.00 recall 1.00; on-device Prompt Guard 2 alone 4/4 precision, 4/6 recall; "either guard" 6/6. 31-case set per commit 0c10770 | MEASURED on a small set. Say "on our test set"; the 6/6 is both guards together. Prompt Guard 2 is the 22M int8 ONNX model (`android/README.md`). |
| 1.86 s median auto-reviewer decision | `agentd/reviewer-results.md`: 2,273.5 ms → 1,859 ms median on 18 actions, Mac-side run; live cases 27/28 | MEASURED on the Mac, not the phone. On the phone the auto-approve wait is 2.5–4.6 s (`agentd/bench/RESULTS.md` header). |
| Fingerprint for payments and passwords always | STATUS.md decisions (fingerprint only for the most sensitive); `agentd/reviewer-results.md` "payment/password fingerprint rules" | REPO. |
| Fingerprint signs the exact action; audit hash chain checkable offline | `android/README.md` (Keystore EC key, auth per use, BIOMETRIC_STRONG; `chain_ok`, Mac re-check); `pitch/claims-check.md` C8/C12 | REPO. **C8 was PENDING on 10-26** (full approved action end to end). STATUS 10-27 rehearsal lists "fingerprint card + system prompt" as passed but "needs the presenter: the finger". Confirm one approved action ran before saying "signs" in present tense; otherwise "is built to sign". Hardware backing (TEE/StrongBox) not checked: say "Android Keystore", not "secure hardware" (C9). The prompt text is drawn by our app (known limit, README). |
| 38/38 · 12/12 cross-app memory | `agentd/memory/eval/RESULTS.md`: end-to-end Codex 22/38 → 38/38 tuning, 12/12 held-out; 1.2 model calls per question vs 2.6; re-run 50/50 | MEASURED, synthetic multi-app world on the Mac with the real model. On the phone with real data: 2.2–4.1 s per question. |
| 26/30 offline tasks (was 0/30), automatic fallback | `agentd/runs/2026-10-27-local-model.md` total row 26/30 (26/27 supported), router 30/30; `agentd/runs/2026-10-28-offline-fallback.md`: warm local ask 2.3 s, worker 3.4 s, cold 24.3 s (llama-server stopped) | MEASURED on the phone. Qwen3.5-0.8B Q4_0 via llama.cpp. Cold start is slow: start llama-server before the demo. |
| 476 tracker domains; kernel firewall by app ID; SNI watcher names the company | `android/README.md` line ~193 (`res/raw/trackers.txt`, 476 domains, Exodus signatures); iptables owner-uid rules | REPO. Firewall rules and counters reset on reboot (README limit). |
| "Switch brains in one tap" | `launcher/shots/polish-night-2/after-center.png` model row Codex · Claude · Local; Claude provider works on the phone (commit 5bbe0da "Claude on phone fixed") | REPO. Claude not benchmarked live: say "pluggable" if pressed (claims E6). |
| Keyboard suggestions 7.3 s → 3.5 s (not on the slide; good Q&A number) | `keyboard/LATENCY.md` After table | MEASURED on the phone, 10-28 night. |
| Voice latency | `spikes/voice/LATENCY.md` has definitions only, **no measured results yet** ("Baseline pending the shared phone lock"). The older spike number (1.9 s plain question → reply, `spikes/voice/README.md`) was via a Mac relay. | UNVERIFIED for the current build. Kept off the slides. |
| Proactive cards precision (not on the slide) | `agentd/research/PROACTIVE.md`: 99–100 % precision, 92–95 % recall over 5 runs, 152 threads; blind set 100 % / 91–97 % | MEASURED on labeled threads (synthetic + blind set). |

Footer line claim: "the model's plan can be fooled; an outward action without your approval cannot happen". That is the precise claim from `pitch/deck.md` slide 3. Root is above everything (known limit): a rooted attacker is out of scope.

## Slide 4 · Why it matters for Utah (~35 s)

Suggested words: "Governor Cox's Pro-Human AI values say, word for word, AI must be human-guided. That sentence is our permission system. Who needs it first? Utahns who can't easily use a touchscreen: the Division of Services for People with Disabilities serves over seven thousand people, and the state's assistive technology program at USU already runs demo and loan libraries. Then older adults, and families who want a grandparent to say it and have it done, with anything risky waiting for a fingerprint. It runs on the phone people already own and keeps working with no signal, which matters outside the Wasatch Front. We're a Utah LLC with a Utah-based founder, so UTIF, SBIR through Nucleus Grow and the Nucleus Fund are open to us now."

| On the slide | Source | Status |
|---|---|---|
| "AI must be human-guided" (Gov. Cox, Pro-Human AI initiative) | prohumanai.utah.gov; `research/08` §2; claims D1 | VERIFIED (state site). Quote exactly. |
| 7,000+ Utahns served by DSPD | DSPD FY2024 Annual Report PDF; claims D3 | VERIFIED (primary). |
| Utah Assistive Technology Program (USU), Logan; demo and loan libraries | idrpp.usu.edu/uatp; claims D6 | VERIFIED. Say **"target pilot channel"**, never "partner". No agreement exists. |
| ~12 % of Utahns 65+; 1 in 4 by 2065 | KSL; KPCW (Gardner Institute projections); claims D5 | SECONDARY. Marked "projection" on the slide. |
| Utah LLC, Utah-based founder; eligible for UTIF, SBIR, Nucleus Fund | founder (claims E2); UTIF rules are aggregator figures | VERIFIED (founder). Have the LLC name and date ready. Say "apply for", no UTIF dollar amount. |
| EO 2026-08: state agencies adopting AI with safeguards | GovTech; claims D2 | SECONDARY. One line only. |
| Utah's device-management industry | SiliconANGLE on Ivanti; claims D11 | SECONDARY, no company named on the slide. Never imply a relationship. |
| Nucleus at The Point, Draper; Nucleus Grow free SBIR help | startup.utah.gov; Deseret News; claims D8 | SECONDARY. |
| Utah Cyber Center (SLC pin) | cybercenter.utah.gov; SB 123 (2026); `research/08` §2 | SECONDARY; context only. |
| "Works with no signal" (rural pin) | offline fallback, see slide 3 | MEASURED on the phone (airplane mode). Rural coverage framing is ours, not a study. |
| Map | hand-drawn outline (rectangle with the NE notch); pins are approximate | n/a |

## Slide 5 · Path to reality and the $10K (~40 s)

Suggested words: "Root is our lab, not our product. The shippable paths are a managed-device app, the way device-management tools already work, and Android's AppFunctions. The ten thousand buys the phones to make that move, a ten-person pilot here in Utah through an assistive-tech channel, and an outside red team so the safety claim isn't just ours. Day 30: off root, open threat model, SBIR topic picked with Nucleus Grow. Day 60: pilot enrolled, red-team done. Day 90: results published and a Phase I proposal ready for the first open solicitation. What we need besides money: an intro to a university co-PI and to the assistive technology program at USU."

| On the slide | Source | Status |
|---|---|---|
| $2,500 devices · $3,000 pilot · $2,500 red team · $1,000 model credits · $1,000 registrations and legal | `pitch/deck.md` slide 5, `pitch/one-pager.md`, `research/08` §6 (adjusted because the LLC already exists); claims E3 | Our estimate. Phone prices ($150–250) and $150 stipends are assumptions. SAM.gov registration is free. |
| "8 phones" | 6 low-cost Android phones + 2 Pixels (one-pager) | Plan. |
| Managed-device app, Android AppFunctions as the off-root path | Android Developers Blog (AppFunctions, Feb 2026); claims E5 | SECONDARY; **none built yet**, it is the plan. |
| SBIR "proposal ready", not "submitted", at day 90 | SBIR reauthorized 2026-04-13 through FY2031 (Mondaq), agencies restarting cycles; claims D9/D10 | SECONDARY. Check sbir.gov before saying the date. |
| Independent red team | plan | Not started. |
| One founder plus AI coding agents | claims E1 | VERIFIED. |
| Ask: university co-PI (U of U or USU), UATP intro | one-pager "People needed" | Ask, not a claim. |

---

## Q&A crib (from `pitch/deck.md`, still current)

- **Root isn't shippable.** Agreed: root is the lab. Production paths: device-owner (managed-device) app, a privileged app on a re-locked custom build, Android AppFunctions.
- **Is software hard tech?** The brief lists cyber and AI infrastructure. We work in the keystore, the accessibility layer and the kernel firewall, and we stop a published attack class on the device.
- **One founder?** Yes, plus AI agents building under the founder's review. The $10K adds a co-PI, an assistive-tech specialist and an outside red team.
- **Which AI do you use?** Demo: the founder's ChatGPT plan through OpenAI's Codex app-server, plus a local model; Claude plugs in through an API key. Product: API keys or local models, not personal subscriptions.
- **Known limits to volunteer:** root is above everything; the approval prompt text is drawn by our app (no Protected Confirmation); firewall rules reset on reboot; `task.start` isn't human-approved.

## Numbers deliberately left off the slides
- Voice latency (no measured results in `spikes/voice/LATENCY.md` yet).
- "$150 phone" (unverified price).
- DSPD technology pilot "$5K per person" (aggregator, unverified).
- Humane / Rabbit funding figures (conflicting sources).
- B2 "2 % opacity text read 18 of 20 times" (inconsistent in our notes).
