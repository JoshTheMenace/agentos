package local.agentos;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shell-run renderer tests, in a separately signed test APK. Never a production RPC/approval bypass. */
public class ModTests extends Instrumentation {
    private static Object field(Object o, String name) throws Exception {
        Field f = (o instanceof Class ? (Class<?>) o : o.getClass()).getDeclaredField(name); f.setAccessible(true);
        return f.get(o instanceof Class ? null : o);
    }
    private static Object call(Object target, String name, Object... args) throws Exception {
        Class<?> cls = target instanceof Class ? (Class<?>) target : target.getClass();
        for (Method m : cls.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterCount() == args.length) {
            m.setAccessible(true);
            try { return m.invoke(target instanceof Class ? null : target, args); }
            catch (java.lang.reflect.InvocationTargetException e) { if (e.getCause() instanceof Exception) throw (Exception) e.getCause(); throw e; }
        }
        throw new NoSuchMethodException(name);
    }
    private static void check(boolean ok, Object why) { if (!ok) throw new AssertionError(String.valueOf(why)); }
    private void front(android.app.Service svc, String pkg) {
        String current = "";
        for (int i = 0; i < 80; i++) {
            android.view.accessibility.AccessibilityNodeInfo root = ((android.accessibilityservice.AccessibilityService) svc).getRootInActiveWindow();
            current = root == null ? "" : String.valueOf(root.getPackageName());
            if (pkg.equals(current)) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("expected " + pkg + ", foreground " + current);
    }
    private int shot(android.app.Service svc, String name, int x, int y) throws Exception {
        java.util.concurrent.CompletableFuture<Integer> done = new java.util.concurrent.CompletableFuture<>();
        ((android.accessibilityservice.AccessibilityService) svc).takeScreenshot(0, svc.getMainExecutor(), new android.accessibilityservice.AccessibilityService.TakeScreenshotCallback() {
            public void onSuccess(android.accessibilityservice.AccessibilityService.ScreenshotResult r) {
                try {
                    android.graphics.Bitmap hw = android.graphics.Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                    android.graphics.Bitmap bm = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                    hw.recycle(); r.getHardwareBuffer().close();
                    try (java.io.FileOutputStream out = svc.openFileOutput(name, 0)) { bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
                    int pixel = bm.getPixel(x, y); bm.recycle(); done.complete(pixel);
                } catch (Exception e) { done.completeExceptionally(e); }
            }
            public void onFailure(int code) { done.completeExceptionally(new IllegalStateException("screenshot " + code)); }
        });
        return done.get(3, java.util.concurrent.TimeUnit.SECONDS);
    }
    private JSONObject spec(String id) throws Exception {
        return new JSONObject("{\"id\":\"" + id + "\",\"package\":\"com.agentos.modfixture\",\"label\":\"Mask test\",\"action\":{\"type\":\"mask\",\"match\":{\"text\":\"Promoted\"},\"scope\":\"item\"}}");
    }
    @Override public void onCreate(Bundle b) { super.onCreate(b); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); JSONObject report = new JSONObject();
        Object mods = null;
        try {
            ClassLoader loader = getTargetContext().getClassLoader();
            Class<?> brokerClass = loader.loadClass("local.agentos.Broker"), modsClass = loader.loadClass("local.agentos.Mods");
            Object broker = null;
            for (int i = 0; i < 100 && broker == null; i++) { broker = field(brokerClass, "self"); if (broker == null) SystemClock.sleep(100); }
            check(broker != null, "accessibility service not bound");
            android.app.Service svc = (android.app.Service) field(broker, "svc"); mods = field(broker, "mods");
            final Object test = mods;
            android.content.Intent fixture = new android.content.Intent().setClassName("com.agentos.modfixture", "local.agentos.ModFixture").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            runOnMainSync(() -> svc.startActivity(fixture)); front(svc, "com.agentos.modfixture");
            JSONArray times = new JSONArray(), visible = new JSONArray();
            for (int i = 0; i < 10; i++) {
                JSONObject s = spec("mod-test");
                JSONObject preview = (JSONObject) call(mods, "preview", s);
                check(preview.getBoolean("ok"), "no preview match");
                long t = SystemClock.uptimeMillis();
                JSONObject r = ((JSONObject) call(mods, "apply", s, "isolated renderer test", 2500L));
                check(r.getBoolean("shown"), r); times.put(SystemClock.uptimeMillis() - t);
                {
                    SystemClock.sleep(350);
                    JSONArray rct = preview.getJSONArray("matches").getJSONArray(0);
                    int pixel = shot(svc, "mods-fixture-mask.png", (rct.getInt(0) + rct.getInt(2)) / 2, (rct.getInt(1) + rct.getInt(3)) / 2);
                    check((pixel & 0xFFFFFF) == 0x101820, "mask not visible: " + Integer.toHexString(pixel));
                    visible.put(SystemClock.uptimeMillis() - t);
                }
                check(((JSONObject) call(mods, "remove", "mod-test", "isolated renderer test")).getBoolean("ok"), "remove failed");
            }
            report.put("mask_apply_remove_ms", times).put("screenshot_verified_ms", visible);
            JSONObject noPreview = (JSONObject) call(broker, "call", "app.mod.apply", new JSONObject().put("spec", new JSONObject("{\"id\":\"no-preview\",\"package\":\"com.google.android.calculator\",\"label\":\"Gate test\",\"action\":{\"type\":\"mask\",\"match\":{\"id\":\"op_div\"}}}")), 2000);
            check(!noPreview.getBoolean("ok") && noPreview.optString("reason").contains("preview"), "production preview gate: " + noPreview);
            report.put("production_preview_gate", "pass");
            Class<?> rootClass = loader.loadClass("local.agentos.Root");
            String targetPid = ((String) call(rootClass, "run", "pidof com.agentos.modfixture")).trim();
            JSONObject bad = spec("mod-test"); bad.put("code", "throw new Error()");
            try { call(mods, "apply", bad, "test", 500L); throw new AssertionError("bad spec accepted"); }
            catch (IllegalArgumentException expected) { report.put("bad_spec", "rejected"); }
            for (String pkg : new String[]{"com.android.settings", "com.example.bank", "com.example.authenticator", "local.agentos.home", "com.whatsapp"}) {
                bad = spec("mod-test").put("package", pkg);
                try { call(modsClass, "validate", bad); throw new AssertionError("unsafe package accepted: " + pkg); } catch (IllegalArgumentException expected) {}
            }
            check(((JSONObject) call(mods, "apply", spec("mod-test"), "test", 2500L)).getBoolean("shown"), "fault setup");
            Method refresh = modsClass.getDeclaredMethod("refresh"); refresh.setAccessible(true);
            runOnMainSync(() -> {
                try {
                    Object m = ((Map<?, ?>) field(test, "mods")).get("mod-test");
                    ((JSONObject) field(m, "spec")).getJSONObject("action").put("color", "force renderer exception");
                    refresh.invoke(test);
                    check(!((String) field(m, "disabled")).isEmpty() && !((Boolean) field(m, "shown")) && ((java.util.List<?>) field(m, "masks")).isEmpty(), "exception not rolled back");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            check(!targetPid.isEmpty() && targetPid.equals(((String) call(rootClass, "run", "pidof com.agentos.modfixture")).trim()), "target stopped after bad spec/exception");
            report.put("target_pid_before_after_fault", targetPid);
            report.put("render_exception", call(mods, "list"));
            check(((JSONObject) call(mods, "remove", "mod-test", "test cleanup")).getBoolean("ok"), "cleanup");
            check(((JSONObject) call(mods, "apply", spec("mod-test"), "test", 2500L)).getBoolean("shown"), "kill setup");
            call(mods, "disable", "com.agentos.modfixture", "per-app kill test");
            check(call(mods, "list").toString().contains("per-app kill test"), "kill not persisted");
            call(mods, "remove", "mod-test", "test cleanup");
            call(mods, "apply", spec("mod-test"), "restart test", 2500L);
            call(rootClass, "run", "am force-stop com.agentos.modfixture");
            runOnMainSync(() -> svc.startActivity(fixture)); front(svc, "com.agentos.modfixture");
            check(((JSONArray) call(mods, "list")).getJSONObject(0).getBoolean("shown"), "mod did not survive target restart");
            call(mods, "disable", "", "global kill test");
            check(((JSONArray) call(mods, "list")).getJSONObject(0).optString("disabled").equals("global kill test"), "global kill failed");
            call(mods, "remove", "mod-test", "restart cleanup");
            report.put("target_restart_and_kill_switches", "pass");
            JSONObject feed = new JSONObject("{\"id\":\"mod-test\",\"package\":\"com.agentos.modfixture\",\"label\":\"Hide promoted\",\"action\":{\"type\":\"mask\",\"match\":{\"text\":\"Promoted\"},\"scope\":\"item\"}}");
            check(((JSONObject) call(mods, "preview", feed)).getBoolean("ok"), "item preview");
            check(((JSONObject) call(mods, "apply", feed, "test fixture", 2500L)).getBoolean("shown"), "item mask");
            runOnMainSync(() -> svc.startActivity(new android.content.Intent(fixture).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("crash", true)));
            SystemClock.sleep(2500);
            check(((JSONArray) call(mods, "list")).getJSONObject(0).optString("disabled").equals("target crash/ANR"), "crash watchdog failed: " + call(mods, "list"));
            report.put("real_fixture_crash", call(mods, "list"));
            call(mods, "remove", "mod-test", "fixture cleanup");
            call(rootClass, "run", "am force-stop com.agentos.modfixture");
            runOnMainSync(() -> svc.startActivity(svc.getPackageManager().getLaunchIntentForPackage("com.google.android.calculator").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))); front(svc, "com.google.android.calculator");

            JSONObject button = new JSONObject("{\"id\":\"mod-button-test\",\"package\":\"com.google.android.calculator\",\"label\":\"Double\",\"anchor\":\"op_div\",\"place\":\"above\",\"gap\":8,\"w\":\"anchor\",\"h\":44,\"action\":{\"type\":\"press\",\"press\":[\"op_mul\",\"digit_2\",\"eq\"]}}");
            check(((JSONObject) call(mods, "preview", button)).getBoolean("ok"), "button preview");
            report.put("button", call(mods, "apply", button, "isolated renderer test", 2500L));
            check(report.getJSONObject("button").getBoolean("shown"), "button missing");
            SystemClock.sleep(400); shot(svc, "mods-calculator-button.png", 0, 0);
            call(mods, "remove", "mod-button-test", "test cleanup");
            result.putString("report", report.toString()); finish(0, result);
        } catch (Throwable e) {
            if (mods != null) try { call(mods, "remove", "mod-test", "test cleanup"); call(mods, "remove", "mod-button-test", "test cleanup"); } catch (Exception ignored) {}
            result.putString("error", android.util.Log.getStackTraceString(e)); result.putString("report", report.toString()); finish(1, result);
        }
    }
}
