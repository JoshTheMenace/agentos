package local.agentos;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * screen.capture: one private screenshot for the voice ("what's on my screen?", "help me answer this").
 * Like a friend glancing at your phone when you hold it up: they see the app, not your passwords or our approval cards.
 * - Never on money, password or vault apps, system/AgentOS surfaces or FLAG_SECURE windows. A screen Prompt Guard flags is
 *   still captured but marked flagged (PG2 also flags some plain login pages), so agentd treats its text as hostile.
 * - Password fields (and vault-filled ones) and our own windows/overlays (island, approval cards) are painted black.
 * - If the launcher is in front (the voice sheet), the last app the user had open is brought back first, so the user
 *   sees exactly what was looked at. Never while an agent task holds the screen.
 * - Every capture or refusal is one audit line (Activity log), no pop-up. The image is returned, never stored.
 */
final class Look {
    private Look() {}
    static final String HOME = "local.agentos.home";
    private static final Pattern NEVER = Pattern.compile(
            "(?i)(bank|pay|wallet|finance|credit|crypto|trading|broker|password|vault|bitwarden|lastpass|keepass|1password|authenticator)");
    static volatile String lastApp = ""; // the last ordinary app in front (not us, the launcher, the keyboard or System UI)

    /** AgentA11y: remember the last ordinary app that came to the front. */
    static void onEvent(AccessibilityService s, AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String p = e.getPackageName().toString();
        if (p.equals("local.agentos") || p.equals(HOME) || p.equals("local.agentos.keys") || p.equals("com.android.systemui") || p.equals(lastApp)) return;
        if (s.getPackageManager().getLaunchIntentForPackage(p) != null) lastApp = p;
    }

    static JSONObject capture(AgentA11y svc, Audit audit, JSONObject p, boolean leaseHeld, String caller) throws Exception {
        String why = p.optString("why", "look at screen");
        JSONObject s = svc.screen.read(svc);
        String pkg = s.optString("pkg");
        boolean switched = false;
        if (leaseHeld) return refuse(audit, caller, pkg, why, "a task is using the screen"); // a read would also renumber its node ids
        if (pkg.equals(HOME) || pkg.isEmpty()) { // the voice sheet is in front: look at the app behind it
            String target = lastApp;
            if (target.isEmpty()) return refuse(audit, caller, pkg, why, "no app open to look at");
            String no = rule(target, false);
            if (no != null) return refuse(audit, caller, target, why, no); // checked before it is opened
            if (!Actions.openApp(svc, target)) return refuse(audit, caller, target, why, "can't open it");
            for (long end = SystemClock.uptimeMillis() + 2000; SystemClock.uptimeMillis() < end; ) {
                Thread.sleep(150); svc.screen.markDirty(); s = svc.screen.read(svc);
                if (target.equals(s.optString("pkg"))) break;
            }
            Thread.sleep(350); // let it finish drawing
            svc.screen.markDirty(); s = svc.screen.read(svc); pkg = s.optString("pkg"); switched = true;
            if (!target.equals(pkg)) return refuse(audit, caller, target, why, "the app didn't come to the front");
        }
        String no = rule(pkg, "FLAG_SECURE".equals(s.optString("secure_reason")));
        if (no != null) return refuse(audit, caller, pkg, why, no);
        PromptGuard.annotate(s); // flagged screens are still shown (PG2 flags some login pages), but marked so the eyes treat them as hostile
        boolean flagged = "MALICIOUS".equals(s.getJSONObject("guard").optString("label"));

        long t0 = SystemClock.uptimeMillis();
        CompletableFuture<Bitmap> f = new CompletableFuture<>();
        svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
            @Override public void onSuccess(AccessibilityService.ScreenshotResult r) {
                Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                f.complete(hw.copy(Bitmap.Config.ARGB_8888, true)); hw.recycle(); r.getHardwareBuffer().close();
            }
            @Override public void onFailure(int code) { f.completeExceptionally(new IllegalStateException("screenshot failed " + code)); }
        });
        Bitmap bm = f.get(3, TimeUnit.SECONDS);
        // black out: password fields, then our own windows and every accessibility overlay (island, cards, sign-in offer)
        Canvas c = new Canvas(bm); Paint black = new Paint(); black.setColor(Color.BLACK);
        int hidden = 0;
        JSONArray nodes = s.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.getJSONObject(i);
            if (!n.optBoolean("password")) continue;
            JSONArray b = n.getJSONArray("bounds");
            c.drawRect(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3), black); hidden++;
        }
        for (AccessibilityWindowInfo w : svc.getWindows()) {
            AccessibilityNodeInfo root = w.getRoot();
            boolean ours = w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                    || root != null && String.valueOf(root.getPackageName()).equals("local.agentos");
            if (!ours) continue;
            Rect r = new Rect(); w.getBoundsInScreen(r); c.drawRect(r, black); hidden++;
        }
        int max = Math.max(256, Math.min(2048, p.optInt("max", 1024))), q = Math.max(30, Math.min(90, p.optInt("quality", 70)));
        float k = Math.min(1f, max / (float) Math.max(bm.getWidth(), bm.getHeight()));
        Bitmap out = Bitmap.createScaledBitmap(bm, Math.round(bm.getWidth() * k), Math.round(bm.getHeight() * k), true);
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, q, jpg);
        int w = out.getWidth(), h = out.getHeight();
        bm.recycle(); out.recycle();
        audit.log(new JSONObject().put("task", "").put("tool", "screen.capture").put("pkg", pkg).put("target", why).put("caller", caller)
                .put("decision", "auto").put("reason", "screenshot for the voice" + (hidden > 0 ? ", " + hidden + " area(s) blacked out" : "") + (flagged ? ", Prompt Guard flagged its text" : ""))
                .put("bytes", jpg.size()).put("switched", switched));
        return new JSONObject().put("ok", true).put("pkg", pkg).put("jpeg", Base64.encodeToString(jpg.toByteArray(), Base64.NO_WRAP))
                .put("w", w).put("h", h).put("bytes", jpg.size()).put("hidden", hidden).put("switched", switched).put("flagged", flagged)
                .put("ms", SystemClock.uptimeMillis() - t0);
    }

    /** Why this app's screen is never captured, or null. */
    private static String rule(String pkg, boolean flagSecure) {
        if (NEVER.matcher(pkg).find()) return "money and password apps are never captured";
        if (Guard.forbidden(pkg)) return "system and AgentOS screens are never captured";
        if (flagSecure) return "this app blocks screenshots";
        return null;
    }

    private static JSONObject refuse(Audit audit, String caller, String pkg, String why, String reason) throws Exception {
        audit.log(new JSONObject().put("task", "").put("tool", "screen.capture").put("pkg", pkg).put("target", why).put("caller", caller)
                .put("decision", "refused").put("reason", reason));
        return new JSONObject().put("ok", false).put("decision", "refused").put("pkg", pkg).put("reason", reason);
    }
}
