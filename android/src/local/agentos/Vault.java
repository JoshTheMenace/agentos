package local.agentos;

import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.accessibility.AccessibilityEvent;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
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
 *    needs the public half, so adding an entry needs no finger (the owner's Add screen asks for one anyway, as consent).
 *  - Adding: the owner's native screen in this app (VaultAddActivity, opened from the launcher's Passwords view: the
 *    password is typed here and encrypted in this process, never on a socket) or adb (tools-client.py vault add).
 *    Removing: the launcher's own uid over :7777 (owner line) or adb. No model tool can add or remove an entry.
 *  - vault.list: {id, label, apps, username}. Never the password or its ciphertext.
 *  - vault.fill {id, field_id}: the field must be a password field in one of the entry's own apps (a look-alike app
 *    asking for your Spotify password is refused: phishing defense). The fingerprint prompt ("Use your Spotify
 *    password?") unlocks one decrypt; ACTION_SET_TEXT fills the field; the reply is only {filled:true}. Optional
 *    user_field_id also fills the saved username (the broker forbids typing on a screen with a password field).
 *  - Sign-in offer (like iOS): when an app with a saved login shows a password field and no agent task is running,
 *    the card asks "Sign in as <user>?"; Sign in -> the same fingerprint prompt and fill path -> the Sign in button is
 *    tapped when there is exactly one. Declined: not offered again for that app for a few minutes.
 */
final class Vault {
    /** TEST BUILD ONLY (AGENTOS_VAULT_TEST=1 ./build.sh): a separate key and file that need no fingerprint, so the
     *  decrypt -> fill path can be tested without a finger. Normal builds compile this to false. Never ship a test build. */
    static final boolean TEST = BuildFlags.VAULT_TEST;
    private static final String ALIAS = TEST ? "agentos-vault-test" : "agentos-vault", FILE = TEST ? "vault-test.json" : "vault.json";
    private static final String RSA = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final OAEPParameterSpec OAEP = new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT);
    private static final Pattern SUBMIT = Pattern.compile("(?i)^\\s*(sign in|log in|login|continue|next|submit)\\s*$");
    /** Fields the vault filled: screen.read and labels hide their text even if the app un-masks it ("show password"). */
    static final Set<AccessibilityNodeInfo> filled = Collections.synchronizedSet(new HashSet<AccessibilityNodeInfo>());

    private final AgentA11y svc;
    private final Audit audit;
    /** Apps with a saved login (the sign-in offer only looks at these). */
    private volatile Set<String> pkgs = new HashSet<>();
    private final Map<String, Long> snooze = new HashMap<>(); // pkg -> don't offer before (ms)
    private volatile boolean offering;
    private final Runnable detectR = this::detect;

    Vault(AgentA11y svc, Audit audit) {
        this.svc = svc; this.audit = audit;
        try { index(entries()); } catch (Exception ignored) {}
    }

    JSONObject list() throws Exception {
        JSONArray out = new JSONArray(), all = entries();
        for (int i = 0; i < all.length(); i++) {
            JSONObject e = all.getJSONObject(i);
            out.put(new JSONObject().put("id", e.get("id")).put("label", e.get("label")).put("apps", e.get("pkgs")).put("username", e.optString("username")));
        }
        return new JSONObject().put("ok", true).put("entries", out).put("test_build", TEST);
    }

    /** vault.add {id, label, pkgs[], domain?, username, password}: encrypted with the public key; same id replaces. by = "adb" | "owner". */
    synchronized JSONObject add(JSONObject p, String by) throws Exception {
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
                .put("target", "saved password " + p.getString("label")).put("decision", "auto").put("reason", "added by the " + by));
        return new JSONObject().put("ok", true).put("id", id).put("count", all.length());
    }

    synchronized JSONObject remove(String id, String by) throws Exception {
        JSONArray all = without(id);
        save(all);
        audit.log(new JSONObject().put("task", "").put("tool", "vault.remove").put("entry", id).put("decision", "auto").put("reason", "removed by the " + by));
        return new JSONObject().put("ok", true).put("count", all.length());
    }

    /** vault.fill {id, field_id, user_field_id?, task_id?}: the agent's path. Runs under Broker's action lock, inside the task's lease. */
    JSONObject fill(JSONObject p, Broker.Lease l) throws Exception {
        String id = p.getString("id");
        JSONObject e = entry(id);
        AccessibilityNodeInfo n = svc.screen.node(p.getInt("field_id")), u = p.has("user_field_id") ? svc.screen.node(p.getInt("user_field_id")) : null;
        String pkg = String.valueOf(n.getPackageName()), label = e == null ? id : e.getString("label");
        JSONObject log = log(id, pkg, label, l == null ? "" : l.id);
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
                : !owns(e, pkg) ? "refused: this password belongs to " + e.getJSONArray("pkgs").join(", ").replace("\"", "") + ", not " + pkg + " (possible phishing)"
                : null;
        if (no != null) return end(log, false, "blocked", no);
        try { return fillInto(e, n, u, pkg, l.id, log, Math.max(10, Math.min(120, p.optInt("approval_timeout_s", 60))) * 1000L); }
        finally { l.h.steps++; svc.main.post(() -> { if (!l.paused) svc.autoPause.start(); }); }
    }

    /** The shared fill: fingerprint prompt -> one decrypt -> set the password (and username) fields. */
    private JSONObject fillInto(JSONObject e, AccessibilityNodeInfo n, AccessibilityNodeInfo u, String pkg, String taskId, JSONObject log, long timeoutMs) throws Exception {
        String label = e.getString("label");
        svc.main.post(() -> svc.island.set("approval", "Fingerprint for " + label, -1));
        byte[] pw;
        try { pw = decrypt(e, pkg, taskId, log, timeoutMs); }
        catch (SecurityException x) { svc.main.post(() -> svc.island.set(taskId.isEmpty() ? "idle" : "working", "Not filled", -1)); return end(log, false, "denied", x.getMessage()); }
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
        svc.main.post(() -> svc.island.set(taskId.isEmpty() ? (ok ? "done" : "alert") : "working", ok ? "Password filled" : "Fill failed", -1));
        return end(log, ok, ok ? "approved" : "failed", ok ? "filled password " + label + " into " + pkg : "Android refused the action");
    }

    /** One decrypt behind the system fingerprint prompt (ApproveActivity with a CryptoObject). Throws SecurityException if not approved. */
    private byte[] decrypt(JSONObject e, String pkg, String taskId, JSONObject log, long timeoutMs) throws Exception {
        byte[] ct = Base64.decode(e.getString("ct"), Base64.NO_WRAP);
        Cipher c = Cipher.getInstance(RSA);
        try { c.init(Cipher.DECRYPT_MODE, (PrivateKey) keystore().getKey(ALIAS, null), OAEP); }
        catch (android.security.keystore.KeyPermanentlyInvalidatedException x) { // dead for good: start a fresh vault
            keystore().deleteEntry(ALIAS);
            file().delete();
            index(new JSONArray());
            throw new SecurityException("the vault key was reset because fingerprints changed: re-add entries");
        }
        if (TEST) { log.put("approver", "TEST BUILD: no fingerprint"); return c.doFinal(ct); }
        ApprovalCard.Model m = new ApprovalCard.Model();
        String app = appLabel(pkg);
        m.title = "Use your " + e.getString("label") + " password?";
        m.app = app;
        m.target = "Fills " + e.optString("username") + " in " + app + ". The agent never sees it.";
        Approvals.Pending a = new Approvals.Pending(Approvals.canonical("vault.fill", pkg, e.getString("id"), "", taskId, Approvals.hex(16)), m, Broker.FINGERPRINT_ONLY);
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

    // ------------------------------------------------------------------ sign-in offer

    /** AgentA11y, main thread: a saved app's screen changed. Look for a login form once it settles. */
    void onEvent(AccessibilityEvent e) {
        CharSequence p = e.getPackageName();
        if (p == null || !pkgs.contains(p.toString())) return;
        int t = e.getEventType();
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && t != AccessibilityEvent.TYPE_VIEW_FOCUSED) return;
        svc.main.removeCallbacks(detectR);
        svc.main.postDelayed(detectR, 700);
    }

    private static final class Login { AccessibilityNodeInfo user, pass, submit; int submits; }

    private void detect() {
        try {
            if (offering || Broker.busy() || svc.getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) return;
            String pkg = String.valueOf(root.getPackageName());
            if (!pkgs.contains(pkg) || snooze.getOrDefault(pkg, 0L) > System.currentTimeMillis()) return;
            Login f = new Login();
            find(root, f, new int[]{400});
            JSONObject e = f.pass == null ? null : entryFor(pkg);
            if (e == null) return;
            offering = true;
            String user = e.optString("username");
            Runnable timeout = () -> settle(pkg, 60_000, "sign-in offer timed out");
            ApprovalCard.Listener l = new ApprovalCard.Listener() {
                @Override public void approve() {
                    svc.main.removeCallbacks(timeout);
                    svc.card.hide();
                    snooze.put(pkg, System.currentTimeMillis() + 120_000);
                    new Thread(() -> { try { autoFill(e, f, pkg); } catch (Exception x) { android.util.Log.w("AgentOS", "sign-in offer", x); } finally { offering = false; } }, "vault-offer").start();
                }
                @Override public void deny() { svc.main.removeCallbacks(timeout); settle(pkg, 180_000, "sign-in offer declined"); }
            };
            svc.card.offer(pkg, user, l);
            svc.main.postDelayed(timeout, 30_000);
        } catch (Exception x) { offering = false; android.util.Log.w("AgentOS", "sign-in detect", x); }
    }

    /** Main thread: close the offer without filling. */
    private void settle(String pkg, long snoozeMs, String why) {
        if (!offering) return;
        offering = false;
        snooze.put(pkg, System.currentTimeMillis() + snoozeMs);
        svc.card.hide();
        svc.island.hide();
        try { audit.log(new JSONObject().put("task", "").put("tool", "vault.fill").put("pkg", pkg).put("decision", "denied").put("reason", why)); } catch (JSONException ignored) {}
    }

    private void autoFill(JSONObject e, Login f, String pkg) throws Exception {
        JSONObject log = log(e.getString("id"), pkg, e.getString("label"), "").put("because", "sign-in offer accepted by the user");
        if (!owns(e, pkg) || !f.pass.refresh() || !f.pass.isPassword()) { end(log, false, "blocked", "login form changed"); return; }
        JSONObject r = fillInto(e, f.pass, f.user != null && f.user.refresh() && f.user.isEditable() && !f.user.isPassword() ? f.user : null, pkg, "", log, 60_000);
        if (r.getBoolean("ok") && f.submits == 1 && f.submit.refresh()) { Thread.sleep(250); Actions.tap(svc, f.submit); }
    }

    /** The login form: the password field, the text field right before it, and the one Sign in / Continue button. */
    private static void find(AccessibilityNodeInfo n, Login o, int[] budget) {
        if (n == null || --budget[0] < 0) return;
        if (n.isVisibleToUser()) {
            if (n.isEditable() && n.isPassword()) { if (o.pass == null) o.pass = n; }
            else if (n.isEditable() && o.pass == null) o.user = n;
            else if (n.isClickable() && n.isEnabled()) {
                for (CharSequence cs : new CharSequence[]{n.getText(), n.getContentDescription()})
                    if (cs != null && SUBMIT.matcher(cs).find()) { o.submits++; o.submit = n; break; }
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) find(n.getChild(i), o, budget);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean owns(JSONObject e, String pkg) throws JSONException {
        JSONArray p = e.getJSONArray("pkgs");
        for (int i = 0; i < p.length(); i++) if (pkg.equals(p.getString(i))) return true;
        return false;
    }

    private JSONObject log(String id, String pkg, String label, String taskId) throws JSONException {
        return new JSONObject().put("task", taskId).put("tool", "vault.fill").put("entry", id).put("pkg", pkg)
                .put("target", "fill password " + label + " into " + pkg).put("tier", "T3 vault password");
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

    private JSONObject entryFor(String pkg) throws Exception {
        JSONArray all = entries();
        for (int i = 0; i < all.length(); i++) if (owns(all.getJSONObject(i), pkg)) return all.getJSONObject(i);
        return null;
    }

    private JSONArray without(String id) throws Exception {
        JSONArray all = entries(), out = new JSONArray();
        for (int i = 0; i < all.length(); i++) if (!all.getJSONObject(i).getString("id").equals(id)) out.put(all.getJSONObject(i));
        return out;
    }

    private void save(JSONArray all) throws Exception {
        Files.write(file().toPath(), all.toString().getBytes(StandardCharsets.UTF_8));
        index(all);
        Events.emit("vault.changed", new JSONObject().put("count", all.length()));
    }

    private void index(JSONArray all) throws JSONException {
        Set<String> s = new HashSet<>();
        for (int i = 0; i < all.length(); i++) { JSONArray p = all.getJSONObject(i).getJSONArray("pkgs"); for (int j = 0; j < p.length(); j++) s.add(p.getString(j)); }
        pkgs = s;
    }

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
