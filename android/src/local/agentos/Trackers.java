package local.agentos;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Tracker radar: which tracker companies each app talks to, and blocks that hold.
 *
 * Seeing: every HTTPS connection opens with a "ClientHello" that names the server in plain text (SNI), like the
 * address on an envelope. A root iptables rule copies just those first packets to NFLOG group 42, and the kernel tags
 * each with the sending app's uid. A root tcpdump streams them to us; we read the name and look it up in
 * res/raw/trackers.txt. (DNS can't do this: Android's resolver sends every lookup as root, so you can't tell who asked.)
 * The radar is always on, so it already knows what an app did since it opened. Hits survive restarts (files/trackers.json).
 *
 * Blocking: Firewall.names() rejects the app's connections whose ClientHello/Host names a blocked domain, so the block
 * holds however that domain's IPs rotate. Only names on the bundled tracker list can be blocked here.
 */
final class Trackers {
    private static final String CHAIN = "AGENTOS_SNI", TAG = "AgentOS";
    private static final int GROUP = 42;
    private static final Map<String, String> LIST = new HashMap<>(); // tracker domain -> company
    private static final Map<Integer, Map<String, Integer>> SEEN = new ConcurrentHashMap<>(); // uid -> domain -> hits
    private static volatile int lastUid = -1; // the app that most recently hit a tracker
    private static File store;
    private static Context ctx;
    private static Thread radar;

    private Trackers() {}

    /** Start the radar once (Broker constructor). Restarts tcpdump if it dies. */
    static synchronized void start(Context c) {
        if (radar != null) return;
        ctx = c.getApplicationContext();
        try {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getResources().openRawResource(R.raw.trackers), StandardCharsets.UTF_8))) {
                for (String l; (l = r.readLine()) != null; ) {
                    int sp = l.indexOf(' ');
                    if (!l.startsWith("#") && sp > 0) LIST.put(l.substring(0, sp), l.substring(sp + 1).trim());
                }
            }
            store = new File(c.getFilesDir(), "trackers.json");
            if (store.exists()) {
                JSONObject j = new JSONObject(new String(Files.readAllBytes(store.toPath()), StandardCharsets.UTF_8));
                for (Iterator<String> u = j.keys(); u.hasNext(); ) {
                    String uid = u.next();
                    Map<String, Integer> m = new ConcurrentHashMap<>();
                    JSONObject d = j.getJSONObject(uid);
                    for (Iterator<String> k = d.keys(); k.hasNext(); ) { String s = k.next(); if (!firstParty(Integer.parseInt(uid), s)) m.put(s, d.getInt(s)); }
                    SEEN.put(Integer.parseInt(uid), m);
                }
            }
        } catch (Exception e) { Log.w(TAG, "tracker list/store", e); }
        radar = new Thread(() -> {
            while (true) {
                try { watch(); } catch (Exception e) { Log.w(TAG, "tracker radar: " + e); }
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        }, "tracker-radar");
        radar.setDaemon(true);
        radar.start();
    }

    /** One tcpdump session: pcap on stdout, NFLOG link type (TLVs: 9 = packet, 11 = uid). */
    private static void watch() throws Exception {
        for (String t : new String[]{"iptables", "ip6tables"})
            Root.run(t + " -w -N " + CHAIN + " 2>/dev/null; " + t + " -w -C OUTPUT -j " + CHAIN + " 2>/dev/null || " + t + " -w -I OUTPUT 1 -j " + CHAIN
                    + "; " + t + " -w -F " + CHAIN + "; " + t + " -w -A " + CHAIN + " -p tcp --dport 443 -m string --algo kmp --hex-string '|160301|' --to 200"
                    + " -j NFLOG --nflog-group " + GROUP + " --nflog-size 2048");
        String pid = "/data/local/tmp/agentos-radar.pid";
        Process p = new ProcessBuilder("su", "-c", "kill $(cat " + pid + ") 2>/dev/null; echo $$ > " + pid
                + "; exec tcpdump -i nflog:" + GROUP + " -U -w - 2>/dev/null").start();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(p.getInputStream()))) {
            in.readFully(new byte[24]);
            byte[] h = new byte[16];
            while (true) {
                in.readFully(h);
                byte[] rec = new byte[le(h, 8, 4)];
                in.readFully(rec);
                try { packet(rec); } catch (RuntimeException ignored) {} // a cut-short or odd packet
            }
        } finally { p.destroy(); }
    }

    private static int le(byte[] b, int o, int n) { int v = 0; for (int i = n - 1; i >= 0; i--) v = v << 8 | b[o + i] & 0xff; return v; }
    private static int be(byte[] b, int o, int n) { int v = 0; for (int i = 0; i < n; i++) v = v << 8 | b[o + i] & 0xff; return v; }

    private static void packet(byte[] r) throws Exception {
        int uid = -1, ip = -1, ipLen = 0;
        for (int j = 4; j + 4 <= r.length; ) { // NFLOG TLVs after a 4-byte header; length/type little-endian, values big-endian
            int len = le(r, j, 2), type = le(r, j + 2, 2);
            if (len < 4) break;
            if (type == 11) uid = be(r, j + 4, 4);
            if (type == 9) { ip = j + 4; ipLen = len - 4; }
            j += len + 3 & ~3;
        }
        if (uid < 10000 || ip < 0) return; // apps only
        int tcp = ip + ((r[ip] & 0xff) >> 4 == 6 ? 40 : (r[ip] & 15) * 4), t = tcp + ((r[tcp + 12] & 0xff) >> 4) * 4, end = ip + ipLen;
        if (r[t] != 0x16 || r[t + 5] != 1) return; // TLS handshake record, ClientHello
        int k = t + 9 + 2 + 32;                    // record + handshake headers, version, random
        k += 1 + (r[k] & 0xff);                    // session id
        k += 2 + be(r, k, 2);                      // cipher suites
        k += 1 + (r[k] & 0xff);                    // compression
        int extEnd = Math.min(end, k + 2 + be(r, k, 2));
        for (k += 2; k + 4 <= extEnd; k += 4 + be(r, k + 2, 2))
            if (be(r, k, 2) == 0) { hit(uid, new String(r, k + 9, be(r, k + 7, 2), StandardCharsets.US_ASCII)); return; }
    }

    /** host -> the tracker-list domain it falls under ("de0mpg.launches.appsflyersdk.com" -> "appsflyersdk.com"), or null. */
    static String tracker(String host) {
        for (String h = host.toLowerCase(); ; h = h.substring(h.indexOf('.') + 1)) {
            if (LIST.containsKey(h)) return h;
            if (h.indexOf('.') < 0) return null;
        }
    }

    private static void hit(int uid, String host) {
        String d = tracker(host);
        if (d == null || firstParty(uid, d)) return;
        Map<String, Integer> m = SEEN.computeIfAbsent(uid, u -> new ConcurrentHashMap<>());
        boolean first = !m.containsKey(d);
        m.merge(d, 1, Integer::sum);
        lastUid = uid;
        save(); // a few per minute at most: trackers only
        if (!first) return;
        try { Events.emit("tracker.seen", new JSONObject().put("uid", uid).put("domain", d).put("company", LIST.get(d)).put("host", host)); }
        catch (JSONException ignored) {}
    }

    /** The company's own app (the Facebook app talking to facebook.com) isn't being tracked by a third party. */
    private static boolean firstParty(int uid, String domain) {
        String[] pk = ctx.getPackageManager().getPackagesForUid(uid);
        return pk != null && ("." + pk[0] + ".").contains("." + domain.split("[.-]")[0] + ".");
    }

    private static synchronized void save() {
        try {
            JSONObject j = new JSONObject();
            for (Map.Entry<Integer, Map<String, Integer>> e : SEEN.entrySet()) j.put(String.valueOf(e.getKey()), new JSONObject(e.getValue()));
            try (FileOutputStream o = new FileOutputStream(store)) { o.write(j.toString().getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception e) { Log.w(TAG, "tracker store", e); }
    }

    // ------------------------------------------------------------------ tools

    /** trackers.scan {pkg?, seconds?=3}: watch the app (default: the one on screen) a few seconds, then report it. */
    static JSONObject scan(AccessibilityService svc, JSONObject p) throws Exception {
        String pkg = pkg(svc, p);
        Thread.sleep(Math.max(0, Math.min(15, p.optInt("seconds", 3))) * 1000L);
        JSONObject app = app(svc, pkg, blocked(svc).get(uid(svc, pkg)));
        JSONArray t = app.getJSONArray("trackers");
        int n = t.length(), b = t.toString().split("\"blocked\":true", -1).length - 1;
        return app.put("ok", true).put("summary", n == 0 ? "No trackers seen from " + app.getString("label") + " yet. Use the app a bit and scan again."
                : app.getString("label") + " talks to " + n + " tracker" + (n == 1 ? "" : "s") + ": " + companies(t) + (b == 0 ? "" : b == n ? " (all blocked)" : " (" + b + " blocked)"));
    }

    /** trackers.list: every app the radar has seen talk to a tracker, or with blocked trackers. The Blockers view. */
    static JSONObject list(Context c) throws Exception {
        Map<Integer, Set<String>> blocked = blocked(c);
        Set<Integer> uids = new LinkedHashSet<>(SEEN.keySet());
        uids.addAll(blocked.keySet());
        List<JSONObject> apps = new ArrayList<>();
        for (int uid : uids) {
            String[] pk = c.getPackageManager().getPackagesForUid(uid);
            JSONObject a = pk == null ? null : app(c, pk[0], blocked.get(uid));
            if (a != null && a.getJSONArray("trackers").length() > 0) apps.add(a);
        }
        apps.sort((a, b) -> b.optJSONArray("trackers").length() - a.optJSONArray("trackers").length());
        return new JSONObject().put("ok", true).put("apps", new JSONArray(apps)).put("list_size", LIST.size())
                .put("blocked_requests", Firewall.blockedRequests());
    }

    /**
     * Before the approval card: pick and check exactly what trackers.block / trackers.unblock will change, and write it
     * back into p (pkg, domains), so the card shows and the approval binds to the same thing execute() does.
     * Default: every tracker seen for the app (block) or every blocked one (unblock). Names must be on the tracker list.
     */
    static String plan(AccessibilityService svc, String tool, JSONObject p) throws Exception {
        String pkg = pkg(svc, p);
        int uid = uid(svc, pkg);
        Set<String> blocked = blocked(svc).get(uid), in = new LinkedHashSet<>(), names = new TreeSet<>(), who = new LinkedHashSet<>();
        JSONArray d = p.optJSONArray("domains");
        if (d != null && d.length() > 0) for (int i = 0; i < d.length(); i++) in.add(d.getString(i));
        else if (tool.equals("trackers.block")) in.addAll(SEEN.getOrDefault(uid, new HashMap<>()).keySet());
        else if (blocked != null) in.addAll(blocked);
        for (String s : in) {
            String t = tracker(s);
            if (t == null) throw new IllegalArgumentException(s + " is not on the tracker list (use firewall.set for other domains)");
            names.add(t);
        }
        if (names.isEmpty())
            throw new IllegalArgumentException(tool.equals("trackers.block") ? "no trackers seen for " + pkg + " yet: open it and run trackers.scan" : "nothing blocked for " + pkg);
        for (String t : names) who.add(LIST.get(t));
        p.put("pkg", pkg).put("domains", new JSONArray(names));
        return (tool.equals("trackers.block") ? "block trackers: " : "unblock trackers: ") + String.join(", ", who) + " (" + String.join(", ", names) + ")";
    }

    static void block(Context c, String tool, JSONObject p) throws Exception {
        String pkg = p.getString("pkg");
        JSONArray d = p.getJSONArray("domains");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < d.length(); i++) names.add(d.getString(i));
        boolean on = tool.equals("trackers.block");
        Firewall.names(uid(c, pkg), names, on);
        JSONObject app = app(c, pkg, blocked(c).get(uid(c, pkg)));
        Events.emit("tracker.blocked", new JSONObject().put("pkg", pkg).put("label", app.getString("label")).put("blocked", on)
                .put("domains", d).put("app", app));
    }

    // ---------------------------------------------------------------- helpers

    private static String companies(JSONArray t) throws JSONException {
        Set<String> s = new LinkedHashSet<>();
        for (int i = 0; i < t.length(); i++) s.add(t.getJSONObject(i).getString("company"));
        return String.join(", ", s);
    }

    /** {pkg, label, trackers:[{domain, company, blocked, hits}]}: one app's row in the Blockers view. */
    private static JSONObject app(Context c, String pkg, Set<String> blocked) throws Exception {
        PackageManager pm = c.getPackageManager();
        Map<String, Integer> seen = SEEN.getOrDefault(uid(c, pkg), new HashMap<>());
        Set<String> all = new LinkedHashSet<>(seen.keySet());
        if (blocked != null) all.addAll(blocked);
        JSONArray t = new JSONArray();
        for (String d : all)
            t.put(new JSONObject().put("domain", d).put("company", LIST.getOrDefault(d, d)).put("blocked", blocked != null && blocked.contains(d))
                    .put("hits", seen.getOrDefault(d, 0)));
        String label;
        try { label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception e) { label = pkg; }
        return new JSONObject().put("pkg", pkg).put("label", label).put("trackers", t);
    }

    private static int uid(Context c, String pkg) throws Exception { return c.getPackageManager().getPackageUid(pkg, 0); }

    /** uid -> tracker domains blocked by Firewall.names rules (iptables is the source of truth). */
    private static Map<Integer, Set<String>> blocked(Context c) throws Exception {
        Map<Integer, Set<String>> m = new HashMap<>();
        for (String[] r : Firewall.nameRules()) m.computeIfAbsent(Integer.parseInt(r[0]), u -> new LinkedHashSet<>()).add(r[1]);
        return m;
    }

    /** p.pkg, else the app on screen (topmost app window that isn't AgentOS), else the app that last hit a tracker. */
    private static String pkg(AccessibilityService svc, JSONObject p) throws Exception {
        String pkg = p.optString("pkg", "");
        if (!pkg.isEmpty()) { uid(svc, pkg); return pkg; } // throws if not installed
        for (AccessibilityWindowInfo w : svc.getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo n = w.getRoot();
            String s = n == null || n.getPackageName() == null ? "" : n.getPackageName().toString();
            if (!s.isEmpty() && !s.startsWith("local.agentos") && !s.equals("com.android.systemui")) return s;
        }
        String[] last = lastUid < 0 ? null : svc.getPackageManager().getPackagesForUid(lastUid);
        if (last == null) throw new IllegalArgumentException("no app on screen: pass pkg");
        return last[0];
    }
}
