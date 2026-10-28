package local.agentos;

import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import static local.agentos.Island.*;

/**
 * The native cards that open out of the Island (TYPE_ACCESSIBILITY_OVERLAY; AI or web content can never draw them).
 * Progressive disclosure: each card shows only the essentials; a tap on the card body reveals the details.
 *  - show(Model, Listener): approval. App + exact action (+ the exact text as a blue bubble), Deny / Approve; a
 *    fingerprint-only card has no Approve, just the fingerprint control ("Touch the sensor to approve") and Deny.
 *    Approve and the control only call Listener.approve(); the card itself never grants anything.
 *  - alert(...): prompt injection caught. One headline, the source app, Block / OK; score and snippet behind a tap.
 *  - lease(...): "Allow agent in <app> for N min?" Deny / Allow.
 *  - task(): the task sheet behind a pill tap, with the always-available Stop.
 *  - autoApproved(action, why): a quiet "Auto-approved" moment on the pill; a tap reveals why.
 * Broker-facing API (Model, Listener, show, hide) is unchanged; all policy stays in Broker.
 */
final class ApprovalCard {
    static final class Model {
        String title = "", app = "", target = "", text = "", task = "", tier = "", reason = "";
        boolean untrusted, priv, outward;
        /** Fallback when the broker hasn't registered channels for this model (demo cards): true = say "Fingerprint required". */
        boolean fingerprint = true;
    }

    interface Listener { void approve(); void deny(); }
    interface AlertListener { void block(); void ok(); }

    private final AgentA11y svc;
    private String autoState, autoLabel, autoAction, autoWhy; private int autoPct;
    private long shownAt; // buttons accept only real finger touches made >= 500 ms after this (HumanTouch)

    ApprovalCard(AgentA11y svc) {
        this.svc = svc;
        svc.island.onPillTap = this::task;
    }

    /** Main thread. Replaces any card already showing. */
    void show(Model m, Listener l) {
        java.util.List<String> ch = Approvals.channelsFor(m);
        boolean finger = ch.isEmpty() ? m.fingerprint : !ch.contains("tap"); // ordinary actions: a real tap is the approval
        LinearLayout f = face(appIcon(m.app), appLabel(m.app), headline(m.title), BLUE, true);
        if (!m.text.isEmpty()) f.addView(bubble(m.text));
        if (m.untrusted) f.addView(chips(new String[]{"Untrusted content"}, new boolean[]{true}, ORANGE), margin(0, dp(10)));
        View details = col();
        details.setVisibility(View.GONE);
        ((LinearLayout) details).addView(text(m.task + "\n" + m.tier + " · " + m.reason, 11, 500, L3), margin(0, dp(10)));
        ((LinearLayout) details).addView(chips(new String[]{"Untrusted input", "Private data", "Outward action"},
                new boolean[]{m.untrusted, m.priv, m.outward}, BLUE), margin(0, dp(8)));
        f.addView(details);
        if (finger) f.addView(acts(sensor(l::approve), button("Deny", FILL14, L1, l::deny))); // no plain Approve: the finger is the approval
        else f.addView(acts(new View(svc), button("Deny", FILL14, L1, l::deny), button("Approve", BLUE, L1, l::approve)));
        disclose(f, details);
        open(f, BLUE);
    }

    /** Main thread. Closes the card (the pill stays while a task is active). */
    void hide() { svc.island.collapse(); }

    /** Prompt injection caught in `app` (sender may be empty). */
    void alert(String app, String sender, String snippet, float score, AlertListener l) {
        LinearLayout f = face(glyphIcon("shield", RED), appLabel(app) + (sender.isEmpty() ? "" : " · " + sender),
                "Ignored instructions in a message", RED, false);
        LinearLayout details = col();
        details.setVisibility(View.GONE);
        details.addView(text("Prompt Guard " + String.format(Locale.US, "%.2f", score) + " · blocked · logged "
                + new SimpleDateFormat("H:mm:ss", Locale.US).format(new Date()), 11, 500, L3), margin(0, dp(10)));
        TextView q = text("“" + snippet + "”", 12.5f, 400, L2);
        q.setMaxLines(3); q.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(q, margin(0, dp(6)));
        details.addView(chips(new String[]{"Untrusted input", "Private data", "Outward action"}, new boolean[]{true, true, true}, RED), margin(0, dp(8)));
        f.addView(details);
        f.addView(acts(text("Details", 12, 600, L3), button("Block", FILL14, L1, l::block), button("OK", RED, L1, l::ok)));
        disclose(f, details);
        svc.island.set("alert", "Blocked a message", -1);
        open(f, RED);
    }

    /** ScamWatch: a scam or hidden AI orders in a chat the owner opened (no task). Calm amber: headline, one-line why, OK; snippet and score behind a tap. */
    void scam(String app, String sender, String why, String snippet, double score, Runnable ok) {
        LinearLayout f = face(glyphIcon("shield", AMBER), appLabel(app) + (sender.isEmpty() ? "" : " · " + sender), "Possible scam message", AMBER, false);
        f.addView(text(Character.toUpperCase(why.charAt(0)) + why.substring(1), 13, 500, L2), margin(0, dp(6)));
        LinearLayout details = col();
        details.setVisibility(View.GONE);
        TextView q = text("“" + snippet + "”", 12.5f, 400, L2);
        q.setMaxLines(4); q.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(q, margin(0, dp(10)));
        details.addView(text((score >= 0 ? "Prompt Guard " + String.format(Locale.US, "%.2f", score) + " · " : "") + "logged in Activity · the agent won't act on it", 11, 500, L3), margin(0, dp(6)));
        f.addView(details);
        f.addView(acts(text("Details", 12, 600, L3), button("OK", AMBER, 0xFF1C1C1E, ok)));
        disclose(f, details);
        svc.island.set("alert", "Possible scam", -1);
        open(f, AMBER);
    }

    /** Task lease (LeaseCard delegates here): let the agent act in `apps` (labels) for `minutes`. Caller is behind a tap. */
    void lease(String apps, String minutes, String goal, String caller, Listener l) {
        LinearLayout f = face(glyphIcon("clock", BLUE), goal, "Allow agent in " + apps + " for " + minutes + " min?", BLUE, true);
        LinearLayout details = col();
        details.setVisibility(View.GONE);
        details.addView(text("Asked by " + caller, 11, 500, L3), margin(0, dp(10)));
        f.addView(details);
        f.addView(acts(new View(svc), button("Not now", FILL14, L1, l::deny), button("Allow", BLUE, L1, l::approve)));
        disclose(f, details);
        open(f, BLUE);
    }

    /** Vault sign-in offer: "Sign in as <user>?" Not now / Sign in (the fingerprint prompt follows). Caller is a real tap. */
    void offer(String pkg, String user, Listener l) {
        LinearLayout f = face(appIcon(pkg), appLabel(pkg) + " · saved password", "Sign in as " + user + "?", BLUE, true);
        f.addView(acts(hint("finger", BLUE, "Fingerprint"), button("Not now", FILL14, L1, l::deny), button("Sign in", BLUE, L1, l::approve)));
        open(f, BLUE);
    }

    /** The task sheet (pill tap): goal, step, progress, Hide / Stop. */
    void task() {
        Island i = svc.island;
        if (autoLabel != null) { why(); return; }
        LinearLayout f = face(glyphIcon("stop", i.accent()), "AgentOS · " + i.state, i.goal.isEmpty() ? "Working" : i.goal, i.accent(), true);
        f.addView(text(i.label.getText(), 13, 500, L2), margin(0, dp(10)));
        if (i.pct >= 0) f.addView(new Bar(svc, i.accent(), i.pct / 100f), new LinearLayout.LayoutParams(-1, dp(4)) {{ topMargin = dp(10); }});
        f.addView(acts(new View(svc), button("Hide", FILL14, L1, i::collapse), button("Stop", RED, L1, () -> { if (i.onStop != null) i.onStop.run(); i.collapse(); })));
        open(f, i.accent());
    }

    /** A quiet moment: the pill turns green and says "Auto-approved"; a tap reveals why. Restores the task pill after 2.6 s. */
    void autoApproved(String action, String why) {
        Island i = svc.island;
        if (autoLabel == null) { autoState = i.state; autoLabel = i.label.getText().toString(); autoPct = i.pct; }
        autoAction = action; autoWhy = why;
        i.set("done", "Auto-approved ✓", -1);
        i.postDelayed(this::restore, 2600);
    }

    /** Back to the task pill (or let the "done" pill auto-hide when there was no task). */
    private void restore() {
        if (autoLabel == null) return;
        if (!"idle".equals(autoState) && !"done".equals(autoState)) svc.island.set(autoState, autoLabel, autoPct);
        autoLabel = null;
    }
    private void why() {
        LinearLayout f = face(glyphIcon("finger", GREEN), "Reviewer model · auto-approved", headline(autoAction), GREEN, false);
        f.addView(text(autoWhy, 13, 400, L2), margin(0, dp(10)));
        f.addView(acts(new View(svc), button("OK", FILL14, L1, () -> { svc.island.collapse(); restore(); })));
        open(f, GREEN);
    }

    private void open(View f, int accent) { shownAt = SystemClock.uptimeMillis(); svc.island.expand(f, accent); }

    // ---------------------------------------------------------------- pieces

    /** Card scaffold: icon, source line, headline, optional breathing orb at the end. */
    private LinearLayout face(View icon, String src, String title, int accent, boolean orb) {
        LinearLayout f = col();
        f.setPadding(dp(18), dp(16), dp(18), dp(16));
        f.setClipToPadding(false);
        LinearLayout hd = row();
        hd.setClipChildren(false); // the orb's halo reaches past its box
        hd.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout tt = col();
        tt.addView(text(src, 12, 500, L2));
        tt.addView(text(title, 17, 600, L1));
        hd.addView(tt, new LinearLayout.LayoutParams(0, -2, 1) {{ leftMargin = dp(11); }});
        if (orb) hd.addView(new Orb(svc, accent), new LinearLayout.LayoutParams(dp(12), dp(12)) {{ leftMargin = dp(10); }});
        f.addView(hd);
        return f;
    }

    /** Tap on the card body toggles the details block; the island re-fits its height. */
    private void disclose(View f, View details) {
        f.setOnClickListener(v -> {
            boolean show = details.getVisibility() != View.VISIBLE;
            details.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) { details.setAlpha(0); details.animate().alpha(1).setDuration(260).start(); }
            svc.island.refit();
        });
    }

    private View bubble(String s) {
        TextView t = text(s, 14, 500, L1);
        t.setBackground(capsule(svc, BLUE, 0, 18));
        t.setPadding(dp(13), dp(8), dp(13), dp(8));
        t.setMaxWidth(Math.round(dp(CARD_W - 36) * .88f));
        FrameLayout w = new FrameLayout(svc);
        w.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.END));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(9);
        w.setLayoutParams(p);
        return w;
    }

    private View chips(String[] labels, boolean[] lit, int accent) {
        LinearLayout r = row();
        for (int i = 0; i < labels.length; i++) {
            TextView t = text("● " + labels[i], 10.5f, 600, lit[i] ? accent : L3);
            t.setBackground(capsule(svc, lit[i] ? (accent & 0x00FFFFFF) | 0x24000000 : 0, lit[i] ? (accent & 0x00FFFFFF) | 0x73000000 : 0x1AFFFFFF, 0));
            t.setPadding(dp(8), dp(4), dp(8), dp(4));
            r.addView(t, margin(i == 0 ? 0 : dp(6), 0));
        }
        return r;
    }

    private View hint(String glyph, int color, String s) {
        LinearLayout r = row();
        r.addView(new Glyph(svc, glyph, color), new LinearLayout.LayoutParams(dp(22), dp(22)));
        r.addView(text(s, 12, 600, L2), margin(dp(8), 0));
        return r;
    }

    private View acts(View left, View... buttons) {
        LinearLayout r = row();
        r.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        for (View b : buttons) r.addView(b, new LinearLayout.LayoutParams(-2, dp(40)) {{ leftMargin = dp(8); }});
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(14);
        r.setLayoutParams(p);
        return r;
    }

    /** The fingerprint control: a blue glass capsule with the fingerprint glyph. A real tap (re)opens the system prompt (Broker opens it by itself too). */
    private View sensor(Runnable onTap) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER);
        r.setMinimumHeight(dp(40));
        r.setPadding(dp(12), 0, dp(14), 0);
        r.setBackground(capsule(svc, (BLUE & 0x00FFFFFF) | 0x2E000000, (BLUE & 0x00FFFFFF) | 0x66000000, 0));
        r.addView(new Glyph(svc, "finger", BLUE), new LinearLayout.LayoutParams(dp(22), dp(22)));
        r.addView(text("Touch the sensor to approve", 13, 600, L1), margin(dp(8), 0));
        return press(r, onTap);
    }

    private TextView button(String label, int bg, int fg, Runnable onTap) {
        TextView b = text(label, 15, 600, fg);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setBackground(capsule(svc, bg, 0, 0));
        return press(b, onTap);
    }

    /** Real-touch filter + iOS press for any control: only a real finger, >= 500 ms after the card appeared, reaches onTap (HumanTouch). */
    private <V extends View> V press(V b, Runnable onTap) {
        b.setFilterTouchesWhenObscured(true);
        b.setOnTouchListener((v, e) -> { // only a real finger, >= 500 ms after the card appeared, reaches onClick (HumanTouch)
            if (!HumanTouch.real(e, shownAt)) return true;
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) v.animate().scaleX(.94f).scaleY(.94f).setDuration(120).start(); // iOS press
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) v.animate().scaleX(1).scaleY(1).setDuration(260).setInterpolator(SPRING).start();
            return false;
        });
        b.setOnClickListener(v -> onTap.run());
        return b;
    }

    private View appIcon(String pkg) {
        ImageView iv = new ImageView(svc);
        Drawable d;
        try { d = svc.getPackageManager().getApplicationIcon(pkg); } catch (Exception e) { d = svc.getPackageManager().getDefaultActivityIcon(); }
        iv.setImageDrawable(d);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10)); } });
        iv.setClipToOutline(true);
        return iv;
    }

    private View glyphIcon(String kind, int color) {
        FrameLayout f = new FrameLayout(svc);
        f.setBackground(capsule(svc, (color & 0x00FFFFFF) | 0x33000000, 0, 10));
        f.addView(new Glyph(svc, kind, color), new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        return f;
    }

    private String appLabel(String pkg) {
        try { return String.valueOf(svc.getPackageManager().getApplicationLabel(svc.getPackageManager().getApplicationInfo(pkg, 0))); }
        catch (Exception e) { return pkg; }
    }

    /** Broker's action string ("tap \"Send\"") as a headline: Tap “Send”? */
    private static String headline(String s) {
        if (s == null || s.isEmpty()) return "Approve?";
        StringBuilder b = new StringBuilder();
        boolean openQuote = true;
        for (char c : s.toCharArray()) { if (c == '"') { b.append(openQuote ? '“' : '”'); openQuote = !openQuote; } else b.append(c); }
        return Character.toUpperCase(b.charAt(0)) + b.substring(1) + "?";
    }

    private TextView text(CharSequence s, float sp, int weight, int color) { return Island.text(svc, s, sp, weight, color); }
    private LinearLayout row() { LinearLayout l = new LinearLayout(svc); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout col() { LinearLayout l = new LinearLayout(svc); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private int dp(float v) { return svc.island.dp(v); }
}
