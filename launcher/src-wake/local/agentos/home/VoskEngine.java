package local.agentos.home;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

/** Key-free fallback: Vosk (Kaldi) speech recognition squeezed into a keyword spotter by a tiny grammar. The extra words are
 *  decoys: "the agent replied" decodes as "the agent", not "hey agent" (launcher/README.md "Wake word" has the tests). */
public class VoskEngine implements Wake.Engine {
  static final String GRAMMAR = "[\"hey agent\", \"hey\", \"agent\", \"the\", \"a\", \"an\", \"[unk]\"]";
  final Model model; final Recognizer rec; int stable; String last = "";
  public VoskEngine(String dir) throws Exception { model = new Model(dir); rec = new Recognizer(model, Wake.RATE, GRAMMAR); }
  @Override public int frame() { return 1600; } // 100 ms
  @Override public boolean hit(short[] pcm) {
    // Fires when the partial result is exactly "hey agent" for 300 ms (or a final result is), i.e. said after a pause and
    // followed by a short one, like a real wake word: ~0.9 s from the start of the phrase. Looser rules ("contains", "starts
    // with") fired on the phone during back-to-back speech ("Hey, how are you doing" heard as "hey agent …").
    boolean fin = rec.acceptWaveForm(pcm, pcm.length);
    String t;
    try { t = new JSONObject(fin ? rec.getResult() : rec.getPartialResult()).optString(fin ? "text" : "partial"); } catch (Exception e) { return false; }
    if (t.contains("agent") && !t.equals(last)) android.util.Log.d("AgentOSWake", (fin ? "final " : "partial ") + t); // tuning: logcat -s AgentOSWake:D
    last = t;
    stable = "hey agent".equals(t) ? stable + 1 : 0;
    if (stable < 3 && !(fin && stable > 0)) return false;
    stable = 0; rec.reset(); return true;
  }
  @Override public void close() { rec.close(); model.close(); }
}
