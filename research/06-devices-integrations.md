# 06 - Other devices + external AI assistants

Track: Galaxy Watch4, smart plug, and exposing AgentOS as an MCP server that other assistants (Claude, ChatGPT, Grok, OpenAI Dots, Grok Bot) can drive.
Researched 2026-10-26. Nothing was run against the phone or watch. Items marked **[UNVERIFIED]** come from secondary sources or could not be confirmed.

---

## TL;DR

1. **Watch, live data:** the quickest way to get real live heart rate is a small Wear OS app of our own. It uses Health Services `MeasureClient`, gets sideloaded with `adb pair`/`connect`/`install` over Wi-Fi, and sends readings to the phone with the Data Layer `MessageClient`. The Watch4 should now run **Wear OS 6 (One UI 8 Watch, API 36)**, which means it needs the new `android.permission.health.READ_HEART_RATE` permission. Health Connect on the phone is fine for "steps today" but is **not live**.
2. **Watch as a second-factor approver: yes, at two levels.** (a) Zero code: a phone notification with Approve/Deny buttons is automatically mirrored to the watch. It looks good but is not true 2FA. (b) Real version: the watch app holds a key in its own Android Keystore and **signs a hash of the exact action**. The phone runs nothing without a valid signature. This is the security centerpiece.
3. **Smart plug:** **Shelly Gen2+ is the easiest by far.** One `curl` call, no cloud account. Kasa through `python-kasa` is a close second. Tapo needs cloud credentials, and newer firmware that uses the "TPAP" transport may not work. Tuya needs a Tuya developer-cloud account to get the key. Matter is too heavy for 36 hours.
4. **"OpenAI dots"** = OpenAI **Dots**: always-on cloud agents launched at DevDay on 2026-09-29, for Pro and Business Premium. **"Grok bot"** = xAI's **Grok Bot**: a persistent computer-use agent in beta since around 2026-08-11, for SuperGrok Heavy and Cursor Ultra. Neither has a public API or an Android hook. The realistic way to integrate is **MCP connectors**: Grok's custom connectors officially accept any public MCP URL, and ChatGPT and Claude both support custom remote MCP.
5. **MCP exposure:** the one setup all three clients accept is **Streamable HTTP + OAuth 2.1 (PKCE S256, with DCR or CIMD client registration)**. ChatGPT rejects static API keys. Recommended setup: an **MCP gateway on the laptop** (FastMCP + GitHub OAuth limited to our account) published with **Tailscale Funnel**. It talks to the phone **only over the tailnet**, and every write action goes through the phone's permission engine and the watch's signature.

---

## (a) Galaxy Watch4

### Facts that matter
- **OS version:** Samsung shipped One UI 8 Watch (Wear OS 6) to the Watch4 and Watch4 Classic starting December 2025. It is the last major update. Confirm on the watch under Settings > About watch > Software.
  - https://9to5google.com/2025/12/16/samsung-galaxy-watch-4-one-ui-8-watch-wear-os-6-update/
  - https://www.sammobile.com/news/galaxy-watch-4-one-ui-8-watch-update-released-globally/
- **Wear OS 6 (API 36) permission change:** heart rate now needs `android.permission.health.READ_HEART_RATE` instead of `BODY_SENSORS`. Steps need `ACTIVITY_RECOGNITION`. Background passive data needs `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND`. Source: https://developer.android.com/health-and-fitness/health-services/permissions
  ```xml
  <uses-permission android:name="android.permission.BODY_SENSORS" android:maxSdkVersion="35"/>
  <uses-permission android:name="android.permission.health.READ_HEART_RATE"/>
  <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION"/>
  ```
- **Non-Samsung phone:** a Watch4 paired to the Moto through the Galaxy Wearable app works. The Data Layer only rules out iOS phones (https://developer.android.com/training/wearables/data/data-layer). Samsung's own HR-to-phone codelab lists only "Android mobile device" as a requirement, but its pairing steps assume a Samsung phone. **[UNVERIFIED on the Moto; test early.]**

### Options compared

| Option | Live HR? | Steps | Push prompt to watch | Effort | Verdict |
|---|---|---|---|---|---|
| **A. Own Wear OS app** (Health Services + Data Layer) | Yes, about 1 Hz while measuring | `PassiveMonitoringClient` (STEPS_DAILY) | Yes, our own UI | 4-6 h | **Primary** |
| B. Samsung Health **Sensor SDK** on watch | Yes, plus IBI/HRV | No | Same as A | A + 1-2 h | Only if we want HRV |
| C. Samsung Health → **Health Connect** on phone | No (batched sync) | Yes (daily) | No | 1-2 h | Optional "steps today" |
| D. BLE straight from watch | Watch4 doesn't broadcast standard HR service 0x180D natively **[UNVERIFIED]**; needs a third-party watch app | No | No | - | Skip |
| E. Bridged phone notification | - | - | **Yes, zero watch code** | 1 h | **Approval fallback** |

**Option A, the core code** (as a picture: the watch is a thermometer that radios each reading to the phone):
```kotlin
// watch
val mc = HealthServices.getClient(ctx).measureClient
mc.registerMeasureCallback(DataType.HEART_RATE_BPM, object : MeasureCallback {
  override fun onDataReceived(d: DataPointContainer) {
    val bpm = d.getData(DataType.HEART_RATE_BPM).last().value
    Wearable.getMessageClient(ctx).sendMessage(phoneNodeId, "/hr", bpm.toString().toByteArray())
  }
  override fun onAvailabilityChanged(t: DeltaDataType<*, *>, a: Availability) {}
})
// phone: a WearableListenerService that overrides onMessageReceived for path "/hr"
```
- Library: `androidx.health:health-services-client` (check the current version in Android Studio; **[UNVERIFIED]** exact version) and `com.google.android.gms:play-services-wearable`.
- **Gotcha:** the Data Layer only connects two apps that have the **same applicationId and the same signing key**. Build both debug APKs on the same machine. (This is the well-known rule, but the page fetched above didn't state it.) Find the phone's node with `CapabilityClient` or `NodeClient.connectedNodes`.
- **Gotcha:** `MeasureClient` only delivers data while registered, and in practice only while our app or an ongoing activity is active. For the demo, keep the watch app open or use an `OngoingActivity` with a foreground service.
- Samsung codelab that builds exactly HR+IBI to phone via `MessageClient`: https://developer.samsung.com/codelab/health/heart-rate-data-transfer.html (Option B; requires "Health Platform developer mode" on the watch).

**Sideloading to the watch** (team runs this from the laptop, or from Termux `pkg install android-tools` on the rooted phone):
1. Watch: Settings > About watch > Software information > tap **Software version** 7 times.
2. Developer options > enable **ADB debugging** and **Wireless debugging** > "Pair new device" shows IP:port and a 6-digit code.
3. `adb pair <ip>:<pairport> <code>` → `adb connect <ip>:<port>` → `adb -s <ip>:<port> install -r watch-debug.apk`. Android Studio's "Pair devices using Wi-Fi" also works.
   (Secondary guide: https://anexplorer.io/install/apk-on-wear-os)
- **Security note for the pitch:** watch ADB is full control of the watch. Turn it off after the demo. The rooted phone can also ADB into the watch, which is a real lateral-movement path to mention in the threat model.

### Watch as a second-factor approver

Analogy: the phone is the bank teller, and the watch is a separate signature card. The teller can't cash the check without your signature.

**Level 1 (1 h, fallback):** the phone posts a `NotificationCompat` with "Approve"/"Deny" actions. Enable notification forwarding for our app in the Galaxy Wearable app, and the buttons appear on the watch. A tap fires the phone's `PendingIntent`. Weakness: it all runs on the phone, so anything that controls the phone (including our own accessibility agent tapping notifications!) could click Approve. **Make the executor ignore approvals that arrive while an accessibility-driven action is in progress.** At minimum, exclude our notification from the agent's tap targets.

**Level 2 (+3-4 h on top of Option A, recommended for the pitch):**
1. One-time enrollment: the watch app creates an EC P-256 key in the **watch's** `AndroidKeyStore` and sends the public key to the phone, which pins it.
2. The phone's agent wants a risky action, so it builds `req = {id, human_summary, sha256(canonical_action_json), nonce, expires_at (now+30s)}` and sends it to the watch with `MessageClient` on path `/approve`.
3. The watch shows the summary on its own screen (a "trusted display" the phone agent can't draw on), with a large Approve button. Deny is the default, and timeout counts as deny.
4. The watch signs `id||hash||nonce||expires_at` and sends back the signature. The phone verifies against the pinned key, checks that the hash matches the action it's about to run, nonce and expiry included, then runs it and writes an audit log entry.
- Optional hardening **[UNVERIFIED on Watch4]**: create the key with `setUserAuthenticationRequired(true)` and `setUserAuthenticationParameters(…, AUTH_DEVICE_CREDENTIAL)` so the key only works while the watch is unlocked (Watch4 locks when taken off the wrist if a lock is set). Check whether the Watch4 keystore is hardware-backed with `KeyInfo.getSecurityLevel()`.
- **Honest threat model** (say it in the pitch): this protects against a prompt-injected or misbehaving **AI agent** and against **remote MCP callers**. Neither can forge the watch's signature. It does **not** protect against root-level malware on the phone, which can skip the check, because the executor lives on the phone. Mitigation story: the executor runs as a separate small service, and the audit log is append-only.

---

## (b) Smart plugs: local control comparison

| Ecosystem | Local API | Cloud account needed? | From Termux | From Android app | Demo ease |
|---|---|---|---|---|---|
| **Shelly Gen2/3/4 (Plus/Pro)** | HTTP RPC: `GET /rpc/Switch.Set?id=0&on=true`, `/rpc/Switch.GetStatus?id=0` (returns `apower` W, `voltage`, `aenergy`) | **No** | `curl` | OkHttp | **Easiest (15 min)** |
| Shelly Gen1 | `GET /relay/0?turn=on` | No | curl | OkHttp | Easy |
| **TP-Link Kasa** (HS103/KP115/EP25…) | `python-kasa` (latest **0.12.0**, needs Python ≥3.11) | Older firmware no; newer KLAP firmware needs TP-Link login | `pip install python-kasa` then `kasa --host IP on` | Shell out to Termux, or a tiny HTTP shim | Easy-medium (1-2 h) |
| TP-Link **Tapo** | python-kasa (KLAP/SSL) | **Yes**, TP-Link cloud email+password used locally | `kasa --host IP --username U --password P on` | same | Medium. Release notes say the newer **TPAP** transport "is used more and more on newer devices" and is **not supported** |
| **Tuya / Smart Life** | `tinytuya` (protocols 3.1-3.5) | **Yes**: iot.tuya.com project + link app + `python -m tinytuya wizard` to get `local_key`; IoT Core trial lasts about 1 month | `d=tinytuya.OutletDevice(id, ip, key); d.set_version(3.3); d.turn_on()` | same | Medium-hard (2-3 h); only one TCP connection at a time, so close the Smart Life app |
| **Matter** (Wi-Fi) | Needs a Matter controller (chip-tool / python-matter-server / Home Assistant) and a commissioning fabric | No, but complex | Not realistic | Google Home APIs / own fabric | **Not in 36 h** |

Sources: Shelly https://shelly-api-docs.shelly.cloud/gen2/ComponentsAndServices/Switch · python-kasa https://github.com/python-kasa/python-kasa/releases · tinytuya https://github.com/jasonacox/tinytuya

**Recommendation:** use a **Shelly Plug (Gen3/Gen4, about $20-30)** if we can get one. Otherwise use whatever plug we own if it's Kasa. Live wattage (`apower`) makes a strong demo: "the agent turned on the lamp, and the watch shows the approval and the wattage jumps."

**Gotchas:**
- **Android blocks plain `http://` by default** (API 28+). Add a `network_security_config` that allows cleartext to the plug's IP or LAN, or set `usesCleartextTraffic`.
- **Venue Wi-Fi often isolates clients**, so phone, plug, and watch can't see each other. **Fallback: put the plug and the watch on the phone's hotspot**, or bring a travel router. Pin the plug's IP; don't rely on mDNS/UDP discovery (Android needs a `MulticastLock`).
- Shelly can have digest auth turned on. Leave it off for the demo, or implement SHA-256 digest. Mention local-auth-on in the security slide.
- Termux: install `pkg install python python-cryptography` before `pip install python-kasa` so Rust doesn't compile on the phone **[UNVERIFIED package name; check `pkg search cryptography`]**.

---

## (c) MCP: other assistants driving AgentOS (and us calling them)

### What "Grok bot" and "OpenAI dots" most likely mean (as of 2026-10-26)

**OpenAI Dots: high confidence.** Launched at DevDay on **2026-09-29**. These are persistent, always-on agents in ChatGPT, running on "GPT-6 Astra". Each has its own cloud computer and browser and "connects to over 4,000 apps through OpenAI's plugin ecosystem". You can message them from Slack, Teams, the ChatGPT mobile app (but can't create one on mobile), and in a US-only Pro SMS beta. Available on Pro (outside EEA/CH/UK) and Business Premium, with Enterprise in beta. In background mode, connected apps are **read-only**. Local computer access goes through the ChatGPT desktop app. Not mentioned: custom MCP, an Android app, or a public API.
- https://techcrunch.com/2026/09/29/openai-launches-dots-its-bubbly-agentic-avatar/
- https://www.cnbc.com/2026/09/29/openai-devday-2026-live-updates.html
- https://www.datacamp.com/blog/openai-dots (secondary, most detailed)
- https://openai.com/index/how-we-build-safety-security-and-privacy-into-dots/ (primary safety post; couldn't fetch, 403)
- No other plausible meaning found.

**Grok Bot: medium-high confidence.** xAI's persistent computer-use agent, in beta since **around 2026-08-11**, built with Cursor. Runs on desktop (macOS/Windows) plus an iPhone companion. **Android was not supported at launch.** Access through SuperGrok Heavy, Cursor Ultra, or Cursor Teams Premium. It "can use connectors, plugins, and MCP servers". xAI's docs warn that all Bots on one account share one computer, so they are not a security boundary. **[Mostly secondary sources; x.ai/bot returned 403.]**
- https://composio.dev/content/guide-to-frok-bot · https://www.layer3labs.io/guides/what-is-grok-bot · https://www.vellum.ai/blog/official-grok-bot-breakdown · primary (unfetched): https://x.ai/news/introducing-grok-bot
- Other candidates: (2) the **@grok reply bot on X** (older, often confused with Grok Bot); (3) the **Grok app with Connectors** (web/iOS/Android), which is what actually accepts our custom MCP URL: https://x.ai/news/grok-connectors
- Context: xAI was acquired by SpaceX (Feb 2026) and renamed "SpaceXAI" **[UNVERIFIED, secondary]**.

**Implication:** we can't call Dots or Grok Bot through an API. What works: (1) **they call us** through an MCP connector, or (2) we message them through Slack. Both cost $200-300/month for access, so the demo should likely use **Claude + ChatGPT dev mode + Grok app connectors**. Show Dots and Grok Bot as "same MCP server; works with any MCP client".

### Which clients accept a custom remote MCP server

| Client | Custom remote MCP? | Auth accepted | Notes |
|---|---|---|---|
| **Claude** (web/desktop/mobile) | Yes, on Free (1 connector), Pro, Max, Team, Enterprise: Settings > Connectors > Add custom connector | **None (authless)**, OAuth DCR, OAuth CIMD, own client ID; static header auth is **beta for limited orgs** | Callback `https://claude.ai/api/mcp/auth_callback`; needs `401` + `WWW-Authenticate: Bearer resource_metadata=…`; 10 s OAuth endpoint timeout; egress from `160.79.104.0/21`. https://claude.com/docs/connectors/building/authentication · https://www.claude.com/docs/connectors/custom/remote-mcp |
| **Claude Code / Codex CLI** | Yes, local config; no tunnel needed on the laptop | Headers OK | `claude mcp add --transport http agentos https://…/mcp`; Codex `[mcp_servers.agentos] url=…` in `~/.codex/config.toml` **[UNVERIFIED exact key]**. This matters because our ChatGPT-subscription path runs through the Codex app-server. |
| **ChatGPT** | Yes, through **Developer mode** (Settings > Apps & Connectors > Advanced; the menu location moved during 2026) | **OAuth 2.1 + PKCE S256** (CIMD preferred, DCR OK, predefined client); **no static API keys**; per-tool `noauth` allowed for read-only tools | Redirect `https://chatgpt.com/connector_platform_oauth_redirect` (or `/connector/oauth/{callback_id}`). Plan gating is **disputed**: Plus/Pro may be read-only and full write may need Business/Enterprise **[UNVERIFIED]**. https://developers.openai.com/apps-sdk/build/auth · https://help.openai.com/en/articles/12584461 (403 to fetcher) |
| **Grok app** (web/iOS/Android) | Yes: grok.com/connectors > New Connector > Custom > paste URL | "complete any required authentication" (OAuth presumably) | Must be **public internet**; private IPs rejected; Cloudflare quick tunnels fine for Streamable HTTP, **not SSE**; free tunnel URLs change on restart, so you must re-add the connector. https://docs.x.ai/grok/connectors · https://docs.x.ai/grok/connectors/custom-mcp-tunneling |
| Grok CLI | Yes | OAuth auto or `--header "Authorization: Bearer …"` | `grok mcp add --transport http agentos https://…/mcp --header …` https://docs.x.ai/build/features/mcp-servers |
| xAI / OpenAI / Anthropic APIs (us calling out) | Yes: "remote MCP tool" in each API | Header/bearer | Useful if the AgentOS router wants a cloud model to use our tools; not needed for the demo. |
| Dots / Grok Bot | Through their plugin/connector ecosystems | - | Custom MCP **[UNVERIFIED]** for both |

**The common denominator:** Streamable HTTP endpoint at `/mcp` + OAuth 2.1 authorization code with PKCE S256 + DCR (or CIMD) + protected resource metadata. Authless works for Claude and Grok, and for ChatGPT only on read-only tools.

### Recommended architecture

```
Claude / ChatGPT / Grok  --HTTPS+OAuth-->  Tailscale Funnel (laptop :443)
                                              |
                                  MCP gateway (FastMCP, laptop)
                                    - GitHub OAuth, allowlist = our GitHub user
                                    - tool tiers, rate limit, audit log
                                              |  tailnet only (100.x), HMAC-signed requests
                                  Phone: AgentOS service (listens on tailnet IP only)
                                    - permission engine -> island card + watch signature
                                    - executor (accessibility/root), plug, watch HR
```
Analogy: the gateway is a front desk in the lobby (public), and the phone is a locked office upstairs (private). Visitors only ever talk to the front desk, and the front desk has to phone upstairs for permission.

- **Gateway:** FastMCP (Python) with `GitHubProvider` (an OAuth proxy that gives MCP clients DCR while using a plain GitHub OAuth app). Reject any GitHub login that isn't ours. https://gofastmcp.com/servers/auth/authentication
  ```python
  auth = GitHubProvider(client_id=..., client_secret=..., base_url="https://<laptop>.<tailnet>.ts.net")
  mcp = FastMCP("AgentOS", auth=auth)
  ```
  For a faster demo, use the TypeScript `@modelcontextprotocol/sdk` (pure JS, also runs in Termux with `pkg install nodejs`) or the Kotlin SDK inside the Android app (`io.modelcontextprotocol:kotlin-sdk-server`, about 0.12-0.13 **[UNVERIFIED]**).
- **Tunnel:** `tailscale funnel --bg 8000` on the laptop. This gives a **stable** `https://<machine>.<tailnet>.ts.net` URL, and Funnel only allows ports 443/8443/10000 (https://tailscale.com/docs/reference/tailscale-cli/funnel). The Tailscale **Android app has no documented Funnel/Serve support** **[UNVERIFIED]**, which is another reason to keep the public side on the laptop. Alternatives: a Cloudflare named tunnel (needs a domain on Cloudflare), or a quick `cloudflared tunnel --url http://localhost:8000` (random URL every run). Cloudflare Access can't sit in front, because ChatGPT can't send service-token headers.
- **Tools to expose** (high-level, with annotations): `get_status`, `read_notifications` (readOnly), `watch_heart_rate` (readOnly), `plug_set(on)` (destructive → watch approval), `run_task(goal)` (destructive → island card + watch approval). **Do not expose raw `tap(x,y)`/`type(text)` to remote callers.**
- **Security checklist:** OAuth plus a single-user allowlist; the phone binds only to the tailnet IP and checks an HMAC from the gateway; validate the `Origin` header and bind to localhost for any local HTTP (the MCP spec's DNS-rebinding warning); per-caller rate limits; append-only audit log shown in an island card; one-tap kill switch that disables the MCP route; treat screen text and notifications returned to callers as **untrusted** (prompt-injection channel) and label them; never put tokens in the URL (the MCP auth spec forbids it).
- **Fallback (when OAuth fights back):** run the gateway **authless** on a quick tunnel URL, kept up only during the demo. Safety then comes entirely from the server side: every write needs the watch's signature, so an attacker who finds the URL can only *request* actions. Say this openly on stage as defense in depth.

---

## Recommended plan and fallback

| Piece | Primary | Fallback |
|---|---|---|
| Watch data | Own Wear app: HR through `MeasureClient` → `MessageClient` → phone | Health Connect steps-today only; or show HR from Samsung Health screenshots (weak) |
| Watch approval | Level 2 signed approvals | Level 1 bridged notification Approve/Deny |
| Plug | Shelly HTTP RPC from the Android app (OkHttp) | Kasa through python-kasa in Termux; or a desk lamp on whatever plug we own, through its vendor's local lib |
| Network | Phone hotspot with plug + watch on it | Travel router |
| MCP | Laptop FastMCP + GitHub OAuth + Tailscale Funnel → phone over tailnet | Authless + quick tunnel + watch-signature gate |
| External assistants | Claude custom connector (most reliable) + Grok connector + ChatGPT dev mode | Claude Code / Codex locally (no tunnel) |

## Risks
1. **ChatGPT plan gating:** writes in dev mode may need Business/Enterprise. Confirm which plan the team has, on day 1.
2. **OAuth discovery failures** are the most common connector error (missing `401` + `resource_metadata`, wrong `resource` URL, token endpoint returning 415 on form-encoded requests). Budget debugging time; test with Claude first.
3. **Data Layer pairing** on a Moto + Galaxy Wearable setup isn't proven; package-name and signing mismatch breaks it silently. De-risk in hour 1 with a "ping" message.
4. **Wear OS 6 permissions:** asking for `BODY_SENSORS` alone on API 36 fails without a clear error.
5. **Venue Wi-Fi client isolation** breaks the plug, watch ADB, and LAN calls. Use the hotspot.
6. **Tunnel/laptop single point of failure** during the demo; free tunnel URLs change, so connectors have to be re-added.
7. **Dots / Grok Bot access** costs $200-300/month and rolls out gradually. Don't make the demo depend on them.
8. **Tapo/TPAP or Tuya cloud-key friction** if the plug we already own is one of those.
9. **Overclaiming security:** the watch signature doesn't stop root malware. State the threat model precisely.

## Estimate (one person; hours)

| Task | Hours |
|---|---|
| Watch dev mode + sideload + Data Layer ping | 1.5-2 |
| Live HR + steps watch → phone | 2.5-4 |
| Level 1 bridged approvals | 1 |
| Level 2 signed approvals (keystore, protocol, verify, audit) | 3-4 |
| Smart plug (Shelly / Kasa / Tuya) | 0.5 / 1.5 / 3 |
| MCP gateway + tools + phone tailnet API | 3-4 |
| OAuth + Funnel + connect Claude, Grok, ChatGPT | 2-4 |
| **Total recommended path** | **about 14-19 h** (about 6-8 h for the fallback path) |
