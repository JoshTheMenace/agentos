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

import java.io.File;
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
public final class Keys extends InputMethodService implements KeyboardView.Listener, Strip.Listener, Writer.Listener {
    private static final int MODE_REPLY = 0, MODE_REWRITE = 1, MODE_FIX = 2;
    private static final int MAX_BEFORE = 500, MAX_AFTER = 200;

    private KeyboardView kb;
    private Strip strip;
    private Writer writer;
    private Agent agent;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Map<String, Agent.Result> cache = new LinkedHashMap<String, Agent.Result>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Agent.Result> e) { return size() > 32; }
    };
    private final Runnable debounced = this::request;

    // current field
    private String pkg = "", app = "", hint = "";
    private int enterAction = EditorInfo.IME_ACTION_NONE, inputType;
    private boolean privateField, capSentences;
    private int mode = MODE_REPLY;
    private int gen; // request generation: late answers for older generations are dropped
    private List<String> shown = new ArrayList<>(); // last reply items on the strip: typing a prefix of one shows it instantly
    private long trigAt; private String trig = "focus"; // latency log (keyboard/LATENCY.md): when the focus / keystroke that led to this request happened
    private String lastKey = "";
    private Agent.Screen lastScreen; private long lastScreenAt; // reused by the writer for 20 s (saves the ~0.9 s screen.read)
    private String intent = ""; // what the user asked the writer for; kept for Redo / chips
    private String[] mimes = new String[0]; // EditorInfo.contentMimeTypes: can this field take an image?
    private File lastImage; // the generated PNG, inserted or copied on request
    private int wgen; // writer request generation

    @Override public void onCreate() { super.onCreate(); agent = new Agent(this); }
    @Override public boolean onEvaluateFullscreenMode() { return false; }

    @Override public View onCreateInputView() {
        FrameLayout root = new FrameLayout(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        KeyboardView.Preview preview = new KeyboardView.Preview(this);
        strip = new Strip(this, this);
        writer = new Writer(this, this);
        kb = new KeyboardView(this, this, preview);
        col.addView(writer); col.addView(strip); col.addView(kb);
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
        mimes = info.contentMimeTypes == null ? new String[0] : info.contentMimeTypes;
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
        strip.setMode(mode); strip.setActions(null, null); strip.setNotice(null); strip.collapse();
        if (writer != null) writer.hide();
        lastKey = ""; lastScreen = null; shown = new ArrayList<>();
        if (privateField) {
            strip.setContext(password ? "Password field · nothing sent" : "Private field · nothing sent", Theme.CORAL);
            strip.setItems(new ArrayList<String>(), false);
        } else { trig = "focus"; trigAt = now(); ui.removeCallbacks(debounced); ui.postDelayed(debounced, Theme.FOCUS_SETTLE); } // prefetch on focus (after the app's screen settles)
    }

    @Override public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        ui.removeCallbacks(debounced); gen++; wgen++; agent.cancel();
    }

    @Override public void onUpdateSelection(int os, int oe, int ns, int ne, int cs, int ce) {
        super.onUpdateSelection(os, oe, ns, ne, cs, ce);
        if (kb == null) return;
        kb.setShift(autoCap(before(3)));
        if (!privateField) { trig = "type"; trigAt = now(); ui.removeCallbacks(debounced); ui.postDelayed(debounced, Theme.DEBOUNCE); }
    }

    private static long now() { return android.os.SystemClock.uptimeMillis(); }

    private boolean autoCap(String before) {
        return capSentences && (before.isEmpty() || before.matches("(?s).*[.!?]\\s+$") || before.endsWith("\n"));
    }

    private String before(int n) { InputConnection ic = getCurrentInputConnection(); CharSequence s = ic == null ? null : ic.getTextBeforeCursor(n, 0); return s == null ? "" : s.toString(); }
    private String after(int n) { InputConnection ic = getCurrentInputConnection(); CharSequence s = ic == null ? null : ic.getTextAfterCursor(n, 0); return s == null ? "" : s.toString(); }

    // ---- keys ----
    private long lastSpace;
    @Override public void onText(String t) {
        if (writer.typing()) { writer.type(t); return; }
        InputConnection ic = getCurrentInputConnection(); if (ic == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (t.equals(" ") && now - lastSpace < Theme.DOUBLE_TAP && before(2).matches("\\w ")) { ic.deleteSurroundingText(1, 0); t = ". "; } // iOS: double space = period
        lastSpace = t.equals(" ") ? now : 0;
        ic.commitText(t, 1);
    }
    @Override public void onBackspace() {
        if (writer.typing()) { writer.backspace(); return; }
        InputConnection ic = getCurrentInputConnection(); if (ic == null) return;
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) ic.commitText("", 1); else ic.deleteSurroundingText(1, 0);
    }
    @Override public void onEnter() {
        if (writer.typing()) { writer.submit(); return; }
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

    @Override public void onWrite() { if (privateField) return; writer.open(); kb.setShift(true); }
    @Override public void onDetailAction(String action) {
        if (action.equals(Strip.ACT_CATCH_UP)) runWrite("summary", "", "", "Catch me up");
        else runWrite("translate_last", "", lang(), "Last message in " + lang());
    }

    // ---- writer ----
    private static final java.util.regex.Pattern IMAGE_INTENT = java.util.regex.Pattern.compile("(?i)^(?:(?:make|generate|create|draw|paint)(?: me)?(?: an?| some)? (?:image|picture|photo|drawing|illustration|sticker|logo)(?: of)?|(?:image|picture|photo|drawing) of)\\s*(.*)");
    @Override public void onSubmit(String what) {
        intent = what;
        java.util.regex.Matcher m = IMAGE_INTENT.matcher(what.trim());
        if (m.matches()) runImage(m.group(1).isEmpty() ? what : m.group(1)); else runWrite("write", what, "", "Writing for " + app);
    }
    @Override public void onChip(String task, String lg) {
        if (task.equals("image")) { runImage(intent); return; }
        if (!lg.isEmpty()) prefs().edit().putString("lang|" + convKey(), lg).apply(); // remember the language per conversation
        runWrite(task, intent, lg, task.equals("translate") ? "Translating to " + lg : task.substring(0, 1).toUpperCase() + task.substring(1));
    }
    @Override public void onInsert(String text) { if (text.equals("\u0000image")) insertImage(); else { writer.hide(); onSuggestion(text); } }

    // ---- images: agentd runs one `codex exec` with image generation on; the PNG comes back over the socket ----
    private boolean canInsertImages() { for (String m : mimes) if (m.equals("image/png") || m.equals("image/*") || m.equals("*/*")) return true; return false; }
    private void runImage(final String prompt) {
        final int g = ++wgen;
        writer.loading("Making an image · about a minute");
        bg.execute(() -> {
            long t0 = System.currentTimeMillis();
            try {
                byte[] png = agent.image(prompt, (t) -> ui.post(() -> { if (g == wgen) writer.progress(t); }));
                File f = new File(Images.dir(this), "kb-" + t0 + ".png");
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(png); }
                android.graphics.BitmapFactory.Options opt = new android.graphics.BitmapFactory.Options(); opt.inSampleSize = 4; // preview only
                final android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length, opt);
                final long ms = System.currentTimeMillis() - t0;
                Log.i(Agent.TAG, "image in " + ms + " ms, " + png.length + " bytes, field accepts images: " + canInsertImages());
                ui.post(() -> { if (g != wgen) return; lastImage = f; writer.showImage(bmp, String.format("%s · image %.0fs%s", app, ms / 1000.0, canInsertImages() ? "" : " · field takes text only"), canInsertImages()); });
            } catch (Exception e) {
                Log.w(Agent.TAG, "image failed", e);
                ui.post(() -> { if (g == wgen) writer.showNote("No image: " + e.getMessage(), app); });
            }
        });
    }
    /** commitContent (the image keyboard API) when the field lists image/png or image/*; otherwise the clipboard. */
    private void insertImage() {
        if (lastImage == null) return;
        android.net.Uri uri = Images.uri(lastImage.getName());
        InputConnection ic = getCurrentInputConnection();
        if (canInsertImages() && ic != null) {
            grantUriPermission(pkg, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            android.view.inputmethod.InputContentInfo info = new android.view.inputmethod.InputContentInfo(uri, new android.content.ClipDescription("image", new String[]{"image/png"}));
            boolean ok = ic.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null);
            Log.i(Agent.TAG, "commitContent -> " + ok);
            if (ok) { writer.hide(); kb.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM); return; }
        }
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newUri(getContentResolver(), "image", uri));
        writer.showNote("Copied the image. Paste it here or in another app (this field takes text only).", app);
    }
    @Override public void onClose() { wgen++; writer.hide(); } // a stale answer is dropped by wgen; the suggest socket is left alone

    private android.content.SharedPreferences prefs() { return getSharedPreferences("keys", MODE_PRIVATE); }
    /** "Conversation" = app + the top line on screen (the contact or subject), good enough to remember a language. */
    private String convKey() { return pkg + "|" + (lastScreen != null && !lastScreen.lines.isEmpty() ? lastScreen.lines.get(0) : ""); }
    private String lang() { return prefs().getString("lang|" + convKey(), prefs().getString("lang|" + pkg + "|", "Spanish")); }

    /** One writer job: screen context (reused if fresh) + agentd `write`; shows the result card. Chips act on the shown result, not the field. */
    private void runWrite(final String task, final String what, final String lg, String label) {
        final int g = ++wgen;
        final boolean onResult = writer.state() == Writer.RESULT || writer.state() == Writer.LANGS;
        final String b = onResult ? writer.result() : before(MAX_BEFORE), a = onResult ? "" : after(MAX_AFTER);
        final boolean note = task.equals("summary") || task.equals("translate_last");
        writer.loading(label);
        bg.execute(() -> {
            Agent.Screen sc = screen();
            if (g != wgen) return;
            int[] red = {0};
            JSONArray lines = new JSONArray();
            for (String l : sc.lines) lines.put(Agent.redact(l, red));
            try {
                JSONObject ctx = new JSONObject().put("pkg", pkg).put("app", app).put("hint", hint).put("inputType", inputType).put("before", Agent.redact(b, red)).put("after", Agent.redact(a, red))
                    .put("screen", lines).put("redacted", red[0]).put("untrusted", true);
                Agent.Result r = agent.write(ctx, task, what, lg);
                Log.i(Agent.TAG, "write " + task + " via " + r.how + " in " + r.ms + " ms: " + r.items);
                final String out = r.items.isEmpty() ? "" : r.items.get(0);
                final String title = String.format("%s · %d lines%s · %s %.1fs", app, sc.lines.size(), red[0] > 0 ? " · " + red[0] + " hidden" : "", r.how, r.ms / 1000.0);
                ui.post(() -> {
                    if (g != wgen) return;
                    if (out.isEmpty()) writer.showNote("Nothing came back. Try again.", title);
                    else if (note) writer.showNote(out, title);
                    else writer.showResult(out, title, task.equals("translate") ? lg : prefs().getString("lang|" + convKey(), ""));
                });
            } catch (Exception e) {
                Log.w(Agent.TAG, "write failed", e);
                ui.post(() -> { if (g == wgen) writer.showNote("agentd offline: " + e.getMessage(), app); });
            }
        });
    }

    private Agent.Screen screen() {
        Agent.Screen sc = lastScreen;
        if (sc == null || System.currentTimeMillis() - lastScreenAt > 20000) { sc = agent.screenRead(); lastScreen = sc; lastScreenAt = System.currentTimeMillis(); }
        ui.post(() -> strip.setActions(lastScreen != null && !lastScreen.lines.isEmpty() ? Strip.ACT_TRANSLATE : null, lastScreen != null && lastScreen.lines.size() >= 8 ? Strip.ACT_CATCH_UP : null));
        return sc;
    }

    // ---- send-safety: a login code, password or card number typed into a chat gets one calm line, and nothing leaves the keyboard ----
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("(?<!\\d)\\d{6,8}(?!\\d)"), CARD = java.util.regex.Pattern.compile("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)"),
        PASSWORD = java.util.regex.Pattern.compile("(?i)\\b(password|passwd|pwd|passcode|pin)\\b\\s*(is|:|=)\\s*\\S+");
    static String sendSafety(String draft, String hint) {
        if (hint.matches("(?i).*(code|otp|password|card|cvv|pin).*")) return null; // the field itself asks for it (a login form), not a chat
        if (PASSWORD.matcher(draft).find()) return "Password? Real support never asks for it.";
        java.util.regex.Matcher m = CARD.matcher(draft);
        while (m.find()) if (luhn(m.group().replaceAll("\\D", ""))) return "Card number? Keep it inside a payment app.";
        if (CODE.matcher(draft).find()) return "Login code? Real support never asks for one.";
        return null;
    }
    private static boolean luhn(String n) { int sum = 0; boolean alt = false; for (int i = n.length() - 1; i >= 0; i--) { int x = n.charAt(i) - '0'; if (alt) { x *= 2; if (x > 9) x -= 9; } sum += x; alt = !alt; } return n.length() >= 13 && sum % 10 == 0; }

    // ---- suggestions ----
    private void request() {
        if (privateField) return;
        final int g = ++gen;
        final String b = before(MAX_BEFORE), a = after(MAX_AFTER);
        String warn = sendSafety(b + a, hint);
        strip.setNotice(warn);
        if (warn != null) { lastKey = ""; agent.cancel(); return; } // nothing is sent while the draft looks like a secret
        if (mode != MODE_REPLY && b.trim().isEmpty() && a.trim().isEmpty()) {
            strip.setContext(app + " · empty draft", Theme.IDLE);
            strip.setItems(Arrays.asList(mode == MODE_REWRITE ? "Type a draft to rewrite" : "Type a draft to fix"), false);
            return;
        }
        final String key = mode + "|" + pkg + "|" + b + "|" + a;
        if (key.equals(lastKey)) return; // cursor moved but the text didn't: nothing new to ask
        lastKey = key;
        List<String> instant = new ArrayList<>(); String draft = (b + a).trim().toLowerCase(); // QuickType-style: keep what still fits the draft
        strip.setTyped(mode == MODE_REPLY ? (b + a).trim() : "");
        if (mode == MODE_REPLY && !draft.isEmpty()) for (String s : shown) if (s.toLowerCase().startsWith(draft) && s.length() > draft.length()) instant.add(s);
        if (instant.isEmpty()) strip.setLoading(); else { strip.setItems(instant, true); lat(trig, trigAt, now(), now(), now(), now(), "instant " + instant.size()); }
        final boolean reuse = trig.equals("type") && !draft.isEmpty() && lastScreen != null && System.currentTimeMillis() - lastScreenAt < 30000; // typing doesn't change the chat; an empty draft (just sent) re-reads
        agent.cancel();
        final long t0 = trigAt, t1 = now(); final String why = trig;
        bg.execute(() -> {
            final long t2 = now();
            final Agent.Screen sc = reuse ? lastScreen : agent.screenRead(); // 0.3-1 s; what the other person said
            final long t3 = now();
            if (!reuse) { lastScreen = sc; lastScreenAt = System.currentTimeMillis(); }
            ui.post(() -> strip.setActions(sc.lines.isEmpty() ? null : Strip.ACT_TRANSLATE, sc.lines.size() >= 8 ? Strip.ACT_CATCH_UP : null));
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
            synchronized (cache) { Agent.Result r = cache.get(cacheKey); hit = r != null && android.os.SystemClock.elapsedRealtime() < r.expiresAt ? r.items : null; }
            if (hit != null) { final List<String> h = hit; ui.post(() -> { if (g == gen) { strip.setContext(ctxLabel + " · cached", Theme.GREEN); strip.setItems(h, true); if (mode == MODE_REPLY) shown = h; lat(why, t0, t1, t2, t3, t3, "cached"); } }); return; }
            ui.post(() -> { if (g == gen) strip.setContext(ctxLabel + " · thinking", Theme.AMBER); });
            Log.d(Agent.TAG, "context " + ctxLabel + " before=\"" + b + "\" screen=" + lines);
            try {
                JSONObject ctx = new JSONObject().put("pkg", pkg).put("app", app).put("hint", hint).put("inputType", inputType).put("before", Agent.redact(b, red)).put("after", Agent.redact(a, red))
                    .put("screen", lines).put("redacted", redacted).put("untrusted", true);
                String w0 = b.trim().split("\\s+")[0]; // Reply with a draft: the model sometimes answers the chat instead of finishing the draft; a tap would erase it
                Agent.Result r = agent.suggest(ctx, mode, s -> { if (mode != MODE_REPLY || s.regionMatches(true, 0, w0, 0, w0.length())) ui.post(() -> { if (g == gen && strip.setFirst(s)) lat(why + "-first", t0, t1, t2, t3, now(), "streamed"); }); });
                final long t4 = now();
                Log.i(Agent.TAG, "suggest via " + r.how + " in " + r.ms + " ms: " + r.items);
                if (mode == MODE_REPLY && !w0.isEmpty()) r.items.removeIf(x -> !x.regionMatches(true, 0, w0, 0, w0.length()));
                if (!r.items.isEmpty()) synchronized (cache) { cache.put(cacheKey, r); }
                ui.post(() -> {
                    if (g != gen) return;
                    String label = r.flagged > 0 && sc.flagged == 0 ? ctxLabel + " · " + r.flagged + " flagged" : ctxLabel; // agentd's guard withheld lines
                    strip.setContext(label + String.format(" · %s %.1fs", r.how, r.ms / 1000.0), r.items.isEmpty() ? Theme.CORAL : Theme.GREEN);
                    strip.setItems(r.items.isEmpty() ? Arrays.asList("No suggestion") : r.items, !r.items.isEmpty());
                    if (mode == MODE_REPLY && !r.items.isEmpty()) shown = r.items;
                    lat(why, t0, t1, t2, t3, t4, r.how + (reuse ? " screen-reused " : " ") + r.timing);
                });
            } catch (Exception e) {
                Log.w(Agent.TAG, "suggest failed", e);
                lastKey = ""; // let the next edit retry
                ui.post(() -> { if (g == gen) { strip.setContext(app + " · agentd offline", Theme.CORAL); strip.setItems(Arrays.asList(String.valueOf(e.getMessage())), false); } });
            }
        });
    }

    /** One line per shown suggestion set: trigger → debounce → bg queue → screen.read → agentd → on screen (adb logcat -s AgentKeys | grep lat). */
    private static void lat(String why, long t0, long t1, long t2, long t3, long t4, String how) {
        long t5 = now();
        Log.i(Agent.TAG, String.format("lat %s total=%d wait=%d queue=%d screen=%d agentd=%d render=%d %s", why, t5 - t0, t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4, how));
    }

    @Override public void onDestroy() { super.onDestroy(); bg.shutdownNow(); }
}
