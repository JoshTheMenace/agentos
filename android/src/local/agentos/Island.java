package local.agentos;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The island: a black pill just below the camera hole and status bar (placed from the window insets, never a fixed
 * offset, so it clears the cutout on any phone), drawn as TYPE_ACCESSIBILITY_OVERLAY so no app or web content can
 * fake it. It breathes while the agent works (orb + step label + progress ring) and springs open into a card
 * (approval, security alert, task lease, or the task sheet with Stop). Think of a pupil dilating: the shape is one
 * clip path animated with invalidate() only, so there is no layout work per frame; the window is resized once per
 * state change. Style: A2 "Calm Glass iOS Dark" (design/mockups/A2-calm-glass-ios-dark.html); the tokens below are
 * the only colors in the overlays.
 *
 * API (main thread): set(state, label, pct) drives the pill; expand(face, accent) / collapse() open and close a card;
 * refit() after a card changes height; hide() removes the window. Software-made touches (HumanTouch.injected)
 * are swallowed, so the agent can't press anything here.
 */
final class Island extends FrameLayout {
    // ---- A2 tokens
    static final int L1 = 0xFFFFFFFF, L2 = 0x99EBEBF5, L3 = 0x4DEBEBF5, HAIR = 0x17FFFFFF, FILL14 = 0x24FFFFFF,
            BLUE = 0xFF0A84FF, GREEN = 0xFF30D158, ORANGE = 0xFFFF9F0A, RED = 0xFFFF453A, AMBER = 0xFFFFC43D, IDLE = 0xFF8E8E93;
    static final int TOP_DP = 8, PILL_H = 37, PILL_W = 126, CARD_W = 378, CARD_R = 44, WIN_PILL_W = 240, SLACK = 20;
    static final Interpolator SPRING = new OvershootInterpolator(1f), EASE = new DecelerateInterpolator(2f);

    private final WindowManager wm;
    private final WindowManager.LayoutParams lp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), hair = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF();
    final LinearLayout pill;
    final Orb orb;
    final TextView label;
    final Ring ring;
    private float sw, sh, sr, pillW;   // current shape and the pill's target width (px)
    private int accent = IDLE;
    String state = "idle", goal = "";
    int pct = -1;
    private View face;
    private boolean shown, open, active, closing; // closing: a collapse whose clean-up (drop the card, shrink the window) is still due
    private ValueAnimator anim;
    /** Set by the host: the pill was tapped (open the task sheet); the user pressed Stop. */
    Runnable onPillTap, onStop;
    private final Runnable autoHide = this::hide;

    Island(Context c) {
        super(c);
        wm = c.getSystemService(WindowManager.class);
        lp = new WindowManager.LayoutParams(dp(WIN_PILL_W), dp(PILL_H + 4), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        place();
        fill.setColor(Color.BLACK);
        hair.setStyle(Paint.Style.STROKE); hair.setStrokeWidth(dp(1)); hair.setColor(HAIR);
        sw = pillW = dp(PILL_W); sh = dp(PILL_H); sr = sh / 2;

        pill = new LinearLayout(c);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(12), 0, dp(10), 0);
        pill.setClipChildren(false); pill.setClipToPadding(false); // the orb's glow and halo reach past its 12 dp box
        pill.addView(orb = new Orb(c, accent), dp(12), dp(12));
        label = text(c, "AgentOS", 14, 600, L1);
        label.setSingleLine(); label.setEllipsize(TextUtils.TruncateAt.END); label.setMaxWidth(dp(150));
        pill.addView(label, margin(dp(8), 0));
        pill.addView(ring = new Ring(c, accent), new LinearLayout.LayoutParams(dp(20), dp(20)) {{ leftMargin = dp(8); }});
        ring.setVisibility(GONE);
        addView(pill, new LayoutParams(-2, dp(PILL_H), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        setOnClickListener(v -> { if (!open && onPillTap != null) onPillTap.run(); });
    }

    // ------------------------------------------------------------------ pill

    /** state: idle | working | approval | done | alert (null keeps). label null keeps; pct < 0 hides the ring. */
    void set(String st, String lbl, int p) {
        if (!shown) show();
        removeCallbacks(autoHide);
        if (st != null && !st.equals(state)) { state = st; tint(color(st)); orb.state(st); }
        active = "working".equals(state); // only a running task keeps the pill; other states are moments that fade
        label.setMaxWidth(dp(p >= 0 ? 150 : 190)); // a moment without the ring ("Blocked · Google Analytics") can use the ring's room
        if (p >= 0 != (ring.getVisibility() == VISIBLE)) { ring.setVisibility(p >= 0 ? VISIBLE : GONE); fit(); }
        if (p >= 0) ring.to(p / 100f);
        pct = p;
        if (lbl != null && !lbl.contentEquals(label.getText())) swap(lbl);
        if ("done".equals(state)) postDelayed(autoHide, 3000);
    }

    static int color(String st) {
        switch (st) {
            case "working": return ORANGE;
            case "approval": return BLUE;
            case "done": return GREEN;
            case "alert": return AMBER; // flagged: calm, not alarming (founder 10-28)
            default: return IDLE;
        }
    }

    int accent() { return accent; }

    /** The orb state a card header in this accent should show. */
    static String stateFor(int color) {
        return color == BLUE ? "approval" : color == GREEN ? "done" : color == ORANGE ? "working" : color == AMBER || color == RED ? "alert" : "idle";
    }

    private void tint(int to) {
        ValueAnimator v = ValueAnimator.ofObject(new ArgbEvaluator(), accent, to);
        v.setDuration(600);
        v.addUpdateListener(u -> { accent = orb.color = ring.color = (int) u.getAnimatedValue(); ring.invalidate(); });
        v.start();
    }

    /** New label at once, brightening in (a fade out first leaves an empty pill for a visible beat), and the pill re-fits. */
    private void swap(String s) {
        label.animate().cancel();
        label.setText(s); label.setAlpha(.25f);
        label.animate().alpha(1).setDuration(220).start();
        fit();
    }

    private void fit() {
        pill.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        pillW = Math.max(dp(PILL_W), pill.getMeasuredWidth());
        if (!open) morph(pillW, dp(PILL_H), dp(PILL_H) / 2f, 0, 0, 450, SPRING, this::closed); // takes over a running collapse's clean-up
    }

    // ------------------------------------------------------------------ cards

    /** Spring the pill open into this view (CARD_W wide, measured height). */
    void expand(View f, int acc) {
        if (!shown) show();
        modsHold(true);
        removeCallbacks(autoHide);
        closing = false;
        if (face != null) removeView(face);
        face = f;
        f.setAlpha(0);
        addView(f, new LayoutParams(dp(CARD_W), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        int fh = measureFace();
        open = true;
        tint(acc);
        window(dp(CARD_W), fh + dp(SLACK)); // once, before the motion
        morph(dp(CARD_W), fh, dp(CARD_R), 0, 1, 560, SPRING, null);
    }

    /** Close the card back into the pill. The pill then auto-hides unless a task is active. */
    /** A card is open or still closing (Mods won't sample colors under it). */
    boolean carded() { return open || closing; }

    void collapse() {
        if (!open) return;
        open = false;
        closing = true;
        tint(color(state));
        morph(pillW, dp(PILL_H), dp(PILL_H) / 2f, 1, 0, 380, EASE, this::closed);
    }

    /** End of a collapse (or of a pill re-fit that cancelled it): drop the card, shrink the window to the pill. */
    private void closed() {
        if (open || !closing) return;
        closing = false;
        if (face != null) removeView(face);
        face = null;
        window(dp(WIN_PILL_W), dp(PILL_H + 4));
        modsHold(false);
        if (!active) postDelayed(autoHide, "done".equals(state) ? 3000 : 1500);
    }

    /** The open card changed its content height (details toggled). */
    void refit() {
        if (!open || face == null) return;
        int fh = measureFace();
        window(dp(CARD_W), Math.max(fh, (int) sh) + dp(SLACK));
        morph(dp(CARD_W), fh, dp(CARD_R), 1, 1, 360, EASE, () -> window(dp(CARD_W), fh + dp(SLACK)));
    }

    boolean isOpen() { return open; }

    private int measureFace() {
        face.measure(MeasureSpec.makeMeasureSpec(dp(CARD_W), MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED);
        return face.getMeasuredHeight();
    }

    /** One animator for width, height, radius and the pill/card crossfade (o: 0 = pill, 1 = card). */
    private void morph(float tw, float th, float tr, float o0, float o1, long dur, Interpolator in, Runnable end) {
        if (anim != null) anim.cancel();
        float w0 = sw, h0 = sh, r0 = sr;
        View f = face;
        if (f != null) f.setLayerType(LAYER_TYPE_HARDWARE, null);
        pill.setLayerType(LAYER_TYPE_HARDWARE, null);
        anim = ValueAnimator.ofFloat(0, 1);
        anim.setDuration(dur); anim.setInterpolator(in);
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue(), c = clamp(t), o = lerp(o0, o1, c);
            sw = lerp(w0, tw, t); sh = lerp(h0, th, t); sr = lerp(r0, tr, c);
            pill.setAlpha(clamp(1 - o * 2.5f));
            if (f != null) {
                f.setAlpha(clamp((o - .4f) / .6f));
                f.setTranslationY((1 - o) * dp(8));
                f.setScaleX(.97f + .03f * o); f.setScaleY(.97f + .03f * o);
            }
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(Animator a) { cancelled = true; } // a newer morph took over: its end work is not ours
            @Override public void onAnimationEnd(Animator a) {
                if (f != null) f.setLayerType(LAYER_TYPE_NONE, null);
                pill.setLayerType(LAYER_TYPE_NONE, null);
                if (end != null && !cancelled) end.run();
            }
        });
        anim.start();
    }

    // ------------------------------------------------------------------ window

    void show() {
        if (shown) return;
        shown = true;
        removeCallbacks(autoHide);
        animate().cancel();
        place();
        if (getParent() == null) { setAlpha(0); setScaleX(.6f); setScaleY(.6f); setPivotY(0); wm.addView(this, lp); }
        animate().alpha(1).scaleX(1).scaleY(1).setDuration(420).setInterpolator(SPRING).start();
    }

    /** Fade out and remove the window. State resets now; only the removal waits for the fade, and show() can cancel it. */
    void hide() {
        if (!shown) return;
        shown = false; open = false; active = false; closing = false; state = "idle"; pct = -1;
        accent = orb.color = ring.color = IDLE; // the next pill starts grey, not in the last card's colour
        orb.state("idle");
        removeCallbacks(autoHide);
        if (anim != null) anim.cancel();
        if (face != null) removeView(face);
        face = null;
        pill.setAlpha(1);
        sw = pillW; sh = dp(PILL_H); sr = sh / 2;
        lp.width = dp(WIN_PILL_W); lp.height = dp(PILL_H + 4);
        wm.updateViewLayout(this, lp);
        modsHold(false);
        animate().alpha(0).scaleX(.6f).scaleY(.6f).setDuration(260).setInterpolator(EASE).withEndAction(() -> {
            if (shown) return; // shown again during the fade
            try { wm.removeView(this); } catch (Exception ignored) {}
        }).start();
    }

    private void modsHold(boolean on) {
        Broker b = ((AgentA11y) getContext()).broker;
        if (b != null) b.mods.holdForApproval(on);
    }

    /** Top edge = TOP_DP below whatever is taller, the camera cutout or the status bar (rotation and phone agnostic).
     *  Always from the display's own insets: the ones dispatched to this window are relative to its frame, so once the
     *  pill sits below the bar they read 0 and it would jump up into the status bar. */
    private void place() {
        int y = wm.getCurrentWindowMetrics().getWindowInsets().getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top + dp(TOP_DP);
        if (y == lp.y) return;
        lp.y = y;
        if (shown) wm.updateViewLayout(this, lp);
    }

    @Override public WindowInsets onApplyWindowInsets(WindowInsets in) { place(); return super.onApplyWindowInsets(in); }

    private void window(int w, int h) {
        lp.width = w; lp.height = h;
        if (shown) wm.updateViewLayout(this, lp);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        setPivotX(getWidth() / 2f);
    }

    @Override protected void dispatchDraw(Canvas c) {
        float l = (getWidth() - sw) / 2f;
        box.set(l, 0, l + sw, sh);
        c.drawRoundRect(box, sr, sr, fill);
        clip.reset(); clip.addRoundRect(box, sr, sr, Path.Direction.CW);
        c.save(); c.clipPath(clip);
        super.dispatchDraw(c);
        c.restore();
        box.inset(dp(1) / 2f, dp(1) / 2f);
        c.drawRoundRect(box, sr, sr, hair);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (HumanTouch.injected(e)) return true; // agent-made touch: swallow
        if (e.getAction() == MotionEvent.ACTION_DOWN && !box.contains(e.getX(), e.getY())) return false;
        return super.dispatchTouchEvent(e);
    }

    // ------------------------------------------------------------------ drawing primitives

    /** The agent's presence: a 12 dp glass sphere (white specular, accent body, dark rim, soft glow) whose motion says the
     *  state. working: a light orbits it, behind then in front. approval: it breathes inside a halo. done: it pops and a
     *  check draws in. alert: a calm, slow pulse. idle: nearly still. States cross-fade through presence weights (480 ms;
     *  the done weight overshoots, which is the pop); the Island tints the colour. Canvas only: one small view, redrawn
     *  per frame while attached, transform and alpha math on the CPU side is a few multiplies. */
    static final class Orb extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), s = new Paint(Paint.ANTI_ALIAS_FLAG);
        final ValueAnimator clock = ValueAnimator.ofFloat(0, 1);
        final Path check = new Path(), trimmed = new Path();
        final PathMeasure pm = new PathMeasure();
        final float[] w = new float[4]; // presence of working, approval, done, alert
        final ValueAnimator[] fade = new ValueAnimator[4];
        int color;
        float t; // seconds, wraps every 48 s
        Orb(Context c, int color) { this(c, color, stateFor(color)); }
        Orb(Context c, int color, String st) {
            super(c);
            this.color = color;
            clock.setDuration(48_000); clock.setRepeatCount(-1); clock.setInterpolator(new LinearInterpolator());
            clock.addUpdateListener(v -> { t = (float) v.getAnimatedValue() * 48; invalidate(); });
            s.setStyle(Paint.Style.STROKE); s.setStrokeCap(Paint.Cap.ROUND); s.setStrokeJoin(Paint.Join.ROUND);
            int i = idx(st);
            if (i >= 0) w[i] = 1;
        }
        static int idx(String st) {
            switch (st) { case "working": return 0; case "approval": return 1; case "done": return 2; case "alert": return 3; default: return -1; }
        }
        /** Cross-fade to a state: the old motion fades while the new one grows. */
        void state(String st) {
            int to = idx(st);
            for (int i = 0; i < 4; i++) {
                float goal = i == to ? 1 : 0;
                if (fade[i] != null) fade[i].cancel();
                if (w[i] == goal) continue;
                boolean pop = i == 2 && goal == 1;
                ValueAnimator a = fade[i] = ValueAnimator.ofFloat(w[i], goal);
                a.setDuration(pop ? 640 : 480); a.setInterpolator(pop ? new OvershootInterpolator(1.8f) : EASE);
                final int k = i;
                a.addUpdateListener(v -> w[k] = (float) v.getAnimatedValue());
                a.start();
            }
        }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); clock.start(); }
        @Override protected void onDetachedFromWindow() { clock.cancel(); super.onDetachedFromWindow(); }
        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, R = Math.min(cx, cy);
            float wW = w[0], wA = w[1], wD = clamp(w[2]), wF = w[3], wI = clamp(1 - wW - wA - wD - wF);
            float br = (float) Math.sin(t / 2.4 * 2 * Math.PI) * .5f + .5f;   // 2.4 s breath (A2 `breathe`)
            float slow = (float) Math.sin(t / 3.6 * 2 * Math.PI) * .5f + .5f; // 3.6 s calm pulse
            float pop = w[2] > 1 ? 1 + (w[2] - 1) * .7f : 1;                  // the overshoot of the done fade
            float r = R * .7f * (1 + .12f * wA * br + .05f * wF * slow + .025f * wI * slow) * pop;
            int dark = Color.rgb(Color.red(color) * 11 / 20, Color.green(color) * 11 / 20, Color.blue(color) * 11 / 20);
            // halo (approval): a thin ring breathing outward
            if (wA > .01f) {
                s.setStrokeWidth(R * .1f); s.setColor(color); s.setAlpha((int) (110 * wA * (1 - br * .55f)));
                c.drawCircle(cx, cy, r * (1.3f + .32f * br), s);
            }
            // orbit (working): a light passes behind the sphere, then in front
            double th = t / 1.7 * 2 * Math.PI;
            float ox = cx + (float) Math.cos(th) * r * 1.08f, oy = cy + (float) Math.sin(th) * r * .45f;
            boolean front = Math.sin(th) > 0;
            if (wW > .01f && !front) spark(c, ox, oy, R * .17f, wW * .6f);
            // the sphere: specular top-left, accent body, dark rim; the glow grows with the state
            float glow = R * (.35f + .4f * wA * br + .25f * wW + .3f * wF * slow + .35f * wD);
            p.setShadowLayer(glow, 0, 0, (color & 0x00FFFFFF) | ((int) (140 + 70 * wD) << 24));
            p.setShader(new RadialGradient(cx - r * .32f, cy - r * .38f, r * 1.35f, new int[]{0xF7FFFFFF, color, dark}, new float[]{0, .42f, 1}, Shader.TileMode.CLAMP));
            p.setAlpha(255);
            c.drawCircle(cx, cy, r, p);
            p.clearShadowLayer();
            // gloss cap: the glass look
            p.setShader(new RadialGradient(cx, cy - r * .6f, r * .85f, 0x80FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.save(); c.clipRect(cx - r, cy - r, cx + r, cy); c.drawCircle(cx, cy, r, p); c.restore();
            p.setShader(null);
            if (wW > .01f && front) spark(c, ox, oy, R * .2f, wW);
            // check (done): draws in with the pop, white on the green
            if (wD > .01f) {
                check.reset();
                check.moveTo(cx - r * .44f, cy + r * .02f); check.lineTo(cx - r * .12f, cy + r * .34f); check.lineTo(cx + r * .48f, cy - r * .34f);
                pm.setPath(check, false); trimmed.reset(); pm.getSegment(0, pm.getLength() * wD, trimmed, true);
                s.setStrokeWidth(R * .21f); s.setColor(Color.WHITE); s.setAlpha((int) (255 * wD));
                c.drawPath(trimmed, s);
            }
        }
        /** The orbiting light: a white-hot dot with a glow in the accent. */
        private void spark(Canvas c, float x, float y, float rad, float a) {
            p.setShadowLayer(rad * 1.8f, 0, 0, (color & 0x00FFFFFF) | ((int) (210 * a) << 24));
            p.setColor(Color.WHITE); p.setAlpha((int) (240 * a));
            c.drawCircle(x, y, rad, p);
            p.clearShadowLayer();
        }
    }

    /** Progress ring: 3 dp track, accent arc from 12 o'clock, eased. */
    static final class Ring extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        float frac;
        int color;
        Ring(Context c, int color) { super(c); this.color = color; p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); }
        void to(float f) {
            ValueAnimator v = ValueAnimator.ofFloat(frac, f);
            v.setDuration(1200); v.setInterpolator(EASE);
            v.addUpdateListener(u -> { frac = (float) u.getAnimatedValue(); invalidate(); });
            v.start();
        }
        @Override protected void onDraw(Canvas c) {
            float s = getWidth() * 0.15f;
            p.setStrokeWidth(s);
            r.set(s, s, getWidth() - s, getHeight() - s);
            p.setColor(0x1FFFFFFF); c.drawOval(r, p);
            p.setColor(color); c.drawArc(r, -90, 360 * frac, false, p);
        }
    }

    /** Thin progress track for the task sheet. */
    static final class Bar extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float frac; int color;
        Bar(Context c, int color, float frac) { super(c); this.color = color; this.frac = frac; }
        @Override protected void onDraw(Canvas c) {
            float h = getHeight();
            p.setColor(0x26FFFFFF); c.drawRoundRect(0, 0, getWidth(), h, h, h, p);
            p.setColor(color); c.drawRoundRect(0, 0, getWidth() * frac, h, h, h, p);
        }
    }

    /** Small line icons drawn by hand (no image assets): fingerprint, shield, clock, stop. */
    static final class Glyph extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        final Path path = new Path();
        final String kind; int color;
        Glyph(Context c, String kind, int color) {
            super(c); this.kind = kind; this.color = color;
            p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override protected void onDraw(Canvas c) {
            float u = getWidth() / 24f;
            p.setColor(color); p.setStrokeWidth(1.7f * u);
            switch (kind) {
                case "finger":
                    for (float k : new float[]{4, 7, 10}) { r.set(12 - k * u, 13 - k * u, 12 + k * u, 13 + k * u); c.drawArc(r, 205, 130, false, p); }
                    c.drawLine(12 * u, 13 * u, 12 * u, 20 * u, p);
                    r.set(5 * u, 6 * u, 19 * u, 20 * u); c.drawArc(r, 250, 80, false, p);
                    break;
                case "shield":
                    path.reset(); path.moveTo(12 * u, 3 * u); path.lineTo(5 * u, 6 * u); path.lineTo(5 * u, 11 * u);
                    path.cubicTo(5 * u, 16 * u, 8.5f * u, 19.5f * u, 12 * u, 21 * u);
                    path.cubicTo(15.5f * u, 19.5f * u, 19 * u, 16 * u, 19 * u, 11 * u); path.lineTo(19 * u, 6 * u); path.close();
                    c.drawPath(path, p);
                    c.drawLine(12 * u, 9 * u, 12 * u, 13 * u, p); c.drawPoint(12 * u, 16 * u, p);
                    break;
                case "clock":
                    c.drawCircle(12 * u, 12 * u, 8.5f * u, p);
                    c.drawLine(12 * u, 7 * u, 12 * u, 12 * u, p); c.drawLine(12 * u, 12 * u, 15.5f * u, 14 * u, p);
                    break;
                default:
                    p.setStyle(Paint.Style.FILL); r.set(6 * u, 6 * u, 18 * u, 18 * u); c.drawRoundRect(r, 2.5f * u, 2.5f * u, p);
                    p.setStyle(Paint.Style.STROKE);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    static TextView text(Context c, CharSequence s, float sp, int weight, int color) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        t.setTypeface(Typeface.create(Typeface.SANS_SERIF, weight, false));
        t.setLetterSpacing(sp >= 17 ? -0.025f : -0.01f);
        return t;
    }

    static LinearLayout.LayoutParams margin(int left, int top) { return new LinearLayout.LayoutParams(-2, -2) {{ leftMargin = left; topMargin = top; }}; }

    /** Round-rect fill (radius = half the height, i.e. a capsule, unless radiusDp > 0) with an optional 1 dp stroke. */
    static Drawable capsule(Context c, int fillColor, int stroke, float radiusDp) {
        return new Drawable() {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas cv) {
                RectF b = new RectF(getBounds());
                float r = radiusDp > 0 ? radiusDp * c.getResources().getDisplayMetrics().density : b.height() / 2;
                p.setStyle(Paint.Style.FILL); p.setColor(fillColor); cv.drawRoundRect(b, r, r, p);
                if (stroke != 0) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setColor(stroke); b.inset(.75f, .75f); cv.drawRoundRect(b, r, r, p); }
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(ColorFilter f) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    static float clamp(float t) { return Math.max(0, Math.min(1, t)); }
    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
