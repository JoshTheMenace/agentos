package local.agentos.keys;

import android.content.Context;
import android.graphics.Typeface;

/**
 * The ONE place for every color, radius, font size, spacing and timing of the keyboard.
 * Current direction: A2 "Calm Glass iOS Dark" (design/mockups/A2-calm-glass-ios-dark.html, Keyboard screen):
 * a dark iOS keyboard with the AI replies in the flat QuickType row and a context line above it.
 * To restyle for another variant, change values here only; no other file knows a color.
 * (A1 "Calm Glass" values are in git history: glass card strip, Manrope, #12151D keys.)
 */
final class Theme {
    // ---- colors (ARGB), from design/A2-tokens.md (l1/l2/l3, blue, green, orange, red, idle) ----
    static final int BG = 0xFF1F1F21;            // keyboard background (.kb-wrap)
    static final int KEY = 0xFF5A5A5E;           // letter key
    static final int KEY_PRESSED = 0xFF7C7C80;   // letter key while held (iOS keys lighten)
    static final int KEY_DARK = 0xFF3B3B3E;      // shift / backspace / 123 / globe (.key.w)
    static final int KEY_DARK_PRESSED = 0xFF5A5A5E;
    static final int KEY_ON = 0xFFFFFFFF;        // shift when active (key turns white)
    static final int ON_KEY_ON = 0xFF000000;     // the arrow on an active shift key
    static final int KEY_ACCENT = 0xFF0A84FF;    // enter when it performs an action (--blue)
    static final int KEY_ACCENT_PRESSED = 0xFF3D9BFF;
    static final int ON_ACCENT = 0xFFFFFFFF;
    static final int KEY_SHADOW = 0xFF0A0A0A;    // 1 dp line under every key
    static final int TEXT = 0xFFFFFFFF;          // --l1
    static final int TEXT2 = 0x99EBEBF5;         // --l2 (60 %)
    static final int TEXT3 = 0x4DEBEBF5;         // --l3 (30 %)
    // status colors for the context orb / right label
    static final int AMBER = 0xFFFF9F0A;         // working (--orange)
    static final int GREEN = 0xFF30D158;         // ready / on-device (--green)
    static final int CORAL = 0xFFFF453A;         // private field, error (--red)
    static final int IDLE = 0xFF8E8E93;
    static final int PREVIEW_BG = 0xFF6E6E73;    // key preview bubble
    static final int STRIP_BG = BG;              // the QuickType row sits flat on the keyboard
    static final int STRIP_LINE = 0x00000000;    // no card outline in A2
    static final int CHIP = 0x00000000;          // suggestion cell at rest: transparent
    static final int CHIP_PRESSED = 0x33FFFFFF;
    static final int DIVIDER = 0x2EFFFFFF;       // 1 dp line between suggestion cells
    static final int SHIMMER_LO = 0x14FFFFFF, SHIMMER_HI = 0x33FFFFFF;
    static final int MODE_BG = 0x14FFFFFF, MODE_ON = 0x33FFFFFF;

    static final String SPACE_LABEL = "space", MODE_GLYPH = "✦"; // ✦ on the mode pill in the default (Reply) mode
    static final String WAND = "✨";                              // the "Write for me" button on the strip
    // ---- writer panel (✨ Write for me) and strip extras ----
    static final int WRITER_BG = 0xFF2A2A2D, WRITER_LINE = 0x1FFFFFFF, CHIP_BG = 0x1FFFFFFF, CHIP_ON = 0xFF0A84FF, CURSOR = 0xFF0A84FF;
    static final int NOTICE = 0xFFFFD60A;        // calm send-safety line (--yellow), never a red pop-up
    static final float WRITER_PAD_X = 12, WRITER_TEXT = 15, WRITER_LINE_H = 20, WRITER_ROW_H = 44, WRITER_CHIP_H = 30, WRITER_CHIP_PAD_X = 11, WRITER_CHIP_GAP = 6, WRITER_CHIP_TEXT = 12, WRITER_TITLE = 11;
    static final float WRITER_IMAGE_H = 150;
    static final int WRITER_MAX_LINES = 4, DETAIL_ACTIONS_MS = 5000, CURSOR_BLINK = 530;

    // ---- radii (dp) ----
    static final float KEY_R = 6, PREVIEW_R = 10, STRIP_R = 0, CHIP_R = 8, MODE_R = 999;

    // ---- keyboard sizes (dp) ----
    static final float KEY_H = 43, ROW_GAP = 11, KEY_GAP = 6, PAD_X = 5, PAD_TOP = 6, PAD_BOTTOM = 10;
    static final float KEY_TEXT = 22, KEY_TEXT_SMALL = 15, ICON = 20, SHADOW_H = 1;
    static final float PREVIEW_TEXT = 34, PREVIEW_LIFT = 54, PREVIEW_W_FACTOR = 1.5f, PREVIEW_PAD = 2;
    static final float PRESS_SCALE = 0.0f;       // iOS keys don't shrink; they lighten and pop a preview

    // ---- strip sizes (dp): one row = [dot] [3 cells] [mode pill] ----
    static final float STRIP_PAD_X = 8, STRIP_PAD_TOP = 6, STRIP_PAD_BOTTOM = 0;
    static final float CHIP_H = 40, CHIP_TEXT = 15, CHIP_PAD_X = 6, SKELETON_H = 12, SKELETON_W = .6f, CELL_GAP = 4;
    static final float ORB = 7, DOT_TOUCH = 30;                 // the dot and its touch target
    static final float CTX_TEXT = 11;                           // the detail line shown on dot tap
    static final float MODE_TEXT = 11, MODE_H = 22, MODE_PAD_X = 10;
    static final int DETAIL_MS = 2200;                          // how long the detail line stays

    // ---- font ----
    static final String FONT_ASSET = null;       // A2 uses SF/Inter; the system sans-serif is the closest on-device match. "manrope.ttf" for A1.
    private static Typeface base;
    static Typeface font(Context c, int weight) {
        if (FONT_ASSET == null) // Roboto's named families render truer than variable weights on this device
            return Typeface.create(weight <= 300 ? "sans-serif-light" : weight <= 400 ? "sans-serif" : weight <= 500 ? "sans-serif-medium" : "sans-serif-bold", Typeface.NORMAL);
        if (base == null) {
            try { base = Typeface.createFromAsset(c.getAssets(), FONT_ASSET); } catch (RuntimeException e) { base = Typeface.SANS_SERIF; }
        }
        return Typeface.create(base, weight, false);
    }
    static final int W_KEY = 400, W_LABEL = 500, W_CHIP = 500, W_CTX = 600, W_PREVIEW = 400;

    // ---- motion (ms) ----
    static final int PRESS_IN = 40, PRESS_OUT = 120, CHIP_IN = 300, CHIP_STAGGER = 70, SHIMMER = 1100;
    static final int BACKSPACE_DELAY = 350, BACKSPACE_REPEAT = 45, DOUBLE_TAP = 300, DEBOUNCE = 700, FOCUS_SETTLE = 300;

    private Theme() {}
}
