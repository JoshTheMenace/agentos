package local.agentos.keys;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The QWERTY grid, drawn on one Canvas (no per-key Views, so nothing to lay out and nothing lags).
 * Think of it as a picture of a keyboard that knows which rectangle you touched.
 * Keys commit on finger-down (zero felt latency); backspace repeats while held; shift is tap / double-tap for caps.
 */
final class KeyboardView extends View {
    interface Listener {
        void onText(String text);
        void onBackspace();
        void onEnter();
        void onGlobe();
    }

    static final String SHIFT = "⇧", BKSP = "⌫", ENTER = "↵", SPACE = "␣", SYM = "123", ABC = "ABC", SYM2 = "#+=", GLOBE = "🌐";
    private static final String BOTTOM = "123:1.3 🌐 ␣:5.2 ↵:2.5"; // iOS bottom row: 123 · globe · space · return
    /** Rows as "label[:width-units] ..."; the three pages are letters, symbols, more symbols (iOS order). */
    private static final String[][] PAGES = {
        {"q w e r t y u i o p", "a s d f g h j k l", "⇧:1.5 z x c v b n m ⌫:1.5", BOTTOM},
        {"1 2 3 4 5 6 7 8 9 0", "- / : ; ( ) $ & @ \"", "#+=:1.5 . , ? ! ' ⌫:1.5", BOTTOM.replace("123", "ABC")},
        {"[ ] { } # % ^ * + =", "_ \\ | ~ < > € £ ¥ •", "123:1.5 . , ? ! ' ⌫:1.5", BOTTOM.replace("123", "ABC")},
    };

    static final class Key {
        String label; float units; final RectF r = new RectF();
        boolean down; long pressT, releaseT;
        Key(String l, float u) { label = l; units = u; }
        /** 0..1 press amount, animated in and out. */
        float press(long now) {
            if (down) return Math.min(1f, (now - pressT) / (float) Theme.PRESS_IN);
            return Math.max(0f, 1f - (now - releaseT) / (float) Theme.PRESS_OUT);
        }
        boolean text() { return label.length() == 1 && !label.equals(SHIFT) && !label.equals(BKSP) && !label.equals(ENTER) && !label.equals(SPACE); }
    }

    private final Listener listener;
    private final Preview preview;
    private final float d; // 1 dp in px
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<List<Key>> rows = new ArrayList<>();
    private final SparseArray<Key> pointers = new SparseArray<>();
    private int page;
    private int shift; // 0 off, 1 one-shot, 2 caps lock
    private long lastShiftTap;
    private String enterLabel = "return";
    private boolean enterAccent;
    private boolean previews = true;
    private Key repeating;
    private final Runnable repeat = new Runnable() { public void run() { if (repeating != null) { listener.onBackspace(); postDelayed(this, Theme.BACKSPACE_REPEAT); } } };

    KeyboardView(Context c, Listener l, Preview p) {
        super(c);
        listener = l; preview = p;
        d = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(Theme.BG);
        setHapticFeedbackEnabled(true);
        text.setTextAlign(Paint.Align.CENTER);
        icon.setStyle(Paint.Style.STROKE); icon.setStrokeWidth(1.8f * d); icon.setStrokeJoin(Paint.Join.ROUND); icon.setStrokeCap(Paint.Cap.ROUND);
        setPage(0);
    }

    // ---- state from the service ----
    void setEnter(String label, boolean accent) { enterLabel = label; enterAccent = accent; invalidate(); }
    void setShift(boolean on) { if (shift != 2) { shift = on ? 1 : 0; invalidate(); } }
    void setPreviews(boolean on) { previews = on; }
    boolean shifted() { return shift > 0; }

    private void setPage(int p) {
        page = p; rows.clear();
        for (String row : PAGES[p]) {
            List<Key> keys = new ArrayList<>();
            for (String tok : row.split(" ")) {
                int i = tok.lastIndexOf(':');
                keys.add(i > 0 ? new Key(tok.substring(0, i), Float.parseFloat(tok.substring(i + 1))) : new Key(tok, 1));
            }
            rows.add(keys);
        }
        if (getWidth() > 0) layout(getWidth());
        invalidate();
    }

    // ---- layout ----
    @Override protected void onMeasure(int w, int h) {
        int height = Math.round(d * (Theme.PAD_TOP + Theme.PAD_BOTTOM + 4 * Theme.KEY_H + 3 * Theme.ROW_GAP));
        setMeasuredDimension(MeasureSpec.getSize(w), height);
    }
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { layout(w); }

    private void layout(int w) {
        float gap = Theme.KEY_GAP * d, unit = (w - 2 * Theme.PAD_X * d - 9 * gap) / 10f; // width of one letter key
        float y = Theme.PAD_TOP * d;
        for (List<Key> row : rows) {
            float sum = 0; for (Key k : row) sum += k.units;
            float u = sum >= 9 ? unit : (w - 2 * Theme.PAD_X * d - (row.size() - 1) * gap) / sum; // short rows stretch (iOS symbols page)
            float x = (w - (sum * u + (row.size() - 1) * gap)) / 2f;
            for (Key k : row) { k.r.set(x, y, x + k.units * u, y + Theme.KEY_H * d); x += k.units * u + gap; }
            y += Theme.KEY_H * d + Theme.ROW_GAP * d;
        }
    }

    // ---- drawing ----
    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        boolean animating = false;
        RectF r = new RectF();
        for (List<Key> row : rows) for (Key k : row) {
            float p = k.press(now);
            if (p > 0 && p < 1) animating = true;
            float s = 1 - Theme.PRESS_SCALE * p, cx = k.r.centerX(), cy = k.r.centerY();
            r.set(cx - k.r.width() * s / 2, cy - k.r.height() * s / 2, cx + k.r.width() * s / 2, cy + k.r.height() * s / 2);
            int base, pressed, fg;
            boolean dark = !k.text() && !k.label.equals(SPACE);
            if (k.label.equals(SHIFT) && shift > 0) { base = pressed = Theme.KEY_ON; fg = Theme.ON_KEY_ON; }
            else if (k.label.equals(ENTER) && enterAccent) { base = Theme.KEY_ACCENT; pressed = Theme.KEY_ACCENT_PRESSED; fg = Theme.ON_ACCENT; }
            else if (dark) { base = Theme.KEY_DARK; pressed = Theme.KEY_DARK_PRESSED; fg = Theme.TEXT2; }
            else { base = Theme.KEY; pressed = Theme.KEY_PRESSED; fg = Theme.TEXT; }
            fill.setColor(Theme.KEY_SHADOW);
            r.offset(0, Theme.SHADOW_H * d); c.drawRoundRect(r, Theme.KEY_R * d, Theme.KEY_R * d, fill); r.offset(0, -Theme.SHADOW_H * d);
            fill.setColor(lerp(base, pressed, p));
            c.drawRoundRect(r, Theme.KEY_R * d, Theme.KEY_R * d, fill);
            drawLabel(c, k, cx, cy, fg);
        }
        if (animating) postInvalidateOnAnimation();
    }

    private void drawLabel(Canvas c, Key k, float cx, float cy, int fg) {
        String l = k.label;
        icon.setColor(fg); icon.setStyle(Paint.Style.STROKE);
        float i = Theme.ICON * d;
        if (l.equals(SHIFT)) { // arrow: outline when off, filled when on, underline for caps lock
            Path p = new Path();
            p.moveTo(cx, cy - i * .45f); p.lineTo(cx - i * .42f, cy + i * .05f); p.lineTo(cx - i * .2f, cy + i * .05f); p.lineTo(cx - i * .2f, cy + i * .4f);
            p.lineTo(cx + i * .2f, cy + i * .4f); p.lineTo(cx + i * .2f, cy + i * .05f); p.lineTo(cx + i * .42f, cy + i * .05f); p.close();
            if (shift > 0) icon.setStyle(Paint.Style.FILL_AND_STROKE);
            c.drawPath(p, icon);
            if (shift == 2) c.drawLine(cx - i * .2f, cy + i * .55f, cx + i * .2f, cy + i * .55f, icon);
            return;
        }
        if (l.equals(BKSP)) {
            Path p = new Path();
            p.moveTo(cx - i * .5f, cy); p.lineTo(cx - i * .22f, cy - i * .32f); p.lineTo(cx + i * .5f, cy - i * .32f); p.lineTo(cx + i * .5f, cy + i * .32f); p.lineTo(cx - i * .22f, cy + i * .32f); p.close();
            c.drawPath(p, icon);
            float x = cx + i * .12f, e = i * .13f;
            c.drawLine(x - e, cy - e, x + e, cy + e, icon); c.drawLine(x - e, cy + e, x + e, cy - e, icon);
            return;
        }
        String s; float size; int weight; int color = fg;
        if (l.equals(ENTER)) { s = enterLabel; size = Theme.KEY_TEXT_SMALL; weight = Theme.W_LABEL; }
        else if (l.equals(SPACE)) { s = Theme.SPACE_LABEL; size = Theme.KEY_TEXT_SMALL; weight = Theme.W_KEY; }
        else if (k.text()) { s = shift > 0 ? l.toUpperCase() : l; size = Theme.KEY_TEXT; weight = Theme.W_KEY; }
        else { s = l; size = Theme.KEY_TEXT_SMALL; weight = Theme.W_LABEL; }
        text.setColor(color); text.setTextSize(size * d); text.setTypeface(Theme.font(getContext(), weight));
        c.drawText(s, cx, cy - (text.ascent() + text.descent()) / 2, text);
    }

    static int lerp(int a, int b, float t) {
        return Color.argb(Color.alpha(a) + Math.round((Color.alpha(b) - Color.alpha(a)) * t), Color.red(a) + Math.round((Color.red(b) - Color.red(a)) * t),
            Color.green(a) + Math.round((Color.green(b) - Color.green(a)) * t), Color.blue(a) + Math.round((Color.blue(b) - Color.blue(a)) * t));
    }

    // ---- touch ----
    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked(), idx = e.getActionIndex();
        switch (a) {
            case MotionEvent.ACTION_DOWN: case MotionEvent.ACTION_POINTER_DOWN: {
                Key k = hit(e.getX(idx), e.getY(idx));
                if (k != null) { pointers.put(e.getPointerId(idx), k); press(k); }
                return true;
            }
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_POINTER_UP: {
                Key k = pointers.get(e.getPointerId(idx));
                if (k != null) { pointers.remove(e.getPointerId(idx)); release(k); }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < pointers.size(); i++) release(pointers.valueAt(i));
                pointers.clear();
                return true;
        }
        return true;
    }

    private Key hit(float x, float y) {
        for (List<Key> row : rows) for (Key k : row) if (k.r.contains(x, y)) return k;
        // forgiving: nearest key in the touched row, so edge touches between keys still land
        for (List<Key> row : rows) if (y >= row.get(0).r.top - Theme.ROW_GAP * d / 2 && y <= row.get(0).r.bottom + Theme.ROW_GAP * d / 2) {
            Key best = null; float bd = Float.MAX_VALUE;
            for (Key k : row) { float dx = Math.max(k.r.left - x, x - k.r.right); if (dx < bd) { bd = dx; best = k; } }
            return bd < Theme.KEY_GAP * d ? best : null;
        }
        return null;
    }

    private void press(Key k) {
        k.down = true; k.pressT = SystemClock.uptimeMillis();
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING | HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        String l = k.label;
        if (l.equals(SHIFT)) {
            long now = SystemClock.uptimeMillis();
            shift = (now - lastShiftTap < Theme.DOUBLE_TAP) ? 2 : (shift == 0 ? 1 : 0);
            lastShiftTap = now;
        } else if (l.equals(BKSP)) { listener.onBackspace(); repeating = k; postDelayed(repeat, Theme.BACKSPACE_DELAY); }
        else if (l.equals(ENTER)) listener.onEnter();
        else if (l.equals(SPACE)) listener.onText(" ");
        else if (l.equals(GLOBE)) listener.onGlobe();
        else if (l.equals(SYM)) setPage(1);
        else if (l.equals(SYM2)) setPage(2);
        else if (l.equals(ABC)) setPage(0);
        else {
            String s = shift > 0 ? l.toUpperCase() : l;
            if (previews) preview.show(k.r, s, getTop());
            listener.onText(s);
            if (shift == 1) shift = 0;
        }
        invalidate();
    }

    private void release(Key k) {
        k.down = false; k.releaseT = SystemClock.uptimeMillis();
        if (repeating == k) { repeating = null; removeCallbacks(repeat); }
        preview.hide();
        invalidate();
    }

    /** iOS-style key preview bubble. Lives in the root FrameLayout, above the strip, so it can rise over the top row. */
    static final class Preview extends View {
        private final float d;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private String s;
        Preview(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            fill.setColor(Theme.PREVIEW_BG); text.setColor(Theme.TEXT); text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(Theme.PREVIEW_TEXT * d); text.setTypeface(Theme.font(c, Theme.W_PREVIEW));
        }
        void show(RectF key, String label, int yOffset) {
            float w = key.width() * Theme.PREVIEW_W_FACTOR, cx = key.centerX();
            cx = Math.max(w / 2 + Theme.PREVIEW_PAD * d, Math.min(getWidth() - w / 2 - Theme.PREVIEW_PAD * d, cx));
            r.set(cx - w / 2, key.top + yOffset - Theme.PREVIEW_LIFT * d, cx + w / 2, key.bottom + yOffset);
            s = label; invalidate();
        }
        void hide() { s = null; invalidate(); }
        /** Takes no space of its own: the frame sizes itself to the strip + keys, then hands us that exact size. */
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getMode(w) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(w) : 0, MeasureSpec.getMode(h) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(h) : 0);
        }
        @Override protected void onDraw(Canvas c) {
            if (s == null) return;
            c.drawRoundRect(r, Theme.PREVIEW_R * d, Theme.PREVIEW_R * d, fill);
            float cy = r.top + Theme.PREVIEW_LIFT * d * .55f;
            c.drawText(s, r.centerX(), cy - (text.ascent() + text.descent()) / 2, text);
        }
    }
}
