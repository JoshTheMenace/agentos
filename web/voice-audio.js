// AEC can still leak speaker audio on Android. Keep the mic closed for the whole reply, including pauses.
const voiceConstraints = { echoCancellation: true, noiseSuppression: true, autoGainControl: false };
class VoiceAudio {
  constructor(track, speaker, changed = () => {}) {
    Object.assign(this, { track, speaker, changed, active: true, speaking: false, done: false, muted: false, interrupted: false, lastAudio: 0 });
    this.apply();
  }
  apply() {
    this.track.enabled = this.active && !this.muted && !this.speaking;
    this.speaker.muted = !this.active || this.interrupted;
    this.changed(this.speaking ? 'Speaking · tap to interrupt' : this.muted ? 'Muted' : 'Listening');
  }
  event(m) {
    if (m.type === 'turn.created' && m.turn?.role === 'user') this.interrupted = false;
    if (this.interrupted) return;
    if (m.type === 'output_transcript.added' || (m.type === 'turn.created' && m.turn?.role === 'assistant')) {
      this.speaking = true; this.done = false; this.lastAudio = Date.now(); this.apply();
    }
    if (m.type === 'turn.done' && m.turn?.role === 'assistant') { this.done = true; this.lastAudio = Date.now(); }
  }
  sample(level, now = Date.now()) {
    if (level > 0.005) this.lastAudio = now;
    if (this.speaking && this.done && now - this.lastAudio > 500) { this.speaking = false; this.apply(); }
  }
  toggle() {
    if (this.speaking) { this.interrupted = true; this.speaking = this.muted = false; }
    else this.muted = !this.muted;
    this.apply();
  }
}
