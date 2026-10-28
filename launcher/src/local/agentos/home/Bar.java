package local.agentos.home;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import org.json.JSONObject;

/**
 * The assistant bar: help from the agent inside any app, without leaving it.
 * Think of a sticky note you can slap on the bottom of whatever is open. Two overlay windows (TYPE_APPLICATION_OVERLAY, the
 * appop install.sh grants):
 *  - a slim handle on the right edge, tappable from any app (hidden while Home is in front: Home has its own ask bar);
 *  - the bar itself: a transparent WebView showing web/bar.html (mic, text field, a two-line transcript, x). It is sized to its
 *    content, so the app underneath stays visible and usable, and it sits BELOW the native island/approval cards
 *    (TYPE_ACCESSIBILITY_OVERLAY is a higher layer), so a card can never be obscured by it.
 * The page talks to agentd itself (asks, GPT Live voice over WebRTC, same gate as voice mode); approvals stay native.
 * Keyboard: the window starts FLAG_NOT_FOCUSABLE so the app underneath keeps its own keyboard behaviour; it becomes focusable
 * only while the text field is active (focus(true)) and gives focus back afterwards. ADJUST_RESIZE lifts the bar above the IME.
 * The agent never sees or taps the bar: screen.read skips windows of local.agentos.home (android Guard.offLimits).
 * Mic from a service needs a microphone foreground service while the bar is open (SYSTEM_ALERT_WINDOW exempts while-in-use).
 */
public class Bar extends Service {
  static final String TAG = "AgentOSBar", CH = "bar", OPEN = "local.agentos.home.BAR_OPEN", CLOSE = "local.agentos.home.BAR_CLOSE", HOME = "local.agentos.home.BAR_HOME";
  static Bar self;
  final Handler ui = new Handler(Looper.getMainLooper());
  WindowManager wm; WebView web; View handle; WindowManager.LayoutParams lp, hp; float density;
  boolean open, focusable, homeFront, ready;

  public static void open(Context c, boolean listen) { c.startForegroundService(new Intent(c, Bar.class).setAction(OPEN).putExtra("listen", listen)); }
  public static void close(Context c) { if (self != null) c.startService(new Intent(c, Bar.class).setAction(CLOSE)); }
  /** Home tells us when it is in front: no handle there (Home has its own ask bar), and an open bar closes. */
  public static void home(Context c, boolean front) { c.startService(new Intent(c, Bar.class).setAction(HOME).putExtra("front", front)); }

  @Override public IBinder onBind(Intent i) { return null; }
  @Override public void onCreate() {
    super.onCreate(); self = this;
    wm = (WindowManager) getSystemService(WINDOW_SERVICE); density = getResources().getDisplayMetrics().density;
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(new NotificationChannel(CH, "Assistant bar", NotificationManager.IMPORTANCE_MIN));
    // A foreground service the whole time (one silent, minimised notification): a cached launcher process gets killed under
    // memory pressure (lmkd, seen 10-28), and the handle would vanish with it. Microphone type: the bar records when open.
    Notification n = new Notification.Builder(this, CH).setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("Assistant bar")
        .setContentText("Tap the handle at the right edge of any app").setOngoing(true)
        .setContentIntent(android.app.PendingIntent.getForegroundService(this, 2, new Intent(this, Bar.class).setAction(OPEN), android.app.PendingIntent.FLAG_IMMUTABLE)).build();
    try { startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE); } catch (Exception e) { Log.w(TAG, "fg: " + e); }
    makeHandle();
  }
  @Override public int onStartCommand(Intent i, int f, int id) {
    String a = i == null ? null : i.getAction();
    if (OPEN.equals(a)) show(i.getBooleanExtra("listen", false));
    else if (CLOSE.equals(a)) hide();
    else if (HOME.equals(a)) { homeFront = i.getBooleanExtra("front", true); if (homeFront && open) { js("bar.close(true)"); hide(); } showHandle(); }
    return START_STICKY;
  }
  @Override public void onDestroy() { hide(); if (handle != null) try { wm.removeView(handle); } catch (Exception ignored) {} self = null; super.onDestroy(); }

  int px(float dp) { return Math.round(dp * density); }
  void js(String code) { ui.post(() -> { if (web != null) web.evaluateJavascript(code, null); }); }

  // ---- the edge handle: a 5 dp pill inside an invisible 22x72 dp touch target, mid-right ----
  void makeHandle() {
    FrameLayout box = new FrameLayout(this);
    View pill = new View(this);
    GradientDrawable d = new GradientDrawable(); d.setColor(0xE08E8E93); d.setStroke(px(1), 0x80FFFFFF); d.setCornerRadius(px(3)); // A2 idle grey + light edge: reads on light and dark apps
    pill.setBackground(d); pill.setElevation(px(2));
    FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(px(5), px(40), Gravity.END | Gravity.CENTER_VERTICAL); pl.rightMargin = px(2);
    box.addView(pill, pl);
    box.setOnClickListener(v -> { hap(); show(false); });
    box.setOnTouchListener((v, e) -> { // a short drag inwards also opens it (the pill scales as a hint while pressed)
      if (e.getAction() == MotionEvent.ACTION_DOWN) pill.animate().scaleX(1.6f).scaleY(1.1f).setDuration(120).start();
      else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) pill.animate().scaleX(1).scaleY(1).setDuration(250).start();
      return false;
    });
    hp = new WindowManager.LayoutParams(px(22), px(72), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
    hp.gravity = Gravity.END | Gravity.CENTER_VERTICAL; hp.y = 0; // mid-right: clear of chat send buttons (bottom) and toolbars (top) hp.setTitle("AgentOS handle");
    handle = box;
    showHandle();
  }
  void showHandle() {
    boolean want = !homeFront && !open;
    try { if (want && handle.getParent() == null) wm.addView(handle, hp); else if (!want && handle.getParent() != null) wm.removeView(handle); }
    catch (Exception e) { Log.w(TAG, "handle: " + e); }
  }

  // ---- the bar ----
  void show(boolean listen) {
    if (web == null) makeWeb();
    if (!open) {
      open = true; showHandle();
      lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, px(84), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
              | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT);
      lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; lp.setTitle("AgentOS bar");
      lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED;
      focusable = false;
      try { wm.addView(web, lp); } catch (Exception e) { Log.w(TAG, "add: " + e); open = false; showHandle(); return; }
    }
    if (ready) js("bar.open(" + listen + ")"); else pendingListen = listen ? 1 : 0;
    foreground();
  }
  /** The app the user is in, from the broker over the owner line (notifications.list {n:0} -> foreground {pkg, app}); the page
   *  sends it with every ask ({app: {pkg, label}}) so agentd can act on that app without naming it. */
  void foreground() {
    new Thread(() -> {
      try {
        String tok = Home.readFile(this, "phone.token");
        java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL("http://127.0.0.1:7777/rpc").openConnection();
        h.setConnectTimeout(1500); h.setReadTimeout(3000); h.setRequestMethod("POST"); h.setDoOutput(true);
        h.setRequestProperty("Authorization", "Bearer " + tok); h.setRequestProperty("Content-Type", "application/json");
        h.getOutputStream().write("{\"method\":\"notifications.list\",\"params\":{\"n\":0}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
        try (java.io.InputStream in = h.getInputStream()) { while ((n = in.read(buf)) > 0) bo.write(buf, 0, n); }
        JSONObject r = new JSONObject(bo.toString("UTF-8"));
        JSONObject fg = r.optJSONObject("foreground");
        if (fg != null && !fg.optString("pkg").isEmpty()) js("bar.app(" + new JSONObject().put("pkg", fg.optString("pkg")).put("label", fg.optString("app")) + ")");
      } catch (Exception e) { Log.w(TAG, "foreground: " + e); }
    }, "bar-fg").start();
  }
  int pendingListen = -1;
  void hide() {
    if (!open) return;
    open = false; focus(false);
    try { wm.removeView(web); } catch (Exception e) { Log.w(TAG, "remove: " + e); }
    showHandle();
  }
  /** Keyboard focus: only while the text field is active. Otherwise the app underneath keeps focus and its own keyboard. */
  void focus(boolean on) {
    if (web == null || !open || focusable == on) return;
    focusable = on;
    if (on) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    try { wm.updateViewLayout(web, lp); } catch (Exception e) { Log.w(TAG, "focus: " + e); }
    InputMethodManager imm = getSystemService(InputMethodManager.class);
    if (on) web.requestFocus(); // the keyboard is asked for once the window really has focus (onWindowFocusChanged in makeWeb)
    else imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
  }
  void hap() { Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE); if (v != null) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)); }

  void makeWeb() {
    web = new WebView(new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_NoActionBar)) {
      // Focusable now (focus(true)): re-focus the field so Chromium registers the editor, then ask for the keyboard ourselves.
      // An override, not a ViewTreeObserver listener: those are lost each time the view is removed and re-added to the window.
      @Override public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has && focusable) { if (!isFocused()) requestFocus(); js("bar.refocus()"); ui.postDelayed(() -> getSystemService(InputMethodManager.class).showSoftInput(this, 0), 80); }
      }
    };
    web.setBackgroundColor(0); web.setFocusable(true); web.setFocusableInTouchMode(true); // requestFocus() must work in touch mode (focus(true))
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
    s.setAllowFileAccess(false); s.setAllowContentAccess(false);
    s.setMediaPlaybackRequiresUserGesture(false);
    s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW); // https page -> ws://127.0.0.1 (loopback only)
    s.setCacheMode(WebSettings.LOAD_NO_CACHE);
    web.addJavascriptInterface(new Bridge(), "Native");
    web.setWebViewClient(new WebViewClient() {
      @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return Home.serve(Bar.this, r.getUrl()); }
      @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
    });
    web.setWebChromeClient(new WebChromeClient() {
      @Override public void onPermissionRequest(PermissionRequest r) { // the page's getUserMedia: mic only, and only if Android granted it
        for (String res : r.getResources()) if (!res.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) { r.deny(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) r.grant(r.getResources()); else r.deny();
      }
      @Override public boolean onConsoleMessage(ConsoleMessage m) { Log.i(TAG, "js: " + m.message() + " (" + m.lineNumber() + ")"); return true; }
    });
    web.setOnTouchListener((v, e) -> { if (e.getAction() == MotionEvent.ACTION_OUTSIDE && focusable) { focus(false); js("bar.blur()"); } return false; });
    web.loadUrl(Home.ORIGIN + "/bar.html");
  }

  /** The page's side. Requests only. Runs on a WebView worker thread. */
  class Bridge {
    @JavascriptInterface public String info() throws Exception { return new JSONObject().put("pkg", getPackageName()).put("density", density).toString(); }
    @JavascriptInterface public String token(String which) { return Home.readFile(Bar.this, "agentd.token"); }
    @JavascriptInterface public void ready() { ready = true; if (pendingListen >= 0) { js("bar.open(" + (pendingListen == 1) + ")"); pendingListen = -1; } }
    /** The card's height in CSS px: the window is cut to it so the app underneath stays touchable. */
    @JavascriptInterface public void size(float h) { ui.post(() -> { if (!open || lp == null) return; int p = px(h); if (p != lp.height) { lp.height = p; try { wm.updateViewLayout(web, lp); } catch (Exception ignored) {} } }); }
    @JavascriptInterface public void focus(boolean on) { ui.post(() -> Bar.this.focus(on)); }
    @JavascriptInterface public void close() { ui.post(Bar.this::hide); }
    @JavascriptInterface public void haptic(String kind) {
      Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
      int fx = "heavy".equals(kind) ? VibrationEffect.EFFECT_HEAVY_CLICK : "tick".equals(kind) ? VibrationEffect.EFFECT_TICK : VibrationEffect.EFFECT_CLICK;
      if (v != null) v.vibrate(VibrationEffect.createPredefined(fx));
    }
    /** Wake word bookkeeping: "voice:1" / "voice:0" (the bar holds the mic, the wake word lets go). */
    @JavascriptInterface public String wake(String cmd) { if (cmd != null && cmd.startsWith("voice:")) Wake.voice(cmd.endsWith("1")); return Wake.json(Bar.this); }
    /** Full voice mode on Home (the fallback screen), when the bar is too small for a long conversation. */
    @JavascriptInterface public void voiceMode() { ui.post(() -> { hide(); startActivity(new Intent(Bar.this, Home.class).setAction(Wake.WAKE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }); }
  }
}
