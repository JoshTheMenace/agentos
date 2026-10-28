package local.agentos;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tool plugins (agentd/plugins.js, agentd/research/TOOLS-AT-SCALE.md): the owner's on/off switches for groups of agent
 * API tools. This is the source of truth. The map from method to plugin lives here, in Java, so agentd can't misreport
 * which plugin a call belongs to. Like a light switch wired at the fuse box: agentd also hides the tools, but the
 * broker is where the power is actually cut. A switch gates the API methods only, never the app itself (founder
 * decision 10-27): with Messages off the agent may still open and drive the Messages app on screen.
 * files/plugins.json {"off":[ids]} is written only by set(), which Broker allows only from the launcher (owner line) or adb.
 */
final class Plugins {
    static final List<String> IDS = Collections.unmodifiableList(Arrays.asList(
            "calendar", "messages", "clock", "device", "media", "web", "privacy", "vault", "memory", "rules", "schedule", "mods", "apps",
            "linkedin", "x", "slack", "instagram", "discord", "whatsapp", "gmail", "spotify", "youtube")); // apps (micro-apps) runs in agentd only
    /** Broker method (or "intent:kind": one method serves several plugins) -> plugin. memory, rules, schedule run in agentd only. */
    private static final Map<String, String> MAP = new HashMap<>();
    static {
        for (String id : AppReads.PACKAGES.keySet()) MAP.put(id + ".summary", id);
        MAP.put("linkedin.unread", "linkedin");
        for (String m : new String[]{"calendar.add", "calendar.find", "calendar.update", "calendar.delete"}) MAP.put(m, "calendar");
        for (String m : new String[]{"contacts.find", "sms.send", "sms.list", "calls.list", "call.place"}) MAP.put(m, "messages");
        MAP.put("intent:email", "gmail");
        for (String m : new String[]{"alarms.list", "alarms.delete"}) MAP.put(m, "clock"); // set_alarm/set_timer are core
        for (String m : new String[]{"device.set", "device.status"}) MAP.put(m, "device");
        for (String m : new String[]{"media.control", "intent:play"}) MAP.put(m, "media");
        for (String m : new String[]{"intent:navigate", "intent:url"}) MAP.put(m, "web"); // intent:search is core
        for (String m : new String[]{"trackers.scan", "trackers.list", "trackers.block", "trackers.unblock", "firewall.list", "firewall.set", "firewall.clear"}) MAP.put(m, "privacy");
        for (String m : new String[]{"vault.list", "vault.fill"}) MAP.put(m, "vault");
        for (String m : new String[]{"app.mod.apply", "app.mod.targets", "app.mod.list", "app.mod.remove", "app.mod.preview", "app.mod.disable"}) MAP.put(m, "mods");
    }
    private static File file;
    private static volatile Set<String> off = Collections.emptySet();

    static void init(Context c) {
        file = new File(c.getFilesDir(), "plugins.json");
        try {
            JSONArray a = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).getJSONArray("off");
            Set<String> s = new LinkedHashSet<>();
            for (int i = 0; i < a.length(); i++) if (IDS.contains(a.getString(i))) s.add(a.getString(i));
            off = Collections.unmodifiableSet(s);
        } catch (Exception e) { /* missing or broken: everything on */ }
    }

    /** The switched-off plugin this call belongs to, or null (core, or its plugin is on). */
    static String off(String method, JSONObject p) {
        String id = MAP.get(method.equals("intent") ? "intent:" + p.optString("kind") : method);
        return id != null && off.contains(id) ? id : null;
    }

    static JSONObject list() throws Exception {
        return new JSONObject().put("ok", true).put("off", new JSONArray(off)).put("ids", new JSONArray(IDS));
    }

    /** Owner or adb only (Broker checks). Persists, audits and tells every listener (agentd re-reads its cache). */
    static synchronized JSONObject set(String id, boolean on, String by, Audit audit) throws Exception {
        if (!IDS.contains(id)) return new JSONObject().put("ok", false).put("reason", "unknown plugin " + id);
        Set<String> s = new LinkedHashSet<>(off);
        if (on) s.remove(id); else s.add(id);
        File tmp = new File(file.getPath() + ".tmp");
        Files.write(tmp.toPath(), new JSONObject().put("off", new JSONArray(s)).toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        off = Collections.unmodifiableSet(s);
        audit.log(new JSONObject().put("task", "").put("tool", "plugins.set").put("plugin", id).put("on", on).put("decision", "auto").put("reason", "by the " + by));
        Events.emit("plugins.changed", new JSONObject().put("id", id).put("on", on));
        return list();
    }
}
