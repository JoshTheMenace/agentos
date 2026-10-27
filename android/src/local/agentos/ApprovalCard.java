package local.agentos;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The native approval card, drawn as TYPE_ACCESSIBILITY_OVERLAY (above status bar, nav bar and other apps' overlays).
 * Everything visual is in this class so a designer can restyle it. Hooks:
 *  - Model: the structured fields to show (never model prose).
 *  - Listener: approve()/deny(); the card itself never grants anything (Approve only opens the fingerprint prompt).
 *  - Style constants and the build/header/row/chips/chip/buttons/button methods.
 * Touches injected by accessibility services (MotionEvent flag 0x800) are ignored, so the agent can't press it.
 */
final class ApprovalCard {
    static final class Model {
        String title = "", app = "", target = "", text = "", task = "", tier = "", reason = "";
        boolean untrusted, priv, outward;
    }

    interface Listener { void approve(); void deny(); }

    // ---- style hooks
    static int BG = 0xF2141820, FG = 0xFFF4F6F8, MUTED = 0xFF9AA4B2, CHIP_ON = 0xFFD64545, CHIP_OFF = 0xFF2C333D,
            APPROVE = 0xFF2E8B57, DENY = 0xFF3A414B, RADIUS_DP = 20, PAD_DP = 18;
    private static final int FLAG_FROM_ACCESSIBILITY = 0x800;

    private final AccessibilityService svc;
    private View view;

    ApprovalCard(AccessibilityService svc) { this.svc = svc; }

    /** Main thread. Replaces any card already showing. */
    void show(Model m, Listener l) {
        hide();
        view = build(m, l);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM;
        p.y = dp(56);
        svc.getSystemService(WindowManager.class).addView(view, p);
    }

    /** Main thread. */
    void hide() {
        if (view == null) return;
        try { svc.getSystemService(WindowManager.class).removeView(view); } catch (Exception ignored) {}
        view = null;
    }

    View build(Model m, Listener l) {
        LinearLayout card = new LinearLayout(svc);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(PAD_DP), dp(PAD_DP), dp(PAD_DP), dp(PAD_DP));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setCornerRadius(dp(RADIUS_DP));
        card.setBackground(bg);
        card.addView(header(m));
        card.addView(row("App", m.app));
        card.addView(row("Target", m.target));
        if (!m.text.isEmpty()) card.addView(row("Text", "“" + m.text + "”"));
        card.addView(row("Task", m.task));
        card.addView(row("Why", m.tier + " · " + m.reason));
        card.addView(chips(m));
        card.addView(buttons(l));
        LinearLayout outer = new LinearLayout(svc);
        outer.setPadding(dp(10), 0, dp(10), 0);
        outer.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return outer;
    }

    View header(Model m) {
        TextView t = text("AgentOS wants to: " + m.title, 18, FG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    View row(String k, String v) {
        TextView t = text(k + "   " + v, 14, FG);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    /** The Rule-of-Two chips: red when that property holds for this action. All three red = the dangerous combination. */
    View chips(Model m) {
        LinearLayout r = new LinearLayout(svc);
        r.setPadding(0, dp(10), 0, dp(4));
        r.addView(chip("untrusted input", m.untrusted));
        r.addView(chip("private data", m.priv));
        r.addView(chip("outward", m.outward));
        return r;
    }

    View chip(String label, boolean on) {
        TextView t = text((on ? "● " : "○ ") + label, 12, on ? FG : MUTED);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(on ? CHIP_ON : CHIP_OFF);
        bg.setCornerRadius(dp(12));
        t.setBackground(bg);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.rightMargin = dp(6);
        t.setLayoutParams(p);
        return t;
    }

    View buttons(Listener l) {
        LinearLayout r = new LinearLayout(svc);
        r.setPadding(0, dp(12), 0, 0);
        r.addView(button("Deny", DENY, l::deny), new LinearLayout.LayoutParams(0, dp(48), 1));
        View gap = new View(svc);
        r.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));
        r.addView(button("Approve with fingerprint", APPROVE, l::approve), new LinearLayout.LayoutParams(0, dp(48), 2));
        return r;
    }

    Button button(String label, int color, Runnable onTap) {
        Button b = new Button(svc);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(FG);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        b.setFilterTouchesWhenObscured(true);
        b.setOnTouchListener((v, e) -> (e.getFlags() & FLAG_FROM_ACCESSIBILITY) != 0); // swallow agent-made touches
        b.setOnClickListener(v -> onTap.run());
        return b;
    }

    TextView text(String s, int sp, int color) {
        TextView t = new TextView(svc);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    int dp(int v) { return Math.round(v * svc.getResources().getDisplayMetrics().density); }
}
