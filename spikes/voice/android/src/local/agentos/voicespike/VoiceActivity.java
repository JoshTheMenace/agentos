package local.agentos.voicespike;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import java.util.Arrays;

// Bare WebView host for the voice page. The only interesting part is the mic hand-off:
// page calls getUserMedia -> WebView asks us (onPermissionRequest) -> we need RECORD_AUDIO too.
public class VoiceActivity extends Activity {
  static final String TAG = "AgentOSVoice";
  PermissionRequest pending;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    WebView.setWebContentsDebuggingEnabled(true); // chrome://inspect from the Mac
    WebView web = new WebView(this);
    web.getSettings().setJavaScriptEnabled(true);
    web.getSettings().setMediaPlaybackRequiresUserGesture(false); // let the reply audio autoplay
    web.setWebChromeClient(new WebChromeClient() {
      @Override public void onPermissionRequest(PermissionRequest r) {
        boolean micOnly = Arrays.equals(r.getResources(), new String[] {PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        boolean local = "127.0.0.1".equals(r.getOrigin().getHost()); // only our own page may use the mic
        Log.i(TAG, "permission request " + Arrays.toString(r.getResources()) + " from " + r.getOrigin());
        if (!micOnly || !local) { r.deny(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
          r.grant(r.getResources());
        } else { pending = r; requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 1); }
      }
      @Override public boolean onConsoleMessage(ConsoleMessage m) { Log.i(TAG, "console: " + m.message()); return true; }
    });
    setContentView(web);
    String url = getIntent().getDataString();
    web.loadUrl(url != null ? url : "http://127.0.0.1:8790/");
  }

  @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
    if (pending == null) return;
    if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) pending.grant(pending.getResources());
    else pending.deny();
    Log.i(TAG, "RECORD_AUDIO " + (res.length > 0 && res[0] == 0 ? "granted" : "denied"));
    pending = null;
  }
}
