package local.agentos;

import android.content.Context;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Per-app network rules with root iptables/ip6tables on our own chain AGENTOS_FW (jumped to from OUTPUT).
 * Everything we add lives in that one chain, so clear() (flush it) undoes it all. Loopback is never filtered,
 * so agentd keeps reaching us. Block rules for domains match the name itself (names()), so they hold when IPs rotate;
 * allow-only-these-domains rules still resolve names to IPs once, at set time.
 */
final class Firewall {
    static final String CHAIN = "AGENTOS_FW";
    private static final String[] IPT = {"iptables", "ip6tables"};
    private static final Pattern RULE = Pattern.compile("-A " + CHAIN + "(?: -d (\\S+))? -m owner --uid-owner (\\d+) -j (\\w+)"),
            NAME = Pattern.compile("-A " + CHAIN + " -p tcp -m owner --uid-owner (\\d+) -m string --string \"?([a-z0-9.-]+)\"?"),
            HOST = Pattern.compile("[a-z0-9-]+(\\.[a-z0-9-]+)+");
    /** nameRules() as last read; every change below clears it, so reads skip the slow su round trip. */
    private static volatile List<String[]> cache;

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
                Matcher m = RULE.matcher(line.trim()), n = NAME.matcher(line.trim());
                boolean name = n.find();
                if (!name && !m.find()) continue;
                int uid = Integer.parseInt(name ? n.group(1) : m.group(2));
                rules.put(new JSONObject().put("pkg", String.valueOf(c.getPackageManager().getNameForUid(uid))).put("uid", uid)
                        .put("family", t.equals("iptables") ? "ipv4" : "ipv6").put("dest", name ? "name:" + n.group(2) : m.group(1) == null ? "any" : m.group(1))
                        .put("action", name ? "REJECT" : m.group(3)));
            }
        return new JSONObject().put("ok", true).put("chain", CHAIN).put("rules", rules);
    }

    /** Connections our name blocks have refused so far (iptables packet counters on the tracker REJECT rules; reset at reboot). */
    static long blockedRequests() throws java.io.IOException {
        long n = 0;
        for (String t : IPT)
            for (String line : Root.run(t + " -w -L " + CHAIN + " -v -n -x").split("\n"))
                if (line.contains("STRING match") && line.contains("REJECT")) try { n += Long.parseLong(line.trim().split("\\s+")[0]); } catch (NumberFormatException ignored) {}
        return n;
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
              .append(" ' | sed 's/^-A/-D/; s/\"//g' | while read r; do ").append(t).append(" -w $r; done; ");
        String owner = " -m owner --uid-owner " + uid;
        int n = domains == null ? 0 : domains.length();
        if (mode.equals("block") && n > 0) { // by name, so the block holds when the IPs rotate
            Root.run(sh.append("true").toString());
            cache = null;
            List<String> names = new ArrayList<>();
            for (int i = 0; i < n; i++) names.add(domains.getString(i).toLowerCase());
            names(uid, names, true);
            return pkg + " (uid " + uid + "): block " + String.join(",", names);
        }
        for (int i = 0; i < n; i++)
            for (InetAddress a : InetAddress.getAllByName(domains.getString(i))) {
                String t = a instanceof Inet6Address ? "ip6tables" : "iptables";
                sh.append(t).append(" -w -A ").append(CHAIN).append(" -d ").append(a.getHostAddress()).append(owner)
                  .append(mode.equals("allow") ? " -j ACCEPT; " : " -j REJECT; ");
            }
        if (mode.equals("block") && n == 0 || mode.equals("allow") && n > 0)
            for (String t : IPT) sh.append(t).append(" -w -A ").append(CHAIN).append(owner).append(" -j REJECT; ");
        Root.run(sh.append("true").toString());
        cache = null;
        return pkg + " (uid " + uid + "): " + mode + (n > 0 ? " " + domains.join(",") : " all");
    }

    /**
     * Block (or unblock) domains for one app by name: REJECT its TCP packets whose TLS ClientHello (SNI) or HTTP Host
     * header contains the name (subdomains included), so the block holds however the name's IPs rotate. While the app has
     * any name rule, its QUIC (UDP 443) is rejected too: it falls back to TCP, where the name is visible. Names must
     * match HOST before they reach the shell, so the root command stays a fixed template.
     */
    static void names(int uid, Collection<String> names, boolean block) throws Exception {
        ensure();
        String owner = " -m owner --uid-owner " + uid;
        Set<String> left = new HashSet<>();
        for (String[] r : nameRules()) if (r[0].equals(String.valueOf(uid))) left.add(r[1]);
        StringBuilder sh = new StringBuilder();
        for (String name : names) {
            if (!HOST.matcher(name).matches()) throw new IllegalArgumentException("not a domain: " + name);
            if (block) left.add(name); else left.remove(name);
            for (String t : IPT) {
                String r = " " + CHAIN + " -p tcp" + owner + " -m string --string " + name + " --algo kmp -j REJECT --reject-with tcp-reset";
                sh.append(t).append(" -w -D").append(r).append(" 2>/dev/null; ");
                if (block) sh.append(t).append(" -w -A").append(r).append("; ");
            }
        }
        for (String t : IPT) {
            String q = " " + CHAIN + " -p udp" + owner + " --dport 443 -j REJECT";
            sh.append(t).append(" -w -D").append(q).append(" 2>/dev/null; ");
            if (!left.isEmpty()) sh.append(t).append(" -w -A").append(q).append("; ");
        }
        Root.run(sh.append("true").toString());
        cache = null;
    }

    /** Every name rule as {uid, name} (from the IPv4 table; names() always writes both). */
    static List<String[]> nameRules() throws Exception {
        if (cache != null) return cache;
        List<String[]> out = new ArrayList<>();
        for (String line : Root.run("iptables -w -S " + CHAIN + " 2>/dev/null; true").split("\n")) {
            Matcher m = NAME.matcher(line.trim());
            if (m.find()) out.add(new String[]{m.group(1), m.group(2)});
        }
        return cache = out;
    }

    /** Undo everything AgentOS added (the OUTPUT jump stays, pointing at an empty chain). */
    static void clear() throws Exception {
        ensure();
        for (String t : IPT) Root.run(t + " -w -F " + CHAIN + " && " + t + " -w -I " + CHAIN + " 1 -o lo -j RETURN");
        cache = null;
    }
}
