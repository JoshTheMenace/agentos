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

    static List<Rect> bounds(AccessibilityNodeInfo root, JSONObject spec) {
        List<Rect> out = new ArrayList<>();
        Rect screen = new Rect(); root.getBoundsInScreen(screen);
        if (!sensitive(root, new int[]{600}, 0)) walk(root, spec, screen, out, new int[]{600}, 0);
        return out;
    }

    private static boolean sensitive(AccessibilityNodeInfo n, int[] budget, int depth) {
        if (n == null) return false;
        if (--budget[0] < 0 || depth > 40 || n.isPassword() || n.isEditable() && n.isFocused()) return true;
        for (int i = 0; i < n.getChildCount(); i++) if (sensitive(n.getChild(i), budget, depth + 1)) return true;
        return false;
    }

    private static void walk(AccessibilityNodeInfo n, JSONObject spec, Rect screen, List<Rect> out, int[] budget, int depth) {
        if (n == null || --budget[0] < 0 || depth > 40 || out.size() >= 12
                || !spec.optString("package").contentEquals(n.getPackageName())) return;
        JSONObject a = spec.optJSONObject("action"), m = a.optJSONObject("match");
        boolean hit = m.has("text") ? m.optString("text").equalsIgnoreCase(String.valueOf(n.getText()).trim())
                || m.optString("text").equalsIgnoreCase(String.valueOf(n.getContentDescription()).trim())
                : m.has("id") ? (spec.optString("package") + ":id/" + m.optString("id")).equals(n.getViewIdResourceName())
                : m.optString("class").contentEquals(n.getClassName() == null ? "" : n.getClassName());
        if (hit && !n.isPassword() && !n.isEditable()) {
            AccessibilityNodeInfo target = n, list = null; // list: the scrollable ancestor; the mask never leaves its bounds (top bar, bottom nav stay clear)
            boolean item = "item".equals(a.optString("scope", "node"));
            int up = 0;
            for (AccessibilityNodeInfo c = n, p = n.getParent(); p != null && up++ < 40; c = p, p = p.getParent()) {
                if (p.isScrollable()) { list = p; if (item) target = c; break; }
                if (p.getParent() == null) break;
            }
            if (item && list == null) target = null;
            Rect r = new Rect(), clip = new Rect(screen); if (target != null) target.getBoundsInScreen(r);
            if (list != null) list.getBoundsInScreen(clip);
            if (r.intersect(clip) && r.intersect(screen) && r.width() > 0 && r.height() > 0 && r.height() <= screen.height() * 0.85
                    && !out.contains(r)) out.add(r);
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), spec, screen, out, budget, depth + 1);
    }

    static boolean scroll(AccessibilityNodeInfo n, int x, int y, boolean forward, int[] budget, int depth) {
        if (n == null || --budget[0] < 0 || depth > 40) return false;
        Rect r = new Rect(); n.getBoundsInScreen(r);
        if (!r.contains(x, y)) return false;
        if (n.isScrollable()) return n.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        for (int i = 0; i < Math.min(n.getChildCount(), 100); i++) if (scroll(n.getChild(i), x, y, forward, budget, depth + 1)) return true;
        return false;
    }

    static void paint(View v, JSONObject spec, boolean preview) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(preview ? 0x2238BDF8 : fill(spec, 0xFF101820));
        if (preview) d.setStroke(3, 0xFF38BDF8);
        v.setBackground(d);
    }

    /** The spec's explicit color, else the app's own sampled background (so the hidden item reads as blank space of the app). */
    static int fill(JSONObject spec, int sampled) {
        JSONObject a = spec.optJSONObject("action");
        return a.has("color") ? Color.parseColor(a.optString("color")) : sampled;
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
