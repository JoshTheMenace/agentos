package local.agentos.home;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A micro-app the agent built (agentd apps.js): one HTML file, run full screen in its own WebView. Think of a sealed room with
 * one light switch: the page gets its own origin (https://<id>.agentos.app), a strict CSP, no network (the WebView blocks loads
 * and every request is answered from here: the app's file, /ui.css, /ui.js, the font), no Native bridge, no file or content
 * access. The only way out is AgentOSApp: summary(text) for the home widget, haptic(kind), close(). Storage is the origin's own
 * localStorage, so apps can't read each other's data. The file comes from agentd (GET /apps/<id>/index.html, bearer token) and
 * is cached in files/apps/, so an app still opens when agentd is down. Generated code is untrusted.
 */
public class MicroApp extends Activity {
  static final String TAG = "AgentOSApp", PREFS = "microapps", AGENTD = "http://127.0.0.1:8787";
  static final String CSP = "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; font-src 'self'; img-src data:; "
      + "connect-src 'none'; frame-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'";
  WebView web; String id, origin; byte[] page;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    id = getIntent().getStringExtra("id");
    if (id == null || !id.matches("[a-z0-9-]{1,32}")) { finish(); return; }
    origin = "https://" + id + ".agentos.app";
    getWindow().setStatusBarColor(0xFF000000); getWindow().setNavigationBarColor(0xFF000000);
    web = new WebView(this);
    web.setBackgroundColor(0xFF000000);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);            // the app's own localStorage (per origin)
    s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setBlockNetworkLoads(true);
    s.setCacheMode(WebSettings.LOAD_NO_CACHE); s.setGeolocationEnabled(false); s.setJavaScriptCanOpenWindowsAutomatically(false);
    web.addJavascriptInterface(new Bridge(), "AgentOSApp");
    web.setWebViewClient(new WebViewClient() {
      @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return serve(r.getUrl()); }
      @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; } // no navigation at all
    });
    web.setWebChromeClient(new WebChromeClient() {
      @Override public boolean onConsoleMessage(ConsoleMessage m) { Log.i(TAG, id + ": " + m.message() + " (" + m.lineNumber() + ")"); return true; }
      @Override public boolean onJsAlert(WebView v, String u, String m, JsResult r) { r.cancel(); return true; }      // alert/confirm/prompt do nothing
      @Override public boolean onJsConfirm(WebView v, String u, String m, JsResult r) { r.cancel(); return true; }
    });
    setContentView(web);
    load();
  }
  @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); String n = i.getStringExtra("id"); if (n != null && !n.equals(id)) { id = n; origin = "https://" + id + ".agentos.app"; load(); } }
  @Override public void onBackPressed() { finish(); }

  /** Fetch the app's file from agentd (fresh after an edit), fall back to the cached copy, then load its origin. */
  void load() {
    new Thread(() -> {
      File cache = new File(getFilesDir(), "apps/" + id + ".html");
      byte[] p = null;
      try {
        String tok = Home.readFile(this, "agentd.token");
        HttpURLConnection h = (HttpURLConnection) new URL(AGENTD + "/apps/" + id + "/index.html").openConnection();
        h.setConnectTimeout(2000); h.setReadTimeout(5000); h.setRequestProperty("Authorization", "Bearer " + tok);
        if (h.getResponseCode() == 200) {
          try (InputStream in = h.getInputStream()) { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) o.write(buf, 0, n); p = o.toByteArray(); }
          cache.getParentFile().mkdirs(); Files.write(cache.toPath(), p);
        }
      } catch (Exception e) { Log.w(TAG, "fetch " + id + ": " + e); }
      if (p == null) try { p = Files.readAllBytes(cache.toPath()); } catch (Exception e) { p = "<!doctype html><body style=\"background:#000;color:#aaa;font-family:sans-serif;padding:40px;text-align:center\">This app isn't available right now.</body>".getBytes(StandardCharsets.UTF_8); }
      page = p;
      Matcher m = Pattern.compile("<title>([^<]*)</title>").matcher(new String(p, StandardCharsets.UTF_8));
      String title = m.find() ? m.group(1).trim() : id;
      runOnUiThread(() -> { setTitle(title); setTaskDescription(new ActivityManager.TaskDescription(title)); web.loadUrl(origin + "/index.html"); });
    }, "app-" + id).start();
  }

  WebResourceResponse serve(Uri u) {
    String path = u.getPath() == null ? "" : u.getPath();
    try {
      if (!origin.equals(u.getScheme() + "://" + u.getHost())) throw new Exception("foreign " + u);
      if (path.equals("/index.html")) { Map<String, String> h = new HashMap<>(); h.put("Content-Security-Policy", CSP); h.put("Cache-Control", "no-store");
        return new WebResourceResponse("text/html", "utf-8", 200, "OK", h, new ByteArrayInputStream(page)); }
      if (path.equals("/ui.css")) return new WebResourceResponse("text/css", "utf-8", getAssets().open("web/apps/ui.css"));
      if (path.equals("/ui.js")) return new WebResourceResponse("text/javascript", "utf-8", getAssets().open("web/apps/ui.js"));
      if (path.matches("/fonts/[A-Za-z0-9._-]+\\.woff2")) return new WebResourceResponse("font/woff2", null, getAssets().open("web" + path));
      throw new Exception("blocked " + u);
    } catch (Exception e) {
      Log.w(TAG, id + ": " + e.getMessage());
      return new WebResourceResponse("text/plain", "utf-8", 403, "blocked", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }
  }

  /** The whole bridge. Nothing here reads anything; the app id is fixed by this activity, not by the page. */
  class Bridge {
    @JavascriptInterface public void summary(String t) { getSharedPreferences(PREFS, 0).edit().putString(id, t == null ? "" : t.substring(0, Math.min(80, t.length()))).apply(); }
    @JavascriptInterface public void haptic(String kind) {
      Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
      int fx = "heavy".equals(kind) ? VibrationEffect.EFFECT_HEAVY_CLICK : "tick".equals(kind) ? VibrationEffect.EFFECT_TICK : VibrationEffect.EFFECT_CLICK;
      if (v != null) v.vibrate(VibrationEffect.createPredefined(fx));
    }
    @JavascriptInterface public void close() { runOnUiThread(MicroApp.this::finish); }
  }
}
