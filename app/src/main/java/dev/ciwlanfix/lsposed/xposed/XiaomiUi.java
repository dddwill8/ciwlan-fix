package dev.ciwlanfix.lsposed.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Replaces the two HyperCeiler phone/SystemUI bits this module actually needs:
 * show 通话辅助, and do not hide the VoWiFi status-bar icon.
 */
final class XiaomiUi {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private XiaomiUi() {}

    static void install(ClassLoader cl) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        hookVoiceLink(cl);
        hookVowifiIcon(cl);
        hookSimToggles(cl);
        LogX.i("[UI] Xiaomi 通话辅助 / VoWiFi icon hooks installed");
    }

    private static void hookVoiceLink(ClassLoader cl) {
        Class<?> utils = Reflects.findOrNull(cl, "com.android.phone.MiuiPhoneUtils");
        if (utils != null) {
            for (Method m : utils.getDeclaredMethods()) {
                String n = m.getName();
                if (!("isSupportVoiceLinkFeature".equals(n)
                        || n.toLowerCase().contains("voicelink"))) {
                    continue;
                }
                if (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(true);
                        }
                    });
                    LogX.i("[UI] hook " + utils.getName() + "." + n + " -> true");
                } catch (Throwable t) {
                    LogX.w("[UI] hook voice link " + n + ": " + t);
                }
            }
        }
        Class<?> cloud = Reflects.findOrNull(cl, "com.android.phone.CloudController.TelephonyCloudController");
        if (cloud != null) {
            try {
                Field f = cloud.getDeclaredField("CLOUD_VOICE_LINK_FEATURE_DISABLED");
                f.setAccessible(true);
                f.set(null, false);
                LogX.i("[UI] CLOUD_VOICE_LINK_FEATURE_DISABLED=false");
            } catch (Throwable t) {
                LogX.w("[UI] CLOUD_VOICE_LINK_FEATURE_DISABLED: " + t);
            }
        }
    }

    private static void hookVowifiIcon(ClassLoader cl) {
        Class<?> cfg = Reflects.findOrNull(cl,
                "com.miui.interfaces.IOperatorCustomizedPolicy$OperatorConfig");
        if (cfg == null) {
            return;
        }
        XC_MethodHook show = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                setBool(param.thisObject, "hideVowifi", false);
                setBool(param.thisObject, "hideVoWifi", false);
                setBool(param.thisObject, "hideVOWIFI", false);
            }
        };
        for (Constructor<?> ctor : cfg.getDeclaredConstructors()) {
            try {
                XposedBridge.hookMethod(ctor, show);
            } catch (Throwable t) {
                LogX.w("[UI] hook OperatorConfig ctor: " + t);
            }
        }
        LogX.i("[UI] OperatorConfig hideVowifi forced false");
    }

    private static void setBool(Object obj, String name, boolean value) {
        try {
            XposedHelpers.setBooleanField(obj, name, value);
        } catch (Throwable ignored) {
        }
    }

    /** Keep SIM-info switches in sync with what the module actually enabled. */
    private static void hookSimToggles(ClassLoader cl) {
        String[] prefs = new String[]{
                "androidx.preference.TwoStatePreference",
                "androidx.preference.SwitchPreference",
                "androidx.preference.SwitchPreferenceCompat",
                "miuix.preference.SwitchPreferenceCompat",
                "android.preference.TwoStatePreference",
                "com.android.settingslib.PrimarySwitchPreference",
        };
        for (String n : prefs) {
            Class<?> c = Reflects.findOrNull(cl, n);
            if (c == null) {
                continue;
            }
            hookBoolIfKey(c, "isChecked");
        }
        String[] controllers = new String[]{
                "com.android.settings.network.telephony.BackupCallingPreferenceController",
                "com.android.settings.network.telephony.CrossSimCallingPreferenceController",
                "com.android.settings.network.telephony.WifiCallingPreferenceController",
                "com.android.settings.network.telephony.Enhanced4gLtePreferenceController",
        };
        for (String n : controllers) {
            Class<?> c = Reflects.findOrNull(cl, n);
            if (c == null) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!"isChecked".equals(m.getName()) && !"isCallStateModifyAllowed".equals(m.getName())) {
                    continue;
                }
                if (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if ("isChecked".equals(m.getName())) {
                                param.setResult(true);
                            }
                        }
                    });
                    LogX.i("[UI] hook " + n + "." + m.getName());
                } catch (Throwable t) {
                    LogX.w("[UI] hook " + n + "." + m.getName() + ": " + t);
                }
            }
        }
    }

    private static void hookBoolIfKey(Class<?> c, String method) {
        for (Method m : c.getDeclaredMethods()) {
            if (!method.equals(m.getName())) {
                continue;
            }
            if (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) {
                continue;
            }
            if (m.getParameterCount() != 0) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isTargetToggle(param.thisObject)) {
                            return;
                        }
                        if ("isChecked".equals(method)) {
                            param.setResult(true);
                        }
                    }
                });
                LogX.i("[UI] hook " + c.getName() + "." + method);
            } catch (Throwable t) {
                LogX.w("[UI] hook " + c.getName() + "." + method + ": " + t);
            }
        }
    }

    private static boolean isTargetToggle(Object pref) {
        if (pref == null) {
            return false;
        }
        String key = "";
        try {
            Object k = XposedHelpers.callMethod(pref, "getKey");
            if (k != null) {
                key = k.toString().toLowerCase();
            }
        } catch (Throwable ignored) {
        }
        String title = "";
        try {
            Object t = XposedHelpers.callMethod(pref, "getTitle");
            if (t != null) {
                title = t.toString();
            }
        } catch (Throwable ignored) {
        }
        String blob = key + " " + title.toLowerCase();
        return blob.contains("voice_link") || blob.contains("voicelink")
                || blob.contains("cross_sim") || blob.contains("crosssim")
                || blob.contains("backup_call") || blob.contains("backupcalling")
                || blob.contains("通话辅助")
                || blob.contains("wfc") || blob.contains("wifi_call") || blob.contains("wifi calling")
                || blob.contains("wlan通话") || blob.contains("wlan 通话") || blob.contains("wi-fi通话")
                || blob.contains("wi-fi 通话") || blob.contains("vowifi");
    }
}
