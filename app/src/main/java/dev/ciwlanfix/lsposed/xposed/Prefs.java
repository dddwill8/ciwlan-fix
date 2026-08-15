package dev.ciwlanfix.lsposed.xposed;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import de.robv.android.xposed.XSharedPreferences;

final class Prefs {
    private static XSharedPreferences xsp;
    private static long lastReloadMs;

    private Prefs() {}

    static SharedPreferences appPrefs(Context ctx) {
        return ctx.getSharedPreferences(Const.PREF_FILE, Context.MODE_PRIVATE);
    }

    static synchronized XSharedPreferences xsp() {
        if (xsp == null) {
            xsp = new XSharedPreferences(Const.MODULE_PKG, Const.PREF_FILE);
            xsp.makeWorldReadable();
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastReloadMs > 400L) {
            xsp.reload();
            lastReloadMs = now;
        }
        return xsp;
    }

    static boolean fn1On(Context ctx) {
        return readBool(ctx, Const.G_FN1, Const.K_FN1, false);
    }

    static String plmn(Context ctx) {
        String g = readGlobal(ctx, Const.G_PLMN);
        if (g != null && !g.isEmpty()) {
            return g.trim();
        }
        try {
            String v = xsp().getString(Const.K_PLMN, Const.DEFAULT_PLMN);
            return (v == null || v.trim().isEmpty()) ? Const.DEFAULT_PLMN : v.trim();
        } catch (Throwable t) {
            return Const.DEFAULT_PLMN;
        }
    }

    static boolean fn2On(Context ctx) {
        return readBool(ctx, Const.G_FN2, Const.K_FN2, true);
    }

    static String fn3Mode(Context ctx) {
        String g = readGlobal(ctx, Const.G_FN3);
        if (g != null && !g.isEmpty()) {
            return normalizeFn3(g);
        }
        try {
            return normalizeFn3(xsp().getString(Const.K_FN3, Const.FN3_AUTO));
        } catch (Throwable t) {
            return Const.FN3_AUTO;
        }
    }

    static boolean fn3ShouldRun(Context ctx) {
        if (ctx == null) {
            return false;
        }
        String mode = fn3Mode(ctx);
        if (Const.FN3_OFF.equals(mode)) {
            return false;
        }
        if (crossSimCall1(ctx) != 1) {
            return false;
        }
        if (WifiAssoc.associated(ctx)) {
            return false;
        }
        return Const.FN3_ON.equals(mode) || fn2On(ctx);
    }

    static boolean writeGlobal(Context ctx, String key, String value) {
        if (ctx == null) {
            return false;
        }
        try {
            return Settings.Global.putString(ctx.getContentResolver(), key, value);
        } catch (Throwable t) {
            LogX.w("Settings.Global.putString(" + key + ") failed: " + t);
            return false;
        }
    }

    static String readGlobal(Context ctx, String key) {
        if (ctx == null) {
            return null;
        }
        try {
            return Settings.Global.getString(ctx.getContentResolver(), key);
        } catch (Throwable t) {
            return null;
        }
    }

    static int readGlobalInt(Context ctx, String key, int def) {
        String v = readGlobal(ctx, key);
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static boolean readBool(Context ctx, String globalKey, String prefKey, boolean def) {
        String g = readGlobal(ctx, globalKey);
        if (g != null && !g.isEmpty()) {
            return parseBool(g, def);
        }
        try {
            return xsp().getBoolean(prefKey, def);
        } catch (Throwable t) {
            return def;
        }
    }

    static boolean parseBool(String v, boolean def) {
        if (v == null) {
            return def;
        }
        String s = v.trim();
        if ("1".equals(s) || "true".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s)) {
            return true;
        }
        if ("0".equals(s) || "false".equalsIgnoreCase(s) || "off".equalsIgnoreCase(s)) {
            return false;
        }
        return def;
    }

    static String normalizeFn3(String v) {
        if (v == null) {
            return Const.FN3_AUTO;
        }
        String s = v.trim().toLowerCase();
        if (Const.FN3_ON.equals(s) || Const.FN3_OFF.equals(s) || Const.FN3_AUTO.equals(s)) {
            return s;
        }
        if ("1".equals(s) || "true".equals(s)) {
            return Const.FN3_ON;
        }
        if ("0".equals(s) || "false".equals(s)) {
            return Const.FN3_OFF;
        }
        return Const.FN3_AUTO;
    }

    static int crossSimCall1(Context ctx) {
        if (ctx == null) {
            return 0;
        }
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), Const.CROSS_SIM_CALL_1, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    static boolean airplaneOn(Context ctx) {
        if (ctx == null) {
            return false;
        }
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    static ContentResolver cr(Context ctx) {
        return ctx == null ? null : ctx.getContentResolver();
    }
}
