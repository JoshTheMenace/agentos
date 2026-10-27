package local.agentos;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import org.json.JSONObject;

/**
 * Fingerprint-signed approvals. Like a cheque: the fingerprint signs one exact payee and amount, never a blank.
 *  1. Broker builds the canonical action JSON (fixed key order) and a Pending.
 *  2. The user taps Approve on the card; ApproveActivity asks for a fingerprint, which unlocks a Keystore EC key
 *     (auth required on every use) and signs SHA-256(canonical) with ECDSA ("SHA256withECDSA").
 *  3. Broker verifies that signature against files/approval.pub before it acts. No valid signature, no action.
 */
final class Approvals {
    static final String ALIAS = "agentos-approval";
    static final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private static final SecureRandom RNG = new SecureRandom();

    private Approvals() {}

    static final class Pending {
        final String id = hex(8);
        final byte[] canonical;
        final ApprovalCard.Model model;
        /** Who may approve this one (Broker.channels): "fingerprint" always; "tap", "voice", "reviewer" for ordinary actions. */
        volatile java.util.List<String> channels;
        /** Why only a fingerprint will do (null for an ordinary action). */
        volatile String because;
        /** Main thread: (re)draws this request's card, e.g. after an escalation. */
        volatile Runnable card;
        final CountDownLatch done = new CountDownLatch(1);
        volatile byte[] sig;
        /** How it was approved ("fingerprint", "tap", "voice", "reviewer"), and the user's words or reviewer's rationale. */
        volatile String channel = "", note = "";
        /** Closes the fingerprint prompt showing this request, so a late touch can't land on a dead request. */
        volatile Runnable onDone;
        volatile String reason = "timed out";

        Pending(String canonical, ApprovalCard.Model model, java.util.List<String> channels) {
            this.canonical = canonical.getBytes(StandardCharsets.UTF_8);
            this.model = model;
            this.channels = channels;
        }

        /** Fingerprint result, Deny, Stop or timeout. First answer wins. */
        void finish(byte[] signature, String why) { end(signature, signature != null ? "fingerprint" : "", "", why); }

        /** A non-fingerprint approval; false if this channel isn't allowed for this action or it's already decided. */
        synchronized boolean confirm(String ch, String words) { return channels.contains(ch) && end(null, ch, words, "approved by " + ch); }

        /** approval.escalate: make an undecided request fingerprint-only. Only ever stricter; false if already decided. */
        synchronized boolean escalate(String why) {
            if (done.getCount() == 0) return false;
            if (!channels.equals(Broker.FINGERPRINT_ONLY)) { channels = Broker.FINGERPRINT_ONLY; because = why; }
            return true;
        }

        private synchronized boolean end(byte[] signature, String ch, String words, String why) {
            if (done.getCount() == 0) return false;
            sig = signature;
            channel = ch;
            note = words;
            reason = why != null ? why : "timed out";
            done.countDown();
            Runnable r = onDone;
            if (r != null) r.run();
            return true;
        }
    }

    /** For the card: which channels may approve the action this card shows (empty if it isn't pending). */
    static java.util.List<String> channelsFor(ApprovalCard.Model m) {
        for (Pending p : pending.values()) if (p.model == m) return p.channels;
        return java.util.Collections.emptyList();
    }

    /** The exact bytes that get signed: {tool, pkg, target, text, task_id, nonce} in this order. */
    static String canonical(String tool, String pkg, String target, String text, String taskId, String nonce) {
        return "{\"tool\":" + JSONObject.quote(tool) + ",\"pkg\":" + JSONObject.quote(pkg) + ",\"target\":" + JSONObject.quote(target)
                + ",\"text\":" + JSONObject.quote(text) + ",\"task_id\":" + JSONObject.quote(taskId) + ",\"nonce\":" + JSONObject.quote(nonce) + "}";
    }

    static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return Audit.hex(b);
    }

    /** Create the hardware-backed key once and keep its public half in files/approval.pub. */
    static synchronized void ensureKey(Context c, boolean fresh) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        File pub = new File(c.getFilesDir(), "approval.pub");
        if (fresh) ks.deleteEntry(ALIAS);
        else if (ks.containsAlias(ALIAS) && pub.exists()) return;
        KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG) // 0 s = a fingerprint per signature
                .setInvalidatedByBiometricEnrollment(true)
                .setUnlockedDeviceRequired(true)
                .build());
        Files.write(pub.toPath(), g.generateKeyPair().getPublic().getEncoded());
    }

    /** A Signature ready to sign, locked until the fingerprint unlocks it. */
    static Signature signer(Context c) throws Exception {
        ensureKey(c, false);
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign((PrivateKey) ks.getKey(ALIAS, null));
        return s;
    }

    /** Does sig sign exactly these bytes, with our key? Checked by the broker right before acting. */
    static boolean verify(Context c, byte[] canonical, byte[] sig) {
        if (sig == null) return false;
        try {
            byte[] pub = Files.readAllBytes(new File(c.getFilesDir(), "approval.pub").toPath());
            Signature v = Signature.getInstance("SHA256withECDSA");
            v.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(pub)));
            v.update(canonical);
            return v.verify(sig);
        } catch (Exception e) { return false; }
    }

    static String publicKeyB64(Context c) {
        try { return android.util.Base64.encodeToString(Files.readAllBytes(new File(c.getFilesDir(), "approval.pub").toPath()), android.util.Base64.NO_WRAP); }
        catch (Exception e) { return ""; }
    }
}
