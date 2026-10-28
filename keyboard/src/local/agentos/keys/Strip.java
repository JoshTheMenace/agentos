package local.agentos.keys;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * The AI strip where QuickType sits, one quiet row: [status dot] [three suggestions with dividers] [mode pill].
 * Nothing else is written on it. Tap or hold the dot to see the context details ("Pigeon · 6 lines · 1 hidden · 1.6s")
 * for a moment; tap the pill to cycle Reply → Rewrite → Fix. Skeleton bars shimmer while loading.
 * Readable mode: when three cells would cut a suggestion short, the strip grows upward into a glass panel that lists the
 * suggestions as full-width rows (2–3 lines each). It opens by itself while the draft is empty (you are choosing a reply),
 * and on "+2", a swipe up, or a long-press on any chip; a tap inserts, a swipe down / the chevron / typing closes it.
 */
final class Strip extends View {
    interface Listener { void onSuggestion(String text); void onMode(int mode); void onWrite(); void onDetailAction(String action); }
    static final String ACT_TRANSLATE = "Translate", ACT_CATCH_UP = "Catch up";
    static final String[] MODES = {"Reply", "Rewrite", "Fix"};

    private final Listener listener;
    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF(), dot = new RectF(), pill = new RectF(), wand = new RectF();
    private final RectF[] actRects = {new RectF(), new RectF()};
    private final String[] acts = {null, null}; // detail-line actions (Translate, Catch up), set by Keys
    private String notice; // send-safety line; replaces the suggestions while set
    private final RectF[] cells = {new RectF(), new RectF(), new RectF()};
    // readable mode
    private final TextPaint rowText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), hair = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF more = new RectF(), chev = new RectF(), panel = new RectF();
    private final RectF[] rows = {new RectF(), new RectF(), new RectF()};
    private final StaticLayout[] lay = new StaticLayout[3];
    private final OvershootInterpolator spring = new OvershootInterpolator(1.1f);
    private final DecelerateInterpolator decel = new DecelerateInterpolator(1.5f);
    private boolean open, closing;
    private long openAt;
    private int pressedRow = -1, heldRow = -1;
    private float downX, downY;
    private final Runnable longPress = () -> { heldRow = this.pressedCell; this.pressedCell = -1; expand(true); }; // preview: the held chip's row lit, nothing inserted
    private final List<String> items = new ArrayList<>();
    private String typed = ""; // Reply mode: the draft every chip starts with; chips show only what they add ("…tonight?")
    private String detail = "";
    private int orb = Theme.IDLE, mode;
    private boolean loading, tappable = true;
    private long shownAt, detailUntil;
    private int pressedCell = -1;
    private final Runnable hideDetail = this::invalidate;

    Strip(Context c, Listener l) {
        super(c);
        listener = l;
        d = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(Theme.BG);
        line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(d); line.setColor(Theme.DIVIDER);
        ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(d); ring.setColor(Theme.GLASS_RING);
        hair.setStyle(Paint.Style.STROKE); hair.setStrokeWidth(d); hair.setColor(Theme.HAIR);
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(1.5f * d); stroke.setStrokeCap(Paint.Cap.ROUND); stroke.setColor(Theme.TEXT3);
        rowText.setTextSize(Theme.ROW_TEXT * d); rowText.setTypeface(Theme.font(c, Theme.W_CHIP)); rowText.setColor(Theme.TEXT);
    }

    // ---- state ----
    /** detail: what was used, shown only on request. orbColor: idle / working / ready / blocked. */
    void setContext(String detail, int orbColor) { this.detail = detail; orb = orbColor; invalidate(); }
    void setMode(int m) { mode = m; invalidate(); }
    /** Calm send-safety warning (null = none). While set, nothing else is shown in the row and nothing is requested. */
    void setNotice(String n) { notice = n; if (n != null) expand(false); invalidate(); }
    boolean hasNotice() { return notice != null; }
    /** Actions offered on the detail line (null = hidden): translate the last incoming message, catch me up. */
    void setActions(String translate, String catchUp) { acts[0] = translate; acts[1] = catchUp; }
    void setLoading() { loading = true; items.clear(); expand(false); invalidate(); }
    void setTyped(String draft) { typed = draft == null ? "" : draft; }
    /** Replace the suggestions; tappable=false shows a plain message (e.g. "Type a draft to rewrite"). */
    void setItems(List<String> list, boolean tappable) {
        boolean keep = loading && !items.isEmpty() && !list.isEmpty() && items.get(0).equals(list.get(0)); // the streamed first chip doesn't fade in twice
        loading = false; items.clear(); items.addAll(list); this.tappable = tappable; if (!keep) shownAt = SystemClock.uptimeMillis(); invalidate();
        if (tappable && typed.isEmpty() && cramped()) { if (!open || closing) openAt = SystemClock.uptimeMillis(); open = true; closing = false; requestLayout(); } // choosing a reply: make them readable
        else expand(false);
    }
    /** Readable mode: open with a spring, or settle closed (the window shrinks when the motion ends). */
    void expand(boolean on) {
        if (on == open && !closing) return;
        if (on) { open = true; closing = false; requestLayout(); } else closing = true;
        openAt = SystemClock.uptimeMillis(); invalidate();
    }
    /** Snap closed without motion (new field). */
    void collapse() { open = closing = false; heldRow = pressedRow = -1; requestLayout(); invalidate(); }
    /** Width left for the suggestion cells: [dot] [cells] [✨] [pill]. */
    private float cellAvail(float W) {
        text.setTextSize(Theme.MODE_TEXT * d); text.setTypeface(Theme.font(getContext(), Theme.W_CTX));
        float pw = Math.max(Theme.MODE_H * d, text.measureText(mode == 0 ? Theme.MODE_GLYPH : MODES[mode]) + 2 * Theme.MODE_PAD_X * d);
        return W - (Theme.STRIP_PAD_X + 2 * Theme.DOT_TOUCH + Theme.CELL_GAP + 2) * d - pw;
    }
    /** What a chip shows: in Reply mode with a draft, only what the suggestion adds ("…tonight?"); newlines flattened. */
    private String shown(String it) {
        if (tappable && !typed.isEmpty() && it.length() > typed.length() && it.regionMatches(true, 0, typed, 0, typed.length())) it = "…" + it.substring(typed.length()).trim();
        return it.replace('\n', ' ').replaceAll("\\s+", " ");
    }
    /** True when three side-by-side cells would cut one of the suggestions short. */
    private boolean cramped() {
        if (!tappable || items.size() < 2 || getWidth() == 0) return false;
        float w = cellAvail(getWidth()) / 3 - 2 * Theme.CHIP_PAD_X * d;
        text.setTextSize(Theme.CHIP_TEXT * d); text.setTypeface(Theme.font(getContext(), Theme.W_CHIP));
        for (String s : items) if (text.measureText(shown(s)) > w) return true;
        return false;
    }
    /** The streamed first suggestion: it shows in the first cell while the other two keep shimmering. False if the set already came. */
    boolean setFirst(String s) {
        if (!loading || !items.isEmpty()) return false;
        items.add(s); tappable = true; shownAt = SystemClock.uptimeMillis(); invalidate(); return true;
    }
    private void showDetail(long ms) { detailUntil = SystemClock.uptimeMillis() + ms; removeCallbacks(hideDetail); postDelayed(hideDetail, ms + 16); invalidate(); }

    @Override protected void onMeasure(int w, int h) {
        float W = MeasureSpec.getSize(w), ph = 0;
        if (open) { // one full-width wrapped row per suggestion, stacked in a glass panel above the usual row
            int tw = Math.round(W - 2 * (Theme.PANEL_X + Theme.ROW_X) * d);
            float y = (Theme.PANEL_Y + Theme.PANEL_PAD) * d;
            for (int i = 0; i < 3; i++) {
                String s = i < items.size() ? items.get(i).trim() : null;
                lay[i] = s == null ? null : StaticLayout.Builder.obtain(s, 0, s.length(), rowText, tw).setMaxLines(Theme.ROW_LINES).setEllipsize(TextUtils.TruncateAt.END).build();
                if (lay[i] == null) { rows[i].setEmpty(); continue; }
                float rh = lay[i].getHeight() + 2 * Theme.ROW_Y * d;
                rows[i].set(Theme.PANEL_X * d, y, W - Theme.PANEL_X * d, y + rh); y += rh;
            }
            panel.set(Theme.PANEL_X * d, Theme.PANEL_Y * d, W - Theme.PANEL_X * d, y + Theme.PANEL_PAD * d);
            ph = panel.bottom;
        }
        setMeasuredDimension((int) W, Math.round(ph + d * (Theme.STRIP_PAD_TOP + Theme.CHIP_H + Theme.STRIP_PAD_BOTTOM)));
    }

    /** The glass panel: rises 24 dp with a spring on open, settles on close; only transform and opacity move. */
    private void drawPanel(Canvas c, long now, float rowTop) {
        float p = Math.min(1f, (now - openAt) / (float) (closing ? Theme.PANEL_OUT : Theme.PANEL_IN));
        if (closing && p >= 1) { open = closing = false; requestLayout(); return; }
        float e = closing ? 1 - decel.getInterpolation(p) : spring.getInterpolation(p);
        int a = Math.round(255 * Math.max(0, Math.min(1, closing ? e : p * 3)));
        c.save(); c.clipRect(0, 0, getWidth(), rowTop);
        c.translate(0, (1 - e) * 24 * d); c.scale(.96f + .04f * e, .96f + .04f * e, panel.centerX(), panel.bottom);
        fill.setShader(new LinearGradient(panel.left, panel.top, panel.right, panel.bottom, Theme.GLASS_HI, Theme.GLASS_LO, Shader.TileMode.CLAMP)); fill.setAlpha(a);
        c.drawRoundRect(panel, Theme.PANEL_R * d, Theme.PANEL_R * d, fill); fill.setShader(null); fill.setAlpha(255);
        ring.setAlpha(a * (Theme.GLASS_RING >>> 24) / 255); c.drawRoundRect(panel, Theme.PANEL_R * d, Theme.PANEL_R * d, ring);
        hair.setAlpha(a * (Theme.HAIR >>> 24) / 255); rowText.setAlpha(a);
        for (int i = 0; i < 3 && lay[i] != null; i++) {
            RectF r = rows[i];
            if (i > 0) c.drawLine(r.left + Theme.ROW_X * d, r.top, r.right - Theme.ROW_X * d, r.top, hair);
            if (i == pressedRow || i == heldRow) { fill.setColor(Theme.CHIP_PRESSED); tmp.set(r); tmp.inset(6 * d, 3 * d); c.drawRoundRect(tmp, Theme.ROW_R * d, Theme.ROW_R * d, fill); }
            c.save(); c.translate(r.left + Theme.ROW_X * d, r.top + Theme.ROW_Y * d); lay[i].draw(c); c.restore();
        }
        c.restore();
        if (p < 1) postInvalidateOnAnimation();
    }

    private float baseline(float cy) { return cy - (text.ascent() + text.descent()) / 2; }

    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float bottom = getHeight() - Theme.STRIP_PAD_BOTTOM * d, top = bottom - Theme.CHIP_H * d, cy = (top + bottom) / 2;
        more.setEmpty(); chev.setEmpty();
        if (open) drawPanel(c, now, top - Theme.PANEL_Y * d / 2);

        // status dot (left) and mode pill (right)
        dot.set(0, top, Theme.DOT_TOUCH * d, bottom);
        fill.setColor(orb); c.drawCircle(dot.centerX(), cy, Theme.ORB * d / 2, fill);
        text.setTextSize(Theme.MODE_TEXT * d); text.setTypeface(Theme.font(getContext(), Theme.W_CTX)); text.setTextAlign(Paint.Align.CENTER);
        String pillText = mode == 0 ? Theme.MODE_GLYPH : MODES[mode]; // default mode is just a glyph; Rewrite / Fix are named so you know you're in them
        float pw = Math.max(Theme.MODE_H * d, text.measureText(pillText) + 2 * Theme.MODE_PAD_X * d);
        pill.set(getWidth() - Theme.STRIP_PAD_X * d - pw, cy - Theme.MODE_H * d / 2, getWidth() - Theme.STRIP_PAD_X * d, cy + Theme.MODE_H * d / 2);
        fill.setColor(mode == 0 ? Theme.MODE_BG : Theme.MODE_ON); c.drawRoundRect(pill, Theme.MODE_R, Theme.MODE_R, fill);
        text.setColor(mode == 0 ? Theme.TEXT2 : Theme.TEXT); c.drawText(pillText, pill.centerX(), baseline(cy), text);

        // ✨ Write for me, left of the pill
        wand.set(pill.left - Theme.DOT_TOUCH * d - 2 * d, top, pill.left - 2 * d, bottom);
        text.setTextSize(14 * d); text.setColor(Theme.TEXT); c.drawText(Theme.WAND, wand.centerX(), baseline(cy), text);

        float left = dot.right, avail = wand.left - Theme.CELL_GAP * d - left;
        text.setTextSize(Theme.CHIP_TEXT * d); text.setTypeface(Theme.font(getContext(), Theme.W_CHIP)); text.setTextAlign(Paint.Align.CENTER);
        for (RectF ch : cells) ch.setEmpty();

        for (RectF r : actRects) r.setEmpty();
        if (notice != null) { // send-safety: one quiet amber line, no pop-up
            text.setTextSize(Theme.CTX_TEXT * d); text.setTypeface(Theme.font(getContext(), Theme.W_CTX)); text.setColor(Theme.NOTICE);
            c.drawText(TextUtils.ellipsize(notice, text, avail - 8 * d, TextUtils.TruncateAt.END).toString(), left + avail / 2, baseline(cy), text);
            return;
        }
        if (now < detailUntil) { // the context line, only while asked for; actions on its right
            text.setTextSize(Theme.CTX_TEXT * d); text.setTypeface(Theme.font(getContext(), Theme.W_CTX));
            float right = left + avail;
            for (int i = acts.length - 1; i >= 0; i--) {
                if (acts[i] == null) continue;
                float aw = text.measureText(acts[i]) + 2 * Theme.MODE_PAD_X * d;
                actRects[i].set(right - aw, cy - Theme.MODE_H * d / 2, right, cy + Theme.MODE_H * d / 2);
                fill.setColor(Theme.MODE_BG); c.drawRoundRect(actRects[i], Theme.MODE_R, Theme.MODE_R, fill);
                text.setColor(Theme.KEY_ACCENT); c.drawText(acts[i], actRects[i].centerX(), baseline(cy), text);
                right -= aw + 4 * d;
            }
            text.setColor(Theme.TEXT2); text.setTextAlign(Paint.Align.LEFT);
            c.drawText(TextUtils.ellipsize(detail, text, right - 8 * d - left, TextUtils.TruncateAt.END).toString(), left, baseline(cy), text);
            text.setTextAlign(Paint.Align.CENTER);
            return;
        }
        if (loading) {
            float phase = (now % Theme.SHIMMER) / (float) Theme.SHIMMER, w = avail / 3;
            LinearGradient g = new LinearGradient(0, 0, avail, 0, new int[]{Theme.SHIMMER_LO, Theme.SHIMMER_HI, Theme.SHIMMER_LO}, new float[]{0, .5f, 1}, Shader.TileMode.CLAMP);
            Matrix m = new Matrix(); m.setTranslate(left - avail + 2 * avail * phase, 0); g.setLocalMatrix(m);
            fill.setShader(g);
            float[] widths = {.9f, 1f, .75f};
            for (int i = items.size(); i < 3; i++) {
                float sw = w * Theme.SKELETON_W * widths[i], sh = Theme.SKELETON_H * d, cx = left + w * (i + .5f);
                tmp.set(cx - sw / 2, cy - sh / 2, cx + sw / 2, cy + sh / 2);
                c.drawRoundRect(tmp, sh / 2, sh / 2, fill);
            }
            fill.setShader(null);
            postInvalidateOnAnimation();
            if (items.isEmpty()) return;
        }
        if (open) { // the row keeps the controls; a chevron in the middle closes the panel
            chev.set(left, top, left + avail, bottom); float cx = chev.centerX();
            c.drawLine(cx - 6 * d, cy - 2.5f * d, cx, cy + 3 * d, stroke); c.drawLine(cx, cy + 3 * d, cx + 6 * d, cy - 2.5f * d, stroke);
            return;
        }
        boolean wide = !loading && cramped();
        int n = loading ? 3 : wide ? 1 : items.size(); // loading with one item: the streamed first chip in a third of the strip
        if (n == 0) return;
        if (wide) { // the best suggestion reads wide; "+2" opens the rest
            text.setTextSize(Theme.MODE_TEXT * d); text.setTypeface(Theme.font(getContext(), Theme.W_CTX));
            String mt = "+" + (items.size() - 1); float mw = text.measureText(mt) + 2 * Theme.MODE_PAD_X * d;
            more.set(left + avail - mw, cy - Theme.MODE_H * d / 2, left + avail, cy + Theme.MODE_H * d / 2);
            fill.setColor(Theme.MODE_BG); c.drawRoundRect(more, Theme.MODE_R, Theme.MODE_R, fill);
            text.setColor(Theme.TEXT2); c.drawText(mt, more.centerX(), baseline(cy), text);
            avail -= mw + Theme.CELL_GAP * d;
            text.setTextSize(Theme.CHIP_TEXT * d); text.setTypeface(Theme.font(getContext(), Theme.W_CHIP));
        }
        float w = avail / n;
        boolean animating = false;
        for (int i = 0; i < items.size() && i < n; i++) {
            float t = tappable ? Math.min(1f, (now - shownAt - i * Theme.CHIP_STAGGER) / (float) Theme.CHIP_IN) : 1;
            if (t < 0) t = 0; if (t < 1) animating = true;
            float ease = 1 - (1 - t) * (1 - t);
            cells[i].set(left + w * i, top, left + w * (i + 1), bottom);
            if (i > 0) c.drawLine(cells[i].left, top + 8 * d, cells[i].left, bottom - 8 * d, line);
            tmp.set(cells[i]); tmp.inset(3 * d, 4 * d); tmp.offset(0, (1 - ease) * 6 * d);
            if (i == pressedCell) { fill.setColor(Theme.CHIP_PRESSED); c.drawRoundRect(tmp, Theme.CHIP_R * d, Theme.CHIP_R * d, fill); }
            text.setColor(tappable ? Theme.TEXT : Theme.TEXT3); text.setAlpha(Math.round(255 * ease));
            CharSequence s = TextUtils.ellipsize(shown(items.get(i)), text, w - 2 * Theme.CHIP_PAD_X * d, TextUtils.TruncateAt.END);
            c.drawText(s.toString(), tmp.centerX(), baseline(tmp.centerY()), text);
        }
        if (animating) postInvalidateOnAnimation();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX(); downY = e.getRawY(); // screen coords: the view grows under a held finger when the panel opens
                if (open && !closing && y < panel.bottom) { pressedRow = rowAt(x, y); invalidate(); return true; }
                if (chev.contains(x, y)) { expand(false); return true; }
                if (more.contains(x, y)) { expand(true); return true; }
                if (dot.contains(x, y)) { showDetail(acts[0] != null || acts[1] != null ? Theme.DETAIL_ACTIONS_MS : Theme.DETAIL_MS); return true; }
                if (wand.contains(x, y)) { listener.onWrite(); return true; }
                if (pill.contains(x, y)) { mode = (mode + 1) % 3; listener.onMode(mode); invalidate(); return true; }
                for (int i = 0; i < acts.length; i++) if (acts[i] != null && actRects[i].contains(x, y)) { detailUntil = 0; invalidate(); listener.onDetailAction(acts[i]); return true; }
                pressedCell = tappable ? cellAt(x, y) : -1; invalidate();
                if (pressedCell >= 0) postDelayed(longPress, Theme.LONG_PRESS); // hold a chip: preview its full text
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                if (Math.abs(dx) > 12 * d || Math.abs(dy) > 12 * d) removeCallbacks(longPress);
                if (dy > Theme.SWIPE * d && open) { expand(false); pressedRow = pressedCell = -1; invalidate(); }               // swipe down: close
                else if (-dy > Theme.SWIPE * d && !open && !more.isEmpty()) { expand(true); pressedCell = -1; invalidate(); } // swipe up: open
                return true;
            case MotionEvent.ACTION_UP:
                if (pressedRow >= 0 && rowAt(x, y) == pressedRow) { listener.onSuggestion(items.get(pressedRow)); expand(false); }
                if (pressedCell >= 0 && cellAt(x, y) == pressedCell) listener.onSuggestion(items.get(pressedCell));
                // fall through
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress); pressedCell = pressedRow = heldRow = -1; invalidate(); return true;
        }
        return true;
    }
    private int cellAt(float x, float y) { for (int i = 0; i < items.size() && i < 3; i++) if (cells[i].contains(x, y)) return i; return -1; }
    private int rowAt(float x, float y) { for (int i = 0; i < items.size() && i < 3; i++) if (rows[i].contains(x, y)) return i; return -1; }
}
