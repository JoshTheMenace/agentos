package local.agentos;

import android.content.*;
import android.graphics.*;
import android.os.*;
import android.security.keystore.*;
import android.util.Base64;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import local.agentos.deep.Contract;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Pigeon-only signed-spec coordinator, separate from accessibility overlay mods. */
final class DeepMods {
    static volatile DeepMods instance;
    final AgentA11y svc;
    final Audit audit;
    final Object lock = new Object(); // apply and durable kill share one serialization boundary
    final android.content.SharedPreferences prefs;
    volatile IBinder app;
    volatile long epoch; volatile boolean summaryReady;
    long revision;
    JSONObject active, previous;
    String activeTask = "", approvedBuild = ""; long lastSummary; String summaryContext = "";
    KeyPair key;
    final Map<String, Receipt> receipts = new LinkedHashMap<>();
    static final class Receipt { String id, raw, hash, build, context, source, evidence, task; long base, epoch, generation, until; JSONObject before, candidate; }
    final File evidenceDir;
    final Runnable sweep = new Runnable() { public void run() { prune(); svc.main.postDelayed(this, 60000); } };
    DeepMods(AgentA11y svc, Audit audit) {
        this.svc = svc; this.audit = audit; prefs = svc.getSharedPreferences("deepmods", 0);
        epoch = prefs.getLong("epoch", 0) + 1; prefs.edit().putLong("epoch", epoch).commit();
        evidenceDir = new File(svc.getFilesDir(), "deepmod-evidence"); evidenceDir.mkdirs();
        instance = this; svc.main.post(sweep);
    }
    void bind() throws Exception {
        if (app != null && app.isBinderAlive()) return;
        Contract.need(Contract.PIGEON_CERT.equals(Contract.cert(svc, Contract.PKG)), "Pigeon certificate mismatch");
        CompletableFuture<IBinder> f = new CompletableFuture<>();
        svc.main.post(() -> {
            pendingBind = f;
            boolean ok = svc.bindService(new Intent().setComponent(new ComponentName(Contract.PKG, Contract.PKG + ".DeepModService")), connection, Context.BIND_AUTO_CREATE);
            if (!ok) f.completeExceptionally(new IllegalStateException("cannot bind Pigeon")); else pendingBind = f;
        });
        app = f.get(3, TimeUnit.SECONDS);
    }
    volatile CompletableFuture<IBinder> pendingBind;
    final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
            app = b; CompletableFuture<IBinder> f = pendingBind; if (f != null) f.complete(b);
            try { b.linkToDeath(() -> { app = null; try { disable("Pigeon process disconnected"); } catch (Exception ignored) {} }, 0); } catch (Exception ignored) {}
        }
        public void onServiceDisconnected(ComponentName n) { app = null; }
    };
    JSONObject request(JSONObject p) throws Exception { bind(); return Contract.rpc(app, p); }
    JSONObject inspect() throws Exception {
        foreground();
        JSONObject r = request(new JSONObject().put("op", "inspect"));
        Contract.need(Contract.PIGEON_CERT.equals(r.getString("cert")) && r.optBoolean("foreground"), "target not foreground");
        String build = Contract.build(svc, Contract.PKG);
        Contract.need(build.equals(r.getString("build")), "Pigeon build changed");
        if (approvedBuild.isEmpty()) { approvedBuild = prefs.getString("build", ""); }
        Contract.need(build.equals(approvedBuild), "Pigeon build not integrated; developer must enroll this exact APK");
        revision = Math.max(revision, r.getLong("revision"));
        Contract.need(r.getLong("epoch") == epoch, "wait for broker grant synchronization");
        if (r.isNull("spec") && !r.optBoolean("preview")) { active = null; previous = null; }
        return r.put("capabilities", new JSONObject().put("colors", new JSONArray(Contract.COLORS)).put("fonts", new JSONArray(Arrays.asList("sans-serif", "serif", "monospace")))
            .put("hide", new JSONArray(Arrays.asList("pigeon_overflow", "pigeon_promotion"))).put("filter", "pigeon_threads:promoted=true (explicit fixtures only)")
            .put("button", "chat/pigeon_chat_actions: summarize-chat → agent.ask/summarize-visible; HUMAN approval; read consent required"));
    }
    JSONObject enroll() throws Exception {
        Contract.need(Contract.PIGEON_CERT.equals(Contract.cert(svc, Contract.PKG)), "Pigeon certificate mismatch");
        approvedBuild = Contract.build(svc, Contract.PKG); prefs.edit().putString("build", approvedBuild).commit();
        audit.log(new JSONObject().put("tool", "app.deepmod.integrate").put("build", approvedBuild).put("decision", "auto").put("reason", "developer integration over adb"));
        return new JSONObject().put("ok", true).put("build", approvedBuild);
    }
    void foreground() {
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        Contract.need(root != null && Contract.PKG.contentEquals(root.getPackageName()) && !svc.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked(), "open Pigeon on an unlocked phone first");
    }
    synchronized KeyPair key() throws Exception {
        if (key != null) return key;
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        String alias = "agentos-deepmod-v1";
        if (!ks.containsAlias(alias)) {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
            g.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(new java.security.spec.ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build()); g.generateKeyPair();
        }
        key = new KeyPair(ks.getCertificate(alias).getPublicKey(), (PrivateKey)ks.getKey(alias, null)); return key;
    }
    JSONObject envelope(String raw, String mode, String task, JSONObject state) throws Exception {
        JSONObject e = new JSONObject().put("payloadB64", Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP))
            .put("digest", Contract.hash(raw)).put("build", state.getString("build")).put("cert", Contract.PIGEON_CERT).put("revision", state.getLong("revision") + 1)
            .put("task", task).put("mode", mode).put("nonce", UUID.randomUUID().toString()).put("expires", System.currentTimeMillis() + 9000).put("epoch", epoch);
        Signature sig = Signature.getInstance("SHA256withECDSA"); sig.initSign(key().getPrivate()); sig.update(Contract.signed(e));
        return e.put("signature", Base64.encodeToString(sig.sign(), Base64.NO_WRAP));
    }
    JSONObject render(String raw, String mode, String task, JSONObject state) throws Exception {
        return request(new JSONObject().put("op", "envelope").put("envelope", envelope(raw, mode, task, state)));
    }
    JSONObject preview(String raw, String task) throws Exception {
        Contract.validate(raw);
        synchronized (lock) {
            Contract.need(!"mods".equals(Plugins.off("app.deepmod.preview", new JSONObject())), "mods plugin is off");
            JSONObject before = inspect(); Contract.need(!before.optBoolean("preview"), "preview already running");
            JSONObject spec = Contract.validate(raw); Contract.need(spec.getLong("baseRevision") == before.getLong("revision"), "stale base revision");
            Receipt r = new Receipt(); r.id = UUID.randomUUID().toString(); r.task = task; r.raw = raw; r.hash = Contract.hash(raw); r.build = before.getString("build"); r.base = before.getLong("revision");
            r.context = before.getString("context"); r.generation = before.getLong("generation"); r.epoch = epoch; r.source = before.getString("source_sha256"); r.before = before;
            cleanup(); Bitmap baseline = capture(); Bitmap candidate = null;
            try {
                r.candidate = render(raw, "preview", task, before);
                Thread.sleep(400); candidate = capture(); r.candidate = inspect();
                verify(r, r.candidate, true);
                r.evidence = evidence(r, baseline, candidate); r.until = SystemClock.uptimeMillis() + 30000;
            } finally {
                request(new JSONObject().put("op", "restore")); baseline.recycle(); if (candidate != null) candidate.recycle();
            }
            receipts.put(r.id, r);
            audit.log(new JSONObject().put("tool", "app.deepmod.preview").put("task", task).put("spec_sha256", r.hash).put("build", r.build).put("receipt", r.id).put("evidence_sha256", r.evidence).put("decision", "auto").put("verified", true));
            return new JSONObject().put("ok", true).put("receipt", r.id).put("spec_sha256", r.hash).put("expires_ms", 30000).put("restored", true).put("verified", true)
                .put("evidence", evidenceDir + "/" + r.id).put("structure", r.candidate);
        }
    }
    Receipt receipt(String id) throws Exception {
        Receipt r = receipts.get(id); Contract.need(r != null && r.epoch == epoch && r.until > SystemClock.uptimeMillis(), "preview receipt expired/disabled");
        JSONObject now = inspect();
        Contract.need(r.build.equals(now.getString("build")) && r.base == now.getLong("revision") && r.context.equals(now.getString("context")) && r.generation == now.getLong("generation") && r.source.equals(now.getString("source_sha256")), "app/screen/data changed; preview again");
        Contract.need(now.getInt("orientation") == r.before.getInt("orientation") && now.getDouble("fontScale") == r.before.getDouble("fontScale") && now.getJSONArray("bounds").toString().equals(r.before.getJSONArray("bounds").toString()), "display changed; preview again");
        return r;
    }
    JSONObject apply(String id, String task) throws Exception {
        synchronized (lock) {
            Receipt r = receipt(id); Contract.need(task.equals(r.task), "preview belongs to another task"); JSONObject state = inspect();
            JSONObject old = active;
            try {
                JSONObject result = render(r.raw, "commit", task, state); verify(r, result, false);
                Thread.sleep(400); Bitmap committed = capture();
                try {
                    JSONObject settled = inspect(); verify(r, settled, false);
                    Contract.need(settled.getInt("orientation") == r.before.getInt("orientation") && settled.getDouble("fontScale") == r.before.getDouble("fontScale"), "display configuration changed");
                    save(committed, new File(evidenceDir, r.id + "-committed.png")); } finally { committed.recycle(); }
                request(new JSONObject().put("op", "verified").put("revision", result.getLong("revision")).put("epoch", epoch));
                previous = old; active = Contract.validate(r.raw); revision = result.getLong("revision"); activeTask = task; receipts.clear();
                audit.log(new JSONObject().put("tool", "app.deepmod.apply").put("task", task).put("spec_sha256", r.hash).put("receipt", r.id).put("evidence_sha256", r.evidence).put("revision", revision).put("decision", "approved").put("verified", true));
                return new JSONObject().put("ok", true).put("verified", true).put("revision", revision).put("evidence", evidenceDir + "/" + r.id + "-committed.png");
            } catch (Exception error) {
                disable("commit verification failed");
                throw error;
            }
        }
    }
    void verify(Receipt r, JSONObject state, boolean preview) throws Exception {
        Contract.need(r.context.equals(state.getString("context")) && r.generation == state.getLong("generation") && r.source.equals(state.getString("source_sha256")), "screen/data changed during rendering");
        Contract.need(state.optBoolean("structural"), "runtime structural assertion failed");
        JSONObject spec = Contract.validate(r.raw); JSONArray lists = spec.optJSONArray("lists"), buttons = spec.optJSONArray("buttons");
        Contract.need(state.optBoolean("preview") == preview && spec.toString().equals(state.getJSONObject("spec").toString()), "candidate expired or changed");
        int filtered = lists == null || lists.length() == 0 ? 0 : 2;
        Contract.need(state.getJSONArray("visibleRows").length() == state.getInt("sourceRows") - filtered, "row projection mismatch");
        JSONArray rows = state.getJSONArray("visibleRows");
        for (int i = 0; i < rows.length(); i++) Contract.need(rows.getInt(i) == i, "neighboring row order changed");
        JSONArray views = spec.optJSONArray("views");
        for (int i = 0; views != null && i < views.length(); i++) { JSONObject m = views.getJSONObject(i).getJSONObject("match"); Contract.need(state.getJSONObject("hidden").getBoolean(m.has("id") ? m.getString("id") : "pigeon_promotion"), "view did not collapse"); }
        int count = buttons == null ? 0 : buttons.length();
        Contract.need(state.getInt("buttonCount") == count && (!preview || !state.getBoolean("buttonEnabled")), "native button/preview assertion failed");
    }
    JSONObject remove(boolean rollback) throws Exception {
        synchronized (lock) {
            if (!rollback) { try { foreground(); } catch (Exception e) { return disable("remove while backgrounded"); } }
            JSONObject now = inspect(), restore = rollback ? previous : null;
            if (restore != null) restore = Contract.parse(restore.toString()).put("baseRevision", now.getLong("revision"));
            JSONObject result = render(restore == null ? "{}" : restore.toString(), "rollback", "owner undo", now);
            Contract.need(result.optBoolean("structural"), "rollback structural check failed");
            request(new JSONObject().put("op", "verified").put("revision", result.getLong("revision")).put("epoch", epoch));
            revision = result.getLong("revision"); active = restore; previous = null; receipts.clear();
            audit.log(new JSONObject().put("tool", rollback ? "app.deepmod.rollback" : "app.deepmod.remove").put("revision", revision).put("decision", "auto").put("reason", "appearance only; chat data unchanged"));
            return result;
        }
    }
    JSONObject disable(String reason) throws Exception {
        synchronized (lock) {
            epoch++;
            Contract.need(prefs.edit().putLong("epoch", epoch).commit(), "cannot persist kill epoch");
            active = null; previous = null; summaryContext = ""; receipts.clear();
            for (Approvals.Pending pending : Approvals.pending.values()) {
                JSONObject action = new JSONObject(new String(pending.canonical, StandardCharsets.UTF_8));
                if (action.optString("tool").startsWith("app.deepmod.")) pending.finish(null, "deep mods disabled");
            }
            if (app != null && app.isBinderAlive()) try { request(new JSONObject().put("op", "disable").put("epoch", epoch)); } catch (Exception ignored) {}
            audit.log(new JSONObject().put("tool", "app.deepmod.disable").put("epoch", epoch).put("decision", "auto").put("reason", reason));
            return new JSONObject().put("ok", true).put("epoch", epoch).put("disabled", true);
        }
    }
    boolean consumeSummary(String context) {
        synchronized (lock) {
            boolean allowed = active != null && epoch == prefs.getLong("epoch", -1) && summaryContext.equals(context) && SystemClock.uptimeMillis() - lastSummary < 30000;
            summaryContext = ""; return allowed;
        }
    }
    JSONObject callback(JSONObject p) throws Exception {
        String op = p.getString("op");
        if (op.equals("grant")) return new JSONObject().put("ok", true).put("key", Base64.encodeToString(key().getPublic().getEncoded(), Base64.NO_WRAP)).put("epoch", epoch).put("summaryReady", summaryReady);
        if (op.equals("reset")) return disable("Pigeon Reset appearance");
        if (op.equals("action")) {
            // No chat text or model-authored task crosses this endpoint. agentd checks existing cloud consent.
            synchronized (lock) {
                foreground(); Contract.need(summaryReady, "enable Pigeon cloud consent in Memory first"); Contract.need(Plugins.off("app.deepmod.apply", new JSONObject()) == null, "mods plugin off");
                JSONObject now = inspect();
                Contract.need(active != null && active.optJSONArray("buttons") != null && active.getJSONArray("buttons").length() == 1
                    && p.getLong("revision") == revision && p.getLong("epoch") == epoch && p.getString("context").equals(now.getString("context")) && "chat".equals(now.getString("screen")) && "summarize-chat".equals(p.getString("action")), "inactive/stale action");
                Contract.need(SystemClock.uptimeMillis() - lastSummary >= 5000, "summary already requested"); lastSummary = SystemClock.uptimeMillis();
                summaryContext = now.getString("context");
                Events.emit("deepmod.summary", new JSONObject().put("revision", revision).put("epoch", epoch).put("context", now.getString("context")));
                return new JSONObject().put("ok", true);
            }
        }
        throw new IllegalArgumentException("unknown callback");
    }
    Bitmap capture() throws Exception {
        foreground(); CompletableFuture<Bitmap> f = new CompletableFuture<>();
        svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.getMainExecutor(), new android.accessibilityservice.AccessibilityService.TakeScreenshotCallback() {
            public void onSuccess(android.accessibilityservice.AccessibilityService.ScreenshotResult r) {
                Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace()); f.complete(hw.copy(Bitmap.Config.ARGB_8888, true)); hw.recycle(); r.getHardwareBuffer().close();
            }
            public void onFailure(int code) { f.completeExceptionally(new IllegalStateException("screenshot failed " + code)); }
        });
        return f.get(3, TimeUnit.SECONDS);
    }
    String evidence(Receipt receipt, Bitmap a, Bitmap b) throws Exception {
        String id = receipt.id;
        Contract.need(a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight(), "orientation changed");
        int w = a.getWidth(), h = a.getHeight();
        Bitmap pair = Bitmap.createBitmap(w * 2, h, Bitmap.Config.ARGB_8888), diff = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(pair); c.drawBitmap(a, 0, 0, null); c.drawBitmap(b, w, 0, null);
        JSONArray rect = receipt.before.getJSONArray("bounds");
        Rect bounds = new Rect(rect.getInt(0), rect.getInt(1), rect.getInt(2), rect.getInt(3));
        Contract.need(bounds.toString().equals(new Rect(receipt.candidate.getJSONArray("bounds").getInt(0), receipt.candidate.getJSONArray("bounds").getInt(1), receipt.candidate.getJSONArray("bounds").getInt(2), receipt.candidate.getJSONArray("bounds").getInt(3)).toString()), "content bounds changed");
        int[] left = new int[w*h], right = new int[w*h], heat = new int[w*h]; a.getPixels(left, 0, w, 0, 0, w, h); b.getPixels(right, 0, w, 0, 0, w, h);
        int changed = 0;
        for (int y = bounds.top; y < Math.min(h, bounds.bottom); y++) for (int x = bounds.left; x < Math.min(w, bounds.right); x++) {
            int i = y*w+x, d = Math.max(Math.abs(Color.red(left[i])-Color.red(right[i])), Math.max(Math.abs(Color.green(left[i])-Color.green(right[i])), Math.abs(Color.blue(left[i])-Color.blue(right[i]))));
            heat[i] = Color.rgb(d, 0, 0); if (d > 16) changed++;
        }
        diff.setPixels(heat, 0, w, 0, 0, w, h);
        try {
            save(a, new File(evidenceDir, id + "-before.png")); save(b, new File(evidenceDir, id + "-after.png")); save(pair, new File(evidenceDir, id + "-pair.png")); save(diff, new File(evidenceDir, id + "-diff.png"));
            JSONObject metadata = new JSONObject().put("payload_sha256", receipt.hash).put("build", receipt.build).put("baseRevision", receipt.base).put("epoch", receipt.epoch)
                .put("generation", receipt.generation).put("context", receipt.context).put("source_sha256", receipt.source).put("timestamp", System.currentTimeMillis())
                .put("bounds", rect).put("orientation", receipt.before.getInt("orientation")).put("fontScale", receipt.before.getDouble("fontScale")).put("changedPixels", changed)
                .put("declaredRegion", "Pigeon content root; system bars excluded").put("structural", true);
            java.nio.file.Files.write(new File(evidenceDir, id + "-metadata.json").toPath(), metadata.toString(2).getBytes(StandardCharsets.UTF_8));
            Contract.need(changed > 0, "no screenshot change; nothing verified");
            return Contract.hash(java.nio.file.Files.readAllBytes(new File(evidenceDir, id + "-pair.png").toPath()));
        } finally { pair.recycle(); diff.recycle(); }
    }
    static void save(Bitmap b, File f) throws Exception { try (FileOutputStream out = new FileOutputStream(f)) { Contract.need(b.compress(Bitmap.CompressFormat.PNG, 100, out), "PNG encode failed"); } }
    void prune() {
        File[] fs = evidenceDir.listFiles(); if (fs == null) return;
        Arrays.sort(fs, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < fs.length; i++) if (i < fs.length - 48 || System.currentTimeMillis() - fs[i].lastModified() > 600000) fs[i].delete();
    }
    void cleanup() { prune(); receipts.clear(); }
    void close() { svc.main.removeCallbacks(sweep); try { disable("broker closed"); } catch (Exception ignored) {} if (app != null) try { svc.unbindService(connection); } catch (Exception ignored) {} if (instance == this) instance = null; }
}
