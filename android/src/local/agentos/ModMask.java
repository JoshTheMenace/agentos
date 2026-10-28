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

/** Passive masks only: no app actions, injected code, arbitrary regex or layout mutations. */
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
        if (n == null || --budget[0] < 0 || depth > 40 || out.size() >= 12 || !n.isVisibleToUser()
                || !spec.optString("package").contentEquals(n.getPackageName())) return;
        JSONObject a = spec.optJSONObject("action"), m = a.optJSONObject("match");
        boolean hit = m.has("text") ? m.optString("text").equalsIgnoreCase(String.valueOf(n.getText()).trim())
                || m.optString("text").equalsIgnoreCase(String.valueOf(n.getContentDescription()).trim())
                : m.has("id") ? (spec.optString("package") + ":id/" + m.optString("id")).equals(n.getViewIdResourceName())
                : m.optString("class").contentEquals(n.getClassName() == null ? "" : n.getClassName());
        if (hit && !n.isPassword() && !n.isEditable()) {
            AccessibilityNodeInfo target = n;
            if ("item".equals(a.optString("scope", "node"))) {
                target = null;
                int up = 0;
                for (AccessibilityNodeInfo c = n, p = n.getParent(); p != null && up++ < 40; c = p, p = p.getParent()) {
                    if (p.isScrollable()) { target = c; break; }
                    if (p.getParent() == null) break;
                }
            }
            Rect r = new Rect(); if (target != null) target.getBoundsInScreen(r);
            if (r.intersect(screen) && r.width() > 0 && r.height() > 0 && r.height() <= screen.height() * 0.85
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
        d.setColor(preview ? 0x2238BDF8 : Color.parseColor(spec.optJSONObject("action").optString("color", "#101820")));
        if (preview) d.setStroke(3, 0xFF38BDF8);
        v.setBackground(d);
    }

    static void clear(WindowManager wm, List<View> views) {
        for (View v : views) try { if (v.isAttachedToWindow()) wm.removeViewImmediate(v); } catch (RuntimeException ignored) {}
        views.clear();
    }
}
