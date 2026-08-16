package dev.ciwlanfix.lsposed;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
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
    private Switch master;
    private boolean globalOk = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = getSharedPreferences(PREF_FILE, MODE_PRIVATE);

        master = findViewById(R.id.master_switch);
        Button refresh = findViewById(R.id.refresh_status);

        master.setChecked(readMasterOn());
        master.setOnCheckedChangeListener((v, checked) -> {
            persist();
            if (!checked) {
                Toast.makeText(this, R.string.restore_done, Toast.LENGTH_LONG).show();
            }
            refreshStatus();
        });
        refresh.setOnClickListener(v -> refreshStatus());
        persist();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        persist();
    }

    private boolean readMasterOn() {
        boolean fn2 = readInitialBool(G_FN2, K_FN2, true);
        String fn3 = normalizeFn3(readInitialString(G_FN3, K_FN3, "auto"));
        return fn2 && !"off".equals(fn3);
    }

    private void refreshStatus() {
        TextView plain = findViewById(R.id.runtime_status);
        TextView pro = findViewById(R.id.pro_log);
        String fn2Done = dash(readGlobal("ciwlan_fix_fn2_done"));
        String avail = dash(readGlobal("ciwlan_fix_slot1_ciwlan_available"));
        String epdg = dash(readGlobal("ciwlan_fix_slot1_epdg_over_cellular"));
        String qns = dash(readGlobal("ciwlan_fix_qns_slot1_ims_pref"));
        String fn3 = dash(readGlobal("ciwlan_fix_fn3_latched"));
        String wlan = dash(readGlobal("ciwlan_fix_fn3_wlan_reg"));
        String setup = dash(readGlobal("ciwlan_fix_fn3_setup"));
        String cross = dash(readGlobal("cross_sim_call_1"));
        String crossSub = dash(readGlobal("ciwlan_fix_cross_sim_sub1"));

        if ("—".equals(fn2Done) && "—".equals(avail) && "—".equals(crossSub)) {
            plain.setText(R.string.status_empty);
        } else {
            plain.setText("总开关：" + (master.isChecked() ? "开" : "关（正在还原）")
                    + "\n系统通话辅助：" + yn(cross)
                    + "\n卡 2 跨卡通话：" + yn(crossSub)
                    + "\n卡 2 CIWLAN 已下发：" + yn(fn2Done)
                    + "\nmodem CIWLAN 可用：" + yn(avail)
                    + "\nePDG over cellular：" + yn(epdg));
        }

        pro.setText("cross_sim_call_1=" + cross + "  sub1=" + crossSub
                + "\nfn2_done=" + fn2Done
                + "\nisCiwlanAvailable(1) raw=" + avail
                + "\nisEpdgOverCellular(1)=" + epdg
                + "\nQNS slot1 IMS pref=" + qns + "  (3=EUTRAN, 5=IWLAN)"
                + "\nfn3_latched=" + fn3
                + "\nfn3_wlan_reg=" + wlan
                + "\nfn3_setup=" + setup);
    }

    private static String yn(String v) {
        if (v == null || "—".equals(v)) {
            return "未知";
        }
        String s = v.trim();
        if ("1".equals(s) || "true".equalsIgnoreCase(s)) {
            return "是";
        }
        if ("0".equals(s) || "false".equalsIgnoreCase(s)) {
            return "否";
        }
        return s;
    }

    private static String dash(String v) {
        return (v == null || v.trim().isEmpty()) ? "—" : v.trim();
    }

    private void persist() {
        boolean on = master.isChecked();
        String fn3Value = on ? "auto" : "off";
        prefs.edit()
                .putBoolean(K_FN1, on)
                .putBoolean(K_FN2, on)
                .putString(K_PLMN, "99999")
                .putString(K_FN3, fn3Value)
                .apply();
        boolean ok = true;
        ok &= writeGlobal(G_FN1, on ? "1" : "0");
        ok &= writeGlobal(G_FN2, on ? "1" : "0");
        ok &= writeGlobal(G_PLMN, "99999");
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
