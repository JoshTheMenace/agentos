package local.agentos.watch;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;

/**
 * The round face, A2 Calm Glass on black: a soft glow on the rim in the state color (orange working, blue needs you,
 * green done, red flagged, grey idle), an eyebrow, the title, one sub line, and a mic button. Tap anywhere to talk.
 */
public class Face extends Activity {
  View v;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    startForegroundService(new Intent(this, Link.class));
    if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 2);
    setContentView(v = new Dial(this));
  }
  @Override protected void onResume() { super.onResume(); Link.onChange = () -> v.postInvalidate(); v.invalidate(); }
  @Override protected void onPause() { super.onPause(); Link.onChange = null; }

  void talk() {
    if (!Link.online) return;
    try {
      startActivityForResult(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
          .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask your phone"), 1);
    } catch (ActivityNotFoundException e) { Link.sub = "No voice input on this watch"; v.invalidate(); }
  }
  @Override protected void onActivityResult(int req, int res, Intent data) {
    ArrayList<String> said = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
    if (res == RESULT_OK && said != null && !said.isEmpty()) Link.ask(said.get(0));
  }

  class Dial extends View {
    final float d = getResources().getDisplayMetrics().density;
    final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint eyebrow = text(11, 700, 0x4DEBEBF5, .02f), big = text(17, 600, 0xFFFFFFFF, -.02f), small = text(13, 500, 0x99EBEBF5, -.01f);
    final long t0 = SystemClock.uptimeMillis();

    Dial(Context c) { super(c); setBackgroundColor(0xFF000000); line.setStyle(Paint.Style.STROKE); line.setStrokeCap(Paint.Cap.ROUND); }
    TextPaint text(float sp, int w, int color, float track) {
      TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
      p.setTextSize(sp * getResources().getDisplayMetrics().scaledDensity); p.setColor(color); p.setLetterSpacing(track);
      p.setTypeface(Typeface.create(Typeface.DEFAULT, w, false)); p.setTextAlign(Paint.Align.LEFT);
      return p;
    }

    @Override protected void onDraw(Canvas c) {
      float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, r = Math.min(w, h) / 2;
      int acc = Link.acc;
      boolean live = acc == Link.WORKING || acc == Link.APPROVAL;
      // breathe: 2.4 s, opacity .88 -> 1 (A2 motion); only while the agent is busy, so idle costs no frames
      float k = live ? (float) (.5 - .5 * Math.cos((SystemClock.uptimeMillis() - t0) / 2400.0 * 2 * Math.PI)) : 1;
      int a = (int) (255 * (.88f + .12f * k)), rgb = acc & 0xFFFFFF;
      glow.setShader(new RadialGradient(cx, cy, r, new int[] {0, 0, (0x26 << 24) | rgb, (0x80 << 24) | rgb}, new float[] {0, .7f, .9f, 1}, Shader.TileMode.CLAMP));
      glow.setAlpha(a);
      c.drawCircle(cx, cy, r, glow);
      // orb
      fill.setColor(acc); fill.setAlpha(a);
      c.drawCircle(cx, cy - r * .5f, 5 * d * (1 + .14f * k), fill);
      // eyebrow, title (2 lines), sub (2 lines)
      float tw = r * 1.45f;
      String eb = acc == Link.APPROVAL ? "NEEDS YOU" : acc == Link.DONE ? "DONE" : acc == Link.ALERT ? "HELD" : "AGENT";
      c.drawText(eb, cx - eyebrow.measureText(eb) / 2, cy - r * .32f, eyebrow);
      float y = block(c, Link.title, big, tw, cx, cy - r * .24f, 2);
      block(c, Link.sub, small, tw, cx, y + 4 * d, 2);
      // mic button: a grp2 circle with a drawn mic glyph (blue when you can talk)
      float my = cy + r * .62f, mr = 20 * d;
      fill.setColor(0xFF2C2C2E); c.drawCircle(cx, my, mr, fill);
      line.setColor(Link.online && !Link.turn ? 0xFF0A84FF : 0x4DEBEBF5); line.setStrokeWidth(2 * d);
      c.drawRoundRect(cx - 4 * d, my - 9 * d, cx + 4 * d, my + 3 * d, 4 * d, 4 * d, line);
      c.drawArc(cx - 7 * d, my - 6 * d, cx + 7 * d, my + 6 * d, 20, 140, false, line);
      c.drawLine(cx, my + 6 * d, cx, my + 9 * d, line);
      if (live) postInvalidateDelayed(33);
    }

    /** Centered, word-wrapped text; returns the y below it. */
    float block(Canvas c, String s, TextPaint p, float width, float cx, float top, int lines) {
      if (s == null || s.isEmpty()) return top;
      StaticLayout l = StaticLayout.Builder.obtain(s, 0, s.length(), p, (int) width).setAlignment(Layout.Alignment.ALIGN_CENTER)
          .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build();
      c.save(); c.translate(cx - width / 2, top); l.draw(c); c.restore();
      return top + l.getHeight();
    }

    @Override public boolean onTouchEvent(MotionEvent e) { if (e.getAction() == MotionEvent.ACTION_UP) talk(); return true; }
  }
}
