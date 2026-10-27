package local.agentos;

import android.app.KeyguardManager;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Base64;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * outward (T3). T3 always needs approval; T2 needs it when untrusted and private are both true. How strong the
 * approval must be (a tap, or a fingerprint) is decided by fingerprintRequired(), the risk-adaptive policy.
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

    private static final java.util.regex.Pattern HIGH_STAKES = java.util.regex.Pattern.compile("(?i)\\b(pay|payment|buy|purchase|order|"
            + "checkout|check out|transfer|donate|subscribe|add to (?:cart|basket|bag)|delete|remove|erase|install|uninstall|update|reset|deactivate)\\b");
    static final List<String> FINGERPRINT_ONLY = Collections.singletonList("fingerprint"),
            ORDINARY = Collections.unmodifiableList(Arrays.asList("tap", "voice", "reviewer", "fingerprint"));

    /**
     * Risk-adaptive approvals: the riskier the action, the stronger the proof. Returns null for an ordinary outward
     * action (a real finger tap on the card, a spoken "send it", or agentd's reviewer model may approve it), else the
     * reason only a fingerprint will do. Because the reviewer is only offered when this returns null, it can never
     * approve anything tainted, flagged or high-stakes.
     */
    static String fingerprintRequired(History h, String tool, String target, boolean untrusted, boolean priv, boolean outward) {
        if (h != null && h.flagged) return "Prompt Guard flagged text in this task";
        if (h != null && h.copied) return "the agent typed text copied from the screen (untrusted arguments)";
        if (tool.startsWith("firewall.")) return "network rule change";
        if (HIGH_STAKES.matcher(target).find()) return "money, delete or install";
        if (untrusted && priv && outward) return "all three Rule-of-Two lamps are lit";
        return null;
    }

    private static final java.util.regex.Pattern SECRET = java.util.regex.Pattern.compile(
            "AGENTOS-CANARY-[A-Z0-9]+|(?<![\\d.:])\\d{6,8}(?![\\d.:])|(?<!\\d)\\d(?:[ -]?\\d){12,18}(?!\\d)");
    private static final String[] TIER = {"T0 observe", "T1 navigate", "T2 draft", "T3 outward", "T4 forbidden"};
    private final AgentA11y svc;
    private final Audit audit;
    private final Object actLock = new Object(), startLock = new Object();
    private final LeaseCard leaseCard;
    /** Open approvals as announced on /events (approval_id -> approval.shown payload): approval.list returns these. */
    private final Map<String, JSONObject> shown = new ConcurrentHashMap<>();
    private volatile Lease lease, last; // last: the most recent lease, even after stop (for carry-over)
    private volatile Approvals.Pending current;
    private volatile CompletableFuture<Boolean> leaseAsk;
    /** The island pill's step label; guard alerts already shown ("task|sender"), so rereads don't nag. */
    private volatile String step = "Starting";
    private final Set<String> alerted = ConcurrentHashMap.newKeySet();

    Broker(AgentA11y svc) {
        this.svc = svc;
        leaseCard = new LeaseCard(svc);
        audit = new Audit(svc.getFilesDir());
        PromptGuard.init(svc);
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
            case "approval.list": return approvals();
            case "approval.confirm": return confirm(p, uid);
            case "approval.deny": return deny(p);
            case "approval.escalate": return escalate(p, uid);
            case "tap": case "type": case "scroll": case "press": case "open_app": case "firewall.set": case "firewall.clear":
                synchronized (actLock) { return act(m, p); } // one action at a time; nothing else acts while a card is up
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
        if (l == null) return ok(true).put("reason", "no active task");
        if (!taskId.isEmpty() && !taskId.equals(l.id)) return ok(false).put("reason", "task " + taskId + " is not the active task (" + l.id + ")");
        lease = null;
        l.granted = false; // a stopped grant can't be reused by a later task.start
        Approvals.Pending c = current;
        if (c != null) c.finish(null, "task stopped");
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
        else if (!fw && l.h.steps >= l.maxSteps) block = "task reached max_steps " + l.maxSteps;
        else if (!fw && p.has("task_id") && !p.getString("task_id").equals(l.id)) block = "task_id does not match the active task";
        else if (tier == 4) block = "T4 forbidden: " + why;
        else if (tool.equals("type") && leaksSecret(l.h, text)) block = "T4 forbidden: text contains a code/canary read from the screen (P3)";
        else if (node != null && Guard.offLimits(node)) block = "T4 forbidden: AgentOS's own window";
        else if (scoped && Guard.forbidden(pkg)) block = "T4 forbidden: " + pkg + " is a protected system surface";
        else if (scoped && !l.apps.contains(pkg)) block = pkg + " is not in this task's lease " + l.apps;

        boolean untrusted = l != null && l.h.untrusted, priv = Guard.privateApp(pkg) || secure, outward = tier == 3;
        JSONObject r2 = new JSONObject().put("untrusted", untrusted).put("private", priv).put("outward", outward);
        JSONObject log = entry(tool, l).put("pkg", pkg).put("target", target).put("text", Guard.clip(text, 200))
                .put("tier", TIER[tier]).put("rule_of_two", r2);
        if (block != null) return finish(log, ok(false), "blocked", block, null);

        if (tool.equals("type") && copied(l.h, text)) l.h.copied = true; // sticky: later sends need a fingerprint
        if (!fw) island(l, stepLabel(tool, pkg, target));
        String decision = "auto";
        if (tier == 3 || (tier == 2 && untrusted && priv)) {
            if (tier == 2) why += " after reading untrusted content in a private app (Rule of Two)";
            String fp = fingerprintRequired(l == null ? null : l.h, tool, target, untrusted, priv, outward);
            List<String> channels = fp == null ? ORDINARY : FINGERPRINT_ONLY;
            Approvals.Pending a = approve(tool, pkg, target, text, l, TIER[tier], why, r2, channels, fp,
                    Math.max(10, Math.min(120, p.optInt("approval_timeout_s", 60))) * 1000L);
            log.put("allowed_channels", new JSONArray(a.channels)).put("fingerprint_because", a.because == null ? JSONObject.NULL : a.because);
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
        boolean done = execute(tool, p, node);
        if (!fw && l != null) l.h.steps++;
        JSONObject res = ok(done).put("tier", TIER[tier]).put("rule_of_two", r2);
        if (fw) {
            Events.emit("firewall.changed", new JSONObject().put("tool", tool).put("pkg", pkg).put("target", target).put("ok", done));
            return finish(log, res, decision, done ? why : "failed", null);
        }
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

    /** Show the card, wait (approval_timeout_s, default 60 s) for a tap / fingerprint / voice / reviewer, Deny or Stop. */
    private Approvals.Pending approve(String tool, String pkg, String target, String text, Lease l, String tier, String why, JSONObject r2,
            List<String> channels, String fp, long timeoutMs) throws Exception {
        ApprovalCard.Model m = new ApprovalCard.Model();
        m.title = tool.equals("type") ? "type into \"" + target + "\"" : tool.equals("tap") ? "tap \"" + target + "\"" : tool + " " + target;
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
                .put("fingerprint_because", fp == null ? JSONObject.NULL : fp).put("expires", System.currentTimeMillis() + timeoutMs));
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
            Events.emit("guard.flag", new JSONObject().put("task_id", l == null ? "" : l.id).put("pkg", s.optString("pkg"))
                    .put("score", g.opt("score")).put("node_ids", g.optJSONArray("flagged_node_ids")));
            if (l != null) alert(l, s, g);
        }
        return s.put("ok", true);
    }

    /** guard.flag on the island: one alert card per task and sender (or flagged text). Never over an open approval card. */
    private void alert(Lease l, JSONObject s, JSONObject g) {
        JSONArray ids = g.optJSONArray("flagged_node_ids");
        int id = ids == null || ids.length() == 0 ? -1 : ids.optInt(0);
        JSONObject n = id < 0 ? new JSONObject() : s.optJSONArray("nodes").optJSONObject(id);
        String snippet = n.optString("text", n.optString("desc", "text on this screen")), sender = id < 0 ? "" : sender(id);
        if (current != null || !alerted.add(l.id + "|" + (sender.isEmpty() ? snippet : sender))) return;
        float score = (float) g.optDouble("score", 0);
        svc.main.post(() -> svc.card.alert(s.optString("pkg"), sender, snippet, score, new ApprovalCard.AlertListener() {
            @Override public void block() { // Block = stop this task; taint already forces a fingerprint for any send
                svc.card.hide();
                new Thread(() -> { try { stop(l.id, "user pressed Block on a Prompt Guard alert"); } catch (Exception ignored) {} }).start();
            }
            @Override public void ok() { svc.card.hide(); if (lease == l) island(l, null); }
        }));
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
            case "press": return "Pressing " + target;
            default: return "Scrolling";
        }
    }

    /** The live text of the field this task last typed into (same app), for a "Send" card's bubble; "" if none. */
    private static String draft(Lease l, String pkg) {
        AccessibilityNodeInfo f = l == null ? null : l.field;
        if (f == null || !f.refresh() || f.isShowingHintText() || !pkg(f).equals(pkg)) return "";
        CharSequence t = f.getText();
        return t == null ? "" : t.toString();
    }

    private static boolean leaksSecret(History h, String text) {
        String flat = text.replaceAll("[ -]", "");
        synchronized (h.secrets) { for (String x : h.secrets) if (flat.contains(x.replaceAll("[ -]", ""))) return true; }
        return false;
    }

    /** Is this text lifted from third-party screen text: the same string, or a 12+ character piece of one (either way)? */
    private static boolean copied(History h, String text) {
        String t = norm(text);
        synchronized (h.seen) {
            for (String x : h.seen)
                if (t.equals(x) || x.length() >= 12 && t.contains(x) || t.length() >= 12 && x.contains(t)) return true;
        }
        return false;
    }

    private static String norm(String s) { return s.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim(); }

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
