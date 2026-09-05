package dev.ciwlanfix.lsposed.xposed;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.SubscriptionManager;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * CN HyperOS hides VoWiFi unless the user dials *#*#869434#*#* (force_enable_vowifi).
 * Replicate that for slot 1 when the module switch is on, then turn on the user WFC toggles.
 */
final class WfcUnlock {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static long lastForceLogMs;
    private static final String PREF_FORCE = "force_enable_vowifi";
    private static final String KEY_WFC_AVAILABLE = "carrier_wfc_ims_available_bool";
    private static final String KEY_WFC_AVAILABLE_DEV = "carrier_wfc_ims_available_bool_develop";
    private static final String KEY_CROSS_SIM = "carrier_cross_sim_ims_available_bool";
    private static final String KEY_DEFAULT_WFC = "carrier_default_wfc_ims_enabled_bool";
    private static final String KEY_DEFAULT_WFC_ROAM = "carrier_default_wfc_ims_roaming_enabled_bool";
    private static final String KEY_SKIP_ENTITLEMENT = "imsserviceentitlement.skip_wfc_activation_bool";
    private static final String KEY_DEFAULT_ENTITLEMENT = "imsserviceentitlement.default_service_entitlement_status_bool";
    private static final String KEY_IMS_PROVISIONING = "imsserviceentitlement.ims_provisioning_bool";
    private static final String KEY_SHOW_VOWIFI_WEBVIEW = "imsserviceentitlement.show_vowifi_webview_bool";
    private static final String KEY_VOLTE_OVERRIDE_WFC = "carrier_volte_override_wfc_provisioning_bool";
    private static final String KEY_SHOW_WFC_ICON = "show_wifi_calling_icon_in_status_bar_bool";
    private static Context appCtx;

    private WfcUnlock() {}

    static void attachContext(Context ctx) {
        if (ctx == null) {
            return;
        }
        appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        enableSlot1("attach");
    }

    static void install(ClassLoader cl) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        hookCarrierConfig(cl);
        hookImsManager(cl);
        hookMiuiHelpers(cl);
        hookEntitlement(cl);
        LogX.i("[WFC] hooks installed");
    }

    static void enableSlot1(String why) {
        Context ctx = appCtx;
        if (ctx == null || !masterOn()) {
            return;
        }
        writeForcePref(ctx, why);
        callImsManagerForce(ctx, why);
        enableUserWfc(ctx, why);
        if (Prefs.crossSimCall1(ctx) != 1) {
            Prefs.writeGlobal(ctx, Const.CROSS_SIM_CALL_1, "1");
            LogX.i("[WFC] wrote cross_sim_call_1=1 (" + why + ")");
        }
        CrossSimSlot1.sync(ctx, "wfc/" + why);
    }

    private static boolean masterOn() {
        if (appCtx == null) {
            return true;
        }
        return Prefs.fn1On(appCtx) || Prefs.fn2On(appCtx);
    }

    private static void writeForcePref(Context ctx, String why) {
        if (!Const.PKG_ANDROID_PHONE.equals(ctx.getPackageName())) {
            return;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(
                    ctx.getPackageName() + "_preferences", Context.MODE_PRIVATE);
            if (sp.getBoolean(PREF_FORCE, false)) {
                Prefs.writeGlobal(ctx, Const.G_WFC_FORCE, "1");
                return;
            }
            sp.edit().putBoolean(PREF_FORCE, true).apply();
            Prefs.writeGlobal(ctx, Const.G_WFC_FORCE, "1");
            LogX.i("[WFC] force_enable_vowifi=true (" + why + ") — same as *#*#869434#*#*");
        } catch (Throwable t) {
            LogX.w("[WFC] write force_enable_vowifi: " + t);
        }
    }

    private static void callImsManagerForce(Context ctx, String why) {
        try {
            Class<?> ims = Reflects.findOrNull(ctx.getClassLoader(), "com.android.ims.ImsManager");
            if (ims == null) {
                ims = Class.forName("com.android.ims.ImsManager");
            }
            Object mgr = null;
            try {
                mgr = Reflects.callStatic(ims, "getInstance", ctx, Const.SLOT_TARGET);
            } catch (Throwable ignored) {
            }
            if (mgr == null) {
                return;
            }
            quietCall(mgr, "setVoWifiForceEnabled", Boolean.TRUE);
            quietCall(mgr, "setWfcSetting", Boolean.TRUE);
            quietCall(mgr, "setWfcRoamingSetting", Boolean.TRUE);
            quietCall(mgr, "setWfcMode", Const.WFC_WIFI_PREFERRED);
            quietCall(mgr, "setWfcRoamingMode", Const.WFC_WIFI_PREFERRED);
            LogX.i("[WFC] ImsManager slot1 force/user WFC applied (" + why + ")");
        } catch (Throwable t) {
            LogX.w("[WFC] ImsManager force: " + t);
        }
    }

    private static void enableUserWfc(Context ctx, String why) {
        int subId = Slot.subIdSlot1(ctx);
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            LogX.skip("[WFC] no subId for slot 1 (" + why + ")");
            return;
        }
        Object mgr = mmTel(ctx, subId);
        if (mgr == null) {
            LogX.skip("[WFC] ImsMmTelManager missing (" + why + ")");
            return;
        }
        try {
            quietCall(mgr, "setVoWiFiSettingEnabled", Boolean.TRUE);
            quietCall(mgr, "setVoWiFiRoamingSettingEnabled", Boolean.TRUE);
            quietCall(mgr, "setVoWiFiModeSetting", Const.WFC_WIFI_PREFERRED);
            quietCall(mgr, "setVoWiFiRoamingModeSetting", Const.WFC_WIFI_PREFERRED);
            Boolean user = (Boolean) Reflects.callOrNull(mgr, "isVoWiFiSettingEnabled");
            Boolean roam = (Boolean) Reflects.callOrNull(mgr, "isVoWiFiRoamingSettingEnabled");
            Prefs.writeGlobal(ctx, Const.G_WFC_USER, Boolean.TRUE.equals(user) ? "1" : "0");
            Prefs.writeGlobal(ctx, Const.G_WFC_ROAM, Boolean.TRUE.equals(roam) ? "1" : "0");
            LogX.i("[WFC] ImsMmTelManager subId=" + subId
                    + " user=" + user + " roam=" + roam + " why=" + why);
        } catch (Throwable t) {
            LogX.e("[WFC] enable user WFC failed (" + why + ")", t);
        }
    }

    private static Object mmTel(Context ctx, int subId) {
        try {
            Class<?> cls = Class.forName("android.telephony.ims.ImsMmTelManager");
            try {
                return cls.getMethod("createForSubscriptionId", int.class).invoke(null, subId);
            } catch (NoSuchMethodException ignored) {
            }
            Class<?> imsCls = Class.forName("android.telephony.ims.ImsManager");
            Object ims = ctx.getSystemService(imsCls);
            if (ims != null) {
                return Reflects.callOrNull(ims, "getImsMmTelManager", subId);
            }
        } catch (Throwable t) {
            LogX.w("[WFC] ImsMmTelManager: " + t);
        }
        return null;
    }

    private static void hookCarrierConfig(ClassLoader cl) {
        Class<?> ccm = Reflects.findOrNull(cl, "android.telephony.CarrierConfigManager");
        if (ccm != null) {
            for (Method m : ccm.getDeclaredMethods()) {
                if (m.getReturnType() == null) {
                    continue;
                }
                String rt = m.getReturnType().getName();
                if (!rt.contains("PersistableBundle") && !rt.contains("Bundle")) {
                    continue;
                }
                if (!m.getName().startsWith("getConfig")) {
                    continue;
                }
                hookBundleReturn(m, "CarrierConfigManager." + m.getName());
            }
        }
        Class<?> loader = Reflects.findOrNull(cl, "com.android.phone.CarrierConfigLoader");
        if (loader != null) {
            for (Method m : loader.getDeclaredMethods()) {
                if (m.getReturnType() == null) {
                    continue;
                }
                String rt = m.getReturnType().getName();
                if (!rt.contains("PersistableBundle") && !rt.contains("Bundle")) {
                    continue;
                }
                if (!m.getName().toLowerCase().contains("config")) {
                    continue;
                }
                hookBundleReturn(m, "CarrierConfigLoader." + m.getName());
            }
        }
    }

    private static void hookBundleReturn(Method m, String label) {
        try {
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!masterOn()) {
                        return;
                    }
                    int slot = slotOfConfigCall(m.getName(), param.args);
                    if (slot != Const.SLOT_TARGET) {
                        return;
                    }
                    Object bundle = param.getResult();
                    if (bundle == null) {
                        return;
                    }
                    patchBundle(bundle);
                }
            });
            LogX.i("[WFC] hook " + label);
        } catch (Throwable t) {
            LogX.w("[WFC] hook " + label + ": " + t);
        }
    }

    private static void patchBundle(Object bundle) {
        putBool(bundle, KEY_WFC_AVAILABLE, true);
        putBool(bundle, KEY_WFC_AVAILABLE_DEV, true);
        putBool(bundle, KEY_CROSS_SIM, true);
        putBool(bundle, KEY_DEFAULT_WFC, true);
        putBool(bundle, KEY_DEFAULT_WFC_ROAM, true);
        putBool(bundle, KEY_SKIP_ENTITLEMENT, true);
        putBool(bundle, KEY_DEFAULT_ENTITLEMENT, true);
        putBool(bundle, KEY_IMS_PROVISIONING, false);
        putBool(bundle, KEY_SHOW_VOWIFI_WEBVIEW, false);
        putBool(bundle, KEY_VOLTE_OVERRIDE_WFC, false);
        putBool(bundle, KEY_SHOW_WFC_ICON, true);
    }

    private static void putBool(Object bundle, String key, boolean value) {
        try {
            XposedHelpers.callMethod(bundle, "putBoolean", key, value);
        } catch (Throwable ignored) {
        }
    }

    private static void hookImsManager(ClassLoader cl) {
        String[] names = new String[]{
                "com.android.ims.ImsManager",
                "android.telephony.ims.ImsManager",
                "android.telephony.ims.ImsMmTelManager",
        };
        for (String n : names) {
            Class<?> c = Reflects.findOrNull(cl, n);
            if (c == null) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                if (!(name.contains("Wfc") || name.contains("VoWifi") || name.contains("VoWiFi")
                        || name.contains("WifiCalling"))) {
                    continue;
                }
                Class<?> rt = m.getReturnType();
                if (rt != boolean.class && rt != Boolean.class) {
                    continue;
                }
                hookImsBoolean(m, n + "." + name);
            }
        }
    }

    private static void hookImsBoolean(Method m, String label) {
        try {
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!masterOn()) {
                        return;
                    }
                    int slot = phoneIdOf(param);
                    if (slot != Const.SLOT_TARGET && slot != -1) {
                        return;
                    }
                    String name = m.getName();
                    boolean force = name.contains("Force")
                            || name.contains("Platform")
                            || name.contains("ByUser")
                            || name.contains("Roaming")
                            || name.contains("Provision")
                            || "isVoWiFiEnabled".equals(name)
                            || "isVoWifiForceEnabled".equals(name)
                            || "isWfcEnabledByPlatform".equals(name)
                            || "isWfcEnabledByUser".equals(name)
                            || "isWfcRoamingEnabledByUser".equals(name)
                            || "isWfcProvisionedOnDevice".equals(name)
                            || "isVoWiFiSettingEnabled".equals(name)
                            || "isVoWiFiRoamingSettingEnabled".equals(name);
                    if (!force) {
                        return;
                    }
                    if (!Boolean.TRUE.equals(param.getResult())) {
                        param.setResult(true);
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - lastForceLogMs > 10_000L) {
                            lastForceLogMs = now;
                            LogX.i("[WFC] " + label + " slot=" + slot + " forced true");
                        }
                    }
                }
            });
            LogX.i("[WFC] hook " + label);
        } catch (Throwable t) {
            LogX.w("[WFC] hook " + label + ": " + t);
        }
    }

    private static void hookEntitlement(ClassLoader cl) {
        String[] names = new String[]{
                "com.android.imsserviceentitlement.ImsServiceEntitlement",
                "com.android.imsserviceentitlement.WfcActivationActivity",
                "com.android.imsserviceentitlement.entitlement.EntitlementApi",
                "com.android.imsserviceentitlement.entitlement.EntitlementResult",
                "android.telephony.ims.ImsManager",
                "com.android.ims.ImsManager",
        };
        for (String n : names) {
            Class<?> c = Reflects.findOrNull(cl, n);
            if (c == null) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                String lower = name.toLowerCase();
                if (!(lower.contains("entitlement") || lower.contains("activation")
                        || lower.contains("provision") || "isWfcProvisionedOnDevice".equals(name))) {
                    continue;
                }
                Class<?> rt = m.getReturnType();
                if (rt != boolean.class && rt != Boolean.class) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!masterOn()) {
                                return;
                            }
                            if (!Boolean.TRUE.equals(param.getResult())) {
                                param.setResult(true);
                            }
                        }
                    });
                    LogX.i("[WFC] hook entitlement " + n + "." + name);
                } catch (Throwable t) {
                    LogX.w("[WFC] hook entitlement " + n + "." + name + ": " + t);
                }
            }
        }
    }

    private static void hookMiuiHelpers(ClassLoader cl) {
        String[] names = new String[]{
                "com.android.phone.ImsUtil",
                "com.android.phone.MiuiImsPhoneUtils",
                "miui.telephony.TelephonyManagerEx",
        };
        for (String n : names) {
            Class<?> c = Reflects.findOrNull(cl, n);
            if (c == null) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                String lower = name.toLowerCase();
                if (!(lower.contains("wfc") || lower.contains("vowifi") || lower.contains("wificall"))) {
                    continue;
                }
                Class<?> rt = m.getReturnType();
                if (rt != boolean.class && rt != Boolean.class) {
                    continue;
                }
                hookImsBoolean(m, n + "." + name);
            }
        }
    }

    private static int phoneIdOf(XC_MethodHook.MethodHookParam param) {
        if (param.thisObject != null) {
            Object v = Reflects.getFieldOrNull(param.thisObject, "mPhoneId");
            if (v instanceof Integer) {
                return (Integer) v;
            }
            try {
                Object r = XposedHelpers.callMethod(param.thisObject, "getPhoneId");
                if (r instanceof Integer) {
                    return (Integer) r;
                }
            } catch (Throwable ignored) {
            }
            v = Reflects.getFieldOrNull(param.thisObject, "mSubId");
            if (v instanceof Integer) {
                return slotFromSubId((Integer) v);
            }
            try {
                Object r = XposedHelpers.callMethod(param.thisObject, "getSubscriptionId");
                if (r instanceof Integer) {
                    return slotFromSubId((Integer) r);
                }
            } catch (Throwable ignored) {
            }
            try {
                Object r = XposedHelpers.callMethod(param.thisObject, "getSubId");
                if (r instanceof Integer) {
                    return slotFromSubId((Integer) r);
                }
            } catch (Throwable ignored) {
            }
        }
        return slotFromSubIdArgs(param.args);
    }

    private static int slotOfConfigCall(String methodName, Object[] args) {
        String lower = methodName == null ? "" : methodName.toLowerCase();
        if (lower.contains("phoneid") || lower.contains("slot")) {
            if (args != null) {
                for (Object a : args) {
                    if (a instanceof Integer) {
                        int v = (Integer) a;
                        if (v == Const.SLOT_TARGET || v == Const.SLOT_FORBIDDEN) {
                            return v;
                        }
                    }
                }
            }
            return -1;
        }
        return slotFromSubIdArgs(args);
    }

    private static int slotFromSubIdArgs(Object[] args) {
        if (args == null) {
            return -1;
        }
        for (Object a : args) {
            if (a instanceof Integer) {
                int slot = slotFromSubId((Integer) a);
                if (slot == Const.SLOT_TARGET || slot == Const.SLOT_FORBIDDEN) {
                    return slot;
                }
            }
        }
        return -1;
    }

    private static void quietCall(Object target, String name, Object... args) {
        try {
            Reflects.call(target, name, args);
        } catch (Throwable ignored) {
        }
    }

    private static int slotFromSubId(int subId) {
        try {
            int slot = SubscriptionManager.getSlotIndex(subId);
            if (slot >= 0) {
                return slot;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
