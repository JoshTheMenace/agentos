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
    private JSONObject spec(String id) throws Exception {
        return new JSONObject("{\"id\":\"" + id + "\",\"package\":\"com.google.android.calculator\",\"label\":\"Mask test\",\"action\":{\"type\":\"mask\",\"match\":{\"id\":\"op_div\"},\"scope\":\"node\"}}");
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
            runOnMainSync(() -> svc.startActivity(svc.getPackageManager().getLaunchIntentForPackage("com.google.android.calculator").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)));
            SystemClock.sleep(1500);
            JSONArray times = new JSONArray();
            for (int i = 0; i < 10; i++) {
                JSONObject s = spec("mod-test");
                check(((JSONObject) call(mods, "preview", s)).getBoolean("ok"), "no preview match");
                long t = SystemClock.uptimeMillis();
                JSONObject r = ((JSONObject) call(mods, "apply", s, "isolated renderer test", 2500L));
                check(r.getBoolean("shown"), r); times.put(SystemClock.uptimeMillis() - t);
                check(((JSONObject) call(mods, "remove", "mod-test", "isolated renderer test")).getBoolean("ok"), "remove failed");
            }
            report.put("mask_apply_remove_ms", times);
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
            report.put("render_exception", call(mods, "list"));
            check(((JSONObject) call(mods, "remove", "mod-test", "test cleanup")).getBoolean("ok"), "cleanup");
            check(((JSONObject) call(mods, "apply", spec("mod-test"), "test", 2500L)).getBoolean("shown"), "kill setup");
            call(mods, "disable", "com.google.android.calculator", "per-app kill test");
            check(call(mods, "list").toString().contains("per-app kill test"), "kill not persisted");
            call(mods, "remove", "mod-test", "test cleanup");
            report.put("kill_switch", "pass");
            android.content.Intent fixture = new android.content.Intent().setClassName("com.agentos.modtests", "local.agentos.ModFixture").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            runOnMainSync(() -> svc.startActivity(fixture)); SystemClock.sleep(1500);
            JSONObject feed = new JSONObject("{\"id\":\"mod-test\",\"package\":\"com.agentos.modtests\",\"label\":\"Hide promoted\",\"action\":{\"type\":\"mask\",\"match\":{\"text\":\"Promoted\"},\"scope\":\"item\"}}");
            check(((JSONObject) call(mods, "preview", feed)).getBoolean("ok"), "item preview");
            check(((JSONObject) call(mods, "apply", feed, "test fixture", 2500L)).getBoolean("shown"), "item mask");
            runOnMainSync(() -> svc.startActivity(new android.content.Intent(fixture).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("crash", true)));
            SystemClock.sleep(2500);
            check(call(mods, "list").toString().contains("target crash/ANR"), "crash watchdog failed: " + call(mods, "list"));
            report.put("real_fixture_crash", call(mods, "list"));
            call(mods, "remove", "mod-test", "fixture cleanup");
            runOnMainSync(() -> svc.startActivity(svc.getPackageManager().getLaunchIntentForPackage("com.google.android.calculator").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))); SystemClock.sleep(1500);

            JSONObject button = new JSONObject("{\"id\":\"mod-button-test\",\"package\":\"com.google.android.calculator\",\"label\":\"Tip\",\"anchor\":\"op_div\",\"place\":\"above\",\"gap\":8,\"w\":\"anchor\",\"h\":44,\"action\":{\"type\":\"press\",\"press\":[\"op_add\"]}}");
            check(((JSONObject) call(mods, "preview", button)).getBoolean("ok"), "button preview");
            report.put("button", call(mods, "apply", button, "isolated renderer test", 2500L));
            check(report.getJSONObject("button").getBoolean("shown"), "button missing");
            SystemClock.sleep(2000);
            call(mods, "remove", "mod-button-test", "test cleanup");
            result.putString("report", report.toString()); finish(0, result);
        } catch (Throwable e) {
            if (mods != null) try { call(mods, "remove", "mod-test", "test cleanup"); call(mods, "remove", "mod-button-test", "test cleanup"); } catch (Exception ignored) {}
            result.putString("error", android.util.Log.getStackTraceString(e)); result.putString("report", report.toString()); finish(1, result);
        }
    }
}
