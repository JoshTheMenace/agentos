package local.agentos;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import local.agentos.deep.Contract;
import org.json.JSONObject;

/** Narrow authenticated callback; never exposes the broker RPC/token to Pigeon. */
public final class DeepModService extends Service {
    @Override public IBinder onBind(Intent i) { return new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                Contract.peer(DeepModService.this, Binder.getCallingUid(), Contract.PKG, Contract.PIGEON_CERT);
                Contract.need(code == 1, "unsupported transaction"); data.enforceInterface(Contract.WIRE);
                JSONObject p = Contract.wire(data.readString());
                DeepMods m = DeepMods.instance; Contract.need(m != null, "broker unavailable");
                JSONObject result = m.callback(p); reply.writeNoException(); reply.writeString(result.toString());
            } catch (Exception e) { reply.writeException(new SecurityException(e.getMessage())); }
            return true;
        }
    }; }
}
