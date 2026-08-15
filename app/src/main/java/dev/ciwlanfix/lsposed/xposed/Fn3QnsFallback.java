package dev.ciwlanfix.lsposed.xposed;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class Fn3QnsFallback {
    private static final java.util.concurrent.atomic.AtomicBoolean INSTALLED =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final ConcurrentHashMap<Integer, Object> PROVIDERS = new ConcurrentHashMap<>();
    private static final int APN_MASK_SLOT0_STYLE = 2646;
    private static Context appCtx;
    private static Handler injectHandler;
    private static long lastSameLogMs;
    private static volatile boolean seenQns3;
    private static volatile boolean latched;

    private Fn3QnsFallback() {}

    static void attachContext(Context ctx) {
        if (ctx == null) {
            return;
        }
        appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        startInjector();
    }

    static void install(ClassLoader cl, Context ctx) {
        if (ctx != null) {
            attachContext(ctx);
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            hookFrameworkProvider(cl);
        } catch (Throwable t) {
            LogX.e("[FN3] hook framework NetworkAvailabilityProvider failed", t);
        }
        String[] names = new String[]{
                "vendor.qti.iwlan.QualifiedNetworksServiceImpl",
                "vendor.qti.iwlan.QualifiedNetworksServiceImpl$NetworkAvailabilityProviderImpl",
                "vendor.qti.iwlan.NetworkAvailabilityProviderImpl",
                "com.qualcomm.qti.iwlan.QualifiedNetworksServiceImpl",
                "com.qualcomm.qti.iwlan.QualifiedNetworksServiceImpl$NetworkAvailabilityProviderImpl",
                "com.qti.iwlan.QualifiedNetworksServiceImpl",
        };
        for (String n : names) {
            Class<?> c = Reflects.findOrNull(cl, n);
            if (c == null) {
                LogX.i("[FN3] class not present: " + n);
                continue;
            }
            LogX.i("[FN3] found " + n);
            try {
                Reflects.dumpMethods(c, "qualified");
                hookUpdateMethods(c);
                hookUpdateQualifiedNetworks(c);
                hookProviderLifecycle(c);
            } catch (Throwable t) {
                LogX.e("[FN3] hook " + n + " failed", t);
            }
        }
        hookIwlanPreferred(cl);
        hookWlanRegistration(cl);
        hookSetupDataCall(cl);
        startInjector();
    }

    private static void hookIwlanPreferred(ClassLoader cl) {
        String[] names = new String[]{
                "vendor.qti.iwlan.IWlanNetworkService",
                "vendor.qti.iwlan.IWlanNetworkService$IWlanNetworkServiceProvider",
                "vendor.qti.iwlan.IWlanNetworkServiceProvider",
                "com.qualcomm.qti.iwlan.IWlanNetworkService",
                "com.qti.iwlan.IWlanNetworkService",
        };
        for (String n : names) {
            Class<?> c = Reflects.findOrNull(cl, n);
            if (c == null) {
                continue;
            }
            LogX.i("[FN3] found " + n);
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                String lower = name.toLowerCase();
                if (!(lower.contains("iwlanpreferred") || "isIwlanPreferred".equals(name)
                        || "getIwlanPreferred".equals(name))) {
                    continue;
                }
                if (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) {
                    continue;
                }
                LogX.i("[FN3] hook preferred " + n + "." + name);
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            LogX.i("[FN3] " + n + "." + name + " raw=" + param.getResult());
                            if (appCtx != null && !Const.FN3_OFF.equals(Prefs.fn3Mode(appCtx))
                                    && Prefs.fn2On(appCtx)
                                    && Prefs.crossSimCall1(appCtx) == 1
                                    && !WifiAssoc.associated(appCtx)) {
                                param.setResult(true);
                                LogX.i("[FN3] " + name + " forced true (runtime-only)");
                            }
                        }
                    });
                } catch (Throwable t) {
                    LogX.w("[FN3] hook preferred " + n + "." + name + " failed: " + t);
                }
            }
        }
    }

    private static void hookFrameworkProvider(ClassLoader cl) {
        Class<?> nap = Reflects.findOrNull(cl,
                "android.telephony.data.QualifiedNetworksService$NetworkAvailabilityProvider");
        if (nap == null) {
            LogX.skip("[FN3] NetworkAvailabilityProvider not in this process classloader");
            return;
        }
        hookUpdateMethods(nap);
    }

    private static void hookProviderLifecycle(Class<?> cls) {
        if (cls.getName().endsWith("QualifiedNetworksServiceImpl")) {
            try {
                XposedHelpers.findAndHookMethod(cls, "onCreateNetworkAvailabilityProvider", int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                int slot = (Integer) param.args[0];
                                rememberProvider(slot, param.getResult());
                                LogX.i("[FN3] onCreateNetworkAvailabilityProvider slot=" + slot
                                        + " provider=" + param.getResult());
                            }
                        });
            } catch (Throwable t) {
                LogX.w("[FN3] hook onCreateNetworkAvailabilityProvider: " + t);
            }
        }
        if (cls.getName().contains("NetworkAvailabilityProviderImpl")) {
            for (java.lang.reflect.Constructor<?> ctor : cls.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int slot = slotOf(param.thisObject);
                        rememberProvider(slot, param.thisObject);
                        LogX.i("[FN3] provider ctor slot=" + slot + " " + param.thisObject);
                    }
                });
            }
        }
    }

    private static void hookUpdateQualifiedNetworks(Class<?> cls) {
        for (Method m : cls.getDeclaredMethods()) {
            if (!"updateQualifiedNetworks".equals(m.getName())) {
                continue;
            }
            LogX.i("[FN3] hook " + cls.getName() + "." + m.getName() + Arrays.toString(m.getParameterTypes()));
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int slot = slotOf(param.thisObject);
                        LogX.i("[FN3] updateQualifiedNetworks slot=" + slot + " raw=" + param.args[0]);
                        rememberProvider(slot, param.thisObject);
                    }
                });
            } catch (Throwable t) {
                LogX.w("[FN3] hook updateQualifiedNetworks failed: " + t);
            }
        }
    }

    private static void rememberProvider(int slot, Object provider) {
        if (provider == null || slot < 0) {
            return;
        }
        PROVIDERS.put(slot, provider);
        LogX.i("[FN3] remembered provider slot=" + slot + " total=" + PROVIDERS.keySet());
    }

    private static synchronized void startInjector() {
        if (injectHandler != null) {
            return;
        }
        HandlerThread ht = new HandlerThread("CIWLAN_FIX_FN3");
        ht.start();
        injectHandler = new Handler(ht.getLooper());
        injectHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    injectSlot1IfNeeded();
                } catch (Throwable t) {
                    LogX.e("[FN3] inject failed", t);
                }
                if (injectHandler != null) {
                    injectHandler.postDelayed(this, 8000L);
                }
            }
        });
    }

    private static void injectSlot1IfNeeded() {
        if (!shouldInject()) {
            return;
        }
        Object provider = PROVIDERS.get(Const.SLOT_TARGET);
        if (provider == null) {
            logSame("[FN3] inject wait: no slot1 NetworkAvailabilityProvider yet keys=" + PROVIDERS.keySet());
            return;
        }
        List<Integer> iwlan = new ArrayList<>(Collections.singletonList(Const.ACCESS_NETWORK_IWLAN));
        try {
            XposedHelpers.callMethod(provider, "updateQualifiedNetworkTypes", Const.APN_TYPE_IMS, iwlan);
            XposedHelpers.callMethod(provider, "updateQualifiedNetworkTypes", APN_MASK_SLOT0_STYLE,
                    new ArrayList<>(iwlan));
            try {
                XposedHelpers.callMethod(provider, "reconnectQualifiedNetworkType",
                        Const.APN_TYPE_IMS, Const.ACCESS_NETWORK_IWLAN);
            } catch (Throwable t) {
                LogX.w("[FN3] reconnectQualifiedNetworkType: " + t);
            }
            latched = true;
            publishStatus("5", "1", "home", null);
            LogX.i("[FN3] injected slot1 IMS/APN mask networks=[5] via " + provider.getClass().getName());
        } catch (Throwable t) {
            LogX.e("[FN3] inject updateQualifiedNetworkTypes failed", t);
        }
    }

    private static boolean shouldInject() {
        if (!Prefs.fn3ShouldRun(appCtx)) {
            logSame("[FN3] inject skip: fn3ShouldRun=false");
            return false;
        }
        return true;
    }

    private static void hookUpdateMethods(Class<?> cls) {
        for (Method m : cls.getDeclaredMethods()) {
            if (!"updateQualifiedNetworkTypes".equals(m.getName())) {
                continue;
            }
            LogX.i("[FN3] hook " + cls.getName() + "." + m.getName() + Arrays.toString(m.getParameterTypes()));
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        onUpdate(param);
                    }
                });
            } catch (Throwable t) {
                LogX.w("[FN3] hook " + cls.getName() + "." + m.getName() + " failed: " + t);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void onUpdate(XC_MethodHook.MethodHookParam param) {
        try {
            int slot = slotOf(param.thisObject);
            rememberProvider(slot, param.thisObject);
            int apn = apnOf(param.args);
            Object networksArg = networksOf(param.args);
            List<Integer> before = toIntList(networksArg);
            LogX.i("[FN3] before slot=" + slot + " apnTypes=" + apn + " networks=" + before);

            if (slot == Const.SLOT_TARGET && (apn & Const.APN_TYPE_IMS) != 0 && !before.isEmpty()) {
                if (before.get(0) == Const.ACCESS_NETWORK_EUTRAN) {
                    seenQns3 = true;
                }
                publishStatus(String.valueOf(before.get(0)), latched ? "1" : null, null, null);
            }

            if (!shouldRewrite(slot, apn)) {
                return;
            }

            List<Integer> after = new ArrayList<>();
            after.add(Const.ACCESS_NETWORK_IWLAN);
            for (Integer n : before) {
                if (n != null && n != Const.ACCESS_NETWORK_IWLAN) {
                    after.add(n);
                }
            }
            writeNetworks(param.args, networksArg, after);
            LogX.i("[FN3] after  slot=" + slot + " apnTypes=" + apn + " networks=" + after
                    + " (IMS forced IWLAN=5, runtime-only)");
        } catch (Throwable t) {
            LogX.e("[FN3] updateQualifiedNetworkTypes hook failed", t);
        }
    }

    private static boolean shouldRewrite(int slot, int apn) {
        if (slot != Const.SLOT_TARGET) {
            return false;
        }
        if ((apn & Const.APN_TYPE_IMS) == 0) {
            return false;
        }
        if (!Prefs.fn3ShouldRun(appCtx)) {
            logSame("[FN3] rewrite skip: fn3ShouldRun=false");
            latched = false;
            return false;
        }
        if (!latched) {
            latched = true;
            LogX.i("[FN3] latch: rewriting slot1 IMS to IWLAN=5");
        }
        return true;
    }

    private static void logSame(String msg) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSameLogMs > 10_000L) {
            LogX.i(msg);
            lastSameLogMs = now;
        }
    }

    private static int slotOf(Object provider) {
        if (provider == null) {
            return -1;
        }
        Object v = Reflects.getFieldOrNull(provider, "mSlotIndex");
        if (v instanceof Integer) {
            return (Integer) v;
        }
        try {
            Object r = XposedHelpers.callMethod(provider, "getSlotIndex");
            if (r instanceof Integer) {
                return (Integer) r;
            }
        } catch (Throwable ignored) {
        }
        v = Reflects.getFieldOrNull(provider, "mSlotId");
        if (v instanceof Integer) {
            return (Integer) v;
        }
        v = Reflects.getFieldOrNull(provider, "mPhoneId");
        if (v instanceof Integer) {
            return (Integer) v;
        }
        LogX.w("[FN3] cannot resolve slot on " + provider.getClass().getName());
        return -1;
    }

    private static int apnOf(Object[] args) {
        if (args == null) {
            return 0;
        }
        for (Object a : args) {
            if (a instanceof Integer) {
                return (Integer) a;
            }
        }
        return 0;
    }

    private static Object networksOf(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object a : args) {
            if (a instanceof List || a instanceof int[]) {
                return a;
            }
        }
        return null;
    }

    private static List<Integer> toIntList(Object raw) {
        List<Integer> out = new ArrayList<>();
        if (raw instanceof int[]) {
            for (int n : (int[]) raw) {
                out.add(n);
            }
        } else if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o instanceof Integer) {
                    out.add((Integer) o);
                } else if (o instanceof Number) {
                    out.add(((Number) o).intValue());
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void writeNetworks(Object[] args, Object original, List<Integer> after) {
        if (original instanceof int[]) {
            int[] arr = new int[after.size()];
            for (int i = 0; i < after.size(); i++) {
                arr[i] = after.get(i);
            }
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof int[]) {
                    args[i] = arr;
                }
            }
            return;
        }
        if (original instanceof List) {
            List<Object> list = (List<Object>) original;
            try {
                list.clear();
                list.addAll(after);
            } catch (Throwable t) {
                ArrayList<Integer> copy = new ArrayList<>(after);
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof List) {
                        args[i] = copy;
                    }
                }
            }
        }
    }

    private static void hookWlanRegistration(ClassLoader cl) {
        String[] names = new String[]{
                "android.telephony.NetworkService$NetworkServiceProvider",
                "vendor.qti.iwlan.IWlanNetworkService$IWlanNetworkServiceProvider",
                "vendor.qti.iwlan.IWlanAidlClient",
        };
        for (String n : names) {
            Class<?> c = Reflects.findOrNull(cl, n);
            if (c == null) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                if ("getNetworkRegistrationInfo".equals(name)
                        || (m.getReturnType() != null
                        && m.getReturnType().getName().contains("NetworkRegistrationInfo"))) {
                    hookGetNri(m);
                } else if ("getDataRegistrationStateResponse".equals(name)
                        || "networkRegistrationStateChangeIndication".equals(name)) {
                    hookRegStateResult(m);
                }
            }
        }
    }

    private static void hookGetNri(Method m) {
        try {
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!Prefs.fn3ShouldRun(appCtx)) {
                        return;
                    }
                    int slot = slotOf(param.thisObject);
                    if (slot != Const.SLOT_TARGET) {
                        return;
                    }
                    Integer domain = null;
                    Integer transport = null;
                    if (param.args != null) {
                        for (Object a : param.args) {
                            if (a instanceof Integer) {
                                if (domain == null) {
                                    domain = (Integer) a;
                                } else {
                                    transport = (Integer) a;
                                }
                            }
                        }
                    }
                    if (domain != null && domain != 2) {
                        return;
                    }
                    if (transport != null && transport != 2) {
                        return;
                    }
                    Object current = param.getResult();
                    if (current != null && transport == null) {
                        try {
                            Object t = XposedHelpers.callMethod(current, "getTransportType");
                            if (t instanceof Integer && (Integer) t != 2) {
                                return;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    Object fake = homeWlanNri(param.thisObject.getClass().getClassLoader());
                    if (fake != null) {
                        param.setResult(fake);
                        publishStatus(null, "1", "home", null);
                        logSame("[FN3] getNetworkRegistrationInfo slot1 WLAN forced HOME/IWLAN");
                    }
                }
            });
            LogX.i("[FN3] hook " + m.getDeclaringClass().getName() + "." + m.getName());
        } catch (Throwable t) {
            LogX.w("[FN3] hook getNetworkRegistrationInfo: " + t);
        }
    }

    private static void hookRegStateResult(Method m) {
        try {
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!Prefs.fn3ShouldRun(appCtx) || param.args == null) {
                        return;
                    }
                    int slot = slotOf(param.thisObject);
                    if (slot != Const.SLOT_TARGET && slot != -1) {
                        return;
                    }
                    if (slot == -1) {
                        return;
                    }
                    for (Object a : param.args) {
                        if (a != null && Reflects.findField(a.getClass(), "regState") != null) {
                            Object old = Reflects.getFieldOrNull(a, "regState");
                            Reflects.setField(a, "regState", 1);
                            logSame("[FN3] HAL IWlanDataRegStateResult.regState " + old + " -> 1 (HOME)");
                            publishStatus(null, "1", "home", null);
                        }
                    }
                }
            });
            LogX.i("[FN3] hook " + m.getDeclaringClass().getName() + "." + m.getName());
        } catch (Throwable t) {
            LogX.w("[FN3] hook " + m.getName() + ": " + t);
        }
    }

    private static void hookSetupDataCall(ClassLoader cl) {
        String[] names = new String[]{
                "vendor.qti.iwlan.IWlanDataService$IWlanDataServiceProvider",
                "vendor.qti.iwlan.IWlanDataService",
                "android.telephony.data.DataService$DataServiceProvider",
        };
        for (String n : names) {
            Class<?> c = Reflects.findOrNull(cl, n);
            if (c == null) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!"setupDataCall".equals(m.getName())) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int slot = slotOf(param.thisObject);
                            LogX.i("[FN3] setupDataCall slot=" + slot
                                    + " args=" + Arrays.toString(param.args));
                            if (slot == Const.SLOT_TARGET) {
                                publishStatus("5", "1", "home", "called");
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            int slot = slotOf(param.thisObject);
                            if (slot == Const.SLOT_TARGET) {
                                LogX.i("[FN3] setupDataCall slot1 returned " + param.getResult());
                            }
                        }
                    });
                    LogX.i("[FN3] hook " + n + ".setupDataCall" + Arrays.toString(m.getParameterTypes()));
                } catch (Throwable t) {
                    LogX.w("[FN3] hook setupDataCall: " + t);
                }
            }
        }
    }

    private static Object homeWlanNri(ClassLoader cl) {
        try {
            Class<?> builder = Reflects.find(cl, "android.telephony.NetworkRegistrationInfo$Builder");
            Object b = XposedHelpers.newInstance(builder);
            XposedHelpers.callMethod(b, "setDomain", 2);
            XposedHelpers.callMethod(b, "setTransportType", 2);
            XposedHelpers.callMethod(b, "setRegistrationState", 1);
            XposedHelpers.callMethod(b, "setAccessNetworkTechnology", 18);
            try {
                XposedHelpers.callMethod(b, "setAvailableServices", Arrays.asList(1, 2, 3));
            } catch (Throwable ignored) {
            }
            return XposedHelpers.callMethod(b, "build");
        } catch (Throwable t) {
            LogX.w("[FN3] build HOME WLAN NRI: " + t);
            return null;
        }
    }

    private static void publishStatus(String qns, String latchedVal, String wlan, String setup) {
        if (appCtx == null) {
            return;
        }
        try {
            android.content.Intent i = new android.content.Intent(Const.ACTION_FN3_STATUS);
            i.setPackage(Const.PKG_QTI_PHONE);
            if (qns != null) {
                i.putExtra("qns", qns);
            }
            if (latchedVal != null) {
                i.putExtra("latched", latchedVal);
            }
            if (wlan != null) {
                i.putExtra("wlan", wlan);
            }
            if (setup != null) {
                i.putExtra("setup", setup);
            }
            appCtx.sendBroadcast(i);
        } catch (Throwable t) {
            LogX.w("[FN3] publish status: " + t);
        }
    }
}
