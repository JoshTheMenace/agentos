package local.agentos;

import android.app.Instrumentation;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** No touch dispatch and no approval click: checks window cleanup before the first traversal. */
public class WindowTests extends Instrumentation {
    private static Object field(Object o, String name) throws Exception {
        Field f = (o instanceof Class ? (Class<?>) o : o.getClass()).getDeclaredField(name); f.setAccessible(true);
        return f.get(o instanceof Class ? null : o);
    }
    @Override public void onCreate(Bundle b) { super.onCreate(b); start(); }
    @Override public void onStart() {
        Bundle out = new Bundle();
        try {
            ClassLoader loader = getTargetContext().getClassLoader();
            Object broker = field(loader.loadClass("local.agentos.Broker"), "self");
            android.app.Service svc = (android.app.Service) field(broker, "svc");
            WindowManager wm = svc.getSystemService(WindowManager.class);
            Method clear = loader.loadClass("local.agentos.ModMask").getDeclaredMethod("clear", WindowManager.class, List.class); clear.setAccessible(true);
            Method params = loader.loadClass("local.agentos.Mods").getDeclaredMethod("params"); params.setAccessible(true);
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            runOnMainSync(() -> {
                View v = new View(svc); List<View> views = new ArrayList<>(); views.add(v);
                try {
                    WindowManager.LayoutParams lp = (WindowManager.LayoutParams) params.invoke(null);
                    lp.width = lp.height = 30; lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    wm.addView(v, lp);
                    boolean attached = v.isAttachedToWindow(), parentBefore = v.getParent() != null;
                    clear.invoke(null, wm, views);
                    boolean leaked = v.getParent() != null;
                    android.util.Log.i("ModWindowTest", "attached=" + attached + " parentBefore=" + parentBefore + " parentAfterClear=" + leaked);
                    out.putString("window", "attached=" + attached + " parentBefore=" + parentBefore + " parentAfterClear=" + leaked);
                    if (leaked) throw new AssertionError("clear orphaned a pre-attachment window");
                } catch (Throwable e) { failure.set(e); }
                finally { try { wm.removeViewImmediate(v); } catch (RuntimeException ignored) {} }
            });
            if (failure.get() != null) throw new AssertionError(failure.get());
            Object mods = field(broker, "mods"), island = field(svc, "island");
            Method expand = island.getClass().getDeclaredMethod("expand", View.class, int.class); expand.setAccessible(true);
            Method collapse = island.getClass().getDeclaredMethod("collapse"); collapse.setAccessible(true);
            runOnMainSync(() -> {
                try {
                    View preview = new View(svc);
                    WindowManager.LayoutParams lp = (WindowManager.LayoutParams) params.invoke(null); lp.width = lp.height = 30;
                    wm.addView(preview, lp); ((List<View>) field(mods, "highlights")).add(preview);
                    android.widget.TextView face = new android.widget.TextView(svc); face.setText("Mod window test: no approval pending");
                    expand.invoke(island, face, 0xFF38BDF8);
                    if (!(Boolean) field(mods, "approving") || preview.getParent() != null || !((List<?>) field(mods, "highlights")).isEmpty()) throw new AssertionError("card did not clear/suspend mod windows");
                    android.util.Log.i("ModWindowTest", "card hold=true previewParent=null highlights=0");
                    collapse.invoke(island);
                } catch (Throwable e) { failure.set(e); }
            });
            android.os.SystemClock.sleep(800);
            runOnMainSync(() -> {
                try { if ((Boolean) field(mods, "approving")) throw new AssertionError("hold survived card collapse"); }
                catch (Throwable e) { failure.set(e); }
            });
            if (failure.get() != null) throw new AssertionError(failure.get());
            Method real = loader.loadClass("local.agentos.HumanTouch").getDeclaredMethod("real", android.view.MotionEvent.class, long.class); real.setAccessible(true);
            long now = android.os.SystemClock.uptimeMillis();
            for (int flags : new int[]{0, 1, 2, 0x800}) {
                android.view.MotionEvent.PointerProperties p = new android.view.MotionEvent.PointerProperties(); p.id = 0; p.toolType = android.view.MotionEvent.TOOL_TYPE_FINGER;
                android.view.MotionEvent.PointerCoords c = new android.view.MotionEvent.PointerCoords(); c.x = c.y = 1; c.pressure = c.size = 1;
                android.view.MotionEvent e = android.view.MotionEvent.obtain(now, now, 0, 1, new android.view.MotionEvent.PointerProperties[]{p}, new android.view.MotionEvent.PointerCoords[]{c}, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, flags);
                boolean accepted = (Boolean) real.invoke(null, e, now - 1000), young = (Boolean) real.invoke(null, e, now);
                e.recycle();
                if (accepted != (flags == 0) || young) throw new AssertionError("touch policy flags=" + flags);
            }
            android.util.Log.i("ModWindowTest", "PASS card hold/resume; finger accepted; obscured, partial, accessibility and early events rejected; no touch dispatched");
            out.putString("card_and_policy", "PASS; no touch dispatched");
            finish(0, out);
        } catch (Throwable e) { out.putString("error", android.util.Log.getStackTraceString(e)); finish(1, out); }
    }
}
