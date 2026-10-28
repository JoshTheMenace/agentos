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
        /** asked: the founder's own request via agentd (voice, typed, or a schedule they made). relock: we unlocked the phone for it. */
        volatile boolean asked, relock;
        volatile String origin = "";
        /** remote: a paired assistant ("Grok") the owner let unlock the phone ("Remote can unlock"). unlockedFor: set once we opened it for that assistant. */
        volatile String remote, unlockedFor;
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
        if (tool.equals("trackers.unblock")) return new Object[]{HUMAN, "undoing a protection needs a person"};
        if (tool.matches("sms\\.send|call\\.place|calendar\\.add")) return new Object[]{HUMAN, "a text, call or invite to a person needs a person"}; // never the reviewer
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
    final DeepMods deepMods;
    /** Saved passwords (Vault.java): filled behind a fingerprint, never shown to the model. */
    final Vault vault;
    /** Open approvals as announced on /events (approval_id -> approval.shown payload): approval.list returns these. */
    private final Map<String, JSONObject> shown = new ConcurrentHashMap<>();
    private volatile Lease lease, last; // last: the most recent lease, even after stop (for carry-over)
    private volatile Approvals.Pending current;
    private volatile CompletableFuture<Boolean> leaseAsk;
    /** The island pill's step label. */
    private volatile String step = "Starting";
    private static volatile Broker self; // for Notifs.flagged, Vault
    /** VaultAddActivity (same process): the vault, or null while the service is off. */
    static Vault vault() { Broker b = self; return b == null ? null : b.vault; }
    /** A card is up or an agent task is running: the vault's sign-in offer stays out of the way. */
    static boolean busy() { Broker b = self; return b == null || b.current != null || b.leaseAsk != null || b.lease != null; }
    /** Apps whose lease always needs the card (money, installs, passwords), even when the user asked by name. */
    private static final java.util.regex.Pattern SENSITIVE = java.util.regex.Pattern.compile(
            "(?i)(bank|pay|wallet|finance|credit|crypto|trading|broker|vending|packageinstaller|password|vault|bitwarden|lastpass|keepass|authenticator)");

    Broker(AgentA11y svc) {
        this.svc = svc;
        leaseCard = new LeaseCard(svc);
        audit = new Audit(svc.getFilesDir());
        mods = new Mods(svc, audit);
        deepMods = new DeepMods(svc, audit);
        vault = new Vault(svc, audit);
        PromptGuard.init(svc);
        Trackers.start(svc);
        Wake.listen(svc);
        Api.init(svc);
        Plugins.init(svc);
        self = this;
    }

    /** uid: the calling app's uid for task.start and approval.confirm (-1 if unknown or not looked up). */
    JSONObject call(String m, JSONObject p, int uid) throws Exception {
        String plugin = Plugins.off(m, p); // a switched-off plugin's methods: refused for agents; the owner's own screens and adb still work
        if (plugin != null && !m.matches("app\\.deepmod\\.(remove|disable|rollback)") && uid != 2000 && !callerIn(uid, "owner_callers")) {
            String why = "plugin " + plugin + " is turned off by the owner";
            audit.log(entry(m, lease).put("caller", who(uid)).put("decision", "refused").put("reason", why));
            return ok(false).put("decision", "refused").put("reason", why);
        }
        if (m.startsWith("app.deepmod.")) return deepmod(m, p, uid);
        if (AppReads.app(m) != null) synchronized (actLock) {
            String pkg = AppReads.PACKAGES.get(AppReads.app(m));
            JSONObject out = AppReads.read(svc, m, p), log = entry(m, lease).put("pkg", pkg).put("tier", "T0 read");
            return finish(log, absorb(lease, new Api.Spec(pkg, 0, true, true, "Checking app updates"), out, ok(true), log), "auto", "background read only", null);
        }
        if (Api.SPECS.containsKey(m)) synchronized (actLock) { return act(m, p, uid); } // Android's own APIs (Api.java): the same gate as a tap
        switch (m) {
            case "screen.read": return read();
            case "screen.capture": synchronized (actLock) { return Look.capture(svc, audit, p, lease != null, who(uid)); } // voice: one private screenshot (Look.java)
            case "guard.classify": return PromptGuard.classify(p.getString("text"));
            case "guard.screen": return PromptGuard.annotate(new JSONObject().put("nodes", p.getJSONArray("nodes"))); // test hook: android/guard-tests
            case "list_apps": return ok(true).put("apps", Actions.listApps(svc));
            case "wait":
                Thread.sleep(Math.max(0, Math.min(5000, p.optLong("ms", 1000))));
                return ok(true).put("decision", "auto").put("screen", read());
            case "task.start": return start(p, uid);
            case "task.stop": return stop(p.optString("task_id", ""), "task.stop", p.optLong("done_ms", 0));
            case "task.pause": return pause(true, "task.pause");
            case "task.resume": return pause(false, "task.resume");
            case "audit.tail": return audit.tail(Math.max(1, Math.min(500, p.optInt("n", 20)))).put("pubkey", Approvals.publicKeyB64(svc));
            case "audit.mark_breaks": // adb only: names today's chain breaks in one new line; history is never rewritten
                if (uid != 2000) return ok(false).put("reason", "audit.mark_breaks only over adb, not from " + who(uid));
                return audit.markBreaks(p.optString("reason", "marked by the operator over adb"));
            case "audit.demo_reset": { // owner (long-press "Protected today") or adb: one demo.reset line; the launcher counts from it, the chain stays whole
                if (uid != 2000 && !callerIn(uid, "owner_callers")) return ok(false).put("reason", "audit.demo_reset only from the launcher or adb, not from " + who(uid));
                JSONObject e = new JSONObject().put("task", "").put("tool", "demo.reset").put("decision", "auto").put("reason", uid == 2000 ? "operator over adb" : "owner (command center)");
                audit.log(e);
                return ok(true).put("since", e.getLong("ts"));
            }
            case "firewall.list": return Firewall.list(svc);
            case "trackers.scan": return Trackers.scan(svc, p);
            case "trackers.list": return Trackers.list(svc);
            case "notifications.list": return notifications(p);
            case "notifications.conversations": case "notifications.conversation": case "notifications.changes":
                {
                    JSONObject log = entry(m, lease).put("tier", "T0 read");
                    return finish(log, absorb(lease, new Api.Spec("notifications", 0, true, true, "Reading live messages"),
                            NotificationFeed.read(m, p), ok(true), log), "auto", "delivered notifications only", null);
                }
            case "approval.list": return approvals();
            case "app.mod.list": return ok(true).put("mods", mods.list());
            case "bar.rect": { // the launcher's assistant bar: its window rect, or {} once closed. App-mod masks stop at its top edge (Mods.clip).
                if (uid != 2000 && !callerIn(uid, "owner_callers")) return ok(false).put("reason", "bar.rect only from the launcher or adb, not from " + who(uid));
                mods.bar(p.has("top") ? new android.graphics.Rect(p.getInt("left"), p.getInt("top"), p.getInt("right"), p.getInt("bottom")) : null);
                return ok(true);
            }
            case "app.mod.targets": return mods.targets();
            case "app.mod.preview": return mods.preview(p.getJSONObject("spec"));
            case "app.mod.disable": return mods.disable(p.optString("package"), "kill switch");
            case "app.mod.remove": return mods.remove(p.getString("id"), p.optString("by", "agent"));
            case "app.mod.apply": synchronized (actLock) { return mod(p, uid); }
            case "approval.confirm": return confirm(p, uid);
            case "vault.list": return vault.list();
            case "vault.fill":
                if (lease != null && lease.unlockedFor != null) return ok(false).put("decision", "blocked").put("reason", "no saved passwords while the phone is unlocked for " + lease.unlockedFor);
                synchronized (actLock) { return vault.fill(p, lease); }
            case "vault.add": // adb only (shell uid 2000) or the owner's native screen (VaultAddActivity, in-process); never agentd or a model tool
                if (uid != 2000) return ok(false).put("reason", "vault.add only over adb or the Passwords screen, not from " + who(uid));
                return vault.add(p, "operator over adb");
            case "vault.remove": // adb or the launcher's own uid (owner line); never agentd or a model tool
                if (uid != 2000 && !callerIn(uid, "owner_callers")) return ok(false).put("reason", "vault.remove only from the Passwords screen or adb, not from " + who(uid));
                return vault.remove(p.getString("id"), uid == 2000 ? "operator over adb" : "owner (Passwords screen)");
            case "plugins.list": return Plugins.list(); // anyone may read the switches (agentd hides off plugins from the model)
            case "plugins.set": // the owner's Plugins screen (launcher uid) or adb; never agentd or a model tool
                if (uid != 2000 && !callerIn(uid, "owner_callers")) return ok(false).put("reason", "plugins.set only from the Plugins screen or adb, not from " + who(uid));
                return Plugins.set(p.getString("id"), p.optBoolean("on"), uid == 2000 ? "operator over adb" : "owner (Plugins screen)", audit);
            case "approval.deny": return deny(p);
            case "schedule.alarm": return schedule(p, uid);
            case "remote.read": { // agentd (mcp-remote.js): a paired outside assistant read phone_brief or memory_lookup; one chain line, no card
                if (!callerIn(uid, "lease_callers")) return ok(false).put("reason", "remote.read only from agentd, not " + who(uid));
                audit.log(new JSONObject().put("task", "remote").put("tool", "remote.read").put("from", Guard.clip(p.optString("from"), 20))
                        .put("target", Guard.clip(p.optString("tool"), 30)).put("decision", "auto").put("reason", "paired assistant, reads on"));
                return ok(true);
            }
            case "image.sent": { // agentd: a camera frame or photo went to a model (voice eyes, typed chat); one chain line, no card
                if (!callerIn(uid, "lease_callers")) return ok(false).put("reason", "image.sent only from agentd, not " + who(uid));
                audit.log(new JSONObject().put("task", "").put("tool", "image.sent").put("target", Guard.clip(p.optString("source"), 10))
                        .put("model", Guard.clip(p.optString("model"), 30)).put("bytes", p.optInt("bytes")).put("decision", "auto").put("reason", "user's own picture"));
                return ok(true);
            }
            case "approval.escalate": return escalate(p, uid);
            case "firewall.set": case "firewall.clear": case "trackers.block": case "trackers.unblock": // owner settings (Blockers taps) or adb; never agentd or a model (founder 10-28)
                if (uid != 2000 && !callerIn(uid, "owner_callers")) {
                    String why = m + " only from the owner's Blockers screen or adb, not from " + who(uid);
                    audit.log(entry(m, lease).put("caller", who(uid)).put("decision", "refused").put("reason", why));
                    return ok(false).put("decision", "refused").put("reason", why);
                }
                synchronized (actLock) { return act(m, p, uid); }
            case "tap": case "type": case "scroll": case "press": case "open_app": case "intent": case "notifications.reply": case "notifications.mark_read":
                synchronized (actLock) { return act(m, p, uid); } // one action at a time; nothing else acts while a card is up
            case "notifications.autoreply":
                if (p.optInt("minutes", 60) > 0) synchronized (actLock) { return act(m, p, uid); }
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
            // The user's own words (typed or spoken to agentd) are the grant for ordinary apps. Still a card: money,
            // installs and passwords, a running task that Prompt Guard flagged asking for more apps (an injected agent
            // widening its scope), other callers. A new request after a flagged task is the user's again (sends stay human).
            boolean plain = callerIn(uid, "lease_callers") && !(same && lease != null && prev.h.flagged) && apps.stream().noneMatch(x -> SENSITIVE.matcher(x).find());
            boolean asked = plain && p.optBoolean("user_asked");
            // A paired remote assistant (Grok/Dots via agentd, founder 10-28) also needs no lease card for ordinary apps. It stays untrusted
            // and not "asked": it can't unlock the phone or skip a mod card, and every outward step still waits for a person (reviewer holds it).
            boolean remote = plain && !asked && "remote".equals(p.optString("origin")) && p.optBoolean("untrusted");
            if (asked || remote) granted = true;
            else if (settings().optBoolean("require_user_lease", true)) {
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
            l.granted = granted; l.asked = asked; l.origin = p.optString("origin");
            // "Remote can unlock" (founder 10-28, owner switch in agentd's remote.json, default off): a paired assistant's clean task may open
            // a locked phone like the owner's schedule. Never for a goal about money or installs (those need the fingerprint anyway).
            if (remote && p.optBoolean("remote_unlock") && !MONEY.matcher(goal).find()) l.remote = Guard.clip(p.optString("remote", "Remote"), 20);
            if (p.optBoolean("tainted")) l.h.flagged = true; // agentd's own guard flagged text in this turn: it can only add taint
            if (same && lease == null) l.h.steps = 0; // a new task after the old one stopped: keep the taint, reset the count
            if (p.optBoolean("untrusted")) l.h.untrusted = true;
            synchronized (this) {
                if (lease != null) { l.relock = lease.relock; if (!l.asked) l.unlockedFor = lease.unlockedFor; stop(lease.id, "replaced by a new task.start"); }
                lease = last = l;
            }
            svc.main.post(svc.autoPause::start);
            audit.log(entry("task.start", l).put("goal", l.goal).put("apps", new JSONArray(apps)).put("refused_apps", refused)
                    .put("caller", caller).put("user_granted", granted).put("by_request", asked).put("by_paired_remote", remote).put("carried_over", same));
            Events.emit("task.state", task(l).put("state", "started"));
            island(l, "Starting");
            awake(l); // a scheduled task on a locked phone: open it now, so the first screen read is the real one
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
    synchronized JSONObject stop(String taskId, String why) throws JSONException { return stop(taskId, why, 0); }

    /** doneMs > 0: the task finished well in that many ms; the island shows "Done · 6.8 s" for 3 s (plain "Done" past 30 s). */
    synchronized JSONObject stop(String taskId, String why, long doneMs) throws JSONException {
        CompletableFuture<Boolean> ask = leaseAsk;
        if (ask != null && taskId.isEmpty()) ask.complete(false); // the kill switch also answers an open lease card with no
        Lease l = lease;
        Approvals.Pending c = current; // the kill switch (no task id) denies any open card, even a lease-free one (a reply)
        if (c != null && (taskId.isEmpty() || l != null && taskId.equals(l.id) && c.model.task.startsWith(l.id))) c.finish(null, "task stopped");
        if (l == null) return ok(true).put("reason", "no active task");
        if (!taskId.isEmpty() && !taskId.equals(l.id)) return ok(false).put("reason", "task " + taskId + " is not the active task (" + l.id + ")");
        lease = null;
        l.granted = false; // a stopped grant can't be reused by a later task.start
        if (!why.startsWith("replaced")) Wake.done(l.relock); // a replacement keeps the phone open (it inherits relock)
        svc.main.post(svc.autoPause::stop);
        audit.log(entry("task.stop", l).put("reason", why).put("steps", l.h.steps));
        Events.emit("task.state", task(l).put("state", "stopped").put("reason", why));
        if (doneMs > 0) svc.main.post(() -> svc.island.set("done", doneMs < 30_000 ? String.format(java.util.Locale.US, "Done · %.1f s", doneMs / 1000.0) : "Done", -1));
        else svc.main.post(svc.island::hide);
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
        l.relock = false; // the owner has the phone in hand: don't lock it on them when the task ends
        try { pause(true, "auto-pause: a human touched the screen"); } catch (JSONException ignored) {}
    }

    // ---------------------------------------------------------------- actions

    /** uid: the caller (only looked up for trackers.*): the launcher's own uid is the owner's tap, which needs no card. */
    private JSONObject act(String tool, JSONObject p, int uid) throws Exception {
        Lease l = lease;
        Api.Spec api = Api.SPECS.get(tool);
        boolean fw = tool.startsWith("firewall.") || tool.startsWith("trackers."), reply = tool.equals("notifications.reply"), free = fw || tool.startsWith("notifications.");
        // Tracker blocks: the owner's tap in Blockers (launcher uid) is the approval. Agents can't reach these (call()); over adb a block is
        // protective (tracker-list names only, the app keeps working) so it needs no card, an unblock needs a person (channels()).
        boolean owner = tool.startsWith("trackers.") && callerIn(uid, "owner_callers"), protective = tool.equals("trackers.block");
        boolean scoped = !free && !tool.equals("press"); // free: the card itself is the gate (no lease needed)
        AccessibilityNodeInfo node = api == null && p.has("id") ? svc.screen.node(p.getInt("id")) : null; // an API id is an event/alarm id
        if (node == null && (tool.equals("tap") || tool.equals("type"))) throw new IllegalArgumentException(tool + " needs an id from screen.read");
        boolean secure = !free && api == null && svc.screen.secure();
        boolean notificationAction = reply || tool.equals("notifications.mark_read");
        Notifs.Rec nr = notificationAction ? Notifs.get(p.optString("key")) : null;
        if (notificationAction && nr == null) throw new IllegalArgumentException("no such notification key (read live conversations again)");
        String text = p.optString("text", ""), pkg, target, why;
        int tier;
        if (api != null) { // scope = the lease name ("calendar", "sms", ...); describe() resolves a contact to the number the card shows
            String[] d = Api.describe(svc, tool, p);
            pkg = api.scope; target = d[0]; text = d[1]; tier = Api.tier(tool, p); why = "API " + tool;
            if (tool.equals("device.set") && l != null && "remote".equals(l.origin) && (p.has("wifi") || p.has("bluetooth") || p.has("airplane"))) {
                tier = 3; why = "radio change from a remote assistant (it could cut the phone off)"; } // torch, volume, DND stay card-free
        } else switch (tool) {
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
                pkg = node != null ? pkg(node) : svc.screen.pkgNow(); target = (node != null ? Guard.label(node) + " " : "screen ") + p.optString("dir", "down");
                tier = 1; why = "scroll";
                break;
            case "press":
                pkg = svc.screen.pkgNow(); target = p.optString("key"); tier = 1; why = "system key";
                break;
            case "open_app":
                pkg = p.getString("pkg"); target = "launch app"; tier = 1; why = "open app in lease";
                break;
            case "intent": // allow-listed: alarm, timer, web search, calendar draft, play music (Actions.intent). Words that leave the phone or go into an app are a draft
                pkg = Actions.intentPkg(svc, p); target = p.getString("kind") + " " + (p.has("query") ? p.getString("query") : p.has("title") ? p.getString("title") + " " + p.getString("start")
                        : p.has("url") ? p.getString("url") : p.has("to") ? p.getString("to") : p.has("seconds") ? p.getInt("seconds") + " s" : String.format(java.util.Locale.US, "%02d:%02d", p.optInt("hour"), p.optInt("minute")));
                text = String.join(" ", p.optString("query"), p.optString("title"), p.optString("url"), p.optString("to"), p.optString("subject"), p.optString("body")).trim();
                tier = p.getString("kind").matches("alarm|timer") ? 1 : 2; why = "direct " + p.getString("kind");
                if (p.getString("kind").equals("url") && linked(l, p.getString("url"))) { tier = 3; why = "a link that came from text the agent read"; } // a slipped-in link: a person looks first
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
            case "notifications.mark_read":
                pkg = nr.pkg; target = "Mark read: " + nr.convo; tier = 3; why = "app's mark-as-read action (may send a read receipt)";
                break;
            case "notifications.autoreply":
                pkg = "local.agentos"; target = "Auto-reply for " + Math.max(1, Math.min(240, p.optInt("minutes", 60))) + " min"; tier = 3; why = "standing auto-reply";
                break;
            default: // firewall.clear
                pkg = "*"; target = "remove all AgentOS firewall rules"; tier = 3; why = "network rule change";
        }

        if (p.optBoolean("tainted") && l != null) l.h.flagged = true; // agentd's own guard flagged text in this turn
        String block = awake(l);
        if (block != null) { /* locked and not eligible (or the unlock failed) */ }
        else if (!free && l == null) block = "no active task: call task.start first";
        else if (!free && System.currentTimeMillis() > l.expires) block = "task lease expired";
        else if (!free && l.paused) block = "task paused (human touched the screen or task.pause): call task.resume after re-reading the screen";
        else if (!free && l.h.steps >= l.maxSteps) block = "task reached max_steps " + l.maxSteps;
        else if (!free && p.has("task_id") && !p.getString("task_id").equals(l.id)) block = "task_id does not match the active task";
        else if (tier == 4) block = "T4 forbidden: " + why;
        else if (text.trim().isEmpty() && free && !fw && !tool.equals("notifications.mark_read")) block = "text is empty";
        else if (notificationAction && (!Notifs.connected || nr.removed)) block = "notification removed or listener disconnected; read live conversations again";
        else if (reply && nr.reply == null) block = "this notification has no reply action: open the app instead";
        else if (tool.equals("notifications.mark_read") && nr.markRead == null) block = "app offers no mark-as-read action";
        else if ((tool.equals("type") || tool.equals("intent") || free || api != null) && (l != null && leaksSecret(l.h, text) || Notifs.leaks(text)))
            block = "T4 forbidden: text contains a code/canary read from the screen or a notification (P3)";
        else if (node != null && Guard.offLimits(node)) block = "T4 forbidden: AgentOS's own window";
        else if (scoped && Guard.forbidden(pkg)) block = "T4 forbidden: " + pkg + " is a protected system surface";
        else if (scoped && !l.apps.contains(pkg)) block = pkg + " is not in this task's lease " + l.apps;

        boolean untrusted = l != null && l.h.untrusted || notificationAction, priv = Guard.privateApp(pkg) || secure || nr != null && nr.test || api != null && api.priv, outward = tier == 3; // a reply answers a stranger's text
        JSONObject r2 = new JSONObject().put("untrusted", untrusted).put("private", priv).put("outward", outward);
        JSONObject log = entry(tool, l).put("pkg", pkg).put("target", target).put("text", Guard.clip(text, 200))
                .put("tier", TIER[tier]).put("rule_of_two", r2);
        if (notificationAction) log.put("notification_key", nr.key).put("conversation_id", nr.conversationId);
        if (block != null) return finish(log, ok(false), "blocked", block, null);

        if (tool.equals("type") && copied(l.h, text)) l.h.copied = true; // sticky: later sends need a person, not the reviewer
        if (!free || notificationAction && l != null) island(l, notificationAction ? target : stepLabel(tool, pkg, target));
        String decision = "auto";
        if (reply && Notifs.standing(text)) { log.put("approver", "standing auto-reply"); decision = "approved"; } // the user approved this exact text once
        else if (owner) { log.put("approver", "owner tap"); decision = "approved"; }
        else if (protective) { log.put("approver", "policy").put("rationale", "protective: only tracker-list domains, the app keeps working"); decision = "approved"; }
        else if (tier == 3 || (tier == 2 && untrusted && priv)) {
            if (tier == 2) why += " after reading untrusted content in a private app (Rule of Two)";
            boolean copy = (free && !fw || api != null) && copied(Notifs.seen, text) || api != null && copied(l.h, text);
            Object[] pol = channels(l == null ? null : l.h, tool, target, untrusted, priv, outward, copy);
            if (tool.equals("intent") && tier == 3) pol = new Object[]{HUMAN, why}; // a slipped-in link
            if (notificationAction && "MALICIOUS".equals(nr.label) && pol[1] == null) pol = new Object[]{HUMAN, "Prompt Guard flagged this message"};
            if (tool.equals("notifications.autoreply")) pol = new Object[]{HUMAN, "a standing grant needs a person"};
            if (l != null && l.unlockedFor != null && pol[0] == FINGERPRINT_ONLY) // nobody may be holding the phone: money, installs, vault
                return finish(log, ok(false), "blocked", "needs the owner's fingerprint, which a remote assistant can't ask for on a phone it unlocked", null);
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
        long t0 = SystemClock.uptimeMillis();
        JSONObject out = null;
        boolean done;
        try {
            done = reply ? Notifs.reply(svc, nr, text) : tool.equals("notifications.mark_read") ? Notifs.markRead(svc, nr)
                    : api != null ? (out = Api.run(svc, tool, p)).getBoolean("ok") : execute(tool, p, node);
        } catch (Exception e) {
            if (!notificationAction) throw e;
            return finish(log, ok(false), "blocked", "notification action unavailable; read again: " + e.getClass().getSimpleName(), null);
        }
        if (!free && l != null) l.h.steps++;
        JSONObject res = ok(done).put("tier", TIER[tier]).put("rule_of_two", r2);
        if (api != null) return finish(log, absorb(l, api, out, res, log), decision, done ? why : out.optString("reason", "failed"), null); // the data is the answer: no screen
        if (fw) Events.emit("firewall.changed", new JSONObject().put("tool", tool).put("pkg", pkg).put("target", target).put("ok", done));
        if (free) return finish(log, res, decision, done ? why : "failed", null);
        if (done && (tool.equals("open_app") || tool.equals("intent"))) awaitApp(pkg);
        if (!p.optBoolean("screen", true)) return finish(log, res, decision, done ? why : "Android refused the action", null); // a reflex (agentd reflex.js): no one reads the next screen, so no settle or re-read
        settle(t0);
        return finish(log, res, decision, done ? why : "Android refused the action", read());
    }

    /**
     * app.mod.apply {spec}: strict schema, then the reviewer for clean owner-requested masks, otherwise a person.
     * Matching screen labels are selectors, not copied outward content. Every apply is audited with the
     * full spec. The app is brought to the front so the mod shows up at once.
     */
    static List<String> modChannels(Lease l, JSONObject p, String pkg, boolean mask, boolean caller) {
        return mask && caller && l != null && l.asked && l.granted && !l.paused && System.currentTimeMillis() < l.expires
                && l.id.equals(p.optString("task_id")) && l.apps.contains(pkg) && Arrays.asList("typed", "voice").contains(l.origin)
                && !l.h.flagged && !l.h.copied && !p.optBoolean("tainted") ? ORDINARY : HUMAN;
    }

    private JSONObject deepmod(String method, JSONObject p, int uid) throws Exception {
        String op = method.substring("app.deepmod.".length());
        if (op.equals("disable")) return deepMods.disable("kill switch");
        if (op.equals("remove") || op.equals("rollback")) return deepMods.remove(op.equals("rollback"));
        if (op.equals("inspect") || op.equals("list")) return deepMods.inspect();
        if (op.equals("ready")) {
            if (!callerIn(uid, "lease_callers")) return ok(false).put("reason", "summary consent status only from agentd");
            deepMods.summaryReady = p.optBoolean("enabled"); return ok(true);
        }
        if (op.equals("read")) {
            if (!callerIn(uid, "lease_callers")) return ok(false).put("reason", "summary read only from agentd");
            JSONObject now = deepMods.inspect();
            if (!p.optString("context").equals(now.getString("context")) || !now.getString("screen").equals("chat") || !deepMods.consumeSummary(p.getString("context")))
                return ok(false).put("reason", "summary context expired or no native activation");
            JSONObject screen = read();
            if (!local.agentos.deep.Contract.PKG.equals(screen.optString("pkg"))) return ok(false).put("reason", "foreground changed; no context returned");
            return screen;
        }
        if (op.equals("integrate")) {
            if (uid != 2000) return ok(false).put("reason", "developer integration over adb only");
            return deepMods.enroll();
        }
        if (op.equals("style")) {
            JSONObject now = deepMods.inspect(), spec = now.isNull("spec") ? new JSONObject().put("schema", local.agentos.deep.Contract.WIRE)
                    .put("id", "pigeon-theme").put("package", local.agentos.deep.Contract.PKG).put("capabilityVersion", 1) : new JSONObject(now.getJSONObject("spec").toString());
            spec.put("baseRevision", now.getLong("revision"));
            JSONObject theme = spec.optJSONObject("theme"); if (theme == null) theme = new JSONObject();
            if (p.has("palette")) {
                local.agentos.deep.Contract.need(p.getString("palette").equals("navy-gold"), "only navy-gold preset is advertised");
                JSONObject colors = new JSONObject(); String[] values = {"#FF081A33", "#FF122A4A", "#FF1C3555", "#FFFFF4D6", "#FFD4C6A5", "#FFD6AD45", "#FF081A33"};
                for (int i = 0; i < values.length; i++) colors.put(local.agentos.deep.Contract.COLORS.get(i), values[i]);
                theme.put("colors", colors);
            }
            if (p.has("font")) theme.put("font", new JSONObject().put("family", p.getString("font")).put("scale", p.optDouble("scale", 1)));
            if (theme.length() > 0) spec.put("theme", theme);
            if (p.has("filter_promoted")) spec.put("lists", p.getBoolean("filter_promoted") ? new JSONArray().put(new JSONObject().put("id", "pigeon_threads")
                    .put("exclude", new JSONObject().put("field", "promoted").put("equals", true))) : new JSONArray());
            if (p.has("hide_decorations")) spec.put("views", p.getBoolean("hide_decorations") ? new JSONArray().put(new JSONObject().put("screen", "chat-list")
                    .put("match", new JSONObject().put("id", "pigeon_overflow")).put("visibility", "gone")).put(new JSONObject().put("screen", "chat-list")
                    .put("match", new JSONObject().put("id", "pigeon_promotion")).put("visibility", "gone")) : new JSONArray());
            JSONObject proposal = new JSONObject().put("task_id", p.optString("task_id")).put("tainted", p.optBoolean("tainted")).put("payload", spec.toString());
            JSONObject preview = deepmod("app.deepmod.preview", proposal, uid);
            if (!preview.optBoolean("ok")) return preview;
            return deepmod("app.deepmod.apply", proposal.put("receipt", preview.getString("receipt")), uid);
        }
        if (!op.equals("preview") && !op.equals("apply")) throw new IllegalArgumentException("unknown deepmod operation");
        Lease l = lease;
        if (l == null || !l.id.equals(p.optString("task_id")) || !l.caller.equals(who(uid)) || !callerIn(uid, "lease_callers")
                || !l.granted || l.paused || l.expires < System.currentTimeMillis() || !l.apps.contains(local.agentos.deep.Contract.PKG))
            return ok(false).put("reason", "a live caller-bound Pigeon task lease is required");
        if (p.optBoolean("tainted")) l.h.flagged = true;
        String raw; long epoch;
        synchronized (deepMods.lock) { epoch = deepMods.epoch; raw = op.equals("preview") ? p.getString("payload") : deepMods.receipt(p.getString("receipt")).raw; }
        JSONObject spec = local.agentos.deep.Contract.validate(raw);
        boolean passive = !spec.has("buttons") || spec.getJSONArray("buttons").length() == 0;
        List<String> channels = modChannels(l, p, local.agentos.deep.Contract.PKG, passive || op.equals("preview"), true);
        if (op.equals("apply") || channels == HUMAN) {
            JSONObject r2 = new JSONObject().put("untrusted", l.h.untrusted).put("private", false).put("outward", false);
            Approvals.Pending a = approve(method, local.agentos.deep.Contract.PKG, passive ? "Restyle Pigeon and filter synthetic promoted rows" : "Add a native Summarize button to Pigeon",
                    "Reversible appearance only. Spec SHA-256: " + local.agentos.deep.Contract.hash(raw), l, "T2 UI change", "cooperative signed spec", r2,
                    channels, channels == HUMAN ? "remote, tainted or button mods need a person" : null, Math.max(10, Math.min(60, p.optInt("approval_timeout_s", 60))) * 1000L);
            if (a.channel.isEmpty()) return ok(false).put("decision", "denied").put("reason", a.reason);
            if (lease != l || !l.granted || l.paused || l.expires < System.currentTimeMillis()
                    || channels == ORDINARY && modChannels(l, p, local.agentos.deep.Contract.PKG, passive, true) != ORDINARY
                    || Plugins.off(method, p) != null) return ok(false).put("reason", "policy changed during approval");
            audit.log(entry(method + ".review", l).put("spec_sha256", local.agentos.deep.Contract.hash(raw)).put("approver", a.channel).put("decision", "approved"));
        }
        synchronized (deepMods.lock) {
            local.agentos.deep.Contract.need(epoch == deepMods.epoch, "deep mods disabled during review; start a fresh preview");
            return op.equals("preview") ? deepMods.preview(raw, l.id) : deepMods.apply(p.getString("receipt"), l.id);
        }
    }

    private JSONObject mod(JSONObject p, int uid) throws Exception {
        Lease l = lease;
        JSONObject spec = p.optJSONObject("spec");
        String pkg = spec == null ? "" : spec.optString("package"), bad = null;
        try { if (spec == null) throw new IllegalArgumentException("spec is required"); Mods.validate(spec); }
        catch (IllegalArgumentException e) { bad = "bad spec: " + e.getMessage(); }
        if (bad == null && svc.getPackageManager().getLaunchIntentForPackage(pkg) == null) bad = pkg + " is not installed";
        else if (bad == null && !mods.previewed(spec)) bad = "preview this exact spec with visible matches first (valid for 5 minutes)";
        else if (bad == null && mods.count(spec.optString("id")) >= Mods.MAX_MODS) bad = "at most " + Mods.MAX_MODS + " mods: remove one first";
        else if (bad == null && svc.getSystemService(KeyguardManager.class).isKeyguardLocked()) bad = "phone is locked";
        String app = labels(Collections.singleton(pkg)), target = "Add “" + (spec == null ? "" : spec.optString("label")) + "” to " + app;
        JSONObject log = entry("app.mod.apply", l).put("pkg", pkg).put("target", target).put("tier", "T2 UI change");
        if (bad != null) return finish(log, ok(false), "blocked", bad, null);
        String text = mods.describe(spec);
        log.put("id", spec.getString("id")).put("label", spec.getString("label")).put("text", text)
                .put("spec_sha256", Audit.sha256(spec.toString())).put("spec", spec);
        JSONObject r2 = new JSONObject().put("untrusted", l != null && l.h.untrusted).put("private", false).put("outward", false);
        if (l != null && p.optBoolean("tainted")) l.h.flagged = true;
        List<String> channels = modChannels(l, p, pkg, ModMask.is(spec), l != null && callerIn(uid, "lease_callers") && l.caller.equals(who(uid)));
        Approvals.Pending a = approve("app.mod.apply", pkg, target, text, l, "T2 UI change", "changes the appearance of " + app, r2, channels,
                channels == ORDINARY ? null : "remote, tainted, unbound or button mods need a person", Math.max(10, Math.min(120, p.optInt("approval_timeout_s", 60))) * 1000L);
        log.put("allowed_channels", new JSONArray(a.channels));
        if (a.channel.isEmpty()) return finish(log, ok(false), "denied", a.reason, null);
        if (!mods.previewed(spec)) return finish(log, ok(false), "blocked", "preview expired or mods disabled/removed during approval; preview again", null);
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
            case "trackers.block": case "trackers.unblock": Trackers.block(svc, tool, p); // the island names who just got shut out (green moment, 3 s)
                if (tool.equals("trackers.block")) { String who = "Blocked · " + Trackers.who(p.getJSONArray("domains")); svc.main.post(() -> svc.island.set("done", who, -1)); }
                return true;
            case "notifications.autoreply": Notifs.standing.put(p.getString("text"), System.currentTimeMillis() + Math.max(1, Math.min(240, p.optInt("minutes", 60))) * 60_000L); return true;
            default: Firewall.clear(); return true;
        }
    }

    /** Show the card, wait (approval_timeout_s, default 60 s) for a tap / fingerprint / voice / reviewer, Deny or Stop. */
    private Approvals.Pending approve(String tool, String pkg, String target, String text, Lease l, String tier, String why, JSONObject r2,
            List<String> channels, String fp, long timeoutMs) throws Exception {
        ApprovalCard.Model m = new ApprovalCard.Model();
        m.title = tool.equals("type") ? "type into \"" + target + "\"" : tool.equals("tap") ? "tap \"" + target + "\""
                : tool.equals("firewall.set") ? target + " for " + labels(Collections.singleton(pkg)) // "Block httpbin.org for Beacon?"
                : tool.startsWith("notifications.") || tool.startsWith("trackers.") || tool.startsWith("app.") || tool.equals("intent") || Api.SPECS.containsKey(tool) ? target : tool + " " + target;
        m.app = pkg; m.target = target; m.text = text; m.tier = tier; m.reason = why;
        m.task = l == null ? "(no task)" : l.id + " · " + l.goal;
        m.untrusted = r2.getBoolean("untrusted"); m.priv = r2.getBoolean("private"); m.outward = r2.getBoolean("outward");
        Approvals.Pending a = new Approvals.Pending(
                Approvals.canonical(tool, pkg, target, text, l == null ? "" : l.id, Approvals.hex(16)), m, channels);
        long[] shownAt = {0};
        ApprovalCard.Listener ui = new ApprovalCard.Listener() {
            @Override public void approve() {
                if (!HumanTouch.settled(shownAt[0])) return; // a tap already in flight when the card appeared isn't a decision
                boolean accepted = a.confirm("tap", "");
                android.util.Log.i("ApprovalTouch", "listener accepted=" + accepted + " channels=" + a.channels);
                if (accepted) return; // ordinary action: the real tap is the approval
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
        Lease l = lease;
        if (l != null) awake(l); // the screen locked mid-task (a long voice task): open it again if eligible
        JSONObject s = svc.screen.read(svc);
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

    /**
     * Open the phone for this task if it is locked or asleep. Eligible: the founder's own request (agentd's user_asked:
     * voice, typed, or a schedule they made), and nothing in the task looked like an injection (Prompt Guard, agentd's
     * guard, or text copied from the screen). Injected or third-party text never gets here: a reply rule's turn isn't
     * asked, and a flagged task stays flagged (History carries over). Returns null if the phone is open, else why not.
     */
    private String awake(Lease l) throws JSONException {
        boolean locked = Wake.locked(svc);
        if (!locked && !Wake.asleep(svc)) return null;
        String no = l == null || !l.asked && l.remote == null ? "phone is locked (only the user's own requests, or a paired assistant you let unlock, may unlock it)"
                : l.h.flagged ? "phone is locked (a guard flagged text in this task, so it may not unlock)"
                : l.h.copied ? "phone is locked (this task copied untrusted text, so it may not unlock)" : null;
        if (no != null) return locked ? no : null; // asleep but not locked: act as before
        no = Wake.unlock(svc);
        if (locked) {
            if (no == null) l.relock = true;
            if (no == null && !l.asked) { l.unlockedFor = l.remote; island(l, null); }
            audit.log(entry("phone.unlock", l).put("goal", l.goal).put("origin", l.origin).put("decision", no == null ? "auto" : "blocked").put("remote", l.asked ? null : l.remote)
                    .put("reason", no != null ? no : !l.asked ? "Unlocked for " + l.remote + ": " + l.goal.replaceFirst("^From [^:]+: ", "")
                            : "unlocked for " + ("schedule".equals(l.origin) ? "the user's scheduled task" : "the user's own " + l.origin + " request")));
        }
        return no;
    }

    /**
     * schedule.alarm {at, event?, id?, prompt?, when?, text?} from agentd: set the exact alarm that wakes it at `at`, audit
     * the founder's schedule changes, and post the result of a finished scheduled task as a notification.
     */
    private JSONObject schedule(JSONObject p, int uid) throws JSONException {
        if (!callerIn(uid, "lease_callers")) return ok(false).put("reason", "schedule.alarm only from agentd, not " + who(uid));
        String ev = p.optString("event");
        if (!ev.isEmpty()) audit.log(new JSONObject().put("task", "").put("tool", "schedule." + ev).put("id", p.optString("id"))
                .put("goal", Guard.clip(p.optString("prompt"), 200)).put("target", p.optString("when")).put("decision", "auto")
                .put("reason", ev.equals("created") ? "the user scheduled it" : ev));
        if (ev.equals("done") || ev.equals("failed")) Wake.notify(svc, Guard.clip(p.optString("prompt"), 60), Guard.clip(p.optString("text"), 500));
        return p.has("at") ? Wake.alarm(svc, p.optLong("at")) : ok(true);
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
        String st = l.unlockedFor != null ? "Unlocked for " + l.unlockedFor : step; // the whole run, so anyone looking at the phone knows who opened it
        int pct = Math.min(100, l.h.steps * 100 / Math.max(1, l.maxSteps));
        svc.main.post(() -> { svc.island.goal = l.goal; svc.island.set("working", st, pct); });
    }

    private String stepLabel(String tool, String pkg, String target) {
        if (Api.SPECS.containsKey(tool)) return Api.SPECS.get(tool).step;
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
        if (f == null || f.isShowingHintText() || f.isPassword() || Vault.filled.contains(f) || !pkg(f).equals(pkg)) return "";
        CharSequence t = f.getText();
        return t == null ? "" : t.toString();
    }

    /** Collects editable nodes under n, stopping at 2 (callers only need "exactly one"). */
    private static void editables(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        if (n == null || out.size() > 1) return;
        if (n.isEditable()) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) editables(n.getChild(i), out);
    }

    /**
     * An API result into the reply. Reads of other people's text (SMS, calls, contacts, events) count like screen text:
     * the task becomes untrusted, codes go to the P3 secrets, the text joins the copy check, and Prompt Guard scores each
     * item (flagged text is withheld and flags the task).
     */
    private JSONObject absorb(Lease l, Api.Spec api, JSONObject out, JSONObject res, JSONObject log) throws Exception {
        JSONArray items = out.optJSONArray("items");
        if (api.reads && l != null) l.h.untrusted = true;
        for (int i = 0; api.reads && items != null && i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            String t = it.optString("text");
            java.util.regex.Matcher m = SECRET.matcher(t);
            while (l != null && m.find()) l.h.secrets.add(m.group().replaceAll("[ -]", ""));
            if (l != null && norm(t).length() >= 3 && l.h.seen.size() < 4000) l.h.seen.add(norm(t));
            JSONObject g = t.length() >= 12 ? PromptGuard.classify(t) : null;
            if (g == null || !"MALICIOUS".equals(g.optString("label"))) continue;
            if (l != null) l.h.flagged = true;
            flag(l, api.scope, it.optString("from"), t, g.opt("score"), null);
            it.put("text", "[withheld by guard: likely prompt injection, score " + g.opt("score") + "]");
        }
        if (items != null) log.put("items", items.length());
        for (java.util.Iterator<String> k = out.keys(); k.hasNext(); ) { String key = k.next(); if (!key.equals("ok")) res.put(key, out.get(key)); }
        return res;
    }

    /** open_url: did this link (or its host) come from text the task read (screen, notification, SMS), or is the task flagged? */
    private static boolean linked(Lease l, String url) {
        String host = String.valueOf(android.net.Uri.parse(url).getHost()).toLowerCase(java.util.Locale.ROOT).replaceFirst("^www\\.", "");
        if (l != null && (l.h.flagged || copied(l.h, url))) return true;
        if (copied(Notifs.seen, url)) return true;
        for (Set<String> seen : l == null ? Collections.singletonList(Notifs.seen) : Arrays.asList(l.h.seen, Notifs.seen))
            synchronized (seen) { for (String x : seen) if (host.length() >= 4 && x.contains(host)) return true; }
        return false;
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
        if (t.isEmpty()) return false; // no text, nothing copied (an empty notification message used to taint every text-less API call)
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
        List<String> list = new java.util.ArrayList<>(Arrays.asList(key.equals("owner_callers") ? "local.agentos.home" : "com.termux")); // owner: the launcher only, never agentd
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

    /** Wait for the screen to settle: the first event after the action (at t0), then 300 ms of quiet; at most 2 s. */
    private void settle(long t0) throws InterruptedException {
        long start = SystemClock.uptimeMillis(), now;
        do { Thread.sleep(50); now = SystemClock.uptimeMillis(); }
        while (now - start < 2000 && (svc.lastEvent < t0 || now - svc.lastEvent < 300));
    }

    /** open_app / intent: a cold start draws its window over a second later, and the quiet before it fooled settle(), so the
     *  agent got the old screen and had to read again (agentd/bench/RESULTS.md round 2). Wait (max 3 s) for the app in front. */
    private void awaitApp(String pkg) throws InterruptedException {
        for (long t = SystemClock.uptimeMillis(); SystemClock.uptimeMillis() - t < 3000; Thread.sleep(50)) {
            AccessibilityNodeInfo r = svc.getRootInActiveWindow();
            if (r != null && pkg.equals(String.valueOf(r.getPackageName()))) return;
        }
    }

    private static String pkg(AccessibilityNodeInfo n) { return String.valueOf(n.getPackageName()); }

    private static JSONObject ok(boolean ok) throws JSONException { return new JSONObject().put("ok", ok); }

    private static JSONObject entry(String tool, Lease l) throws JSONException {
        return new JSONObject().put("task", l == null ? "" : l.id).put("tool", tool);
    }
}
