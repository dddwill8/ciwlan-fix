package dev.ciwlanfix.lsposed;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.Toast;

public final class SettingsActivity extends Activity {
    private static final String PREF_FILE = "ciwlan_fix";
    private static final String K_FN1 = "force_oos_slot1";
    private static final String K_PLMN = "force_oos_plmn";
    private static final String K_FN2 = "set_ciwlan_only_slot1";
    private static final String K_FN3 = "force_qns_iwlan_fallback";

    private static final String G_FN1 = "ciwlan_fix_force_oos_slot1";
    private static final String G_PLMN = "ciwlan_fix_force_oos_plmn";
    private static final String G_FN2 = "ciwlan_fix_set_ciwlan_only_slot1";
    private static final String G_FN3 = "ciwlan_fix_force_qns_iwlan_fallback";

    private SharedPreferences prefs;
    private Switch fn1;
    private Switch fn2;
    private EditText plmn;
    private RadioGroup fn3;
    private boolean globalOk = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = getSharedPreferences(PREF_FILE, MODE_PRIVATE);

        fn1 = findViewById(R.id.fn1_switch);
        fn2 = findViewById(R.id.fn2_switch);
        plmn = findViewById(R.id.fn1_plmn);
        fn3 = findViewById(R.id.fn3_group);
        Button restore = findViewById(R.id.restore);

        fn1.setChecked(readInitialBool(G_FN1, K_FN1, false));
        fn2.setChecked(readInitialBool(G_FN2, K_FN2, true));
        plmn.setText(readInitialString(G_PLMN, K_PLMN, "99999"));
        String mode = normalizeFn3(readInitialString(G_FN3, K_FN3, "auto"));
        if ("on".equals(mode)) {
            fn3.check(R.id.fn3_on);
        } else if ("off".equals(mode)) {
            fn3.check(R.id.fn3_off);
        } else {
            fn3.check(R.id.fn3_auto);
        }

        fn1.setOnCheckedChangeListener((v, checked) -> persist());
        fn2.setOnCheckedChangeListener((v, checked) -> persist());
        fn3.setOnCheckedChangeListener((g, id) -> persist());
        plmn.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                persist();
            }
        });
        restore.setOnClickListener(this::onRestore);
        persist();
    }

    @Override
    protected void onPause() {
        super.onPause();
        persist();
    }

    private void onRestore(View v) {
        fn1.setChecked(false);
        fn2.setChecked(false);
        fn3.check(R.id.fn3_off);
        persist();
        Toast.makeText(this, R.string.restore_done, Toast.LENGTH_LONG).show();
    }

    private void persist() {
        String plmnValue = plmn.getText() == null ? "99999" : plmn.getText().toString().trim();
        if (plmnValue.isEmpty()) {
            plmnValue = "99999";
        }
        String fn3Value = "auto";
        int id = fn3.getCheckedRadioButtonId();
        if (id == R.id.fn3_on) {
            fn3Value = "on";
        } else if (id == R.id.fn3_off) {
            fn3Value = "off";
        }
        prefs.edit()
                .putBoolean(K_FN1, fn1.isChecked())
                .putBoolean(K_FN2, fn2.isChecked())
                .putString(K_PLMN, plmnValue)
                .putString(K_FN3, fn3Value)
                .apply();
        boolean ok = true;
        ok &= writeGlobal(G_FN1, fn1.isChecked() ? "1" : "0");
        ok &= writeGlobal(G_FN2, fn2.isChecked() ? "1" : "0");
        ok &= writeGlobal(G_PLMN, plmnValue);
        ok &= writeGlobal(G_FN3, fn3Value);
        if (ok != globalOk) {
            globalOk = ok;
            if (!ok) {
                Toast.makeText(this, R.string.grant_hint, Toast.LENGTH_LONG).show();
            }
        }
    }

    private static String normalizeFn3(String v) {
        if (v == null) {
            return "auto";
        }
        String s = v.trim().toLowerCase();
        if ("on".equals(s) || "off".equals(s) || "auto".equals(s)) {
            return s;
        }
        if ("1".equals(s) || "true".equals(s)) {
            return "on";
        }
        if ("0".equals(s) || "false".equals(s)) {
            return "off";
        }
        return "auto";
    }

    private boolean readInitialBool(String globalKey, String prefKey, boolean def) {
        String g = readGlobal(globalKey);
        if (g != null && !g.isEmpty()) {
            String s = g.trim();
            if ("1".equals(s) || "true".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s)) {
                return true;
            }
            if ("0".equals(s) || "false".equalsIgnoreCase(s) || "off".equalsIgnoreCase(s)) {
                return false;
            }
        }
        return prefs.getBoolean(prefKey, def);
    }

    private String readInitialString(String globalKey, String prefKey, String def) {
        String g = readGlobal(globalKey);
        if (g != null && !g.trim().isEmpty()) {
            return g.trim();
        }
        String local = prefs.getString(prefKey, def);
        return (local == null || local.trim().isEmpty()) ? def : local;
    }

    private String readGlobal(String key) {
        try {
            return Settings.Global.getString(getContentResolver(), key);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean writeGlobal(String key, String value) {
        try {
            return Settings.Global.putString(getContentResolver(), key, value);
        } catch (Throwable t) {
            return false;
        }
    }
}
