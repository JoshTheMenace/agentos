package local.agentos.pigeon;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import local.agentos.deep.Contract;
import org.json.JSONObject;

/** Only the certificate-pinned AgentOS broker can reach the cooperative renderer. */
public final class DeepModService extends Service {
    @Override public IBinder onBind(Intent i) { return new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                Contract.peer(DeepModService.this, Binder.getCallingUid(), Contract.BROKER, Contract.BROKER_CERT);
                Contract.need(code == 1, "unsupported transaction"); data.enforceInterface(Contract.WIRE);
                JSONObject p = Contract.wire(data.readString());
                DeepRuntime runtime = DeepRuntime.current; Contract.need(runtime != null, "open Pigeon first");
                JSONObject result = runtime.call(p); reply.writeNoException(); reply.writeString(result.toString());
            } catch (Exception e) { reply.writeException(new SecurityException(e.getMessage())); }
            return true;
        }
    }; }
}
