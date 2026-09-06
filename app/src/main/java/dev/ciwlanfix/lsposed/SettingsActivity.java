package dev.ciwlanfix.lsposed;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import dev.ciwlanfix.lsposed.xposed.Const;

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
    private RadioGroup plmnGroup;
    private boolean globalOk = true;
    private boolean suppressPlmnCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = getSharedPreferences(PREF_FILE, MODE_PRIVATE);

        master = findViewById(R.id.master_switch);
        plmnGroup = findViewById(R.id.plmn_group);
        Button refresh = findViewById(R.id.refresh_status);
        Button copyAdb = findViewById(R.id.copy_adb);
        Button exportLog = findViewById(R.id.export_log);

        master.setChecked(readMasterOn());
        selectPlmn(readPlmn());
        master.setOnCheckedChangeListener((v, checked) -> {
            persist();
            if (!checked) {
                Toast.makeText(this, R.string.restore_done, Toast.LENGTH_LONG).show();
            }
            refreshStatus();
        });
        plmnGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (suppressPlmnCallback) {
                return;
            }
            persist();
            if (master.isChecked()) {
                writeGlobal(Const.G_FN1_LAST_APPLY_MS, "0");
                writeGlobal(Const.G_FN1_APPLIED_PLMN, "");
                Toast.makeText(this, getString(R.string.plmn_saved, Const.plmnLabel(selectedPlmn())),
                        Toast.LENGTH_SHORT).show();
            }
            refreshStatus();
        });
        refresh.setOnClickListener(v -> refreshStatus());
        copyAdb.setOnClickListener(v -> copyAdbCmd());
        exportLog.setOnClickListener(v -> exportLogs());
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

    private String readPlmn() {
        return Const.normalizePlmn(readInitialString(G_PLMN, K_PLMN, Const.DEFAULT_PLMN));
    }

    private void selectPlmn(String plmn) {
        suppressPlmnCallback = true;
        int id = R.id.plmn_ct;
        if (Const.PLMN_CBN.equals(plmn)) {
            id = R.id.plmn_cbn;
        } else if (Const.PLMN_UNICOM.equals(plmn)) {
            id = R.id.plmn_unicom;
        }
        plmnGroup.check(id);
        suppressPlmnCallback = false;
    }

    private String selectedPlmn() {
        int id = plmnGroup.getCheckedRadioButtonId();
        if (id == R.id.plmn_unicom) {
            return Const.PLMN_UNICOM;
        }
        if (id == R.id.plmn_cbn) {
            return Const.PLMN_CBN;
        }
        return Const.PLMN_CT;
    }

    private void refreshStatus() {
        TextView plain = findViewById(R.id.runtime_status);
        String fn2Done = dash(readGlobal("ciwlan_fix_fn2_done"));
        String cross = dash(readGlobal("cross_sim_call_1"));
        String crossSub = dash(readGlobal("ciwlan_fix_cross_sim_sub1"));
        String wfcUser = dash(readGlobal("ciwlan_fix_wfc_user"));
        String appliedRaw = dash(readGlobal(Const.G_FN1_APPLIED_PLMN));
        if ("—".equals(fn2Done) && "—".equals(crossSub) && "—".equals(wfcUser)) {
            plain.setText(R.string.status_empty);
            return;
        }
        String plmn = selectedPlmn();
        String appliedLine = "—".equals(appliedRaw)
                ? "尚未下发"
                : Const.plmnLabel(appliedRaw) + "（" + Const.normalizePlmn(appliedRaw) + "）";
        plain.setText("总开关：" + (master.isChecked() ? "开" : "关（正在还原）")
                + "\n卡 2 选网：" + Const.plmnLabel(plmn) + "（" + plmn + "）"
                + "\n已下发选网：" + appliedLine
                + "\nWi-Fi 通话：" + yn(wfcUser)
                + "\n跨卡通话：" + yn(crossSub)
                + "\n系统通话辅助：" + yn(cross));
    }

    private void copyAdbCmd() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("adb", Const.ADB_LOGCAT));
        }
        Toast.makeText(this, R.string.adb_copied, Toast.LENGTH_SHORT).show();
    }

    private void exportLogs() {
        Toast.makeText(this, R.string.exporting, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String body = buildLogDump();
                String name = "ciwlan-fix-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                        .format(new Date()) + ".txt";
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    throw new IllegalStateException("MediaStore insert returned null");
                }
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) {
                        throw new IllegalStateException("openOutputStream returned null");
                    }
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.export_ok, name),
                        Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.export_fail, t.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String buildLogDump() {
        StringBuilder sb = new StringBuilder();
        sb.append("CIWLAN Fix log dump\n");
        sb.append("time=").append(new Date()).append('\n');
        try {
            sb.append("pkg=").append(getPackageName())
                    .append(" version=").append(getPackageManager()
                            .getPackageInfo(getPackageName(), 0).versionName)
                    .append('\n');
        } catch (Throwable t) {
            sb.append("pkg version: ").append(t).append('\n');
        }
        sb.append("selectedPlmn=").append(selectedPlmn()).append('\n');
        sb.append("master=").append(master.isChecked()).append('\n');
        sb.append('\n').append("=== settings global (ciwlan) ===\n");
        String globals = shell(new String[]{"su", "-c", "settings list global"});
        for (String line : globals.split("\n")) {
            String lower = line.toLowerCase(Locale.US);
            if (lower.contains("ciwlan") || lower.contains("cross_sim")) {
                sb.append(line).append('\n');
            }
        }
        sb.append('\n').append("=== logcat CIWLAN_FIX ===\n");
        String log = shell(new String[]{"su", "-c", "logcat -d -t 4000 -s CIWLAN_FIX:D"});
        if (log.trim().isEmpty()) {
            log = shell(new String[]{"logcat", "-d", "-t", "4000", "-s", "CIWLAN_FIX:D"});
        }
        sb.append(log);
        return sb.toString();
    }

    private static String shell(String[] cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return out + "cmd timeout\n";
            }
            return out.toString();
        } catch (Throwable t) {
            return "cmd failed: " + t + "\n";
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
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
        String plmn = selectedPlmn();
        prefs.edit()
                .putBoolean(K_FN1, on)
                .putBoolean(K_FN2, on)
                .putString(K_PLMN, plmn)
                .putString(K_FN3, fn3Value)
                .apply();
        boolean ok = true;
        ok &= writeGlobal(G_FN1, on ? "1" : "0");
        ok &= writeGlobal(G_FN2, on ? "1" : "0");
        ok &= writeGlobal(G_PLMN, plmn);
        ok &= writeGlobal(G_FN3, fn3Value);
        if (on) {
            ok &= writeGlobal("cross_sim_call_1", "1");
        }
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
