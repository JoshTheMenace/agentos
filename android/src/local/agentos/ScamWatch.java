package local.agentos;

import android.app.KeyguardManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Passive scam watch: like a smoke detector, it only sniffs; it never acts. When the owner opens a chat in a messaging
 * app (no agent task running) and a message there reads like a scam or hidden orders for an AI, the island shows one
 * calm amber card ("Possible scam message" + why + OK) and Activity gets "Scam message caught · <app>".
 *  - Only messaging apps (APPS), only an open conversation (a compose field is on screen; clickable list rows and
 *    their previews are skipped, so the chat list and a scenario replay stay quiet), only text of 6+ words.
 *  - Only NEW text: every text seen is remembered (LRU; forgotten 15 min after it was last on screen, or on demo reset),
 *    so reopening a chat never repeats a card.
 *  - Judges: ScamRules (two signals per rule) and Prompt Guard 2 (per message, app strings filtered), both off the
 *    UI thread, debounced, and never while a task runs (the broker's own reads flag then; Prompt Guard's cache is shared).
 */
final class ScamWatch {
    static final Set<String> APPS = new HashSet<>(Arrays.asList(
            "local.agentos.pigeon", "com.google.android.apps.messaging", "com.android.mms", "com.Slack", "com.discord",
            "com.whatsapp", "com.whatsapp.w4b", "com.instagram.android", "com.linkedin.android", "com.google.android.gm",
            "org.telegram.messenger", "org.thoughtcrime.securesms", "com.facebook.orca"));
    private static final int MIN_WORDS = 6, MAX_NODES = 400, DEBOUNCE_MS = 350, GAP_MS = 700, CARD_MS = 30_000;
    private static final ExecutorService bg = Executors.newSingleThreadExecutor(); // Prompt Guard must not run on the UI thread
    /** text key → when it was last on screen. A message is "new" again only after FORGET_MS out of sight (a replayed trap can demo again). */
    private static final Map<String, Long> seen = new LinkedHashMap<String, Long>(256, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> e) { return size() > 2048; }
    };
    private static final long FORGET_MS = 15 * 60_000L;

    private final AgentA11y svc;
    private final Runnable scanR = () -> bg.execute(this::scan), closeR = this::close;
    private volatile boolean showing, pending, rescan;
    private String shownIn = "";

    ScamWatch(AgentA11y svc) { this.svc = svc; }

    /** Demo reset: the trap message may be shown again. */
    static void reset() { synchronized (seen) { seen.clear(); } }

    /** Main thread, every accessibility event. */
    void onEvent(AccessibilityEvent e) {
        CharSequence p = e.getPackageName();
        int t = e.getEventType();
        if (showing && t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && p != null && !shownIn.contentEquals(p) && !"local.agentos".contentEquals(p)) close();
        if (p == null || !APPS.contains(p.toString())) return;
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && t != AccessibilityEvent.TYPE_VIEW_SCROLLED) return;
        if (pending) { rescan = true; return; } // one scan at a time: events during a scan earn one follow-up, never a queue
        pending = true;
        svc.main.postDelayed(scanR, DEBOUNCE_MS);
    }

    private static final class Found { final List<String> texts = new ArrayList<>(); String title = ""; boolean compose; int n; }

    /** Background thread. */
    private void scan() {
        long t0 = android.os.SystemClock.uptimeMillis();
        rescan = false;
        try {
            if (showing || Broker.busy() || svc.island.carded() || svc.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                Log.d("AgentOS", "scam watch: skip (showing " + showing + ", busy " + Broker.busy() + ", carded " + svc.island.carded() + ")"); return;
            }
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) return;
            String pkg = String.valueOf(root.getPackageName());
            if (!APPS.contains(pkg)) return;
            Found f = new Found();
            walk(root, pkg, false, f);
            List<String> fresh = new ArrayList<>();
            long now = System.currentTimeMillis();
            if (f.compose) synchronized (seen) { for (String t : f.texts) { Long last = seen.put(pkg + '\n' + t, now); if (last == null || now - last > FORGET_MS) fresh.add(t); } }
            Log.d("AgentOS", "scam watch: " + pkg + " compose " + f.compose + ", texts " + f.texts.size() + ", new " + fresh.size() + ", title \"" + f.title + "\" in " + (android.os.SystemClock.uptimeMillis() - t0) + " ms");
            if (fresh.isEmpty()) return; // a chat list (no compose field), or nothing new
            // Prompt Guard on the new messages in one batch (its cache is shared with the broker's screen reads).
            JSONArray nodes = new JSONArray();
            for (int i = 0; i < fresh.size(); i++) nodes.put(new JSONObject().put("id", i).put("text", fresh.get(i)).put("src", "APP:" + pkg).put("actions", new JSONArray()));
            JSONObject g = PromptGuard.annotate(new JSONObject().put("nodes", nodes));
            String why = null, text = null; double score = -1, best = -1;
            for (int i = 0; i < fresh.size(); i++) {
                String t = fresh.get(i), w = ScamRules.why(t);
                double p = nodes.getJSONObject(i).optDouble("guard", -1);
                if (w == null && p >= PromptGuard.FLAG && !AppStrings.own(pkg, t)) w = "hides instructions for an AI assistant";
                if (w == null) continue;
                double rank = Math.max(p, 0) + (w.startsWith("hides") ? 0 : 1); // the heuristic's word is more specific than a score
                if (rank > best) { best = rank; why = w; text = t; score = p; }
            }
            if (why == null) return;
            Broker.scamCaught(pkg, f.title, text, score < 0 ? JSONObject.NULL : Math.round(score * 1000) / 1000.0, why);
            String title = f.title, snippet = text, w = why; double sc = score;
            svc.main.post(() -> {
                if (showing || Broker.busy() || svc.island.carded()) return;
                showing = true; shownIn = pkg;
                svc.card.scam(pkg, title, w, snippet, sc, this::close);
                svc.main.postDelayed(closeR, CARD_MS);
            });
        } catch (Throwable e) { Log.w("AgentOS", "scam watch: " + e); }
        finally { // free the gate after a pause (a big tree such as Slack's takes ~1 s to walk); one more pass if anything changed meanwhile
            svc.main.postDelayed(() -> { pending = false; if (rescan) { rescan = false; pending = true; svc.main.postDelayed(scanR, DEBOUNCE_MS); } }, GAP_MS);
        }
    }

    /** Main thread: OK, a timeout, or leaving the app closes the card. */
    private void close() {
        svc.main.removeCallbacks(closeR);
        if (!showing) return;
        showing = false;
        svc.island.collapse();
    }

    /** Visible, non-editable text of 6+ words from this app, outside clickable rows; the first short text is the chat's title. */
    private static void walk(AccessibilityNodeInfo n, String pkg, boolean clickable, Found f) {
        if (n == null || f.n++ > MAX_NODES || !n.isVisibleToUser()) return;
        if (n.isEditable()) { f.compose = true; return; }
        clickable |= n.isClickable();
        CharSequence t = n.getText();
        if (!clickable && !n.isPassword() && t != null && pkg.contentEquals(String.valueOf(n.getPackageName()))) {
            String s = t.toString().trim();
            int words = s.isEmpty() ? 0 : s.split("\\s+").length;
            if (words >= MIN_WORDS) f.texts.add(Guard.clip(s, 600));
            else if (f.title.isEmpty() && words > 0 && words <= 4 && s.length() >= 2 && s.length() <= 30 && !s.matches("[\\d:. APM]+") && !s.equalsIgnoreCase(label(n))) f.title = s;
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), pkg, clickable, f);
    }

    private static String label(AccessibilityNodeInfo n) {
        try { return String.valueOf(n.getWindow().getTitle()); } catch (Exception e) { return ""; }
    }
}
