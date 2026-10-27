/*
 * Ported from buddy-android (https://github.com/ghorbelhamdi/buddy-android),
 * app/src/main/java/app/buddy/assistant/AutoPause.java. Copyright the buddy-android authors.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 * Changes by AgentOS: touch watching only (no call detection); calls Broker.humanTouched().
 */
package local.agentos;

import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Pauses the agent when a human touches the screen. A 1-pixel window that can't be seen or touched hears about every
 * touch elsewhere (FLAG_WATCH_OUTSIDE_TOUCH), but not where it landed. The agent's own gestures are skipped.
 */
final class AutoPause {
    /** MotionEvent.FLAG_IS_ACCESSIBILITY_EVENT (hidden, Android 12+): an accessibility service made this touch. */
    private static final int FLAG_FROM_ACCESSIBILITY = 0x800;
    private final AgentA11y svc;
    private View watcher;
    private volatile long ownUntil;

    AutoPause(AgentA11y svc) { this.svc = svc; }

    /** Main thread. */
    void start() {
        if (watcher != null) return;
        View v = new View(svc);
        v.setOnTouchListener((view, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) onOutside(e);
            return false;
        });
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        try { svc.getSystemService(WindowManager.class).addView(v, p); watcher = v; } catch (Exception ignored) {}
    }

    /** Main thread. */
    void stop() {
        if (watcher == null) return;
        try { svc.getSystemService(WindowManager.class).removeView(watcher); } catch (Exception ignored) {}
        watcher = null;
    }

    /** The agent is about to touch the screen itself for this long (any thread). */
    void ownGesture(long ms) { ownUntil = SystemClock.uptimeMillis() + ms + 600; }

    private void onOutside(MotionEvent e) {
        if ((e.getFlags() & FLAG_FROM_ACCESSIBILITY) != 0 || e.getEventTime() <= ownUntil) return;
        // Android reports coordinates only for touches on our own windows (the card): that's the user talking to us.
        if (e.getPressure() != 0f || e.getTouchMajor() != 0f) return;
        svc.main.post(svc.broker::humanTouched);
    }
}
