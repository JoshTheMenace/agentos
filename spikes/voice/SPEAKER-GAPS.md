# Speaker echo and missing transcripts, 2026-10-28

Root cause reproduced on the Moto's real speaker and microphone, voice-call speaker volume 5/8. The founder's old data-channel events were not retained. agentd retained delegations only; the launcher ignored live transcript events. Android's audio history already showed WebRTC setting MODE_IN_COMMUNICATION and routing to the speaker. The fresh baseline confirmed echoCancellation=true, noiseSuppression=true, autoGainControl=true; AudioFlinger showed Qualcomm Fluence AEC enabled. This was residual echo despite AEC, rather than a missing communication-mode setting.

## Evidence

Times below are milliseconds from the instrumented page run, not wall time.

| Run | Data-channel / UI evidence |
| --- | --- |
| Baseline | Assistant starts at 15370; at 19187 it ends with “one two three four” and a new **user** turn starts with “three,” copied from speaker output. Assistant resumes at 20085. No human spoke. Packet loss 0; max reported jitter 25 ms. |
| First fixed, count to 100 | One assistant turn, 54 seconds of output; no extra user turns, cancellation or truncation. Every output fragment through thirty-nine matches the visible transcript. Scroller top 196.57, height 517, viewport 320: newest text visible. Stopped the test deliberately. |
| Final, complete count to 30 | Assistant starts 14508, ends 51541 (37.0 s). Exactly one user prompt and one assistant turn. No additional user turn, cancellation, or truncation. Full one-through-thirty transcript matches all streamed fragments. Scroll top 144, height 464, viewport 320. Mic re-enabled automatically, state Listening. Packet loss 0; max jitter 18 ms. |
| Tap to interrupt | During the next live count, mic=false/speaker audible; orb tap switches to mic=true/speaker muted. A second tap manually mutes the mic. Warm reopening still replaces the ended mic track. |
| Controls | After completion: orb tap disables mic, second tap enables it. Closing/reopening reuses the same connected PC, ends the old mic track and obtains a live new track. |

JSONL evidence is retained locally under `out/speaker-gaps/` (gitignored), including RTP, audio onsets, every non-delta data-channel event and sampled visible text. Production now retains `voice audio` turn/interruption event metadata in the existing agentd log, without transcripts or audio.

## Fix and code walkthrough

- `web/voice-audio.js` is the shared gate for both clients: explicit AEC + noise suppression, AGC off; disable the mic at the first output transcript/assistant turn; keep it disabled across pauses; reopen only after assistant turn.done **and** 500 ms of quiet playback. It preserves manual mute and warm-session state. An orb tap while speaking mutes playback and opens the mic, allowing the user's next words to interrupt the server naturally.
- `web/app.js` appends the v3 `output_transcript.added` pieces across their distinct item ids. Previously it only rendered completed sideband transcripts: the echo-truncated first turn appeared, but later live text was ignored. Sideband results cannot overwrite the live text. The transcript scrolls to its newest line; CSS only adds a bounded scroll area, keeping A2 typography/colors.
- Android audio mode and routing already worked, so no native audio change or speculative server VAD setting was added. This subscription transport is v3 frameless, with turn.created/done, not the public Realtime response/VAD event schema.
- `agentd/voice.html` shares the same gate; `server.js` and the benchmark proxy serve its script. `voice.audio` forwards diagnostic event metadata into agentd's existing log.

Code-golf pass: one shared gate, no dependencies; ordinary listening, manual mute, interruption, fresh/warm sessions, microphone release, existing error cleanup, camera speech detection and delegation routing are preserved. `audio-test.mjs` exercises gating, pauses, buffered tails, manual mute, interruption, closed/warm sessions, transcript streaming, stale results and scrolling.

## Reproduce

Run from the repo, with the launcher open and the room quiet. The synthetic prompt goes over the existing WebRTC sender, then the sender switches back to the real mic **before** the phone speaks. No contacts, tasks or approvals are involved. The harness restores speaker volume, releases the mic, closes voice and returns Home.

```sh
say -v Samantha -r 160 -o /tmp/voice-count.aiff 'Please count slowly from one to thirty, speaking every number clearly, without stopping or using tools. This is a speaker playback test. Start counting now.'
afconvert /tmp/voice-count.aiff /tmp/voice-count.wav -f WAVE -d LEI16@24000
scripts/withphone node spikes/voice/speaker-test.mjs /tmp/voice-count.wav /tmp/voice-count.jsonl
node spikes/voice/audio-test.mjs
```

The harness asserts 30+ seconds of output, no echo user turn, complete streamed text, bottom scrolling and automatic mic reopening. `node --check` passed for changed agentd files, extracted voice-page JS, app.js, shared gate and both test scripts; launcher APK built and installed. The phone was returned Home with voice closed and the original volume restored.
