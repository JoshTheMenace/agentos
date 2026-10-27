package local.agentos.watch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Keeps one WebSocket open to agentd and boils its events down to one line of state: an accent color, a title, a sub line.
 * Think of it as the wrist's radio: it listens to the phone's agent and buzzes when you're needed or it's done.
 * Runs as a foreground service so the link (and the glanceable notification) survive the watch screen turning off.
 */
public class Link extends Service {
  static final String TAG = "AgentWatch";
  static final int IDLE = 0xFF8E8E93, WORKING = 0xFFFF9F0A, APPROVAL = 0xFF0A84FF, DONE = 0xFF30D158, ALERT = 0xFFFF453A; // A2 state colors
  static volatile int acc = IDLE;
  static volatile String title = "Connecting", sub = "", reply = "";
  static volatile boolean online, turn;
  static volatile Runnable onChange; // the open Face redraws on this
  static Link me;

  final Handler main = new Handler(Looper.getMainLooper());
  volatile Ws ws;
  String goal = "";
  final Runnable rest = () -> set(IDLE, "Ready", "Tap to talk");

  @Override public void onCreate() {
    super.onCreate();
    me = this;
    getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("agent", "Agent status", NotificationManager.IMPORTANCE_LOW));
    startForeground(1, note());
    new Thread(this::run, "agentd-link").start();
  }
  @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }
  @Override public IBinder onBind(Intent i) { return null; }

  /** Connect, read, reconnect with backoff (1 s .. 15 s). The token comes from our private files dir (install.sh puts it there). */
  void run() {
    for (int wait = 1000; ; wait = Math.min(wait * 2, 15_000)) {
      String tok = file("agentd.token"), at = file("agentd.addr");
      String[] hp = (at == null ? "127.0.0.1:8787" : at).split(":");
      try {
        if (tok == null) throw new Exception("no token: run watch/install.sh");
        Ws w = new Ws(hp[0], Integer.parseInt(hp[1]), tok);
        ws = w; online = true; wait = 1000;
        main.post(() -> { if (acc == IDLE) rest.run(); else changed(); });
        Thread pinger = new Thread(() -> { try { while (ws == w) { Thread.sleep(20_000); w.ping(); } } catch (Exception ignored) {} });
        pinger.start();
        w.loop((s) -> main.post(() -> on(s)));
      } catch (Exception e) {
        Log.w(TAG, "link: " + e.getMessage());
        Ws w = ws; ws = null; if (w != null) w.close();
        online = false; turn = false;
        main.post(() -> set(IDLE, "Phone offline", "Reconnecting"));
      }
      try { Thread.sleep(wait); } catch (InterruptedException ignored) {}
    }
  }

  /** A spoken request from the wrist: an ordinary agentd turn, same tools and the same approval gate as typing on the phone. */
  static boolean ask(String text) {
    Link l = me; Ws w = l == null ? null : l.ws;
    if (w == null || turn) return false;
    turn = true; reply = "";
    new Thread(() -> { try { w.send(new JSONObject().put("type", "ask").put("text",
        "(Asked from the watch: reply in one short sentence, it shows on a small round screen.) " + text).toString()); } catch (Exception e) { turn = false; } }).start();
    l.set(WORKING, clip(text, 40), "Thinking");
    return true;
  }

  void on(String raw) {
    JSONObject m; try { m = new JSONObject(raw); } catch (Exception e) { return; }
    switch (m.optString("type")) {
      case "task.state": {
        String st = m.optString("state");
        goal = clip(m.optString("goal"), 44);
        if ("stopped".equals(st)) { set(DONE, "Done", goal); buzz(VibrationEffect.EFFECT_DOUBLE_CLICK); later(); }
        else set(WORKING, goal, "paused".equals(st) ? "Paused" : "Working");
        break;
      }
      case "audit.append": { // one line per step, like the island
        String tool = m.optString("tool"), t = m.optString("target");
        if (tool.startsWith("task.") || acc == APPROVAL) break;
        String app = m.optString("pkg").replaceAll(".*\\.", "");
        set(WORKING, g(), ("open_app".equals(tool) ? "Opening " + app : "type".equals(tool) ? "Typing in " + app
            : "tap".equals(tool) ? "Tap " + clip(t, 18) : tool));
        break;
      }
      case "approval.shown": {
        JSONArray ch = m.optJSONArray("allowed_channels");
        boolean fp = ch == null || !ch.toString().contains("\"tap\"");
        set(APPROVAL, "lease".equals(m.optString("kind")) ? "Allow apps?" : clip(m.optString("summary", "Needs you"), 40),
            fp ? "Fingerprint on phone" : "Approve on phone");
        buzz(VibrationEffect.EFFECT_HEAVY_CLICK);
        break;
      }
      case "review": if ("approved".equals(m.optString("action"))) set(DONE, g(), "Auto-approved ✓"); break;
      case "approval.resolved": if (acc == APPROVAL) set(WORKING, g(), "approved".equals(m.optString("decision")) ? "Approved ✓" : "Denied"); break;
      case "guard.flag": set(ALERT, g(), "Flagged · in log"); break;
      case "text.delta": reply += m.optString("text"); break;
      case "turn.done": turn = false; if (!reply.isEmpty()) { set(DONE, "Done", clip(reply, 90)); later(); } break;
      case "error": if (turn && !m.optBoolean("retryable")) { turn = false; set(ALERT, "Couldn't do that", clip(m.optString("message"), 60)); later(); } break;
    }
  }

  String g() { return goal.isEmpty() ? "Working" : goal; } // we may join mid-task, before its task.state
  void set(int a, String t, String s) { acc = a; title = t; sub = s; changed(); }
  void later() { main.removeCallbacks(rest); main.postDelayed(rest, 12_000); }
  void changed() {
    if (acc != DONE) main.removeCallbacks(rest);
    getSystemService(NotificationManager.class).notify(1, note());
    Runnable r = onChange; if (r != null) r.run();
  }
  void buzz(int fx) { Vibrator v = getSystemService(Vibrator.class); if (v != null) v.vibrate(VibrationEffect.createPredefined(fx)); }

  Notification note() { // the glanceable line in the watch's notification stream; tap opens the face
    PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, Face.class), PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, "agent").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(title).setContentText(sub)
        .setColor(acc).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi).build();
  }

  String file(String n) { try { return new String(Files.readAllBytes(new File(getFilesDir(), n).toPath()), StandardCharsets.UTF_8).trim(); } catch (Exception e) { return null; } }
  static String clip(String s, int n) { s = s == null ? "" : s.trim(); return s.length() > n ? s.substring(0, n - 1) + "…" : s; }
}
