package local.agentos.appmod;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;

/** adb-only entry point (manifest guards it with DUMP): apply / remove / list. The broker would call the same three methods. */
public class ModCmd extends BroadcastReceiver {
  @Override public void onReceive(Context c, Intent i) {
    ModService s = ModService.self;
    if (s == null) { setResultData("{\"ok\":false,\"error\":\"accessibility service is off\"}"); return; }
    String a = i.getAction();
    if (a.endsWith("APPLY")) {
      String spec = i.getStringExtra("spec");
      if (spec == null && i.getStringExtra("spec64") != null) spec = new String(Base64.decode(i.getStringExtra("spec64"), 0));
      s.apply(spec, goAsync());
    } else if (a.endsWith("REMOVE")) setResultData(s.remove(i.getStringExtra("id")));
    else setResultData(s.list());
  }
}
