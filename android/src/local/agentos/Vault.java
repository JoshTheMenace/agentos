package local.agentos;

import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Password vault. Like a valet with your house key: the agent can say "open the Spotify door", the phone asks for your
 * fingerprint, and the valet opens it. The agent never holds the key: no tool result, screen read, log or audit line
 * ever contains the password.
 *  - files/vault.json: [{id, label, pkgs[], domain, username, ct}]. ct = RSA-OAEP(password) under a Keystore key whose
 *    private half needs a fingerprint for every decrypt (auth timeout 0, BIOMETRIC_STRONG, CryptoObject). Encrypting only
 *    needs the public half, so adding an entry needs no finger.
 *  - vault.add / vault.remove: adb only (shell uid 2000; tools-client.py vault ...). No model tool can add an entry.
 *  - vault.list: {id, label, apps, username}. Never the password or its ciphertext.
 *  - vault.fill {id, field_id}: the field must be a password field in one of the entry's own apps (a look-alike app
 *    asking for your Spotify password is refused: phishing defense). The fingerprint prompt ("Use your Spotify
 *    password?") unlocks one decrypt; ACTION_SET_TEXT fills the field; the reply is only {filled:true}. Optional
 *    user_field_id also fills the saved username (the broker forbids typing on a screen with a password field).
 */
final class Vault {
    /** TEST BUILD ONLY (AGENTOS_VAULT_TEST=1 ./build.sh): a separate key and file that need no fingerprint, so the
     *  decrypt -> fill path can be tested without a finger. Normal builds compile this to false. Never ship a test build. */
    static final boolean TEST = BuildFlags.VAULT_TEST;
    private static final String ALIAS = TEST ? "agentos-vault-test" : "agentos-vault", FILE = TEST ? "vault-test.json" : "vault.json";
    private static final String RSA = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final OAEPParameterSpec OAEP = new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT);
    /** Fields the vault filled: screen.read and labels hide their text even if the app un-masks it ("show password"). */
    static final Set<AccessibilityNodeInfo> filled = Collections.synchronizedSet(new HashSet<AccessibilityNodeInfo>());

    private final AgentA11y svc;
    private final Audit audit;

    Vault(AgentA11y svc, Audit audit) { this.svc = svc; this.audit = audit; }

    JSONObject list() throws Exception {
        JSONArray out = new JSONArray(), all = entries();
        for (int i = 0; i < all.length(); i++) {
            JSONObject e = all.getJSONObject(i);
            out.put(new JSONObject().put("id", e.get("id")).put("label", e.get("label")).put("apps", e.get("pkgs")).put("username", e.optString("username")));
        }
        return new JSONObject().put("ok", true).put("entries", out).put("test_build", TEST);
    }

    /** vault.add {id, label, pkgs[], domain?, username, password}: encrypted with the public key; same id replaces. */
    synchronized JSONObject add(JSONObject p) throws Exception {
        String id = p.getString("id");
        JSONArray pkgs = p.getJSONArray("pkgs");
        if (!id.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("id: a-z 0-9 _ -, max 32");
        if (pkgs.length() == 0) throw new IllegalArgumentException("pkgs: at least one app package");
        Cipher c = Cipher.getInstance(RSA);
        c.init(Cipher.ENCRYPT_MODE, publicKey(), OAEP);
        byte[] pw = p.getString("password").getBytes(StandardCharsets.UTF_8);
        String ct = Base64.encodeToString(c.doFinal(pw), Base64.NO_WRAP);
        Arrays.fill(pw, (byte) 0);
        JSONArray all = without(id).put(new JSONObject().put("id", id).put("label", p.getString("label")).put("pkgs", pkgs)
                .put("domain", p.optString("domain")).put("username", p.optString("username")).put("ct", ct));
        save(all);
        audit.log(new JSONObject().put("task", "").put("tool", "vault.add").put("entry", id).put("pkg", pkgs.join(",").replace("\"", ""))
                .put("target", "saved password " + p.getString("label")).put("decision", "auto").put("reason", "added by the operator over adb"));
        return new JSONObject().put("ok", true).put("id", id).put("count", all.length());
    }

    synchronized JSONObject remove(String id) throws Exception {
        JSONArray all = without(id);
        save(all);
        audit.log(new JSONObject().put("task", "").put("tool", "vault.remove").put("entry", id).put("decision", "auto").put("reason", "removed by the operator over adb"));
        return new JSONObject().put("ok", true).put("count", all.length());
    }

    /** vault.fill {id, field_id, user_field_id?, task_id?}. Runs under Broker's action lock, inside the task's lease. */
    JSONObject fill(JSONObject p, Broker.Lease l) throws Exception {
        String id = p.getString("id");
        JSONObject e = entry(id);
        AccessibilityNodeInfo n = svc.screen.node(p.getInt("field_id")), u = p.has("user_field_id") ? svc.screen.node(p.getInt("user_field_id")) : null;
        String pkg = String.valueOf(n.getPackageName()), label = e == null ? id : e.getString("label");
        JSONObject log = new JSONObject().put("task", l == null ? "" : l.id).put("tool", "vault.fill").put("entry", id).put("pkg", pkg)
                .put("target", "fill password " + label + " into " + pkg).put("tier", "T3 vault password");
        String no = e == null ? "no such vault entry (call vault.list)"
                : svc.getSystemService(KeyguardManager.class).isKeyguardLocked() ? "phone is locked"
                : l == null ? "no active task: call task.start first"
                : System.currentTimeMillis() > l.expires ? "task lease expired"
                : l.paused ? "task paused: call task.resume after re-reading the screen"
                : p.has("task_id") && !p.getString("task_id").equals(l.id) ? "task_id does not match the active task"
                : !n.isPassword() ? "field " + p.getInt("field_id") + " is not a password field"
                : u != null && (!u.isEditable() || u.isPassword() || !pkg.contentEquals(u.getPackageName())) ? "user_field_id must be a text field in the same app"
                : Guard.offLimits(n) || Guard.forbidden(pkg) ? "T4 forbidden: " + pkg + " is a protected surface"
                : !l.apps.contains(pkg) ? pkg + " is not in this task's lease " + l.apps
                : !e.getJSONArray("pkgs").toString().contains("\"" + pkg + "\"")
                        ? "refused: this password belongs to " + e.getJSONArray("pkgs").join(", ").replace("\"", "") + ", not " + pkg + " (possible phishing)"
                : null;
        if (no != null) return end(log, false, "blocked", no);
        svc.main.post(() -> svc.island.set("approval", "Fingerprint for " + label, -1));
        byte[] pw;
        try { pw = decrypt(e, pkg, l, log, Math.max(10, Math.min(120, p.optInt("approval_timeout_s", 60))) * 1000L); }
        catch (SecurityException x) { return end(log, false, "denied", x.getMessage()); }
        finally { svc.main.post(() -> { if (!l.paused) svc.autoPause.start(); }); }
        Thread.sleep(300); // the prompt's window closes; the app's field takes input again
        if (!n.refresh() || !n.isPassword() || !pkg.equals(String.valueOf(n.getPackageName()))) {
            Arrays.fill(pw, (byte) 0);
            return end(log, false, "blocked", "screen changed during the fingerprint; nothing filled");
        }
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, new String(pw, StandardCharsets.UTF_8));
        Arrays.fill(pw, (byte) 0);
        boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        if (ok) filled.add(n);
        if (ok && u != null && u.refresh()) Actions.type(u, e.optString("username"), false);
        l.h.steps++;
        svc.main.post(() -> svc.island.set("working", ok ? "Password filled" : "Fill failed", -1));
        return end(log, ok, ok ? "approved" : "failed", ok ? "filled password " + label + " into " + pkg : "Android refused the action");
    }

    /** One decrypt behind the system fingerprint prompt (ApproveActivity with a CryptoObject). Throws SecurityException if not approved. */
    private byte[] decrypt(JSONObject e, String pkg, Broker.Lease l, JSONObject log, long timeoutMs) throws Exception {
        byte[] ct = Base64.decode(e.getString("ct"), Base64.NO_WRAP);
        Cipher c = Cipher.getInstance(RSA);
        try { c.init(Cipher.DECRYPT_MODE, (PrivateKey) keystore().getKey(ALIAS, null), OAEP); }
        catch (android.security.keystore.KeyPermanentlyInvalidatedException x) { // dead for good: start a fresh vault
            keystore().deleteEntry(ALIAS);
            file().delete();
            throw new SecurityException("the vault key was reset because fingerprints changed: re-add entries (tools-client.py vault add)");
        }
        if (TEST) { log.put("approver", "TEST BUILD: no fingerprint"); return c.doFinal(ct); }
        ApprovalCard.Model m = new ApprovalCard.Model();
        String app = appLabel(pkg);
        m.title = "Use your " + e.getString("label") + " password?";
        m.app = app;
        m.target = "Fills " + e.optString("username") + " in " + app + ". The agent never sees it.";
        Approvals.Pending a = new Approvals.Pending(Approvals.canonical("vault.fill", pkg, e.getString("id"), "", l.id, Approvals.hex(16)), m, Broker.FINGERPRINT_ONLY);
        a.cipher = c;
        Approvals.pending.put(a.id, a);
        svc.main.post(svc.autoPause::stop);
        svc.startActivity(new Intent(svc, ApproveActivity.class).putExtra("id", a.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try { if (!a.done.await(timeoutMs, TimeUnit.MILLISECONDS)) a.finish(null, null); }
        finally { Approvals.pending.remove(a.id); }
        log.put("allowed_channels", new JSONArray(a.channels)).put("because", "uses a saved password");
        if (!a.channel.equals("fingerprint")) throw new SecurityException(a.reason);
        log.put("approver", "fingerprint");
        return c.doFinal(ct); // the cipher the fingerprint just unlocked, good for this one operation
    }

    private JSONObject end(JSONObject log, boolean ok, String decision, String reason) throws JSONException {
        audit.log(log.put("decision", decision).put("reason", reason).put("ok", ok));
        return new JSONObject().put("ok", ok).put("filled", ok).put("decision", decision).put("reason", reason);
    }

    // ------------------------------------------------------------------ storage

    private File file() { return new File(svc.getFilesDir(), FILE); }

    private JSONArray entries() throws Exception {
        return file().exists() ? new JSONArray(new String(Files.readAllBytes(file().toPath()), StandardCharsets.UTF_8)) : new JSONArray();
    }

    private JSONObject entry(String id) throws Exception {
        JSONArray all = entries();
        for (int i = 0; i < all.length(); i++) if (all.getJSONObject(i).getString("id").equals(id)) return all.getJSONObject(i);
        return null;
    }

    private JSONArray without(String id) throws Exception {
        JSONArray all = entries(), out = new JSONArray();
        for (int i = 0; i < all.length(); i++) if (!all.getJSONObject(i).getString("id").equals(id)) out.put(all.getJSONObject(i));
        return out;
    }

    private void save(JSONArray all) throws Exception { Files.write(file().toPath(), all.toString().getBytes(StandardCharsets.UTF_8)); }

    private static KeyStore keystore() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        return ks;
    }

    /** The public half (a software copy: public-key encryption needs no fingerprint). Creates the key pair on first use. */
    private static synchronized PublicKey publicKey() throws Exception {
        KeyStore ks = keystore();
        if (!ks.containsAlias(ALIAS)) {
            KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP);
            if (!TEST) b.setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG) // 0 s = a fingerprint per decrypt
                    .setInvalidatedByBiometricEnrollment(true).setUnlockedDeviceRequired(true);
            KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            g.initialize(b.build());
            g.generateKeyPair();
        }
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(ks.getCertificate(ALIAS).getPublicKey().getEncoded()));
    }

    private String appLabel(String pkg) {
        try { return String.valueOf(svc.getPackageManager().getApplicationLabel(svc.getPackageManager().getApplicationInfo(pkg, 0))); }
        catch (Exception x) { return pkg; }
    }
}
