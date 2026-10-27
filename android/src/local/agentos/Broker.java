package local.agentos;

import android.app.KeyguardManager;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Base64;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The bouncer. Every tool call comes here; it decides auto / approved / denied / blocked, then acts and audits.
 *
 * Tiers: T0 observe (screen.read, wait, list_apps) · T1 navigate (plain taps, scroll, press, open_app) ·
 * T2 draft (type) · T3 outward (risky button, type+submit, any tap on a secure screen, firewall changes) ·
 * T4 forbidden (passwords, typing on secure screens, system/AgentOS surfaces, locked phone).
 * Rule of Two, per action: untrusted (this task has read screen content) · private (private app or secure screen) ·
 * outward (T3). T3 always needs a fingerprint; T2 needs one when untrusted and private are both true.
 */
final class Broker {
    static final class Lease {
        final String id = "t" + Approvals.hex(4), goal;
        final Set<String> apps;
        final long expires;
        final int maxSteps;
        volatile int steps;
        volatile boolean paused, untrusted;
        /** Canaries, OTPs and card numbers seen on screen in this task (P3: never typed, no approval possible). */
        final Set<String> secrets = java.util.Collections.synchronizedSet(new LinkedHashSet<String>());

        Lease(String goal, Set<String> apps, long expires, int maxSteps) {
            this.goal = goal; this.apps = apps; this.expires = expires; this.maxSteps = maxSteps;
        }
    }

    private static final java.util.regex.Pattern SECRET = java.util.regex.Pattern.compile(
            "AGENTOS-CANARY-[A-Z0-9]+|(?<![\\d.:])\\d{6,8}(?![\\d.:])|(?<!\\d)\\d(?:[ -]?\\d){12,18}(?!\\d)");
    private static final String[] TIER = {"T0 observe", "T1 navigate", "T2 draft", "T3 outward", "T4 forbidden"};
    private final AgentA11y svc;
    private final Audit audit;
    private final Object actLock = new Object();
    private volatile Lease lease;
    private volatile Approvals.Pending current;

    Broker(AgentA11y svc) {
        this.svc = svc;
        audit = new Audit(svc.getFilesDir());
        PromptGuard.init(svc);
    }

    JSONObject call(String m, JSONObject p) throws Exception {
        switch (m) {
            case "screen.read": return read();
            case "guard.classify": return PromptGuard.classify(p.getString("text"));
            case "list_apps": return ok(true).put("apps", Actions.listApps(svc));
            case "wait":
                Thread.sleep(Math.max(0, Math.min(5000, p.optLong("ms", 1000))));
                return ok(true).put("decision", "auto").put("screen", read());
            case "task.start": return start(p);
            case "task.stop": return stop(p.optString("task_id", ""), "task.stop");
            case "task.pause": return pause(true, "task.pause");
            case "task.resume": return pause(false, "task.resume");
            case "audit.tail": return audit.tail(Math.max(1, Math.min(500, p.optInt("n", 20)))).put("pubkey", Approvals.publicKeyB64(svc));
            case "firewall.list": return Firewall.list(svc);
            case "tap": case "type": case "scroll": case "press": case "open_app": case "firewall.set": case "firewall.clear":
                synchronized (actLock) { return act(m, p); } // one action at a time; nothing else acts while a card is up
            default: throw new IllegalArgumentException("unknown method " + m);
        }
    }

    // ------------------------------------------------------------------ tasks

    private synchronized JSONObject start(JSONObject p) throws JSONException {
        if (lease != null) stop(lease.id, "replaced by a new task.start");
        Set<String> apps = new LinkedHashSet<>();
        JSONArray refused = new JSONArray(), a = p.optJSONArray("apps");
        for (int i = 0; a != null && i < a.length(); i++) {
            if (Guard.forbidden(a.getString(i))) refused.put(a.getString(i)); else apps.add(a.getString(i));
        }
        Lease l = new Lease(p.optString("goal"), apps, System.currentTimeMillis() + Math.min(p.optLong("ttl_s", 600), 3600) * 1000,
                Math.min(p.optInt("max_steps", 40), 200));
        lease = l;
        svc.main.post(svc.autoPause::start);
        audit.log(entry("task.start", l).put("goal", l.goal).put("apps", new JSONArray(apps)).put("refused_apps", refused));
        return ok(true).put("task_id", l.id).put("apps", new JSONArray(apps)).put("expires", l.expires)
                .put("max_steps", l.maxSteps).put("refused_apps", refused);
    }

    /** Stop: revoke the lease and deny any open approval. Also used by the volume-key kill switch. */
    synchronized JSONObject stop(String taskId, String why) throws JSONException {
        Lease l = lease;
        if (l == null) return ok(true).put("reason", "no active task");
        if (!taskId.isEmpty() && !taskId.equals(l.id)) return ok(false).put("reason", "task " + taskId + " is not the active task (" + l.id + ")");
        lease = null;
        Approvals.Pending c = current;
        if (c != null) c.finish(null, "task stopped");
        svc.main.post(svc.autoPause::stop);
        audit.log(entry("task.stop", l).put("reason", why).put("steps", l.steps));
        return ok(true).put("task_id", l.id).put("reason", why);
    }

    private synchronized JSONObject pause(boolean on, String why) throws JSONException {
        Lease l = lease;
        if (l == null) return ok(false).put("reason", "no active task");
        l.paused = on;
        svc.main.post(on ? svc.autoPause::stop : svc.autoPause::start);
        audit.log(entry(on ? "task.pause" : "task.resume", l).put("reason", why));
        return ok(true).put("task_id", l.id).put("paused", on);
    }

    /** AutoPause: a human touched the screen. Main thread. */
    void humanTouched() {
        Lease l = lease;
        if (l == null || l.paused || current != null) return;
        try { pause(true, "auto-pause: a human touched the screen"); } catch (JSONException ignored) {}
    }

    // ---------------------------------------------------------------- actions

    private JSONObject act(String tool, JSONObject p) throws Exception {
        Lease l = lease;
        boolean fw = tool.startsWith("firewall."), scoped = !fw && !tool.equals("press");
        AccessibilityNodeInfo node = p.has("id") ? svc.screen.node(p.getInt("id")) : null;
        if (node == null && (tool.equals("tap") || tool.equals("type"))) throw new IllegalArgumentException(tool + " needs an id from screen.read");
        boolean secure = !fw && svc.screen.secure();
        String text = p.optString("text", ""), pkg, target, why;
        int tier;
        switch (tool) {
            case "tap": {
                String risky = Guard.riskyLabel(node);
                pkg = pkg(node); target = Guard.label(node);
                tier = risky != null || secure ? 3 : 1;
                why = risky != null ? "outward button \"" + risky + "\"" : secure ? "tap on a secure screen (" + svc.screen.secureWhy + ")" : "navigation tap";
                break;
            }
            case "type":
                pkg = pkg(node); target = Guard.label(node);
                tier = node.isPassword() || secure ? 4 : p.optBoolean("submit") ? 3 : 2;
                why = node.isPassword() ? "password field: hand this to the human" : secure ? "typing on a secure screen" : tier == 3 ? "type and submit" : "draft text";
                break;
            case "scroll":
                pkg = node != null ? pkg(node) : svc.screen.pkg; target = (node != null ? Guard.label(node) + " " : "screen ") + p.optString("dir", "down");
                tier = 1; why = "scroll";
                break;
            case "press":
                pkg = svc.screen.pkg; target = p.optString("key"); tier = 1; why = "system key";
                break;
            case "open_app":
                pkg = p.getString("pkg"); target = "launch app"; tier = 1; why = "open app in lease";
                break;
            case "firewall.set": {
                JSONArray d = p.optJSONArray("domains");
                pkg = p.getString("pkg"); target = p.getString("mode") + " " + (d == null || d.length() == 0 ? "all traffic" : d.join(",").replace("\"", ""));
                tier = 3; why = "network rule change";
                break;
            }
            default: // firewall.clear
                pkg = "*"; target = "remove all AgentOS firewall rules"; tier = 3; why = "network rule change";
        }

        String block = null;
        if (svc.getSystemService(KeyguardManager.class).isKeyguardLocked()) block = "phone is locked";
        else if (!fw && l == null) block = "no active task: call task.start first";
        else if (!fw && System.currentTimeMillis() > l.expires) block = "task lease expired";
        else if (!fw && l.paused) block = "task paused (human touched the screen or task.pause): call task.resume after re-reading the screen";
        else if (!fw && l.steps >= l.maxSteps) block = "task reached max_steps " + l.maxSteps;
        else if (!fw && p.has("task_id") && !p.getString("task_id").equals(l.id)) block = "task_id does not match the active task";
        else if (tier == 4) block = "T4 forbidden: " + why;
        else if (tool.equals("type") && leaksSecret(l, text)) block = "T4 forbidden: text contains a code/canary read from the screen (P3)";
        else if (node != null && Guard.offLimits(node)) block = "T4 forbidden: AgentOS's own window";
        else if (scoped && Guard.forbidden(pkg)) block = "T4 forbidden: " + pkg + " is a protected system surface";
        else if (scoped && !l.apps.contains(pkg)) block = pkg + " is not in this task's lease " + l.apps;

        boolean untrusted = l != null && l.untrusted, priv = Guard.privateApp(pkg) || secure, outward = tier == 3;
        JSONObject r2 = new JSONObject().put("untrusted", untrusted).put("private", priv).put("outward", outward);
        JSONObject log = entry(tool, l).put("pkg", pkg).put("target", target).put("text", Guard.clip(text, 200))
                .put("tier", TIER[tier]).put("rule_of_two", r2);
        if (block != null) return finish(log, ok(false), "blocked", block, null);

        String decision = "auto";
        if (tier == 3 || (tier == 2 && untrusted && priv)) {
            if (tier == 2) why += " after reading untrusted content in a private app (Rule of Two)";
            Approvals.Pending a = approve(tool, pkg, target, text, l, TIER[tier], why, r2, Math.max(10, Math.min(120, p.optInt("approval_timeout_s", 60))) * 1000L);
            if (!Approvals.verify(svc, a.canonical, a.sig)) return finish(log, ok(false), "denied", a.reason, null);
            log.put("approver", "fingerprint").put("canonical", new String(a.canonical, java.nio.charset.StandardCharsets.UTF_8))
               .put("pubkey_sha256", Audit.hex(Audit.sha256(Base64.decode(Approvals.publicKeyB64(svc), Base64.NO_WRAP))))
               .put("sig", Base64.encodeToString(a.sig, Base64.NO_WRAP));
            if (node != null && (!node.refresh() || !Guard.label(node).equals(target) || !pkg(node).equals(pkg)))
                return finish(log, ok(false), "blocked", "screen changed after approval; nothing done", null);
            decision = "approved";
        }

        boolean done = execute(tool, p, node);
        if (!fw && l != null) l.steps++;
        JSONObject res = ok(done).put("tier", TIER[tier]).put("rule_of_two", r2);
        if (fw) return finish(log, res, decision, done ? why : "failed", null);
        settle();
        return finish(log, res, decision, done ? why : "Android refused the action", read());
    }

    private JSONObject finish(JSONObject log, JSONObject res, String decision, String reason, JSONObject screen) throws JSONException {
        audit.log(log.put("decision", decision).put("reason", reason).put("ok", res.getBoolean("ok")));
        res.put("decision", decision).put("reason", reason);
        return screen == null ? res : res.put("screen", screen);
    }

    private boolean execute(String tool, JSONObject p, AccessibilityNodeInfo node) throws Exception {
        switch (tool) {
            case "tap": return Actions.tap(svc, node);
            case "type": return Actions.type(node, p.getString("text"), p.optBoolean("submit"));
            case "scroll": return Actions.scroll(svc, node, p.optString("dir", "down"));
            case "press": return Actions.press(svc, p.getString("key"));
            case "open_app": return Actions.openApp(svc, p.getString("pkg"));
            case "firewall.set": Firewall.set(svc, p.getString("pkg"), p.getString("mode"), p.optJSONArray("domains")); return true;
            default: Firewall.clear(); return true;
        }
    }

    /** Show the card, wait (approval_timeout_s, default 60 s) for Deny / fingerprint / Stop. Returns the finished Pending. */
    private Approvals.Pending approve(String tool, String pkg, String target, String text, Lease l, String tier, String why, JSONObject r2,
            long timeoutMs) throws Exception {
        ApprovalCard.Model m = new ApprovalCard.Model();
        m.title = tool.equals("type") ? "type into \"" + target + "\"" : tool.equals("tap") ? "tap \"" + target + "\"" : tool + " " + target;
        m.app = pkg; m.target = target; m.text = text; m.tier = tier; m.reason = why;
        m.task = l == null ? "(no task)" : l.id + " · " + l.goal;
        m.untrusted = r2.getBoolean("untrusted"); m.priv = r2.getBoolean("private"); m.outward = r2.getBoolean("outward");
        Approvals.Pending a = new Approvals.Pending(
                Approvals.canonical(tool, pkg, target, text, l == null ? "" : l.id, Approvals.hex(16)), m);
        Approvals.pending.put(a.id, a);
        current = a;
        svc.main.post(() -> {
            svc.autoPause.stop();
            svc.card.show(m, new ApprovalCard.Listener() {
                @Override public void approve() {
                    svc.card.hide(); // the fingerprint prompt must not be covered by our overlay
                    svc.startActivity(new Intent(svc, ApproveActivity.class).putExtra("id", a.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
                @Override public void deny() { a.finish(null, "denied by user"); }
            });
        });
        try { if (!a.done.await(timeoutMs, TimeUnit.MILLISECONDS)) a.finish(null, null); }
        finally {
            current = null;
            Approvals.pending.remove(a.id);
            svc.main.post(() -> {
                svc.card.hide();
                Lease now = lease;
                if (now != null && !now.paused) svc.autoPause.start();
            });
        }
        return a;
    }

    // ---------------------------------------------------------------- helpers

    private JSONObject read() throws JSONException {
        JSONObject s = svc.screen.read(svc);
        Lease l = lease;
        if (l != null) {
            JSONArray nodes = s.getJSONArray("nodes");
            if (nodes.length() > 0) l.untrusted = true; // the task has now seen third-party content
            for (int i = 0; i < nodes.length(); i++) {
                java.util.regex.Matcher m = SECRET.matcher(nodes.getJSONObject(i).optString("text") + " " + nodes.getJSONObject(i).optString("desc"));
                while (m.find()) l.secrets.add(m.group().replaceAll("[ -]", ""));
            }
        }
        PromptGuard.annotate(s); // every untrusted node gets a Prompt Guard score
        return s.put("ok", true);
    }

    private static boolean leaksSecret(Lease l, String text) {
        String flat = text.replaceAll("[ -]", "");
        synchronized (l.secrets) { for (String x : l.secrets) if (flat.contains(x.replaceAll("[ -]", ""))) return true; }
        return false;
    }

    /** Wait for the screen to settle: the first event after the action, then 300 ms of quiet; at most 2 s. */
    private void settle() throws InterruptedException {
        long t0 = SystemClock.uptimeMillis(), now;
        do { Thread.sleep(50); now = SystemClock.uptimeMillis(); }
        while (now - t0 < 2000 && (svc.lastEvent < t0 || now - svc.lastEvent < 300));
    }

    private static String pkg(AccessibilityNodeInfo n) { return String.valueOf(n.getPackageName()); }

    private static JSONObject ok(boolean ok) throws JSONException { return new JSONObject().put("ok", ok); }

    private static JSONObject entry(String tool, Lease l) throws JSONException {
        return new JSONObject().put("task", l == null ? "" : l.id).put("tool", tool);
    }
}
