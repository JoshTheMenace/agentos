package local.agentos;

import android.content.SharedPreferences;
import android.util.AtomicFile;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONObject;

/** Fixed, owner-installed runtime mods; callers cannot supply scripts, commands or target packages. */
final class RuntimeMods {
    static final class Kind {
        final String id, pkg, app, label, script, description;
        Kind(String id, String pkg, String app, String label, String script, String description) {
            this.id = id; this.pkg = pkg; this.app = app; this.label = label; this.script = script; this.description = description;
        }
    }
    static boolean known(String id) { for (Kind kind : BuiltinMods.VALUES) if (kind.id.equals(id)) return true; return false; }
    final Kind kind;
    private final AgentA11y svc;
    private final Audit audit;
    private final SharedPreferences prefs;
    private final File dir, control, status;
    private final String generation = UUID.randomUUID().toString();
    private volatile boolean closed, starting;
    private final Runnable pulse = this::pulse;

    RuntimeMods(AgentA11y svc, Audit audit, Kind kind) {
        this.svc = svc; this.audit = audit; this.kind = kind;
        prefs = svc.getSharedPreferences(kind.id.equals("duolingo-promotions") ? "runtime_mods" : "runtime_mods_" + kind.id, 0);
        dir = new File(svc.getFilesDir(), "runtime-mods" + (kind.id.equals("duolingo-promotions") ? "" : "/" + kind.id)); dir.mkdirs();
        control = new File(dir, "control.json"); status = new File(dir, "status.json");
        try {
            for (String name : new String[]{"controller.py", kind.script})
                try (java.io.InputStream in = svc.getAssets().open("runtime-mods/" + name)) {
                    Files.copy(in, new File(dir, name).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
        } catch (Exception e) { android.util.Log.e("RuntimeMods", "assets unavailable", e); }
        svc.main.post(pulse);
    }

    private synchronized void write() throws Exception {
        JSONObject state = new JSONObject().put("id", kind.id).put("generation", generation).put("active", !closed && prefs.getBoolean("installed", false))
                .put("enabled", prefs.getBoolean("enabled", false)).put("revision", prefs.getLong("revision", 0))
                .put("lease_until", System.currentTimeMillis() + 8000);
        AtomicFile file = new AtomicFile(control);
        java.io.FileOutputStream out = file.startWrite();
        try { out.write(state.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
        catch (Exception e) { file.failWrite(out); throw e; }
    }

    private synchronized void pulse() {
        svc.main.removeCallbacks(pulse);
        if (closed || !prefs.getBoolean("installed", false)) return;
        try {
            write();
            JSONObject live = readStatus();
            if (!starting && (!generation.equals(live.optString("generation")) || System.currentTimeMillis() - live.optLong("heartbeat") > 6000)) {
                starting = true;
                new Thread(() -> {
                    try {
                        String python = "/data/data/com.termux/files/usr/bin/python";
                        Root.global(python + " " + dir + "/controller.py >" + dir + "/controller.log 2>&1 </dev/null &");
                    } catch (Exception e) { android.util.Log.e("RuntimeMods", "controller start failed", e); }
                    finally { svc.main.post(() -> starting = false); }
                }, "runtime-mod-start").start();
            }
        } catch (Exception e) { android.util.Log.e("RuntimeMods", "heartbeat failed", e); }
        svc.main.postDelayed(pulse, 2000);
    }

    synchronized JSONObject install() throws Exception {
        prefs.edit().putBoolean("installed", true).commit();
        return setEnabled(true);
    }

    synchronized void migrate(JSONObject legacy) {
        if (!prefs.contains("installed") && !prefs.edit().putBoolean("installed", true)
                .putBoolean("enabled", legacy.optBoolean("enabled", true)).putString("reason", legacy.optString("disabled"))
                .putLong("created", legacy.optLong("created")).putString("by", legacy.optString("by")).commit())
            throw new IllegalStateException("Could not migrate promoted-ad mod");
    }

    synchronized JSONObject setEnabled(boolean on) throws Exception {
        if (!prefs.getBoolean("installed", false)) throw new IllegalArgumentException("mod is not installed");
        prefs.edit().putBoolean("enabled", on).putString("reason", "").putLong("revision", prefs.getLong("revision", 0) + 1).commit();
        svc.main.removeCallbacks(pulse); pulse();
        changed("toggled");
        audit.log(new JSONObject().put("tool", "app.mod.set_enabled").put("id", kind.id).put("enabled", on).put("decision", "human"));
        return new JSONObject().put("ok", true).put("enabled", on);
    }

    synchronized JSONObject remove() throws Exception {
        if (!prefs.getBoolean("installed", false)) return new JSONObject().put("ok", false).put("reason", "mod is not installed");
        prefs.edit().putBoolean("installed", false).putBoolean("enabled", false).commit();
        write(); svc.main.removeCallbacks(pulse); changed("removed");
        audit.log(new JSONObject().put("tool", "app.mod.remove").put("id", kind.id).put("decision", "auto"));
        return new JSONObject().put("ok", true).put("removed", kind.id);
    }

    synchronized void disable(String pkg, String reason) throws Exception {
        if ((!pkg.isEmpty() && !kind.pkg.equals(pkg)) || !prefs.getBoolean("installed", false)) return;
        prefs.edit().putBoolean("enabled", false).putString("reason", reason).commit();
        write(); changed("disabled");
    }

    synchronized JSONObject entry() throws org.json.JSONException {
        if (!prefs.getBoolean("installed", false)) return null;
        boolean enabled = prefs.getBoolean("enabled", false);
        JSONObject live = readStatus();
        boolean fresh = generation.equals(live.optString("generation")) && System.currentTimeMillis() - live.optLong("heartbeat") < 6000;
        return new JSONObject().put("id", kind.id).put("package", kind.pkg).put("app", kind.app).put("label", kind.label).put("description", kind.description)
                .put("enabled", enabled).put("disabled", prefs.getString("reason", "")).put("shown", enabled && fresh && live.optBoolean("attached"))
                .put("status", !enabled ? "Off" : fresh ? live.optString("state", "Starting") : "Starting");
    }

    private JSONObject readStatus() {
        try { return new JSONObject(new String(Files.readAllBytes(status.toPath()), StandardCharsets.UTF_8)); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private void changed(String change) throws Exception { Events.emit("mod.changed", new JSONObject().put("change", change).put("id", kind.id)); }
    synchronized void close() { closed = true; svc.main.removeCallbacks(pulse); try { write(); } catch (Exception ignored) {} }
}
