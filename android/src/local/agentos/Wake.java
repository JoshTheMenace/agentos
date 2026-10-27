package local.agentos;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Waking and unlocking the phone for the founder's own tasks (a schedule they made, or a voice task when the screen locks).
 * Like a house sitter with a lockbox: the key (the PIN, /data/adb/agentos/unlock.pin, root:root 0600) never leaves the box.
 * One root shell reads it and types it into the system's own PIN pad, so it is never in our Java strings, our argv, logs,
 * agentd or the model. Who may come in is the broker's call (Broker.awake): this class only knows how.
 * Unlocking is not a fingerprint: approval and vault keys need BIOMETRIC_STRONG per use, which a PIN never satisfies.
 * Also the schedule alarm: an exact AlarmManager alarm that wakes the CPU even in deep doze and tells agentd (schedule.due).
 */
final class Wake {
    private static final String PIN = "/data/adb/agentos/unlock.pin", DUE = "local.agentos.SCHEDULE_DUE";
    private static PowerManager.WakeLock screen;
    private static int failures; // two wrong unlocks in a row: stop trying (the PIN pad locks out after five)

    private Wake() {}

    static boolean locked(Context c) { return c.getSystemService(KeyguardManager.class).isKeyguardLocked(); }
    static boolean asleep(Context c) { return !c.getSystemService(PowerManager.class).isInteractive(); }

    /** Screen on and keyguard gone afterwards? null if so, else why not. Blocks up to ~4 s. */
    static synchronized String unlock(Context c) {
        if (!asleep(c) && !locked(c)) return null;
        if (failures >= 2) return "auto-unlock is off after 2 failed tries (unlock the phone by hand once)";
        try {
            // Wake, ask the keyguard to go (a secure one shows its PIN pad), and type the PIN only while the system's own
            // keyguard window has focus (never into an app). $(cat) expands inside root's shell, not here.
            Root.run("input keyevent WAKEUP; wm dismiss-keyguard; sleep 0.8; [ -s " + PIN + " ] || exit 0; "
                    + "dumpsys window | grep -q 'mCurrentFocus=.*NotificationShade' || exit 0; "
                    + "input text \"$(cat " + PIN + ")\"; input keyevent ENTER");
        } catch (Exception e) { return "unlock failed: " + e.getMessage(); }
        for (long t = SystemClock.uptimeMillis(); locked(c) && SystemClock.uptimeMillis() - t < 3000; ) SystemClock.sleep(100);
        if (locked(c)) { failures++; return "unlock failed (still locked)"; }
        failures = 0;
        if (screen == null) { // keep the screen on while the task runs, so it doesn't lock again between steps
            screen = c.getSystemService(PowerManager.class).newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "agentos:task");
            screen.acquire(30 * 60_000L);
        }
        return null;
    }

    /** The task ended: let the screen time out again, and lock it now if it was locked before we opened it. */
    static synchronized void done(boolean relock) {
        if (screen != null && screen.isHeld()) screen.release();
        screen = null;
        if (relock) new Thread(() -> { try { Root.run("input keyevent SLEEP"); } catch (Exception ignored) {} }).start();
    }

    /** schedule.alarm: ring at `at` (epoch ms; 0 = cancel). */
    static JSONObject alarm(Context c, long at) throws JSONException {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(DUE).setPackage(c.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (at <= 0) am.cancel(pi);
        else if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        return new JSONObject().put("ok", true).put("at", at).put("exact", am.canScheduleExactAlarms());
    }

    /** The alarm rang: hold the CPU up for a minute so agentd (Termux) can start the task, and tell it. */
    static void listen(Context c) {
        c.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context x, Intent i) {
                x.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agentos:schedule").acquire(60_000L);
                try { Events.emit("schedule.due", new JSONObject().put("at", System.currentTimeMillis())); } catch (JSONException ignored) {}
            }
        }, new IntentFilter(DUE), Context.RECEIVER_NOT_EXPORTED);
    }

    /** A scheduled task finished: tell the user with a notification (a reminder lands here). */
    static void notify(Context c, String title, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("scheduled", "Scheduled tasks", NotificationManager.IMPORTANCE_HIGH));
        nm.notify("scheduled", (int) (System.currentTimeMillis() / 1000), new Notification.Builder(c, "scheduled")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build());
    }
}
