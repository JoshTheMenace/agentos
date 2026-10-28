package local.agentos;

import android.app.Notification;
import android.app.Person;
import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded, process-local history of supported notification surfaces, never an app's full inbox. */
final class NotificationFeed {
    static final int CONVERSATIONS = 120, MESSAGES = 50, UPDATES = 240;
    private static final Map<String, Thread> threads = new LinkedHashMap<>();
    private static final List<JSONObject> updates = new ArrayList<>();
    private static long cursor, connectedAt, lastDisconnect;

    private static final class Thread {
        Notifs.Rec latest;
        long updated;
        final Map<String, JSONObject> messages = new LinkedHashMap<>();
        final Map<String, Notifs.Rec> active = new LinkedHashMap<>();
    }

    static JSONArray messages(Context c, Notification n, Notifs.Rec r) throws Exception {
        JSONArray out = new JSONArray();
        Notification.Style recovered = null;
        try { recovered = Notification.Builder.recoverBuilder(c, n).getStyle(); }
        catch (RuntimeException ignored) { /* malformed/custom style: retain the standard preview below */ }
        if (recovered instanceof Notification.MessagingStyle) {
            Notification.MessagingStyle style = (Notification.MessagingStyle) recovered;
            List<Notification.MessagingStyle.Message> all = new ArrayList<>(style.getHistoricMessages());
            all.addAll(style.getMessages());
            for (Notification.MessagingStyle.Message m : all) {
                Person p = m.getSenderPerson(), me = style.getUser();
                boolean mine = p == null || samePerson(p, me);
                out.put(message(r, m.getText() == null ? "[attachment]" : m.getText().toString(),
                        mine ? "me" : String.valueOf(p.getName()), m.getTimestamp(), mine));
            }
        }
        if (out.length() == 0) out.put(message(r, r.text, r.sender, r.when, r.fromMe));
        return out;
    }

    private static boolean samePerson(Person a, Person b) {
        if (b == null) return false;
        if (a.getKey() != null && b.getKey() != null) return a.getKey().equals(b.getKey());
        if (a.getUri() != null && b.getUri() != null) return a.getUri().equals(b.getUri());
        return a.getName() != null && a.getName().toString().equals(String.valueOf(b.getName()));
    }

    private static JSONObject message(Notifs.Rec r, String text, String sender, long when, boolean mine) throws Exception {
        return new JSONObject().put("text", Guard.clip(Notifs.str(text), 600)).put("sender", Notifs.str(sender)).put("when", when)
                .put("from_me", mine).put("source_pkg", r.pkg).put("source", "notification:" + r.pkg).put("trusted", false);
    }

    static synchronized void connected() {
        connectedAt = System.currentTimeMillis();
        for (Thread t : threads.values()) t.active.clear();
    }

    static synchronized void disconnected() { lastDisconnect = System.currentTimeMillis(); }

    static synchronized void capture(Notifs.Rec r, boolean removed, int reason) throws Exception {
        if (!r.msg) return;
        Thread t = threads.remove(r.conversationId);
        if (t == null) t = new Thread();
        if (!removed || t.latest == null) t.latest = r;
        t.updated = cursor = Math.max(System.currentTimeMillis(), cursor + 1);
        JSONArray added = new JSONArray();
        for (int i = 0; !removed && i < r.messages.length(); i++) {
            JSONObject m = r.messages.getJSONObject(i);
            String id = new JSONArray().put(m.get("when")).put(m.get("sender")).put(m.get("from_me")).put(m.get("text")).toString();
            if (t.messages.containsKey(id)) continue;
            JSONObject copy = new JSONObject(m.toString()).put("observed_at", cursor);
            t.messages.put(id, copy);
            added.put(copy);
        }
        while (t.messages.size() > MESSAGES) t.messages.remove(t.messages.entrySet().stream()
                .min((a, b) -> Long.compare(a.getValue().optLong("when"), b.getValue().optLong("when"))).get().getKey());
        if (removed) t.active.remove(r.key); else t.active.put(r.key, r);
        threads.put(r.conversationId, t);
        while (threads.size() > CONVERSATIONS) threads.remove(threads.keySet().iterator().next());
        updates.add(summary(t).put("event", removed ? "removed" : "posted").put("key", r.key)
                .put("removal_reason", reason).put("messages", added));
        while (updates.size() > UPDATES) updates.remove(0);
    }

    private static JSONObject summary(Thread t) throws Exception {
        Notifs.Rec r = t.latest, action = null;
        for (Notifs.Rec a : t.active.values()) if (!a.removed && Notifs.get(a.key) == a && (a.reply != null || a.markRead != null)) action = a;
        return new JSONObject().put("conversation_id", r.conversationId).put("pkg", r.pkg).put("source_pkg", r.pkg)
                .put("app", r.app).put("conversation", r.convo).put("sender", r.sender).put("text", r.text).put("when", r.when)
                .put("from_me", r.fromMe).put("updated_at", t.updated).put("active", Notifs.connected && !t.active.isEmpty())
                .put("key", action == null ? r.key : action.key).put("can_reply", Notifs.connected && action != null && action.reply != null)
                .put("can_mark_read", Notifs.connected && action != null && action.markRead != null).put("message_count", t.messages.size())
                .put("source", "notification:" + r.pkg).put("trusted", false);
    }

    static synchronized JSONObject read(String method, JSONObject p) throws Exception {
        String pkg = p.optString("pkg");
        int n = Math.max(1, Math.min(50, p.optInt("n", 20)));
        JSONArray items = new JSONArray();
        if (method.equals("notifications.conversation")) {
            Thread t = threads.get(p.getString("conversation_id"));
            if (t == null) throw new IllegalArgumentException("conversation expired or unknown; list conversations again");
            List<JSONObject> ms = new ArrayList<>(t.messages.values());
            Collections.sort(ms, (a, b) -> Long.compare(b.optLong("when"), a.optLong("when")));
            for (JSONObject m : ms) if (items.length() < n) items.put(new JSONObject(m.toString()));
            return envelope().put("conversation", summary(t)).put("items", items);
        }
        long since = p.optLong("since", 0);
        if (method.equals("notifications.changes")) {
            since = p.getLong("since");
            // Oldest first and a cursor for the last returned event: bounded polling never skips a page.
            long next = since;
            for (JSONObject u : updates) if (u.getLong("updated_at") > since && (pkg.isEmpty() || pkg.equals(u.optString("pkg")))) {
                if (items.length() == n) break;
                items.put(new JSONObject(u.toString())); next = u.getLong("updated_at");
            }
            return envelope().put("items", items).put("next_since", items.length() < n ? Math.max(since, cursor) : next)
                    .put("gap", since < Notifs.started || since < lastDisconnect || !updates.isEmpty() && since < updates.get(0).getLong("updated_at") && updates.size() == UPDATES);
        }
        List<Thread> all = new ArrayList<>(threads.values());
        Collections.reverse(all);
        for (Thread t : all) if (items.length() < n && t.updated > since && (pkg.isEmpty() || pkg.equals(t.latest.pkg))) items.put(summary(t));
        return envelope().put("items", items);
    }

    private static JSONObject envelope() throws Exception {
        return new JSONObject().put("ok", true).put("listener", Notifs.connected).put("fresh_as_of", Notifs.connected ? System.currentTimeMillis() : JSONObject.NULL)
                .put("last_disconnect_at", lastDisconnect)
                .put("connected_at", connectedAt).put("history_since", Notifs.started).put("cursor", cursor)
                .put("coverage", "Delivered notifications only; gaps while disconnected; history resets on broker restart. Empty results do not prove an empty inbox.")
                .put("capacity", new JSONObject().put("conversations", CONVERSATIONS).put("messages_per_conversation", MESSAGES).put("updates", UPDATES));
    }
}
