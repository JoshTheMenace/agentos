package local.agentos.home;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * AgentOS home: a native HOME activity whose whole surface is one WebView showing web/ (bundled as assets).
 * Think of it as a picture frame: the frame (this class) talks to Android, the picture (web/) is HTML.
 * The JS bridge is narrow and request-only: list/launch apps, haptics, settings, calendar, the agentd token, and an async GET
 * on agentd. Tool-server calls go through agentd's WebSocket. Approvals still happen natively (fingerprint card).
 * Dev: write a URL into /data/local/tmp/agentos-home.dev (e.g. http://127.0.0.1:5173/index.html over adb reverse)
 * and the page loads live from the Mac instead of the bundle.
 */
public class Home extends Activity {
  static final String TAG = "AgentOSHome", ORIGIN = "https://agentos.home", DEV_FLAG = "/data/local/tmp/agentos-home.dev";
  WebView web; PermissionRequest pendingMic; String devOrigin;
  final Map<String, byte[]> icons = new HashMap<>();
  float density; int insTop, insBottom, insIme;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    density = getResources().getDisplayMetrics().density;
    getWindow().setDecorFitsSystemWindows(false); // edge to edge: the page pads itself with insets()
    getWindow().setStatusBarColor(0); getWindow().setNavigationBarColor(0);
    getWindow().setNavigationBarContrastEnforced(false);
    WebView.setWebContentsDebuggingEnabled(true); // chrome://inspect from the Mac
    web = new WebView(this);
    web.setBackgroundColor(0xFF000000);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
    s.setAllowFileAccess(false); s.setAllowContentAccess(false);
    s.setMediaPlaybackRequiresUserGesture(false);                        // voice replies autoplay
    s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);       // https page -> ws://127.0.0.1 (loopback only)
    s.setCacheMode(WebSettings.LOAD_NO_CACHE);
    web.addJavascriptInterface(new Bridge(), "Native");
    web.setWebViewClient(new WebViewClient() {
      @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return serve(r.getUrl()); }
      @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return !allowed(r.getUrl()); } // bundled or dev pages only
    });
    web.setWebChromeClient(new WebChromeClient() {
      @Override public void onPermissionRequest(PermissionRequest r) { // page getUserMedia -> needs RECORD_AUDIO too
        boolean micOnly = Arrays.equals(r.getResources(), new String[] {PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        if (!micOnly || !allowed(r.getOrigin())) { r.deny(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) r.grant(r.getResources());
        else { pendingMic = r; requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 1); }
      }
      @Override public boolean onConsoleMessage(ConsoleMessage m) { Log.i(TAG, "js: " + m.message() + " (" + m.lineNumber() + ")"); return true; }
    });
    web.setOnApplyWindowInsetsListener((v, ins) -> {
      insTop = ins.getInsets(WindowInsets.Type.statusBars()).top; insBottom = ins.getInsets(WindowInsets.Type.navigationBars()).bottom;
      insIme = ins.getInsets(WindowInsets.Type.ime()).bottom;
      js("window.app&&app.insets&&app.insets(" + px(insTop) + "," + px(insBottom) + "," + px(insIme) + ")");
      return ins;
    });
    setContentView(web);
    WindowInsetsController ic = web.getWindowInsetsController(); // light icons on dark bars (needs the decor view: after setContentView)
    if (ic != null) ic.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    String dev = null;
    try { dev = new String(Files.readAllBytes(new File(DEV_FLAG).toPath()), StandardCharsets.UTF_8).trim(); } catch (Exception ignored) {}
    if (dev != null && dev.startsWith("http://127.0.0.1")) { devOrigin = dev.replaceAll("^(https?://[^/]+).*$", "$1"); web.loadUrl(dev); Log.i(TAG, "dev page " + dev); }
    else web.loadUrl(ORIGIN + "/index.html");
  }

  float px(int p) { return Math.round(p / density * 10) / 10f; }
  boolean allowed(Uri u) { String o = u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : ""); return ORIGIN.equals(o) || o.equals(devOrigin); }
  void js(String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }

  @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); js("window.app&&app.home&&app.home()"); } // HOME key while on home: reset
  @Override protected void onResume() { super.onResume(); js("window.app&&app.resume&&app.resume()"); }
  @Override public void onBackPressed() { js("window.app&&app.back&&app.back()"); } // never leave home
  @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
    if (pendingMic == null) return;
    if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) pendingMic.grant(pendingMic.getResources()); else pendingMic.deny();
    pendingMic = null;
  }

  // Bundled page: https://agentos.home/<file> -> assets/web/<file>; https://agentos.home/icon/<pkg> -> the app icon as PNG.
  WebResourceResponse serve(Uri u) {
    if (!ORIGIN.equals(u.getScheme() + "://" + u.getHost())) return null;
    String path = u.getPath() == null ? "" : u.getPath().substring(1);
    try {
      if (path.startsWith("icon/")) return new WebResourceResponse("image/png", null, new ByteArrayInputStream(icon(path.substring(5))));
      String mime = path.endsWith(".html") ? "text/html" : path.endsWith(".css") ? "text/css" : path.endsWith(".js") ? "text/javascript"
          : path.endsWith(".woff2") ? "font/woff2" : path.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream";
      return new WebResourceResponse(mime, "utf-8", getAssets().open("web/" + path));
    } catch (Exception e) { return new WebResourceResponse("text/plain", "utf-8", 404, "not found", Collections.emptyMap(), new ByteArrayInputStream(new byte[0])); }
  }

  byte[] icon(String pkg) throws Exception {
    synchronized (icons) {
      byte[] b = icons.get(pkg);
      if (b != null) return b;
      Drawable d = getPackageManager().getApplicationIcon(pkg);
      int sz = Math.round(60 * density);
      Bitmap bm = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
      Canvas c = new Canvas(bm); d.setBounds(0, 0, sz, sz); d.draw(c);
      ByteArrayOutputStream o = new ByteArrayOutputStream(); bm.compress(Bitmap.CompressFormat.PNG, 90, o); bm.recycle();
      icons.put(pkg, b = o.toByteArray());
      return b;
    }
  }

  String file(String name) { try { return new String(Files.readAllBytes(new File(getFilesDir(), name).toPath()), StandardCharsets.UTF_8).trim(); } catch (Exception e) { return null; } }

  /** The JS side of the frame. Every method is a request; nothing here grants anything. Runs on a WebView worker thread. */
  class Bridge {
    @JavascriptInterface public String info() throws Exception {
      return new JSONObject().put("pkg", getPackageName()).put("dev", devOrigin != null).put("density", density)
          .put("top", px(insTop)).put("bottom", px(insBottom)).put("ime", px(insIme)).toString();
    }
    @JavascriptInterface public String token(String which) { return file("agentd.token"); } // the only token this app holds
    @JavascriptInterface public String apps() throws Exception {
      Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
      PackageManager pm = getPackageManager();
      List<ResolveInfo> ri = pm.queryIntentActivities(main, 0);
      List<String[]> out = new ArrayList<>();
      for (ResolveInfo r : ri) if (!r.activityInfo.packageName.equals(getPackageName())) out.add(new String[] {r.activityInfo.packageName, String.valueOf(r.loadLabel(pm))});
      Collections.sort(out, (a, b) -> a[1].compareToIgnoreCase(b[1]));
      JSONArray a = new JSONArray();
      for (String[] e : out) a.put(new JSONObject().put("pkg", e[0]).put("label", e[1]));
      return a.toString();
    }
    @JavascriptInterface public boolean launch(String pkg) {
      Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
      if (i == null) return false;
      startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
      return true;
    }
    @JavascriptInterface public void haptic(String kind) {
      Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
      int fx = "heavy".equals(kind) ? VibrationEffect.EFFECT_HEAVY_CLICK : "tick".equals(kind) ? VibrationEffect.EFFECT_TICK : VibrationEffect.EFFECT_CLICK;
      if (v != null) v.vibrate(VibrationEffect.createPredefined(fx));
    }
    @JavascriptInterface public void settings(String what) { // "" | "wifi" | "sound" | "app:<pkg>"
      Intent i = what != null && what.startsWith("app:") ? new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + what.substring(4)))
          : new Intent("wifi".equals(what) ? Settings.ACTION_WIFI_SETTINGS : "sound".equals(what) ? Settings.ACTION_SOUND_SETTINGS : Settings.ACTION_SETTINGS);
      startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    @JavascriptInterface public void shade() { // pull down notifications (EXPAND_STATUS_BAR; hidden API via reflection)
      try { Object sb = getSystemService("statusbar"); sb.getClass().getMethod("expandNotificationsPanel").invoke(sb); } catch (Exception e) { Log.w(TAG, "shade: " + e); }
    }
    @JavascriptInterface public String calendarNext() { // next instance within 7 days, or null (needs READ_CALENDAR; install.sh grants it)
      if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null;
      long now = System.currentTimeMillis();
      Uri u = CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(String.valueOf(now - 60_000)).appendPath(String.valueOf(now + 7 * 86_400_000L)).build();
      String[] cols = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY};
      try (Cursor c = getContentResolver().query(u, cols, null, null, CalendarContract.Instances.BEGIN + " ASC")) {
        while (c != null && c.moveToNext()) {
          if (c.getLong(2) < now) continue; // already over
          return new JSONObject().put("title", c.getString(0)).put("begin", c.getLong(1)).put("end", c.getLong(2))
              .put("location", c.isNull(3) ? "" : c.getString(3)).put("allDay", c.getInt(4) == 1).toString();
        }
        return null;
      } catch (Exception e) { Log.w(TAG, "calendar: " + e); return null; }
    }
    /** Async GET on agentd (the page's https origin can't fetch http://127.0.0.1 cross-origin). Reply: app.rpcDone(id, json).
     *  Tool-server calls go over agentd's WebSocket passthrough instead; this app holds no :7777 token. */
    @JavascriptInterface public void get(String id, String path) {
      new Thread(() -> {
        String out;
        try {
          String tok = file("agentd.token");
          if (tok == null) throw new Exception("no token (run launcher/install.sh)");
          HttpURLConnection h = (HttpURLConnection) new URL("http://127.0.0.1:8787" + path).openConnection();
          h.setConnectTimeout(3000); h.setReadTimeout(20_000);
          h.setRequestProperty("Authorization", "Bearer " + tok);
          InputStream in = h.getResponseCode() < 400 ? h.getInputStream() : h.getErrorStream();
          ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
          while (in != null && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
          out = bo.size() > 0 ? bo.toString("UTF-8") : new JSONObject().put("ok", false).put("error", "HTTP " + h.getResponseCode()).toString();
        } catch (Exception e) { out = "{\"ok\":false,\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}"; }
        js("window.app&&app.rpcDone(" + JSONObject.quote(id) + "," + out + ")");
      }, "get-" + path).start();
    }
  }
}
