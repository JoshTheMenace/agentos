package local.agentos;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Tamper-evident ledger: files/audit.jsonl, one JSON object per line. Each line = body + "hash", where
 * body ends with "prev_hash" and hash = sha256(prev_hash + body). Like a chain of wax seals: change one old
 * line and every later seal stops matching. A line is: body minus its closing "}" + ,"hash":"<64 hex>"}.
 * prev_hash is read from the file's last line on every write (one lock per process), never from memory: on
 * 10-27 a second Broker (a11y service re-bound) kept its own stale copy and forked the chain at vault.fill lines.
 * Breaks found later are never rewritten; the operator can only mark them with an "audit.break" line (markBreaks).
 */
final class Audit {
    static final String ZERO = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final int SUFFIX = ",\"hash\":\"".length() + 64 + 2;
    private static final Object LOCK = new Object(); // every Audit in this process writes the same file
    private final File file;

    Audit(File dir) { file = new File(dir, "audit.jsonl"); }

    void log(JSONObject e) {
        synchronized (LOCK) {
            try {
                String prev = head();
                e.put("ts", System.currentTimeMillis()).put("prev_hash", prev);
                String body = e.toString(), h = sha256(prev + body);
                try (FileOutputStream o = new FileOutputStream(file, true)) {
                    o.write((body.substring(0, body.length() - 1) + ",\"hash\":\"" + h + "\"}\n").getBytes(StandardCharsets.UTF_8));
                }
                Events.emit("audit.append", e.put("hash", h));
            } catch (Exception ex) { throw new IllegalStateException("audit write failed", ex); }
        }
    }

    /** The last n entries plus a full-chain check (breaks marked by an audit.break line don't count). */
    JSONObject tail(int n) throws JSONException {
        synchronized (LOCK) {
            List<String> lines = lines();
            JSONObject v = verify(lines);
            JSONArray out = new JSONArray();
            long since = 0; // the last demo.reset line: the launcher counts "today" from there (a view filter; nothing is removed)
            for (int i = lines.size() - 1; i >= 0 && since == 0; i--) if (lines.get(i).contains("\"tool\":\"demo.reset\"")) since = new JSONObject(lines.get(i)).getLong("ts");
            for (int i = Math.max(0, lines.size() - n); i < lines.size(); i++) out.put(new JSONObject(lines.get(i)));
            return v.put("ok", true).put("entries", out).put("count", lines.size()).put("head", head()).put("since", since);
        }
    }

    /** Operator (adb): append one audit.break line naming every unmarked link break. Refused if a line's own seal is wrong. */
    JSONObject markBreaks(String reason) throws JSONException {
        synchronized (LOCK) {
            JSONObject v = verify(lines());
            if (v.getInt("tampered_at") >= 0) return v.put("ok", false).put("reason", "line " + v.getInt("tampered_at") + " was altered; that can't be marked");
            JSONArray b = v.getJSONArray("unmarked");
            if (b.length() == 0) return v.put("ok", true).put("reason", "nothing to mark");
            log(new JSONObject().put("task", "").put("tool", "audit.break").put("breaks", b).put("decision", "auto").put("reason", reason));
            return new JSONObject().put("ok", true).put("marked", b);
        }
    }

    /** Walk the whole chain. A link break (own seal fine, prev_hash points elsewhere) is excused only by an audit.break line listing {line, hash}. */
    private static JSONObject verify(List<String> lines) throws JSONException {
        Set<String> marked = new HashSet<>();
        for (String l : lines) {
            if (!l.contains("\"tool\":\"audit.break\"")) continue;
            JSONArray b = new JSONObject(l).optJSONArray("breaks");
            for (int i = 0; b != null && i < b.length(); i++) marked.add(b.getJSONObject(i).getInt("line") + ":" + b.getJSONObject(i).getString("hash"));
        }
        String p = ZERO;
        int tampered = -1, broken = -1, nMarked = 0;
        JSONArray unmarked = new JSONArray();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i), body = l.substring(0, l.length() - SUFFIX) + "}", h = hashOf(l), own = new JSONObject(body).optString("prev_hash");
            if (!sha256(own + body).equals(h)) { if (tampered < 0) tampered = i; if (broken < 0) broken = i; }
            else if (!own.equals(p)) {
                if (marked.contains(i + ":" + h)) nMarked++;
                else { unmarked.put(new JSONObject().put("line", i).put("hash", h).put("prev_hash", own).put("expected", p)); if (broken < 0) broken = i; }
            }
            p = h;
        }
        return new JSONObject().put("chain_ok", broken < 0).put("broken_at", broken).put("tampered_at", tampered)
                .put("marked_breaks", nMarked).put("unmarked", unmarked);
    }

    /** The last line's hash, straight from the file (every line ends with "hash":"<64 hex>"}\n). */
    private String head() {
        long n = file.length();
        if (n < 67) return ZERO;
        try (RandomAccessFile r = new RandomAccessFile(file, "r")) {
            byte[] b = new byte[64];
            r.seek(n - 67);
            r.readFully(b);
            return new String(b, StandardCharsets.US_ASCII);
        } catch (IOException e) { throw new IllegalStateException(e); }
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
