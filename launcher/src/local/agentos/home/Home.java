package local.agentos.home;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Base64;
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
      @Override public void onPermissionRequest(PermissionRequest r) { // page getUserMedia: mic (voice) or camera (voice vision), each needs its Android permission too
        List<String> need = new ArrayList<>();
        for (String res : r.getResources()) {
          if (res.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) need.add(Manifest.permission.RECORD_AUDIO);
          else if (res.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) need.add(Manifest.permission.CAMERA);
          else { r.deny(); return; }
        }
        if (!allowed(r.getOrigin())) { r.deny(); return; }
        need.removeIf(p -> checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED);
        if (need.isEmpty()) r.grant(r.getResources());
        else { pendingMic = r; requestPermissions(need.toArray(new String[0]), 1); }
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
  // Attach (＋): Camera = the system camera writing into our private shot file; Photos = Android's photo picker (no storage
  // permission). The picture comes back to the page as one JPEG data: URL (long side <= 1280 px, EXIF rotation applied).
  // The camera shot is deleted as soon as it's read; nothing of it is kept here.
  void pick(String kind) {
    Intent i;
    if ("camera".equals(kind)) {
      Uri u = Uri.parse("content://" + getPackageName() + ".shot/shot.jpg");
      i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, u).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
      i.setClipData(ClipData.newRawUri("", u));
    } else i = new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
    try { startActivityForResult(i, "camera".equals(kind) ? 2 : 3); } catch (Exception e) { Log.w(TAG, "pick: " + e); js("window.app&&app.picked(null)"); }
  }
  @Override protected void onActivityResult(int code, int res, Intent data) {
    if (code != 2 && code != 3) return;
    File shot = new File(getCacheDir(), "shot.jpg");
    Uri u = code == 2 ? Uri.fromFile(shot) : data == null ? null : data.getData();
    new Thread(() -> {
      String url = null;
      try {
        if (res == RESULT_OK && u != null) {
          Bitmap bm = ImageDecoder.decodeBitmap(code == 2 ? ImageDecoder.createSource(shot) : ImageDecoder.createSource(getContentResolver(), u), (d, info, src) -> {
            int w = info.getSize().getWidth(), h = info.getSize().getHeight(); float k = Math.min(1f, 1280f / Math.max(w, h));
            d.setTargetSize(Math.round(w * k), Math.round(h * k)); d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
          });
          ByteArrayOutputStream o = new ByteArrayOutputStream(); bm.compress(Bitmap.CompressFormat.JPEG, 80, o); bm.recycle();
          url = "data:image/jpeg;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
        }
      } catch (Exception e) { Log.w(TAG, "picked: " + e); }
      shot.delete();
      js("window.app&&app.picked(" + (url == null ? "null" : JSONObject.quote(url)) + "," + JSONObject.quote(code == 2 ? "camera" : "photos") + ")");
    }, "pick").start();
  }

  /** content://local.agentos.home.shot/shot.jpg: the one file the system camera may write (granted per capture, not exported). */
  public static class Shot extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public ParcelFileDescriptor openFile(Uri u, String mode) throws java.io.FileNotFoundException {
      return ParcelFileDescriptor.open(new File(getContext().getCacheDir(), "shot.jpg"), ParcelFileDescriptor.parseMode(mode.contains("w") ? "rwt" : "r"));
    }
    @Override public String getType(Uri u) { return "image/jpeg"; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
  }

  @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
    if (pendingMic == null) return;
    boolean ok = res.length > 0; for (int x : res) ok &= x == PackageManager.PERMISSION_GRANTED;
    if (ok) pendingMic.grant(pendingMic.getResources()); else pendingMic.deny();
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

  String file(String name) { return readFile(this, name); }
  static String readFile(Activity a, String name) { try { return new String(Files.readAllBytes(new File(a.getFilesDir(), name).toPath()), StandardCharsets.UTF_8).trim(); } catch (Exception e) { return null; } }

  /** The JS side of the frame. Every method is a request; nothing here grants anything. Runs on a WebView worker thread. */
  class Bridge {
    @JavascriptInterface public String info() throws Exception {
      return new JSONObject().put("pkg", getPackageName()).put("dev", devOrigin != null).put("density", density)
          .put("top", px(insTop)).put("bottom", px(insBottom)).put("ime", px(insIme)).toString();
    }
    @JavascriptInterface public void pick(String kind) { runOnUiThread(() -> Home.this.pick(kind)); } // "camera" | "photos" -> app.picked(dataUrl|null, kind)
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
    @JavascriptInterface public void get(String id, String path) { http(id, "http://127.0.0.1:8787" + path, "agentd.token", null); }
    /** The owner line: this app's own uid straight to the broker (:7777, files/phone.token). The broker treats it as the
     *  owner's tap (Blockers: trackers.block/unblock without a card; Passwords: vault.list/remove). agentd (and so any
     *  model) connects from Termux's uid and never gets that. No secrets travel here: passwords are added natively. */
    @JavascriptInterface public void phone(String id, String method, String params) {
      try { http(id, "http://127.0.0.1:7777/rpc", "phone.token", new JSONObject().put("method", method).put("params", new JSONObject(params == null || params.isEmpty() ? "{}" : params)).toString()); }
      catch (Exception e) { js("window.app&&app.rpcDone(" + JSONObject.quote(id) + ",{\"ok\":false,\"error\":\"bad params\"})"); }
    }
    /** Micro-apps (MicroApp.java): open one full screen in its own task; the one-line summaries they left for the home widgets. */
    @JavascriptInterface public void openApp(String id) {
      if (id == null || !id.matches("[a-z0-9-]{1,32}")) return;
      startActivity(new Intent(Home.this, MicroApp.class).putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
    @JavascriptInterface public String appSummaries() { return new JSONObject(getSharedPreferences(MicroApp.PREFS, 0).getAll()).toString(); }
    /** Add a saved password: the native screen in local.agentos (the password never enters this WebView). */
    @JavascriptInterface public void vaultAdd(String pkg) {
      startActivity(new Intent().setClassName("local.agentos", "local.agentos.VaultAddActivity").putExtra("pkg", pkg == null ? "" : pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    void http(String id, String url, String tokenFile, String body) {
      new Thread(() -> {
        String out;
        try {
          String tok = file(tokenFile);
          if (tok == null) throw new Exception("no " + tokenFile + " (run launcher/install.sh)");
          HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
          h.setConnectTimeout(3000); h.setReadTimeout(90_000);
          h.setRequestProperty("Authorization", "Bearer " + tok);
          if (body != null) { h.setRequestMethod("POST"); h.setDoOutput(true); h.setRequestProperty("Content-Type", "application/json"); h.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8)); }
          InputStream in = h.getResponseCode() < 400 ? h.getInputStream() : h.getErrorStream();
          ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
          while (in != null && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
          out = bo.size() > 0 ? bo.toString("UTF-8") : new JSONObject().put("ok", false).put("error", "HTTP " + h.getResponseCode()).toString();
        } catch (Exception e) { out = "{\"ok\":false,\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}"; }
        js("window.app&&app.rpcDone(" + JSONObject.quote(id) + "," + out + ")");
      }, "http-" + id).start();
    }
  }
}
