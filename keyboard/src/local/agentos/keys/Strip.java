package local.agentos.keys;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The AI strip where QuickType sits, one quiet row: [status dot] [three suggestions with dividers] [mode pill].
 * Nothing else is written on it. Tap or hold the dot to see the context details ("Pigeon · 6 lines · 1 hidden · 1.6s")
 * for a moment; tap the pill to cycle Reply → Rewrite → Fix. Skeleton bars shimmer while loading.
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
    }

    // ---- state ----
    /** detail: what was used, shown only on request. orbColor: idle / working / ready / blocked. */
    void setContext(String detail, int orbColor) { this.detail = detail; orb = orbColor; invalidate(); }
    void setMode(int m) { mode = m; invalidate(); }
    /** Calm send-safety warning (null = none). While set, nothing else is shown in the row and nothing is requested. */
    void setNotice(String n) { notice = n; invalidate(); }
    boolean hasNotice() { return notice != null; }
    /** Actions offered on the detail line (null = hidden): translate the last incoming message, catch me up. */
    void setActions(String translate, String catchUp) { acts[0] = translate; acts[1] = catchUp; }
    void setLoading() { loading = true; items.clear(); invalidate(); }
    void setTyped(String draft) { typed = draft == null ? "" : draft; }
    /** Replace the suggestions; tappable=false shows a plain message (e.g. "Type a draft to rewrite"). */
    void setItems(List<String> list, boolean tappable) {
        loading = false; items.clear(); items.addAll(list); this.tappable = tappable; shownAt = SystemClock.uptimeMillis(); invalidate();
    }
    private void showDetail(long ms) { detailUntil = SystemClock.uptimeMillis() + ms; removeCallbacks(hideDetail); postDelayed(hideDetail, ms + 16); invalidate(); }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(d * (Theme.STRIP_PAD_TOP + Theme.CHIP_H + Theme.STRIP_PAD_BOTTOM)));
    }

    private float baseline(float cy) { return cy - (text.ascent() + text.descent()) / 2; }

    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float top = Theme.STRIP_PAD_TOP * d, bottom = top + Theme.CHIP_H * d, cy = (top + bottom) / 2;

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
            for (int i = 0; i < 3; i++) {
                float sw = w * Theme.SKELETON_W * widths[i], sh = Theme.SKELETON_H * d, cx = left + w * (i + .5f);
                tmp.set(cx - sw / 2, cy - sh / 2, cx + sw / 2, cy + sh / 2);
                c.drawRoundRect(tmp, sh / 2, sh / 2, fill);
            }
            fill.setShader(null);
            postInvalidateOnAnimation();
            return;
        }
        int n = items.size();
        if (n == 0) return;
        float w = avail / n;
        boolean animating = false;
        for (int i = 0; i < n && i < 3; i++) {
            float t = tappable ? Math.min(1f, (now - shownAt - i * Theme.CHIP_STAGGER) / (float) Theme.CHIP_IN) : 1;
            if (t < 0) t = 0; if (t < 1) animating = true;
            float ease = 1 - (1 - t) * (1 - t);
            cells[i].set(left + w * i, top, left + w * (i + 1), bottom);
            if (i > 0) c.drawLine(cells[i].left, top + 8 * d, cells[i].left, bottom - 8 * d, line);
            tmp.set(cells[i]); tmp.inset(3 * d, 4 * d); tmp.offset(0, (1 - ease) * 6 * d);
            if (i == pressedCell) { fill.setColor(Theme.CHIP_PRESSED); c.drawRoundRect(tmp, Theme.CHIP_R * d, Theme.CHIP_R * d, fill); }
            text.setColor(tappable ? Theme.TEXT : Theme.TEXT3); text.setAlpha(Math.round(255 * ease));
            String it = items.get(i); if (tappable && !typed.isEmpty() && it.length() > typed.length() && it.regionMatches(true, 0, typed, 0, typed.length())) it = "…" + it.substring(typed.length()).trim();
            CharSequence s = TextUtils.ellipsize(it.replace('\n', ' ').replaceAll("\\s+", " "), text, w - 2 * Theme.CHIP_PAD_X * d, TextUtils.TruncateAt.END); // an email's newlines would draw as boxes
            c.drawText(s.toString(), tmp.centerX(), baseline(tmp.centerY()), text);
        }
        if (animating) postInvalidateOnAnimation();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (dot.contains(x, y)) { showDetail(acts[0] != null || acts[1] != null ? Theme.DETAIL_ACTIONS_MS : Theme.DETAIL_MS); return true; }
                if (wand.contains(x, y)) { listener.onWrite(); return true; }
                if (pill.contains(x, y)) { mode = (mode + 1) % 3; listener.onMode(mode); invalidate(); return true; }
                for (int i = 0; i < acts.length; i++) if (acts[i] != null && actRects[i].contains(x, y)) { detailUntil = 0; invalidate(); listener.onDetailAction(acts[i]); return true; }
                pressedCell = tappable ? cellAt(x, y) : -1; invalidate(); return true;
            case MotionEvent.ACTION_UP:
                if (pressedCell >= 0 && cellAt(x, y) == pressedCell) listener.onSuggestion(items.get(pressedCell));
                // fall through
            case MotionEvent.ACTION_CANCEL:
                pressedCell = -1; invalidate(); return true;
        }
        return true;
    }
    private int cellAt(float x, float y) { for (int i = 0; i < items.size() && i < 3; i++) if (cells[i].contains(x, y)) return i; return -1; }
}
