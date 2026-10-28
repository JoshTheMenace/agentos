package local.agentos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs one shell command as root (Magisk su). Only the broker's referee jobs use it; models never reach it. */
final class Root {
    private Root() {}

    /** Output (stdout + stderr). Throws if su is missing, denied, times out, or the command exits non-zero. */
    static String run(String cmd) throws IOException { return su(cmd, "-c"); }

    /** Same, in the global mount namespace ("-mm"): our own namespace hides other apps' data dirs (Api: Clock's alarms). */
    static String global(String cmd) throws IOException { return su(cmd, "-mm", "-c"); }

    private static String su(String cmd, String... flags) throws IOException {
        List<String> argv = new ArrayList<>(Arrays.asList(flags));
        argv.add(0, "su"); argv.add(cmd);
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
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
