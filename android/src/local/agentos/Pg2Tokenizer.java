package local.agentos;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Prompt Guard 2's tokenizer (SentencePiece Unigram from tokenizer.json), ported to plain Java (research/04 3a).
 * Meta's normalizer deletes all whitespace, so "i g n o r e" and "ignore" give the same tokens. Steps:
 * NFKC, delete whitespace/control/zero-width chars, prepend "▁", then Viterbi over the piece scores (best total
 * log-probability; an unknown char becomes [UNK], and consecutive [UNK]s merge). Matches HF `tokenizers` on our golden set.
 */
final class Pg2Tokenizer {
    final int cls, sep, unk;
    private final HashMap<String, Integer> ids = new HashMap<>();
    private final List<Double> scores = new ArrayList<>();
    private final double unkScore;
    private int maxLen;

    Pg2Tokenizer(File tokenizerJson) throws IOException {
        String s = new String(Files.readAllBytes(tokenizerJson.toPath()), StandardCharsets.UTF_8);
        int i = s.indexOf('[', s.indexOf("\"vocab\"", s.indexOf("\"model\""))) + 1;
        double min = 0;
        for (;;) { // the vocab is [["piece", score], ...]: a tiny scanner instead of parsing 8.6 MB of JSON into objects
            while (s.charAt(i) != '[' && s.charAt(i) != ']') i++;
            if (s.charAt(i) == ']') break;
            StringBuilder b = new StringBuilder();
            i = s.indexOf('"', i) + 1;
            for (char c; (c = s.charAt(i++)) != '"'; ) {
                if (c == '\\') { // JSON escapes
                    c = s.charAt(i++);
                    if (c == 'u') { c = (char) Integer.parseInt(s.substring(i, i + 4), 16); i += 4; }
                    else if ("ntrbf".indexOf(c) >= 0) c = "\n\t\r\b\f".charAt("ntrbf".indexOf(c));
                }
                b.append(c);
            }
            int end = s.indexOf(']', i);
            double score = Double.parseDouble(s.substring(s.indexOf(',', i) + 1, end).trim());
            ids.put(b.toString(), scores.size());
            scores.add(score);
            min = Math.min(min, score);
            maxLen = Math.max(maxLen, b.length());
            i = end + 1;
        }
        unkScore = min - 10; // HF Unigram's unknown-char penalty
        cls = ids.get("[CLS]"); sep = ids.get("[SEP]"); unk = ids.get("[UNK]");
    }

    /** Token ids without [CLS]/[SEP]. */
    int[] encode(String text) {
        StringBuilder b = new StringBuilder("▁");
        Normalizer.normalize(text, Normalizer.Form.NFKC).codePoints().filter(c -> !Character.isWhitespace(c)
                && !Character.isSpaceChar(c) && (c == 0 || Character.getType(c) != Character.CONTROL) && (c < 0x200B || c > 0x200F)
                && c != 0xFEFF && c != 0x2581 && c != 0xFFFD).forEach(b::appendCodePoint);
        String t = b.toString();
        int n = t.length();
        if (n == 1) return new int[0];
        double[] best = new double[n + 1];
        int[] from = new int[n + 1], tok = new int[n + 1];
        java.util.Arrays.fill(from, -1);
        from[0] = 0;
        for (int st = 0; st < n; st = t.offsetByCodePoints(st, 1)) {
            if (from[st] < 0) continue;
            int one = t.offsetByCodePoints(st, 1);
            boolean single = false;
            for (int e = st + 1; e <= Math.min(n, st + maxLen); e++) {
                Integer id = ids.get(t.substring(st, e));
                if (id == null) continue;
                single |= e == one;
                double c = best[st] + scores.get(id);
                if (from[e] < 0 || c > best[e]) { best[e] = c; from[e] = st; tok[e] = id; }
            }
            if (!single && (from[one] < 0 || best[st] + unkScore > best[one])) { best[one] = best[st] + unkScore; from[one] = st; tok[one] = unk; }
        }
        ArrayList<Integer> out = new ArrayList<>();
        for (int e = n; e > 0; e = from[e]) if (tok[e] != unk || out.isEmpty() || out.get(out.size() - 1) != unk) out.add(tok[e]);
        int[] r = new int[out.size()];
        for (int k = 0; k < r.length; k++) r[k] = out.get(r.length - 1 - k);
        return r;
    }
}
