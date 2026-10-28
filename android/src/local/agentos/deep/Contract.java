package local.agentos.deep;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Base64;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;

/** Shared bounded data contract; no executable selectors or action arguments. */
public final class Contract {
    public static final String PKG = "local.agentos.pigeon", BROKER = "local.agentos", WIRE = "agentos.deepmod/1";
    public static final String PIGEON_CERT = "876960f7e473def77908ccb11d3fbc3231688a6e7bafcba54f154c90edc358fe";
    public static final String BROKER_CERT = "01cc23296c87510b424f5a9b1c7e3ed79e1f1113fd36bcfb35ee55effc71ece5";
    public static final List<String> COLORS = Arrays.asList("bg", "surface", "surface2", "text", "text2", "accent", "onAccent");
    public static void need(boolean yes, String why) { if (!yes) throw new IllegalArgumentException(why); }
    public static String hash(byte[] bytes) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) s.append(String.format(Locale.US, "%02x", b & 255));
        return s.toString();
    }
    public static String hash(String s) throws Exception { return hash(s.getBytes(StandardCharsets.UTF_8)); }
    public static String cert(Context c, String pkg) throws Exception {
        PackageInfo p = c.getPackageManager().getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
        android.content.pm.Signature[] a = p.signingInfo.getApkContentsSigners();
        need(a.length == 1, "unexpected signers"); return hash(a[0].toByteArray());
    }
    public static void peer(Context c, int uid, String pkg, String pin) throws Exception {
        String[] a = c.getPackageManager().getPackagesForUid(uid);
        need(a != null && a.length == 1 && pkg.equals(a[0]) && pin.equals(cert(c, pkg)), "untrusted Binder peer");
    }
    public static String build(Context c, String pkg) throws Exception {
        return hash(Files.readAllBytes(Paths.get(c.getPackageManager().getApplicationInfo(pkg, 0).sourceDir)));
    }
    public static JSONObject rpc(IBinder b, JSONObject request) throws Exception {
        Parcel p = Parcel.obtain(), r = Parcel.obtain();
        try { p.writeInterfaceToken(WIRE); p.writeString(request.toString()); need(b.transact(1, p, r, 0), "Binder refused"); r.readException(); return wire(r.readString()); }
        finally { p.recycle(); r.recycle(); }
    }
    public static void keys(JSONObject o, String... allowed) {
        Set<String> set = new HashSet<>(Arrays.asList(allowed));
        for (Iterator<String> i = o.keys(); i.hasNext();) need(set.contains(i.next()), "unknown field");
    }
    public static JSONObject validate(String raw) throws Exception {
        JSONObject o = parse(raw); keys(o, "schema", "id", "package", "capabilityVersion", "baseRevision", "theme", "views", "lists", "buttons");
        need(WIRE.equals(o.getString("schema")) && PKG.equals(o.getString("package")), "unsupported target/schema");
        need(o.get("capabilityVersion").equals(1) && (o.get("baseRevision") instanceof Integer || o.get("baseRevision") instanceof Long) && o.getLong("baseRevision") >= 0, "bad capability/revision");
        need(o.get("id") instanceof String && o.getString("id").matches("[a-z0-9_-]{1,32}"), "bad id");
        int ops = 0;
        if (o.has("theme")) {
            JSONObject t = o.getJSONObject("theme"); keys(t, "colors", "font");
            if (t.has("colors")) { JSONObject cs = t.getJSONObject("colors"); keys(cs, COLORS.toArray(new String[0]));
                for (Iterator<String> i = cs.keys(); i.hasNext();) { need(cs.getString(i.next()).matches("#FF[0-9A-Fa-f]{6}"), "opaque #FFRRGGBB required"); ops++; }
            }
            if (t.has("font")) { JSONObject f = t.getJSONObject("font"); keys(f, "family", "scale");
                need(Arrays.asList("sans-serif", "serif", "monospace").contains(f.getString("family")), "unsupported font");
                need(f.get("scale") instanceof Number, "numeric scale required"); double scale = f.getDouble("scale"); need(Double.isFinite(scale) && scale >= .85 && scale <= 1.3, "font scale outside .85–1.3"); ops++;
            }
        }
        JSONArray vs = o.has("views") ? o.getJSONArray("views") : new JSONArray();
        Set<String> targets = new HashSet<>();
        for (int i = 0; i < vs.length(); i++) { JSONObject v = vs.getJSONObject(i); keys(v, "screen", "match", "visibility");
            need("chat-list".equals(v.getString("screen")) && "gone".equals(v.getString("visibility")), "hide only list decorations");
            JSONObject m = v.getJSONObject("match"); keys(m, "id", "text"); need(m.length() == 1, "exactly one selector");
            String target = m.has("id") ? m.getString("id") : "Demo promotion".equals(m.getString("text")) ? "pigeon_promotion" : "";
            need(Arrays.asList("pigeon_overflow", "pigeon_promotion").contains(target) && targets.add(target), "unknown/duplicate decorative target"); ops++;
        }
        JSONArray ls = o.has("lists") ? o.getJSONArray("lists") : new JSONArray(); need(ls.length() <= 1, "one list filter");
        for (int i = 0; i < ls.length(); i++) { JSONObject l = ls.getJSONObject(i); keys(l, "id", "exclude");
            JSONObject e = l.getJSONObject("exclude"); keys(e, "field", "equals");
            need("pigeon_threads".equals(l.getString("id")) && "promoted".equals(e.getString("field")) && Boolean.TRUE.equals(e.get("equals")), "only synthetic promoted=true filtering"); ops++;
        }
        JSONArray bs = o.has("buttons") ? o.getJSONArray("buttons") : new JSONArray(); need(bs.length() <= 1, "one summarize control");
        for (int i = 0; i < bs.length(); i++) { JSONObject b = bs.getJSONObject(i); keys(b, "id", "screen", "slot", "label", "action");
            JSONObject a = b.getJSONObject("action"); keys(a, "id", "template", "context");
            need("summarize-chat".equals(b.getString("id")) && "chat".equals(b.getString("screen")) && "pigeon_chat_actions".equals(b.getString("slot"))
                && "Summarize".equals(b.getString("label")) && "agent.ask".equals(a.getString("id")) && "summarize-visible".equals(a.getString("template")) && "current-chat".equals(a.getString("context")), "only fixed summarize action"); ops++;
        }
        need(ops <= 32, "too many operations"); return o;
    }
    public static byte[] signed(JSONObject e) throws Exception {
        keys(e, "payloadB64", "digest", "build", "cert", "revision", "task", "mode", "nonce", "expires", "epoch", "signature");
        JSONArray a = new JSONArray().put(WIRE);
        for (String k : Arrays.asList("payloadB64", "digest", "build", "cert", "revision", "task", "mode", "nonce", "expires", "epoch")) a.put(e.get(k));
        return a.toString().getBytes(StandardCharsets.UTF_8);
    }
    public static JSONObject parse(String raw) throws Exception { return parse(raw, 16384); }
    public static JSONObject wire(String raw) throws Exception { return parse(raw, 24576); }
    private static JSONObject parse(String raw, int max) throws Exception {
        need(raw != null && raw.getBytes(StandardCharsets.UTF_8).length <= max, "JSON exceeds 16 KiB");
        Parser p = new Parser(raw); Object o = p.value(0); p.ws(); need(p.at == raw.length() && o instanceof JSONObject, "expected JSON object"); return (JSONObject)o;
    }
    // JSONTokener accepts duplicate keys and non-JSON syntax. This small front end accepts strict JSON only.
    private static final class Parser {
        final String s; int at;
        Parser(String s) { this.s = s; }
        void ws() { while (at < s.length() && " \r\n\t".indexOf(s.charAt(at)) >= 0) at++; }
        char take() { need(at < s.length(), "incomplete JSON"); return s.charAt(at++); }
        String string() throws Exception {
            int start = at; need(take() == '"', "quoted string required");
            for (;;) { char c = take(); if (c == '"') break; need(c >= 32, "control character"); if (c == '\\') { c = take(); need("\"\\/bfnrtu".indexOf(c) >= 0, "bad escape"); if (c == 'u') for (int i = 0; i < 4; i++) need(Character.digit(take(), 16) >= 0, "bad unicode"); } }
            return (String)new JSONTokener(s.substring(start, at)).nextValue();
        }
        Object value(int depth) throws Exception {
            need(depth <= 8, "JSON too deep"); ws(); need(at < s.length(), "missing value"); char c = s.charAt(at);
            if (c == '"') return string();
            if (c == '{') { at++; JSONObject o = new JSONObject(); Set<String> seen = new HashSet<>(); ws(); if (at < s.length() && s.charAt(at) == '}') { at++; return o; }
                for (;;) { ws(); String k = string(); need(seen.add(k), "duplicate key: " + k); ws(); need(take() == ':', "missing colon"); o.put(k, value(depth + 1)); ws(); c = take(); if (c == '}') return o; need(c == ',', "missing comma"); }
            }
            if (c == '[') { at++; JSONArray a = new JSONArray(); ws(); if (at < s.length() && s.charAt(at) == ']') { at++; return a; }
                for (;;) { a.put(value(depth + 1)); ws(); c = take(); if (c == ']') return a; need(c == ',', "missing comma"); }
            }
            int start = at; while (at < s.length() && ",]} \r\n\t".indexOf(s.charAt(at)) < 0) at++;
            String token = s.substring(start, at); need(token.matches("true|false|null|-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"), "invalid JSON literal");
            return new JSONTokener(token).nextValue();
        }
    }
}
