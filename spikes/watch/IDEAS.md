# Galaxy Watch4 ideas, ranked by "whoa" per hour (2026-10-27)

Score = how much it makes a judge sit up, divided by the hours it costs us with no Gradle/AndroidX.

| # | Idea | Whoa | Hours | Needs agentd/broker change? | Verdict |
|---|---|---|---|---|---|
| 1 | **Wrist status + buzz**: the watch shows what the agent is doing ("Reply to Sam · Typing in Messages"), buzzes when it needs you, double-buzzes when done | High: the agent is visibly "with you" off the phone | 2 | No (agentd already broadcasts task.state, audit.append, approval.*, review) | **Built** (`watch/`) |
| 2 | **Tap to talk on the wrist**: speak, the text goes to agentd as an `ask`, the reply shows on the watch | Very high: "the Siri we should have" on your wrist | +0.5 on top of #1 | No | **Built** (needs the watch's speech recognizer; unverified) |
| 3 | **Approve on the wrist** (tap, for ordinary actions) | High | 1 watch + 1 broker | Yes: a `watch` channel in allowed_channels and an approve path for the watch | NEEDS.md |
| 4 | **Watch as a second factor** (watch Keystore signs a hash of the exact action; phone checks it) | Very high for the security thread | 3-4 + broker | Yes (broker verifies the signature) | NEEDS.md; strong pitch beat, too big before freeze unless someone owns the broker side |
| 5 | **Heart rate / steps as real context** ("you're at 140 bpm, want me to hold notifications?") | Medium (cool, but the founder dropped HR from home) | 2 (SensorManager + BODY_SENSORS, no Health Services lib) | Yes: a `context` message agentd adds to the prompt | Later |
| 6 | Tile / complication on the watch face | Medium | 3+ (needs androidx.wear.tiles + protolayout + guava AARs) | No | Skip: the ongoing notification gives a glance for free |

Transport: Data Layer needs play-services-wearable (and its AndroidX dependency chain) plus a phone-side companion with the same package and signing key. Too heavy without Gradle. We use agentd's existing WebSocket instead:
- today: an adb tunnel through the Mac (watch 127.0.0.1:8787 → Mac → phone 127.0.0.1:8787). Zero agentd change, but the Mac must stay connected.
- better: agentd also listens on the phone's Wi-Fi/hotspot IP with a watch-scoped token (watch/NEEDS.md).
