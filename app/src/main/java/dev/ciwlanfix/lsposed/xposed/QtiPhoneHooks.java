package dev.ciwlanfix.lsposed.xposed;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.Context;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

final class QtiPhoneHooks {
    private static final Object LOCK = new Object();
    private static volatile ExtPhoneGateway gw;
    private static volatile Object pendingImpl;
    private static volatile Object pendingCtrl;
    private static volatile Boolean rawCiwlanSlot1;
    private static final java.util.concurrent.atomic.AtomicBoolean FN3_RX =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private QtiPhoneHooks() {}

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        try {
            Context existing = AndroidAppHelper.currentApplication();
            if (existing != null) {
                LogX.i("com.qti.phone Application already exists uid=" + android.os.Process.myUid());
                ExtPhoneGateway.startOnce(cl, existing);
            }
        } catch (Throwable t) {
            LogX.w("currentApplication: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Application)) {
                        return;
                    }
                    Application app = (Application) param.thisObject;
                    Context ctx = app.getApplicationContext();
                    if (ctx == null) {
                        ctx = app;
                    }
                    LogX.i("com.qti.phone Application.onCreate uid=" + android.os.Process.myUid());
                    ExtPhoneGateway.startOnce(cl, ctx);
                }
            });
        } catch (Throwable t) {
            LogX.w("hook Application.onCreate: " + t);
        }
        hookServiceOnCreate(cl);
        hookImplCtors(cl);
    }

    private static void hookServiceOnCreate(ClassLoader cl) {
        Class<?> svc = Reflects.findOrNull(cl, "com.qti.phone.ExtTelephonyService");
        if (svc == null) {
            LogX.w("ExtTelephonyService not found");
            return;
        }
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.thisObject == null || !svc.isInstance(param.thisObject)) {
                        return;
                    }
                    Object raw = XposedHelpers.callMethod(param.thisObject, "getApplicationContext");
                    if (!(raw instanceof Context)) {
                        return;
                    }
                    LogX.i("ExtTelephonyService.onCreate");
                    ExtPhoneGateway.startOnce(cl, (Context) raw);
                } catch (Throwable t) {
                    LogX.w("ExtTelephonyService.onCreate hook: " + t);
                }
            }
        };
        boolean hookedDeclared = false;
        for (Method m : svc.getDeclaredMethods()) {
            if (!"onCreate".equals(m.getName()) || m.getParameterCount() != 0) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, hook);
                hookedDeclared = true;
            } catch (Throwable t) {
                LogX.w("hook ExtTelephonyService.onCreate: " + t);
            }
        }
        if (hookedDeclared) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(svc, "onCreate", hook);
        } catch (Throwable t) {
            LogX.w("hook ExtTelephonyService.onCreate: " + t);
        }
    }

    private static void hookImplCtors(ClassLoader cl) {
        Class<?> impl = Reflects.findOrNull(cl, "com.qti.phone.ExtTelephonyServiceImpl");
        if (impl == null) {
            LogX.w("ExtTelephonyServiceImpl not found");
            return;
        }
        for (java.lang.reflect.Constructor<?> ctor : impl.getDeclaredConstructors()) {
            try {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        LogX.i("ExtTelephonyServiceImpl constructed " + ArraysCtor(param.args));
                        Object implObj = param.thisObject;
                        Object ctrl = Reflects.getFieldOrNull(implObj, "mQtiCiwlanModePreferenceController");
                        synchronized (LOCK) {
                            pendingImpl = implObj;
                            pendingCtrl = ctrl;
                            if (gw != null) {
                                gw.attachImpl(implObj);
                                if (ctrl != null) {
                                    gw.attachController(ctrl);
                                }
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                LogX.w("hook ExtTelephonyServiceImpl ctor: " + t);
            }
        }
    }

    private static String ArraysCtor(Object[] args) {
        if (args == null) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(args[i] == null ? "null" : args[i].getClass().getName());
        }
        return sb.append("]").toString();
    }

    static Boolean rawCiwlanSlot1() {
        return rawCiwlanSlot1;
    }

    static void registerFn3StatusReceiver(Context ctx) {
        if (ctx == null || !FN3_RX.compareAndSet(false, true)) {
            return;
        }
        android.content.IntentFilter filter = new android.content.IntentFilter(Const.ACTION_FN3_STATUS);
        android.content.BroadcastReceiver rx = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, android.content.Intent intent) {
                if (intent == null) {
                    return;
                }
                writeExtra(c, intent, "qns", Const.G_QNS_SLOT1_IMS_PREF);
                writeExtra(c, intent, "latched", Const.G_FN3_LATCHED);
                writeExtra(c, intent, "wlan", Const.G_FN3_WLAN_REG);
                writeExtra(c, intent, "setup", Const.G_FN3_SETUP);
            }
        };
        try {
            ctx.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED);
            LogX.i("FN3 status receiver registered");
        } catch (Throwable t) {
            try {
                ctx.registerReceiver(rx, filter);
                LogX.i("FN3 status receiver registered (legacy)");
            } catch (Throwable t2) {
                LogX.w("FN3 status receiver: " + t2);
            }
        }
    }

    private static void writeExtra(Context ctx, android.content.Intent intent, String extra, String key) {
        String v = intent.getStringExtra(extra);
        if (v != null && !v.isEmpty()) {
            Prefs.writeGlobal(ctx, key, v);
        }
    }

    static void installBooleanLoggers(ClassLoader cl, Context ctx) {
        hookBoolean(cl, ctx, "com.qti.extphone.ExtTelephonyManager", "isCiwlanAvailable");
        hookBoolean(cl, ctx, "com.qti.extphone.ExtTelephonyManager", "isEpdgOverCellularDataSupported");
        hookBoolean(cl, ctx, "com.qti.phone.ExtTelephonyServiceImpl", "isCiwlanAvailable");
        hookBoolean(cl, ctx, "com.qti.phone.ExtTelephonyServiceImpl", "isEpdgOverCellularDataSupported");
        hookBoolean(cl, ctx, "com.qti.phone.QtiRadioProxy", "isCiwlanAvailable");
        hookBoolean(cl, ctx, "com.qti.phone.QtiRadioProxy", "isEpdgOverCellularDataSupported");
        hookBoolean(cl, ctx, "com.qti.phone.QtiRadioAidl", "isCiwlanAvailable");
        hookBoolean(cl, ctx, "com.qti.phone.QtiRadioAidl", "isEpdgOverCellularDataSupported");
        hookCallbackBooleans(cl, ctx);
    }

    private static void hookBoolean(ClassLoader cl, Context ctx, String cls, String method) {
        Class<?> c = Reflects.findOrNull(cl, cls);
        if (c == null) {
            return;
        }
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(method)) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int slot = slotHintOf(param);
                        Object raw = param.getResult();
                        if ("isCiwlanAvailable".equals(method) && slot == Const.SLOT_TARGET) {
                            if (raw instanceof Boolean) {
                                rawCiwlanSlot1 = (Boolean) raw;
                            }
                            if (Prefs.fn3ShouldRun(ctx) && !Boolean.TRUE.equals(raw)) {
                                param.setResult(true);
                                LogX.i(cls + "." + method + "(slotHint=" + slot + ") raw=" + raw
                                        + " forced=true (runtime-only)");
                                return;
                            }
                        }
                        LogX.i(cls + "." + method + "(slotHint=" + slot + ")=" + param.getResult());
                    }
                });
            } catch (Throwable t) {
                LogX.w("hook " + cls + "." + method + ": " + t);
            }
        }
    }

    private static int slotHintOf(XC_MethodHook.MethodHookParam param) {
        if (param.args != null && param.args.length > 0 && param.args[0] instanceof Integer) {
            return (Integer) param.args[0];
        }
        Object v = Reflects.getFieldOrNull(param.thisObject, "mSlotId");
        if (v instanceof Integer) {
            return (Integer) v;
        }
        return -1;
    }

    private static void hookCallbackBooleans(ClassLoader cl, Context ctx) {
        Class<?> listener = Reflects.findOrNull(cl, "com.qti.extphone.ExtPhoneCallbackListener");
        if (listener == null) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(listener, "onCiwlanAvailable", int.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int slot = param.args[0] instanceof Integer ? (Integer) param.args[0] : -1;
                            boolean raw = Boolean.TRUE.equals(param.args[1]);
                            if (slot == Const.SLOT_TARGET) {
                                rawCiwlanSlot1 = raw;
                            }
                            if (slot == Const.SLOT_TARGET && !raw && Prefs.fn3ShouldRun(ctx)) {
                                param.args[1] = true;
                                LogX.i("onCiwlanAvailable slot=" + slot + " raw=" + raw + " forced=true");
                                return;
                            }
                            LogX.i("onCiwlanAvailable slot=" + slot + " available=" + param.args[1]);
                        }
                    });
        } catch (Throwable t) {
            LogX.w("hook onCiwlanAvailable: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(listener, "onEpdgOverCellularDataSupported", int.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            LogX.i("onEpdgOverCellularDataSupported slot=" + param.args[0] + " supported=" + param.args[1]);
                        }
                    });
        } catch (Throwable t) {
            LogX.w("hook onEpdgOverCellularDataSupported: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(listener, "setCiwlanModeUserPreferenceResponse",
                    int.class,
                    Reflects.find(cl, "com.qti.extphone.Token"),
                    Reflects.find(cl, "com.qti.extphone.Status"),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            LogX.i("setCiwlanModeUserPreferenceResponse slot=" + param.args[0]
                                    + " token=" + param.args[1] + " status=" + param.args[2]);
                        }
                    });
        } catch (Throwable t) {
            LogX.w("hook setCiwlanModeUserPreferenceResponse: " + t);
        }
    }

    static void installComparePreferencesGuard(ClassLoader cl, Context ctx) {
        Class<?> ctrl = Reflects.findOrNull(cl, "com.qti.phone.QtiCiwlanModePreferenceController");
        if (ctrl == null) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(ctrl, "comparePreferences", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Integer)) {
                        return;
                    }
                    int slot = (Integer) param.args[0];
                    if (slot == Const.SLOT_FORBIDDEN) {
                        return;
                    }
                    if (slot == Const.SLOT_TARGET && Prefs.fn2On(ctx)) {
                        LogX.i("[FN2] skip comparePreferences(slot=1) while FN2 on");
                        Class<?> rt = void.class;
                        if (param.method instanceof Method) {
                            rt = ((Method) param.method).getReturnType();
                        }
                        if (rt == boolean.class || rt == Boolean.class) {
                            param.setResult(false);
                        } else if (rt == int.class || rt == Integer.class) {
                            param.setResult(0);
                        } else {
                            param.setResult(null);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            LogX.w("hook comparePreferences: " + t);
        }
    }

    static void bindGateway(ExtPhoneGateway gateway) {
        synchronized (LOCK) {
            gw = gateway;
            if (pendingImpl != null) {
                gateway.attachImpl(pendingImpl);
            }
            if (pendingCtrl != null) {
                gateway.attachController(pendingCtrl);
            }
        }
    }
}
