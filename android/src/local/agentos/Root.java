package local.agentos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** Runs one shell command as root (Magisk su). Only the broker's referee jobs use it; models never reach it. */
final class Root {
    private Root() {}

    /** Output (stdout + stderr). Throws if su is missing, denied, times out, or the command exits non-zero. */
    static String run(String cmd) throws IOException {
        Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] b = new byte[16384];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        }
        try {
            if (!p.waitFor(15, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("root command timed out"); }
        } catch (InterruptedException e) { throw new IOException(e); }
        String s = out.toString("UTF-8");
        if (p.exitValue() != 0) throw new IOException("root exit " + p.exitValue() + ": " + s.trim());
        return s;
    }
}
