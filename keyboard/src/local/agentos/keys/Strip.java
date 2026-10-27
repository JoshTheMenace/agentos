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
    interface Listener { void onSuggestion(String text); void onMode(int mode); }
    static final String[] MODES = {"Reply", "Rewrite", "Fix"};

    private final Listener listener;
    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF(), dot = new RectF(), pill = new RectF();
    private final RectF[] cells = {new RectF(), new RectF(), new RectF()};
    private final List<String> items = new ArrayList<>();
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
    void setLoading() { loading = true; items.clear(); invalidate(); }
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

        float left = dot.right, avail = pill.left - Theme.CELL_GAP * d - left;
        text.setTextSize(Theme.CHIP_TEXT * d); text.setTypeface(Theme.font(getContext(), Theme.W_CHIP)); text.setTextAlign(Paint.Align.CENTER);
        for (RectF ch : cells) ch.setEmpty();

        if (now < detailUntil) { // the context line, only while asked for
            text.setTextSize(Theme.CTX_TEXT * d); text.setTypeface(Theme.font(getContext(), Theme.W_CTX)); text.setColor(Theme.TEXT2);
            c.drawText(TextUtils.ellipsize(detail, text, avail - 8 * d, TextUtils.TruncateAt.END).toString(), left + avail / 2, baseline(cy), text);
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
            CharSequence s = TextUtils.ellipsize(items.get(i), text, w - 2 * Theme.CHIP_PAD_X * d, TextUtils.TruncateAt.END);
            c.drawText(s.toString(), tmp.centerX(), baseline(tmp.centerY()), text);
        }
        if (animating) postInvalidateOnAnimation();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (dot.contains(x, y)) { showDetail(Theme.DETAIL_MS); return true; }
                if (pill.contains(x, y)) { mode = (mode + 1) % 3; listener.onMode(mode); invalidate(); return true; }
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
