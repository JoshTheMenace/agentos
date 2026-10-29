package local.agentos;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Passive masks: no automated clicks, injected code, arbitrary regex or layout mutations. */
final class ModMask {
    static boolean is(JSONObject spec) { return "mask".equals(spec.optJSONObject("action").optString("type")); }

    static List<Rect> bounds(AccessibilityNodeInfo root, JSONObject spec) { return bounds(root, spec, false, new ArrayList<>()); }

    /** Matched rectangles. track keeps {marker, target, list} nodes of the last full walk: mid-scroll (quick) they are re-read
     *  with three IPCs each instead of a 600-node walk, so a drag can be followed every frame; a recycled row fails the
     *  marker re-check and falls back to the walk. quick also swaps the whole-tree password scan for a focused-field check. */
    static List<Rect> bounds(AccessibilityNodeInfo root, JSONObject spec, boolean quick, List<AccessibilityNodeInfo[]> track) {
        List<Rect> out = new ArrayList<>();
        Rect screen = new Rect(); root.getBoundsInScreen(screen);
        if (quick && !track.isEmpty()) {
            AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus != null && (focus.isPassword() || focus.isEditable())) return out;
            for (AccessibilityNodeInfo[] t : track)
                if (t[0].refresh() && matches(t[0], spec) && t[1].refresh() && (t[2] == null || t[2].refresh())) add(t[1], t[2], screen, out);
            if (!out.isEmpty()) return out;
        }
        track.clear();
        if (!sensitive(root, new int[]{600}, 0)) walk(root, spec, screen, out, track, new int[]{600}, 0);
        return out;
    }

    private static boolean sensitive(AccessibilityNodeInfo n, int[] budget, int depth) {
        if (n == null) return false;
        if (--budget[0] < 0 || depth > 40 || n.isPassword() || n.isEditable() && n.isFocused()) return true;
        for (int i = 0; i < n.getChildCount(); i++) if (sensitive(n.getChild(i), budget, depth + 1)) return true;
        return false;
    }

    private static boolean matches(AccessibilityNodeInfo n, JSONObject spec) {
        if (!spec.optString("package").contentEquals(n.getPackageName()) || n.isPassword() || n.isEditable()) return false;
        JSONObject m = spec.optJSONObject("action").optJSONObject("match");
        return m.has("text") ? m.optString("text").equalsIgnoreCase(String.valueOf(n.getText()).trim())
                || m.optString("text").equalsIgnoreCase(String.valueOf(n.getContentDescription()).trim())
                : m.has("id") ? (spec.optString("package") + ":id/" + m.optString("id")).equals(n.getViewIdResourceName())
                : m.optString("class").contentEquals(n.getClassName() == null ? "" : n.getClassName());
    }

    private static void walk(AccessibilityNodeInfo n, JSONObject spec, Rect screen, List<Rect> out, List<AccessibilityNodeInfo[]> track, int[] budget, int depth) {
        if (n == null || --budget[0] < 0 || depth > 40 || out.size() >= 12) return;
        if (matches(n, spec)) {
            AccessibilityNodeInfo target = n, list = null; // list: the scrollable ancestor; the mask never leaves its bounds (top bar, bottom nav stay clear)
            boolean item = "item".equals(spec.optJSONObject("action").optString("scope", "node"));
            int up = 0;
            for (AccessibilityNodeInfo c = n, p = n.getParent(); p != null && up++ < 40; c = p, p = p.getParent()) {
                if (p.isScrollable()) { list = p; if (item) target = c; break; }
                if (p.getParent() == null) break;
            }
            if (item && list == null) target = null;
            if (target != null && add(target, list, screen, out)) track.add(new AccessibilityNodeInfo[]{n, target, list});
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), spec, screen, out, track, budget, depth + 1);
    }

    /** The target's on-screen rectangle clipped to its list, refused when oversized or duplicate. */
    private static boolean add(AccessibilityNodeInfo target, AccessibilityNodeInfo list, Rect screen, List<Rect> out) {
        Rect r = new Rect(), clip = new Rect(screen); target.getBoundsInScreen(r);
        if (list != null) list.getBoundsInScreen(clip);
        if (!(r.intersect(clip) && r.intersect(screen) && r.width() > 0 && r.height() > 0 && r.height() <= screen.height() * 0.85 && !out.contains(r))) return false;
        out.add(r); return true;
    }

    static void paint(View v, JSONObject spec, boolean preview) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(preview ? 0x2238BDF8 : fill(spec, 0xFF101820));
        if (preview) d.setStroke(3, 0xFF38BDF8);
        v.setBackground(d);
    }

    /** The spec's explicit color, else the app's own sampled background (so the hidden item reads as blank space of the app). */
    static int fill(JSONObject spec, int sampled) { return explicit(spec) ? Color.parseColor(spec.optJSONObject("action").optString("color")) : sampled; }

    /** An explicit colour other than #101820, the old example default, which now means "the app's own background". */
    static boolean explicit(JSONObject spec) {
        JSONObject a = spec.optJSONObject("action");
        return a.has("color") && !a.optString("color").equalsIgnoreCase("#101820");
    }

    /** Most common pixel along the item's inner edges (card padding, header row): its background, not its image or text. */
    static int background(android.graphics.Bitmap bm, Rect b) {
        b = new Rect(b); if (!b.intersect(0, 0, bm.getWidth(), bm.getHeight()) || b.width() < 16 || b.height() < 16) return 0xFFFFFFFF;
        List<Integer> pts = new ArrayList<>();
        for (int i = 0; i < 12; i++) { int y = b.top + 6 + (b.height() - 12) * i / 11; pts.add(bm.getPixel(b.left + 4, y)); pts.add(bm.getPixel(b.right - 5, y)); }
        for (int i = 0; i < 8; i++) { int x = b.left + 6 + (b.width() - 12) * i / 7; pts.add(bm.getPixel(x, b.top + 6)); pts.add(bm.getPixel(x, b.bottom - 7)); }
        int best = 0xFFFFFFFF, bestN = -1;
        for (int p : pts) {
            int n = 0;
            for (int q : pts) if (Math.abs(Color.red(p) - Color.red(q)) + Math.abs(Color.green(p) - Color.green(q)) + Math.abs(Color.blue(p) - Color.blue(q)) < 24) n++;
            if (n > bestN) { bestN = n; best = p; }
        }
        return best | 0xFF000000;
    }

    static void clear(WindowManager wm, List<View> views) {
        for (View v : views) try { wm.removeViewImmediate(v); } catch (RuntimeException ignored) {}
        views.clear();
    }
}
