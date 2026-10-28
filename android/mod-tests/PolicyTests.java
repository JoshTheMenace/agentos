package local.agentos;

import android.app.Instrumentation;
import android.os.Bundle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/** Policy-only: no broker calls, windows, touches, or approval confirmations. */
public class PolicyTests extends Instrumentation {
    private static void set(Object o, String name, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); f.set(o, value);
    }
    private static Object get(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }
    private Method policy;
    private int cases;
    private void check(Object lease, JSONObject p, String pkg, boolean mask, boolean caller, boolean ordinary) throws Exception {
        List<?> channels = (List<?>) policy.invoke(null, lease, p, pkg, mask, caller);
        if (channels.contains("reviewer") != ordinary) throw new AssertionError("case " + cases + ": " + channels);
        cases++;
    }
    @Override public void onCreate(Bundle b) { super.onCreate(b); start(); }
    @Override public void onStart() {
        Bundle out = new Bundle();
        try {
            ClassLoader cl = getTargetContext().getClassLoader();
            Class<?> broker = cl.loadClass("local.agentos.Broker"), lc = cl.loadClass("local.agentos.Broker$Lease"), hc = cl.loadClass("local.agentos.Broker$History");
            Constructor<?> history = hc.getDeclaredConstructor(); history.setAccessible(true); Object h = history.newInstance();
            Constructor<?> lease = lc.getDeclaredConstructors()[0]; lease.setAccessible(true);
            String pkg = "com.linkedin.android";
            Object l = lease.newInstance("Hide Promoted posts in LinkedIn", Collections.singleton(pkg), System.currentTimeMillis() + 60000, 40, "com.termux", h);
            set(l, "asked", true); set(l, "granted", true); set(l, "origin", "typed");
            policy = broker.getDeclaredMethod("modChannels", lc, JSONObject.class, String.class, boolean.class, boolean.class); policy.setAccessible(true);
            JSONObject p = new JSONObject().put("task_id", get(l, "id"));
            check(l, p, pkg, true, true, true);
            set(h, "untrusted", true); check(l, p, pkg, true, true, true); // selector observations are not request taint
            set(l, "origin", "voice"); check(l, p, pkg, true, true, true);
            set(l, "origin", "remote"); check(l, p, pkg, true, true, false); set(l, "origin", "typed");
            for (String field : new String[]{"flagged", "copied"}) { set(h, field, true); check(l, p, pkg, true, true, false); set(h, field, false); }
            p.put("tainted", true); check(l, p, pkg, true, true, false); p.remove("tainted");
            set(l, "asked", false); check(l, p, pkg, true, true, false); set(l, "asked", true);
            set(l, "paused", true); check(l, p, pkg, true, true, false); set(l, "paused", false);
            check(l, new JSONObject(), pkg, true, true, false);
            check(l, p, "com.example.other", true, true, false);
            check(l, p, pkg, false, true, false);
            check(l, p, pkg, true, false, false);
            check(null, p, pkg, true, true, false);
            out.putString("policy", cases + " cases PASS; no approval dispatched"); finish(0, out);
        } catch (Throwable e) { out.putString("error", android.util.Log.getStackTraceString(e)); finish(1, out); }
    }
}
