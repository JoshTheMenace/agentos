package local.agentos;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * The accessibility service: the phone's eyes and hands, and the process that hosts the broker and tool server.
 * Kill switch: hold volume-down for 1 s to stop the active task (physical keys can't be pressed by the agent).
 */
public class AgentA11y extends AccessibilityService {
    final Handler main = new Handler(Looper.getMainLooper());
    final Screen screen = new Screen();
    volatile long lastEvent;
    AutoPause autoPause;
    ApprovalCard card;
    Broker broker;
    private Server server;
    private long volDownAt;

    @Override protected void onServiceConnected() {
        autoPause = new AutoPause(this);
        card = new ApprovalCard(this);
        broker = new Broker(this);
        try {
            Approvals.ensureKey(this, false);
            server = new Server(this, broker);
            server.start();
        } catch (Exception e) { Log.e("AgentOS", "startup failed", e); }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        lastEvent = SystemClock.uptimeMillis();
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) screen.markDirty();
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) volDownAt = e.getEventTime();
        if (e.getAction() == KeyEvent.ACTION_UP && e.getEventTime() - volDownAt >= 1000) {
            new Thread(() -> { try { broker.stop("", "volume-down kill switch"); } catch (Exception ignored) {} }).start();
        }
        return false; // the volume still changes
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        if (server != null) server.stop();
        if (card != null) card.hide();
        if (autoPause != null) autoPause.stop();
        super.onDestroy();
    }
}
