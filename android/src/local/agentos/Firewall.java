package local.agentos;

import android.content.Context;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Per-app network rules with root iptables/ip6tables on our own chain AGENTOS_FW (jumped to from OUTPUT).
 * Everything we add lives in that one chain, so clear() (flush it) undoes it all. Loopback is never filtered,
 * so agentd keeps reaching us. Domain rules resolve names to IPs once, at set time.
 */
final class Firewall {
    static final String CHAIN = "AGENTOS_FW";
    private static final String[] IPT = {"iptables", "ip6tables"};
    private static final Pattern RULE = Pattern.compile("-A " + CHAIN + "(?: -d (\\S+))? -m owner --uid-owner (\\d+) -j (\\w+)");

    private Firewall() {}

    static void ensure() throws Exception {
        for (String t : IPT)
            Root.run(t + " -w -N " + CHAIN + " 2>/dev/null; " + t + " -w -C OUTPUT -j " + CHAIN + " 2>/dev/null || " + t + " -w -I OUTPUT 1 -j " + CHAIN
                    + "; " + t + " -w -C " + CHAIN + " -o lo -j RETURN 2>/dev/null || " + t + " -w -I " + CHAIN + " 1 -o lo -j RETURN");
    }

    static JSONObject list(Context c) throws Exception {
        ensure();
        JSONArray rules = new JSONArray();
        for (String t : IPT)
            for (String line : Root.run(t + " -w -S " + CHAIN).split("\n")) {
                Matcher m = RULE.matcher(line.trim());
                if (!m.find()) continue;
                int uid = Integer.parseInt(m.group(2));
                rules.put(new JSONObject().put("pkg", String.valueOf(c.getPackageManager().getNameForUid(uid))).put("uid", uid)
                        .put("family", t.equals("iptables") ? "ipv4" : "ipv6").put("dest", m.group(1) == null ? "any" : m.group(1))
                        .put("action", m.group(3)));
            }
        return new JSONObject().put("ok", true).put("chain", CHAIN).put("rules", rules);
    }

    /**
     * block, no domains: block all traffic. block + domains: block only those. allow, no domains: remove our rules
     * (back to normal). allow + domains: allow only those, block the rest.
     */
    static String set(Context c, String pkg, String mode, JSONArray domains) throws Exception {
        int uid = c.getPackageManager().getPackageUid(pkg, 0);
        if (!mode.equals("allow") && !mode.equals("block")) throw new IllegalArgumentException("mode must be allow or block");
        ensure();
        StringBuilder sh = new StringBuilder();
        for (String t : IPT) // drop this app's old rules first
            sh.append(t).append(" -w -S ").append(CHAIN).append(" | grep -- '--uid-owner ").append(uid)
              .append(" ' | sed 's/^-A/-D/' | while read r; do ").append(t).append(" -w $r; done; ");
        String owner = " -m owner --uid-owner " + uid;
        int n = domains == null ? 0 : domains.length();
        for (int i = 0; i < n; i++)
            for (InetAddress a : InetAddress.getAllByName(domains.getString(i))) {
                String t = a instanceof Inet6Address ? "ip6tables" : "iptables";
                sh.append(t).append(" -w -A ").append(CHAIN).append(" -d ").append(a.getHostAddress()).append(owner)
                  .append(mode.equals("allow") ? " -j ACCEPT; " : " -j REJECT; ");
            }
        if (mode.equals("block") && n == 0 || mode.equals("allow") && n > 0)
            for (String t : IPT) sh.append(t).append(" -w -A ").append(CHAIN).append(owner).append(" -j REJECT; ");
        Root.run(sh.append("true").toString());
        return pkg + " (uid " + uid + "): " + mode + (n > 0 ? " " + domains.join(",") : " all");
    }

    /** Undo everything AgentOS added (the OUTPUT jump stays, pointing at an empty chain). */
    static void clear() throws Exception {
        ensure();
        for (String t : IPT) Root.run(t + " -w -F " + CHAIN + " && " + t + " -w -I " + CHAIN + " 1 -o lo -j RETURN");
    }
}
