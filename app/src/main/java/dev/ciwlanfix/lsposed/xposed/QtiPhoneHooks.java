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

    static void installBooleanLoggers(ClassLoader cl) {
        hookBoolean(cl, "com.qti.extphone.ExtTelephonyManager", "isCiwlanAvailable");
        hookBoolean(cl, "com.qti.extphone.ExtTelephonyManager", "isEpdgOverCellularDataSupported");
        hookBoolean(cl, "com.qti.phone.ExtTelephonyServiceImpl", "isCiwlanAvailable");
        hookBoolean(cl, "com.qti.phone.ExtTelephonyServiceImpl", "isEpdgOverCellularDataSupported");
        hookBoolean(cl, "com.qti.phone.QtiRadioProxy", "isCiwlanAvailable");
        hookBoolean(cl, "com.qti.phone.QtiRadioProxy", "isEpdgOverCellularDataSupported");
        hookBoolean(cl, "com.qti.phone.QtiRadioAidl", "isCiwlanAvailable");
        hookBoolean(cl, "com.qti.phone.QtiRadioAidl", "isEpdgOverCellularDataSupported");
        hookCallbackBooleans(cl);
    }

    private static void hookBoolean(ClassLoader cl, String cls, String method) {
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
                        Object slotHint = null;
                        if (param.args != null && param.args.length > 0 && param.args[0] instanceof Integer) {
                            slotHint = param.args[0];
                        } else {
                            slotHint = Reflects.getFieldOrNull(param.thisObject, "mSlotId");
                        }
                        LogX.i(cls + "." + method + "(slotHint=" + slotHint + ")=" + param.getResult());
                    }
                });
            } catch (Throwable t) {
                LogX.w("hook " + cls + "." + method + ": " + t);
            }
        }
    }

    private static void hookCallbackBooleans(ClassLoader cl) {
        Class<?> listener = Reflects.findOrNull(cl, "com.qti.extphone.ExtPhoneCallbackListener");
        if (listener == null) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(listener, "onCiwlanAvailable", int.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            LogX.i("onCiwlanAvailable slot=" + param.args[0] + " available=" + param.args[1]);
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
                        LogX.i("[FN2] skip comparePreferences(slot=1) while enforcing ONLY/ONLY");
                        Class<?> rt = param.method != null ? param.method.getReturnType() : void.class;
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
