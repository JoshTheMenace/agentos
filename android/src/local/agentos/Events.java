package local.agentos;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/**
 * GET /events: a server-sent-events feed so agentd and the launcher can mirror the broker live.
 * Like a radio: the broker broadcasts and anyone tuned in hears it. Each listener has its own mailbox, so a slow
 * listener never holds up the broker; a mailbox that overflows is dropped (the client reconnects and calls approval.list).
 * Events: approval.shown, approval.resolved, guard.flag, task.state, firewall.changed, audit.append (+ hello on connect).
 */
final class Events {
    static final int MAX_LISTENERS = 6;
    private static final Set<BlockingQueue<String>> listeners = ConcurrentHashMap.newKeySet();
    private static final AtomicLong seq = new AtomicLong();

    private Events() {}

    static void emit(String type, JSONObject data) {
        if (listeners.isEmpty()) return;
        String msg;
        try { msg = "id: " + seq.incrementAndGet() + "\nevent: " + type + "\ndata: " + new JSONObject(data.toString()).put("type", type) + "\n\n"; }
        catch (Exception e) { return; }
        for (BlockingQueue<String> q : listeners) if (!q.offer(msg)) listeners.remove(q);
    }

    /** Streams to o until the client goes away (blocks this thread). hello = the current state, sent first. */
    static void stream(OutputStream o, JSONObject hello) throws Exception {
        if (listeners.size() >= MAX_LISTENERS) throw new IllegalStateException("too many /events listeners");
        BlockingQueue<String> q = new ArrayBlockingQueue<>(256);
        listeners.add(q);
        try {
            o.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
                    + "event: hello\ndata: " + hello + "\n\n").getBytes(StandardCharsets.UTF_8));
            o.flush();
            while (listeners.contains(q)) {
                String m = q.poll(15, TimeUnit.SECONDS);
                o.write((m == null ? ": ping\n\n" : m).getBytes(StandardCharsets.UTF_8)); // the ping notices a vanished client
                o.flush();
            }
        } finally { listeners.remove(q); }
    }
}
