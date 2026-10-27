# AgentOS: one-page plan

**What it is:** the human-guided security layer for AI that operates phones. The AI suggests actions; a gate on the phone decides; anything risky needs the owner's fingerprint on that exact action; everything is logged.

**Where it stands (Oct 28, 2026):** working on a rooted Moto G 5G (Android 13). Built this week: the security broker app (`android/`), the model bridge for Codex and local models with Claude pluggable (`agentd/`), two test apps we own (`demo-apps/`), live GPT voice on the phone (`spikes/voice/`), and a custom AI keyboard. Before this week: an island overlay prototype, Binder/SystemUI experiments, Frida experiments, and Codex running on the phone.

**Company:** Utah-based founder with a Utah LLC already in place, so AgentOS can apply now for UTIF microgrants, SBIR/STTR (with Nucleus Grow) and the Nucleus Fund.

**Goal for the next 90 days:** move from one rooted lab phone to a shippable build, run a 10-person Utah pilot, and have an SBIR Phase I proposal ready.

## Milestones and timeline

| When | Milestone | How we'll know it's done |
|---|---|---|
| **Days 1-30** | Move off root: a managed-device (device-owner) version of the gate, plus Android AppFunctions where apps support them. | The fingerprint gate, audit log and firewall work on an unrooted, re-locked phone. |
| | Publish an open threat model and test suite based on the 7 attack types in arXiv 2607.00333. | Public repo with results per attack. |
| | Swap the regex guard for Meta's Prompt Guard 2 running on the phone. | Runs on the Moto, per-message latency measured. |
| | Meet Nucleus Grow (Dylan Doty) to pick the SBIR agency and topic. Register the existing Utah LLC for federal awards (SAM.gov UEI, SBA company registry). Apply for a UTIF microgrant to cover proposal costs. | Topic chosen; registrations filed; UTIF application sent. |
| **Days 31-60** | Recruit 10 Utah pilot users through a target channel such as UATP (USU) or an independent living center, with a plain-language consent form. | 10 signed consents; phones set up. |
| | Independent red-team review of the gate. | Written report; fixes merged. |
| **Days 61-90** | Run the pilot. Measure task success, approval prompts per task, attacks blocked, and caregiver time saved. | Pilot report published. |
| | SBIR Phase I proposal written (NSF, NIH/NIA or DoD; chosen with Nucleus Grow). Apply to the Utah Office of AI Policy Learning Lab. Bring pilot results to the Nucleus Fund. | Proposal ready for the first open solicitation; Learning Lab application sent. |

SBIR timing depends on when agencies open solicitations after the April 2026 reauthorization, so "ready to submit" is the honest day-90 target.

## People needed

| Who | Why | Status |
|---|---|---|
| Founder (Josh) | Product, security design, reviews all code | In place |
| AI agents (Claude Code, Codex, Fable design agents) | Build, test and research in parallel under the founder's review | In place. This is how one person shipped this week's build. |
| University co-PI (U of U Kahlert School of Computing, or USU IDRPP) | Required for STTR; adds research credibility | Needed. Ask: an intro from Nucleus. |
| Occupational therapist or assistive-tech specialist | Runs the pilot safely with disabled and older users | Needed (paid from pilot budget) |
| Android systems engineer (part-time contract) | The managed-device and custom-build paths | Needed after funding beyond this bounty |
| Outside security reviewer | Independent proof of the safety claim | Needed (paid from red-team budget) |

## Resources needed

- Test phones: low-cost Android phones like the ones pilot users already own, plus Pixels for the re-locked custom build.
- Model credits for Codex/OpenAI and other cloud providers, plus local model testing.
- Introductions: UATP (USU), DSPD, a university co-PI, Nucleus Grow.

## Use of the $10K

| Item | Amount | What it unlocks |
|---|---|---|
| Devices: 6 low-cost Android phones (~$150-250) + 2 Pixels | $2,500 | Move off one rooted phone onto the shippable path |
| Pilot: $150 stipends x 10 users, assistive-tech specialist time, travel to Logan / Uintah Basin | $3,000 | First real-user evidence, in Utah |
| Independent red-team review against published mobile-agent attacks | $2,500 | Outside proof of the security claim, for SBIR and investors |
| Model credits (cloud APIs + local testing) | $1,000 | Results across providers, not tied to one AI company |
| SBIR/federal registrations, pilot consent forms and legal review | $1,000 | Ready to submit to SBIR and sign up pilot users safely (the Utah LLC already exists) |
| **Total** | **$10,000** | |

## Top risks

| Risk | Plan |
|---|---|
| Root isn't shippable | Root is the lab. Ship as a managed-device app, a privileged app on a re-locked build, or via AppFunctions. |
| Google ships something similar | Ours is model-agnostic, runs on cheap phones, and the user or agency owns the rules and the log. |
| Pilot safety | The agent can never send, pay or delete without the user's fingerprint on that exact action. Only our own test apps are used for attack demos. |
| AI provider terms | The product uses API keys or local models, not personal subscriptions. |
