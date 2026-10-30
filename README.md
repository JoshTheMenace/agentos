<img src="assets/icon.svg" width="64" height="64" alt="">

# AgentOS

An agent-native layer for Android. The assistant can see the screen, use any app, remember what matters, build you apps and mod the ones you have. The OS, not the model, decides what it's allowed to do.

Runs on a real $200 Moto G 5G (2023), Android 13. Built for the Nucleus Institute × Startup State hackathon.

**The model proposes. The OS decides.**

## Screenshots

<table>
<tr>
<td width="33%"><img src="social/launch/01-home-ask.png" alt="Home with the ask bar answering 'what smart home devices do I have?'"></td>
<td width="33%"><img src="social/launch/04-keyboard.png" alt="AI keyboard suggesting calendar-aware replies"></td>
<td width="33%"><img src="social/launch/03-assistant-bar.png" alt="Assistant bar answering inside LinkedIn"></td>
</tr>
</table>

<img src="social/launch/02-across-apps.png" alt="Reads Alex's message in Pigeon, then finds the place in Google Maps">
<img src="social/launch/07-make-an-app.png" alt="Builds a Japanese learning app from one sentence">
<img src="social/launch/06-charts.png" alt="Galaxy Watch sleep chart and the clean-power window for the Christmas tree">
<img src="social/launch/08-security.png" alt="Store apps need the owner's tap; Prompt Guard catches a message with hidden instructions">
<img src="social/launch/05-memory.png" alt="What it knows about a person, and the people graph">
<img src="social/launch/09-linkedin-before-after.png" alt="LinkedIn promoted post, before and after the ad mod">

## What it does

- **Talk to it:** voice with interruptions, an offline "Hey Agent" wake word, camera and screen vision
- **Uses your apps:** reads the screen and taps and types like a person, chains steps across apps, runs scheduled tasks while the phone sleeps
- **AI home screen:** the agent's status, "For you" suggestions with drafts ready, answers as charts
- **AI keyboard in every app:** whole replies from the chat on screen, calendar-aware, rewrite, translate, write for me
- **Memory:** people across Slack, Discord, email, calendar and contacts, a people graph, and only the sources you allow
- **Builds and mods apps:** multi-screen micro-apps from one sentence (data access only with your OK), and mods to real apps like hiding LinkedIn ads
- **Smart home + clean energy:** your devices plus real utility rates and live grid data
- **Health:** Galaxy Watch sleep and heart data
- **Any brain:** Codex or Claude, or an on-phone model that takes over automatically when you're offline. Grok can drive the phone remotely.

## Security

- Every action is checked: a reviewer auto-approves safe steps, risky ones ask you, and money and installs need your fingerprint
- Meta's Prompt Guard 2 runs on the phone and catches prompt injection; scam messages are flagged before the AI reads them
- The AI can't see or tap its own approval cards, and it never gets a shell or root
- Per-app tracker and ad blocking that only the owner can change
- A hash-chained activity log of everything it does, and a password vault the AI never sees

## More

- [ARCHITECTURE.md](ARCHITECTURE.md): how the pieces fit together
- [pitch/FEATURES.md](pitch/FEATURES.md): every feature, with timings · [pitch/SECURITY-FEATURES.md](pitch/SECURITY-FEATURES.md)
- [STATUS.md](STATUS.md): what's working right now
