package local.agentos.keys;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The AI strip where QuickType sits: a glass card with a context line ("● Pigeon · 6 lines · 1 hidden"),
 * a Reply / Rewrite / Fix switch, and three suggestion chips (shimmering skeletons while loading).
 */
final class Strip extends View {
    interface Listener { void onSuggestion(String text); void onMode(int mode); }
    static final String[] MODES = {"Reply", "Rewrite", "Fix"};

    private final Listener listener;
    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF card = new RectF(), tmp = new RectF();
    private final RectF[] chips = {new RectF(), new RectF(), new RectF()}, modes = {new RectF(), new RectF(), new RectF()};
    private final List<String> items = new ArrayList<>();
    private String label = "AgentOS", right = "";
    private int orb = Theme.IDLE, mode;
    private boolean loading, tappable = true;
    private long shownAt;
    private int pressedChip = -1;

    Strip(Context c, Listener l) {
        super(c);
        listener = l;
        d = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(Theme.BG);
        line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(d); line.setColor(Theme.STRIP_LINE);
    }

    // ---- state ----
    void setContext(String label, int orbColor, String right) { this.label = label; orb = orbColor; this.right = right; invalidate(); }
    void setMode(int m) { mode = m; invalidate(); }
    void setLoading() { loading = true; items.clear(); invalidate(); }
    /** Replace the chips; tappable=false shows a plain message (e.g. "Type a draft to rewrite"). */
    void setItems(List<String> list, boolean tappable) {
        loading = false; items.clear(); items.addAll(list); this.tappable = tappable; shownAt = SystemClock.uptimeMillis(); invalidate();
    }

    @Override protected void onMeasure(int w, int h) {
        float card = Theme.STRIP_PAD_TOP + Theme.CTX_H + Theme.STRIP_GAP + Theme.CHIP_H + Theme.STRIP_PAD_BOTTOM;
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(d * (Theme.STRIP_MARGIN_TOP + card + Theme.STRIP_MARGIN_BOTTOM)));
    }

    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        card.set(Theme.STRIP_MARGIN_X * d, Theme.STRIP_MARGIN_TOP * d, getWidth() - Theme.STRIP_MARGIN_X * d, getHeight() - Theme.STRIP_MARGIN_BOTTOM * d);
        fill.setShader(null); fill.setColor(Theme.STRIP_BG);
        c.drawRoundRect(card, Theme.STRIP_R * d, Theme.STRIP_R * d, fill);
        c.drawRoundRect(card, Theme.STRIP_R * d, Theme.STRIP_R * d, line);

        // row 1: orb + context label (left), mode switch (right)
        float x = card.left + Theme.STRIP_PAD_X * d, y = card.top + Theme.STRIP_PAD_TOP * d, cy = y + Theme.CTX_H * d / 2;
        float mx = card.right - Theme.STRIP_PAD_X * d;
        text.setTextSize(Theme.MODE_TEXT * d); text.setTypeface(Theme.font(getContext(), Theme.W_CTX)); text.setTextAlign(Paint.Align.CENTER);
        float[] mw = new float[3]; float total = 0;
        for (int i = 0; i < 3; i++) { mw[i] = text.measureText(MODES[i]) + 2 * Theme.MODE_PAD_X * d; total += mw[i]; }
        tmp.set(mx - total, cy - Theme.MODE_H * d / 2, mx, cy + Theme.MODE_H * d / 2);
        fill.setColor(Theme.MODE_BG); c.drawRoundRect(tmp, Theme.MODE_R, Theme.MODE_R, fill);
        float mxx = mx - total;
        for (int i = 0; i < 3; i++) {
            modes[i].set(mxx, tmp.top, mxx + mw[i], tmp.bottom);
            if (i == mode) { fill.setColor(Theme.MODE_ON); c.drawRoundRect(modes[i], Theme.MODE_R, Theme.MODE_R, fill); }
            text.setColor(i == mode ? Theme.TEXT : Theme.TEXT3);
            c.drawText(MODES[i], modes[i].centerX(), cy - (text.ascent() + text.descent()) / 2, text);
            mxx += mw[i];
        }
        fill.setColor(orb); c.drawCircle(x + Theme.ORB * d / 2, cy, Theme.ORB * d / 2, fill);
        x += (Theme.ORB + Theme.ORB_GAP) * d;
        text.setTextSize(Theme.CTX_TEXT * d); text.setTextAlign(Paint.Align.LEFT);
        String r = right.isEmpty() ? "" : "  " + right;
        text.setColor(Theme.AMBER); float rw = text.measureText(r);
        text.setColor(Theme.TEXT3);
        CharSequence l = TextUtils.ellipsize(label, new android.text.TextPaint(text), tmp.left - x - rw - 8 * d, TextUtils.TruncateAt.END);
        c.drawText(l.toString(), x, cy - (text.ascent() + text.descent()) / 2, text);
        text.setColor(Theme.AMBER); c.drawText(r, x + text.measureText(l.toString()), cy - (text.ascent() + text.descent()) / 2, text);

        // row 2: chips
        float top = y + (Theme.CTX_H + Theme.STRIP_GAP) * d, left = card.left + Theme.STRIP_PAD_X * d, avail = card.width() - 2 * Theme.STRIP_PAD_X * d;
        text.setTextSize(Theme.CHIP_TEXT * d); text.setTypeface(Theme.font(getContext(), Theme.W_CHIP)); text.setTextAlign(Paint.Align.LEFT);
        for (RectF ch : chips) ch.setEmpty();
        if (loading) {
            float w = (avail - 2 * Theme.CHIP_GAP * d) / 3, phase = (now % Theme.SHIMMER) / (float) Theme.SHIMMER;
            LinearGradient g = new LinearGradient(0, 0, avail, 0, new int[]{Theme.SHIMMER_LO, Theme.SHIMMER_HI, Theme.SHIMMER_LO}, new float[]{0, .5f, 1}, Shader.TileMode.CLAMP);
            Matrix m = new Matrix(); m.setTranslate(left - avail + 2 * avail * phase, 0); g.setLocalMatrix(m);
            fill.setShader(g);
            float[] widths = {.9f, 1f, .75f};
            for (int i = 0; i < 3; i++) { tmp.set(left, top, left + w * widths[i], top + Theme.CHIP_H * d); c.drawRoundRect(tmp, Theme.CHIP_R * d, Theme.CHIP_R * d, fill); left += w + Theme.CHIP_GAP * d; }
            fill.setShader(null);
            postInvalidateOnAnimation();
            return;
        }
        int n = items.size();
        if (n == 0) return;
        float[] w = new float[n]; float sum = 0;
        for (int i = 0; i < n; i++) { w[i] = text.measureText(items.get(i)) + 2 * Theme.CHIP_PAD_X * d; sum += w[i]; }
        float gaps = (n - 1) * Theme.CHIP_GAP * d;
        if (sum + gaps > avail) { // shrink the longest chips first until everything fits
            float fair = (avail - gaps) / n, extra = 0; int big = 0;
            for (float v : w) if (v > fair) big++; else extra += fair - v;
            for (int i = 0; i < n; i++) if (w[i] > fair) w[i] = fair + extra / big;
        }
        boolean animating = false;
        for (int i = 0; i < n; i++) {
            float t = tappable ? Math.min(1f, (now - shownAt - i * Theme.CHIP_STAGGER) / (float) Theme.CHIP_IN) : 1;
            if (t < 0) t = 0; if (t < 1) animating = true;
            float ease = 1 - (1 - t) * (1 - t);
            chips[i].set(left, top, left + w[i], top + Theme.CHIP_H * d);
            tmp.set(chips[i]); tmp.offset(0, (1 - ease) * 6 * d);
            fill.setColor(i == pressedChip ? Theme.CHIP_PRESSED : (tappable ? Theme.CHIP : 0));
            fill.setAlpha(Math.round(fill.getAlpha() * ease));
            c.drawRoundRect(tmp, Theme.CHIP_R * d, Theme.CHIP_R * d, fill);
            text.setColor(tappable ? Theme.TEXT : Theme.TEXT3); text.setAlpha(Math.round(255 * ease));
            CharSequence s = TextUtils.ellipsize(items.get(i), new android.text.TextPaint(text), w[i] - 2 * Theme.CHIP_PAD_X * d, TextUtils.TruncateAt.END);
            c.drawText(s.toString(), tmp.left + Theme.CHIP_PAD_X * d, tmp.centerY() - (text.ascent() + text.descent()) / 2, text);
            left += w[i] + Theme.CHIP_GAP * d;
        }
        if (animating) postInvalidateOnAnimation();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                for (int i = 0; i < 3; i++) if (modes[i].contains(x, y)) { if (i != mode) { mode = i; listener.onMode(i); } invalidate(); return true; }
                pressedChip = tappable ? chipAt(x, y) : -1; invalidate(); return true;
            case MotionEvent.ACTION_UP:
                if (pressedChip >= 0 && chipAt(x, y) == pressedChip) listener.onSuggestion(items.get(pressedChip));
                // fall through
            case MotionEvent.ACTION_CANCEL:
                pressedChip = -1; invalidate(); return true;
        }
        return true;
    }
    private int chipAt(float x, float y) { for (int i = 0; i < items.size() && i < 3; i++) if (chips[i].contains(x, y)) return i; return -1; }
}
