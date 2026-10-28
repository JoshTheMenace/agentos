package local.agentos;

import java.util.Collections;
import org.json.JSONObject;

/** Uses the broker's production policy, without instantiating a service or dispatching an approval. */
public final class PolicyTest {
    static int cases;
    static void check(Broker.Lease lease, JSONObject p, boolean passive, boolean caller, boolean ordinary) {
        if (Broker.modChannels(lease, p, "local.agentos.pigeon", passive, caller).contains("reviewer") != ordinary) throw new AssertionError("policy case " + cases);
        cases++;
    }
    public static void main(String[] args) throws Exception {
        Broker.History h = new Broker.History();
        Broker.Lease l = new Broker.Lease("Theme Pigeon", Collections.singleton("local.agentos.pigeon"), System.currentTimeMillis() + 60000, 40, "test", h);
        l.asked = true; l.granted = true; l.origin = "typed";
        JSONObject p = new JSONObject().put("task_id", l.id);
        check(l,p,true,true,true);
        l.origin="voice"; check(l,p,true,true,true);
        l.origin="remote"; check(l,p,true,true,false); l.origin="typed";
        h.untrusted=true; check(l,p,true,true,true); // observations cannot authorize, but do not taint an owner theme request
        h.flagged=true; check(l,p,true,true,false); h.flagged=false;
        h.copied=true; check(l,p,true,true,false); h.copied=false;
        p.put("tainted",true); check(l,p,true,true,false); p.remove("tainted");
        l.paused=true; check(l,p,true,true,false); l.paused=false;
        l.granted=false; check(l,p,true,true,false); l.granted=true;
        l.asked=false; check(l,p,true,true,false); l.asked=true;
        check(l,p,false,true,false); // inserted controls always HUMAN at commit
        check(l,p,true,false,false);
        check(l,new JSONObject(),true,true,false);
        check(null,p,true,true,false);
        Broker.Lease expired = new Broker.Lease("Theme Pigeon", l.apps, 0, 40, "test", h);
        expired.asked=true; expired.granted=true; expired.origin="typed";
        check(expired,new JSONObject().put("task_id",expired.id),true,true,false);
        System.out.println(cases + " broker policy cases PASS; no approvals dispatched");
    }
}
