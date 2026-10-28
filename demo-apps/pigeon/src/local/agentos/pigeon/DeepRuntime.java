package local.agentos.pigeon;

import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.util.Base64;
import android.view.View;
import android.widget.*;
import local.agentos.deep.Contract;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.*;

/** Foreground-only cooperative proof. Changes existing Views; never touches chats or recreates the Activity. */
final class DeepRuntime {
    static volatile DeepRuntime current;
    final Main app;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final Map<View, Style> styles = new WeakHashMap<>();
    final Map<String, View> targets = new HashMap<>();
    final List<View> threadRows = new ArrayList<>();
    LinearLayout threadList, actionSlot;
    Button summarize; long touched;
    final Set<Button> resets = Collections.newSetFromMap(new WeakHashMap<Button, Boolean>());
    JSONObject active, preview, saved;
    String build; volatile String key; String context = UUID.randomUUID().toString();
    final Set<String> nonces = new HashSet<>();
    long revision, generation; volatile long epoch, deadline;
    volatile IBinder broker;
    boolean summaryReady; volatile boolean foreground; boolean bound; int lifecycle;
    static final class Style { int fg, bg; float sp; Typeface font; boolean icon, avatar; }

    DeepRuntime(Main app) {
        this.app = app; current = this;
        android.content.SharedPreferences p = prefs(); nonces.addAll(p.getStringSet("nonces", Collections.emptySet())); revision = p.getLong("revision", 0); epoch = p.getLong("epoch", 0);
        // A cold start always renders built-in appearance, including an interrupted candidate.
        android.util.AtomicFile pending = new android.util.AtomicFile(new java.io.File(app.getFilesDir(), "deepmod-pending"));
        if (pending.getBaseFile().exists()) { p.edit().putString("failure", "interrupted candidate; vanilla restored").commit(); pending.delete(); }
        p.edit().remove("pending").commit();
    }
    android.content.SharedPreferences prefs() { return app.getSharedPreferences("deepmod", 0); }
    JSONObject effective() { return preview != null ? preview : active; }
    boolean modified() { return effective() != null; }
    void text(TextView v, int token) {
        Style s = styles.computeIfAbsent(v, k -> new Style()); s.fg = token;
        s.sp = v.getTextSize() / app.getResources().getDisplayMetrics().scaledDensity; s.font = v.getTypeface(); style(v, s);
    }
    void background(View v, int token) { Style s = styles.computeIfAbsent(v, k -> new Style()); s.bg = token; style(v, s); }
    void icon(ImageButton v) { Style s = styles.computeIfAbsent(v, k -> new Style()); s.icon = true; style(v, s); }
    void avatar(TextView v, int original) { Style s = styles.get(v); s.avatar = true; s.bg = original; style(v, s); }
    int color(int res) {
        JSONObject spec = effective(), theme = spec == null ? null : spec.optJSONObject("theme"), cs = theme == null ? null : theme.optJSONObject("colors");
        String token = app.getResources().getResourceEntryName(res);
        return cs != null && cs.has(token) ? android.graphics.Color.parseColor(cs.optString(token)) : app.getColor(res);
    }
    int backgroundColor(Style s) {
        JSONObject spec = effective(), t = spec == null ? null : spec.optJSONObject("theme"), cs = t == null ? null : t.optJSONObject("colors");
        return s.avatar ? cs != null && cs.has("accent") ? color(R.color.accent) : s.bg : color(s.bg);
    }
    void style(View v, Style s) {
        if (s.bg != 0) {
            int c = backgroundColor(s);
            if (v.getBackground() instanceof GradientDrawable) ((GradientDrawable)v.getBackground()).setColor(c); else v.setBackgroundColor(c);
        }
        if (s.icon) ((ImageButton)v).setColorFilter(color(R.color.text));
        if (v instanceof TextView && s.fg != 0) {
            TextView t = (TextView)v; t.setTextColor(color(s.fg));
            JSONObject spec = effective(), th = spec == null ? null : spec.optJSONObject("theme"), f = th == null ? null : th.optJSONObject("font");
            t.setTypeface(f == null ? s.font : Typeface.create(f.optString("family"), s.font == null ? 0 : s.font.getStyle()));
            t.setTextSize(s.sp * (f == null ? 1 : (float)f.optDouble("scale", 1)));
            if (v instanceof EditText) t.setHintTextColor(color(R.color.text2));
        }
    }
    void target(String id, View v) { targets.put(id, v); }
    void list(LinearLayout list, List<View> rows) { threadList = list; threadRows.clear(); threadRows.addAll(rows); for (View v : rows) list.addView(v); if (modified()) refresh(); }
    boolean projectionMatches(boolean filter) {
        int i = 0;
        for (View v : threadRows) if (!filter || !"synthetic-promoted".equals(v.getContentDescription())) {
            if (i >= threadList.getChildCount() || threadList.getChildAt(i++) != v) return false;
        }
        return i == threadList.getChildCount();
    }
    void slot(LinearLayout slot) { actionSlot = slot; summarize = null; refresh(); }
    void refresh() {
        for (Map.Entry<View, Style> e : styles.entrySet()) style(e.getKey(), e.getValue());
        for (View v : targets.values()) v.setVisibility(View.VISIBLE);
        JSONObject spec = effective();
        JSONArray vs = spec == null ? null : spec.optJSONArray("views");
        for (int i = 0; vs != null && i < vs.length(); i++) { JSONObject m = vs.optJSONObject(i).optJSONObject("match"); View v = targets.get(m.has("id") ? m.optString("id") : "pigeon_promotion"); if (v != null) v.setVisibility(View.GONE); }
        boolean filter = spec != null && spec.optJSONArray("lists") != null && spec.optJSONArray("lists").length() > 0;
        if (threadList != null && !projectionMatches(filter)) {
            // Reattach the original row Views in the original order; no data is deleted, copied or reseeded.
            threadList.setLayoutTransition(null);
            threadList.removeAllViews();
            for (View v : threadRows) if (!filter || !"synthetic-promoted".equals(v.getContentDescription())) threadList.addView(v);
        }
        boolean button = spec != null && spec.optJSONArray("buttons") != null && spec.optJSONArray("buttons").length() > 0;
        if (actionSlot != null) {
            if (summarize != null) actionSlot.removeView(summarize);
            summarize = null;
            if (button) {
                summarize = new Button(app); summarize.setId(R.id.pigeon_summarize); summarize.setText("Summarize"); summarize.setAllCaps(false);
                summarize.setMinimumHeight(app.dp(48)); summarize.setEnabled(preview == null && broker != null && foreground && summaryReady);
                summarize.setContentDescription(summaryReady ? "Summarize visible chat" : "Summarize: enable Pigeon cloud consent in AgentOS Memory first");
                summarize.setTextColor(app.getColor(R.color.onAccent)); summarize.setBackground(app.pill(R.color.accent));
                text(summarize, R.color.onAccent); background(summarize, R.color.accent);
                summarize.setOnTouchListener((v, e) -> { if (e.getAction() == android.view.MotionEvent.ACTION_UP && e.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)) touched = SystemClock.uptimeMillis(); return false; });
                summarize.setOnClickListener(v -> {
                    if (preview != null || SystemClock.uptimeMillis() - touched > 500) return;
                    touched = 0; worker.execute(() -> {
                    try { JSONObject r = Contract.rpc(broker, new JSONObject().put("op", "action").put("revision", revision).put("epoch", epoch).put("context", context).put("action", "summarize-chat"));
                        ui.post(() -> Toast.makeText(app, r.optBoolean("ok") ? "Summary requested in AgentOS" : r.optString("reason", "Summary unavailable"), Toast.LENGTH_LONG).show());
                    } catch (Exception e) { ui.post(() -> fail("broker unavailable")); }
                }); });
                actionSlot.addView(summarize);
            }
        }
        for (Button reset : resets) reset.setVisibility(modified() ? View.VISIBLE : View.GONE);
    }
    void changedScreen() { generation++; context = UUID.randomUUID().toString(); if (preview != null) restorePreview(); }
    void resume() {
        foreground = true; lifecycle++; ui.removeCallbacks(checkDeadline); ui.postDelayed(checkDeadline, 500);
        Intent i = new Intent().setComponent(new ComponentName(Contract.BROKER, "local.agentos.DeepModService"));
        bound = app.bindService(i, connection, Context.BIND_AUTO_CREATE);
        if (!bound) fail("broker unavailable");
    }
    void pause() { foreground = false; lifecycle++; generation++; context = UUID.randomUUID().toString(); ui.removeCallbacks(checkDeadline); fail("app backgrounded"); if (bound) { app.unbindService(connection); bound = false; } broker = null; }
    void close() { if (current == this) current = null; worker.shutdownNow(); }
    final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
            worker.execute(() -> { try {
                Contract.need(Contract.BROKER_CERT.equals(Contract.cert(app, Contract.BROKER)), "broker certificate changed");
                b.linkToDeath(() -> ui.post(() -> fail("broker died")), 0);
                broker = b; heartbeat();
            } catch (Exception e) { ui.post(() -> fail(e.getMessage())); } });
        }
        public void onServiceDisconnected(ComponentName n) { broker = null; fail("broker disconnected"); }
    };
    final Runnable checkDeadline = new Runnable() { public void run() {
        if (foreground) { if (modified() && SystemClock.uptimeMillis() >= deadline) fail("broker grant expired"); ui.postDelayed(this, 500); }
    }};
    void heartbeat() {
        int session = lifecycle;
        if (!foreground || broker == null) return;
        try {
            JSONObject r = Contract.rpc(broker, new JSONObject().put("op", "grant"));
            String incoming = r.getString("key"), enrolled = prefs().getString("key", "");
            Contract.need(enrolled.isEmpty() || enrolled.equals(incoming), "patch key changed; explicit integration required");
            if (enrolled.isEmpty()) prefs().edit().putString("key", incoming).commit();
            key = incoming; deadline = SystemClock.uptimeMillis() + 5000;
            long grantEpoch = r.getLong("epoch"); Contract.need(grantEpoch >= epoch, "broker disable epoch regressed");
            ui.post(() -> { summaryReady = r.optBoolean("summaryReady"); if (summarize != null) summarize.setEnabled(preview == null && summaryReady && foreground); if (grantEpoch != epoch) { epoch = grantEpoch; nonces.clear(); prefs().edit().putLong("epoch", epoch).remove("nonces").commit(); fail("disable epoch changed"); } });
        } catch (Exception e) { ui.post(() -> fail("broker liveness lost")); }
        ui.postDelayed(() -> { if (foreground && lifecycle == session) worker.execute(this::heartbeat); }, 2000);
    }
    void fail(String reason) {
        preview = null; active = null; saved = null;
        prefs().edit().remove("pending").putString("failure", reason == null ? "validation failed" : reason).commit(); refresh();
    }
    void restorePreview() { if (preview != null) { preview = null; active = saved; saved = null; refresh(); } }
    <T> T main(Callable<T> job) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        ui.post(() -> { try { f.complete(job.call()); } catch (Exception e) { f.completeExceptionally(e); } });
        return f.get(3, TimeUnit.SECONDS);
    }
    JSONObject call(JSONObject p) throws Exception {
        String op = p.getString("op");
        if (build == null) build = Contract.build(app, Contract.PKG);
        if (op.equals("inspect")) return main(this::inspect);
        if (op.equals("disable")) return main(() -> { epoch = Math.max(epoch, p.getLong("epoch")); prefs().edit().putLong("epoch", epoch).commit(); fail("disabled"); return inspect(); });
        if (op.equals("restore")) return main(() -> { restorePreview(); return inspect(); });
        if (op.equals("verified")) return main(() -> {
            Contract.need(p.getLong("revision") == revision && p.getLong("epoch") == epoch, "stale verification");
            new android.util.AtomicFile(new java.io.File(app.getFilesDir(), "deepmod-pending")).delete(); return inspect();
        });
        Contract.need(op.equals("envelope"), "unknown operation");
        JSONObject e = p.getJSONObject("envelope");
        byte[] payload = Base64.decode(e.getString("payloadB64"), Base64.NO_WRAP);
        Signature sig = Signature.getInstance("SHA256withECDSA");
        Contract.need(key != null, "broker not enrolled");
        sig.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.decode(key, Base64.NO_WRAP))));
        sig.update(Contract.signed(e)); Contract.need(sig.verify(Base64.decode(e.getString("signature"), Base64.NO_WRAP)), "bad patch signature");
        Contract.need(Contract.hash(payload).equals(e.getString("digest")) && build.equals(e.getString("build")) && Contract.PIGEON_CERT.equals(e.getString("cert")), "wrong payload/build/cert");
        String raw = new String(payload, StandardCharsets.UTF_8); JSONObject spec = raw.equals("{}") ? null : Contract.validate(raw);
        return main(() -> {
            long now = System.currentTimeMillis(), next = e.getLong("revision"); String mode = e.getString("mode");
            Contract.need(styles.size() <= 512 && threadRows.size() <= 40, "runtime view/row bound exceeded");
            Contract.need(foreground && broker != null && SystemClock.uptimeMillis() < deadline, "foreground broker grant required");
            Contract.need(e.getLong("epoch") == epoch && next > revision && !nonces.contains(e.getString("nonce")) && e.getLong("expires") > now && e.getLong("expires") <= now + 10000, "stale/replayed envelope");
            Contract.need(Arrays.asList("preview", "commit", "rollback").contains(mode), "unknown mode");
            if (spec != null) {
                Contract.need(spec.getLong("baseRevision") == revision || mode.equals("rollback"), "stale base revision");
                JSONArray vs = spec.optJSONArray("views");
                for (int i = 0; vs != null && i < vs.length(); i++) { JSONObject m = vs.getJSONObject(i).getJSONObject("match"); Contract.need(app.open == null && targets.containsKey(m.has("id") ? m.getString("id") : "pigeon_promotion"), "decorative target not on current screen"); }
                if (spec.has("lists") && spec.getJSONArray("lists").length() > 0) Contract.need(app.open == null && app.fixtures, "enable promoted fixtures on the list first");
                if (spec.has("buttons") && spec.getJSONArray("buttons").length() > 0) Contract.need(app.open != null, "open a chat for summarize preview");
            }
            Contract.need(nonces.size() < 128 && nonces.add(e.getString("nonce")), "nonce window full/replayed");
            Contract.need(prefs().edit().putStringSet("nonces", new HashSet<>(nonces)).commit(), "nonce persist failed");
            JSONObject old = active;
            try {
                if (mode.equals("preview")) { Contract.need(preview == null, "preview already running"); saved = active; preview = spec; refresh();
                    ui.postDelayed(() -> { if (preview == spec) restorePreview(); }, Math.min(8000, e.getLong("expires") - now));
                } else {
                    Contract.need(preview == null, "restore preview before commit");
                    android.util.AtomicFile pending = new android.util.AtomicFile(new java.io.File(app.getFilesDir(), "deepmod-pending"));
                    java.io.FileOutputStream out = pending.startWrite();
                    try { out.write(e.getString("digest").getBytes(StandardCharsets.UTF_8)); pending.finishWrite(out); }
                    catch (Exception error) { pending.failWrite(out); throw error; }
                    active = spec; refresh(); revision = next;
                    Contract.need(prefs().edit().putLong("revision", revision).putLong("epoch", epoch).remove("pending").remove("nonces").commit(), "revision persist failed"); nonces.clear();
                }
                return inspect();
            } catch (Exception error) { preview = null; active = old; saved = null; refresh(); throw error; }
        });
    }
    JSONObject inspect() throws Exception {
        JSONArray rows = new JSONArray();
        if (threadList != null) for (int i = 0; i < threadRows.size(); i++) if (threadRows.get(i).getParent() == threadList) rows.put(i);
        boolean structural = true;
        for (Map.Entry<View, Style> e : styles.entrySet()) {
            Style s = e.getValue(); View v = e.getKey();
            if (s.bg != 0) {
                android.graphics.drawable.Drawable bg = v.getBackground();
                structural &= bg instanceof GradientDrawable ? ((GradientDrawable)bg).getColor() != null && ((GradientDrawable)bg).getColor().getDefaultColor() == backgroundColor(s)
                    : bg instanceof android.graphics.drawable.ColorDrawable && ((android.graphics.drawable.ColorDrawable)bg).getColor() == backgroundColor(s);
            }
            if (v instanceof TextView && s.fg != 0) {
                TextView t = (TextView)v; JSONObject th = effective() == null ? null : effective().optJSONObject("theme"), f = th == null ? null : th.optJSONObject("font");
                Typeface expected = f == null ? s.font : Typeface.create(f.optString("family"), s.font == null ? 0 : s.font.getStyle());
                structural &= t.getCurrentTextColor() == color(s.fg) && Objects.equals(t.getTypeface(), expected)
                    && Math.abs(t.getTextSize() / app.getResources().getDisplayMetrics().scaledDensity - s.sp * (f == null ? 1 : f.optDouble("scale", 1))) < .1;
            }
        }
        if (summarize != null) structural &= summarize.getParent() == actionSlot && actionSlot.getChildCount() == 1 && summarize.getId() == R.id.pigeon_summarize && summarize.getMinimumHeight() >= app.dp(48);
        JSONObject hidden = new JSONObject(); for (Map.Entry<String, View> e : targets.entrySet()) hidden.put(e.getKey(), e.getValue().getVisibility() == View.GONE);
        int[] xy = new int[2]; app.root.getLocationOnScreen(xy);
        JSONArray bounds = new JSONArray(Arrays.asList(xy[0], xy[1], xy[0] + app.root.getWidth(), xy[1] + app.root.getHeight()));
        return new JSONObject().put("bounds", bounds).put("orientation", app.getResources().getConfiguration().orientation).put("fontScale", app.getResources().getConfiguration().fontScale).put("ok", true).put("package", Contract.PKG).put("build", build).put("cert", Contract.PIGEON_CERT).put("capabilityVersion", 1)
            .put("revision", revision).put("epoch", epoch).put("generation", generation).put("context", context).put("screen", app.open == null ? "chat-list" : "chat")
            .put("structural", structural).put("foreground", foreground).put("fixtures", app.fixtures).put("source_sha256", Contract.hash(app.chats.toString())).put("visibleRows", rows).put("sourceRows", threadRows.size())
            .put("hidden", hidden).put("buttonCount", summarize == null ? 0 : 1).put("buttonEnabled", summarize != null && summarize.isEnabled())
            .put("spec", effective() == null ? JSONObject.NULL : effective()).put("preview", preview != null)
            .put("colors", new JSONObject().put("bg", color(R.color.bg)).put("accent", color(R.color.accent)))
            .put("font", effective() != null && effective().optJSONObject("theme") != null ? effective().optJSONObject("theme").optJSONObject("font") : JSONObject.NULL);
    }
}
