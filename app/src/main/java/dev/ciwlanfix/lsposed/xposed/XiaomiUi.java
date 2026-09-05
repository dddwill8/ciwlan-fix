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
}
