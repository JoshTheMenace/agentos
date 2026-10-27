package local.agentos;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Llama Prompt Guard 2 (22M) on the phone: scores untrusted screen text for prompt injection (research/04 3a).
 * Like a metal detector at the door: every third-party text node walks through it before the model sees it.
 * The model dir holds model.onnx + tokenizer.json; the first dir that has both wins (swap models by copying files):
 * files/models/prompt-guard/, then /data/local/tmp/agentos/models/prompt-guard/. Scores are cached by SHA-256 of
 * the text, so rereading an unchanged screen costs ~0 ms. Inference runs on the RPC worker thread, never the UI thread.
 * Built with Llama. Llama 4 is licensed under the Llama 4 Community License, Copyright (c) Meta Platforms, Inc.
 */
final class PromptGuard {
    static final double FLAG = 0.5, SCREEN_FLAG = 0.9; // a node alone at 0.5; the whole screen joined only when sure (split injections score ~0.97)
    private static final int WINDOW = 510, BATCH_TOKENS = 4096; // 512 positions minus [CLS]/[SEP]; tokens per batch
    // Fragments under 3 tokens (names, times, button labels) aren't scored alone: PG2-22M is unreliable on them
    // ("Unknown number" scores 0.98 even in fp32). They still count inside the whole-screen score.
    private static final int MIN_TOKENS = 3, LABEL_TOKENS = 8;
    private static File[] dirs = {};
    private static OrtEnvironment env;
    private static OrtSession session;
    private static Pg2Tokenizer tok;
    private static String model, stamp = "", error = "not loaded yet";
    private static final Map<String, Double> cache = new LinkedHashMap<String, Double>(512, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Double> e) { return size() > 4096; }
    };

    /** Called once at startup: remember where models live and load in the background so the first read is fast. */
    static void init(Context c) {
        dirs = new File[]{new File(c.getFilesDir(), "models/prompt-guard"), new File("/data/local/tmp/agentos/models/prompt-guard")};
        new Thread(PromptGuard::ready, "agentos-guard-load").start();
    }

    /** Loads the model, or reloads it when the files changed (a swap is just a file copy). */
    private static synchronized boolean ready() {
        for (File d : dirs) {
            File m = new File(d, "model.onnx"), t = new File(d, "tokenizer.json");
            if (!m.canRead() || !t.canRead()) continue;
            String now = m + ":" + m.length() + ":" + m.lastModified() + ":" + t.lastModified();
            if (now.equals(stamp)) return session != null; // unchanged; a broken file isn't retried until it changes
            stamp = now;
            try {
                long t0 = SystemClock.uptimeMillis();
                if (session != null) session.close();
                session = null;
                synchronized (cache) { cache.clear(); }
                tok = new Pg2Tokenizer(t);
                env = OrtEnvironment.getEnvironment();
                env.setTelemetry(false); // belt and braces: the AAR's telemetry provider is never merged into our manifest
                OrtSession.SessionOptions o = new OrtSession.SessionOptions();
                o.setIntraOpNumThreads(2); // the two big A76 cores
                session = env.createSession(m.getPath(), o);
                model = m.getPath();
                Log.i("AgentOS", "prompt guard loaded " + model + " in " + (SystemClock.uptimeMillis() - t0) + " ms");
                return true;
            } catch (Exception e) { error = "load " + m + ": " + e; Log.w("AgentOS", error); return false; }
        }
        if (session == null) error = "no model.onnx + tokenizer.json in " + dirs[0] + " or " + dirs[1];
        return session != null; // files vanished: keep the loaded model
    }

    /** guard.classify {text} */
    static JSONObject classify(String text) throws Exception {
        long t0 = SystemClock.uptimeMillis();
        if (!ready()) return new JSONObject().put("ok", false).put("error", error);
        boolean cached;
        synchronized (cache) { cached = cache.containsKey(sha(text)); }
        double p = score(java.util.Collections.singletonList(text))[0];
        return new JSONObject().put("ok", true).put("score", round(p)).put("label", label(p)).put("cached", cached)
                .put("ms", SystemClock.uptimeMillis() - t0).put("model", model);
    }

    /** screen.read hook: a score on every untrusted (APP:*) node, plus {score, label, flagged_node_ids} for the screen. */
    static void annotate(JSONObject screen) throws JSONException {
        long t0 = SystemClock.uptimeMillis();
        JSONObject g = new JSONObject();
        screen.put("guard", g);
        try {
            if (!ready()) { g.put("label", "unavailable").put("error", error); return; }
            JSONArray nodes = screen.getJSONArray("nodes");
            List<JSONObject> scored = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject n = nodes.getJSONObject(i);
                String t = (n.optString("text") + "\n" + n.optString("desc")).trim();
                if (!n.optString("src").startsWith("APP:") || t.isEmpty() || t.equals("(hidden: secure screen)")) continue;
                int len = tok.encode(t).length;
                // A short label on a text-less button ("History mode off", "delete", "all clear") is the app's own chrome, and
                // PG2-22M reads a list of them as commands (Calculator scored 0.99). Skip them everywhere; sentences still count.
                if (n.optString("text").isEmpty() && String.valueOf(n.optJSONArray("actions")).contains("click") && len < LABEL_TOKENS) continue;
                all.append(t).append('\n');
                if (len < MIN_TOKENS) continue;
                scored.add(n);
                texts.add(t);
            }
            texts.add(all.toString()); // the whole screen as one text: catches injections split across nodes
            double[] p = score(texts);
            double max = p[p.length - 1] >= SCREEN_FLAG ? p[p.length - 1] : 0; // joined UI snippets score ~0.6 (Calculator's first run)
            JSONArray flagged = new JSONArray();
            for (int i = 0; i < scored.size(); i++) {
                scored.get(i).put("guard", round(p[i]));
                if (p[i] >= FLAG) flagged.put(scored.get(i).getInt("id"));
                max = Math.max(max, p[i]);
            }
            g.put("score", round(max)).put("label", label(max)).put("flagged_node_ids", flagged)
                    .put("screen_score", round(p[p.length - 1])).put("scored_nodes", scored.size());
        } catch (Exception e) {
            Log.w("AgentOS", "prompt guard failed", e);
            g.put("label", "error").put("error", String.valueOf(e));
        } finally { g.put("ms", SystemClock.uptimeMillis() - t0); }
    }

    /** P(MALICIOUS) per text; long texts are split into 510-token windows and take the max. New texts run in batches. */
    private static synchronized double[] score(List<String> texts) throws Exception {
        if (!ready()) throw new IllegalStateException(error);
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("prompt guard must not run on the UI thread");
        double[] out = new double[texts.size()];
        Map<String, List<Integer>> todo = new HashMap<>();
        for (int i = 0; i < texts.size(); i++) {
            String k = sha(texts.get(i));
            Double hit;
            synchronized (cache) { hit = cache.get(k); }
            if (hit != null) out[i] = hit;
            else { if (!todo.containsKey(k)) todo.put(k, new ArrayList<Integer>()); todo.get(k).add(i); }
        }
        List<Map.Entry<String, long[]>> wins = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : todo.entrySet()) {
            int[] ids = tok.encode(texts.get(e.getValue().get(0)));
            for (int s = 0; s == 0 || s < ids.length; s += WINDOW) {
                int n = Math.min(WINDOW, ids.length - s);
                long[] w = new long[n + 2];
                w[0] = tok.cls; w[n + 1] = tok.sep;
                for (int j = 0; j < n; j++) w[j + 1] = ids[s + j];
                wins.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), w));
            }
        }
        java.util.Collections.sort(wins, (x, y) -> x.getValue().length - y.getValue().length); // similar lengths batch together
        Map<String, Double> res = new HashMap<>();
        for (int a = 0; a < wins.size(); ) { // pad each batch to its longest window; stop when padded size passes BATCH_TOKENS
            int b = a, len = 0;
            while (b < wins.size() && (b == a || Math.max(len, wins.get(b).getValue().length) * (b - a + 1) <= BATCH_TOKENS)) len = Math.max(len, wins.get(b++).getValue().length);
            long[][] ids = new long[b - a][len], mask = new long[b - a][len];
            for (int r = 0; r < b - a; r++) {
                long[] w = wins.get(a + r).getValue();
                System.arraycopy(w, 0, ids[r], 0, w.length);
                java.util.Arrays.fill(mask[r], 0, w.length, 1);
            }
            Map<String, OnnxTensor> in = new HashMap<>();
            try (OnnxTensor i = OnnxTensor.createTensor(env, ids); OnnxTensor m = OnnxTensor.createTensor(env, mask)) {
                in.put("input_ids", i);
                in.put("attention_mask", m);
                try (OrtSession.Result r = session.run(in)) {
                    float[][] logits = (float[][]) r.get(0).getValue();
                    for (int k = 0; k < logits.length; k++) {
                        double p = 1 / (1 + Math.exp(logits[k][0] - logits[k][1])); // softmax over BENIGN, MALICIOUS
                        Double prev = res.get(wins.get(a + k).getKey());
                        res.put(wins.get(a + k).getKey(), prev == null ? p : Math.max(prev, p));
                    }
                }
            }
            a = b;
        }
        for (Map.Entry<String, Double> e : res.entrySet()) {
            synchronized (cache) { cache.put(e.getKey(), e.getValue()); }
            for (int i : todo.get(e.getKey())) out[i] = e.getValue();
        }
        return out;
    }

    private static String label(double p) { return p >= FLAG ? "MALICIOUS" : "BENIGN"; }

    private static double round(double p) { return Math.round(p * 1000) / 1000.0; }

    private static String sha(String s) throws Exception {
        return android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP);
    }
}
