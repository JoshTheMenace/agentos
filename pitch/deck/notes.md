# AgentOS deck: speaker notes and sources (v2, 2026-10-28)

Deck: `pitch/deck/index.html` (open in a browser, press **P** to present, arrows to move), `pitch/deck/AgentOS-deck.pdf`, and one PNG per slide in `pitch/deck/slides/`. The wordier first draft is kept as `pitch/deck/index-v1.html`.

**v2 rule:** each slide is one headline, 2–3 big numbers (or one line), and screenshots. Everything that used to be on the slides is now in these notes, under "Say it, not on the slide". Judges listen; they don't read.

Re-export (PDF, then PNGs):
```
CH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
"$CH" --headless=new --no-pdf-header-footer --print-to-pdf=$PWD/pitch/deck/AgentOS-deck.pdf file://$PWD/pitch/deck/index.html
for n in 1 2 3 4 5; do "$CH" --headless=new --hide-scrollbars --window-size=1600,900 --screenshot=pitch/deck/slides/slide-$n.png "file://$PWD/pitch/deck/index.html?slide=$n"; done
```

Status key: **MEASURED** = a number from a results file in this repo, taken on the real phone or in our harness. **REPO** = checked in the repo or git. **SECONDARY** = press/aggregator, say with attribution. **UNVERIFIED** = soften or cut if challenged. Screenshot sources are in `SOURCES.md`.

The founder is writing the live demo order; these notes only cover the slides.

---

## Slide 1 · The problem (~35 s)

On the slide: **"Your assistant still can't do anything for you."** · 6 apps · 45 s · 6 of 7 · voice-mode screenshot ("AgentOS: say it, it does it.").

Say it, not on the slide:
- "You can ask it the weather. You can't say 'tell Mom I'm 10 minutes late' and trust it to go do that: in your real apps, by voice, on the phone you already own."
- 6 apps: today's flagship phone agent works in a handful of partner apps, cloud-only, and hands back before checkout (reported).
- 45 s: one reviewer's time for that agent to order one ride, about 10 s by hand. Slow enough that people stop using it (reported).
- 6 of 7: the open-source phone agents tested in July each fell to at least 6 of 7 published attacks (arXiv 2607.00333).
- The screenshot is AgentOS voice mode on our Moto G, Oct 27.

Suggested words: "Everyone's phone has an assistant, and it still can't do anything for you. You can ask it the weather. You can't say 'tell Mom I'm ten minutes late' and trust it to go do that in your real apps. The first phone agents are arriving: a handful of partner apps, cloud-only, and slow enough that one reviewer timed a single ride order at 45 seconds. And to let an agent act, it has to be safe: the open-source phone agents tested this July each fell to at least six of seven published attacks. So we built the phone OS we wanted: it does the task, fast, in any app, and the safety lives in the OS, not in the model."

| On the slide | Source | Status |
|---|---|---|
| "6 apps", flagship phone agent, partner apps only, cloud-only, hands back before checkout | Gemini task automation on Galaxy S26 (9to5Google 2026-03-12); `agentd/research/DEMO-WOW.md` "Where we can visibly beat them" | SECONDARY, tagged "reported" on the slide. Say "today"; it may change fast. |
| "~45 s" to order one ride, about 10 s by hand | one hands-on review (gimmie.ai, Aug 2026), linked from the sources list in `agentd/research/DEMO-WOW.md` | SECONDARY, a single review, tagged "reported". If challenged: "one published hands-on timed it at about 45 seconds". "People stop using it" is that reviewer's conclusion, not a study. |
| Open-source phone agents each fell to at least 6 of 7 attacks | arXiv 2607.00333 (Zhang et al., Jul 2026), via CSA research note; `research/08-pitch-utah.md` §4, `pitch/claims-check.md` B1 | SECONDARY. Read the paper's results table before saying "5", "7", "6" out loud. |
| Screenshot: voice mode | `launcher/shots/voice.png` (Oct 27) | REPO. |

Do not say: "unhackable", "we beat the paper". Say "we block these attack types in our test setup".

## Slide 2 · The solution, running today (~45 s)

On the slide: **"AgentOS: the Siri we should have."** · 30 s → 11 s · 1.7 s · 157 commits · four screenshots (home, approval card, blockers, offline).

Say it, not on the slide:
- Kicker: an AI-native phone OS, built security-first, running today on a budget Android phone (Moto G 5G 2023, Android 13, rooted lab phone).
- 30 s → 11 s: median task time before → after this week, same phone, real apps. "Set an alarm for 7 AM" is 7.5 s request to done.
- 1.7 s: by voice, end of your sentence to the task starting. "Done" is spoken about 1 s after it finishes.
- 157 commits: Oct 26 to Oct 28, timestamped in git.
- Home shows what agents did, and what the OS stopped.
- Risky steps: a tap, or your fingerprint. The exact text is shown.
- "Any trackers?" "Block them." Per app, by company name, in the kernel.
- No signal? The phone's own model keeps working.
- Before this week: island overlay prototype, SystemUI experiments, Codex running on the phone.
- Built this week (Oct 26–28): the gate (leases, Rule of Two, fingerprint-gated approvals, audit chain) · agentd with Codex, Claude or an on-device model · live voice · AI keyboard · per-app firewall · cross-app memory · proactive cards · app mods and micro-apps · scheduled tasks on a locked phone · automatic offline fallback.

Suggested words: "This is AgentOS on a budget Moto G. Home shows what agents are doing and what the OS stopped. Risky steps get a card: a tap for most things, a fingerprint for payments and passwords. Ask 'any trackers?' in a real app, say 'block them', and a kernel firewall blocks them by company while the app keeps working. Put it in airplane mode and the phone's own small model keeps going. Speed is a feature: this week median task time went from about 30 seconds to about 11 on the same phone. Before this week we had an island prototype and Codex running on the phone. Everything else you see was built in three days, over a hundred commits, by one founder directing AI coding agents. By voice, the task is under way under two seconds after you stop talking."

| On the slide | Source | Status |
|---|---|---|
| ~30 s → ~11 s median task time, ~2.7× | `agentd/bench/RESULTS.md` round 1 table: median ~30 s → ~11 s, 15 of 16 runs passed; real apps (Clock, Chrome, Calculator, Pigeon), request to `turn.done`, lease card off | MEASURED (10-27). Round 2's prompt tweaks got alarm to 6.0 s but were "not a production default" at the time of that file; commit c3474c7 later made fast mode + finish the default. Quote 7.5 s, the round-1 table number. |
| 7.5 s "Set an alarm for 7 AM" | same file, same table (33.0 s → 7.5 s, 8 → 2 model calls) | MEASURED. Typed request, not voice: voice adds its own time (not measured yet, see slide 3 notes). |
| 1.7 s by voice, end of speech → task starting; "done" spoken ~1 s after | `spikes/voice/results.jsonl` (7 runs, 10-28; definitions in `spikes/voice/LATENCY.md`): speech end → `task.update/running` median 1,686 ms (range 1,262–2,415); task done → spoken completion median 927 ms (792–1,280); speech end → first spoken acknowledgement median 2,239 ms; session ready 8–9 ms warm, 1.1–1.7 s cold | MEASURED on the phone's own Codex app-server with a **synthetic voice** (macOS `say`, one battery-level question, no app driving, no approvals). Say "about two seconds" if rounding up. |
| 157 commits, Oct 26 to Oct 28 | `git rev-list --count HEAD` = 157 at 09:20 on 10-28 (was 123 at 03:10) on branch `t3/agent-native-android-hackathon`. The first commit is dated 2026-10-26 21:47, so `git rev-list --count --since=2026-10-26 HEAD` gives the same number. Includes docs and pitch commits. | REPO. The number grows during the day: re-run the command and edit slide 2 before the final export. Code size if asked: ~19.8k lines of Java/JS (`wc -l` over `android agentd keyboard launcher web`, excluding node_modules and the build copy of web/app.js). |
| Before this week: island overlay prototype, SystemUI experiments, Codex running on the phone | `pitch/claims-check.md` C5; `research/05`, `research/00` | REPO. Don't name the third-party app used in the Frida experiments. |
| Built this week list | git log 10-26 21:47 → 10-28 (`git log --format='%ad %s'`), STATUS.md "Done" | REPO. All on the phone; the "watch" word on slide 3 is a built Wear OS app tested on the phone as a stand-in, not yet paired to a real watch (STATUS "Waiting on the user"). |
| "Budget Android phone" | Moto G 5G (2023), Android 13, rooted (`research/00-device-baseline.md`) | REPO. Price not checked: don't say "$150" (claims-check C2). |
| Screenshots | home, approval card, blockers: `launcher/shots/polish-night-2/` (10-28 02:40); offline: `pitch/rehearsal/11-local-airplane.png` (airplane mode, on-device Qwen3.5-0.8B) | REPO. The offline shot also shows a denied firewall approval and a cancelled fingerprint: that is a real log, not an error. |

Honest framing if asked: root is the lab (slide 5). The phone is one device; nothing is in a store.

## Slide 3 · Hard tech (~45 s)

On the slide: **"The model proposes. The OS decides."** · diagram You → Model → [models stop here] → The gate → Your apps · 6 of 6 · 26 of 30 · 476 · two screenshots (fingerprint card, planted attack caught).

Say it, not on the slide:
- The riskiest steps require your fingerprint.
- You reach it by voice, the AI keyboard, the home screen, or a watch.
- The model (agentd, "the brains"): Codex, Claude, or Qwen 0.8B on the phone. Swappable. No shell, no root. It sees only 16 phone tools.
- The gate (`local.agentos`): leases · Rule of Two · on-device Prompt Guard 2 · approvals bound to the exact action, fingerprint for payments and passwords · hash-chained audit · per-app firewall. Your apps stay unmodified.
- Rule of Two: untrusted text + private data + outward action = a human decides. Codes read from the screen can never be typed out.
- 6 of 6: injections flagged on our test set by Prompt Guard 2 (22M, int8 ONNX) running on the phone, 0 false flags.
- 1.86 s: median auto-reviewer decision on a low-risk step (Mac-side measurement; see table).
- 38/38 · 12/12: cross-app memory, "reply that I'm late" finds the right person and app; held-out set 12/12, one model call instead of 2 to 3.
- 26 of 30: Qwen3.5-0.8B on the phone drives real tasks offline (was 0/30). It switches in automatically when the network drops.
- 476 tracker domains: kernel firewall by app ID. The SNI watcher names the company; one tap blocks it, the app keeps working.
- Screenshots: native approval card with the "Untrusted content" label; a planted attack in our own test app, flagged on the phone.
- Footer claim from v1 (precise wording): the model's plan can be fooled; an outward action without your approval cannot happen.

Suggested words: "The model is the brains, and we assume it can be fooled. So it never gets a shell or root; it only sees phone tools. Everything it proposes goes through one gate, an Android app that owns the accessibility layer, the keystore and the kernel firewall. Three rules do most of the work. Rule of Two: untrusted text plus private data plus an outward action means a person decides, and a code read from the screen can never be typed out. Prompt Guard 2 runs on the phone and labels suspicious text before the model sees it. And every approval is bound to the exact action, app, button, text; payments and saved passwords require your fingerprint, and every decision lands in a hash-chained log. The same gate lets us swap brains: Codex, Claude, or a 0.8-billion-parameter model on the phone that now drives 26 of 30 real tasks and takes over automatically when the network drops."

| On the slide | Source | Status |
|---|---|---|
| Models get no shell, no root; only phone tools ("16 phone tools") | `ARCHITECTURE.md` rules 1–2; commit 2a570ca "16-tool core + 12 owner-toggled plugins" | REPO (design and config). Gotcha from `agentd/README.md`: Codex still sees some non-shell tools; its own approval requests are declined. |
| Rule of Two; screen codes never typed (P3) | `ARCHITECTURE.md` rule 3; `android/README.md` e2e (OTP block, P3); `pitch/claims-check.md` C10 | REPO, verified on phone for the OTP case. |
| 6/6 injections flagged, 0 false flags | `android/guard-tests/README.md` table (10-27): agentd regex after 6/6 precision 1.00 recall 1.00; on-device Prompt Guard 2 alone 4/4 precision, 4/6 recall; "either guard" 6/6. 31-case set per commit 0c10770 | MEASURED on a small set. Say "on our test set"; the 6/6 is both guards together. Prompt Guard 2 is the 22M int8 ONNX model (`android/README.md`). |
| 1.86 s median auto-reviewer decision | `agentd/reviewer-results.md`: 2,273.5 ms → 1,859 ms median on 18 actions, Mac-side run; live cases 27/28 | MEASURED on the Mac, not the phone. On the phone the auto-approve wait is 2.5–4.6 s (`agentd/bench/RESULTS.md` header). |
| Fingerprint for payments and passwords always | STATUS.md decisions (fingerprint only for the most sensitive); `agentd/reviewer-results.md` "payment/password fingerprint rules" | REPO. |
| "The riskiest steps require your fingerprint"; approvals bound to the exact action; audit chain | `android/README.md`: fingerprint-only list (pay, buy, install, vault), card and system fingerprint prompt verified on the phone, `chain_ok` re-checked on the Mac; `pitch/rehearsal/RUN.md` beats 7–8: "touch the sensor (needs presenter)" | REPO for the gate. **Not yet seen end to end:** `android/README.md` "Device results" still says a full *approved* action (fingerprint → signature verified → act) has not been observed; the rehearsal shots show the card and the prompt only. So the deck says "require your fingerprint", not "your fingerprint signs". If the founder completes `python3 android/tools-client.py e2e --press-approve` before 5 PM, "signs the exact action" can go back in. Hardware backing (TEE/StrongBox) not checked: say "Android Keystore" (C9). The prompt text is drawn by our app (known limit). |
| 38/38 · 12/12 cross-app memory | `agentd/memory/eval/RESULTS.md`: end-to-end Codex 22/38 → 38/38 tuning, 12/12 held-out; 1.2 model calls per question vs 2.6; re-run 50/50 | MEASURED, synthetic multi-app world on the Mac with the real model. On the phone with real data: 2.2–4.1 s per question. |
| 26/30 offline tasks (was 0/30), automatic fallback | `agentd/runs/2026-10-27-local-model.md` total row 26/30 (26/27 supported), router 30/30; `agentd/runs/2026-10-28-offline-fallback.md`: warm local ask 2.3 s, worker 3.4 s, cold 24.3 s (llama-server stopped) | MEASURED on the phone. Qwen3.5-0.8B Q4_0 via llama.cpp. Cold start is slow: start llama-server before the demo. |
| 476 tracker domains; kernel firewall by app ID; SNI watcher names the company | `android/README.md` line ~193 (`res/raw/trackers.txt`, 476 domains, Exodus signatures); iptables owner-uid rules | REPO. Firewall rules and counters reset on reboot (README limit). |
| Screenshot: planted attack flagged on-device | `pitch/rehearsal/07-security-alert.png`: Pigeon, **our own test app**, Prompt Guard 2 flag card ("Ignored instructions in a message") | REPO. Always say it is our test app, no real contacts. |
| Codex · Claude · Local switch (Q&A only now) | `launcher/shots/polish-night-2/after-center.png` model row; Claude provider works on the phone (commit 5bbe0da) | REPO. Claude not benchmarked live: say "pluggable" if pressed (claims E6). |
| Keyboard suggestions 7.3 s → 3.5 s (not on the slide; good Q&A number) | `keyboard/LATENCY.md` After table | MEASURED on the phone, 10-28 night. |
| Voice latency | see the slide 2 row: `spikes/voice/results.jsonl` (7 runs), definitions in `spikes/voice/LATENCY.md`. The older 1.9 s spike number in `spikes/voice/README.md` was via a Mac relay; don't mix the two. | MEASURED (synthetic voice, phone's own Codex). |
| Proactive cards precision (not on the slide) | `agentd/research/PROACTIVE.md`: 99–100 % precision, 92–95 % recall over 5 runs, 152 threads; blind set 100 % / 91–97 % | MEASURED on labeled threads (synthetic + blind set). |

Footer line claim: "the model's plan can be fooled; an outward action without your approval cannot happen". That is the precise claim from `pitch/deck.md` slide 3. Root is above everything (known limit): a rooted attacker is out of scope.

## Slide 4 · Why it matters for Utah (~35 s)

On the slide: **"AI must be human-guided." Built into the OS.** (kicker credits Gov. Cox, Pro-Human AI initiative) · 7,000+ · 12 % · Utah LLC · Utah map with four pins · keyboard screenshot.

Say it, not on the slide:
- Every outward action on this phone waits for a person. That sentence is our permission system.
- 7,000+ Utahns served by the Division of Services for People with Disabilities. First users: people who can't easily use a touchscreen. Target pilot channel: the Utah Assistive Technology Program at USU (Logan pin). Say "target channel", never "partner".
- ~12 % of Utahns are 65+; projection 1 in 4 by 2065. "Grandma says it, the agent does it, anything risky waits for her fingerprint or the family's okay."
- Utah LLC, Utah-based founder. Eligible now for UTIF, SBIR with Nucleus Grow, and the Nucleus Fund. Second market: state agencies adopting AI with safeguards (EO 2026-08) and Utah's device-management industry.
- Pins: Logan (USU assistive tech program) · Salt Lake City (state agencies, Cyber Center) · Draper (Nucleus at The Point) · rural Utah (works with no signal).
- Keyboard screenshot: its own keyboard drafts replies in any app; sending still goes through the gate.

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
| Screenshot: AI keyboard | `pitch/rehearsal/04-keyboard.png`: our keyboard (`local.agentos.keys`) suggesting replies in Pigeon | REPO. Suggestions fill the box; Send still goes through the gate. |
| Map | hand-drawn outline (rectangle with the NE notch); pins are approximate | n/a |

## Slide 5 · Path to reality and the $10K (~40 s)

On the slide: **"A 10-person Utah pilot in 90 days."** · budget bar $2,500 / $3,000 / $2,500 / $1,000 / $1,000 (8 phones · Utah pilot · independent red team · model credits · legal) · Day 30 / 60 / 90 · one ask line.

Say it, not on the slide:
- From one rooted phone to a 10-person Utah pilot and an SBIR proposal, in 90 days.
- 8 phones: off root, onto the shippable path (managed-device app, Android AppFunctions).
- Utah pilot: 10 users, stipends, an assistive-tech specialist, travel to Logan.
- Independent red team: outside proof of the safety claim.
- Legal: registrations (SBIR, SAM.gov is free) and consent forms.
- Day 30: off root; open threat model and test suite; SBIR topic chosen with Nucleus Grow.
- Day 60: 10 pilot users enrolled through an assistive-tech channel; independent red-team review done, fixes merged.
- Day 90: pilot results published; SBIR Phase I proposal ready for the first open solicitation; results to the Nucleus Fund.
- One founder plus AI coding agents built this week's phone; the founder sets direction and reviews every security decision.
- Footer: root is the lab, not the product.

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
- Voice "first spoken reply" (2.2 s median) and cold session start (1.1–1.7 s): true but less flattering than task start; in the notes only.
- Code line count (~19.8k): a count, not a quality claim; in the notes only.
- "$150 phone" (unverified price).
- DSPD technology pilot "$5K per person" (aggregator, unverified).
- Humane / Rabbit funding figures (conflicting sources).
- B2 "2 % opacity text read 18 of 20 times" (inconsistent in our notes).
