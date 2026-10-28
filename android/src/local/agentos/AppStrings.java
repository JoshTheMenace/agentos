package local.agentos;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The strings an app ships inside its own APK (the global string pool of resources.arsc, every language). Prompt Guard
 * uses it to tell the app's printed signs (Clock's "Alarm volume is low and you might not hear it") from sticky notes
 * other people put on them (a message, a web paragraph): a third party can't add strings to someone else's APK.
 * Kept as sorted 64-bit hashes (Settings ships ~485k strings = 4 MB), cached per app version, parsed once (~0.1–0.5 s).
 */
final class AppStrings {
    private static Context ctx;
    private static final Map<String, long[]> cache = new LinkedHashMap<String, long[]>(8, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, long[]> e) { return size() > 6; }
    };

    static void init(Context c) { ctx = c; }

    /** True when the text, or each of its pieces, ships in pkg's APK. Pieces are sentences/lines ("Having trouble?" +
     *  "Reset your password") or strings the app glued with 2+ spaces (Maps: "…always obey the law" + "  " + "Google Maps Terms"). */
    static boolean own(String pkg, String text) {
        long[] h = hashes(pkg);
        if (h == null) return false;
        if (Arrays.binarySearch(h, hash(text.trim())) >= 0) return true;
        for (String s : text.split("(?<=[.?!:])\\s+|\\n|\\s{2,}"))
            if (!s.trim().isEmpty() && Arrays.binarySearch(h, hash(s.trim())) < 0) return false;
        return true;
    }

    private static synchronized long[] hashes(String pkg) {
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            String key = pkg + ":" + new java.io.File(ai.sourceDir).lastModified();
            long[] h = cache.get(key);
            if (h != null) return h;
            long t0 = android.os.SystemClock.uptimeMillis(), all[] = new long[0];
            for (String apk : concat(ai.sourceDir, ai.splitSourceDirs)) all = concat(all, pool(apk));
            Arrays.sort(all);
            cache.put(key, all);
            android.util.Log.i("AgentOS", "app strings " + pkg + ": " + all.length + " in " + (android.os.SystemClock.uptimeMillis() - t0) + " ms");
            return all;
        } catch (Exception e) { return null; } // unknown app or odd APK: nothing counts as its own text
    }

    /** Hashes of resources.arsc's global string pool (ResTable header, then ResStringPool: UTF-8 or UTF-16). */
    private static long[] pool(String apk) throws Exception {
        try (ZipFile z = new ZipFile(apk)) {
            ZipEntry e = z.getEntry("resources.arsc");
            if (e == null) return new long[0];
            byte[] head = new byte[12 + 28];
            try (InputStream in = z.getInputStream(e)) {
                DataInputStream d = new DataInputStream(in);
                d.readFully(head);
                ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
                if (b.getShort(12) != 1) return new long[0];
                byte[] p = new byte[b.getInt(16)];
                System.arraycopy(head, 12, p, 0, 28);
                d.readFully(p, 28, p.length - 28);
                b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
                int n = b.getInt(8), start = b.getInt(20), hs = b.getShort(2);
                boolean utf8 = (b.getInt(16) & 0x100) != 0;
                long[] out = new long[n];
                for (int i = 0; i < n; i++) {
                    int o = start + b.getInt(hs + 4 * i);
                    String s;
                    if (utf8) {
                        o += (p[o] & 0x80) != 0 ? 2 : 1; // UTF-16 length, unused
                        int l = p[o] & 0xff;
                        if ((l & 0x80) != 0) l = ((l & 0x7f) << 8) | (p[++o] & 0xff);
                        s = new String(p, o + 1, l, StandardCharsets.UTF_8);
                    } else {
                        int l = b.getShort(o) & 0xffff;
                        if ((l & 0x8000) != 0) { l = ((l & 0x7fff) << 16) | (b.getShort(o + 2) & 0xffff); o += 2; }
                        s = new String(p, o + 2, 2 * l, StandardCharsets.UTF_16LE);
                    }
                    out[i] = hash(s.trim());
                }
                return out;
            }
        }
    }

    /** Two independent 32-bit string hashes side by side. A forged match still faces Prompt Guard's whole-screen score. */
    private static long hash(String s) {
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) h = (h ^ s.charAt(i)) * 0x01000193;
        return ((long) s.hashCode() << 32) | (h & 0xffffffffL);
    }

    private static String[] concat(String a, String[] b) {
        String[] r = new String[1 + (b == null ? 0 : b.length)];
        r[0] = a;
        if (b != null) System.arraycopy(b, 0, r, 1, b.length);
        return r;
    }

    private static long[] concat(long[] a, long[] b) {
        long[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
