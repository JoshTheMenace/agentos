/*
 * Portions of this file are ported from buddy-android (https://github.com/ghorbelhamdi/buddy-android),
 * app/src/main/java/app/buddy/assistant/Guard.java. Copyright the buddy-android authors.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 * Changes by AgentOS: English-only RISKY subset (+ "install"), our FORBIDDEN package list, a "private data"
 * package check, and offLimits reduced to "our package or any accessibility overlay".
 */
package local.agentos;

import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Static rules: which buttons are outward (risky), which apps hold private data, which are never touched. */
final class Guard {
    private Guard() {}

    /** Button words that send, pay, delete or otherwise can't be taken back (Buddy's list, English). */
    private static final Pattern RISKY = Pattern.compile("(?iu)^[^\\p{L}]*(?:(?:proceed|continue|go) to )?("
            + "send|send now|post|publish|share|share now|tweet|reply|reply all|submit|confirm|pay|pay now|buy|buy now|purchase|"
            + "order|place order|checkout|check out|payment|add to cart|add to basket|add to bag|subscribe|transfer|donate|"
            + "delete|remove|erase|install|uninstall|update|unfriend|block|report|unsubscribe|deactivate|factory reset|reset|"
            + "sign out|log out|logout|call|allow|accept"
            + ")(?![\\p{L}])");
    /** Resource ids of icon-only outward buttons (e.g. a chat app's send arrow). */
    private static final Pattern RISKY_ID = Pattern.compile(
            "(?i)[:/_](send|send_button|sendbutton|btn_send|delete|pay|buy|post|submit|add-to-cart|add_to_cart|addtocart)\\b");

    /** Apps that hold other people's messages or money: messaging, mail, social, calls, store (Buddy's HARD list). */
    private static final Set<String> PRIVATE = new HashSet<>(Arrays.asList(
            "com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.messaging", "com.android.mms",
            "org.telegram.messenger", "org.thoughtcrime.securesms", "com.facebook.orca", "com.facebook.katana",
            "com.instagram.android", "com.snapchat.android", "com.twitter.android", "com.zhiliaoapp.musically",
            "com.linkedin.android", "com.discord", "com.Slack", "com.viber.voip", "com.google.android.gm",
            "com.microsoft.office.outlook", "com.google.android.dialer", "com.android.dialer",
            "com.google.android.contacts", "com.android.contacts", "com.google.android.apps.photos",
            "com.android.vending", "com.anthropic.claude", "com.termux"));
    private static final Pattern PRIVATE_NAME = Pattern.compile("(?i)(bank|banking|pay|wallet|finance|credit|crypto|trading|broker)");

    /** Never acted on, whatever the lease says (tier T4): system security surfaces and ourselves. */
    private static final Set<String> FORBIDDEN = new HashSet<>(Arrays.asList(
            "local.agentos", "com.android.settings", "com.android.systemui", "com.topjohnwu.magisk",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            "com.google.android.packageinstaller", "com.android.packageinstaller", "com.motorola.settings"));

    static boolean forbidden(String pkg) { return pkg == null || FORBIDDEN.contains(pkg); }

    static boolean privateApp(String pkg) { return pkg != null && (PRIVATE.contains(pkg) || PRIVATE_NAME.matcher(pkg).find()); }

    /** The agent can never touch our windows (so it can't approve for itself), nor the launcher (its taps count as the owner's). */
    static boolean offLimits(AccessibilityNodeInfo n) {
        if (n == null) return true;
        String p = String.valueOf(n.getPackageName());
        if (p.equals("local.agentos") || p.equals("local.agentos.home")) return true;
        AccessibilityWindowInfo w = n.getWindow();
        return w != null && w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY;
    }

    /** The risky label of what would be tapped (the node, its clickable button and that button's children), or null. */
    static String riskyLabel(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 4; i++) {
            String hit = risky(cur);
            if (hit != null) return hit;
            if (cur.isClickable()) {
                for (int c = 0; c < Math.min(cur.getChildCount(), 6); c++) {
                    AccessibilityNodeInfo ch = cur.getChild(c);
                    String h = ch == null ? null : risky(ch);
                    if (h != null) return h;
                }
                break;
            }
            cur = cur.getParent();
        }
        return null;
    }

    /** Mods: the outward word in a label or selector text ("Send", "Pay now"), or null. */
    static String riskyText(String s) { return s != null && RISKY.matcher(s.trim()).find() ? s.trim() : null; }

    private static String risky(AccessibilityNodeInfo n) {
        for (CharSequence cs : new CharSequence[]{n.getText(), n.getContentDescription(), n.getTooltipText()}) {
            if (cs == null) continue;
            String s = cs.toString().trim();
            // a button label starts with the word and is short, so "the audio call ended" isn't a Call button
            if (s.isEmpty() || s.length() > 30 || s.split("\\s+").length > 4) continue;
            if (RISKY.matcher(s).find()) return s;
        }
        String id = n.getViewIdResourceName();
        if (id != null && RISKY_ID.matcher(id.toLowerCase(Locale.ROOT)).find()) return id.substring(id.lastIndexOf('/') + 1).replace('_', ' ');
        return null;
    }

    /** What a human would call this node: its text, else description, else resource id, else class. */
    static String label(AccessibilityNodeInfo n) {
        if (n == null) return "";
        if (n.isPassword() || Vault.filled.contains(n)) return "(password field)";
        for (CharSequence cs : new CharSequence[]{n.getText(), n.getContentDescription(), n.getHintText()})
            if (cs != null && cs.toString().trim().length() > 0) return clip(cs.toString(), 80);
        String id = n.getViewIdResourceName();
        if (id != null) return id.substring(id.lastIndexOf('/') + 1);
        return Screen.role(n.getClassName());
    }

    static String clip(String s, int max) {
        s = s.replace('\n', ' ').trim();
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
