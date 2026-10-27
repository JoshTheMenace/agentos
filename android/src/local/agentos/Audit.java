package local.agentos;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Tamper-evident ledger: files/audit.jsonl, one JSON object per line. Each line = body + "hash", where
 * body ends with "prev_hash" and hash = sha256(prev_hash + body). Like a chain of wax seals: change one old
 * line and every later seal stops matching. A line is: body minus its closing "}" + ,"hash":"<64 hex>"}.
 */
final class Audit {
    static final String ZERO = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final int SUFFIX = ",\"hash\":\"".length() + 64 + 2;
    private final File file;
    private String prev = ZERO;

    Audit(File dir) {
        file = new File(dir, "audit.jsonl");
        List<String> lines = lines();
        if (!lines.isEmpty()) prev = hashOf(lines.get(lines.size() - 1));
    }

    synchronized void log(JSONObject e) {
        try {
            e.put("ts", System.currentTimeMillis()).put("prev_hash", prev);
            String body = e.toString(), h = sha256(prev + body);
            try (FileOutputStream o = new FileOutputStream(file, true)) {
                o.write((body.substring(0, body.length() - 1) + ",\"hash\":\"" + h + "\"}\n").getBytes(StandardCharsets.UTF_8));
            }
            prev = h;
            Events.emit("audit.append", e.put("hash", h));
        } catch (Exception ex) { throw new IllegalStateException("audit write failed", ex); }
    }

    /** The last n entries plus a full-chain check. */
    synchronized JSONObject tail(int n) throws JSONException {
        List<String> lines = lines();
        String p = ZERO;
        int broken = -1;
        for (int i = 0; i < lines.size() && broken < 0; i++) {
            String l = lines.get(i), body = l.substring(0, l.length() - SUFFIX) + "}";
            if (!new JSONObject(body).optString("prev_hash").equals(p) || !sha256(p + body).equals(hashOf(l))) broken = i;
            p = hashOf(l);
        }
        JSONArray out = new JSONArray();
        for (int i = Math.max(0, lines.size() - n); i < lines.size(); i++) out.put(new JSONObject(lines.get(i)));
        return new JSONObject().put("ok", true).put("entries", out).put("count", lines.size())
                .put("chain_ok", broken < 0).put("broken_at", broken).put("head", prev);
    }

    private static String hashOf(String line) { return line.substring(line.length() - 66, line.length() - 2); }

    private List<String> lines() {
        List<String> out = new ArrayList<>();
        if (!file.exists()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            for (String l; (l = r.readLine()) != null; ) if (!l.isEmpty()) out.add(l);
        } catch (IOException e) { throw new IllegalStateException(e); }
        return out;
    }

    static String sha256(String s) { return hex(sha256(s.getBytes(StandardCharsets.UTF_8))); }

    static byte[] sha256(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
