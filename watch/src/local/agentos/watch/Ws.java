package local.agentos.watch;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The smallest WebSocket client that talks to agentd (RFC 6455: text frames, ping/pong, close). Android has no built-in
 * one and we build without Gradle, so no OkHttp. Like a walkie-talkie: one handshake, then short framed messages both ways.
 */
final class Ws {
  interface On { void text(String s); }
  private final Socket sock = new Socket();
  private final OutputStream out;
  private final DataInputStream in;
  private static final SecureRandom rnd = new SecureRandom();

  Ws(String host, int port, String token) throws IOException {
    sock.connect(new InetSocketAddress(host, port), 4000);
    sock.setSoTimeout(45_000); // agentd is quiet between tasks; we ping every 20 s, so 45 s of silence = dead link
    out = sock.getOutputStream();
    in = new DataInputStream(sock.getInputStream());
    byte[] key = new byte[16]; rnd.nextBytes(key);
    out.write(("GET / HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n"
        + "Sec-WebSocket-Key: " + Base64.getEncoder().encodeToString(key) + "\r\nAuthorization: Bearer " + token + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    StringBuilder head = new StringBuilder();
    while (!head.toString().endsWith("\r\n\r\n")) { int c = in.read(); if (c < 0) throw new IOException("closed during handshake"); head.append((char) c); }
    if (!head.toString().startsWith("HTTP/1.1 101")) throw new IOException(head.toString().split("\r\n")[0]);
  }

  /** Reads frames until the socket closes. Blocks the calling thread. */
  void loop(On on) throws IOException {
    ByteArrayOutputStream msg = new ByteArrayOutputStream();
    while (true) {
      int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte(), op = b0 & 0x0f;
      long len = b1 & 0x7f;
      if (len == 126) len = in.readUnsignedShort(); else if (len == 127) len = in.readLong();
      if (len > 1 << 20) throw new IOException("frame too big");
      byte[] p = new byte[(int) len]; in.readFully(p); // server frames are never masked
      if (op == 8) throw new IOException("closed by agentd");
      if (op == 9) { frame(10, p); continue; }
      if (op == 10) continue;
      msg.write(p);
      if ((b0 & 0x80) != 0) { on.text(msg.toString("UTF-8")); msg.reset(); }
    }
  }

  void send(String s) throws IOException { frame(1, s.getBytes(StandardCharsets.UTF_8)); }
  void ping() throws IOException { frame(9, new byte[0]); }

  private synchronized void frame(int op, byte[] p) throws IOException { // client frames must be masked
    ByteArrayOutputStream f = new ByteArrayOutputStream();
    f.write(0x80 | op);
    if (p.length < 126) f.write(0x80 | p.length);
    else { f.write(0x80 | 126); f.write(p.length >> 8); f.write(p.length & 0xff); } // our messages stay < 64 KB
    byte[] m = new byte[4]; rnd.nextBytes(m); f.write(m);
    for (int i = 0; i < p.length; i++) f.write(p[i] ^ m[i & 3]);
    out.write(f.toByteArray()); out.flush();
  }

  void close() { try { sock.close(); } catch (IOException ignored) {} }
}
