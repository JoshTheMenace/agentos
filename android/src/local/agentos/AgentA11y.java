package local.agentos;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
    Island island;
    ApprovalCard card;
    Broker broker;
    private Server server;
    private long volDownAt;

    @Override protected void onServiceConnected() {
        autoPause = new AutoPause(this);
        island = new Island(this);
        card = new ApprovalCard(this);
        broker = new Broker(this);
        island.onStop = () -> new Thread(() -> { try { broker.stop("", "island Stop button"); } catch (Exception ignored) {} }).start();
        // UI demo hook, adb only (senders must hold android.permission.DUMP): drives the island and cards without the broker.
        registerReceiver(demo, new IntentFilter("local.agentos.UI"), "android.permission.DUMP", main, Context.RECEIVER_EXPORTED);
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

    /** am broadcast -a local.agentos.UI --es island working|approval|done|alert|hide --es label L --ei pct N --es goal G
     *  --es card approval|lease|auto [--es pkg P --es text T --ei min M --ez finger true]
     *  --es notify TEXT [--es from NAME]: a test chat notification with a real Reply action (Notifs.postTest) */
    private final BroadcastReceiver demo = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String st = i.getStringExtra("island"), cd = i.getStringExtra("card"), pkg = i.getStringExtra("pkg"), text = i.getStringExtra("text");
            if (pkg == null) pkg = "local.agentos.pigeon";
            if (i.hasExtra("notify")) Notifs.postTest(c, i.hasExtra("from") ? i.getStringExtra("from") : "Alice", i.getStringExtra("notify"));
            if ("hide".equals(st)) island.hide();
            else if (st != null) { if (i.hasExtra("goal")) island.goal = i.getStringExtra("goal"); island.set(st, i.getStringExtra("label"), i.getIntExtra("pct", -1)); }
            if (cd == null) return;
            ApprovalCard.Listener close = new ApprovalCard.Listener() { public void approve() { card.hide(); } public void deny() { card.hide(); } };
            switch (cd) {
                case "approval": {
                    ApprovalCard.Model m = new ApprovalCard.Model();
                    m.title = "tap \"Send\""; m.app = pkg; m.target = "Send"; m.text = text == null ? "Running late, there in about 10 min." : text;
                    m.task = "demo · text Mom"; m.tier = "T3"; m.reason = "outward action"; m.untrusted = i.getBooleanExtra("untrusted", false);
                    m.priv = true; m.outward = true; m.fingerprint = i.getBooleanExtra("finger", false);
                    card.show(m, close); break; }
                case "lease": card.lease("Pigeon", String.valueOf(i.getIntExtra("min", 10)), text == null ? "Text Mom that I'm late" : text, "agentd", close); break;
                default: card.autoApproved(text == null ? "tap \"Reply\"" : text, "Low risk: a reply inside the task's own chat, no private data, no new recipient.");
            }
        }
    };

    @Override public void onDestroy() {
        if (server != null) server.stop();
        try { unregisterReceiver(demo); } catch (Exception ignored) {}
        if (island != null) island.hide();
        if (autoPause != null) autoPause.stop();
        super.onDestroy();
    }
}
