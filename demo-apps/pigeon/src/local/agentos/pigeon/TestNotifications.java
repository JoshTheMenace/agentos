package local.agentos.pigeon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline test fixture: notification replies stay in Pigeon's own test history, never reach a contact. */
public class TestNotifications extends BroadcastReceiver {
    private static final String POST = "local.agentos.pigeon.TEST_POST", CLEAR = "local.agentos.pigeon.TEST_CLEAR";

    @Override public void onReceive(Context c, Intent i) {
        if (!POST.equals(i.getAction()) && !CLEAR.equals(i.getAction())) return;
        String conversation = i.getStringExtra("conversation");
        if (conversation == null || !conversation.startsWith("AgentOS test")) return;
        if (CLEAR.equals(i.getAction())) {
            c.getSystemService(NotificationManager.class).cancel(conversation, 71);
            prefs(c).edit().remove(conversation).remove(conversation + ":read_at").apply();
        } else append(c, conversation, i.getStringExtra("sender"), i.getStringExtra("text"));
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("notification-tests", Context.MODE_PRIVATE); }

    private static void append(Context c, String conversation, String sender, String text) {
        if (text == null || text.isEmpty()) return;
        try {
            JSONArray history = new JSONArray(prefs(c).getString(conversation, "[]"));
            history.put(new JSONObject().put("sender", sender == null ? "" : sender).put("text", text).put("when", System.currentTimeMillis()));
            while (history.length() > 20) history.remove(0);
            prefs(c).edit().putString(conversation, history.toString()).apply();
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("notification-tests", "AgentOS notification tests", NotificationManager.IMPORTANCE_LOW));
            Notification.MessagingStyle style = new Notification.MessagingStyle(new Person.Builder().setName("Me").setKey("self").build())
                    .setConversationTitle(conversation).setGroupConversation(true);
            for (int j = 0; j < history.length(); j++) {
                JSONObject m = history.getJSONObject(j);
                String who = m.getString("sender");
                Notification.MessagingStyle.Message message = new Notification.MessagingStyle.Message(m.getString("text"), m.getLong("when"),
                        who.isEmpty() ? null : new Person.Builder().setName(who).setKey(who).build());
                if (j < history.length() - 2) style.addHistoricMessage(message); else style.addMessage(message);
            }
            Intent base = new Intent(c, Reply.class).putExtra("conversation", conversation);
            int code = conversation.hashCode();
            PendingIntent reply = PendingIntent.getBroadcast(c, code, new Intent(base).setAction("reply"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            PendingIntent read = PendingIntent.getBroadcast(c, code, new Intent(base).setAction("read"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nm.notify(conversation, 71, new Notification.Builder(c, "notification-tests").setSmallIcon(android.R.drawable.sym_action_chat)
                    .setCategory(Notification.CATEGORY_MESSAGE).setShortcutId(conversation).setStyle(style).setOnlyAlertOnce(true)
                    .addAction(new Notification.Action.Builder(null, "Reply", reply).setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                            .addRemoteInput(new RemoteInput.Builder("reply").setLabel("Reply").build()).build())
                    .addAction(new Notification.Action.Builder(null, "Mark as read", read).setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ).build()).build());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static class Reply extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            String conversation = i.getStringExtra("conversation");
            if (conversation == null || !conversation.startsWith("AgentOS test")) return;
            if ("read".equals(i.getAction())) {
                prefs(c).edit().putLong(conversation + ":read_at", System.currentTimeMillis()).apply();
                c.getSystemService(NotificationManager.class).cancel(conversation, 71);
            } else if ("reply".equals(i.getAction())) {
                Bundle results = RemoteInput.getResultsFromIntent(i);
                CharSequence text = results == null ? null : results.getCharSequence("reply");
                if (text != null) append(c, conversation, null, text.toString());
            }
        }
    }
}
