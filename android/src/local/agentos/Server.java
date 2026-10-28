package local.agentos;

import android.content.Context;
import android.util.Log;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Tool server on 127.0.0.1:7777, "Authorization: Bearer <files/token>" on every request. Loopback only.
 *  POST /rpc {method, params}: replies with the method's result object (always has "ok"); 64 KB body cap.
 *  GET /events: server-sent events (see Events).
 */
final class Server {
    static final int PORT = 7777;
    private final Broker broker;
    private final byte[] expected;
    private final ExecutorService pool = Executors.newFixedThreadPool(4 + Events.MAX_LISTENERS); // /events listeners hold a thread each
    private volatile ServerSocket ss;

    Server(Context c, Broker broker) throws IOException {
        this.broker = broker;
        File f = new File(c.getFilesDir(), "token");
        if (!f.exists()) Files.write(f.toPath(), Approvals.hex(32).getBytes(StandardCharsets.UTF_8));
        expected = ("Bearer " + new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim()).getBytes(StandardCharsets.UTF_8);
    }

    void start() {
        new Thread(() -> {
            try {
                ss = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1")); // not getLoopbackAddress(): that is ::1 on Android
                while (!ss.isClosed()) {
                    Socket s = ss.accept();
                    pool.execute(() -> handle(s));
                }
            } catch (IOException e) { Log.w("AgentOS", "server stopped: " + e); }
        }, "agentos-rpc").start();
    }

    void stop() {
        try { if (ss != null) ss.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
    }

    private void handle(Socket c) {
        try (Socket s = c) {
            s.setSoTimeout(10_000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            String first = in.readLine(), auth = "";
            int len = 0, headerBytes = 0;
            for (String l; (l = in.readLine()) != null && !l.isEmpty(); ) {
                if ((headerBytes += l.length()) > 8192) return;
                String low = l.toLowerCase();
                if (low.startsWith("content-length:")) len = Integer.parseInt(l.substring(15).trim());
                if (low.startsWith("authorization:")) auth = l.substring(14).trim();
            }
            boolean events = first != null && first.startsWith("GET /events ");
            if (!events && (first == null || !first.startsWith("POST /rpc "))) { reply(s, "404 Not Found", err("POST /rpc or GET /events only")); return; }
            if (!MessageDigest.isEqual(expected, auth.getBytes(StandardCharsets.UTF_8))) { reply(s, "401 Unauthorized", err("bearer token required")); return; }
            if (events) { s.setSoTimeout(0); Events.stream(s.getOutputStream(), broker.approvals()); return; }
            if (len <= 0 || len > 65536) { reply(s, "400 Bad Request", err("body must be 1..65536 bytes")); return; }
            byte[] body = new byte[len];
            in.readFully(body);
            JSONObject res;
            try {
                JSONObject req = new JSONObject(new String(body, StandardCharsets.UTF_8));
                JSONObject params = req.optJSONObject("params");
                String m = req.getString("method");
                JSONObject p = params == null ? new JSONObject() : params;
                int uid = java.util.Arrays.asList("task.start", "approval.confirm", "approval.escalate", "vault.add", "vault.remove", "audit.mark_breaks", "audit.demo_reset", "trackers.block", "trackers.unblock", "schedule.alarm", "plugins.set", "remote.read", "image.sent").contains(m)
                        || Plugins.off(m, p) != null ? callerUid(s) : -1; // only these care who asks (a switched-off plugin: is it the owner's own screen?)
                res = broker.call(m, p, uid);
            } catch (Exception e) {
                Log.w("AgentOS", "rpc error", e);
                res = err(e.getMessage() == null ? e.toString() : e.getMessage());
            }
            reply(s, "200 OK", res);
        } catch (Exception e) { Log.w("AgentOS", "request failed: " + e); }
    }

    /**
     * Which app is on the other end? Root reads the kernel's socket table (/proc/net/tcp*): the caller's row has its
     * port as the local port, ours (7777 = 1E61) as the remote one, and its uid in column 8. -1 if unknown (no root).
     */
    static int callerUid(Socket s) {
        String mine = String.format(":%04X", s.getPort()), ours = String.format(":%04X", PORT);
        try {
            for (String line : Root.run("cat /proc/net/tcp /proc/net/tcp6; true").split("\n")) {
                String[] f = line.trim().split("\\s+");
                if (f.length > 7 && f[1].endsWith(mine) && f[2].endsWith(ours)) return Integer.parseInt(f[7]);
            }
        } catch (Exception e) { Log.w("AgentOS", "caller lookup failed: " + e); }
        return -1;
    }

    private static JSONObject err(String msg) {
        try { return new JSONObject().put("ok", false).put("error", msg); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static void reply(Socket s, String status, JSONObject res) throws IOException {
        byte[] b = res.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream o = s.getOutputStream();
        o.write(("HTTP/1.1 " + status + "\r\nContent-Type: application/json\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        o.write(b);
        o.flush();
    }
}
