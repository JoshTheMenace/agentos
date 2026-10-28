package local.agentos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The notification feed. Like a mail-room clerk: it logs every letter as it arrives (app, conversation, sender, text,
 * time) and keeps the reply envelope (the notification's RemoteInput "Reply" action), so the agent can answer in a
 * fraction of a second without opening the app. Notification text is written by other people, so it is UNTRUSTED:
 * Prompt Guard scores every one, and a task that reads it counts as untrusted (Broker). Replies are T3 actions in the
     * broker (notifications.reply), never sent from here on their own.
 * Turn on: cmd notification allow_listener local.agentos/local.agentos.Notifs
 */
public class Notifs extends NotificationListenerService {
    static final int MAX = 40;
    static final int HISTORY = 240; // per-app reads filter before limiting; a noisy app must not hide every other app
    static final long started = System.currentTimeMillis();
    static volatile boolean connected;
    private static final Map<String, Rec> recent = Collections.synchronizedMap(new LinkedHashMap<String, Rec>());
    /** OTPs/canaries that arrived by notification (P3: the agent can never type or send them), and their texts (copy check). */
    static final Set<String> secrets = Collections.synchronizedSet(new LinkedHashSet<String>()), seen = Collections.synchronizedSet(new LinkedHashSet<String>());
    /** Standing auto-reply grants: exact text -> expiry (ms). The user approved each once on a card (notifications.autoreply). */
    static final Map<String, Long> standing = new ConcurrentHashMap<>();
    private static final ExecutorService bg = Executors.newSingleThreadExecutor(); // Prompt Guard must not run on the UI thread

    static final class Rec {
        String key, pkg, conversationId, app = "", convo = "", sender = "", text = "", label = "";
        long when, observedAt;
        JSONArray messages = new JSONArray();
        boolean group, fromMe, msg, test; // test: our adb test chat, which stands in for a messaging app (private)
        volatile boolean removed;
        volatile double score = -1;
        Notification.Action reply, markRead;

        JSONObject json() throws Exception {
            return new JSONObject().put("key", key).put("pkg", pkg).put("app", app).put("conversation", convo).put("sender", sender)
                    .put("text", text).put("when", when).put("group", group).put("from_me", fromMe).put("message", msg)
                    .put("conversation_id", conversationId).put("observed_at", observedAt).put("trusted", false).put("source_pkg", pkg)
                    .put("can_reply", connected && !removed && reply != null).put("can_mark_read", connected && !removed && markRead != null)
                    .put("removed", removed).put("guard", new JSONObject().put("score", score).put("label", label));
        }
    }

    @Override public void onListenerConnected() {
        connected = true;
        NotificationFeed.connected();
        synchronized (recent) { for (Rec r : recent.values()) r.removed = true; }
        try { for (StatusBarNotification s : getActiveNotifications()) posted(s, false); } catch (Exception e) { Log.w("AgentOS", "notifs seed: " + e); }
    }

    @Override public void onListenerDisconnected() { connected = false; NotificationFeed.disconnected(); }

    @Override public void onNotificationPosted(StatusBarNotification s) { posted(s, true); }

    @Override public void onNotificationRemoved(StatusBarNotification s, android.service.notification.NotificationListenerService.RankingMap ranking, int reason) {
        Rec r = recent.get(s.getKey());
        if (r == null) return;
        r.removed = true;
        r.observedAt = System.currentTimeMillis();
        try {
            NotificationFeed.capture(r, true, reason);
            Events.emit("notification.removed", r.json().put("removal_reason", reason));
        } catch (Exception e) { Log.w("AgentOS", "notif removed: " + e); }
    }

    private void posted(StatusBarNotification s, boolean live) {
        Rec r = parse(this, s);
        if (r == null) return;
        Rec old;
        try {
            synchronized (recent) {
                old = recent.remove(r.key);
                recent.put(r.key, r);
                while (recent.size() > HISTORY) recent.remove(recent.keySet().iterator().next());
            }
            for (int i = 0; i < r.messages.length(); i++) {
                JSONObject m = r.messages.getJSONObject(i);
                java.util.regex.Matcher codes = Broker.SECRET.matcher(m.getString("text"));
                while (codes.find()) secrets.add(codes.group().replaceAll("[ -]", ""));
                if (!m.optBoolean("from_me")) seen.add(Broker.norm(m.getString("text")));
            }
            synchronized (seen) { while (seen.size() > 2000) seen.remove(seen.iterator().next()); }
            NotificationFeed.capture(r, false, 0); // visible immediately; Prompt Guard runs off the listener thread
        } catch (Exception e) { Log.w("AgentOS", "notif capture: " + e); return; }
        final Rec previous = old;
        bg.execute(() -> {
            try {
                boolean same = previous != null && previous.messages.toString().equals(r.messages.toString());
                if (same) { r.score = previous.score; r.label = previous.label; } else score(r);
                if (!live || same) return;
                Events.emit("notification.posted", r.json());
                if ("MALICIOUS".equals(r.label)) Broker.flagged(r); // audited + guard.flag event; no pop-up
            } catch (Throwable e) { Log.w("AgentOS", "notif: " + e); }
        });
    }

    /** Prompt Guard score, if the model is loaded (it takes ~5 s after a restart); else -1 and list() retries in the background. */
    private static void score(Rec r) {
        try {
            for (int i = 0; i < r.messages.length(); i++) {
                JSONObject m = r.messages.getJSONObject(i);
                JSONObject g = PromptGuard.classify(r.app + ": " + m.optString("sender") + ": " + m.optString("text"));
                if (g.optBoolean("ok") && g.optDouble("score") > r.score) { r.score = g.optDouble("score"); r.label = g.optString("label"); }
            }
        } catch (Throwable e) { Log.w("AgentOS", "notif guard: " + e); }
    }

    /** One notification -> Rec, or null for system noise, ongoing/summary rows and our own UI. */
    static Rec parse(Context c, StatusBarNotification s) {
        Notification n = s.getNotification();
        String pkg = s.getPackageName();
        if ((n.flags & (Notification.FLAG_GROUP_SUMMARY | Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0) return null;
        if (pkg.equals("local.agentos") ? !TEST_TAG.equals(s.getTag()) : pkg.equals("android") || pkg.equals("com.android.systemui") || pkg.equals("com.termux")) return null;
        Bundle x = n.extras;
        Rec r = new Rec();
        r.key = s.getKey(); r.pkg = pkg; r.test = TEST_TAG.equals(s.getTag()); r.when = n.when > 0 ? n.when : s.getPostTime();
        r.observedAt = System.currentTimeMillis();
        r.conversationId = pkg + ":" + s.getUserId() + ":" + (n.getShortcutId() == null ? "key:" + r.key : "shortcut:" + n.getShortcutId());
        r.app = label(c, pkg);
        r.sender = str(x.getCharSequence(Notification.EXTRA_TITLE));
        r.text = str(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (r.text.isEmpty()) r.text = str(x.getCharSequence(Notification.EXTRA_TEXT));
        r.convo = str(x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        r.group = x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION);
        if (r.convo.isEmpty()) r.convo = r.sender;
        r.reply = replyAction(n);
        if (n.actions != null) for (Notification.Action a : n.actions)
            if (a.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ && a.actionIntent != null) { r.markRead = a; break; }
        r.msg = x.containsKey(Notification.EXTRA_MESSAGES) || r.reply != null || Notification.CATEGORY_MESSAGE.equals(n.category);
        if (r.text.isEmpty() && r.sender.isEmpty()) return null;
        r.text = Guard.clip(r.text, 600);
        try {
            r.messages = NotificationFeed.messages(c, n, r);
            JSONObject last = r.messages.getJSONObject(r.messages.length() - 1);
            r.text = last.getString("text"); r.sender = last.getString("sender"); r.when = last.getLong("when"); r.fromMe = last.getBoolean("from_me");
        } catch (Exception e) { Log.w("AgentOS", "notif history: " + e); }
        return r;
    }

    /** The action that takes free-form text (the "Reply" box), preferring the one marked as a reply. */
    private static Notification.Action replyAction(Notification n) {
        List<Notification.Action> all = new ArrayList<>();
        if (n.actions != null) Collections.addAll(all, n.actions);
        all.addAll(new Notification.WearableExtender(n).getActions());
        Notification.Action best = null;
        for (Notification.Action a : all) {
            RemoteInput[] ri = a.getRemoteInputs();
            boolean free = false;
            for (int i = 0; ri != null && i < ri.length; i++) free |= ri[i].getAllowFreeFormInput();
            if (!free || a.actionIntent == null) continue;
            if (a.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_REPLY) return a;
            if (best == null) best = a;
        }
        return best;
    }

    /** notifications.list: newest first. */
    static JSONArray list(int n) throws Exception {
        return list(n, "", 0);
    }

    static JSONArray list(int n, String pkg, long since) throws Exception {
        List<Rec> all;
        synchronized (recent) { all = new ArrayList<>(recent.values()); }
        Collections.sort(all, (x, y) -> Long.compare(y.when, x.when));
        JSONArray a = new JSONArray();
        for (int i = 0; i < all.size() && a.length() < n; i++) {
            Rec r = all.get(i);
            if ((!pkg.isEmpty() && !pkg.equals(r.pkg)) || r.when < since) continue;
            if (r.score < 0) bg.execute(() -> { if (r.score < 0) score(r); }); // never make the caller wait for the model
            a.put(r.json());
        }
        return a;
    }

    static Rec get(String key) { return key == null ? null : recent.get(key); }

    /** Fills the app's own Reply box and fires its reply intent: what the shade's inline reply does. Broker-only (after approval). */
    static boolean reply(Context c, Rec r, String text) throws Exception {
        requireActive(r);
        if (r.reply == null) throw new IllegalArgumentException("this notification has no reply action; open the app instead");
        RemoteInput[] ri = r.reply.getRemoteInputs();
        Bundle b = new Bundle();
        for (RemoteInput x : ri) if (x.getAllowFreeFormInput()) b.putCharSequence(x.getResultKey(), text);
        Intent i = new Intent();
        RemoteInput.addResultsToIntent(ri, i, b);
        RemoteInput.setResultsSource(i, RemoteInput.SOURCE_FREE_FORM_INPUT);
        r.reply.actionIntent.send(c, 0, i);
        return true;
    }

    static void requireActive(Rec r) {
        if (!connected || r.removed || get(r.key) != r) throw new IllegalArgumentException("notification changed, removed or listener disconnected; read notifications again");
    }

    static boolean markRead(Context c, Rec r) throws Exception {
        requireActive(r);
        if (r.markRead == null) throw new IllegalArgumentException("app offers no mark-as-read action");
        r.markRead.actionIntent.send(c, 0, new Intent());
        return true;
    }

    static boolean leaks(String text) {
        String flat = text.replaceAll("[ -]", "");
        synchronized (secrets) { for (String s : secrets) if (flat.contains(s)) return true; }
        return false;
    }

    /** Is there a live standing auto-reply grant for exactly this text? */
    static boolean standing(String text) {
        Long until = standing.get(text);
        return until != null && until > System.currentTimeMillis();
    }

    static String str(CharSequence s) { return s == null ? "" : s.toString().replaceAll("[\u200e\u200f\u202a-\u202e\u2066-\u2069]", "").trim(); }

    private static String label(Context c, String pkg) {
        try { return c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception e) { return pkg; }
    }

    // ------------------------------------------- test chat (adb only, via the local.agentos.UI demo hook)

    static final String TEST_TAG = "agentos-test", TEST_REPLY = "local.agentos.TEST_REPLY";
    private static final List<String[]> chat = new ArrayList<>();
    private static boolean registered;

    /** Posts a chat-style notification with a real RemoteInput Reply action, like a messaging app would. */
    static synchronized void postTest(Context c, String from, String text) {
        if (!registered) { // the reply comes back as our own PendingIntent, so a non-exported receiver hears it
            c.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context cx, Intent i) {
                    Bundle b = RemoteInput.getResultsFromIntent(i);
                    String said = b == null ? "" : String.valueOf(b.getCharSequence("reply"));
                    Log.i("AgentOS", "test chat reply: " + said);
                    postTest(cx, null, said); // like a real app: the thread now shows our reply
                }
            }, new IntentFilter(TEST_REPLY), Context.RECEIVER_NOT_EXPORTED);
            registered = true;
        }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("test-chat", "Test chat", NotificationManager.IMPORTANCE_HIGH));
        if (from != null && !from.equals(chat.isEmpty() ? null : chat.get(0)[0])) chat.clear();
        chat.add(new String[]{from == null ? (chat.isEmpty() ? "Alice" : chat.get(0)[0]) : from, text, from == null ? "me" : ""});
        Person me = new Person.Builder().setName("Me").build();
        Notification.MessagingStyle st = new Notification.MessagingStyle(me);
        for (String[] m : chat) st.addMessage(m[1], System.currentTimeMillis(), m[2].equals("me") ? null : new Person.Builder().setName(m[0]).build());
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, new Intent(TEST_REPLY).setPackage(c.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        Notification.Action reply = new Notification.Action.Builder(null, "Reply", pi)
                .addRemoteInput(new RemoteInput.Builder("reply").setLabel("Reply").build())
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY).build();
        nm.notify(TEST_TAG, 1, new Notification.Builder(c, "test-chat").setSmallIcon(android.R.drawable.sym_action_chat)
                .setStyle(st).addAction(reply).setCategory(Notification.CATEGORY_MESSAGE).setOnlyAlertOnce(from == null).build());
    }
}
