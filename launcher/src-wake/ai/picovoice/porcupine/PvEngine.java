package ai.picovoice.porcupine;

import local.agentos.home.Wake;

/** Porcupine straight through its JNI class (same package, so package-private PorcupineNative is reachable). This skips the
 *  Porcupine wrapper, whose static init needs OkHttp (only for its train-from-phrase call), so we ship classes.jar + the .so only. */
public class PvEngine implements Wake.Engine {
  static { System.loadLibrary("pv_porcupine"); PorcupineNative.setSdk("android"); }
  final long h;
  public PvEngine(String key, String model, String keyword) throws Exception { h = PorcupineNative.init(key, model, "best", new String[] {keyword}, new float[] {0.6f}); }
  @Override public int frame() { return PorcupineNative.getFrameLength(); }
  @Override public boolean hit(short[] pcm) throws Exception { return PorcupineNative.process(h, pcm) >= 0; }
  @Override public void close() { PorcupineNative.delete(h); }
}
