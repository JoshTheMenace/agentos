package local.agentos.beacon;

import android.animation.LayoutTransition;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Beacon: a weather + notes app we own that "phones home" every few seconds, so a firewall demo has
 * something visible to block. Each tick sends one real request (wttr.in) and one "analytics" beacon.
 * Override: am start -n local.agentos.beacon/.Main --es analytics https://analytics.example.com/collect --ei interval 3000
 */
public class Main extends Activity {
    static final String WEATHER = "https://wttr.in/?format=%l%7C%t%7C%C", ANALYTICS = "https://httpbin.org/post";
    static final int MATCH = -1, WRAP = -2;
    final Handler ui = new Handler(Looper.getMainLooper());
    final Runnable tick = this::tick;
    SharedPreferences prefs;
    LinearLayout log;
    TextView place, temp, cond, upd, stats;
    Button pause;
    boolean paused;
    int ok, fail, seq;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("beacon", MODE_PRIVATE);
        handle(getIntent());
        LinearLayout page = col();
        page.setPadding(dp(16), dp(12), dp(16), 0);

        LinearLayout head = row();
        head.addView(text("Beacon", 24, R.color.text, true), new LinearLayout.LayoutParams(0, WRAP, 1));
        pause = new Button(this);
        pause.setText("Pause");
        pause.setAllCaps(false);
        pause.setTextColor(color(R.color.text));
        pause.setBackground(pill(color(R.color.surface2)));
        pause.setStateListAnimator(null);
        pause.setPadding(dp(16), 0, dp(16), 0);
        pause.setMinHeight(0); pause.setMinimumHeight(dp(36)); pause.setMinWidth(0); pause.setMinimumWidth(0);
        pause.setOnClickListener(v -> { paused = !paused; pause.setText(paused ? "Resume" : "Pause"); ui.removeCallbacks(tick); if (!paused) ui.post(tick); });
        head.addView(pause, new LinearLayout.LayoutParams(WRAP, dp(36)));
        page.addView(head);

        LinearLayout weather = card();
        LinearLayout left = col();
        place = text("Locating…", 13, R.color.text2, false);
        temp = text("--°", 44, R.color.text, true);
        left.addView(place); left.addView(temp);
        weather.addView(left, new LinearLayout.LayoutParams(0, WRAP, 1));
        LinearLayout right = col();
        right.setGravity(Gravity.END);
        cond = text("", 16, R.color.text, false);
        upd = text("waiting for wttr.in", 12, R.color.text2, false);
        right.addView(cond); right.addView(upd);
        weather.addView(right);
        page.addView(weather);

        EditText notes = new EditText(this);
        notes.setHint("Notes (saved on this phone)");
        notes.setText(prefs.getString("notes", ""));
        notes.setTextSize(15);
        notes.setTextColor(color(R.color.text));
        notes.setHintTextColor(color(R.color.text2));
        notes.setMinLines(2);
        notes.setGravity(Gravity.TOP);
        notes.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        notes.setBackground(rounded(color(R.color.surface), 16));
        notes.setPadding(dp(16), dp(12), dp(16), dp(12));
        notes.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(Editable e) { prefs.edit().putString("notes", e.toString()).apply(); }
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
        });
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(MATCH, WRAP);
        nlp.topMargin = dp(12);
        page.addView(notes, nlp);

        LinearLayout cap = row();
        cap.setPadding(0, dp(18), 0, dp(6));
        cap.addView(text("Request log", 13, R.color.text2, true), new LinearLayout.LayoutParams(0, WRAP, 1));
        stats = text("", 12, R.color.text2, false);
        cap.addView(stats);
        page.addView(cap);

        log = col();
        log.setLayoutTransition(new LayoutTransition());
        ScrollView sv = new ScrollView(this);
        sv.addView(log);
        page.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1));
        setContentView(page);
        stat();
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handle(i); ui.removeCallbacks(tick); ui.post(tick); }
    @Override protected void onStart() { super.onStart(); ui.removeCallbacks(tick); ui.post(tick); }
    @Override protected void onStop() { super.onStop(); ui.removeCallbacks(tick); }

    void handle(Intent i) {
        SharedPreferences.Editor e = prefs.edit();
        for (String k : new String[]{"weather", "analytics"}) if (i.hasExtra(k)) e.putString(k, i.getStringExtra(k));
        if (i.hasExtra("interval")) e.putInt("interval", i.getIntExtra("interval", 5000));
        e.apply();
    }

    void tick() {
        if (paused) return;
        seq++;
        request("GET", prefs.getString("weather", WEATHER), "weather", null);
        request("POST", prefs.getString("analytics", ANALYTICS), "analytics",
            "{\"event\":\"heartbeat\",\"seq\":" + seq + ",\"uid\":" + Process.myUid() + "}");
        ui.postDelayed(tick, prefs.getInt("interval", 5000));
    }

    void request(String method, String url, String kind, String body) {
        new Thread(() -> {
            long t0 = SystemClock.elapsedRealtime();
            int code = 0; String out = null, err = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000); c.setReadTimeout(10000); // wttr.in is often slow; a firewall DROP still shows as TIMEOUT
                c.setRequestMethod(method);
                c.setRequestProperty("User-Agent", "curl/8 Beacon-demo");
                if (body != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes("UTF-8")); }
                }
                code = c.getResponseCode();
                try (InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream()) { out = in == null ? "" : read(in); }
                c.disconnect();
            } catch (Exception e) { err = reason(e); }
            long ms = SystemClock.elapsedRealtime() - t0;
            int fc = code; String fo = out, fe = err;
            ui.post(() -> done(method, url, kind, fc, fe, ms, fo));
        }).start();
    }

    void done(String method, String url, String kind, int code, String err, long ms, String body) {
        boolean good = err == null && code < 400;
        if (good) ok++; else fail++;
        if (kind.equals("weather")) {
            String[] p = good && body != null ? body.trim().split("\\|") : new String[0];
            if (p.length == 3) { place.setText(p[0]); temp.setText(p[1].replace("+", "")); cond.setText(p[2]); }
            upd.setText(good ? "updated " + now() : "unreachable · " + (err != null ? err : "HTTP " + code));
            upd.setTextColor(color(good ? R.color.text2 : R.color.bad));
        }
        stat();
        String host = url.replaceFirst("^https?://", "").replaceFirst("/.*", "");
        LinearLayout r = row();
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setBackground(rounded(color(R.color.surface), 14));
        r.addView(text(now(), 12, R.color.text2, false));
        LinearLayout mid = col();
        mid.setPadding(dp(12), 0, dp(8), 0);
        mid.addView(text(method + " " + host, 14, R.color.text, true));
        mid.addView(text(kind + " · " + ms + " ms", 12, R.color.text2, false));
        r.addView(mid, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView chip = text(err != null ? err : String.valueOf(code), 12, good ? R.color.ok : R.color.bad, true);
        chip.setBackground(pill((color(good ? R.color.ok : R.color.bad) & 0xFFFFFF) | 0x2A000000));
        chip.setPadding(dp(10), dp(4), dp(10), dp(4));
        r.addView(chip);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = dp(6);
        log.addView(r, 0, lp);
        if (log.getChildCount() > 40) log.removeViewAt(40);
    }

    void stat() { stats.setText(ok + " ok · " + fail + " failed · every " + prefs.getInt("interval", 5000) / 1000 + " s · uid " + Process.myUid()); }

    static String reason(Exception e) {
        if (e instanceof UnknownHostException) return "DNS";
        if (e instanceof SocketTimeoutException) return "TIMEOUT";
        if (e instanceof ConnectException) return "REFUSED";
        if (e instanceof javax.net.ssl.SSLException) return "TLS";
        return e.getClass().getSimpleName().replace("Exception", "").toUpperCase(Locale.US);
    }

    static String read(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        return o.toString("UTF-8");
    }

    // ---- helpers ----
    static String now() { return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()); }
    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    int color(int res) { return getColor(res); }
    LinearLayout col() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    LinearLayout card() {
        LinearLayout c = row();
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        c.setBackground(rounded(color(R.color.surface), 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }
    GradientDrawable rounded(int argb, int radiusDp) { GradientDrawable d = new GradientDrawable(); d.setColor(argb); d.setCornerRadius(dp(radiusDp)); return d; }
    GradientDrawable pill(int argb) { return rounded(argb, 24); }
    TextView text(String s, float sp, int colorRes, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color(colorRes));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }
}
