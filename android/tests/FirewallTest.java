package local.agentos;

import java.util.Map;

/** Host-only: compile with Firewall.java and android.jar, never with production Root.java. */
public final class FirewallTest {
    public static void main(String[] args) throws Exception {
        if (!Firewall.blockedCounts().isEmpty()) throw new AssertionError("fresh boot has no counters");
        java.lang.reflect.Field cache = Firewall.class.getDeclaredField("cache"); cache.setAccessible(true); cache.set(null, null);
        Root.rules = "-A AGENTOS_FW -p tcp -m owner --uid-owner 10421 -m string --string example.com --algo kmp -j REJECT";
        Root.counts = " 7 700 REJECT tcp -- * * 0.0.0.0/0 0.0.0.0/0 owner UID match 10421 STRING match \"example.com\" ALGO name kmp\n"
                + " 3 300 REJECT tcp -- * * ::/0 ::/0 owner UID match 10421 STRING match \"example.com\" ALGO name kmp\n"
                + " 5 500 REJECT udp -- * * ::/0 ::/0 owner UID match 10421 udp dpt:443";
        Map<String, Long> counts = Firewall.blockedCounts();
        if (counts.size() != 1 || counts.get("10421 example.com") != 10L || Firewall.blockedRequests(counts) != 10)
            throw new AssertionError("sum both families, excluding non-name rules: " + counts);
        Root.counts = null;
        try { Firewall.blockedCounts(); throw new AssertionError("counter errors with active rules must propagate"); }
        catch (java.io.IOException expected) {}
        System.out.println("3 firewall counter cases PASS");
    }
}

final class Root {
    static String rules = "", counts;
    static String run(String cmd) throws java.io.IOException {
        if (cmd.contains(" -S AGENTOS_FW 2>/dev/null; true")) return rules;
        if (counts != null && cmd.contains(" -L AGENTOS_FW -v -n -x")) return counts;
        throw new java.io.IOException("root exit 1: ip6tables: No chain/target/match by that name.");
    }
}
