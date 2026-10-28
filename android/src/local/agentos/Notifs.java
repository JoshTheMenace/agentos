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
import android.os.Parcelable;
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
        String key, pkg, app = "", convo = "", sender = "", text = "", label = "";
        long when;
        boolean group, fromMe, msg, removed, test; // test: our adb test chat, which stands in for a messaging app (private)
        double score = -1;
        Notification.Action reply;

        JSONObject json() throws Exception {
            return new JSONObject().put("key", key).put("pkg", pkg).put("app", app).put("conversation", convo).put("sender", sender)
                    .put("text", text).put("when", when).put("group", group).put("from_me", fromMe).put("message", msg)
                    .put("can_reply", reply != null).put("removed", removed).put("guard", new JSONObject().put("score", score).put("label", label));
        }
    }

    @Override public void onListenerConnected() {
        connected = true;
        try { for (StatusBarNotification s : getActiveNotifications()) posted(s, false); } catch (Exception e) { Log.w("AgentOS", "notifs seed: " + e); }
    }

    @Override public void onListenerDisconnected() { connected = false; }

    @Override public void onNotificationPosted(StatusBarNotification s) { posted(s, true); }

    @Override public void onNotificationRemoved(StatusBarNotification s) {
        Rec r = recent.get(s.getKey());
        if (r != null) r.removed = true; // kept: "reply back" still means this thread; the reply action usually still works
    }

    private void posted(StatusBarNotification s, boolean live) {
        Rec r = parse(this, s);
        if (r == null) return;
        bg.execute(() -> {
            try {
                Rec old = recent.get(r.key);
                boolean same = old != null && old.text.equals(r.text) && old.sender.equals(r.sender);
                if (same) { r.score = old.score; r.label = old.label; } else score(r);
                if (seen.size() > 2000) seen.clear(); // bounded; secrets stay (few, and P3 must not forget them)
                java.util.regex.Matcher m = Broker.SECRET.matcher(r.text);
                while (m.find()) secrets.add(m.group().replaceAll("[ -]", ""));
                if (!r.fromMe) seen.add(Broker.norm(r.text));
                synchronized (recent) {
                    recent.remove(r.key);
                    recent.put(r.key, r); // newest last
                    while (recent.size() > HISTORY) recent.remove(recent.keySet().iterator().next());
                }
                if (!live || same) return;
                Events.emit("notification.posted", r.json());
                if ("MALICIOUS".equals(r.label)) Broker.flagged(r); // audited + guard.flag event; no pop-up
            } catch (Throwable e) { Log.w("AgentOS", "notif: " + e); }
        });
    }

    /** Prompt Guard score, if the model is loaded (it takes ~5 s after a restart); else -1 and list() retries in the background. */
    private static void score(Rec r) {
        try {
            JSONObject g = PromptGuard.classify(r.app + ": " + r.sender + ": " + r.text);
            if (g.optBoolean("ok")) { r.score = g.optDouble("score"); r.label = g.optString("label"); }
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
        r.app = label(c, pkg);
        r.sender = str(x.getCharSequence(Notification.EXTRA_TITLE));
        r.text = str(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (r.text.isEmpty()) r.text = str(x.getCharSequence(Notification.EXTRA_TEXT));
        r.convo = str(x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        r.group = x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION);
        Parcelable[] msgs = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        String title = r.sender, other = "";
        Person me = x.getParcelable(Notification.EXTRA_MESSAGING_PERSON);
        for (int i = msgs == null ? -1 : msgs.length - 1; i >= 0; i--) { // MessagingStyle: newest message first
            if (!(msgs[i] instanceof Bundle)) continue;
            Bundle m = (Bundle) msgs[i];
            Person p = m.getParcelable("sender_person");
            CharSequence who = p != null ? p.getName() : m.getCharSequence("sender");
            boolean mine = who == null || me != null && me.getName() != null && me.getName().toString().equals(who.toString());
            if (!r.msg) { r.text = str(m.getCharSequence("text")); r.fromMe = mine; r.sender = mine ? "me" : str(who); r.msg = true; }
            if (!mine) { other = str(who); break; } // the thread's other person, even when we spoke last
        }
        if (r.convo.isEmpty()) r.convo = !title.isEmpty() ? title : other;
        r.reply = replyAction(n);
        r.msg |= r.reply != null || Notification.CATEGORY_MESSAGE.equals(n.category);
        if (r.text.isEmpty() && r.sender.isEmpty()) return null;
        r.text = Guard.clip(r.text, 600);
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
        if (r.reply == null) throw new IllegalArgumentException("this notification has no reply action; open the app instead");
        RemoteInput[] ri = r.reply.getRemoteInputs();
        Bundle b = new Bundle();
        for (RemoteInput x : ri) b.putCharSequence(x.getResultKey(), text);
        Intent i = new Intent();
        RemoteInput.addResultsToIntent(ri, i, b);
        RemoteInput.setResultsSource(i, RemoteInput.SOURCE_FREE_FORM_INPUT);
        r.reply.actionIntent.send(c, 0, i);
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

    private static String str(CharSequence s) { return s == null ? "" : s.toString().replaceAll("[\u200e\u200f\u202a-\u202e\u2066-\u2069]", "").trim(); }

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
