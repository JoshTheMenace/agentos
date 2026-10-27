package local.agentos;

import android.app.Activity;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import java.security.Signature;

/** Invisible Activity that only hosts the system fingerprint prompt. The fingerprint unlocks the key that signs the action. */
public class ApproveActivity extends Activity {
    private Approvals.Pending p;
    private final CancellationSignal cancel = new CancellationSignal();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = Approvals.pending.get(String.valueOf(getIntent().getStringExtra("id")));
        if (p == null || p.done.getCount() == 0) { p = null; finish(); return; }
        p.onDone = () -> runOnUiThread(this::finish); // timeout, Deny on the card or Stop closes this prompt
        Signature sig = null;
        javax.crypto.Cipher cipher = p.cipher; // vault.fill: the fingerprint unlocks one decrypt instead of signing
        if (cipher == null) try { sig = Approvals.signer(this); }
        catch (KeyPermanentlyInvalidatedException e) { // fingerprints changed: new key; old approvals can't be replayed anyway
            try { Approvals.ensureKey(this, true); sig = Approvals.signer(this); } catch (Exception e2) { done(null, "key error: " + e2); return; }
        } catch (Exception e) { done(null, "key error: " + e); return; }
        ApprovalCard.Model m = p.model;
        new BiometricPrompt.Builder(this)
                .setTitle(cipher != null ? m.title : "Approve: " + m.title)
                .setSubtitle(m.app)
                .setDescription(m.target + (m.text.isEmpty() ? "" : "\n\"" + m.text + "\""))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Deny", getMainExecutor(), (d, w) -> done(null, "denied at fingerprint prompt"))
                .build()
                .authenticate(cipher != null ? new BiometricPrompt.CryptoObject(cipher) : new BiometricPrompt.CryptoObject(sig), cancel, getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        if (p.cipher != null) { done(new byte[0], "fingerprint"); return; } // Vault decrypts with the unlocked cipher
                        try {
                            Signature s = r.getCryptoObject().getSignature();
                            s.update(p.canonical);
                            done(s.sign(), "fingerprint");
                        } catch (Exception e) { done(null, "signing failed: " + e); }
                    }
                    @Override public void onAuthenticationError(int code, CharSequence msg) { done(null, "fingerprint: " + msg); }
                });
    }

    private void done(byte[] sig, String why) {
        if (p != null) p.finish(sig, why);
        finish();
    }

    @Override protected void onDestroy() {
        cancel.cancel();
        if (p != null) p.finish(null, "fingerprint prompt closed");
        super.onDestroy();
    }
}
