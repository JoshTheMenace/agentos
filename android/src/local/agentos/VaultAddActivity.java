package local.agentos;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

import static local.agentos.Island.*;

/**
 * The owner's "Add password" screen (A2 Calm Glass, native). Opened from the launcher's Passwords view.
 * The password is typed into a native field in THIS process and handed straight to Vault.add (Keystore public key):
 * it never crosses a socket, agentd or a model. Saving asks for a fingerprint as the consent gesture.
 * Extras: pkg (preselects the app).
 */
public class VaultAddActivity extends Activity {
    private LinearLayout root, form, picker;
    private EditText user, pass, search;
    private TextView appName;
    private ImageView appIcon;
    private String pkg = "", label = "";
    private float d;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        getWindow().setStatusBarColor(Color.BLACK); getWindow().setNavigationBarColor(Color.BLACK);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.BLACK);
        root = col(); root.setPadding(dp(20), dp(44), dp(20), dp(24));
        sv.addView(root);
        setContentView(sv);
        WindowInsetsController ic = sv.getWindowInsetsController();
        if (ic != null) ic.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        buildForm();
        buildPicker();
        root.addView(form); root.addView(picker);
        String want = getIntent().getStringExtra("pkg");
        if (want != null && !want.isEmpty()) choose(want);
        showPicker(pkg.isEmpty());
    }

    private void buildForm() {
        form = col();
        form.addView(text(this, "Add password", 28, 700, L1));
        TextView sub = text(this, "Kept on this phone behind your fingerprint. Agents can sign you in with it; they never see it.", 13.5f, 500, L2);
        form.addView(sub, margin(0, dp(6)));
        LinearLayout card = col();
        card.setBackground(capsule(this, 0x99484852, HAIR, 22));
        card.setPadding(dp(14), dp(10), dp(14), dp(12));
        // app row
        LinearLayout app = row(); app.setPadding(0, dp(6), 0, dp(10));
        appIcon = new ImageView(this); appIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        appIcon.setBackground(capsule(this, FILL14, 0, 10)); appIcon.setClipToOutline(true);
        app.addView(appIcon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout tt = col();
        tt.addView(text(this, "App", 11, 700, L3));
        appName = text(this, "Choose an app", 16, 600, L1);
        tt.addView(appName);
        app.addView(tt, new LinearLayout.LayoutParams(0, -2, 1) {{ leftMargin = dp(11); }});
        app.addView(text(this, "›", 22, 500, L3));
        app.setOnClickListener(v -> showPicker(true));
        card.addView(app);
        user = field("Email or username", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        pass = field("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(user, new LinearLayout.LayoutParams(-1, dp(46)));
        card.addView(pass, new LinearLayout.LayoutParams(-1, dp(46)) {{ topMargin = dp(8); }});
        form.addView(card, new LinearLayout.LayoutParams(-1, -2) {{ topMargin = dp(18); }});
        TextView save = text(this, "Save with fingerprint", 16, 600, L1);
        save.setGravity(Gravity.CENTER); save.setBackground(capsule(this, BLUE, 0, 0));
        save.setOnClickListener(v -> save());
        form.addView(save, new LinearLayout.LayoutParams(-1, dp(50)) {{ topMargin = dp(18); }});
        TextView cancel = text(this, "Cancel", 15, 600, L2);
        cancel.setGravity(Gravity.CENTER); cancel.setOnClickListener(v -> finish());
        form.addView(cancel, new LinearLayout.LayoutParams(-1, dp(44)) {{ topMargin = dp(6); }});
    }

    private void buildPicker() {
        picker = col();
        picker.addView(text(this, "Which app?", 28, 700, L1));
        search = field("Search", InputType.TYPE_CLASS_TEXT);
        picker.addView(search, new LinearLayout.LayoutParams(-1, dp(46)) {{ topMargin = dp(14); }});
        LinearLayout list = col();
        picker.addView(list, new LinearLayout.LayoutParams(-1, -2) {{ topMargin = dp(8); }});
        PackageManager pm = getPackageManager();
        List<ResolveInfo> ri = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        List<String[]> apps = new ArrayList<>();
        for (ResolveInfo r : ri) if (!r.activityInfo.packageName.startsWith("local.agentos")) apps.add(new String[]{r.activityInfo.packageName, String.valueOf(r.loadLabel(pm))});
        Collections.sort(apps, (a, c) -> a[1].compareToIgnoreCase(c[1]));
        Runnable fill = () -> {
            String q = search.getText().toString().trim().toLowerCase();
            list.removeAllViews();
            for (String[] a : apps) {
                if (!q.isEmpty() && !a[1].toLowerCase().contains(q)) continue;
                LinearLayout r = row(); r.setPadding(dp(4), dp(9), dp(4), dp(9));
                ImageView iv = new ImageView(this);
                try { iv.setImageDrawable(pm.getApplicationIcon(a[0])); } catch (Exception ignored) {}
                r.addView(iv, new LinearLayout.LayoutParams(dp(32), dp(32)));
                r.addView(text(this, a[1], 15, 600, L1), new LinearLayout.LayoutParams(0, -2, 1) {{ leftMargin = dp(12); }});
                r.setOnClickListener(v -> { choose(a[0]); showPicker(false); });
                list.addView(r);
            }
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) { fill.run(); }
        });
        fill.run();
    }

    private void choose(String p) {
        PackageManager pm = getPackageManager();
        try {
            label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0)));
            appIcon.setImageDrawable(pm.getApplicationIcon(p));
        } catch (Exception e) { return; }
        pkg = p; appName.setText(label);
    }

    private void showPicker(boolean on) {
        picker.setVisibility(on ? View.VISIBLE : View.GONE);
        form.setVisibility(on ? View.GONE : View.VISIBLE);
        if (!on && !pkg.isEmpty() && user.getText().length() == 0) user.requestFocus();
    }

    private void save() {
        String u = user.getText().toString().trim();
        if (pkg.isEmpty()) { showPicker(true); return; }
        if (u.isEmpty() || pass.getText().length() == 0) { Toast.makeText(this, "Enter the username and password", Toast.LENGTH_SHORT).show(); return; }
        Vault v = Broker.vault();
        if (v == null) { Toast.makeText(this, "AgentOS is off", Toast.LENGTH_SHORT).show(); return; }
        new BiometricPrompt.Builder(this).setTitle("Save " + label + " password?").setSubtitle(u)
                .setDescription("Agents can fill it with your fingerprint, never read it.")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Cancel", getMainExecutor(), (dlg, w) -> {})
                .build().authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        String id = pkg.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
                        id = id.length() > 32 ? id.substring(id.length() - 32) : id;
                        JSONObject p;
                        try { p = new JSONObject().put("id", id).put("label", label).put("pkgs", new JSONArray().put(pkg)).put("username", u).put("password", pass.getText().toString()); }
                        catch (Exception e) { return; }
                        new Thread(() -> {
                            String m;
                            try { v.add(p, "owner (Passwords screen)"); m = "Saved " + label + " password"; }
                            catch (Exception e) { m = "Could not save: " + e.getMessage(); }
                            final String msg = m;
                            pass.post(() -> { pass.setText(""); Toast.makeText(VaultAddActivity.this, msg, Toast.LENGTH_SHORT).show(); finish(); });
                        }, "vault-add").start();
                    }
                });
    }

    @Override public void onBackPressed() { if (picker.getVisibility() == View.VISIBLE && !pkg.isEmpty()) showPicker(false); else super.onBackPressed(); }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setInputType(type); e.setSingleLine(true);
        e.setTextSize(16); e.setTextColor(L1); e.setHintTextColor(L3);
        e.setBackground(capsule(this, 0x1AFFFFFF, 0, 14)); e.setPadding(dp(14), 0, dp(14), 0);
        return e;
    }

    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout col() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private int dp(float v) { return Math.round(v * d); }
}
