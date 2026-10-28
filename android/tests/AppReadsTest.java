package local.agentos;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Run in a separate app_process with the broker APK on CLASSPATH. No posted notifications or owner settings touched. */
public final class AppReadsTest {
    private static void check(boolean yes, String why) { if (!yes) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        Field recent = Notifs.class.getDeclaredField("recent"); recent.setAccessible(true);
        Map<String, Notifs.Rec> notes = (Map<String, Notifs.Rec>) recent.get(null);
        long now = System.currentTimeMillis();
        for (int i = 0; i < 70; i++) {
            Notifs.Rec r = new Notifs.Rec(); r.key = "fixture-" + i; r.pkg = i == 0 ? "com.Slack" : "com.linkedin.android";
            r.when = now + i; r.text = "fixture"; r.score = 0; notes.put(r.key, r);
        }
        JSONArray slack = Notifs.list(1, "com.Slack", now);
        check(slack.length() == 1 && slack.getJSONObject(0).getString("pkg").equals("com.Slack"), "filter before limit, even behind 69 other app notifications");
        check(Notifs.list(20, "com.Slack", now + 1).length() == 0, "since cutoff");
        check(Notifs.list(40).length() == 40, "existing core read limit");
        check(Notifs.list(2, "com.twitter.android", 0).length() == 0, "no notifications means empty, not another app's data");
        Method rank = AppReads.class.getDeclaredMethod("rank", JSONObject.class, long.class); rank.setAccessible(true);
        JSONObject direct = new JSONObject().put("text", "Meeting today").put("direct", true).put("unread", true).put("when", now);
        JSONObject ad = new JSONObject().put("text", "Urgent meeting today").put("direct", true).put("sponsored", true).put("when", now);
        rank.invoke(null, direct, now); rank.invoke(null, ad, now);
        check(direct.getBoolean("worth_looking_at") && direct.getInt("rank") > ad.getInt("rank"), "unread direct messages outrank ads");
        check(!ad.getBoolean("worth_looking_at"), "sponsored messages never get urgency priority");
        check(AppReads.app("linkedin.unread").equals("linkedin") && AppReads.app("other.summary") == null && AppReads.app("linkedin.send") == null, "fixed read allowlist");
        Field off = Plugins.class.getDeclaredField("off"); off.setAccessible(true);
        off.set(null, new HashSet<>(AppReads.PACKAGES.keySet()));
        for (String id : AppReads.PACKAGES.keySet()) check(id.equals(Plugins.off(id + ".summary", new JSONObject())), "broker toggle " + id);
        check("linkedin".equals(Plugins.off("linkedin.unread", new JSONObject())), "cache toggle");
        check("gmail".equals(Plugins.off("intent", new JSONObject().put("kind", "email"))), "Gmail draft toggle");
        check(Plugins.off("open_app", new JSONObject().put("pkg", "com.linkedin.android")) == null, "app itself remains usable");
        System.out.println("PASS: native package/time filtering, legacy limit, ranking, fixed methods, broker app switches and Gmail draft gate");
    }
}
