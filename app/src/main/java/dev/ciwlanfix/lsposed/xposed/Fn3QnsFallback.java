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
                    injectHandler.postDelayed(this, 5000L);
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
            latched = true;
            if (appCtx != null) {
                Prefs.writeGlobal(appCtx, Const.G_FN3_LATCHED, "1");
                Prefs.writeGlobal(appCtx, Const.G_QNS_SLOT1_IMS_PREF, "5");
            }
            LogX.i("[FN3] injected slot1 IMS/APN mask networks=[5] via " + provider.getClass().getName());
        } catch (Throwable t) {
            LogX.e("[FN3] inject updateQualifiedNetworkTypes failed", t);
        }
    }

    private static boolean shouldInject() {
        if (appCtx == null) {
            return false;
        }
        String mode = Prefs.fn3Mode(appCtx);
        if (Const.FN3_OFF.equals(mode)) {
            return false;
        }
        if (Prefs.crossSimCall1(appCtx) != 1) {
            logSame("[FN3] inject skip: cross_sim_call_1 != 1");
            return false;
        }
        if (WifiAssoc.associated(appCtx)) {
            logSame("[FN3] inject skip: Wi-Fi associated");
            return false;
        }
        if (Const.FN3_ON.equals(mode)) {
            return true;
        }
        if (!Prefs.fn2On(appCtx)) {
            return false;
        }
        String availRaw = Prefs.readGlobal(appCtx, Const.G_SLOT1_CIWLAN_AVAILABLE);
        if (availRaw != null && Prefs.parseBool(availRaw, false)) {
            logSame("[FN3] inject skip: isCiwlanAvailable(1)=true");
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
                Prefs.writeGlobal(appCtx, Const.G_QNS_SLOT1_IMS_PREF, String.valueOf(before.get(0)));
                if (before.get(0) == Const.ACCESS_NETWORK_EUTRAN) {
                    seenQns3 = true;
                    Prefs.writeGlobal(appCtx, Const.G_SEEN_QNS3, "1");
                }
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
        if (appCtx == null) {
            return false;
        }
        String mode = Prefs.fn3Mode(appCtx);
        if (Const.FN3_OFF.equals(mode)) {
            logSame("[FN3] off, pass-through");
            return false;
        }
        if (Prefs.crossSimCall1(appCtx) != 1) {
            logSame("[FN3] skip: cross_sim_call_1 != 1");
            return false;
        }
        if (WifiAssoc.associated(appCtx)) {
            logSame("[FN3] skip: Wi-Fi associated");
            latched = false;
            Prefs.writeGlobal(appCtx, Const.G_FN3_LATCHED, "0");
            return false;
        }
        if (Const.FN3_ON.equals(mode)) {
            return true;
        }
        if (!Prefs.fn2On(appCtx)) {
            logSame("[FN3] auto skip: Function 2 is off");
            latched = false;
            Prefs.writeGlobal(appCtx, Const.G_FN3_LATCHED, "0");
            return false;
        }
        String availRaw = Prefs.readGlobal(appCtx, Const.G_SLOT1_CIWLAN_AVAILABLE);
        boolean availKnownTrue = availRaw != null && Prefs.parseBool(availRaw, false);
        boolean seen3 = seenQns3 || Prefs.readGlobalInt(appCtx, Const.G_SEEN_QNS3, 0) == 1;
        boolean nowLatched = latched || Prefs.readGlobalInt(appCtx, Const.G_FN3_LATCHED, 0) == 1;
        if (availKnownTrue) {
            logSame("[FN3] auto skip: isCiwlanAvailable(1)=true, QNS fallback not needed");
            return false;
        }
        if (nowLatched || seen3) {
            if (!latched) {
                latched = true;
                Prefs.writeGlobal(appCtx, Const.G_FN3_LATCHED, "1");
                LogX.i("[FN3] auto latch: FN2 on, isCiwlanAvailable(1) not true, QNS slot1 IMS was 3");
            }
            return true;
        }
        logSame("[FN3] auto wait: ciwlanAvailableRaw=" + availRaw + " seenPref3=" + seen3);
        return false;
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
}
