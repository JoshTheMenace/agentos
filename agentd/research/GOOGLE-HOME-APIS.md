# Google Home APIs: a same-day path for AgentOS

Researched 2026-10-28. Judging: today, 5 PM Mountain. Research only; no app installed, device controlled, account created, or console configuration changed. Sources are Google's documentation and Google's sample code. Estimates below are engineering estimates, not measured build times.

## Verdict

**Yes, supported cloud-to-cloud devices already linked to the founder's Google Home can be listed, their exposed states read, and supported on/off commands sent without a Nest hub.** Google explicitly includes third-party cloud devices in the Home APIs data model. Its connectivity table permits cloud device state, control, and automation execution locally and remotely with the hub offline. Applying this to a home with no hub is also consistent with Google's cloud-to-cloud overview, which explicitly supports users without a Matter controller. This is a documented implementation path, not a successful test against the founder's home. [Data model](https://developers.home.google.com/apis/android/data-model), [connectivity, table 2](https://developers.home.google.com/apis/android/connectivity), [cloud-to-cloud overview](https://developers.home.google.com/cloud-to-cloud).

The earlier `SMART-HOME-ENERGY.md` conclusion that every Google path requires a hub is incorrect. Its assumption that the founder probably has no devices is superseded by the task's confirmation that real devices are linked. **We still cannot promise every device visible in Google Home will be exposed or controllable:** device types, traits, consent, connectivity, and the manufacturer's integration determine that.

What's missing today is an Android helper using the SDK, OAuth credentials tied to its package and signing certificate, the founder on the OAuth test-user list, and their explicit Home permissions grant. Exact device models, exposed traits, and runtime behavior on this rooted phone remain **unverified**. No general partner approval is documented for the standard test scope; see the important scope distinction below.

For **Matter-only devices**, do not promise a new hub-free setup. Hubless mode appeared in SDK 1.6 on 2025-12-22 but was explicitly removed in 1.7.1 on 2026-03-13. The current SDK prerequisite still requires a compatible Google hub for Matter. [Release history](https://developers.home.google.com/apis/android/release-notes#2026-03-13), [SDK prerequisites](https://developers.home.google.com/apis/android/sdk).

## What the Android APIs expose

| API | Useful capability | Relevance today |
| --- | --- | --- |
| Structure | Homes, IDs/names, rooms and device relationships; streams of permitted structures and rooms | Discover the granted home and organize devices. No hub prerequisite for this cloud graph is documented. |
| Device | Device list, types, traits, attributes, connectivity, and supported commands | Read `OnOff.onOff`; invoke supported `on()`, `off()`, or `toggle()` commands. Other exposed traits include brightness, color, temperatures, thermostat settings, occupancy and locks. |
| Commissioning | Add Matter devices to the Google fabric, optionally also the app's own fabric; Google-managed onboarding UI | Unnecessary for cloud devices already linked to Google Home. Commissioning does not replace the user's Home permissions grant. |
| Automation | Kotlin DSL for starters, conditions and actions; create, list, update, execute and delete app-created automations | A separate feature from an immediate toggle. Trait support varies; the API is not a general listing of every existing personal routine. |

Sources: [Structure API](https://developers.home.google.com/apis/android/structure), [Device API](https://developers.home.google.com/apis/android/device), [supported types and their traits](https://developers.home.google.com/apis/android/supported-device-types), [Commissioning API](https://developers.home.google.com/apis/android/commissioning), [Automation overview](https://developers.home.google.com/apis/android/automation).

The SDK unifies Matter and cloud-to-cloud device types. A Kotlin class under `com.google.home.matter.standard`, or a type beginning with `Google`, does **not** establish the device's transport or manufacturer. A cloud-linked Kasa plug or Govee light is a candidate if its Google integration exposes a supported type and trait. This is an inference from Google's unified model, not a verified compatibility claim for either brand or the founder's models. Check supported attributes/commands at runtime. [Data model](https://developers.home.google.com/apis/android/data-model).

An on/off value is reported device state, not measured power consumption. A null/unavailable trait or offline device must not become `off` in AgentOS. Do not promise watts, energy history, or every manufacturer-app feature merely because a device appears in Google Home.

## Exactly when a hub is needed

The check/cross symbols disappear in plain-text exports of Google's connectivity page. I inspected the actual HTML `compare-yes` / `compare-no` cells. Table 2 says:

| Operation with hub offline | Matter, same home Wi-Fi | Matter, remote | Cloud, local or remote |
| --- | --- | --- | --- |
| Read state / issue control | Yes | No | Yes |
| Commission and add | No | No | Not applicable; cloud account linking is separate |
| Create automation | Yes | Yes | Yes |
| Execute automation | No | No | Yes |

[Connectivity tables and troubleshooting](https://developers.home.google.com/apis/android/connectivity). Local means the controller and device share the home Wi-Fi; remote includes the hackathon phone being away from the home.

**Caveat:** the table's existing-Matter local-control allowance is not a guarantee that a home which has never had a hub can onboard and operate new Matter devices. The release notes removed hubless mode, and the current commissioning table disallows commissioning with the hub offline. The old release-note link to `connectivity#hubless_mode` no longer has a matching section. Use the cloud route today; treat hub-free Matter onboarding as unsupported by the current evidence.

For Matter remote control or execution of Matter automations, supply an online supported Google hub in the home. For Thread devices, the home also needs a Thread Border Router. Supported Home API hubs include Google Home, Nest Audio, Nest Hub, Nest Hub (2nd gen), Nest Hub Max, Nest Mini, Google TV Streamer (4K), and Nest Wifi Pro; not all are Thread Border Routers. A Nest display specifically is not mandatory. [Supported hubs](https://developers.home.google.com/apis/android/sdk#supported_hubs).

## Phone and build requirements

Read-only ADB checks against the connected Moto confirmed:

| Requirement | Official requirement | Observed today |
| --- | --- | --- |
| Phone OS | Android 10 / API 29 or later | Android 13 / API 33: passes |
| Play services | SDK 1.11.1 requires 26.40.32 | Active package 26.41.31: passes the version check |
| Google Home app | Current release notes accept any version | `com.google.android.apps.chromecast.app`, 4.34.22.2 installed |
| Home/account/devices | User must have access to a home with supported devices | Signed in and real devices confirmed by task; device inventory not read |

[Android prerequisites](https://developers.home.google.com/apis/android/sdk), [1.11.1 release requirements, 2026-10-22](https://developers.home.google.com/apis/android/release-notes#2026-10-22).

Checks used only `adb devices`, `getprop`, `pm list packages` and filtered `dumpsys package`. The latter also lists a retained 23.30.13 system copy of Play services; the active package block reports 26.41.31. Version compliance does not prove the Home service/module is ready. Root-specific compatibility and SDK execution were not tested.

The local toolchain is useful but incomplete for the current sample: `tooling/android-env.sh` supplies JDK 17, SDK platforms 33/34 and build-tools 34.0.0; `gradle` is installed. The sample currently requests compile/target SDK 36, minimum 29, Java 17, Kotlin 2.4.0, AGP 8.10.0, and wrapper Gradle 9.2.1. Provision the requested compile SDK/build dependencies instead of assuming the launcher's javac/D8 build can consume this unchanged. Compile SDK 36 does not prevent running on Android 13. [Sample Gradle configuration](https://github.com/google-home/google-home-api-sample-app-android/blob/aeba7528fe6e4085d6e3c84def38dfe72094558f/app/build.gradle.kts), [version catalog](https://github.com/google-home/google-home-api-sample-app-android/blob/aeba7528fe6e4085d6e3c84def38dfe72094558f/gradle/libs.versions.toml), [wrapper](https://github.com/google-home/google-home-api-sample-app-android/blob/aeba7528fe6e4085d6e3c84def38dfe72094558f/gradle/wrapper/gradle-wrapper.properties).

SDK 1.11 requires Kotlin 2.4.0 and explicit Kotlin coroutine dependencies; missing coroutine dependencies can cause a blocking runtime error. [Release notes](https://developers.home.google.com/apis/android/release-notes#2026-09-23).

## OAuth, registration and testing limits

For personal/testing access, Google's documented route is:

1. Use a Google Cloud project and configure an External OAuth consent screen in Testing. Add the founder's exact Google account under Audience → Test users.
2. Create an Android OAuth client with the helper's application/package ID and debug signing certificate SHA-1. A later package or signing-key change requires corresponding OAuth configuration.
3. The Android OAuth setup guide says no scopes need to be manually added to the consent-screen configuration; the SDK requests its Home scope during permissioning.
4. Register the Activity result caller, check `hasPermissions()`, then call `requestPermissions()`. Google presents account selection, home selection and access consent. Being signed into the Google Home app alone does not authorize AgentOS.

[OAuth setup](https://developers.home.google.com/apis/android/oauth), [permission implementation](https://developers.home.google.com/apis/android/permissions).

Grants are per structure; the permissions guide documents one granted structure per app at a time. Unregistered apps are unverified, are limited to **100 OAuth test users**, and can test supported device types. Sensitive devices can require individual consent. Users can revoke access through Google Account connections or Google Home → Settings → Linked Apps. No Nest Device Access $5 fee is part of this Android Home APIs procedure. [Permissions and test/production distinction](https://developers.home.google.com/apis/android/permissions#grant_permissions).

Google explicitly says Developer Console registration is **not required to test and use** Home APIs. Production requires approved device types and OAuth brand verification; completing Home registration lifts the separate 100-user Home limit, including when reusing a verified OAuth client. The documentation still labels Home APIs registration unavailable and the get-started roadmap labels registration/Play launch “Coming soon.” These statements coexist with GA release notes: logged-in production registration availability is **unverified**, not a reason to block the documented test route. A project already pending OAuth verification can produce an access-blocked error; do not submit a fresh personal test app for production verification merely to try it today. [OAuth caveats](https://developers.home.google.com/apis/android/oauth#if_you_have_an_existing_oauth_client), [current roadmap](https://developers.home.google.com/apis/android/get-started).

**Scope trap:** the current sample defaults to `https://www.googleapis.com/auth/home.platform.v2`; many v2 features need partner approval. Google instructs ordinary testers to use `https://www.googleapis.com/auth/home.platform.selected.devices` by setting `HOME_PLATFORM_SCOPE_VERSION_1` in **both** `HomeModule.kt` and `HomeClientProvider.kt`'s `switchAccount()`. Otherwise changing accounts restores v2. There is no documented partner allowlist prerequisite for basic device state/on-off testing with v1 and OAuth test users. [Sample build instructions](https://developers.home.google.com/apis/android/sample-app/build), [authorization guide](https://developers.home.google.com/apis/android/sample-app/authorization).

## Smallest practical implementation today

The official repository is [google-home/google-home-api-sample-app-android](https://github.com/google-home/google-home-api-sample-app-android), not `google-home/sample-apps-for-android` (the suggested URL returned 404). Inspected main commit: `aeba7528fe6e4085d6e3c84def38dfe72094558f`, sample version 1.11.0. It already provides permissioning, structure/device views and state/control examples. Google's [Android codelab](https://developers.home.google.com/codelabs/home-apis-android-build-mobile-app) also walks these APIs, but its hub/virtual-Matter setup is unnecessary for already linked cloud devices.

Fastest proof: adapt and run the sample separately, with a unique package and v1 scope. Keep its existing light/plug controls; skip camera, commissioning and automation work. For a smaller AgentOS helper, retain these pieces:

- [HomeModule.kt](https://github.com/google-home/google-home-api-sample-app-android/blob/aeba7528fe6e4085d6e3c84def38dfe72094558f/app/src/main/java/com/example/googlehomeapisampleapp/HomeModule.kt): construct the registry and singleton Home client. Register `OnOff` and the relevant light/plug types; omission can hide usable capabilities.
- [PermissionsManager.kt](https://github.com/google-home/google-home-api-sample-app-android/blob/aeba7528fe6e4085d6e3c84def38dfe72094558f/app/src/main/java/com/example/googlehomeapisampleapp/PermissionsManager.kt): Activity-bound consent and permission-state tracking.
- [DeviceViewModel.kt](https://github.com/google-home/google-home-api-sample-app-android/blob/aeba7528fe6e4085d6e3c84def38dfe72094558f/app/src/main/java/com/example/googlehomeapisampleapp/viewmodel/devices/DeviceViewModel.kt): device type/trait resolution and displayed state.

The proposed execution flow is: `Home.getClient(...)` → consent → `structures()` / `devices()` flows → registered `device.types()` → appropriate type's `standardTraits.onOff` → read `onOff` or issue a supported command. Resolve the correct type/part so a combined speaker/light doesn't toggle the wrong function. Catch `HomeException`, preserve unknown/offline state and observe the resulting state rather than assuming a successful request proves the appliance changed. [Device access and metadata](https://developers.home.google.com/apis/android/device), [initialization](https://developers.home.google.com/apis/android/initialize).

SDK acquisition is a real setup step: Google's guide requires signed-in SDK download and local hosting. The sample resolves Home framework/types artifacts through Maven local. Its catalog requests `play-services-home` and `play-services-home-types` 17.1.0. Google's public Maven metadata checked today exposes only the older Home 16.0.0 artifact, not the new types artifact. Do not substitute that old commissioning library for the Home APIs SDK. Download the matching SDK bundle and follow its local dependency instructions. Actual download access was **not tested**. [SDK setup](https://developers.home.google.com/apis/android/sdk#set_up_the_sdk), [sample repository settings](https://github.com/google-home/google-home-api-sample-app-android/blob/aeba7528fe6e4085d6e3c84def38dfe72094558f/settings.gradle.kts), [Google Maven metadata](https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-home/maven-metadata.xml).

| Deliverable | Estimate, assuming founder immediately handles consent/setup |
| --- | --- |
| Cloud/OAuth configuration and SDK acquisition | 30–60 minutes; account/download problems can extend this |
| Toolchain dependencies, sample scope/package adjustment, first build | 45–90 minutes |
| List real devices, confirm states and one safe on/off round trip | 30–60 minutes |
| Connect the helper to AgentOS's existing authenticated broker and verify tool behavior | Another 1–2 hours |

Allow **2–4 hours for a working sample**, **3–6 hours for an AgentOS tool integration**, with meaningful deadline risk. No APK was built in this research task. A small Gradle helper avoids converting the launcher build, but AgentOS's Android broker must still enforce authorization and audit controls; an unauthenticated exported receiver is not an acceptable shortcut. These are implementation recommendations based on `ARCHITECTURE.md`, not Google requirements.

## Nest Smart Device Management (SDM)

SDM is an official **REST API for supported Nest devices**, not a general Google Home device inventory. It covers Nest thermostats, cameras, doorbells and the Nest Hub Max's camera capabilities. It does not expose Govee/Kasa plugs, arbitrary Google Home devices, or Nest temperature sensors. Supported camera streams/events vary by model. A separate Nest hub is not needed to call this cloud API for an already activated supported device. [Supported models](https://developers.google.com/nest/device-access/supported-devices), [getting started](https://developers.google.com/nest/device-access/get-started).

Thermostat traits include temperature, humidity, HVAC status, mode, setpoints and fan control. Commands can change modes/setpoints and run the fan; this is not generic outlet on/off or whole-home energy metering. [Thermostat guide](https://developers.google.com/nest/device-access/api/thermostat).

Setup requires the founder to accept Device Access terms and pay a **one-time, non-refundable US$5 per account**, using a consumer Google account. Then enable SDM in a Google Cloud project, create OAuth credentials and a Device Access project, and associate its OAuth client ID. Personal projects can remain in Sandbox indefinitely. [Registration](https://developers.google.com/nest/device-access/registration), [project setup](https://developers.google.com/nest/device-access/project/create).

The user authorizes through Partner Connections Manager and OAuth with `https://www.googleapis.com/auth/sdm.service`; exchange the authorization code for access/refresh tokens. Personal use does not require OAuth API verification, though an unverified-app screen can appear. The first `GET https://smartdevicemanagement.googleapis.com/v1/enterprises/{device-access-project-id}/devices` completes authorization and returns devices/traits. Access tokens last one hour; testing refresh tokens can expire after seven days. Pub/Sub is optional for initial polling, useful later for events. [Authorization](https://developers.google.com/nest/device-access/api/authorization), [first list call and token handling](https://developers.google.com/nest/device-access/authorize).

Sandbox limits are 3 projects, 5 structures and 25 users across the developer account; each structure allows 5 users, excluding the developer account from that per-structure count. [SDM limits](https://developers.google.com/nest/device-access/project/limits).

Estimate: **45–90 minutes to first REST list/read**, **1.5–3 hours for a small AgentOS integration**, if a supported Nest device is already present and payment/OAuth work immediately. Nest device ownership is **unverified**. No registration or payment was performed. SDM is an attractive fallback only for Nest, not a replacement for the cloud-linked light/plug path.

## Fastest official path and founder's steps

**Default recommendation: the v1 Android sample against one existing cloud-linked light or plug.** It is documented for personal testing, avoids hardware acquisition and can work away from the home. The main blockers are software setup and consent, not the absence of a Nest hub.

1. Founder identifies one noncritical light/plug already controllable in Google Home and confirms whether it is cloud-linked or Matter-only. Supply its model and home; do not assume a brand's entire range uses one transport.
2. Founder uses their existing Google account to obtain the Home SDK and access a Cloud project. Configure the helper's Android OAuth client, exact package/SHA-1 and founder test-user email. No production verification application is needed for this test route.
3. Builder adapts the official sample with both v1 scope settings and installs its missing build dependencies. Build/install/phone changes are future implementation work, not authorized or performed by this research report.
4. Founder grants the sample access to the correct account/home. Builder verifies enumeration, reported state and command support before an explicitly authorized on/off test. Keep a device-count/trait dump to distinguish unsupported devices from missing consent.
5. Only after that proof, connect the helper through the AgentOS broker. If this requires new SDK access approval, blocks at OAuth, or consumes the remaining rehearsal window, stop implementation rather than promise the physical demo. If the target is Matter-only and remote, the missing piece is an online supported hub at home, plus a Thread Border Router for Thread devices.

One newer official option could be faster **if the founder is already entitled**: [Google Home MCP](https://developers.home.google.com/mcp/home) exposes `list_homes`, `list_home_resources`, `list_home_states` and `run_home_actions` at `https://home.googleapis.com/mcp`. It requires an active Google Home Premium Advanced subscription, Cloud/OAuth setup, and the guide describes access approval. Google's [September announcement](https://developers.home.google.com/newsletters/2026/09) says early access is rolling out in English in the US. Existing entitlement/approval is **unverified**; no subscription or application was made. If already available, estimate 30–90 minutes to prove access from an MCP client, plus integration time. It is not the default no-signup plan. Home Developer MCP supplies documentation, not access to the user's devices.

## Evidence limits

This report verifies official requirements, public source code, local build-tool inventory and phone package/OS versions. It does **not** verify console eligibility, SDK download entitlement, a successful build, Home service readiness, the founder's device inventory or any command round trip. Existing local changes were left alone. `SMART-HOME-ENERGY.md` and `STATUS.md` were read for context and were not edited.
