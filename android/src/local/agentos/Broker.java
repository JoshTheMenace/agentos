package local.agentos;

import android.app.KeyguardManager;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Base64;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * outward (T3). T3 always needs approval; T2 needs it when untrusted and private are both true. Who may approve
 * (the reviewer, a person's tap or voice, or only a fingerprint) is decided by channels(), the risk-adaptive policy.
 * Notification text (Notifs) is untrusted exactly like screen text; notifications.reply is a T3 outward action.
 */
final class Broker {
    /**
     * What the agent has seen and done. Like a hand stamp at a club: a replacement task.start by the same caller
     * within the old lease's TTL keeps it, so re-leasing can't wash off the taint or reset the step count.
     */
    static final class History {
        volatile int steps;
        /** untrusted: read third-party screen text. flagged: Prompt Guard flagged some. copied: typed text copied from it. */
        volatile boolean untrusted, flagged, copied;
        /** Canaries, OTPs and card numbers seen on screen (P3: never typed, no approval possible). */
        final Set<String> secrets = Collections.synchronizedSet(new LinkedHashSet<String>());
        /** Third-party screen strings (lowercase) and what the agent typed itself: for the "copied from the screen" check. */
        final Set<String> seen = Collections.synchronizedSet(new LinkedHashSet<String>()), typed = Collections.synchronizedSet(new LinkedHashSet<String>());
    }

    static final class Lease {
        final String id = "t" + Approvals.hex(4), goal, caller;
        final Set<String> apps;
        final long expires;
        final int maxSteps;
        final History h;
        volatile boolean paused, granted; // granted: a human allowed it on the lease card (cleared by stop)
        /** The field this task last typed into: its live text is the message a later "Send" tap sends. */
        volatile AccessibilityNodeInfo field;

        Lease(String goal, Set<String> apps, long expires, int maxSteps, String caller, History h) {
            this.goal = goal; this.apps = apps; this.expires = expires; this.maxSteps = maxSteps; this.caller = caller; this.h = h;
        }
    }

    // ------------------------------------------------- risk-adaptive approvals

    /** Money and the like: the only things that need a fingerprint (founder policy, 10-27). */
    private static final java.util.regex.Pattern MONEY = java.util.regex.Pattern.compile("(?i)\\b(pay|payment|buy|purchase|place order|"
            + "checkout|check out|transfer|donate|subscribe|add to (?:cart|basket|bag)|install|uninstall|factory reset|deactivate)\\b");
    static final List<String> FINGERPRINT_ONLY = Collections.singletonList("fingerprint"),
            HUMAN = Collections.unmodifiableList(Arrays.asList("tap", "voice", "fingerprint")),
            ORDINARY = Collections.unmodifiableList(Arrays.asList("tap", "voice", "reviewer", "fingerprint"));

    /**
     * Risk-adaptive approvals, three steps. A fingerprint only for money, installs and vault passwords. A person (tap
     * or voice; the reviewer may not) when the task is tainted: Prompt Guard flagged text, text copied from untrusted
     * content, or all three Rule-of-Two lamps lit. Everything else is ordinary: agentd's reviewer may auto-approve.
     * Returns {channels, why}: why is null for ordinary actions.
     */
    static Object[] channels(History h, String tool, String target, boolean untrusted, boolean priv, boolean outward, boolean copied) {
        if (tool.startsWith("vault.")) return new Object[]{FINGERPRINT_ONLY, "uses a saved password"};
        if (MONEY.matcher(target).find()) return new Object[]{FINGERPRINT_ONLY, "money or an install"};
        if (h != null && h.flagged) return new Object[]{HUMAN, "Prompt Guard flagged text in this task"};
        if (copied || h != null && h.copied) return new Object[]{HUMAN, "the text was copied from untrusted content"};
        if (untrusted && priv && outward) return new Object[]{HUMAN, "all three Rule-of-Two lamps are lit"};
        return new Object[]{ORDINARY, null};
    }

    static final java.util.regex.Pattern SECRET = java.util.regex.Pattern.compile(
            "AGENTOS-CANARY-[A-Z0-9]+|(?<![\\d.:])\\d{6,8}(?![\\d.:])|(?<!\\d)\\d(?:[ -]?\\d){12,18}(?!\\d)");
    private static final String[] TIER = {"T0 observe", "T1 navigate", "T2 draft", "T3 outward", "T4 forbidden"};
    private final AgentA11y svc;
    private final Audit audit;
    private final Object actLock = new Object(), startLock = new Object();
    private final LeaseCard leaseCard;
    /** App mods (Mods.java): buttons drawn on top of other apps. */
    final Mods mods;
    /** Open approvals as announced on /events (approval_id -> approval.shown payload): approval.list returns these. */
    private final Map<String, JSONObject> shown = new ConcurrentHashMap<>();
    private volatile Lease lease, last; // last: the most recent lease, even after stop (for carry-over)
    private volatile Approvals.Pending current;
    private volatile CompletableFuture<Boolean> leaseAsk;
    /** The island pill's step label. */
    private volatile String step = "Starting";
    private static volatile Broker self; // for Notifs.flagged

    Broker(AgentA11y svc) {
        this.svc = svc;
        leaseCard = new LeaseCard(svc);
        audit = new Audit(svc.getFilesDir());
        mods = new Mods(svc, audit);
        PromptGuard.init(svc);
        Trackers.start(svc);
        self = this;
    }

    /** uid: the calling app's uid for task.start and approval.confirm (-1 if unknown or not looked up). */
    JSONObject call(String m, JSONObject p, int uid) throws Exception {
        switch (m) {
            case "screen.read": return read();
            case "guard.classify": return PromptGuard.classify(p.getString("text"));
            case "list_apps": return ok(true).put("apps", Actions.listApps(svc));
            case "wait":
                Thread.sleep(Math.max(0, Math.min(5000, p.optLong("ms", 1000))));
                return ok(true).put("decision", "auto").put("screen", read());
            case "task.start": return start(p, uid);
            case "task.stop": return stop(p.optString("task_id", ""), "task.stop");
            case "task.pause": return pause(true, "task.pause");
            case "task.resume": return pause(false, "task.resume");
            case "audit.tail": return audit.tail(Math.max(1, Math.min(500, p.optInt("n", 20)))).put("pubkey", Approvals.publicKeyB64(svc));
            case "firewall.list": return Firewall.list(svc);
            case "trackers.scan": return Trackers.scan(svc, p);
            case "trackers.list": return Trackers.list(svc);
            case "notifications.list": return notifications(p);
            case "approval.list": return approvals();
            case "app.mod.list": return ok(true).put("mods", mods.list());
            case "app.mod.targets": return mods.targets();
            case "app.mod.remove": return mods.remove(p.getString("id"), p.optString("by", "agent"));
            case "app.mod.apply": synchronized (actLock) { return mod(p); }
            case "approval.confirm": return confirm(p, uid);
            case "approval.deny": return deny(p);
            case "approval.escalate": return escalate(p, uid);
            case "tap": case "type": case "scroll": case "press": case "open_app": case "intent": case "firewall.set": case "firewall.clear":
            case "trackers.block": case "trackers.unblock": case "notifications.reply":
                synchronized (actLock) { return act(m, p); } // one action at a time; nothing else acts while a card is up
            case "notifications.autoreply":
                if (p.optInt("minutes", 60) > 0) synchronized (actLock) { return act(m, p); }
                if (p.has("text")) Notifs.standing.remove(p.getString("text")); else Notifs.standing.clear(); // stopping needs no approval
                audit.log(new JSONObject().put("task", "").put("tool", m).put("text", p.optString("text", "(all)")).put("decision", "auto").put("reason", "auto-reply stopped"));
                return ok(true).put("decision", "auto").put("reason", "auto-reply stopped");
            default: throw new IllegalArgumentException("unknown method " + m);
        }
    }

    // ------------------------------------------------------------------ tasks

    /**
     * A program asking for a lease gets a one-tap "Allow agent in <apps> for N min" card (unless files/settings.json says
     * require_user_lease:false). A replacement by the same caller inside a still-valid grant for the same apps needs no
     * new card and can't extend it. The same caller within the old TTL also keeps the History (taint, steps, secrets).
     */
    private JSONObject start(JSONObject p, int uid) throws Exception {
        synchronized (startLock) { // one task.start, and one lease card, at a time
            Set<String> apps = new LinkedHashSet<>();
            JSONArray refused = new JSONArray(), a = p.optJSONArray("apps");
            for (int i = 0; a != null && i < a.length(); i++) {
                if (Guard.forbidden(a.getString(i))) refused.put(a.getString(i)); else apps.add(a.getString(i));
            }
            long now = System.currentTimeMillis(), ttl = Math.min(p.optLong("ttl_s", 600), 3600), expires = now + ttl * 1000;
            String caller = who(uid), goal = p.optString("goal");
            Lease prev = last;
            boolean same = prev != null && prev.caller.equals(caller) && now < prev.expires, granted = false;
            if (settings().optBoolean("require_user_lease", true)) {
                if (same && prev.granted && prev.apps.containsAll(apps)) expires = Math.min(expires, prev.expires);
                else {
                    String no = askLease(apps, goal, caller, ttl, Math.max(10, Math.min(120, p.optInt("approval_timeout_s", 60))) * 1000L);
                    if (no != null) {
                        audit.log(new JSONObject().put("task", "").put("tool", "task.start").put("goal", goal).put("apps", new JSONArray(apps))
                                .put("caller", caller).put("decision", "denied").put("reason", no));
                        return ok(false).put("decision", "denied").put("reason", "lease not granted: " + no).put("refused_apps", refused);
                    }
                }
                granted = true;
            }
            Lease l = new Lease(goal, apps, expires, Math.min(p.optInt("max_steps", 40), 200), caller, same ? prev.h : new History());
            l.granted = granted;
            if (p.optBoolean("untrusted")) l.h.untrusted = true;
            synchronized (this) {
                if (lease != null) stop(lease.id, "replaced by a new task.start");
                lease = last = l;
            }
            svc.main.post(svc.autoPause::start);
            audit.log(entry("task.start", l).put("goal", l.goal).put("apps", new JSONArray(apps)).put("refused_apps", refused)
                    .put("caller", caller).put("user_granted", granted).put("carried_over", same));
            Events.emit("task.state", task(l).put("state", "started"));
            island(l, "Starting");
            return ok(true).put("task_id", l.id).put("apps", new JSONArray(apps)).put("expires", l.expires).put("max_steps", l.maxSteps)
                    .put("refused_apps", refused).put("steps", l.h.steps).put("untrusted", l.h.untrusted).put("carried_over", same)
                    .put("user_granted", granted);
        }
    }

    /** Shows the lease card and waits for a real tap. Returns null if allowed, else why not. */
    private String askLease(Set<String> apps, String goal, String caller, long ttlS, long timeoutMs) throws Exception {
        LeaseCard.Model m = new LeaseCard.Model();
        m.apps = labels(apps); m.minutes = String.valueOf(Math.max(1, (ttlS + 59) / 60)); m.goal = goal; m.caller = caller;
        String id = Approvals.hex(8);
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        leaseAsk = f;
        show(id, new JSONObject().put("kind", "lease").put("tool", "task.start").put("target", m.apps).put("apps", new JSONArray(apps))
                .put("goal", goal).put("caller", caller).put("tier", "lease").put("summary", "Allow agent in " + m.apps + " for " + m.minutes + " min")
                .put("allowed_channels", new JSONArray().put("tap")).put("expires", System.currentTimeMillis() + timeoutMs));
        svc.main.post(() -> leaseCard.show(m, new LeaseCard.Listener() {
            @Override public void allow() { f.complete(true); }
            @Override public void deny() { f.complete(false); }
        }));
        Boolean yes = null;
        try { yes = f.get(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) { /* no answer = no */ }
        finally { leaseAsk = null; svc.main.post(leaseCard::hide); }
        String no = yes == null ? "timed out" : yes ? null : "denied on the lease card";
        resolved(id, yes == null ? "timeout" : yes ? "approved" : "denied", yes != null && yes ? "tap" : null, no == null ? "allowed by user" : no);
        return no;
    }

    /** Stop: revoke the lease and deny any open approval. Also used by the volume-key kill switch. */
    synchronized JSONObject stop(String taskId, String why) throws JSONException {
        CompletableFuture<Boolean> ask = leaseAsk;
        if (ask != null && taskId.isEmpty()) ask.complete(false); // the kill switch also answers an open lease card with no
        Lease l = lease;
        Approvals.Pending c = current; // the kill switch (no task id) denies any open card, even a lease-free one (a reply)
        if (c != null && (taskId.isEmpty() || l != null && taskId.equals(l.id) && c.model.task.startsWith(l.id))) c.finish(null, "task stopped");
        if (l == null) return ok(true).put("reason", "no active task");
        if (!taskId.isEmpty() && !taskId.equals(l.id)) return ok(false).put("reason", "task " + taskId + " is not the active task (" + l.id + ")");
        lease = null;
        l.granted = false; // a stopped grant can't be reused by a later task.start
        svc.main.post(svc.autoPause::stop);
        audit.log(entry("task.stop", l).put("reason", why).put("steps", l.h.steps));
        Events.emit("task.state", task(l).put("state", "stopped").put("reason", why));
        svc.main.post(svc.island::hide);
        return ok(true).put("task_id", l.id).put("reason", why);
    }

    private synchronized JSONObject pause(boolean on, String why) throws JSONException {
        Lease l = lease;
        if (l == null) return ok(false).put("reason", "no active task");
        l.paused = on;
        svc.main.post(on ? svc.autoPause::stop : svc.autoPause::start);
        audit.log(entry(on ? "task.pause" : "task.resume", l).put("reason", why));
        Events.emit("task.state", task(l).put("state", on ? "paused" : "running").put("reason", why));
        if (on) svc.main.post(() -> svc.island.set("idle", "Paused", -1)); else island(l, null);
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
        boolean fw = tool.startsWith("firewall.") || tool.startsWith("trackers."), reply = tool.equals("notifications.reply"), free = fw || tool.startsWith("notifications.");
        boolean scoped = !free && !tool.equals("press"); // free: the card itself is the gate (no lease needed)
        AccessibilityNodeInfo node = p.has("id") ? svc.screen.node(p.getInt("id")) : null;
        if (node == null && (tool.equals("tap") || tool.equals("type"))) throw new IllegalArgumentException(tool + " needs an id from screen.read");
        boolean secure = !free && svc.screen.secure();
        Notifs.Rec nr = reply ? Notifs.get(p.optString("key")) : null;
        if (reply && nr == null) throw new IllegalArgumentException("no such notification key (call notifications.list)");
        String text = p.optString("text", ""), pkg, target, why;
        int tier;
        switch (tool) {
            case "tap": {
                String risky = Guard.riskyLabel(node);
                pkg = pkg(node); target = Guard.label(node);
                tier = risky != null || secure ? 3 : 1;
                why = risky != null ? "outward button \"" + risky + "\"" : secure ? "tap on a secure screen (" + svc.screen.secureWhy + ")" : "navigation tap";
                if (risky != null) text = draft(l, pkg); // "Send": the card shows (and the fingerprint signs) the exact message
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
            case "intent": // allow-listed: alarm, timer, web search (Actions.intent). A search is a draft: its words leave the phone
                pkg = Actions.intentPkg(svc, p); target = p.getString("kind") + " " + (p.has("query") ? p.getString("query") : p.has("seconds") ? p.getInt("seconds") + " s"
                        : String.format(java.util.Locale.US, "%02d:%02d", p.optInt("hour"), p.optInt("minute")));
                text = p.optString("query", ""); tier = p.getString("kind").equals("search") ? 2 : 1; why = "direct " + p.getString("kind");
                break;
            case "firewall.set": {
                JSONArray d = p.optJSONArray("domains");
                pkg = p.getString("pkg"); target = p.getString("mode") + " " + (d == null || d.length() == 0 ? "all traffic" : d.join(",").replace("\"", ""));
                tier = 3; why = "network rule change";
                break;
            }
            case "trackers.block": case "trackers.unblock": // protective; only names on the bundled tracker list
                target = Trackers.plan(svc, tool, p); pkg = p.getString("pkg"); tier = 3; why = "tracker block (network rule)";
                break;
            case "notifications.reply":
                pkg = nr.pkg; target = "Reply to " + nr.convo; tier = 3; why = "reply to a message (notification)";
                break;
            case "notifications.autoreply":
                pkg = "local.agentos"; target = "Auto-reply for " + Math.max(1, Math.min(240, p.optInt("minutes", 60))) + " min"; tier = 3; why = "standing auto-reply";
                break;
            default: // firewall.clear
                pkg = "*"; target = "remove all AgentOS firewall rules"; tier = 3; why = "network rule change";
        }

        String block = null;
        if (svc.getSystemService(KeyguardManager.class).isKeyguardLocked()) block = "phone is locked";
        else if (!free && l == null) block = "no active task: call task.start first";
        else if (!free && System.currentTimeMillis() > l.expires) block = "task lease expired";
        else if (!free && l.paused) block = "task paused (human touched the screen or task.pause): call task.resume after re-reading the screen";
        else if (!free && l.h.steps >= l.maxSteps) block = "task reached max_steps " + l.maxSteps;
        else if (!free && p.has("task_id") && !p.getString("task_id").equals(l.id)) block = "task_id does not match the active task";
        else if (tier == 4) block = "T4 forbidden: " + why;
        else if (text.trim().isEmpty() && free && !fw) block = "text is empty";
        else if (reply && nr.reply == null) block = "this notification has no reply action: open the app instead";
        else if ((tool.equals("type") || tool.equals("intent") || free) && (l != null && leaksSecret(l.h, text) || Notifs.leaks(text)))
            block = "T4 forbidden: text contains a code/canary read from the screen or a notification (P3)";
        else if (node != null && Guard.offLimits(node)) block = "T4 forbidden: AgentOS's own window";
        else if (scoped && Guard.forbidden(pkg)) block = "T4 forbidden: " + pkg + " is a protected system surface";
        else if (scoped && !l.apps.contains(pkg)) block = pkg + " is not in this task's lease " + l.apps;

        boolean untrusted = l != null && l.h.untrusted || reply, priv = Guard.privateApp(pkg) || secure || nr != null && nr.test, outward = tier == 3; // a reply answers a stranger's text
        JSONObject r2 = new JSONObject().put("untrusted", untrusted).put("private", priv).put("outward", outward);
        JSONObject log = entry(tool, l).put("pkg", pkg).put("target", target).put("text", Guard.clip(text, 200))
                .put("tier", TIER[tier]).put("rule_of_two", r2);
        if (block != null) return finish(log, ok(false), "blocked", block, null);

        if (tool.equals("type") && copied(l.h, text)) l.h.copied = true; // sticky: later sends need a person, not the reviewer
        if (!free) island(l, stepLabel(tool, pkg, target));
        String decision = "auto";
        if (reply && Notifs.standing(text)) { log.put("approver", "standing auto-reply"); decision = "approved"; } // the user approved this exact text once
        else if (tier == 3 || (tier == 2 && untrusted && priv)) {
            if (tier == 2) why += " after reading untrusted content in a private app (Rule of Two)";
            boolean copy = free && !fw && copied(Notifs.seen, text);
            Object[] pol = channels(l == null ? null : l.h, tool, target, untrusted, priv, outward, copy);
            if (reply && "MALICIOUS".equals(nr.label) && pol[1] == null) pol = new Object[]{HUMAN, "Prompt Guard flagged this message"};
            if (tool.equals("notifications.autoreply")) pol = new Object[]{HUMAN, "a standing grant needs a person"};
            @SuppressWarnings("unchecked") List<String> channels = (List<String>) pol[0];
            Approvals.Pending a = approve(tool, pkg, target, text, l, TIER[tier], why, r2, channels, (String) pol[1],
                    Math.max(10, Math.min(120, p.optInt("approval_timeout_s", 60))) * 1000L);
            log.put("allowed_channels", new JSONArray(a.channels)).put("because", a.because == null ? JSONObject.NULL : a.because);
            if (a.channel.isEmpty()) return finish(log, ok(false), "denied", a.reason, null);
            log.put("approver", a.channel).put("canonical", new String(a.canonical, StandardCharsets.UTF_8));
            if (a.channel.equals("fingerprint"))
                log.put("pubkey_sha256", Audit.hex(Audit.sha256(Base64.decode(Approvals.publicKeyB64(svc), Base64.NO_WRAP))))
                   .put("sig", Base64.encodeToString(a.sig, Base64.NO_WRAP));
            else if (!a.note.isEmpty()) log.put(a.channel.equals("reviewer") ? "rationale" : "utterance", a.note);
            if (node != null && (!node.refresh() || !Guard.label(node).equals(target) || !pkg(node).equals(pkg)
                    || tool.equals("tap") && !draft(l, pkg).equals(text)))
                return finish(log, ok(false), "blocked", "screen changed after approval; nothing done", null);
            decision = "approved";
        }

        if (tool.equals("type")) { l.h.typed.add(norm(text)); l.field = node; }
        boolean done = reply ? Notifs.reply(svc, nr, text) : execute(tool, p, node);
        if (!free && l != null) l.h.steps++;
        JSONObject res = ok(done).put("tier", TIER[tier]).put("rule_of_two", r2);
        if (fw) Events.emit("firewall.changed", new JSONObject().put("tool", tool).put("pkg", pkg).put("target", target).put("ok", done));
        if (free) return finish(log, res, decision, done ? why : "failed", null);
        settle();
        return finish(log, res, decision, done ? why : "Android refused the action", read());
    }

    /**
     * app.mod.apply {spec}: strict schema (Mods.validate: only "press" the app's buttons or "compute" chips), then a person
     * approves on the card (tap or voice; never the reviewer: they should see what is added to their app), audited with the
     * full spec. The app is brought to the front so the mod shows up at once.
     */
    private JSONObject mod(JSONObject p) throws Exception {
        Lease l = lease;
        JSONObject spec = p.optJSONObject("spec");
        String pkg = spec == null ? "" : spec.optString("package"), bad = null;
        try { if (spec == null) throw new IllegalArgumentException("spec is required"); Mods.validate(spec); }
        catch (IllegalArgumentException e) { bad = "bad spec: " + e.getMessage(); }
        if (bad == null && svc.getPackageManager().getLaunchIntentForPackage(pkg) == null) bad = pkg + " is not installed";
        else if (bad == null && mods.count(spec.optString("id")) >= Mods.MAX_MODS) bad = "at most " + Mods.MAX_MODS + " mods: remove one first";
        else if (bad == null && svc.getSystemService(KeyguardManager.class).isKeyguardLocked()) bad = "phone is locked";
        String app = labels(Collections.singleton(pkg)), target = "Add “" + (spec == null ? "" : spec.optString("label")) + "” to " + app;
        JSONObject log = entry("app.mod.apply", l).put("pkg", pkg).put("target", target).put("tier", "T2 UI change");
        if (bad != null) return finish(log, ok(false), "blocked", bad, null);
        String text = mods.describe(spec);
        log.put("id", spec.getString("id")).put("label", spec.getString("label")).put("text", text)
                .put("spec_sha256", Audit.sha256(spec.toString())).put("spec", spec);
        JSONObject r2 = new JSONObject().put("untrusted", l != null && l.h.untrusted).put("private", false).put("outward", false);
        Approvals.Pending a = approve("app.mod.apply", pkg, target, text, l, "T2 UI change", "adds a button to " + app, r2, HUMAN,
                "a person should see what is added to their app", Math.max(10, Math.min(120, p.optInt("approval_timeout_s", 60))) * 1000L);
        log.put("allowed_channels", new JSONArray(a.channels));
        if (a.channel.isEmpty()) return finish(log, ok(false), "denied", a.reason, null);
        log.put("approver", a.channel);
        if (!a.note.isEmpty()) log.put("utterance", a.note);
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        boolean front = root != null && pkg.equals(pkg(root));
        if (front) { /* already there */ }
        else if (pkg.equals("com.google.android.deskclock") && spec.toString().contains("timer")) // a Timers mod: open on that tab
            svc.startActivity(new Intent(android.provider.AlarmClock.ACTION_SHOW_TIMERS).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        else Actions.openApp(svc, pkg);
        JSONObject r = mods.apply(spec, l == null ? "agent" : l.id, front ? 1500 : 3000);
        log.put("shown", r.getBoolean("shown"));
        JSONObject res = ok(true).put("id", r.get("id")).put("shown", r.get("shown")).put("app", app);
        if (r.has("ms")) res.put("ms", r.get("ms")); else res.put("note", r.get("note"));
        return finish(log, res, "approved", "mod added", null);
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
            case "intent": return Actions.startIntent(svc, p);
            case "firewall.set": Firewall.set(svc, p.getString("pkg"), p.getString("mode"), p.optJSONArray("domains")); return true;
            case "trackers.block": case "trackers.unblock": Trackers.block(svc, tool, p); return true;
            case "notifications.autoreply": Notifs.standing.put(p.getString("text"), System.currentTimeMillis() + Math.max(1, Math.min(240, p.optInt("minutes", 60))) * 60_000L); return true;
            default: Firewall.clear(); return true;
        }
    }

    /** Show the card, wait (approval_timeout_s, default 60 s) for a tap / fingerprint / voice / reviewer, Deny or Stop. */
    private Approvals.Pending approve(String tool, String pkg, String target, String text, Lease l, String tier, String why, JSONObject r2,
            List<String> channels, String fp, long timeoutMs) throws Exception {
        ApprovalCard.Model m = new ApprovalCard.Model();
        m.title = tool.equals("type") ? "type into \"" + target + "\"" : tool.equals("tap") ? "tap \"" + target + "\""
                : tool.startsWith("notifications.") || tool.startsWith("trackers.") || tool.startsWith("app.") ? target : tool + " " + target;
        m.app = pkg; m.target = target; m.text = text; m.tier = tier; m.reason = why;
        m.task = l == null ? "(no task)" : l.id + " · " + l.goal;
        m.untrusted = r2.getBoolean("untrusted"); m.priv = r2.getBoolean("private"); m.outward = r2.getBoolean("outward");
        Approvals.Pending a = new Approvals.Pending(
                Approvals.canonical(tool, pkg, target, text, l == null ? "" : l.id, Approvals.hex(16)), m, channels);
        long[] shownAt = {0};
        ApprovalCard.Listener ui = new ApprovalCard.Listener() {
            @Override public void approve() {
                if (!HumanTouch.settled(shownAt[0])) return; // a tap already in flight when the card appeared isn't a decision
                if (a.confirm("tap", "")) return;             // ordinary action: the real tap is the approval
                svc.card.hide(); // the fingerprint prompt must not be covered by our overlay
                svc.startActivity(new Intent(svc, ApproveActivity.class).putExtra("id", a.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            @Override public void deny() { a.finish(null, "denied by user"); }
        };
        a.card = () -> { if (a.done.getCount() > 0) { shownAt[0] = SystemClock.uptimeMillis(); svc.card.show(m, ui); } }; // channelsFor(m) picks up an escalation
        a.because = fp;
        Approvals.pending.put(a.id, a);
        current = a;
        show(a.id, new JSONObject().put("kind", "action").put("task_id", l == null ? "" : l.id).put("tool", tool).put("pkg", pkg)
                .put("target", target).put("text", text).put("tier", tier).put("reason", why).put("rule_of_two", r2)
                .put("summary", m.title + " in " + pkg).put("allowed_channels", new JSONArray(channels))
                .put("fingerprint_because", channels.equals(FINGERPRINT_ONLY) ? fp : JSONObject.NULL)
                .put("held_because", fp != null && !channels.equals(FINGERPRINT_ONLY) ? fp : JSONObject.NULL).put("expires", System.currentTimeMillis() + timeoutMs));
        svc.main.post(() -> { svc.autoPause.stop(); a.card.run(); });
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
        if (a.channel.equals("fingerprint") && !Approvals.verify(svc, a.canonical, a.sig)) { a.channel = ""; a.reason = "fingerprint signature did not verify"; }
        boolean yes = !a.channel.isEmpty();
        resolved(a.id, yes ? "approved" : a.reason.equals("timed out") ? "timeout" : "denied",
                yes ? a.channel : a.reason.startsWith("denied") ? "user" : null, a.reason);
        if (a.channel.equals("reviewer")) svc.main.post(() -> svc.card.autoApproved(m.title, a.note));
        return a;
    }

    /**
     * approval.confirm {approval_id, channel: "voice"|"reviewer", utterance | rationale}. Voice: agentd (the user's own
     * speech, never model output) or the launcher. Reviewer: agentd only. A tap and the fingerprint only count on the
     * phone's own card. The channel must be in the approval's allowed_channels.
     */
    private JSONObject confirm(JSONObject p, int uid) throws JSONException {
        String ch = p.optString("channel"), words = p.optString(ch.equals("reviewer") ? "rationale" : "utterance").trim();
        Approvals.Pending a = Approvals.pending.get(p.optString("approval_id"));
        String no = a == null ? "no such pending approval"
                : !ch.equals("voice") && !ch.equals("reviewer") ? "channel must be voice or reviewer (tap and fingerprint happen on the phone's card)"
                : !callerIn(uid, ch + "_callers") ? "caller " + who(uid) + " may not approve by " + ch
                : words.isEmpty() ? (ch.equals("voice") ? "utterance" : "rationale") + " is required"
                : !a.confirm(ch, Guard.clip(words, 500)) ? "this approval accepts only " + a.channels + " (or was already decided)" : null;
        return no == null ? ok(true).put("approval_id", a.id).put("channel", ch) : ok(false).put("reason", no);
    }

    /** approval.deny {approval_id, channel?, utterance?}: anyone may say no; denying can't cause harm. */
    private JSONObject deny(JSONObject p) throws JSONException {
        Approvals.Pending a = Approvals.pending.get(p.optString("approval_id"));
        if (a == null) return ok(false).put("reason", "no such pending approval");
        a.finish(null, "denied by " + p.optString("channel", "caller"));
        return ok(true).put("approval_id", a.id);
    }

    /**
     * approval.escalate {approval_id, to: "fingerprint", rationale}: agentd's reviewer asks for a fingerprint on an ordinary
     * approval. It can only make the card stricter, so any reviewer caller may ask. The card is redrawn and approval.shown
     * re-sent (with escalated:true) so every mirror shows "Fingerprint required".
     */
    private JSONObject escalate(JSONObject p, int uid) throws JSONException {
        Approvals.Pending a = Approvals.pending.get(p.optString("approval_id"));
        String why = "reviewer: " + Guard.clip(p.optString("rationale", "asked for a fingerprint"), 300);
        String no = a == null ? "no such pending approval"
                : !"fingerprint".equals(p.optString("to", "fingerprint")) ? "to must be fingerprint"
                : !callerIn(uid, "reviewer_callers") ? "caller " + who(uid) + " may not escalate"
                : !a.escalate(why) ? "already decided" : null;
        if (no != null) return ok(false).put("reason", no);
        JSONObject info = shown.get(a.id);
        if (info != null && why.equals(a.because)) { // re-announce only when this call changed it
            info.put("allowed_channels", new JSONArray(a.channels)).put("fingerprint_because", why).put("escalated", true);
            Events.emit("approval.shown", info);
            Runnable card = a.card;
            if (card != null) svc.main.post(card);
        }
        return ok(true).put("approval_id", a.id).put("allowed_channels", new JSONArray(a.channels)).put("fingerprint_because", a.because);
    }

    /** approval.list, and the hello event on /events: open approvals and the current task. */
    JSONObject approvals() throws JSONException {
        Lease l = lease;
        return ok(true).put("approvals", new JSONArray(shown.values())).put("task", l == null ? JSONObject.NULL : task(l));
    }

    private void show(String id, JSONObject info) throws JSONException {
        shown.put(id, info.put("approval_id", id));
        Events.emit("approval.shown", info);
    }

    private void resolved(String id, String decision, String approver, String reason) throws JSONException {
        JSONObject info = shown.remove(id);
        Events.emit("approval.resolved", new JSONObject().put("approval_id", id).put("kind", info == null ? "" : info.optString("kind"))
                .put("task_id", info == null ? "" : info.optString("task_id")).put("tool", info == null ? "" : info.optString("tool"))
                .put("decision", decision).put("approver", approver == null ? JSONObject.NULL : approver).put("channel", approver == null ? JSONObject.NULL : approver).put("reason", reason));
    }

    // ---------------------------------------------------------------- helpers

    private JSONObject read() throws JSONException {
        JSONObject s = svc.screen.read(svc);
        Lease l = lease;
        if (l != null) {
            History h = l.h;
            JSONArray nodes = s.getJSONArray("nodes");
            if (nodes.length() > 0) h.untrusted = true; // the task has now seen third-party content
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject n = nodes.getJSONObject(i);
                java.util.regex.Matcher m = SECRET.matcher(n.optString("text") + " " + n.optString("desc"));
                while (m.find()) h.secrets.add(m.group().replaceAll("[ -]", ""));
                if (!n.optString("src").startsWith("APP:") || n.optJSONArray("actions").toString().contains("edit")) continue; // drafts aren't third-party
                for (String t : new String[]{norm(n.optString("text")), norm(n.optString("desc"))})
                    if (t.length() >= 3 && h.seen.size() < 4000 && !h.typed.contains(t)) h.seen.add(t);
            }
        }
        PromptGuard.annotate(s); // every untrusted node gets a Prompt Guard score
        JSONObject g = s.getJSONObject("guard");
        if ("MALICIOUS".equals(g.optString("label"))) {
            if (l != null) l.h.flagged = true;
            JSONArray ids = g.optJSONArray("flagged_node_ids");
            JSONObject n = ids == null || ids.length() == 0 ? null : s.optJSONArray("nodes").optJSONObject(ids.optInt(0));
            flag(l, s.optString("pkg"), ids == null || ids.length() == 0 ? "" : sender(ids.optInt(0)), n == null ? "" : n.optString("text", n.optString("desc")),
                    g.opt("score"), ids);
        }
        return s.put("ok", true);
    }

    /** A Prompt Guard hit: audited and sent as guard.flag (the launcher's log shows it). No pop-up (founder, 10-27). */
    private void flag(Lease l, String pkg, String sender, String snippet, Object score, JSONArray ids) throws JSONException {
        JSONObject e = new JSONObject().put("task_id", l == null ? "" : l.id).put("pkg", pkg).put("sender", sender)
                .put("snippet", Guard.clip(snippet, 160)).put("score", score).put("node_ids", ids == null ? new JSONArray() : ids);
        Events.emit("guard.flag", e);
        audit.log(new JSONObject().put("task", l == null ? "" : l.id).put("tool", "guard.flag").put("pkg", pkg).put("sender", sender)
                .put("text", Guard.clip(snippet, 160)).put("score", score).put("decision", "flagged").put("reason", "Prompt Guard: likely prompt injection"));
    }

    /** Notifs: a message that arrived flagged. */
    static void flagged(Notifs.Rec r) throws JSONException {
        Broker b = self;
        if (b != null) b.flag(null, r.pkg, r.sender, r.text, r.score, null);
    }

    /** notifications.list {n}: recent notifications (untrusted) + the foreground app. Reading them taints the task like screen text. */
    private JSONObject notifications(JSONObject p) throws Exception {
        JSONArray a = Notifs.list(Math.max(1, Math.min(Notifs.MAX, p.optInt("n", 20))));
        Lease l = lease;
        for (int i = 0; l != null && i < a.length(); i++) {
            JSONObject n = a.getJSONObject(i);
            l.h.untrusted = true;
            if (!n.optBoolean("from_me")) l.h.seen.add(norm(n.optString("text")));
            if ("MALICIOUS".equals(n.getJSONObject("guard").optString("label"))) l.h.flagged = true;
        }
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        String fg = root == null ? "" : pkg(root);
        return ok(true).put("listener", Notifs.connected).put("notifications", a)
                .put("foreground", new JSONObject().put("pkg", fg).put("app", fg.isEmpty() ? "" : labels(Collections.singleton(fg))));
    }

    /** Who wrote node id: a short label before it in the same group (a chat row's name), else "". */
    private String sender(int id) {
        try {
            AccessibilityNodeInfo n = svc.screen.node(id), p = n.getParent();
            for (int i = 0; p != null && i < p.getChildCount(); i++) {
                AccessibilityNodeInfo c = p.getChild(i);
                if (c == null || c.equals(n)) break;
                CharSequence t = c.getText();
                if (t != null && t.length() > 0 && t.length() <= 40) return t.toString();
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** The island follows the task: "working", the current step, and the share of max_steps used. */
    private void island(Lease l, String label) {
        if (label != null) step = label;
        String st = step;
        int pct = Math.min(100, l.h.steps * 100 / Math.max(1, l.maxSteps));
        svc.main.post(() -> { svc.island.goal = l.goal; svc.island.set("working", st, pct); });
    }

    private String stepLabel(String tool, String pkg, String target) {
        switch (tool) {
            case "tap": return "Tapping “" + Guard.clip(target, 24) + "”";
            case "type": return "Typing in " + labels(Collections.singleton(pkg));
            case "open_app": return "Opening " + labels(Collections.singleton(pkg));
            case "intent": return "Setting " + Guard.clip(target, 24);
            case "press": return "Pressing " + target;
            default: return "Scrolling";
        }
    }

    /** The live text of the field this task last typed into, else the screen's only text field (a draft left by an earlier task), same app; "" if none. */
    private String draft(Lease l, String pkg) {
        AccessibilityNodeInfo f = l == null ? null : l.field;
        if (f == null || !f.refresh()) { List<AccessibilityNodeInfo> e = new ArrayList<>(); editables(svc.getRootInActiveWindow(), e); f = e.size() == 1 ? e.get(0) : null; }
        if (f == null || f.isShowingHintText() || !pkg(f).equals(pkg)) return "";
        CharSequence t = f.getText();
        return t == null ? "" : t.toString();
    }

    /** Collects editable nodes under n, stopping at 2 (callers only need "exactly one"). */
    private static void editables(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        if (n == null || out.size() > 1) return;
        if (n.isEditable()) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) editables(n.getChild(i), out);
    }

    private static boolean leaksSecret(History h, String text) {
        String flat = text.replaceAll("[ -]", "");
        synchronized (h.secrets) { for (String x : h.secrets) if (flat.contains(x.replaceAll("[ -]", ""))) return true; }
        return false;
    }

    /** Is this text lifted from third-party screen text: the same string, or a 12+ character piece of one (either way)? */
    private static boolean copied(History h, String text) { return copied(h.seen, text); }

    private static boolean copied(Set<String> seen, String text) {
        String t = norm(text);
        synchronized (seen) {
            for (String x : seen)
                if (t.equals(x) || x.length() >= 12 && t.contains(x) || t.length() >= 12 && x.contains(t)) return true;
        }
        return false;
    }

    static String norm(String s) { return s.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim(); }

    /** Root-only switches in files/settings.json (no RPC writes it). Missing or broken file = the safe defaults. */
    private JSONObject settings() {
        try { return new JSONObject(new String(Files.readAllBytes(new File(svc.getFilesDir(), "settings.json").toPath()), StandardCharsets.UTF_8)); }
        catch (Exception e) { return new JSONObject(); }
    }

    /** Is the calling app on the allow-list (settings key, else the default: agentd's Termux; plus the launcher for voice)? */
    private boolean callerIn(int uid, String key) {
        JSONArray allowed = settings().optJSONArray(key);
        List<String> list = new java.util.ArrayList<>(Arrays.asList("com.termux"));
        if (key.equals("voice_callers")) list.add("local.agentos.home");
        if (allowed != null) { list.clear(); for (int i = 0; i < allowed.length(); i++) list.add(allowed.optString(i)); }
        String[] pkgs = uid < 0 ? null : svc.getPackageManager().getPackagesForUid(uid);
        if (pkgs != null) for (String x : pkgs) if (list.contains(x)) return true;
        return false;
    }

    private String who(int uid) { return uid < 0 ? "unknown" : svc.getPackageManager().getNameForUid(uid) + " (uid " + uid + ")"; }

    private String labels(Set<String> pkgs) {
        StringBuilder b = new StringBuilder();
        for (String x : pkgs) {
            CharSequence name = x;
            try { name = svc.getPackageManager().getApplicationLabel(svc.getPackageManager().getApplicationInfo(x, 0)); } catch (Exception ignored) {}
            b.append(b.length() > 0 ? ", " : "").append(name);
        }
        return b.length() == 0 ? "no apps" : b.toString();
    }

    private static JSONObject task(Lease l) throws JSONException {
        return new JSONObject().put("task_id", l.id).put("goal", l.goal).put("apps", new JSONArray(l.apps)).put("expires", l.expires)
                .put("steps", l.h.steps).put("max_steps", l.maxSteps).put("untrusted", l.h.untrusted).put("flagged", l.h.flagged)
                .put("paused", l.paused).put("caller", l.caller);
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
