package local.agentos.keys;

import android.content.pm.ApplicationInfo;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The AgentOS keyboard (InputMethodService). It owns the field's InputConnection, decides what context may leave
 * the field (privacy rules), debounces suggestion requests, and wires the strip and the key grid together.
 */
public final class Keys extends InputMethodService implements KeyboardView.Listener, Strip.Listener {
    private static final int MODE_REPLY = 0, MODE_REWRITE = 1, MODE_FIX = 2;
    private static final int MAX_BEFORE = 500, MAX_AFTER = 200;

    private KeyboardView kb;
    private Strip strip;
    private Agent agent;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Map<String, List<String>> cache = new LinkedHashMap<String, List<String>>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<String>> e) { return size() > 32; }
    };
    private final Runnable debounced = this::request;

    // current field
    private String pkg = "", app = "", hint = "";
    private int enterAction = EditorInfo.IME_ACTION_NONE, inputType;
    private boolean privateField, capSentences;
    private int mode = MODE_REPLY;
    private int gen; // request generation: late answers for older generations are dropped
    private String lastKey = "";

    @Override public void onCreate() { super.onCreate(); agent = new Agent(this); }
    @Override public boolean onEvaluateFullscreenMode() { return false; }

    @Override public View onCreateInputView() {
        FrameLayout root = new FrameLayout(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        KeyboardView.Preview preview = new KeyboardView.Preview(this);
        strip = new Strip(this, this);
        kb = new KeyboardView(this, this, preview);
        col.addView(strip); col.addView(kb);
        root.addView(col); root.addView(preview);
        return root;
    }

    // ---- field lifecycle ----
    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        int it = inputType = info.inputType, cls = it & InputType.TYPE_MASK_CLASS, var = it & InputType.TYPE_MASK_VARIATION;
        boolean password = cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_PASSWORD || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            || cls == InputType.TYPE_CLASS_NUMBER && var == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        boolean incognito = (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0;
        privateField = password || incognito;
        capSentences = cls == InputType.TYPE_CLASS_TEXT && (it & InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0;
        pkg = info.packageName == null ? "" : info.packageName;
        hint = info.hintText == null ? "" : info.hintText.toString();
        try { ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0); app = String.valueOf(getPackageManager().getApplicationLabel(ai)); } catch (Exception e) { app = pkg; }

        int a = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noEnter = (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        enterAction = !noEnter && a != EditorInfo.IME_ACTION_NONE && a != EditorInfo.IME_ACTION_UNSPECIFIED ? a : EditorInfo.IME_ACTION_NONE;
        String label = info.actionLabel != null ? info.actionLabel.toString()
            : a == EditorInfo.IME_ACTION_SEND ? "send" : a == EditorInfo.IME_ACTION_GO ? "go" : a == EditorInfo.IME_ACTION_SEARCH ? "search"
            : a == EditorInfo.IME_ACTION_NEXT ? "next" : a == EditorInfo.IME_ACTION_DONE ? "done" : "return";
        kb.setEnter(enterAction == EditorInfo.IME_ACTION_NONE ? "return" : label, enterAction != EditorInfo.IME_ACTION_NONE);
        kb.setPreviews(!password);
        kb.setShift(autoCap(before(3)));
        strip.setMode(mode);
        lastKey = "";
        if (privateField) {
            strip.setContext(password ? "Password field · nothing sent" : "Private field · nothing sent", Theme.CORAL, "");
            strip.setItems(new ArrayList<String>(), false);
        } else request(); // prefetch on focus: replies are usually ready before the user looks down
    }

    @Override public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        ui.removeCallbacks(debounced); gen++; agent.cancel();
    }

    @Override public void onUpdateSelection(int os, int oe, int ns, int ne, int cs, int ce) {
        super.onUpdateSelection(os, oe, ns, ne, cs, ce);
        if (kb == null) return;
        kb.setShift(autoCap(before(3)));
        if (!privateField) { ui.removeCallbacks(debounced); ui.postDelayed(debounced, Theme.DEBOUNCE); }
    }

    private boolean autoCap(String before) {
        return capSentences && (before.isEmpty() || before.matches("(?s).*[.!?]\\s+$") || before.endsWith("\n"));
    }

    private String before(int n) { InputConnection ic = getCurrentInputConnection(); CharSequence s = ic == null ? null : ic.getTextBeforeCursor(n, 0); return s == null ? "" : s.toString(); }
    private String after(int n) { InputConnection ic = getCurrentInputConnection(); CharSequence s = ic == null ? null : ic.getTextAfterCursor(n, 0); return s == null ? "" : s.toString(); }

    // ---- keys ----
    @Override public void onText(String t) { InputConnection ic = getCurrentInputConnection(); if (ic != null) ic.commitText(t, 1); }
    @Override public void onBackspace() {
        InputConnection ic = getCurrentInputConnection(); if (ic == null) return;
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) ic.commitText("", 1); else ic.deleteSurroundingText(1, 0);
    }
    @Override public void onEnter() {
        InputConnection ic = getCurrentInputConnection(); if (ic == null) return;
        if (enterAction != EditorInfo.IME_ACTION_NONE) ic.performEditorAction(enterAction); else ic.commitText("\n", 1);
    }
    @Override public void onGlobe() { switchToPreviousInputMethod(); }

    // ---- strip ----
    @Override public void onMode(int m) { mode = m; ui.removeCallbacks(debounced); request(); }

    /** A tapped chip replaces the draft we read (Reply completes it; Rewrite and Fix rewrite it). */
    @Override public void onSuggestion(String s) {
        InputConnection ic = getCurrentInputConnection(); if (ic == null) return;
        ic.beginBatchEdit();
        String b = before(MAX_BEFORE), a = after(MAX_AFTER);
        ic.deleteSurroundingText(b.length(), a.length());
        ic.commitText(s, 1);
        ic.endBatchEdit();
        kb.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
    }

    // ---- suggestions ----
    private void request() {
        if (privateField) return;
        final int g = ++gen;
        final String b = before(MAX_BEFORE), a = after(MAX_AFTER);
        if (mode != MODE_REPLY && b.trim().isEmpty() && a.trim().isEmpty()) {
            strip.setContext(app + " · empty draft", Theme.IDLE, "");
            strip.setItems(Arrays.asList(mode == MODE_REWRITE ? "Type a draft to rewrite" : "Type a draft to fix"), false);
            return;
        }
        final String key = mode + "|" + pkg + "|" + b + "|" + a;
        if (key.equals(lastKey)) return; // cursor moved but the text didn't: nothing new to ask
        lastKey = key;
        strip.setLoading();
        agent.cancel();
        bg.execute(() -> {
            Agent.Screen sc = agent.screenRead(); // ~1 s; what the other person said
            if (g != gen) return;
            int[] red = {0};
            JSONArray lines = new JSONArray();
            for (String l : sc.lines) lines.put(Agent.redact(l, red));
            String cacheKey = key + "|" + lines;
            String ctxLabel = app + " · " + sc.lines.size() + (sc.lines.size() == 1 ? " line" : " lines")
                + (red[0] > 0 ? " · " + red[0] + " hidden" : "") + (sc.flagged > 0 ? " · " + sc.flagged + " flagged" : "")
                + (sc.secure ? " · secure screen" : "") + (!agent.hasPhone() ? " · field only" : "");
            final int redacted = red[0];
            List<String> hit;
            synchronized (cache) { hit = cache.get(cacheKey); }
            if (hit != null) { final List<String> h = hit; ui.post(() -> { if (g == gen) { strip.setContext(ctxLabel, Theme.GREEN, "cached"); strip.setItems(h, true); } }); return; }
            ui.post(() -> { if (g == gen) strip.setContext(ctxLabel, Theme.AMBER, "thinking"); });
            try {
                JSONObject ctx = new JSONObject().put("pkg", pkg).put("app", app).put("hint", hint).put("inputType", inputType).put("before", Agent.redact(b, red)).put("after", Agent.redact(a, red))
                    .put("screen", lines).put("redacted", redacted).put("untrusted", true);
                Agent.Result r = agent.suggest(ctx, mode);
                Log.i(Agent.TAG, "suggest via " + r.how + " in " + r.ms + " ms: " + r.items);
                if (!r.items.isEmpty()) synchronized (cache) { cache.put(cacheKey, r.items); }
                ui.post(() -> {
                    if (g != gen) return;
                    String label = r.flagged > 0 && sc.flagged == 0 ? ctxLabel + " · " + r.flagged + " flagged" : ctxLabel; // agentd's guard withheld lines
                    strip.setContext(label, r.items.isEmpty() ? Theme.CORAL : Theme.GREEN, String.format("%s %.1fs", r.how, r.ms / 1000.0));
                    strip.setItems(r.items.isEmpty() ? Arrays.asList("No suggestion") : r.items, !r.items.isEmpty());
                });
            } catch (Exception e) {
                Log.w(Agent.TAG, "suggest failed", e);
                lastKey = ""; // let the next edit retry
                ui.post(() -> { if (g == gen) { strip.setContext(app + " · agentd offline", Theme.CORAL, ""); strip.setItems(Arrays.asList(String.valueOf(e.getMessage())), false); } });
            }
        });
    }

    @Override public void onDestroy() { super.onDestroy(); bg.shutdownNow(); }
}
