# Smart home + energy: what's real today (research, 2026-10-28 12:15 MDT)

Research only. No phone touched, no code changed. The founder has **no smart plug and no Nest hub** (assumed).
Live API calls below were run from the Mac at 12:15–12:20 MDT today.

## TL;DR

1. **Build the "grid-aware agent" (option A, ~1.5–2 h, low risk).** Add one read tool that pulls **live Utah grid data from EIA** (free, works right now with `DEMO_KEY`) and the **official Rocky Mountain Power time-of-use (TOU) prices**. The agent then uses the **existing `schedule_task`** to move work into cheap, clean hours. Everything in the demo is real: real grid data, real tariff, a real scheduled task on the phone.
2. **It fits the Torus $10K bounty almost word for word.** The kickoff deck says: "Torus is offering a $10K bounty in energy × AI infrastructure. Two prompts: **Rush Hour for Electrons** (grid congestion) and **Compute Follows the Sun** (routing compute to the cheapest, cleanest power). Free and open-source data only; simulated data is fine if labeled. Judged on creativity, clarity of insight, and execution." EIA data is public. The tariff is public.
3. **Google Home can't work today.** Every Google path needs a **Matter-capable Nest hub** plus devices in a home. Even Google's own virtual test device needs a hub. Treat it as roadmap only.
4. **A virtual Matter plug (matter.js) is real protocol, simulated power (option B, 2–3 h, medium-high risk).** Do it only if A is finished and there is spare time, or show it after the hackathon.

The insight to lead with (from today's data, see section 3): **Utah's evening rush hour (6–10 PM) is the most expensive *and* the dirtiest power of the day.** As solar drops off, coal goes from **17 % to 63 %** of PacifiCorp East's generation. Rough carbon intensity goes from about **210 to 750 g CO₂/kWh**, and the TOU price goes from **6.6¢ to 29.7¢/kWh**. That is 3.5× dirtier and 4.5× pricier. An agent in every pocket can move flexible load out of that window.

> Note: STATUS.md says code freeze was Wed noon. Option A only adds code: a new `agentd/grid.js` plus one registration line in tools.js. It changes no existing path, so if it breaks, the rest of the demo is unaffected.

---

## 1. Google Home APIs: not feasible today

| Need | What the docs say | Founder today |
|---|---|---|
| A **home with devices** | Permission is "made on a structure-by-structure basis"; the app "can hold permission for only one structure at a time" ([permissions](https://developers.home.google.com/apis/android/permissions)) | Probably no devices |
| **Hub** | Matter-over-Wi-Fi in Google Home needs a hub. Google's Matter Virtual Device also says "You must have a Google Nest Hub that supports Matter" ([MVD](https://developers.home.google.com/tools/virtual-device)) | No hub |
| **OAuth** | "OAuth consent is required to access devices in a user's home" ([get started](https://developers.home.google.com/apis/android/get-started)). Unverified apps: "Only users registered as test users in the OAuth console can grant permissions", max **100 test users** ([permissions](https://developers.home.google.com/apis/android/permissions)) | ~30 min of Cloud Console setup |
| **Developer Console approval** | "get approved for user devices you'd like to access" is marked **"Coming soon"**; the permissions page says "The Google Home Developer Console is not yet available for registration" | Not needed for test users, but it adds friction |
| App side | Kotlin SDK in an Android app built with Gradle + Play services | Our APK is built a different way (android/), so this means a new SDK integration |
| History | Waitlist in May 2024, then public developer beta on Android in Jan 2025 with "up to 100 users" ([SD Times](https://sdtimes.com/google/google-home-apis-now-available-as-public-developer-beta-on-android/), [9to5Google](https://9to5google.com/2025/01/07/google-home-apis-dev-beta/)) | n/a |

**Energy data:** the SDK has Matter `ElectricalEnergyMeasurement` / `ElectricalPowerMeasurement` traits ([Kotlin ref](https://developers.home.google.com/reference/kotlin/com/google/home/matter/standard/ElectricalEnergyMeasurement)). A third-party app could therefore read a Matter plug's watts **if** the plug reports them and the user granted that structure. No device, no data.

**Verdict:** you need a hub + devices + OAuth + an SDK integration. That is at least a day, and it can't run without hardware. **Honest pitch line:** "Google's Home APIs let a third-party app control Matter devices with the user's consent per home. AgentOS would be that app. Today we show the decision layer on real grid data; the actuator is a plug-in."

The UI-driving fallback (the agent taps the Google Home app) also needs a device in the home, so it fails for the same reason.

## 2. Options that need no hardware

| Option | Real? | Effort | Risk | Notes |
|---|---|---|---|---|
| **matter.js virtual measured socket** (`examples/device-measuring-socket`): an `OnOffPlugInUnitDevice` with `ElectricalPowerMeasurement` + `ElectricalEnergyMeasurement` ([repo](https://github.com/project-chip/matter.js), v0.18.0 released 2026-10-20) | Real Matter protocol (PASE/CASE, clusters). The README says the watts are **random**: "only sets random energy and power values when turned on" | 2–3 h | Medium-high | Run the device on the Mac (`npm run matter-measuredsocket`) and a matter.js controller in agentd on the phone (`controller` example: `--ip --port 5540 --passcode 20202021`; passing the IP skips mDNS). Risks: venue Wi-Fi client isolation (STATUS: Wi-Fi is the #1 risk), Node/crypto on Termux untested, fabric state to persist. Can't be added to Google Home without a hub. |
| **Google Matter Virtual Device** | Real Matter | n/a | Blocked | Needs a Nest Hub + a Developer Console project with VID 0xFFF1 / PID 0x8000–0x801F ([MVD](https://developers.home.google.com/tools/virtual-device)) |
| **Home Assistant + demo integration** | Fake entities | ~1 h (Docker) | Low | Adds a second system and fake data. It shows nothing an agent-native phone uniquely does. Skip. |
| **Android Device Controls** (`ControlsProviderService`, power-menu tiles) | Real Android API | ~1.5 h | Medium | We'd be publishing our own *fake* plug into it. No value without a device. Skip. |
| **Shelly/Kasa plug** (research/06) | Real | 15 min once the plug is in hand | Low | Only if someone can buy a Shelly Plug before 5 PM. It's still the best "physical thing moves" beat. |

## 3. Energy intelligence without hardware

### Rocky Mountain Power (Utah) TOU: official tariff

Source: RMP Electric Service Schedule No. 1, sheet 1.2, **filed Aug 5 2026, effective Aug 10 2026**, Docket 26-035-T08 ([PDF](https://www.rockymountainpower.net/content/dam/pcorp/documents/en/rockymountainpower/rates-regulation/utah/rates/001_Residential_Service.pdf)). The PDF was fetched and read today.

| | On-peak | Off-peak | Ratio |
|---|---|---|---|
| Jun–Sep | **33.6173¢/kWh** | **7.4705¢** | 4.5× |
| Oct–May (now) | **29.7498¢/kWh** | **6.6111¢** | 4.5× |

- **On-peak: 6:00–10:00 PM Monday–Friday, except holidays.** Off-peak: all other times, including all weekend hours. The holidays are New Year's, Presidents', Memorial, Independence, **Pioneer**, Labor, Thanksgiving and Christmas Day, observed on the Friday before or Monday after when they fall on a weekend.
- TOU is opt-in, with a 1-year minimum and a guarantee: if TOU costs over 10 % more than standard rates in the first year, the difference is credited back.
- **Standard (non-TOU) Schedule 1 is tiered by amount used, not by time.** Oct–May it is 8.7020¢ for the first 400 kWh and 11.0852¢ after that. **Honesty point:** a customer on the standard rate saves no money by shifting load. They only cut carbon and grid strain. Say "for the RMP customers on time-of-use…", or frame savings as what TOU would pay.
- Customer charge: $12/month for a single-family home.
- Example math (labeled): a ~1.2 kWh dishwasher run moved off-peak saves about 1.2 × 23.1¢ ≈ **28¢**. A 40 kWh EV charge saves about **$9.25** (Oct–May) or about **$10.46** (Jun–Sep).

### Grid signals for Utah (PacifiCorp East = EIA respondent `PACE`)

| Source | Auth / cost | Utah coverage | Freshness | Verdict |
|---|---|---|---|---|
| **EIA API v2** `electricity/rto/region-data` + `fuel-type-data` | Free key ([eia.gov/opendata](https://www.eia.gov/opendata/): "EIA data is provided free of charge"). `DEMO_KEY` works now but returned `x-ratelimit-limit: 10` | ✅ `PACE` live, tested | **Demand: ~1 h lag** (11:00 MDT row at 12:16). **Day-ahead demand forecast (`DF`) through tomorrow 00:00.** **Fuel mix: ~13 h lag** (latest row Tue 23:00) | **Use this.** Public, free, tested, ~270 ms. Cache 15 min. |
| **Electricity Maps** | Free tier: carbon intensity in gCO₂e/kWh, **1 zone**, free for individuals; Home Assistant docs cite ~50 req/h ([HA docs](https://www.home-assistant.io/integrations/co2signal)) | Zone key probably `US-NW-PACE` **[UNVERIFIED]** | Live + flow-traced (counts imports) | Optional upgrade if the founder signs up (~5 min). Better carbon number than our estimate. |
| **WattTime** | Free account via `POST /register` (username, password, email, org); `/login` token lasts 30 min | Free plan: "All signals & endpoints for one region: **CAISO_NORTH**", plus "CO2 percentile, all regions" ([plans](https://watttime.org/?p=1310)); `/v3/forecast` and `/v3/historical` are subscriber-only ([v3 docs](https://docs.watttime.org)) | 5 min | PACE percentile may be free **[UNVERIFIED]**. No free forecast. Skip today. |

**What EIA showed today (real; carbon is our labeled estimate):**

PACE generation mix on Tue 10-27 (MDT). Rough intensity = coal 1000 + gas 430 g/kWh, all else 0. This counts generation only, not imports, so it's an **estimate**:

| Hour | Coal | Gas | Solar | Wind | ≈ g CO₂/kWh |
|---|---|---|---|---|---|
| 8 AM | 41 % | 16 % | 0 % | 40 % | ~495 |
| 1 PM | 23 % | 4 % | 30 % | 39 % | ~260 |
| 5 PM | 17 % | 7 % | 26 % | 47 % | **~210** (cleanest) |
| 8 PM | 54 % | 22 % | 0 % | 20 % | ~650 |
| 10 PM | 63 % | 23 % | 0 % | 9 % | **~750** (dirtiest) |

EIA day-ahead **demand forecast for tonight** (PACE): 5,955 MW at 3 PM, rising to a **6,264 MW peak at 8 PM**, then back down to 5,582 MW at midnight. Grid demand peaks inside RMP's 6–10 PM on-peak window. That is the "rush hour for electrons".

**Takeaway:** in Utah the **cheapest *and* cleanest window is weekday late morning to ~5 PM** (off-peak price + solar). After 10 PM it's cheap but coal-heavy. **Weekends are off-peak all day.** The agent should optimize "cheap AND clean", not just cheap. That is a non-obvious insight, which suits the "clarity of insight" criterion.

Tested calls (swap `DEMO_KEY` for the founder's free key):
```
https://api.eia.gov/v2/electricity/rto/region-data/data/?api_key=KEY&frequency=hourly&data[0]=value&facets[respondent][]=PACE&facets[type][]=D&facets[type][]=DF&start=YYYY-MM-DDT00&sort[0][column]=period&sort[0][direction]=asc
https://api.eia.gov/v2/electricity/rto/fuel-type-data/data/?api_key=KEY&frequency=hourly&data[0]=value&facets[respondent][]=PACE&start=...&sort[0][column]=period&sort[0][direction]=asc
```
Periods are **UTC** hours (`2026-10-29T02` = 8 PM MDT). Use `curl -g` because of the brackets.

### How the agent uses it

- **New tool `grid_outlook`** (read tier, in a new `agentd/grid.js` that exports `tools` like schedule.js does, registered with one loop in tools.js the way `schedule.tools` is at tools.js:136). It returns `{now:{price¢, onPeak, demandMW, estCO2}, next24h:[{hour, price¢, onPeak, demandFcstMW, estCO2}], best:{cheapClean, cheapOnly}, sources, notes}`. The price comes from the hard-coded Schedule 1 table + holiday list. Carbon uses yesterday's same-hour mix as the forecast, labeled "estimate". Demand comes from EIA `DF`. If EIA fails, return the tariff only.
- **Shifting uses the existing `schedule_task`.** Nothing changes in schedule.js. The model calls `grid_outlook`, picks a time, then `schedule_task({when:"tomorrow 11 AM", prompt:"Remind me to start the dishwasher (off-peak and solar-heavy now)"})`.
- Example asks: "When should I run the dishwasher?", "Remind me to charge the car when power's cheapest", "Is now a bad time to run the dryer?", "Do my weekly summary when the grid is cleanest" (**Compute Follows the Sun**: the agent defers its *own* non-urgent compute).
- Phone charging: Android 13 has no public API to pause charging, and the Moto's root sysfs charge node is unverified. **Today: reminders only** ("plug in after 10 AM tomorrow"). EV: reminders only, since we have no car API.
- Optional: a proactive line on Home ("Peak pricing 6–10 PM tonight") through home-suggest/proactive. Skip it today; For you is for act-on items only.

## 4. Ranked recommendation for TODAY

### A. Grid-aware agent: EIA live + RMP TOU + existing scheduler (recommended)
- **Demo beat (~20 s, voice or Ask bar):** "When should I run the dishwasher?" → the agent answers something like: "Not tonight, 6 to 10 is peak: 29.7¢ vs 6.6¢, and Utah's grid is about 60 % coal then. Tomorrow 11 AM to 5 PM is off-peak and solar-heavy. Want a reminder at 11?" → "Yes" → the scheduled card appears in `list_scheduled`. Optional second line: "And run my weekly recap in the cleanest window."
- **Build:** 1.5–2 h (grid.js ~80 lines, tool description, a quick `node cli.js` test, agentd restart).
- **Risk: low.** Read-only, no new permissions, one HTTPS call cached 15 min, tariff-only fallback. Needs internet (the demo does anyway).
- **Founder must:** register a free EIA key (2 min, email) because DEMO_KEY allows only 10 req/h. Optional: Electricity Maps free signup for a live, flow-traced carbon number.
- **Honest claims:** "Live public grid data from the U.S. EIA for PacifiCorp East, and Rocky Mountain Power's published TOU tariff. The carbon number is our estimate from the generation mix." "Savings apply to RMP TOU customers." **Don't claim** we control appliances, reduce the grid peak today, or have live carbon (the EIA fuel mix is ~13 h old; we use it as a pattern).

### B. Virtual Matter plug with simulated watts (only if A is done by ~3 PM)
- **Demo beat:** "Turn on the heater plug… how much is it drawing?" → the matter.js socket on the Mac flips on and reports watts. Then "Turn it off during peak" → `schedule_task` at 6 PM.
- **Build:** 2–3 h. **Risk: medium-high** (Wi-Fi isolation, Node/Matter on Termux, commissioning state; one more process on stage).
- **Founder must:** nothing to sign up for. Keep the Mac on the same network or hotspot.
- **Honest claims:** "A software plug that speaks real Matter, the standard real plugs use. Its wattage is simulated and labeled. The same agent code drives a real Matter plug." **Don't claim** Google Home integration.

### C. No build: talk track + one slide
- Show the section 3 table and the Torus framing. Say Google Home/Matter control is the next step (it needs a hub + plug, about $60 total).
- **0 h, zero risk.** Weaker on the "execution" criterion.

**Do not attempt today:** Google Home APIs (hub + devices + OAuth + SDK), WattTime (no free PACE forecast), Home Assistant demo (fake data).

## 5. Pitch talking points (energy angle)

- **Rush Hour for Electrons:** "In Utah, 6 to 10 PM on weekdays is when the grid peaks (EIA forecasts 6,264 MW tonight at 8 PM), when power costs 4.5× more on Rocky Mountain Power's TOU rate, and when it's dirtiest. Yesterday coal went from 17 % to 63 % of generation as the sun set."
- **Compute Follows the Sun:** "Our agent already decides *when* work runs (scheduled tasks that even unlock the phone). Now it also knows *when power is cheap and clean*. It moves its own deferrable compute, and your dishwasher, dryer and EV reminders, into the solar window."
- **Why a phone agent:** "Demand response today needs a smart thermostat or a utility-controlled battery (RMP's Wattsmart Battery program already dispatches thousands of Utah home batteries daily; [Canary Media](https://canarymedia.com/articles/batteries/in-utah-thousands-of-homes-feed-the-grid-stored-solar-power)). Everyone already has a phone. An agent that controls the phone and, through Matter, the home can turn millions of phones into a flexible-load signal with no new hardware."
- **AI-infra honesty:** "A median Gemini text prompt is ~0.24 Wh ([MIT Tech Review](https://www.technologyreview.com/2025/08/21/1122288/google.gemini.ai.energy/)). The win isn't the agent's own energy. It's the kilowatt-hours it moves (a dishwasher run is ~1 kWh, about 4,000 prompts)."
- **Utah impact:** Torus (SLC) builds mesh energy storage and writes about Wattsmart ([Torus](https://torus.co/news/what-is-the-wattsmart-battery-program-everything-you-need-to-know)). A natural pilot: AgentOS as the phone-side assistant for VPP households.
- **Data rule compliance:** all data is free and public (EIA, the RMP tariff); estimates are labeled.

## Sources
- Torus bounty text: kickoff deck "2026 hackathon HTW" (txt export), https://docs.google.com/presentation/d/1e9siqqQMkUVGRVy5ttxSduS4vGr7IEnOtGZ-5aGGXzE/edit
- RMP Schedule 1: https://www.rockymountainpower.net/content/dam/pcorp/documents/en/rockymountainpower/rates-regulation/utah/rates/001_Residential_Service.pdf
- EIA Open Data: https://www.eia.gov/opendata/ (API calls tested 2026-10-28)
- Google Home APIs: https://developers.home.google.com/apis · /apis/android/get-started · /apis/android/permissions · /tools/virtual-device
- matter.js: https://github.com/project-chip/matter.js (examples/device-measuring-socket, examples/controller)
- WattTime: https://watttime.org/?p=1310 · https://docs.watttime.org
- Electricity Maps: https://www.home-assistant.io/integrations/co2signal · https://www.electricitymaps.com/pricing
- Gemini energy per prompt: https://www.technologyreview.com/2025/08/21/1122288/google.gemini.ai.energy/
- Wattsmart in Utah: https://canarymedia.com/articles/batteries/in-utah-thousands-of-homes-feed-the-grid-stored-solar-power
