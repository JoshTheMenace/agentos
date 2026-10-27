package local.agentos.appmod;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Renders "mods": small buttons that sit on top of another app, anchored to its layout through the accessibility tree.
 * A mod never touches the app's code; it reads the app's on-screen text and presses the app's own buttons (ACTION_CLICK).
 * It shows only while the target app is the active window and its anchor is on screen.
 */
public class ModService extends AccessibilityService {
  static final String TAG = "AppMod";
  static ModService self;

  static class Style { int bg, fg; float round; float textPx; Typeface tf; }
  static class Mod {
    JSONObject spec; String id, pkg;
    TextView btn; WindowManager.LayoutParams lp; Style style; boolean shown;
    LinearLayout chips; WindowManager.LayoutParams clp; List<TextView> chipViews = new ArrayList<>(); double value = Double.NaN;
    long t0; BroadcastReceiver.PendingResult pending; int sampleFails;
  }

  final Handler h = new Handler(Looper.getMainLooper());
  final Map<String, Mod> mods = new LinkedHashMap<>();
  final Map<String, Style> styles = new HashMap<>(); // pkg|selector|orientation|night -> sampled look
  final Runnable refreshR = this::refresh;
  WindowManager wm; float dp; boolean sampling;
  String fgPkg = ""; long fgSince; // sample colors only after the app's launch animation has settled

  @Override protected void onServiceConnected() {
    self = this;
    wm = getSystemService(WindowManager.class);
    dp = getResources().getDisplayMetrics().density;
    try {
      JSONObject saved = new JSONObject(prefs().getString("mods", "{}"));
      for (Iterator<String> it = saved.keys(); it.hasNext(); ) put(saved.getJSONObject(it.next()));
    } catch (JSONException e) { Log.w(TAG, "bad saved mods", e); }
    refresh();
  }

  @Override public void onAccessibilityEvent(AccessibilityEvent e) { h.removeCallbacks(refreshR); h.postDelayed(refreshR, 16); }
  @Override public void onInterrupt() {}
  @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); fgSince = SystemClock.uptimeMillis(); h.postDelayed(refreshR, 50); }
  @Override public void onDestroy() { for (Mod m : mods.values()) hide(m); self = null; super.onDestroy(); }

  // ---- commands (apply / remove / list) ----

  void apply(String json, BroadcastReceiver.PendingResult pr) {
    Mod m;
    try { m = put(validate(new JSONObject(json))); }
    catch (Exception e) { pr.setResultData(err(e.getMessage())); pr.finish(); return; }
    save();
    m.t0 = SystemClock.uptimeMillis(); m.pending = pr;
    h.postDelayed(() -> done(m, false), 1500); // target not on screen: saved, will appear when it is
    refresh();
  }

  String remove(String id) {
    Mod m = mods.remove(id);
    if (m == null) return err("no mod " + id);
    hide(m); save();
    return "{\"ok\":true,\"removed\":" + JSONObject.quote(id) + "}";
  }

  String list() {
    JSONArray a = new JSONArray();
    for (Mod m : mods.values()) {
      try { a.put(new JSONObject().put("id", m.id).put("package", m.pkg).put("label", m.spec.optString("label")).put("shown", m.shown)); }
      catch (JSONException ignored) {}
    }
    return a.toString();
  }

  static JSONObject validate(JSONObject s) throws JSONException {
    for (String k : new String[]{"id", "package", "label"}) if (s.optString(k).isEmpty()) throw new JSONException("missing " + k);
    if (s.optJSONObject("anchor") == null) throw new JSONException("missing anchor");
    String t = s.getJSONObject("action").getString("type");
    if (!t.equals("press") && !t.equals("compute")) throw new JSONException("action.type must be press or compute");
    if (s.getString("label").length() > 24) throw new JSONException("label too long");
    return s;
  }

  Mod put(JSONObject spec) {
    Mod old = mods.remove(spec.optString("id"));
    if (old != null) hide(old);
    Mod m = new Mod(); m.spec = spec; m.id = spec.optString("id"); m.pkg = spec.optString("package");
    mods.put(m.id, m);
    return m;
  }

  void done(Mod m, boolean shown) {
    if (m.pending == null) return;
    long ms = SystemClock.uptimeMillis() - m.t0;
    Log.i(TAG, "apply " + m.id + (shown ? " on screen in " + ms + " ms" : " saved (target not on screen)"));
    m.pending.setResultData("{\"ok\":true,\"id\":" + JSONObject.quote(m.id) + ",\"shown\":" + shown + (shown ? ",\"ms\":" + ms : "") + "}");
    m.pending.finish(); m.pending = null;
  }

  SharedPreferences prefs() { return getSharedPreferences("mods", MODE_PRIVATE); }
  void save() {
    JSONObject o = new JSONObject();
    try { for (Mod m : mods.values()) o.put(m.id, m.spec); } catch (JSONException ignored) {}
    prefs().edit().putString("mods", o.toString()).apply();
  }
  static String err(String msg) { return "{\"ok\":false,\"error\":" + JSONObject.quote(String.valueOf(msg)) + "}"; }

  // ---- follow the app: show, place, hide ----

  void refresh() {
    AccessibilityNodeInfo root = getRootInActiveWindow();
    String pkg = root == null ? "" : String.valueOf(root.getPackageName());
    if (!pkg.equals(fgPkg)) { fgPkg = pkg; fgSince = SystemClock.uptimeMillis(); }
    List<Rect> keys = null;
    for (Mod m : mods.values()) {
      if (!pkg.equals(m.pkg) || !whenOk(root, m)) { hide(m); continue; }
      if (keys == null) keys = buttons(root, new ArrayList<>());
      Rect r = spot(root, m, keys);
      if (r == null) { hide(m); continue; } // no free spot in this layout: better absent than covering a real button
      if (!sampled(root, m)) continue;
      show(m, r, styleOf(m, m.spec.optJSONObject("style")));
      if (m.chips != null) updateChips(m, root, r);
    }
  }

  /** First placement (the spec itself, then each "alt") whose anchor is on screen and that covers none of the app's own buttons. */
  Rect spot(AccessibilityNodeInfo root, Mod m, List<Rect> keys) {
    JSONArray alts = m.spec.optJSONArray("alt");
    int sw = getResources().getDisplayMetrics().widthPixels;
    for (int i = -1; i < (alts == null ? 0 : alts.length()); i++) {
      JSONObject s = i < 0 ? m.spec : alts.optJSONObject(i);
      AccessibilityNodeInfo a = find(root, m.pkg, s.opt("anchor"));
      if (a == null) continue;
      Rect ar = new Rect(); a.getBoundsInScreen(ar);
      Rect r = place(ar, s, dim(s, "w", ar.width()), dim(s, "h", ar.height()));
      boolean clear = r.left >= 0 && r.right <= sw && r.top >= 0;
      for (Rect k : keys) if (clear && Rect.intersects(k, r) && !k.contains(r)) clear = false;
      if (clear) return r;
    }
    return null;
  }

  /** Bounds of every visible clickable node: the app's own buttons, which a mod must never cover. */
  static List<Rect> buttons(AccessibilityNodeInfo n, List<Rect> out) {
    if (n == null || !n.isVisibleToUser()) return out;
    if (n.isClickable()) { Rect r = new Rect(); n.getBoundsInScreen(r); out.add(r); }
    for (int i = 0; i < n.getChildCount(); i++) buttons(n.getChild(i), out);
    return out;
  }

  boolean whenOk(AccessibilityNodeInfo root, Mod m) {
    JSONArray w = m.spec.optJSONArray("when");
    for (int i = 0; w != null && i < w.length(); i++) if (find(root, m.pkg, w.opt(i)) == null) return false;
    return true;
  }

  /** First visible node matching {"id": "..."} / {"text": "..."} / "id", or a list of those (first match wins: app layout variants). */
  static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String pkg, Object sel) {
    if (root == null || sel == null) return null;
    if (sel instanceof JSONArray) {
      for (int i = 0; i < ((JSONArray) sel).length(); i++) { AccessibilityNodeInfo n = find(root, pkg, ((JSONArray) sel).opt(i)); if (n != null) return n; }
      return null;
    }
    String id = sel instanceof JSONObject ? ((JSONObject) sel).optString("id") : sel.toString();
    String text = sel instanceof JSONObject ? ((JSONObject) sel).optString("text") : "";
    List<AccessibilityNodeInfo> l = !id.isEmpty()
        ? root.findAccessibilityNodeInfosByViewId(id.contains(":id/") ? id : pkg + ":id/" + id)
        : root.findAccessibilityNodeInfosByText(text);
    for (AccessibilityNodeInfo n : l)
      if (n.isVisibleToUser() && (text.isEmpty() || text.contentEquals(n.getText() == null ? "" : n.getText()))) return n;
    return null;
  }

  int px(double v) { return Math.round((float) v * dp); }
  int dim(JSONObject s, String k, int anchorPx) { return "anchor".equals(s.opt(k)) ? anchorPx : px(s.optDouble(k, 48)); }

  /** Where the mod goes relative to its anchor: place above|below|left|right|over, align start|center|end, gap/dx/dy in dp. */
  Rect place(Rect a, JSONObject s, int w, int hh) {
    String p = s.optString("place", "above"), al = s.optString("align", "center");
    int g = px(s.optDouble("gap", 8)), x, y;
    if (p.equals("above") || p.equals("below")) {
      y = p.equals("above") ? a.top - g - hh : a.bottom + g;
      x = al.equals("start") ? a.left : al.equals("end") ? a.right - w : a.centerX() - w / 2;
    } else if (p.equals("left") || p.equals("right")) {
      x = p.equals("left") ? a.left - g - w : a.right + g;
      y = al.equals("start") ? a.top : al.equals("end") ? a.bottom - hh : a.centerY() - hh / 2;
    } else { x = a.centerX() - w / 2; y = a.centerY() - hh / 2; }
    x += px(s.optDouble("dx", 0)); y += px(s.optDouble("dy", 0));
    return new Rect(x, y, x + w, y + hh);
  }

  static WindowManager.LayoutParams params() {
    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(0, 0, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT);
    lp.gravity = Gravity.TOP | Gravity.LEFT;
    lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
    return lp;
  }

  static boolean move(WindowManager.LayoutParams lp, int x, int y, int w, int hh) {
    boolean changed = lp.x != x || lp.y != y || lp.width != w || lp.height != hh;
    lp.x = x; lp.y = y; lp.width = w; lp.height = hh;
    return changed;
  }

  void show(Mod m, Rect r, Style st) {
    if (m.btn == null) {
      m.btn = new TextView(this); m.btn.setGravity(Gravity.CENTER); m.btn.setSingleLine();
      m.btn.setText(m.spec.optString("label")); m.btn.setContentDescription(m.spec.optString("label") + " (mod)");
      m.btn.setOnClickListener(v -> act(m));
      m.btn.setOnLongClickListener(v -> { remove(m.id); Toast.makeText(this, m.spec.optString("label") + " removed", Toast.LENGTH_SHORT).show(); return true; });
      m.lp = params();
    }
    if (m.style != st || m.lp.height != r.height()) { paint(m.btn, st, r.height()); m.style = st; }
    boolean changed = move(m.lp, r.left, r.top, r.width(), r.height());
    if (!m.shown) {
      m.btn.setAlpha(0f); m.btn.setScaleX(0.85f); m.btn.setScaleY(0.85f);
      wm.addView(m.btn, m.lp); m.shown = true;
      m.btn.getViewTreeObserver().registerFrameCommitCallback(() -> done(m, true));
      m.btn.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
    } else if (changed) wm.updateViewLayout(m.btn, m.lp);
  }

  void hide(Mod m) {
    hideChips(m);
    if (m.shown) { wm.removeViewImmediate(m.btn); m.shown = false; }
  }

  void paint(TextView v, Style s, int hh) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(s.bg); d.setCornerRadius(s.round * hh / 2f);
    v.setBackground(new RippleDrawable(ColorStateList.valueOf((s.fg & 0x00FFFFFF) | 0x33000000), d, null));
    v.setTextColor(s.fg); v.setTypeface(s.tf);
    v.setTextSize(TypedValue.COMPLEX_UNIT_PX, s.textPx > 0 ? Math.min(s.textPx, hh * 0.45f) : hh * 0.4f);
    v.setPadding(px(14), 0, px(14), 0);
  }

  // ---- behaviors ----

  void act(Mod m) {
    JSONObject a = m.spec.optJSONObject("action");
    if ("press".equals(a.optString("type"))) press(m.pkg, a.optJSONArray("press"), "", null);
    else if (m.chips != null) hideChips(m);
    else showChips(m);
  }

  /** Presses the app's own buttons in order. "$value" types a number through the "keys" map (char -> button id). */
  boolean press(String pkg, JSONArray seq, String value, JSONObject keys) {
    long t = SystemClock.uptimeMillis();
    AccessibilityNodeInfo root = getRootInActiveWindow();
    for (int i = 0; seq != null && i < seq.length(); i++) {
      Object k = seq.opt(i);
      if ("$value".equals(k)) { for (char c : value.toCharArray()) if (!click(root, pkg, keys.optString(String.valueOf(c)))) return false; }
      else if (!click(root, pkg, k)) return false;
    }
    Log.i(TAG, "pressed " + seq.length() + " steps in " + (SystemClock.uptimeMillis() - t) + " ms");
    return true;
  }

  boolean click(AccessibilityNodeInfo root, String pkg, Object id) {
    AccessibilityNodeInfo n = find(root, pkg, id);
    if (n == null) { Log.w(TAG, "no button " + id); return false; }
    if (n.isEnabled()) n.performAction(AccessibilityNodeInfo.ACTION_CLICK); // a disabled key (Backspace on empty) is a no-op, not an error
    return true;
  }

  /** Reads the first number the app shows among the "read" ids (handles 1,234 and the − sign). */
  static double read(AccessibilityNodeInfo root, String pkg, JSONArray ids) {
    for (int i = 0; ids != null && i < ids.length(); i++) {
      AccessibilityNodeInfo n = find(root, pkg, ids.optString(i));
      CharSequence t = n == null ? null : n.getText();
      if (t == null) continue;
      try { return Double.parseDouble(t.toString().replace(",", "").replace("−", "-").replace(" ", "")); }
      catch (NumberFormatException ignored) {}
    }
    return Double.NaN;
  }

  static String money(double v) { return String.format(java.util.Locale.US, "%.2f", v); }
  static String keyed(double v) { return money(v).replaceAll("\\.?0+$", ""); }

  void showChips(Mod m) {
    JSONObject a = m.spec.optJSONObject("action");
    JSONArray cs = a.optJSONArray("chips");
    Style st = styleOf(m, a.optJSONObject("chipStyle"));
    m.chips = new LinearLayout(this); m.chips.setGravity(Gravity.CENTER_VERTICAL); m.chipViews.clear();
    int hh = m.lp.height;
    for (int i = 0; i < Math.max(1, cs == null ? 0 : cs.length()); i++) {
      JSONObject c = cs == null ? new JSONObject() : cs.optJSONObject(i);
      TextView t = new TextView(this); t.setGravity(Gravity.CENTER); t.setSingleLine(); paint(t, st, hh);
      t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.min(t.getTextSize(), hh * 0.36f));
      t.setOnClickListener(v -> {
        if (Double.isNaN(m.value)) return;
        double out = Math.round(m.value * c.optDouble("factor") * 100) / 100.0;
        hideChips(m);
        press(m.pkg, a.optJSONArray("onChip"), keyed(out), a.optJSONObject("keys"));
      });
      LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, hh);
      ll.leftMargin = i == 0 ? 0 : px(8);
      m.chips.addView(t, ll); m.chipViews.add(t);
    }
    m.clp = params();
    m.value = Double.MAX_VALUE; // force a label refresh
    updateChips(m, getRootInActiveWindow(), new Rect(m.lp.x, m.lp.y, m.lp.x + m.lp.width, m.lp.y + m.lp.height));
    m.chips.setAlpha(0f); m.chips.setTranslationX(px(16));
    wm.addView(m.chips, m.clp);
    m.chips.animate().alpha(1f).translationX(0).setDuration(160).start();
  }

  /** Chips follow the button and recompute live as the app's display changes. */
  void updateChips(Mod m, AccessibilityNodeInfo root, Rect btn) {
    JSONObject a = m.spec.optJSONObject("action");
    double x = read(root, m.pkg, a.optJSONArray("read"));
    if (Double.compare(x, m.value) != 0) {
      m.value = x;
      JSONArray cs = a.optJSONArray("chips");
      for (int i = 0; i < m.chipViews.size(); i++) {
        JSONObject c = cs == null ? null : cs.optJSONObject(i);
        m.chipViews.get(i).setText(Double.isNaN(x) || c == null ? a.optString("empty", "Type an amount")
            : c.optString("label") + "  " + money(x * c.optDouble("factor")));
      }
    }
    m.chips.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
    int w = m.chips.getMeasuredWidth();
    int sw = getResources().getDisplayMetrics().widthPixels;
    int cx = btn.left > sw - btn.right ? Math.max(0, btn.left - px(8) - w) : Math.min(sw - w, btn.right + px(8));
    boolean changed = move(m.clp, cx, btn.top, w, btn.height());
    if (changed && m.chips.isAttachedToWindow()) wm.updateViewLayout(m.chips, m.clp);
  }

  void hideChips(Mod m) {
    if (m.chips != null && m.chips.isAttachedToWindow()) wm.removeViewImmediate(m.chips);
    m.chips = null; m.chipViews.clear(); m.value = Double.NaN;
  }

  // ---- look native: sample the app's own buttons from one screenshot ----

  String key(Mod m, Object sel) {
    Configuration c = getResources().getConfiguration();
    return m.pkg + "|" + sel + "|" + c.orientation + "|" + (c.uiMode & Configuration.UI_MODE_NIGHT_MASK);
  }

  Style styleOf(Mod m, JSONObject st) {
    if (st != null && st.has("sample")) { Style s = styles.get(key(m, st.opt("sample"))); if (s != null) return s; }
    return explicit(st, m.pkg);
  }

  Style explicit(JSONObject st, String pkg) {
    Style s = new Style();
    s.bg = st != null && st.has("bg") ? Color.parseColor(st.optString("bg")) : 0xFFD3E3FD;
    s.fg = st != null && st.has("fg") ? Color.parseColor(st.optString("fg")) : 0xFF041E49;
    s.round = st == null ? 1f : (float) st.optDouble("round", 1);
    s.tf = font(pkg);
    return s;
  }

  /** True once every {"sample": id} in the spec has a measured style for this orientation/theme; else starts one screenshot. */
  boolean sampled(AccessibilityNodeInfo root, Mod m) {
    JSONObject st = m.spec.optJSONObject("style"), act = m.spec.optJSONObject("action");
    JSONObject[] want = {st, act == null ? null : act.optJSONObject("chipStyle")};
    Map<String, Rect> todo = new HashMap<>(); Map<String, Float> text = new HashMap<>(); Map<String, Object> sel = new HashMap<>();
    for (JSONObject w : want) {
      if (w == null || !w.has("sample") || styles.containsKey(key(m, w.opt("sample")))) continue;
      AccessibilityNodeInfo n = find(root, m.pkg, w.opt("sample"));
      if (n == null) continue; // not on screen: explicit fallback
      Rect b = new Rect(); n.getBoundsInScreen(b);
      todo.put(key(m, w.opt("sample")), b); text.put(key(m, w.opt("sample")), textPx(n)); sel.put(key(m, w.opt("sample")), w.opt("sample"));
    }
    if (todo.isEmpty() || m.sampleFails >= 3) return true;
    long settle = fgSince + 400 - SystemClock.uptimeMillis();
    if (settle > 0) { h.postDelayed(refreshR, settle); return false; }
    if (sampling) return false;
    sampling = true;
    long t = SystemClock.uptimeMillis();
    takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
      @Override public void onSuccess(ScreenshotResult r) {
        if (!stillThere(m, todo, sel)) { // app left or moved mid-shot (launch, rotation, another screen): retry, never cache it
          r.getHardwareBuffer().close(); sampling = false; h.postDelayed(refreshR, 300); return;
        }
        Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
        Bitmap bm = hw.copy(Bitmap.Config.ARGB_8888, false);
        hw.recycle(); r.getHardwareBuffer().close();
        Typeface tf = font(m.pkg);
        for (Map.Entry<String, Rect> e : todo.entrySet()) {
          Style s = measure(bm, e.getValue()); s.textPx = text.get(e.getKey()); s.tf = tf;
          styles.put(e.getKey(), s);
          Log.i(TAG, "sampled " + e.getKey() + String.format(" bg=#%06X fg=#%06X round=%.2f text=%.0fpx", s.bg & 0xFFFFFF, s.fg & 0xFFFFFF, s.round, s.textPx));
        }
        bm.recycle(); sampling = false;
        Log.i(TAG, "style sample took " + (SystemClock.uptimeMillis() - t) + " ms");
        refresh();
      }
      @Override public void onFailure(int code) {
        Log.w(TAG, "screenshot failed " + code); sampling = false; m.sampleFails++;
        h.postDelayed(refreshR, 350); // usually the 3-per-second screenshot limit
      }
    });
    return false;
  }

  boolean stillThere(Mod m, Map<String, Rect> todo, Map<String, Object> sel) {
    AccessibilityNodeInfo root = getRootInActiveWindow();
    if (root == null || !m.pkg.contentEquals(root.getPackageName())) return false;
    for (Map.Entry<String, Rect> e : todo.entrySet()) {
      AccessibilityNodeInfo n = find(root, m.pkg, sel.get(e.getKey()));
      Rect b = new Rect(); if (n != null) n.getBoundsInScreen(b);
      if (!b.equals(e.getValue())) return false;
    }
    return true;
  }

  static int dist(int a, int b) {
    return Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b));
  }

  /** bg = most common of 4 points inside the button's edges; fg = pixel that differs most (the glyph); corner from a diagonal scan. */
  static Style measure(Bitmap bm, Rect b) {
    b = new Rect(b); if (!b.intersect(0, 0, bm.getWidth(), bm.getHeight())) return new Style();
    int w = b.width(), hh = b.height(), cx = b.centerX(), cy = b.centerY();
    int[] pts = {bm.getPixel(b.left + w / 8, cy), bm.getPixel(b.right - 1 - w / 8, cy), bm.getPixel(cx, b.top + hh / 8), bm.getPixel(cx, b.bottom - 1 - hh / 8)};
    Style s = new Style(); int bestN = -1;
    for (int p : pts) { int n = 0; for (int q : pts) if (dist(p, q) < 24) n++; if (n > bestN) { bestN = n; s.bg = p; } }
    int far = 0; s.fg = s.bg;
    for (int y = b.top; y < b.bottom; y += 2) for (int x = b.left; x < b.right; x += 2) {
      int p = bm.getPixel(x, y), d = dist(p, s.bg);
      if (d > far && x > b.left + w / 6 && x < b.right - w / 6 && y > b.top + hh / 6 && y < b.bottom - hh / 6) { far = d; s.fg = p; }
    }
    if (far < 60) s.fg = Color.luminance(s.bg) > 0.5f ? 0xFF1F1F1F : 0xFFFFFFFF;
    int half = Math.min(w, hh) / 2, d = half;
    for (int i = 0; i < half; i++) if (dist(bm.getPixel(b.left + i, b.top + i), s.bg) < 24) { d = i; break; }
    s.round = Math.min(1f, d / (1 - 0.7071f) / half); // corner of radius r first hits the fill at r*(1-1/sqrt2) along the diagonal
    s.bg |= 0xFF000000; s.fg |= 0xFF000000;
    return s;
  }

  float textPx(AccessibilityNodeInfo n) {
    n.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_RENDERING_INFO_KEY, new Bundle());
    AccessibilityNodeInfo.ExtraRenderingInfo ri = n.getExtraRenderingInfo();
    return ri == null ? 0 : Math.max(0, ri.getTextSizeInPx());
  }

  final Map<String, Typeface> fonts = new HashMap<>();
  /** The app's own font if it ships one as a public resource (read-only), else the system sans. */
  Typeface font(String pkg) {
    if (fonts.containsKey(pkg)) return fonts.get(pkg);
    Typeface tf = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    try {
      Resources r = createPackageContext(pkg, 0).getResources();
      for (String f : new String[]{"gm3_variable_label_large", "gm3_variable_title_medium", "gm3_variable_body_large", "google_sans_medium", "google_sans"}) {
        int id = r.getIdentifier(f, "font", pkg);
        try { if (id != 0) { tf = r.getFont(id); Log.i(TAG, "font " + pkg + " " + f); break; } }
        catch (Resources.NotFoundException e) { Log.i(TAG, "font " + f + " is a download-only font"); }
      }
    } catch (Exception e) { Log.i(TAG, "no app font for " + pkg + ": " + e); }
    fonts.put(pkg, tf);
    return tf;
  }
}
