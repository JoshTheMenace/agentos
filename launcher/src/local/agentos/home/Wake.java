package local.agentos.home;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.json.JSONObject;

/**
 * Wake word: a foreground service that holds the mic only while it's switched on and nothing else needs it.
 * Think of a doorbell: the service listens for one sound ("Jarvis" / "Hey Agent"), rings a soft chime, and opens the home's
 * voice mode (the same voice.start the orb uses). It lets go of the mic while voice mode, a call, another recorder or a dark
 * screen is around, and picks it back up after.
 * Engines (built only when launcher/wake/fetch.sh has run; see launcher/README.md "Wake word"):
 *   porcupine: needs a Picovoice AccessKey in files/porcupine.key (launcher/wake.sh key). Built-in "Jarvis", or files/wake.ppn.
 *   vosk:      key-free fallback, "Hey Agent", behind a flag: files/wake.vosk + the model in files/vosk (launcher/wake.sh vosk).
 */
public class Wake extends Service {
  static final String TAG = "AgentOSWake", CH = "wake", WAKE = "local.agentos.home.WAKE", OFF = "local.agentos.home.WAKE_OFF";
  static final int RATE = 16000;
  /** What a detector does: eat one frame of 16 kHz mono PCM, say whether the wake word ended in it. */
  public interface Engine { int frame(); boolean hit(short[] pcm) throws Exception; void close(); }

  static volatile String state = "off", why = "";   // off | starting | listening | paused | error ; why = voice|call|mic|screen|<error>
  static volatile long voiceUntil;                  // voice mode holds the mic until this (page clears it on close)
  static volatile Runnable listener;                // Home: push state to the page
  static volatile Wake self;
  volatile boolean run = true, dirty = true; long nextCheck; String pause = "";
  final Object lock = new Object();
  AudioManager am; AudioRecord rec; int ours = -1;
  final Handler main = new Handler(Looper.getMainLooper());

  static SharedPreferences prefs(Context c) { return c.getSharedPreferences("wake", 0); }
  static String read(Context c, String f) { try { return new String(Files.readAllBytes(new File(c.getFilesDir(), f).toPath()), StandardCharsets.UTF_8).trim(); } catch (Exception e) { return null; } }
  static boolean has(String cls) { try { Class.forName(cls); return true; } catch (Throwable t) { return false; } }
  /** Which engine would run: porcupine (key), vosk (flag + model), or null. */
  static String engine(Context c) {
    if (has("ai.picovoice.porcupine.PvEngine") && read(c, "porcupine.key") != null) return "porcupine";
    if (has("local.agentos.home.VoskEngine") && new File(c.getFilesDir(), "wake.vosk").exists() && new File(c.getFilesDir(), "vosk/am").isDirectory()) return "vosk";
    return null;
  }
  static String phrase(Context c, String eng) {
    if (!"porcupine".equals(eng)) return "Hey Agent";
    String p = read(c, "wake.phrase"); return p != null ? p : new File(c.getFilesDir(), "wake.ppn").exists() ? "Hey Agent" : "Jarvis";
  }
  static String json(Context c) {
    try { String e = engine(c); return new JSONObject().put("on", prefs(c).getBoolean("on", false)).put("state", e == null ? "nokey" : state)
        .put("why", why).put("engine", e).put("phrase", phrase(c, e)).put("built", has("local.agentos.home.VoskEngine")).toString(); }
    catch (Exception x) { return "{}"; }
  }
  /** The page's toggle. On starts the service (allowed: the home is in front when tapped); off stops it. */
  static void set(Context c, boolean on) {
    prefs(c).edit().putBoolean("on", on).apply();
    if (on && engine(c) != null) c.startForegroundService(new Intent(c, Wake.class)); else c.stopService(new Intent(c, Wake.class));
  }
  /** Voice mode open/closed: hand the mic over (5 min cap in case the page dies mid-session). */
  static void voice(boolean open) { voiceUntil = open ? SystemClock.elapsedRealtime() + 300_000 : 0; Wake w = self; if (w != null) w.poke(); }

  @Override public IBinder onBind(Intent i) { return null; }
  @Override public void onCreate() {
    super.onCreate(); self = this; am = getSystemService(AudioManager.class);
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(new NotificationChannel(CH, "Wake word", NotificationManager.IMPORTANCE_LOW));
    String e = engine(this);
    Notification n = new Notification.Builder(this, CH).setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true)
        .setContentTitle("Listening for “" + phrase(this, e) + "”")
        .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, Home.class), PendingIntent.FLAG_IMMUTABLE))
        .addAction(new Notification.Action.Builder(null, "Turn off", PendingIntent.getService(this, 1, new Intent(this, Wake.class).setAction(OFF), PendingIntent.FLAG_IMMUTABLE)).build())
        .build();
    startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
    IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON); f.addAction(Intent.ACTION_SCREEN_OFF); f.addAction(Intent.ACTION_USER_PRESENT);
    registerReceiver(screen, f);
    am.registerAudioRecordingCallback(recs, main);
    am.addOnModeChangedListener(getMainExecutor(), m -> poke());
    new Thread(this::loop, "wake").start();
  }
  @Override public int onStartCommand(Intent i, int flags, int id) {
    if (i != null && OFF.equals(i.getAction())) { set(this, false); }
    return START_NOT_STICKY; // a restart from the background gets a silent mic on Android 11+; Home.onResume starts it again
  }
  @Override public void onDestroy() {
    run = false; poke(); self = null;
    try { unregisterReceiver(screen); } catch (Exception ignored) {}
    am.unregisterAudioRecordingCallback(recs);
    setState("off", "");
    super.onDestroy();
  }

  final BroadcastReceiver screen = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { poke(); } };
  final AudioManager.AudioRecordingCallback recs = new AudioManager.AudioRecordingCallback() {
    @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> cfgs) { poke(); }
  };
  void poke() { dirty = true; synchronized (lock) { lock.notifyAll(); } }

  /** Why we shouldn't hold the mic right now, or "" to listen. */
  String pauseReason() {
    if (SystemClock.elapsedRealtime() < voiceUntil) return "voice";
    int mode = am.getMode(); if (mode != AudioManager.MODE_NORMAL) return "call";
    PowerManager pm = getSystemService(PowerManager.class); if (!pm.isInteractive()) return "screen";
    if (getSystemService(KeyguardManager.class).isKeyguardLocked()) return "screen";
    for (AudioRecordingConfiguration c : am.getActiveRecordingConfigurations()) // someone else recording (our own WebView voice too)
      if (c.getClientAudioSessionId() != ours && c.getClientAudioSource() != 1999) return "mic"; // 1999 = AudioSource.HOTWORD (hidden): the system "Hey Google" listener
    return "";
  }
  void setState(String s, String w) {
    if (s.equals(state) && w.equals(why)) return;
    state = s; why = w; Log.i(TAG, s + (w.isEmpty() ? "" : " (" + w + ")"));
    Runnable l = listener; if (l != null) l.run();
  }

  void loop() { // an engine that fails to start (e.g. Porcupine's online key check before the network is up) retries every 30 s
    while (run) { listen(); if (run) synchronized (lock) { try { lock.wait(30_000); } catch (InterruptedException e) { return; } } }
  }
  void listen() {
    Engine eng = null;
    try {
      setState("starting", "");
      eng = open();
      short[] buf = new short[eng.frame()];
      while (run) {
        long now = SystemClock.elapsedRealtime(); // re-check on events, and once a second as a backstop (it's a few binder calls)
        if (dirty || now > nextCheck) { dirty = false; nextCheck = now + 1000; pause = pauseReason(); }
        String p = pause;
        if (!p.isEmpty()) { release(); setState("paused", p); synchronized (lock) { if (run) lock.wait(5000); } continue; }
        if (rec == null && !grab()) { setState("paused", "mic"); synchronized (lock) { lock.wait(2000); } continue; }
        setState("listening", "");
        int n = 0; while (n < buf.length && run) { int r = rec.read(buf, n, buf.length - n); if (r <= 0) break; n += r; }
        if (n < buf.length) { release(); continue; }
        if (eng.hit(buf)) wake();
      }
    } catch (Throwable t) { Log.w(TAG, "wake: " + t); setState("error", String.valueOf(t.getMessage())); }
    finally { release(); if (eng != null) eng.close(); }
  }
  Engine open() throws Exception {
    String e = engine(this), dir = getFilesDir().getPath();
    if ("porcupine".equals(e)) {
      File ppn = new File(dir, "wake.ppn");
      String kw = ppn.exists() ? ppn.getPath() : asset("jarvis.ppn");
      return (Engine) Class.forName("ai.picovoice.porcupine.PvEngine").getConstructor(String.class, String.class, String.class)
          .newInstance(read(this, "porcupine.key"), asset("porcupine_params.pv"), kw);
    }
    if ("vosk".equals(e)) return (Engine) Class.forName("local.agentos.home.VoskEngine").getConstructor(String.class).newInstance(dir + "/vosk");
    throw new IllegalStateException("no engine");
  }
  /** assets/wake/<name> -> cache file (Porcupine wants paths). */
  String asset(String name) throws Exception {
    File f = new File(getCacheDir(), name);
    if (!f.exists()) try (InputStream in = getAssets().open("wake/" + name); FileOutputStream o = new FileOutputStream(f)) { byte[] b = new byte[16384]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); }
    return f.getPath();
  }
  boolean grab() {
    try {
      int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
      ours = rec.getAudioSessionId(); rec.startRecording();
      if (rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) return true;
    } catch (Exception e) { Log.w(TAG, "mic: " + e); }
    release(); return false;
  }
  void release() { if (rec != null) { try { rec.stop(); } catch (Exception ignored) {} rec.release(); rec = null; ours = -1; } }

  /** Heard it: let go of the mic, chime, open voice mode on the home screen. */
  void wake() {
    Log.i(TAG, "wake word");
    release(); voice(true); setState("paused", "voice");
    chime();
    if (Home.front) startActivity(new Intent(this, Home.class).setAction(WAKE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    else Bar.open(this, true); // inside another app: the assistant bar opens in place, listening, instead of jumping Home
  }
  /** Two soft sine notes (E6 then A6, 90 ms each, quick fade), at assistant-sonification volume. */
  static void chime() {
    int len = RATE * 9 / 50; short[] pcm = new short[len * 2];
    for (int i = 0; i < pcm.length; i++) {
      int k = i % len; double t = (double) k / RATE, f = i < len ? 1318.5 : 1760.0;
      double env = Math.min(1, k / 160.0) * Math.exp(-t * 18);
      pcm[i] = (short) (Math.sin(2 * Math.PI * f * t) * env * 6000);
    }
    AudioTrack at = new AudioTrack.Builder()
        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(pcm.length * 2).setTransferMode(AudioTrack.MODE_STATIC).build();
    at.write(pcm, 0, pcm.length); at.play();
    new Handler(Looper.getMainLooper()).postDelayed(at::release, 600);
  }
}
