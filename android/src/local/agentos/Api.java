package local.agentos;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Instances;
import android.provider.CalendarContract.Reminders;
import android.provider.CallLog;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Data;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.view.KeyEvent;
import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The API hands: Android's own services instead of taps (android/API-ACTIONS.md). Like Actions: no policy here.
 * Broker.act() gates every call (lease scope, tier, card, Rule of Two, audit). Reads return {items:[{text, ...}]}, where
 * text is other people's words (untrusted): the broker scores it with Prompt Guard and taints the task.
 */
final class Api {
    private Api() {}

    /** scope: the lease name. tier: the base tier (tier() may raise it). priv: other people's data. reads: returns their text. */
    static final class Spec {
        final String scope, step;
        final int tier;
        final boolean priv, reads;
        Spec(String scope, int tier, boolean priv, boolean reads, String step) { this.scope = scope; this.tier = tier; this.priv = priv; this.reads = reads; this.step = step; }
    }

    static final Map<String, Spec> SPECS = new HashMap<>();
    static {
        SPECS.put("calendar.add", new Spec("calendar", 2, false, false, "Adding to Calendar"));
        SPECS.put("calendar.find", new Spec("calendar", 0, false, true, "Checking Calendar"));
        SPECS.put("calendar.update", new Spec("calendar", 2, false, false, "Updating Calendar"));
        SPECS.put("calendar.delete", new Spec("calendar", 3, false, false, "Deleting an event"));
        SPECS.put("contacts.find", new Spec("contacts", 0, true, true, "Looking up a contact"));
        SPECS.put("sms.send", new Spec("sms", 3, true, false, "Texting"));
        SPECS.put("sms.list", new Spec("sms", 0, true, true, "Reading texts"));
        SPECS.put("calls.list", new Spec("calls", 0, true, true, "Checking calls"));
        SPECS.put("call.place", new Spec("calls", 3, true, false, "Calling"));
        SPECS.put("alarms.list", new Spec("alarms", 0, false, false, "Checking alarms"));
        SPECS.put("alarms.delete", new Spec("alarms", 3, false, false, "Deleting an alarm"));
        SPECS.put("device.set", new Spec("device", 1, false, false, "Changing settings"));
        SPECS.put("device.status", new Spec("device", 0, false, false, "Checking the phone"));
        SPECS.put("media.control", new Spec("media", 1, false, false, "Media"));
    }

    /** An invite emails other people (outward); turning an alarm off (not deleting it) is a local change. */
    static int tier(String m, JSONObject p) {
        if (m.equals("calendar.add") && p.optJSONArray("attendees") != null && p.optJSONArray("attendees").length() > 0) return 3;
        if (m.equals("alarms.delete") && p.optBoolean("off")) return 2;
        return SPECS.get(m).tier;
    }

    private static final String[] SETTINGS = {"dnd", "ringer", "volume", "ring_volume", "alarm_volume", "torch", "brightness", "wifi", "bluetooth", "airplane"};

    /** {target, text} for the card and the audit line. Resolves a contact name to a number first (stored in p), so the card shows it. */
    static String[] describe(Context c, String m, JSONObject p) throws Exception {
        switch (m) {
            case "calendar.add": {
                JSONArray a = p.optJSONArray("attendees");
                String what = "“" + p.getString("title") + "” " + p.getString("start").replace('T', ' ');
                return new String[]{a != null && a.length() > 0 ? "Invite " + a.join(", ").replace("\"", "") + " to " + what : "Add " + what, p.getString("title")};
            }
            case "calendar.update": case "calendar.delete": {
                JSONObject e = event(c.getContentResolver(), p.getLong("id"));
                if (e == null) throw new IllegalArgumentException("no event with id " + p.getLong("id") + " (call find_events)");
                return new String[]{(m.endsWith("delete") ? "Delete " : "Change ") + "“" + e.getString("title") + "” " + e.getString("start"), p.optString("title")};
            }
            case "sms.send": case "call.place":
                resolve(c, p);
                return new String[]{(m.equals("sms.send") ? "Text " : "Call ") + p.getString("name") + " · " + p.getString("number"), p.optString("text")};
            case "alarms.delete": {
                JSONObject a = null;
                for (Object o : iter(alarms(c))) if (((JSONObject) o).getLong("id") == p.getLong("id")) a = (JSONObject) o;
                if (a == null) throw new IllegalArgumentException("no alarm with id " + p.getLong("id") + " (call list_alarms)");
                return new String[]{(p.optBoolean("off") ? "Turn off alarm " : "Delete alarm ") + a.getString("time") + " " + a.getString("days") + (a.getString("label").isEmpty() ? "" : " “" + a.getString("label") + "”"), ""};
            }
            case "device.set": {
                StringBuilder b = new StringBuilder();
                for (String k : SETTINGS) if (p.has(k)) b.append(b.length() > 0 ? ", " : "").append(k).append(' ').append(p.get(k));
                if (b.length() == 0) throw new IllegalArgumentException("nothing to change: set dnd, ringer, volume, torch, brightness, wifi, bluetooth or airplane");
                return new String[]{b.toString(), ""};
            }
            case "media.control": return new String[]{p.optString("action", "status"), ""};
            default: return new String[]{m + " " + p.optString("name", p.optString("query", p.optString("from", ""))).trim(), ""};
        }
    }

    static JSONObject run(AgentA11y s, String m, JSONObject p) throws Exception {
        ContentResolver r = s.getContentResolver();
        switch (m) {
            case "calendar.add": return calendarAdd(r, p);
            case "calendar.find": return calendarFind(r, p);
            case "calendar.update": return calendarUpdate(r, p);
            case "calendar.delete": {
                long id = p.getLong("id");
                boolean gone = r.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null) > 0 && event(r, id) == null;
                return ok(gone).put("deleted", gone);
            }
            case "contacts.find": return ok(true).put("items", contacts(r, p.getString("name")));
            case "sms.send": return sms(s, p.getString("number"), p.getString("text")).put("to", p.getString("name"));
            case "sms.list": return smsList(r, p);
            case "calls.list": return calls(r, Math.max(1, Math.min(30, p.optInt("n", 10))));
            case "call.place":
                if (!simReady(s)) return fail("this phone has no working SIM card");
                s.startActivity(new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", p.getString("number"), null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return ok(true).put("calling", p.getString("name"));
            case "alarms.list": return ok(true).put("items", alarms(s));
            case "alarms.delete": return alarmDelete(s, p.getLong("id"), p.optBoolean("off"));
            case "device.set": return deviceSet(s, p);
            case "device.status": return ok(true).put("status", status(s, p.optBoolean("screen_time")));
            case "media.control": return media(s, p.optString("action", "status"));
            default: throw new IllegalArgumentException("unknown API method " + m);
        }
    }

    // ------------------------------------------------------------------ calendar

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm", Locale.US);
    private static long millis(String local) { return LocalDateTime.parse(local).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
    private static String when(long ms, boolean allDay) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), allDay ? ZoneOffset.UTC : ZoneId.systemDefault()).format(allDay ? DateTimeFormatter.ofPattern("EEE yyyy-MM-dd", Locale.US) : WHEN);
    }

    /** The primary writable calendar (the Google account's own), else the first writable one. */
    private static long calendarId(ContentResolver r) {
        try (Cursor c = r.query(Calendars.CONTENT_URI, new String[]{Calendars._ID}, Calendars.CALENDAR_ACCESS_LEVEL + ">=700 AND " + Calendars.VISIBLE + "=1",
                null, Calendars.IS_PRIMARY + " DESC, " + Calendars._ID)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        }
        throw new IllegalStateException("no writable calendar on this phone");
    }

    private static JSONObject calendarAdd(ContentResolver r, JSONObject p) throws Exception {
        long t = millis(p.getString("start"));
        ContentValues v = new ContentValues();
        v.put(Events.CALENDAR_ID, calendarId(r));
        v.put(Events.TITLE, p.getString("title"));
        v.put(Events.DTSTART, t);
        v.put(Events.DTEND, t + Math.max(5, Math.min(1440, p.optInt("minutes", 60))) * 60_000L);
        v.put(Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        if (p.has("location")) v.put(Events.EVENT_LOCATION, p.getString("location"));
        long id = ContentUris.parseId(r.insert(Events.CONTENT_URI, v));
        ContentValues rem = new ContentValues(); // a notification 10 min before, like the Calendar app's default
        rem.put(Reminders.EVENT_ID, id); rem.put(Reminders.MINUTES, 10); rem.put(Reminders.METHOD, Reminders.METHOD_ALERT);
        r.insert(Reminders.CONTENT_URI, rem);
        JSONArray a = p.optJSONArray("attendees");
        for (int i = 0; a != null && i < a.length(); i++) {
            ContentValues at = new ContentValues();
            at.put(Attendees.EVENT_ID, id); at.put(Attendees.ATTENDEE_EMAIL, a.getString(i));
            at.put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE); at.put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED);
            r.insert(Attendees.CONTENT_URI, at);
        }
        JSONObject e = event(r, id); // read it back: the answer is what Calendar now holds, not what we meant to write
        return e == null ? fail("Calendar did not keep the event") : ok(true).put("event", e);
    }

    private static JSONObject calendarUpdate(ContentResolver r, JSONObject p) throws Exception {
        long id = p.getLong("id");
        ContentValues v = new ContentValues();
        if (p.has("title")) v.put(Events.TITLE, p.getString("title"));
        if (p.has("location")) v.put(Events.EVENT_LOCATION, p.getString("location"));
        if (p.has("start") || p.has("minutes")) {
            JSONObject e = event(r, id);
            long t = p.has("start") ? millis(p.getString("start")) : e.getLong("start_ms");
            v.put(Events.DTSTART, t);
            if (e.has("end_ms")) v.put(Events.DTEND, t + (p.has("minutes") ? p.getInt("minutes") * 60_000L : e.getLong("end_ms") - e.getLong("start_ms")));
        }
        if (v.size() == 0) return fail("nothing to change: give title, start, minutes or location");
        r.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), v, null, null);
        return ok(true).put("event", event(r, id));
    }

    /** One event as the model sees it, or null if it doesn't exist (or is deleted and waiting for sync). */
    private static JSONObject event(ContentResolver r, long id) throws Exception {
        try (Cursor c = r.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), new String[]{Events.TITLE, Events.DTSTART, Events.DTEND, Events.ALL_DAY,
                Events.EVENT_LOCATION, Events.CALENDAR_DISPLAY_NAME, Events.DELETED}, null, null, null)) {
            if (c == null || !c.moveToFirst() || c.getInt(6) == 1) return null;
            boolean allDay = c.getInt(3) == 1;
            JSONObject e = new JSONObject().put("id", id).put("title", c.getString(0)).put("start", when(c.getLong(1), allDay)).put("start_ms", c.getLong(1))
                    .put("calendar", c.getString(5));
            if (!c.isNull(2)) e.put("end", when(c.getLong(2), allDay)).put("end_ms", c.getLong(2));
            if (c.getString(4) != null && !c.getString(4).isEmpty()) e.put("location", c.getString(4));
            return allDay ? e.put("all_day", true) : e;
        }
    }

    private static JSONObject calendarFind(ContentResolver r, JSONObject p) throws Exception {
        long from = p.has("from") ? millis(p.getString("from")) : System.currentTimeMillis(), to = p.has("to") ? millis(p.getString("to")) : from + 7 * 86_400_000L;
        Uri.Builder b = Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, from); ContentUris.appendId(b, to);
        String q = p.optString("query").trim();
        JSONArray out = new JSONArray();
        try (Cursor c = r.query(b.build(), new String[]{Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.EVENT_LOCATION,
                Instances.CALENDAR_DISPLAY_NAME}, Instances.VISIBLE + "=1" + (q.isEmpty() ? "" : " AND " + Instances.TITLE + " LIKE ?"),
                q.isEmpty() ? null : new String[]{"%" + q + "%"}, Instances.BEGIN)) {
            while (c != null && c.moveToNext() && out.length() < 25) {
                boolean allDay = c.getInt(4) == 1;
                out.put(item("calendar", c.getString(1)).put("id", c.getLong(0)).put("start", when(c.getLong(2), allDay)).put("end", when(c.getLong(3), allDay))
                        .put("all_day", allDay).put("location", c.getString(5) == null ? "" : c.getString(5)).put("calendar", c.getString(6)));
            }
        }
        return ok(true).put("items", out);
    }

    // ------------------------------------------------------------------ contacts, SMS, calls

    /** Contacts whose name or nickname contains `name`; exact matches (full name, first name, nickname) first. At most 5. */
    static JSONArray contacts(ContentResolver r, String name) throws Exception {
        String n = name.trim().toLowerCase(Locale.ROOT), like = "%" + name.trim() + "%";
        Map<Long, JSONObject> found = new LinkedHashMap<>();
        try (Cursor c = r.query(Data.CONTENT_URI, new String[]{Data.CONTACT_ID, Data.DISPLAY_NAME, Data.MIMETYPE, Data.DATA1},
                Data.DISPLAY_NAME + " LIKE ? OR (" + Data.MIMETYPE + "=? AND " + Data.DATA1 + " LIKE ?)", new String[]{like, Nickname.CONTENT_ITEM_TYPE, like}, null)) {
            while (c != null && c.moveToNext()) {
                JSONObject x = found.get(c.getLong(0));
                if (x == null) found.put(c.getLong(0), x = item("contacts", c.getString(1)).put("phones", new JSONArray()).put("emails", new JSONArray()));
                String dn = String.valueOf(c.getString(1)).toLowerCase(Locale.ROOT);
                if (dn.equals(n) || dn.startsWith(n + " ") || Nickname.CONTENT_ITEM_TYPE.equals(c.getString(2)) && n.equalsIgnoreCase(c.getString(3))) x.put("exact", true);
            }
        }
        List<Long> ids = new ArrayList<>(found.keySet());
        ids.sort((a, b) -> Boolean.compare(found.get(b).optBoolean("exact"), found.get(a).optBoolean("exact")));
        JSONArray out = new JSONArray();
        for (long id : ids.subList(0, Math.min(5, ids.size()))) {
            JSONObject x = found.get(id);
            try (Cursor c = r.query(Data.CONTENT_URI, new String[]{Data.MIMETYPE, Data.DATA1, Data.DATA2}, Data.CONTACT_ID + "=?", new String[]{String.valueOf(id)}, null)) {
                while (c != null && c.moveToNext()) {
                    if (Phone.CONTENT_ITEM_TYPE.equals(c.getString(0))) x.getJSONArray("phones").put(new JSONObject().put("number", c.getString(1)).put("mobile", c.getInt(2) == Phone.TYPE_MOBILE));
                    else if (Email.CONTENT_ITEM_TYPE.equals(c.getString(0))) x.getJSONArray("emails").put(c.getString(1));
                    else if (Nickname.CONTENT_ITEM_TYPE.equals(c.getString(0))) x.put("nickname", c.getString(1));
                }
            }
            out.put(x);
        }
        return out;
    }

    /** sms.send / call.place {to}: a number stays a number; a name must match exactly one contact with a phone (mobile preferred). */
    private static void resolve(Context c, JSONObject p) throws Exception {
        String to = p.getString("to").trim();
        if (to.matches("\\+?[\\d\\s().-]{3,}")) { p.put("number", to.replaceAll("[^+\\d]", "")).put("name", to); return; }
        List<JSONObject> withPhone = new ArrayList<>(), exact = new ArrayList<>();
        for (Object o : iter(contacts(c.getContentResolver(), to))) {
            JSONObject x = (JSONObject) o;
            if (x.getJSONArray("phones").length() == 0) continue;
            withPhone.add(x);
            if (x.optBoolean("exact")) exact.add(x);
        }
        List<JSONObject> pick = exact.size() == 1 ? exact : withPhone;
        if (pick.size() != 1) {
            StringBuilder names = new StringBuilder();
            for (JSONObject x : withPhone) names.append(names.length() > 0 ? ", " : "").append(x.getString("text"));
            throw new IllegalArgumentException(pick.isEmpty() ? "no contact with a phone number matches \"" + to + "\"" : "several contacts match \"" + to + "\": " + names + ". Use the full name or the number");
        }
        JSONArray ph = pick.get(0).getJSONArray("phones");
        String number = ph.getJSONObject(0).getString("number");
        for (int i = 0; i < ph.length(); i++) if (ph.getJSONObject(i).getBoolean("mobile")) { number = ph.getJSONObject(i).getString("number"); break; }
        p.put("number", number.replaceAll("[^+\\d]", "")).put("name", pick.get(0).getString("text"));
    }

    private static boolean simReady(Context c) { return c.getSystemService(TelephonyManager.class).getSimState() == TelephonyManager.SIM_STATE_READY; }

    /** Sends (multipart if long) and waits up to 20 s for the radio's "sent" result. The system files it in Messages' sent box. */
    private static JSONObject sms(Context c, String number, String text) throws Exception {
        if (!simReady(c)) return fail("this phone has no working SIM card, so nothing was sent");
        SmsManager sm = c.getSystemService(SmsManager.class);
        ArrayList<String> parts = sm.divideMessage(text);
        String action = "local.agentos.SMS_SENT." + Approvals.hex(4);
        CountDownLatch done = new CountDownLatch(parts.size());
        int[] code = {Activity.RESULT_OK};
        BroadcastReceiver br = new BroadcastReceiver() {
            @Override public void onReceive(Context x, Intent i) { if (getResultCode() != Activity.RESULT_OK) code[0] = getResultCode(); done.countDown(); }
        };
        c.registerReceiver(br, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
        try {
            ArrayList<PendingIntent> sent = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) sent.add(PendingIntent.getBroadcast(c, i, new Intent(action).setPackage(c.getPackageName()), PendingIntent.FLAG_IMMUTABLE));
            sm.sendMultipartTextMessage(number, null, parts, sent, null);
            boolean fin = done.await(20, TimeUnit.SECONDS);
            return fin && code[0] == Activity.RESULT_OK ? ok(true).put("sent", true) : fail(fin ? "the radio refused it (code " + code[0] + ")" : "no result from the radio in 20 s");
        } finally { c.unregisterReceiver(br); }
    }

    private static JSONObject smsList(ContentResolver r, JSONObject p) throws Exception {
        List<String> nums = new ArrayList<>();
        String from = p.optString("from").trim();
        if (!from.isEmpty()) {
            if (from.matches("\\+?[\\d\\s().-]{3,}")) nums.add(from.replaceAll("\\D", ""));
            else for (Object o : iter(contacts(r, from))) for (Object ph : iter(((JSONObject) o).getJSONArray("phones"))) nums.add(((JSONObject) ph).getString("number").replaceAll("\\D", ""));
            if (nums.isEmpty()) return fail("no contact with a phone number matches \"" + from + "\"");
        }
        JSONArray out = new JSONArray();
        Map<String, String> names = new HashMap<>();
        int n = Math.max(1, Math.min(30, p.optInt("n", 10)));
        try (Cursor c = r.query(Telephony.Sms.CONTENT_URI, new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ},
                null, null, Telephony.Sms.DATE + " DESC")) {
            while (c != null && c.moveToNext() && out.length() < n) {
                String addr = String.valueOf(c.getString(0)), d = addr.replaceAll("\\D", "");
                if (!nums.isEmpty() && nums.stream().noneMatch(x -> tail(x).equals(tail(d)))) continue;
                if (!names.containsKey(addr)) names.put(addr, nameOf(r, addr));
                out.put(item("sms", c.getString(1)).put("from", names.get(addr)).put("sent_by_me", c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT)
                        .put("when", when(c.getLong(2), false)).put("read", c.getInt(4) == 1));
            }
        }
        return ok(true).put("items", out);
    }

    private static String tail(String digits) { return digits.length() > 7 ? digits.substring(digits.length() - 7) : digits; }

    /** "Sam (+18015551234)" if the number is a contact, else the number. */
    private static String nameOf(ContentResolver r, String number) {
        try (Cursor c = r.query(Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                new String[]{android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            return c != null && c.moveToFirst() ? c.getString(0) + " (" + number + ")" : number;
        } catch (Exception e) { return number; }
    }

    private static JSONObject calls(ContentResolver r, int n) throws Exception {
        JSONArray out = new JSONArray();
        String[] kinds = {"", "incoming", "outgoing", "missed", "voicemail", "rejected", "blocked"};
        try (Cursor c = r.query(CallLog.Calls.CONTENT_URI, new String[]{CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION},
                null, null, CallLog.Calls.DATE + " DESC")) {
            while (c != null && c.moveToNext() && out.length() < n)
                out.put(item("calls", c.getString(1) == null ? c.getString(0) : c.getString(1) + " (" + c.getString(0) + ")")
                        .put("kind", c.getInt(2) < kinds.length ? kinds[c.getInt(2)] : "other").put("when", when(c.getLong(3), false)).put("seconds", c.getLong(4)));
        }
        return ok(true).put("items", out);
    }

    // ------------------------------------------------------------------ alarms (Google Clock has no API: root + its own database)

    private static final String CLOCK = "com.google.android.deskclock", CLOCK_DB = "/data/user_de/0/" + CLOCK + "/databases/alarms.db";

    /** A private copy of Clock's database. We create the file, root only fills it, so it stays ours. */
    private static File clockCopy(Context c) throws Exception {
        File f = new File(c.getCacheDir(), "alarms.db");
        f.delete(); new File(f.getPath() + "-journal").delete();
        if (!f.createNewFile()) throw new IllegalStateException("can't create " + f);
        Root.global("cat " + CLOCK_DB + " > " + f);
        return f;
    }

    static JSONArray alarms(Context c) throws Exception {
        JSONArray out = new JSONArray();
        String[] day = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(clockCopy(c).getPath(), null, SQLiteDatabase.OPEN_READONLY);
             Cursor x = db.rawQuery("SELECT _id, hour, minutes, daysofweek, enabled, label FROM alarm_templates ORDER BY hour, minutes", null)) {
            while (x.moveToNext()) {
                int d = x.getInt(3);
                StringBuilder days = new StringBuilder();
                for (int i = 0; i < 7; i++) if ((d & 1 << i) != 0) days.append(days.length() > 0 ? " " : "").append(day[i]);
                out.put(new JSONObject().put("id", x.getLong(0)).put("time", String.format(Locale.US, "%02d:%02d", x.getInt(1), x.getInt(2)))
                        .put("days", d == 0 ? "once" : d == 127 ? "every day" : d == 31 ? "weekdays" : d == 96 ? "weekends" : days.toString())
                        .put("enabled", x.getInt(4) == 1).put("label", x.getString(5)));
            }
        }
        return out;
    }

    /** Stop Clock, change its database, put it back, then send Clock TIME_SET: it re-arms every alarm left (running Clock timers are reset). */
    private static JSONObject alarmDelete(Context c, long id, boolean off) throws Exception {
        Root.global("am force-stop " + CLOCK);
        File f = clockCopy(c);
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            db.execSQL("DELETE FROM alarm_instances WHERE alarm_id=" + id);
            db.execSQL(off ? "UPDATE alarm_templates SET enabled=0 WHERE _id=" + id : "DELETE FROM alarm_templates WHERE _id=" + id);
        }
        Root.global("cat " + f + " > " + CLOCK_DB + " && rm -f " + CLOCK_DB + "-journal && am broadcast -f 32 -a android.intent.action.TIME_SET -p " + CLOCK + " >/dev/null");
        for (Object o : iter(alarms(c))) if (((JSONObject) o).getLong("id") == id) return off && !((JSONObject) o).getBoolean("enabled") ? ok(true).put("alarm", o) : fail(off ? "Clock still has it on" : "Clock still has it");
        return off ? fail("alarm not found") : ok(true).put("deleted", true);
    }

    // ------------------------------------------------------------------ device settings and status

    private static volatile boolean torchOn;
    private static volatile String torchId;

    /** Broker start: follow the flashlight's state (Android only tells us by callback). */
    static void init(AgentA11y s) {
        try {
            CameraManager cm = s.getSystemService(CameraManager.class);
            for (String id : cm.getCameraIdList())
                if (Boolean.TRUE.equals(cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) { torchId = id; break; }
            cm.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override public void onTorchModeChanged(String id, boolean on) { if (id.equals(torchId)) torchOn = on; }
            }, s.main);
        } catch (Exception ignored) {}
    }

    private static final int[] FILTER = {NotificationManager.INTERRUPTION_FILTER_ALL, NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_ALARMS, NotificationManager.INTERRUPTION_FILTER_NONE};
    private static final List<String> DND = java.util.Arrays.asList("off", "on", "alarms", "silent"), RINGER = java.util.Arrays.asList("silent", "vibrate", "normal");

    private static JSONObject deviceSet(AgentA11y s, JSONObject p) throws Exception {
        AudioManager am = s.getSystemService(AudioManager.class);
        NotificationManager nm = s.getSystemService(NotificationManager.class);
        ContentResolver cr = s.getContentResolver();
        if ((p.has("dnd") || p.has("ringer")) && !nm.isNotificationPolicyAccessGranted()) Root.run("cmd notification allow_dnd " + s.getPackageName());
        if (p.has("dnd")) {
            int i = DND.indexOf(p.getString("dnd"));
            if (i < 0) throw new IllegalArgumentException("dnd must be off, on, alarms or silent");
            nm.setInterruptionFilter(FILTER[i]);
        }
        if (p.has("ringer")) {
            int i = RINGER.indexOf(p.getString("ringer"));
            if (i < 0) throw new IllegalArgumentException("ringer must be normal, vibrate or silent");
            am.setRingerMode(i);
        }
        String[] vols = {"volume", "ring_volume", "alarm_volume"};
        int[] streams = {AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING, AudioManager.STREAM_ALARM};
        for (int i = 0; i < 3; i++)
            if (p.has(vols[i])) am.setStreamVolume(streams[i], Math.round(Math.max(0, Math.min(100, p.getInt(vols[i]))) * am.getStreamMaxVolume(streams[i]) / 100f), 0);
        if (p.has("torch")) {
            if (torchId == null) throw new IllegalStateException("this phone has no flashlight");
            s.getSystemService(CameraManager.class).setTorchMode(torchId, p.getBoolean("torch"));
        }
        if (p.has("brightness")) {
            int b = p.getInt("brightness");
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, b < 0 ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            if (b >= 0) Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, Math.round(Math.min(100, b) * 2.55f));
        }
        if (p.has("wifi")) Root.run("svc wifi " + (p.getBoolean("wifi") ? "enable" : "disable"));
        if (p.has("bluetooth")) Root.run("svc bluetooth " + (p.getBoolean("bluetooth") ? "enable" : "disable"));
        if (p.has("airplane")) Root.run("cmd connectivity airplane-mode " + (p.getBoolean("airplane") ? "enable" : "disable"));
        Thread.sleep(p.has("wifi") || p.has("bluetooth") || p.has("airplane") ? 1500 : 300); // radios and the torch callback report a moment later
        return ok(true).put("status", status(s, false));
    }

    private static int pct(AudioManager am, int stream) { return Math.round(am.getStreamVolume(stream) * 100f / am.getStreamMaxVolume(stream)); }

    static JSONObject status(AgentA11y s, boolean screenTime) throws Exception {
        AudioManager am = s.getSystemService(AudioManager.class);
        BatteryManager bm = s.getSystemService(BatteryManager.class);
        ContentResolver cr = s.getContentResolver();
        int f = s.getSystemService(NotificationManager.class).getCurrentInterruptionFilter();
        AlarmManager.AlarmClockInfo next = s.getSystemService(AlarmManager.class).getNextAlarmClock();
        JSONObject st = new JSONObject().put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).put("charging", bm.isCharging())
                .put("dnd", f == FILTER[0] ? "off" : f == FILTER[1] ? "on" : f == FILTER[2] ? "alarms" : f == FILTER[3] ? "silent" : "unknown")
                .put("ringer", RINGER.get(am.getRingerMode())).put("volume", pct(am, AudioManager.STREAM_MUSIC)).put("ring_volume", pct(am, AudioManager.STREAM_RING))
                .put("alarm_volume", pct(am, AudioManager.STREAM_ALARM)).put("torch", torchOn)
                .put("brightness", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1 ? "auto" : Math.round(Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 0) / 2.55f))
                .put("wifi", s.getSystemService(WifiManager.class).isWifiEnabled()).put("bluetooth", Settings.Global.getInt(cr, Settings.Global.BLUETOOTH_ON, 0) == 1)
                .put("airplane", Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
                .put("next_alarm", next == null ? JSONObject.NULL : when(next.getTriggerTime(), false)).put("now_playing", nowPlaying(s));
        if (screenTime) st.put("screen_time", screenTime(s));
        return st;
    }

    /** Today's foreground time per app (minutes), most first, 8 at most. */
    private static JSONArray screenTime(Context c) throws Exception {
        long start = LocalDateTime.now().toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<UsageStats> all = new ArrayList<>(c.getSystemService(UsageStatsManager.class).queryAndAggregateUsageStats(start, System.currentTimeMillis()).values());
        all.sort((a, b) -> Long.compare(b.getTotalTimeInForeground(), a.getTotalTimeInForeground()));
        JSONArray out = new JSONArray();
        for (UsageStats u : all) {
            if (out.length() >= 8 || u.getTotalTimeInForeground() < 60_000) break;
            String label = u.getPackageName();
            try { label = String.valueOf(c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(label, 0))); } catch (Exception ignored) {}
            out.put(new JSONObject().put("app", label).put("minutes", u.getTotalTimeInForeground() / 60_000));
        }
        return out;
    }

    // ------------------------------------------------------------------ media (sessions are visible to us through the notification listener)

    private static MediaController controller(Context c) {
        List<MediaController> l = c.getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(c, Notifs.class));
        for (MediaController m : l) if (m.getPlaybackState() != null && m.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) return m;
        return l.isEmpty() ? null : l.get(0);
    }

    private static Object nowPlaying(Context c) throws Exception {
        MediaController m = controller(c);
        MediaMetadata md = m == null ? null : m.getMetadata();
        if (md == null) return JSONObject.NULL;
        String app = m.getPackageName();
        try { app = String.valueOf(c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(app, 0))); } catch (Exception ignored) {}
        return new JSONObject().put("app", app).put("title", md.getString(MediaMetadata.METADATA_KEY_TITLE)).put("artist", md.getString(MediaMetadata.METADATA_KEY_ARTIST))
                .put("playing", m.getPlaybackState() != null && m.getPlaybackState().getState() == PlaybackState.STATE_PLAYING);
    }

    private static JSONObject media(Context c, String action) throws Exception {
        MediaController m = controller(c);
        int key;
        switch (action) {
            case "status": return ok(true).put("now_playing", nowPlaying(c));
            case "play": key = KeyEvent.KEYCODE_MEDIA_PLAY; break;
            case "pause": key = KeyEvent.KEYCODE_MEDIA_PAUSE; break;
            case "next": key = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "previous": key = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            default: throw new IllegalArgumentException("action must be play, pause, next, previous or status");
        }
        if (m != null) { // the app's own session: exact
            MediaController.TransportControls t = m.getTransportControls();
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY) t.play(); else if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) t.pause(); else if (key == KeyEvent.KEYCODE_MEDIA_NEXT) t.skipToNext(); else t.skipToPrevious();
        } else { // nothing has a session: a media key wakes the last player
            AudioManager am = c.getSystemService(AudioManager.class);
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
        }
        Thread.sleep(800); // let the player update its metadata
        return ok(true).put("now_playing", nowPlaying(c));
    }

    // ------------------------------------------------------------------ helpers

    /** A read result row: text is other people's words (the broker scores and may withhold it). */
    private static JSONObject item(String source, String text) throws Exception {
        return new JSONObject().put("source", source).put("trusted", false).put("text", text == null ? "" : text);
    }

    private static Iterable<Object> iter(JSONArray a) {
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) l.add(a.opt(i));
        return l;
    }

    private static JSONObject ok(boolean ok) throws Exception { return new JSONObject().put("ok", ok); }
    private static JSONObject fail(String why) throws Exception { return ok(false).put("reason", why); }
}
