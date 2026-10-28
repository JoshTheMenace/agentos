package local.agentos.keys;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The "✨ Write for me" panel above the strip (like Gmail's Help me write, inside the keyboard).
 * States: TYPING (the user types a rough intent; the keys go here, not into the app) → LOADING → RESULT (the finished
 * message plus chips: Shorter · Casual · Formal · Redo · Translate, and Insert) → LANGS (pick a language).
 * NOTE is the same card used for "Catch me up" and "Translate last message": text and a Close chip.
 */
final class Writer extends View {
    interface Listener { void onSubmit(String intent); void onChip(String task, String lang); void onInsert(String text); void onClose(); }
    static final int HIDDEN = 0, TYPING = 1, LOADING = 2, RESULT = 3, LANGS = 4, NOTE = 5, IMAGE = 6;
    private android.graphics.Bitmap image; private final RectF imageRect = new RectF();
    static final String[] LANGS_ALL = {"Spanish", "French", "German", "Portuguese", "Italian", "Japanese", "Chinese", "Hindi", "Arabic", "Korean"};

    private final Listener listener;
    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF(), close = new RectF(), go = new RectF();
    private final List<RectF> chipRects = new ArrayList<>();
    private final List<String> chips = new ArrayList<>();
    private final StringBuilder intent = new StringBuilder();
    private String title = "Write for me", resultTitle = "", result = "", lastLang = "";
    private int state = HIDDEN, pressed = -1;
    private boolean insertable = true;
    private StaticLayout layout;

    Writer(Context c, Listener l) {
        super(c); listener = l;
        d = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(Theme.WRITER_BG);
        setVisibility(GONE);
    }

    int state() { return state; }
    boolean typing() { return state == TYPING; }
    String intent() { return intent.toString(); }
    String result() { return result; }

    // ---- state ----
    void open() { intent.setLength(0); set(TYPING, "Write for me"); }
    void loading(String what) { result = ""; set(LOADING, what); }
    /** The finished message; chips let the user shape it, Insert puts it in the field. */
    void showResult(String txt, String title, String lastLang) {
        result = txt; insertable = true; this.lastLang = lastLang; resultTitle = title;
        chips.clear(); chips.addAll(Arrays.asList("Shorter", "Casual", "Formal", "Redo", lastLang.isEmpty() ? "Translate" : "→ " + lastLang, "Insert"));
        set(RESULT, title);
    }
    /** A generated picture: Insert (or Copy when the field can't take images), Redo. */
    void showImage(android.graphics.Bitmap bmp, String title, boolean canInsert) {
        image = bmp; chips.clear(); chips.addAll(Arrays.asList("Redo", canInsert ? "Insert" : "Copy")); set(IMAGE, title);
    }
    /** Loading with a progress line under the shimmer (image generation takes ~30 to 60 s). */
    void progress(String line) { result = line; invalidate(); }
    /** Catch me up / translate last: just text and Close. */
    void showNote(String txt, String title) { result = txt; insertable = false; chips.clear(); chips.add("Close"); set(NOTE, title); }
    void showLangs() { chips.clear(); for (String l : LANGS_ALL) if (!l.equals(lastLang)) chips.add(l); if (!lastLang.isEmpty()) chips.add(0, lastLang); chips.add("Back"); set(LANGS, "Translate to"); }
    void hide() { state = HIDDEN; setVisibility(GONE); }
    private void set(int st, String t) { state = st; title = t; layout = null; setVisibility(VISIBLE); requestLayout(); invalidate(); }

    // ---- keys while typing the intent ----
    void type(String t) { intent.append(t); invalidate(); }
    void backspace() { if (intent.length() > 0) intent.setLength(intent.length() - 1); invalidate(); }
    void submit() { String s = intent.toString().trim(); if (!s.isEmpty()) listener.onSubmit(s); }

    // ---- layout ----
    private StaticLayout body() {
        if (layout == null) {
            text.setTextSize(Theme.WRITER_TEXT * d); text.setTypeface(Theme.font(getContext(), Theme.W_KEY));
            int w = Math.max(1, Math.round(getMeasuredWidth() > 0 ? getMeasuredWidth() - 2 * Theme.WRITER_PAD_X * d : getResources().getDisplayMetrics().widthPixels - 2 * Theme.WRITER_PAD_X * d));
            layout = StaticLayout.Builder.obtain(result, 0, result.length(), text, w).setLineSpacing(0, 1.1f).setMaxLines(Theme.WRITER_MAX_LINES).setEllipsize(TextUtils.TruncateAt.END).build();
        }
        return layout;
    }
    /** Places the chips in rows (wrapping), Insert pinned to the right edge of its row; returns the rows' total height. */
    private float layoutChips(float w) {
        float px = Theme.WRITER_PAD_X * d, ch = Theme.WRITER_CHIP_H * d, gap = Theme.WRITER_CHIP_GAP * d, x = px, y = 0;
        chipRects.clear();
        text.setTextSize(Theme.WRITER_CHIP_TEXT * d); text.setTypeface(Theme.font(getContext(), Theme.W_CTX));
        for (String s : chips) {
            float cw = text.measureText(s) + 2 * Theme.WRITER_CHIP_PAD_X * d;
            if (s.equals("Insert")) x = Math.max(x, w - px - cw);
            else if (x + cw > w - px) { x = px; y += ch + gap; }
            chipRects.add(new RectF(x, y, x + cw, y + ch)); x += cw + gap;
        }
        return chips.isEmpty() ? 0 : y + ch;
    }
    @Override protected void onMeasure(int w, int h) {
        float W = MeasureSpec.getSize(w), hp = 28 * d + 10 * d; // title row + bottom pad
        if (state == TYPING || state == LOADING) hp += Theme.WRITER_ROW_H * d;
        if (state == RESULT || state == NOTE) hp += bodyHeight();
        if (state == IMAGE) hp += Theme.WRITER_IMAGE_H * d + 6 * d;
        if (state != TYPING && state != LOADING) hp += layoutChips(W) + 6 * d;
        setMeasuredDimension((int) W, Math.round(hp));
    }
    private int bodyHeight() { StaticLayout l = body(); int n = Math.min(Theme.WRITER_MAX_LINES, l.getLineCount()); return n == 0 ? 0 : l.getLineBottom(n - 1) + Math.round(6 * d); }
    private float baseline(float cy) { return cy - (text.ascent() + text.descent()) / 2; }

    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float px = Theme.WRITER_PAD_X * d, w = getWidth();
        // title row: "✨ Write for me" and × (close)
        float ty = 12 * d, tcy = ty + Theme.WRITER_TITLE * d / 2;
        text.setTextAlign(Paint.Align.LEFT); text.setTextSize(Theme.WRITER_TITLE * d); text.setTypeface(Theme.font(getContext(), Theme.W_CTX)); text.setColor(Theme.TEXT2);
        c.drawText(Theme.WAND + "  " + title.toUpperCase(), px, baseline(tcy), text);
        close.set(w - 44 * d, 0, w, 32 * d);
        text.setTextAlign(Paint.Align.CENTER); text.setTextSize(16 * d); text.setColor(Theme.TEXT2); c.drawText("×", close.centerX(), baseline(tcy), text);
        float y = 28 * d;
        text.setTextAlign(Paint.Align.LEFT); text.setTextSize(Theme.WRITER_TEXT * d); text.setTypeface(Theme.font(getContext(), Theme.W_KEY));
        if (state == TYPING) { // the intent line with a blinking cursor and a Write button
            go.set(w - px - 64 * d, y, w - px, y + Theme.WRITER_CHIP_H * d + 4 * d);
            fill.setColor(intent.length() > 0 ? Theme.CHIP_ON : Theme.CHIP_BG); c.drawRoundRect(go, 999, 999, fill);
            text.setTextAlign(Paint.Align.CENTER); text.setTextSize(Theme.WRITER_CHIP_TEXT * d); text.setTypeface(Theme.font(getContext(), Theme.W_CTX)); text.setColor(Theme.TEXT);
            c.drawText("Write", go.centerX(), baseline(go.centerY()), text);
            text.setTextAlign(Paint.Align.LEFT); text.setTextSize(Theme.WRITER_TEXT * d); text.setTypeface(Theme.font(getContext(), Theme.W_KEY));
            float avail = go.left - 10 * d - px, cy = go.centerY();
            if (intent.length() == 0) { text.setColor(Theme.TEXT3); c.drawText("What do you want to say?", px, baseline(cy), text); }
            else {
                text.setColor(Theme.TEXT);
                String s = TextUtils.ellipsize(intent, text, avail - 3 * d, TextUtils.TruncateAt.START).toString();
                c.drawText(s, px, baseline(cy), text);
                if ((now / Theme.CURSOR_BLINK) % 2 == 0) { fill.setColor(Theme.CURSOR); c.drawRect(px + text.measureText(s) + d, cy - 9 * d, px + text.measureText(s) + 3 * d, cy + 9 * d, fill); }
            }
            postInvalidateDelayed(Theme.CURSOR_BLINK);
            return;
        }
        if (state == LOADING) { // shimmer bar
            float avail = w - 2 * px, cy = y + Theme.WRITER_ROW_H * d / 2, phase = (now % Theme.SHIMMER) / (float) Theme.SHIMMER;
            LinearGradient g = new LinearGradient(0, 0, avail, 0, new int[]{Theme.SHIMMER_LO, Theme.SHIMMER_HI, Theme.SHIMMER_LO}, new float[]{0, .5f, 1}, Shader.TileMode.CLAMP);
            Matrix m = new Matrix(); m.setTranslate(px - avail + 2 * avail * phase, 0); g.setLocalMatrix(m); fill.setShader(g);
            tmp.set(px, cy - 6 * d, px + avail * .8f, cy + 6 * d); c.drawRoundRect(tmp, 6 * d, 6 * d, fill); fill.setShader(null);
            if (!result.isEmpty()) { text.setTextSize(Theme.CTX_TEXT * d); text.setColor(Theme.TEXT2); c.drawText(TextUtils.ellipsize(result, text, avail, TextUtils.TruncateAt.END).toString(), px, baseline(cy + 14 * d), text); }
            postInvalidateOnAnimation(); return;
        }
        if (state == IMAGE && image != null) {
            float ih = Theme.WRITER_IMAGE_H * d, iw = ih * image.getWidth() / image.getHeight();
            imageRect.set(px, y, px + iw, y + ih);
            c.save(); android.graphics.Path clip = new android.graphics.Path(); clip.addRoundRect(imageRect, Theme.CHIP_R * d, Theme.CHIP_R * d, android.graphics.Path.Direction.CW); c.clipPath(clip);
            c.drawBitmap(image, null, imageRect, fill); c.restore();
            y += ih + 6 * d;
        }
        if (state == RESULT || state == NOTE) { c.save(); c.translate(px, y); text.setColor(Theme.TEXT); body().draw(c); c.restore(); y += bodyHeight(); }
        // chip rows
        float top = y + 6 * d; layoutChips(w);
        text.setTextSize(Theme.WRITER_CHIP_TEXT * d); text.setTypeface(Theme.font(getContext(), Theme.W_CTX)); text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < chips.size(); i++) {
            RectF r = chipRects.get(i); r.offset(0, top);
            boolean primary = chips.get(i).equals("Insert");
            fill.setColor(primary ? Theme.CHIP_ON : i == pressed ? Theme.CHIP_PRESSED : Theme.CHIP_BG); c.drawRoundRect(r, 999, 999, fill);
            text.setColor(Theme.TEXT); c.drawText(chips.get(i), r.centerX(), baseline(r.centerY()), text);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (close.contains(x, y)) { listener.onClose(); return true; }
                if (state == TYPING && go.contains(x, y)) { submit(); return true; }
                pressed = chipAt(x, y); invalidate(); return true;
            case MotionEvent.ACTION_UP:
                if (pressed >= 0 && chipAt(x, y) == pressed) tap(chips.get(pressed));
                // fall through
            case MotionEvent.ACTION_CANCEL:
                pressed = -1; invalidate(); return true;
        }
        return true;
    }
    private int chipAt(float x, float y) { for (int i = 0; i < chipRects.size(); i++) if (chipRects.get(i).contains(x, y)) return i; return -1; }
    private void tap(String chip) {
        switch (chip) {
            case "Insert": case "Copy": listener.onInsert(state == IMAGE ? "\u0000image" : result); return;
            case "Close": listener.onClose(); return;
            case "Back": showResult(result, resultTitle, lastLang); return;
            case "Translate": showLangs(); return;
            case "Shorter": case "Casual": case "Formal": case "Redo": listener.onChip(state == IMAGE ? "image" : chip.toLowerCase(), ""); return;
        }
        if (state == RESULT && chip.startsWith("→ ")) { listener.onChip("translate", lastLang); return; } // last language, one tap
        if (state == LANGS) listener.onChip("translate", chip);
    }
}
