package local.agentos;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The hands. Each method does one thing and says whether Android accepted it. Policy lives in Broker, not here. */
final class Actions {
    private Actions() {}

    /** Click the node or its nearest clickable ancestor; fall back to a tap gesture at its centre. */
    static boolean tap(AgentA11y s, AccessibilityNodeInfo n) {
        for (AccessibilityNodeInfo c = n; c != null; c = c.getParent())
            if (c.isClickable()) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return gesture(s, r.centerX(), r.centerY(), r.centerX(), r.centerY(), 60);
    }

    static boolean type(AccessibilityNodeInfo n, String text, boolean submit) {
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        return submit ? ok && n.performAction(AccessibilityAction.ACTION_IME_ENTER.getId()) : ok;
    }

    /** dir = what you want to see next: down/up/left/right. With a node: scroll it; without: swipe mid-screen. */
    static boolean scroll(AgentA11y s, AccessibilityNodeInfo n, String dir) {
        boolean forward = dir.equals("down") || dir.equals("right");
        if (n != null) {
            for (AccessibilityNodeInfo c = n; c != null; c = c.getParent())
                if (c.isScrollable()) return c.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        }
        DisplayMetrics d = s.getResources().getDisplayMetrics();
        int w = d.widthPixels, h = d.heightPixels;
        switch (dir) {
            case "down": return gesture(s, w / 2, h * 7 / 10, w / 2, h * 3 / 10, 300);
            case "up": return gesture(s, w / 2, h * 3 / 10, w / 2, h * 7 / 10, 300);
            case "right": return gesture(s, w * 8 / 10, h / 2, w * 2 / 10, h / 2, 300);
            case "left": return gesture(s, w * 2 / 10, h / 2, w * 8 / 10, h / 2, 300);
            default: throw new IllegalArgumentException("dir must be up, down, left or right");
        }
    }

    static boolean press(AccessibilityService s, String key) {
        switch (key) {
            case "back": return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            case "home": return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            case "recents": return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
            default: throw new IllegalArgumentException("key must be back, home or recents");
        }
    }

    static boolean openApp(AccessibilityService s, String pkg) {
        Intent i = s.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        s.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }

    static JSONArray listApps(AccessibilityService s) throws JSONException {
        PackageManager pm = s.getPackageManager();
        JSONArray out = new JSONArray();
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0))
            out.put(new JSONObject().put("pkg", r.activityInfo.packageName).put("label", String.valueOf(r.loadLabel(pm))));
        return out;
    }

    /** A straight-line gesture; waits until Android reports it finished. Tells AutoPause it's ours first. */
    static boolean gesture(AgentA11y s, int x1, int y1, int x2, int y2, long ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        final boolean[] ok = {false};
        CountDownLatch done = new CountDownLatch(1);
        s.autoPause.ownGesture(ms);
        s.dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, ms)).build(),
                new AccessibilityService.GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription g) { ok[0] = true; done.countDown(); }
                    @Override public void onCancelled(GestureDescription g) { done.countDown(); }
                }, s.main);
        try { done.await(ms + 2000, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        return ok[0];
    }
}
