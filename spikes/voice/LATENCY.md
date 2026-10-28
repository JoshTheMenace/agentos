# Voice latency, 2026-10-28

The test uses macOS `say`, a WAV injected into an AudioContext MediaStream, headless Chrome, and the phone's own Codex app-server. No human voice, approval taps, real-app writes, calendar items, or alarms. The isolated service listens on phone loopback :8793; adb forwards :18793. Production :8787 is left alone during comparisons.

`bench.mjs` loads the production Voice/task/provider modules without schedules, notification rules or a reviewer. Use the same frozen provider snapshot for both sides of each comparison; the Codex task-speed track owns tools.js, providers/codex.js, prompt.js and tasks.js. Its benchmark results are in agentd/bench/RESULTS.md.

## Definitions

- Mic-on → ready: start request to WebRTC data channel open. This includes WAV loading, SDP creation, ICE gathering, phone signaling and connection. Also inspect `session.started`: the transport can open before the model is active.
- Speech end → reply: last microphone RMS sample above .02 to first remote RMS sample above .01, sampled every 20 ms. Speech-end detection waits 400 ms, but calculations use the actual last sample, not the delayed detector event.
- Speech end → task start: last loud microphone sample to background `task.update/running` arrival. This means queued worker starting; it is not time to first model token or first tool. Worker timing records are logged separately.
- Task done → spoken done: terminal `task.update/done` arrival to the next remote audio onset. Confirm the following assistant transcript describes completion. A missing completion is a failure, not a zero.

`?bench` enables JSONL timing logs in voice.html; normal use sends no benchmark logs. `summarize.mjs <latency.jsonl>` prints each run, transcripts and task count. Preserve individual runs and report medians with sample counts; network variation makes single-run wins unreliable.

## Experiments

Baseline pending the shared phone lock (keyboard before/after test). Instrumentation is in place before changing voice behavior.
