package local.agentos.keys;

import android.content.Context;
import android.graphics.Typeface;

/**
 * The ONE place for every color, radius, font size, spacing and timing of the keyboard.
 * Direction A "Calm Glass" (design/mockups/A-calm-glass.html, Keyboard tab) with an iOS flavor.
 * To restyle for another variant, change values here only; no other file knows a color.
 */
final class Theme {
    // ---- colors (ARGB) ----
    static final int BG = 0xFF12151D;            // keyboard background (.kb)
    static final int KEY = 0xFF232835;           // letter key
    static final int KEY_PRESSED = 0xFF3A4154;   // letter key while held
    static final int KEY_DARK = 0xFF1A1E29;      // shift / backspace / ?123 / globe (.key.w)
    static final int KEY_DARK_PRESSED = 0xFF2A3040;
    static final int KEY_ON = 0xFFF3F5F9;        // shift when active (iOS: key turns white)
    static final int KEY_ACCENT = 0xFF86B7FF;    // enter when it performs an action (--blue)
    static final int KEY_ACCENT_PRESSED = 0xFF6FA2F0;
    static final int ON_ACCENT = 0xFF0A0C12;
    static final int KEY_SHADOW = 0x80000000;    // 1 dp line under every key
    static final int TEXT = 0xFFF3F5F9;          // --t1
    static final int TEXT2 = 0x9EF3F5F9;         // --t2 (62 %)
    static final int TEXT3 = 0x5CF3F5F9;         // --t3 (36 %)
    static final int AMBER = 0xFFF6B65B, GREEN = 0xFF7FDFA4, CORAL = 0xFFFF8F86, IDLE = 0xFF8A93A6;
    static final int PREVIEW_BG = 0xFF3A4154;    // key preview bubble
    static final int STRIP_BG = 0xFF0E1118;      // --ink glass card
    static final int STRIP_LINE = 0x1FFFFFFF;    // gradient hairline, simplified
    static final int CHIP = 0x12FFFFFF;          // rgba(255,255,255,.07)
    static final int CHIP_PRESSED = 0x30FFFFFF;
    static final int SHIMMER_LO = 0x0DFFFFFF, SHIMMER_HI = 0x1FFFFFFF;
    static final int MODE_BG = 0x0FFFFFFF, MODE_ON = 0x24FFFFFF;

    // ---- radii (dp) ----
    static final float KEY_R = 7, PREVIEW_R = 11, STRIP_R = 16, CHIP_R = 10, MODE_R = 999;

    // ---- keyboard sizes (dp) ----
    static final float KEY_H = 42, ROW_GAP = 8, KEY_GAP = 5, PAD_X = 6, PAD_TOP = 8, PAD_BOTTOM = 6;
    static final float KEY_TEXT = 19, KEY_TEXT_SMALL = 12, ICON = 20, SHADOW_H = 1;
    static final float PREVIEW_TEXT = 30, PREVIEW_LIFT = 52, PREVIEW_W_FACTOR = 1.4f, PREVIEW_PAD = 2;
    static final float PRESS_SCALE = 0.04f;      // keys shrink 4 % while held

    // ---- strip sizes (dp) ----
    static final float STRIP_MARGIN_X = 8, STRIP_MARGIN_TOP = 6, STRIP_MARGIN_BOTTOM = 6;
    static final float STRIP_PAD_X = 10, STRIP_PAD_TOP = 8, STRIP_PAD_BOTTOM = 10, STRIP_GAP = 6;
    static final float CTX_TEXT = 10.5f, CTX_H = 16, ORB = 8, ORB_GAP = 6;
    static final float MODE_TEXT = 10.5f, MODE_H = 18, MODE_PAD_X = 9;
    static final float CHIP_H = 32, CHIP_TEXT = 12.5f, CHIP_PAD_X = 10, CHIP_GAP = 6, CHIP_MIN_W = 44;

    // ---- font ----
    static final String FONT_ASSET = "manrope.ttf"; // variable font in assets/; null -> system sans-serif
    private static Typeface base;
    static Typeface font(Context c, int weight) {
        if (base == null) {
            try { base = FONT_ASSET == null ? Typeface.SANS_SERIF : Typeface.createFromAsset(c.getAssets(), FONT_ASSET); }
            catch (RuntimeException e) { base = Typeface.SANS_SERIF; }
        }
        return Typeface.create(base, weight, false);
    }
    static final int W_KEY = 500, W_LABEL = 700, W_CHIP = 600, W_CTX = 700, W_PREVIEW = 500;

    // ---- motion (ms) ----
    static final int PRESS_IN = 50, PRESS_OUT = 130, CHIP_IN = 260, CHIP_STAGGER = 60, SHIMMER = 1100;
    static final int BACKSPACE_DELAY = 350, BACKSPACE_REPEAT = 45, DOUBLE_TAP = 300, DEBOUNCE = 700;

    private Theme() {}
}
