# AgentOS: claims check

Every factual claim in `deck.md`, `one-pager.md` and `demo-script.md`, with its source.
Judges include the Governor's Office of Economic Opportunity, so: if a claim is flagged, fix it or cut it before Wednesday.

**Status key**
- **VERIFIED**: read in a primary source, or checked on the phone or in this repo.
- **SECONDARY**: from a news, blog or summary source; fine to say with attribution, better to confirm against the original.
- **UNVERIFIED**: from an aggregator, an estimate, or conflicting sources. Soften the wording or confirm first.
- **PENDING**: a build claim not yet seen working end to end. Re-test by the Wed noon freeze; cut if not green.
- **DECIDE**: needs a decision from Josh.

## A. The bounty itself

| # | Claim | Used in | Source | Status |
|---|---|---|---|---|
| A1 | Bounty is $10K; judges Angela Smith, Tyler Jennings, Cam Dionne, Dylan Doty | deck header | Kickoff deck slide 4 and speaker notes ("2026 hackathon HTW"), per `research/08-pitch-utah.md` §1 | VERIFIED |
| A2 | Judging Wed Oct 28, 5-6 PM, Aernata, 250 E 200 S Ste 320, SLC | deck header, demo checklist | Kickoff deck slide 6, per `research/08` §1 | VERIFIED |
| A3 | Criteria weights 30/25/20/15/10; deck max 5 slides; one-page plan; video max 2 min; "show what you did this week" | all | Kickoff deck slide 4 notes, per `research/08` §1 | VERIFIED |
| A4 | Brief lists "AI infrastructure" and "cyber" as hard tech | deck slide 3, Q&A | Kickoff deck slide 4, per `research/08` §1 | VERIFIED |

## B. The problem (security research)

| # | Claim | Used in | Source | Status |
|---|---|---|---|---|
| B1 | Researchers tested 5 open-source Android agents against 7 attacks; each fell to at least 6 | deck slide 1, demo step 1 | arXiv 2607.00333 (Zhang et al., Jul 2026); The Hacker News; CSA research note. Cited in `research/04` §1.3 and `research/08` §4 | SECONDARY. Read the paper's abstract/results table to confirm "5", "7" and "≥6". |
| B2 | Near-invisible (2% opacity) text was read by the vision models in ≥18 of 20 trials | not on slides (Q&A only) | `research/04` §1.3. Note: `research/08` summarizes this as "at or near 20/20". | SECONDARY, slightly inconsistent between our notes. Say "nearly every time", not a number. |
| B3 | Crafted WhatsApp/Slack/SMS notifications reached Gemini on Android; Google mitigated with classifier improvements | deck slide 1 | SafeBreach "Gemini's Secret Affair" (2026-06-03); The Hacker News; TechRepublic. `research/04` §1.2 | SECONDARY (vendor research + press) |
| B4 | Google, Perplexity and open-source agents now operate phone apps | deck slide 1 | Perplexity Assistant (TechCrunch, Jan 2025); Gemini app automation (9to5Google Mar 2026, Engadget); Droidrun/Mobilerun. `research/08` §4 | SECONDARY |
| B5 | Meta "Rule of Two" and DeepMind CaMeL are the frameworks we follow | deck slide 3 notes | Meta AI blog 2025-10-31; arXiv 2503.18813. `research/04` §2 | VERIFIED (primary sources cited) |
| B6 | "Classifiers get bypassed" | deck slide 1 notes | Prompt Guard 1 bypass, Robust Intelligence/Cisco blog 2024-07-29. `research/04` §1.3 | SECONDARY. Don't imply Prompt Guard 2 has the same bypass (its tokenizer fixed that one). |

## C. What we built and what works (repo and phone)

| # | Claim | Used in | Source | Status |
|---|---|---|---|---|
| C1 | Runs on a rooted Moto G 5G (2023), Android 13 | deck slide 2 | `research/00-device-baseline.md` | VERIFIED |
| C2 | It's a "~$150-class" phone | deck slide 2, Q&A | `research/08` §5 says "$150 Moto G"; no price source cited | UNVERIFIED. Slides now say "a budget phone"; keep it that way unless a price is checked. |
| C3 | Built this week: broker app, approvals, audit, firewall, agentd (3 providers), 2 test apps, voice on phone | deck slide 2, one-pager | Files in `android/`, `agentd/`, `demo-apps/`, `spikes/voice/`, all dated 2026-10-26 | VERIFIED in repo, but **nothing is committed** (git shows only the initial commit). DECIDE: commit now so a timestamped git log can prove it. |
| C4 | About 2,600 lines of code this week | deck slide 2 | `wc -l` on 2026-10-26: android Java 1,365; agentd JS 617; demo apps Java 605 (total 2,587; excludes voice spike, scripts, node_modules) | VERIFIED (recount before Wed) |
| C5 | Before this week: island overlay prototype, Binder/SystemUI status-bar experiments, Frida experiments, Codex on the phone | deck slide 2, one-pager | `systemui-lab/DEVICE-RESULTS.txt` (2026-10-22), `research/05` §1, `research/00` (Frida 17.24.3 and the Codex lab already on the phone) | VERIFIED. Don't name the third-party app used in the Frida experiments. |
| C6 | Every phone action goes through one gate (the Android app); models never get shell, root or adb | deck slides 2-3 | `ARCHITECTURE.md` rules 1-2; `agentd/README.md` "How each model is boxed in" | VERIFIED (design and config). Note the README's honest gotcha: Codex still sees some non-shell tools (`exec` code mode, `apply_patch` rejected by the sandbox). |
| C7 | Approval card shows exact app, target and text plus Rule-of-Two chips; Approve opens the system fingerprint prompt | deck slide 2, demo step 3 | `android/README.md` "Device results" | VERIFIED on phone |
| C8 | The fingerprint signs the exact action; the gate verifies it, re-reads the screen, then acts | deck slides 2-3, demo steps 3 and 5 | `android/README.md`: "Not yet seen end to end: a full approved action" | **PENDING.** Run `tools-client.py e2e --press-approve` with someone at the sensor. Until then, say "is built to" not "does". |
| C9 | The key is hardware-backed ("the phone's secure hardware") | deck slide 3, demo step 3 | `android/README.md`: Keystore EC key, auth per use, BIOMETRIC_STRONG. Hardware backing (TEE/StrongBox) on this Moto was not checked. | UNVERIFIED. Slides now say "Android Keystore key". Check `KeyInfo.isInsideSecureHardware()` before saying "hardware". |
| C10 | Canary or one-time code read on screen can't be typed out, even with approval (P3) | deck slide 3, demo step 4 | `android/README.md` (e2e: typing the login code just read was blocked, P3, T4); `Broker.java` canary pattern `AGENTOS-CANARY-*` | VERIFIED for the OTP case. The `AGENTOS-CANARY-*` text itself: run once before Wed. |
| C11 | "Blocked with the guard off" | demo step 4 | The demo drives the gate directly with `tools-client.py`, so agentd's `guard.js` isn't in the path at all | VERIFIED as staged. A guard on/off switch inside agentd does not exist yet (PENDING, optional). |
| C12 | Hash-chained audit log, re-checkable offline with `openssl` | deck slide 3, demo step 3 | `android/README.md` (`chain_ok`, Mac-side re-check verified); signature fields stored per approval | Chain: VERIFIED. Offline signature check: PENDING (needs one approved action, C8). |
| C13 | Per-app firewall via root iptables by app ID; agent can only propose, fingerprint applies, fixed rule template | deck slide 3, demo step 5 | `android/README.md`: chain setup and uid rules verified by hand as root; `firewall.set` via the API seen only up to the approval | **PENDING** for the approved path. Known limit: domain rules resolve IPs once. |
| C14 | Beacon shows its tracker requests live and rows turn red when blocked | demo step 5 | `demo-apps/README.md`; screenshot `beacon-blocked-host.png` | VERIFIED app behavior. Note: that screenshot shows an unresolvable host (DNS), not a firewall block. Don't caption it as a firewall result. |
| C15 | Works with ChatGPT (Codex), Claude, or a local model | deck slide 2, demo step 7 | `agentd/README.md`, `agentd/providers/` | Codex and Claude: VERIFIED in agentd (Claude "lists exactly our 10 tools"). Codex driving the **real phone** via agentd: re-verify. Local: **PENDING** (stub, needs llama-server + model). Say "Codex and Claude today, local next" if local isn't green. |
| C16 | GPT live voice works on this phone; first spoken reply about 2 s after a plain question | demo step 2 | `spikes/voice/README.md` phone results (1.9 s; 0.7-1.3 s session start) | VERIFIED as a spike via a Mac relay. Voice driving real phone tools through agentd: **PENDING**. |
| C17 | AI keyboard suggestion strip | demo step 6 | `research/05` §6 (design only); agentd `suggest` returns empty | **PENDING** (not built) |
| C18 | Kill switch: hold volume-down 1 s; a human touch pauses the task | demo global fallbacks | `android/README.md` | VERIFIED in code; stop/pause verified in e2e. Practice the volume-down hold once. |
| C19 | Card hardening: obscured touches filtered, injected touches swallowed | Q&A only | `android/README.md` "Card hardening" | VERIFIED in code, not attack-tested |
| C20 | Known limits we state openly: root is above everything; the prompt text is drawn by our app (no Protected Confirmation); `task.start` isn't human-approved | Q&A | `android/README.md` "Known limits" | VERIFIED. Have these ready; admitting them earns trust. |

## D. Utah and policy

| # | Claim | Used in | Source | Status |
|---|---|---|---|---|
| D1 | Gov. Cox's Pro-Human AI values include "AI Must Be Human-Guided" | deck slide 4, demo close | prohumanai.utah.gov; GovTech on EO 2026-08. `research/08` §2 | VERIFIED (state site). Quote word for word. |
| D2 | EO 2026-08 (Oct 2026) pushes state agencies to adopt AI with safeguards | deck slide 4 | GovTech article. `research/08` §2 | SECONDARY. Read the EO text before saying more than one line. |
| D3 | DSPD serves 7,000+ people | deck slide 4 | DSPD FY2024 Annual Report (dspd.utah.gov). `research/08` §3 | VERIFIED (primary PDF cited) |
| D4 | DSPD technology pilot pays up to $5K per person for "communication devices, tablets, and applications"; due 2027-03-31 | deck slide 4 | atomgrants.com aggregator. `research/08` §3 | **UNVERIFIED.** Slide already says "pending confirmation". Confirm at dspd.utah.gov or drop the $ figure. |
| D5 | About 12% of Utahns are 65+; near 1 in 4 by 2065 | deck slide 4 | KSL; KPCW (Kem C. Gardner projections). `research/08` §2 | SECONDARY. Confirm with the Gardner Policy Institute projection report if asked. |
| D6 | Utah Assistive Technology Program (USU) runs demo and loan libraries (Logan, Uintah Basin) | deck slide 4, one-pager | idrpp.usu.edu/uatp. `research/08` §3 | VERIFIED. Always "target pilot channel", never "partner". |
| D7 | Utah Digital Choice Act (HB 418, 2025; Utah Code 13-81) in force July 1, 2026; data portability incl. social graph with user consent | deck slide 4 | Enrolled HB 418 text; Utah Code 13-81 PDF. `research/07-memory.md` §11 | VERIFIED (primary). Our memory feature that would use it is **not built**; present it as policy fit only. |
| D8 | Nucleus Grow gives free SBIR/STTR help; Nucleus HQ at The Point, Draper | deck slide 4, one-pager | startup.utah.gov; Deseret News 2026-10-02. `research/08` §2 | SECONDARY |
| D9 | SBIR reauthorized 2026-04-13 through FY2031 | deck slide 5, one-pager | Mondaq. `research/08` §2 | SECONDARY. Check sbir.gov before saying the date out loud. |
| D10 | Agencies are still restarting solicitation cycles, so "proposal ready" (not "submitted") at day 90 | one-pager | `research/08` §2 | SECONDARY |
| D11 | Utah's device-management industry (e.g. Ivanti in South Jordan) | deck slide 4 (no name on slide) | SiliconANGLE 2026-01-27. `research/08` §3 | SECONDARY. Never imply a relationship. |
| D12 | Utah Office of AI Policy runs a Learning Lab | one-pager | prohumanai.utah.gov; commerce.utah.gov. `research/08` §2. Note HB 320 (2026) rewrote its regulatory-agreement rules (`research/07` §11). | SECONDARY. Check the current application process. |

## E. Business, team, money

| # | Claim | Used in | Source | Status |
|---|---|---|---|---|
| E1 | Team is one founder plus AI agents | deck slide 2, one-pager | Josh | VERIFIED |
| E2 | AgentOS is (or will be) a Utah company | Q&A | none | **DECIDE.** Where does Josh live, and where will the LLC be formed? Nucleus funds and UTIF need Utah HQ. |
| E3 | $10K split: devices $2,500, pilot $3,000, red-team $2,500, credits $1,000, LLC/legal $1,000 | deck slide 5, one-pager | `research/08` §6 (our plan) | Our estimate. Device prices ~$150-250 and $150 stipends are assumptions. |
| E4 | Gemini's agent runs only Google's model, only on flagship phones, no user-owned policy | Q&A | `research/08` §4 (Pixel 10 / S26 beta, 9to5Google, Engadget) | SECONDARY, and it may change fast. Say "today". |
| E5 | Production paths: device-owner app, re-locked custom build, Android AppFunctions | deck slide 5, one-pager, Q&A | Android Developers Blog (AppFunctions, Feb 2026); GrapheneOS custom AVB key model. `research/08` §5 | SECONDARY. None of these is built yet; it's the plan. |
| E6 | Demo uses personal ChatGPT Pro (Codex app-server, live voice) and Claude logins; product uses API keys or local models | deck Q&A, one-pager risks | `research/01` TL;DR 3 and 5: OpenAI supports ChatGPT-plan use via app-server for open-source/local apps (paid/hosted apps need its interest form); Anthropic doesn't allow subscription login in third-party apps. Live voice uses an `#[experimental]` app-server API. | **DECIDE.** Claude on stage: use an API key (`CLAUDE_BIN=none` + `ANTHROPIC_API_KEY`) or the unmodified Claude Code CLI on the Mac, not a subscription routed through the phone. |

## Do not say

- "Partner" for UATP, DSPD, Ivanti, any university, or Nucleus. Say "target channel".
- "We beat the paper" or "unhackable". Say "we block these attack types in our test setup".
- Any number from B2 or C2 without the softer wording above.
- Anything about the Frida target app, or Duolingo.
