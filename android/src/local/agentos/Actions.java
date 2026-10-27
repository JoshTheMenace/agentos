package local.agentos;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.net.Uri;
import android.app.SearchManager;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.MediaStore;
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

    /**
     * Allow-listed direct intents: one call instead of a string of taps. Only these kinds; never arbitrary intents
     * (no SMS/call/share intents, no URLs: an injected page can't make the agent open evil.com?data=...).
     * kind alarm {hour, minute, label?} · timer {seconds, label?} · search {query} (Google in the browser) ·
     * event {title, start "YYYY-MM-DDTHH:MM" local, minutes?} (Calendar's new-event screen, filled in; nothing is saved
     * until Save is tapped) · play {pkg, query} (play music from a search in that app; "" = anything).
     */
    static Intent intent(JSONObject p) throws JSONException {
        String label = p.optString("label", "");
        switch (p.getString("kind")) {
            case "alarm": return new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, clamp(p.getInt("hour"), 0, 23))
                    .putExtra(AlarmClock.EXTRA_MINUTES, clamp(p.optInt("minute", 0), 0, 59)).putExtra(AlarmClock.EXTRA_MESSAGE, label);
            case "timer": return new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, clamp(p.getInt("seconds"), 1, 86400))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, false);
            case "search": return new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(p.getString("query"))));
            case "event": {
                long t = java.time.LocalDateTime.parse(p.getString("start")).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                return new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, p.getString("title"))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, t).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, t + clamp(p.optInt("minutes", 60), 5, 1440) * 60_000L);
            }
            case "play": return new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(SearchManager.QUERY, p.optString("query", ""))
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
            default: throw new IllegalArgumentException("kind must be alarm, timer, search, event or play");
        }
    }

    /** The app that will handle it (the lease must cover it). A browser chooser falls back to Chrome. "play" names its app. */
    static String intentPkg(AccessibilityService s, JSONObject p) throws JSONException {
        Intent i = intent(p);
        if (p.getString("kind").equals("play")) i.setPackage(p.getString("pkg"));
        ResolveInfo r = s.getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        String pkg = r == null ? null : r.activityInfo.packageName;
        if (i.getPackage() != null && pkg == null) throw new IllegalArgumentException(i.getPackage() + " can't play from a search");
        return pkg == null || pkg.equals("android") ? "com.android.chrome" : pkg;
    }

    static boolean startIntent(AccessibilityService s, JSONObject p) throws JSONException {
        if (p.getString("kind").equals("play") && p.optString("query").isEmpty()) { // "play something": open the app, then Play (resumes its music)
            if (!openApp(s, intentPkg(s, p))) return false;
            android.media.AudioManager a = s.getSystemService(android.media.AudioManager.class); // the app needs 3-7 s to set up its player;
            for (int ms = 3000; ms <= 7000; ms += 2000) // Play (not play/pause) is harmless once it is playing, so press it a few times
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> { for (int k : new int[]{android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP})
                    a.dispatchMediaKeyEvent(new android.view.KeyEvent(k, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)); }, ms);
            return true;
        }
        s.startActivity(intent(p).setPackage(intentPkg(s, p)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

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
