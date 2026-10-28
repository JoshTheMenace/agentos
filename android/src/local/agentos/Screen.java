/*
 * walk() and the window loop in read() are ported from buddy-android (https://github.com/ghorbelhamdi/buddy-android),
 * app/src/main/java/app/buddy/assistant/Tools.java (readScreen/walk). Copyright the buddy-android authors.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 * Changes by AgentOS: JSON nodes {id, role, text, desc, bounds, actions[]}, our own windows skipped, password and
 * secure-screen redaction, risk hints, provenance labels (src), the focused input (focused / focused_id), and FLAG_SECURE +
 * focused activity read via root dumpsys.
 */
package local.agentos;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The model's eyes: a compact numbered list of on-screen elements. Ids are valid until the next read. */
final class Screen {
    static final int MAX_NODES = 150;
    private static final Pattern FOCUS = Pattern.compile("mCurrentFocus=Window\\{(\\S+) u\\d+ ([^/\\s}]+)/?([^\\s}]*)\\}");
    private static final Pattern SECURE_FLAG = Pattern.compile("\\bfl=[^\\n]*\\bSECURE\\b");
    private static final Pattern DIGITS = Pattern.compile("\\d[\\d \\-]{3,}\\d");

    private final List<AccessibilityNodeInfo> nodes = new ArrayList<>();
    private volatile boolean dirty = true;
    /** From the last read: foreground app, activity, and why it counts as secure ("" = not secure). */
    String pkg = "", activity = "", secureWhy = "";
    private boolean flagSecure, password;
    private int focusedId;

    /** A window changed: re-check FLAG_SECURE on the next read. */
    void markDirty() { dirty = true; }

    synchronized JSONObject read(AccessibilityService svc) throws JSONException {
        refreshFocus();
        nodes.clear();
        password = false;
        focusedId = -1;
        JSONArray out = new JSONArray();
        String active = null;
        for (AccessibilityWindowInfo w : svc.getWindows()) {
            int t = w.getType();
            if (t == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY || t == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || Guard.offLimits(root)) continue;
            if (t == AccessibilityWindowInfo.TYPE_APPLICATION && (active == null || w.isActive())) active = String.valueOf(root.getPackageName());
            walk(root, out);
        }
        if (pkg.isEmpty() && active != null) pkg = active;
        secureWhy = flagSecure ? "FLAG_SECURE" : password ? "password field" : "";
        if (flagSecure) redact(out);
        return new JSONObject().put("pkg", pkg).put("activity", activity).put("secure", !secureWhy.isEmpty())
                .put("secure_reason", secureWhy).put("nodes", out).put("truncated", nodes.size() >= MAX_NODES)
                .put("source_pkg", pkg).put("focused_id", focusedId < 0 ? JSONObject.NULL : focusedId).put("untrusted", true);
    }

    boolean secure() { return !secureWhy.isEmpty(); }

    /** The app in front now (refreshed if a window changed since the last read), not the one at the last read. */
    synchronized String pkgNow() { refreshFocus(); return pkg; }

    /** Mods (on their own Screen): is the focused window FLAG_SECURE right now? Root, ~130 ms; fails closed. */
    synchronized boolean secureNow() { dirty = true; refreshFocus(); return flagSecure; }

    /** The node behind an id from the last read. */
    synchronized AccessibilityNodeInfo node(int id) {
        if (id < 0 || id >= nodes.size()) throw new IllegalArgumentException("stale or unknown id " + id + "; call screen.read again");
        return nodes.get(id);
    }

    private void walk(AccessibilityNodeInfo n, JSONArray out) throws JSONException {
        if (n == null || nodes.size() >= MAX_NODES || !n.isVisibleToUser()) return;
        CharSequence text = n.getText(), desc = n.getContentDescription();
        boolean interesting = !empty(text) || !empty(desc) || n.isClickable() || n.isEditable()
                || n.isScrollable() || n.isCheckable() || n.isLongClickable();
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (interesting && !r.isEmpty()) { // zero-size nodes are invisible text: drop them
            JSONObject o = new JSONObject().put("id", nodes.size()).put("role", role(n.getClassName()));
            if (n.isPassword() || Vault.filled.contains(n)) { password = true; o.put("password", true); } // never the text (vault fills stay hidden)
            else if (!empty(text)) o.put("text", Guard.clip(text.toString(), 160));
            if (!empty(desc) && (empty(text) || !desc.toString().equals(String.valueOf(text)))) o.put("desc", Guard.clip(desc.toString(), 120));
            if (n.isEditable() && n.isShowingHintText()) o.put("hint", true);
            if (n.isEditable() && n.isFocused()) { o.put("focused", true); focusedId = nodes.size(); } // the input the keyboard types into
            o.put("bounds", new JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom));
            JSONArray a = new JSONArray();
            if (n.isClickable()) a.put("click");
            if (n.isLongClickable()) a.put("long_click");
            if (n.isEditable()) a.put("edit");
            if (n.isScrollable()) a.put("scroll");
            if (n.isCheckable()) a.put(n.isChecked() ? "checked" : "unchecked");
            if (n.isFocused()) a.put("focused");
            if (!n.isEnabled()) a.put("disabled");
            o.put("actions", a);
            if ((n.isClickable() || n.isLongClickable()) && Guard.riskyLabel(n) != null) o.put("risk", "T3");
            String src = String.valueOf(n.getPackageName()); // provenance: who wrote this text (research/04 3d)
            o.put("src", src.equals("com.android.systemui") ? "SYSTEM" : "APP:" + src);
            nodes.add(n);
            out.put(o);
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out);
    }

    /** FLAG_SECURE screens: hide field contents and long digit runs (card numbers, codes, balances). */
    private static void redact(JSONArray out) throws JSONException {
        for (int i = 0; i < out.length(); i++) {
            JSONObject o = out.getJSONObject(i);
            String t = o.optString("text", "");
            if (o.getJSONArray("actions").toString().contains("edit") || DIGITS.matcher(t).find()) o.put("text", "(hidden: secure screen)");
        }
    }

    /** Root referee: which window has focus, and does it carry FLAG_SECURE? Fails closed (secure) if root is unavailable. */
    private void refreshFocus() {
        if (!dirty) return;
        dirty = false;
        try {
            String d = Root.run("dumpsys window displays | grep mCurrentFocus; dumpsys window windows");
            Matcher m = FOCUS.matcher(d);
            if (!m.find()) { pkg = ""; activity = ""; flagSecure = false; return; }
            pkg = m.group(2);
            activity = m.group(3);
            Matcher h = Pattern.compile("Window #\\d+ Window\\{" + Pattern.quote(m.group(1)) + " ").matcher(d);
            flagSecure = false;
            if (h.find()) {
                int end = d.indexOf("Window #", h.end());
                flagSecure = SECURE_FLAG.matcher(d.substring(h.start(), end < 0 ? d.length() : end)).find();
            }
        } catch (Exception e) {
            pkg = ""; activity = "";
            flagSecure = true; // can't check: assume secure
            dirty = true;
        }
    }

    static String role(CharSequence c) {
        if (c == null) return "View";
        String s = c.toString();
        return s.substring(s.lastIndexOf('.') + 1);
    }

    private static boolean empty(CharSequence c) { return c == null || c.toString().trim().isEmpty(); }
}
