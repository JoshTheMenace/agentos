# AgentOS: one-page plan (DRAFT, Oct 28, 2026)

**What it is:** an AI-native phone OS, "the Siri we should have", built security-first. You say it, agents do it in your real apps. The model proposes; a gate in the OS decides; risky steps wait for a tap or your fingerprint; everything is logged.

**Where it stands (Oct 28, 2026):** working on a rooted Moto G 5G (2023), Android 13, our lab phone. **Before this week:** an island overlay prototype, SystemUI experiments, Codex running on the phone. **Built Oct 26–28 (123 commits):** the gate (`android/`: leases, Rule of Two, on-device Prompt Guard 2, fingerprint-gated approvals, hash-chained audit, per-app kernel firewall); `agentd/` with Codex, Claude or an on-device model, cross-app memory, proactive cards, scheduled tasks on a locked phone, automatic offline fallback; live voice; an A2 AI keyboard; app mods and micro-apps; a Calm Glass launcher.

**Measured on the phone (files in the repo):** median task ~30 s → ~11 s, "set an alarm" 7.5 s (`agentd/bench/RESULTS.md`); keyboard suggestions 7.3 s → 3.5 s (`keyboard/LATENCY.md`); on-device model 26/30 real tasks, was 0/30 (`agentd/runs/2026-10-27-local-model.md`); offline fallback warm ask 2.3 s (`agentd/runs/2026-10-28-offline-fallback.md`); memory brain 38/38, held-out 12/12 (`agentd/memory/eval/RESULTS.md`); auto-reviewer 1.86 s median, Mac run (`agentd/reviewer-results.md`); injection test set 6/6 flagged, 0 false flags (`android/guard-tests/README.md`). Voice (`spikes/voice/results.jsonl`, 7 synthetic-voice runs): warm session ready in 8–9 ms, end of speech → task start 1.7 s median, task done → spoken "done" 0.9 s median.

**Company:** Utah-based founder, Utah LLC already formed. Eligible now for UTIF microgrants, SBIR/STTR (with Nucleus Grow) and the Nucleus Fund.

**Goal for the next 90 days:** move from one rooted lab phone to a shippable build, run a 10-person Utah pilot, and have an SBIR Phase I proposal ready.

## Milestones and timeline

| When | Milestone | How we'll know it's done |
|---|---|---|
| **Days 1–30** | Off root: a managed-device (device-owner) version of the gate, plus Android AppFunctions where apps support them. | Fingerprint gate, audit log and firewall work on an unrooted, re-locked phone. |
| | Publish an open threat model and test suite based on the 7 attack types in arXiv 2607.00333, plus our guard and memory evals. | Public repo with results per attack. |
| | Meet Nucleus Grow to pick the SBIR agency and topic. Register the LLC for federal awards (SAM.gov UEI, SBA registry). Apply for a UTIF microgrant. | Topic chosen; registrations filed; UTIF application sent. |
| **Days 31–60** | Recruit 10 Utah pilot users through a target channel such as the Utah Assistive Technology Program (USU) or an independent living center, with a plain-language consent form. | 10 signed consents; phones set up. |
| | Independent red-team review of the gate. | Written report; fixes merged. |
| **Days 61–90** | Run the pilot. Measure task success, approval prompts per task, attacks blocked, time saved for users and caregivers. | Pilot report published. |
| | SBIR Phase I proposal written (NSF, NIH/NIA or DoD; chosen with Nucleus Grow). Apply to the Utah Office of AI Policy Learning Lab. Bring results to the Nucleus Fund. | Proposal ready for the first open solicitation; Learning Lab application sent. |

SBIR timing depends on when agencies open solicitations after the April 2026 reauthorization, so "ready to submit" is the honest day-90 target.

## People needed

| Who | Why | Status |
|---|---|---|
| Founder (Josh) | Product, security design, reviews every security decision | In place |
| AI coding agents (Claude Code, Codex, Fable design agents) | Build, test and research in parallel under the founder's review | In place; this is how one person shipped this week's build |
| University co-PI (U of U Kahlert School of Computing, or USU IDRPP) | Required for STTR; research credibility | Needed. Ask: an intro from Nucleus |
| Occupational therapist or assistive-tech specialist | Runs the pilot safely with disabled and older users | Needed (pilot budget) |
| Android systems engineer (part-time contract) | Managed-device and custom-build paths | Needed after funding beyond this bounty |
| Outside security reviewer | Independent proof of the safety claim | Needed (red-team budget) |

## Use of the $10K

| Item | Amount | What it unlocks |
|---|---|---|
| Devices: 6 low-cost Android phones (~$150–250) + 2 Pixels | $2,500 | Off one rooted phone, onto the shippable path |
| Pilot: $150 stipends × 10 users, assistive-tech specialist time, travel to Logan / Uintah Basin | $3,000 | First real-user evidence, in Utah |
| Independent red-team review against published mobile-agent attacks | $2,500 | Outside proof of the security claim, for SBIR and investors |
| Model credits (cloud APIs + local testing) | $1,000 | Results across providers, not tied to one AI company |
| SBIR/federal registrations, consent forms and legal review | $1,000 | Ready to submit to SBIR and enroll pilot users safely (the LLC already exists) |
| **Total** | **$10,000** | |

Resources beyond money: introductions to UATP (USU), DSPD, a university co-PI, and Nucleus Grow.

## Top risks

| Risk | Plan |
|---|---|
| Root isn't shippable | Root is the lab. Ship as a managed-device app, a privileged app on a re-locked build, or via AppFunctions. |
| Google or Apple ships something similar | Ours is model-agnostic, runs on cheap phones, keeps working offline, and the user or agency owns the rules and the log. |
| Pilot safety | The agent can never send, pay or delete without a person's approval on that exact action; payments and passwords always need a fingerprint. Attack demos use only our own test apps. |
| AI provider terms | The product uses API keys or local models, not personal subscriptions. |
| Claims | Every number above comes from a results file in the repo; sources per claim are in `pitch/claims-check.md` and `pitch/deck/notes.md`. |
