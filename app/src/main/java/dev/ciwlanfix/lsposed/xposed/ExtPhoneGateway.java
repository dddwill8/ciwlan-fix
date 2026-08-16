package dev.ciwlanfix.lsposed.xposed;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

final class ExtPhoneGateway {
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private final ClassLoader cl;
    private final Context ctx;
    private final Handler handler;

    private Object etm;
    private Object listener;
    private Object listenerCb;
    private Object client;
    private volatile boolean connected;
    private volatile boolean onConnectedRan;

    private volatile Object implInstance;
    private volatile Object controllerInstance;

    ExtPhoneGateway(ClassLoader cl, Context ctx) {
        this.cl = cl;
        Context app = ctx.getApplicationContext();
        this.ctx = app != null ? app : ctx;
        HandlerThread ht = new HandlerThread("CIWLAN_FIX");
        ht.start();
        this.handler = new Handler(ht.getLooper());
    }

    static void startOnce(ClassLoader cl, Context ctx) {
        if (!STARTED.compareAndSet(false, true)) {
            LogX.i("ExtPhoneGateway already started in this process");
            return;
        }
        if (cl == null || ctx == null) {
            STARTED.set(false);
            LogX.w("ExtPhoneGateway.startOnce skipped: classLoader or context is null");
            return;
        }
        ExtPhoneGateway created = new ExtPhoneGateway(cl, ctx);
        QtiPhoneHooks.bindGateway(created);
        created.start();
    }

    void attachImpl(Object impl) {
        this.implInstance = impl;
    }

    void attachController(Object controller) {
        this.controllerInstance = controller;
    }

    Context context() {
        return ctx;
    }

    ClassLoader classLoader() {
        return cl;
    }

    Handler handler() {
        return handler;
    }

    Object etm() {
        return etm;
    }

    Object client() {
        return client;
    }

    boolean connected() {
        return connected;
    }

    private void start() {
        handler.post(this::connect);
        handler.postDelayed(this::pollConnected, 1500L);
        QtiPhoneHooks.installBooleanLoggers(cl, ctx);
        QtiPhoneHooks.installComparePreferencesGuard(cl, ctx);
        QtiPhoneHooks.registerFn3StatusReceiver(ctx);
        CrossSimSlot1.sync(ctx, "qti-start");
        handler.postDelayed(() -> CrossSimSlot1.sync(ctx, "qti-start-retry"), 400L);
        handler.postDelayed(() -> CrossSimSlot1.sync(ctx, "qti-start-retry2"), 3000L);
        Fn1ForceOos.install(this);
        handler.postDelayed(() -> Fn2CiwlanPref.installWatcher(this), 800L);
    }

    private void connect() {
        try {
            Class<?> etmCls = Reflects.find(cl, "com.qti.extphone.ExtTelephonyManager");
            etm = Reflects.callStatic(etmCls, "getInstance", ctx);
            LogX.i("ExtTelephonyManager.getInstance ok etm=" + etm);

            Class<?> sc = Reflects.find(cl, "com.qti.extphone.ServiceCallback");
            Object cb;
            if (sc.isInterface()) {
                cb = Proxy.newProxyInstance(cl, new Class[]{sc}, new ServiceCb());
            } else {
                LogX.skip("ServiceCallback is not an interface; will poll isServiceConnected");
                cb = null;
            }
            if (cb != null) {
                Object r = Reflects.call(etm, "connectService", cb);
                LogX.i("connectService returned " + r);
            }
            if (Boolean.TRUE.equals(Reflects.callOrNull(etm, "isServiceConnected"))) {
                onConnected();
            }
        } catch (Throwable t) {
            LogX.e("connect ExtTelephonyManager failed", t);
        }
    }

    private void pollConnected() {
        try {
            if (etm != null && Boolean.TRUE.equals(Reflects.callOrNull(etm, "isServiceConnected"))) {
                onConnected();
                return;
            }
        } catch (Throwable t) {
            LogX.e("poll isServiceConnected", t);
        }
        handler.postDelayed(this::pollConnected, 2000L);
    }

    private synchronized void onConnected() {
        if (onConnectedRan) {
            connected = true;
            return;
        }
        connected = true;
        onConnectedRan = true;
        LogX.i("ExtTelephonyService onConnected");
        try {
            dumpBothSlots("onConnected");
            ensureClient();
            CrossSimSlot1.sync(ctx, "onConnected");
            Fn2CiwlanPref.onServiceReady(this);
            Fn1ForceOos.onServiceReady(this);
        } catch (Throwable t) {
            LogX.e("onConnected work failed", t);
        }
    }

    private void ensureClient() {
        if (client != null) {
            return;
        }
        try {
            Class<?> listenerCls = Reflects.find(cl, "com.qti.extphone.ExtPhoneCallbackListener");
            listener = Reflects.newInstance(listenerCls);
            listenerCb = Reflects.getFieldOrNull(listener, "mCallback");
            LogX.i("ExtPhoneCallbackListener created callback=" + listenerCb);

            if (listenerCb != null) {
                try {
                    client = Reflects.call(etm, "registerCallback", Const.PKG_QTI_PHONE, listenerCb);
                    LogX.i("registerCallback(com.qti.phone, IExtPhoneCallback) client=" + client);
                } catch (Throwable t) {
                    LogX.w("registerCallback(IExtPhoneCallback) failed: " + t);
                }
            }
            if (client == null) {
                int[] events = new int[]{0, 2, 16, 32, 33, 48, 49, 50};
                client = Reflects.call(etm, "registerCallbackWithEvents", Const.PKG_QTI_PHONE, listener, events);
                LogX.i("registerCallbackWithEvents client=" + client);
            }
        } catch (Throwable t) {
            LogX.e("registerCallback failed", t);
        }
        if (client == null) {
            client = stealExistingClient();
            if (client != null) {
                LogX.i("reused existing QTI Client=" + client);
            } else {
                LogX.skip("no Client; setCiwlanModeUserPreference cannot be called");
            }
        }
    }

    private Object stealExistingClient() {
        try {
            if (controllerInstance == null && implInstance != null) {
                controllerInstance = Reflects.getFieldOrNull(implInstance, "mQtiCiwlanModePreferenceController");
            }
            if (controllerInstance != null) {
                Object c = Reflects.getFieldOrNull(controllerInstance, "mClient");
                if (c != null) {
                    return c;
                }
            }
        } catch (Throwable t) {
            LogX.w("steal Client from QtiCiwlanModePreferenceController failed: " + t);
        }
        return null;
    }

    void dumpBothSlots(String why) {
        LogX.i("===== query " + why + " =====");
        if (etm == null) {
            LogX.skip("dump: ExtTelephonyManager is null");
            return;
        }
        try {
            Object f103 = Reflects.call(etm, "isFeatureSupported", Const.FEATURE_GET_CIWLAN_CONFIG);
            Object f105 = Reflects.call(etm, "isFeatureSupported", Const.FEATURE_CIWLAN_MODE_PREFERENCE);
            LogX.i("isFeatureSupported(103 FEATURE_GET_CIWLAN_CONFIG)=" + f103);
            LogX.i("isFeatureSupported(105 FEATURE_CIWLAN_MODE_PREFERENCE)=" + f105);
        } catch (Throwable t) {
            LogX.e("isFeatureSupported failed", t);
        }
        for (int slot = 0; slot <= 1; slot++) {
            dumpSlot(slot);
        }
        Fn1ForceOos.logSlot1State(ctx, "dump/" + why);
    }

    void dumpSlot(int slot) {
        try {
            Object epdg = Reflects.call(etm, "isEpdgOverCellularDataSupported", slot);
            Object avail = Reflects.call(etm, "isCiwlanAvailable", slot);
            Object cfg = Reflects.call(etm, "getCiwlanConfig", slot);
            Object pref = Reflects.call(etm, "getCiwlanModeUserPreference", slot);
            LogX.i("slot " + slot
                    + " isEpdgOverCellularDataSupported=" + epdg
                    + " isCiwlanAvailable=" + avail
                    + " raw=" + (slot == Const.SLOT_TARGET ? QtiPhoneHooks.rawCiwlanSlot1() : "n/a")
                    + " getCiwlanConfig=" + describeConfig(cfg)
                    + " getCiwlanModeUserPreference=" + describeConfig(pref));
            if (slot == Const.SLOT_TARGET) {
                Boolean raw = QtiPhoneHooks.rawCiwlanSlot1();
                if (raw != null) {
                    Prefs.writeGlobal(ctx, Const.G_SLOT1_CIWLAN_AVAILABLE, raw ? "1" : "0");
                }
                Prefs.writeGlobal(ctx, Const.G_SLOT1_EPDG_CELL, String.valueOf(Boolean.TRUE.equals(epdg)));
            }
        } catch (Throwable t) {
            LogX.e("dump slot " + slot + " failed", t);
        }
    }

    Object getUserPref(int slot) {
        return Reflects.call(etm, "getCiwlanModeUserPreference", slot);
    }

    Object getConfig(int slot) {
        return Reflects.call(etm, "getCiwlanConfig", slot);
    }

    boolean isCiwlanAvailable(int slot) {
        Object v = Reflects.call(etm, "isCiwlanAvailable", slot);
        LogX.i("isCiwlanAvailable(" + slot + ")=" + v);
        return Boolean.TRUE.equals(v);
    }

    boolean isEpdgOverCellular(int slot) {
        Object v = Reflects.call(etm, "isEpdgOverCellularDataSupported", slot);
        LogX.i("isEpdgOverCellularDataSupported(" + slot + ")=" + v);
        return Boolean.TRUE.equals(v);
    }

    Object newCiwlanConfig(int home, int roam) {
        Class<?> cls = Reflects.find(cl, "com.qti.extphone.CiwlanConfig");
        return Reflects.newInstance(cls, home, roam);
    }

    int homeMode(Object cfg) {
        if (cfg == null) {
            return Const.CIWLAN_INVALID;
        }
        try {
            Object v = Reflects.call(cfg, "getCiwlanHomeMode");
            return v instanceof Integer ? (Integer) v : Const.CIWLAN_INVALID;
        } catch (Throwable t) {
            Object v = Reflects.getFieldOrNull(cfg, "mHomeMode");
            return v instanceof Integer ? (Integer) v : Const.CIWLAN_INVALID;
        }
    }

    int roamMode(Object cfg) {
        if (cfg == null) {
            return Const.CIWLAN_INVALID;
        }
        try {
            Object v = Reflects.call(cfg, "getCiwlanRoamMode");
            return v instanceof Integer ? (Integer) v : Const.CIWLAN_INVALID;
        } catch (Throwable t) {
            Object v = Reflects.getFieldOrNull(cfg, "mRoamMode");
            return v instanceof Integer ? (Integer) v : Const.CIWLAN_INVALID;
        }
    }

    String describeConfig(Object cfg) {
        if (cfg == null) {
            return "null";
        }
        try {
            return "home=" + LogX.modeName(homeMode(cfg))
                    + " roam=" + LogX.modeName(roamMode(cfg))
                    + " toString=" + cfg;
        } catch (Throwable t) {
            return String.valueOf(cfg);
        }
    }

    Object setUserPrefSlot1(Object config) {
        Safety.assertTargetSlot(Const.SLOT_TARGET, "setCiwlanModeUserPreference");
        if (client == null) {
            ensureClient();
        }
        if (client == null) {
            LogX.skip("setCiwlanModeUserPreference needs a registered Client");
            return null;
        }
        LogX.i("[FN2] setCiwlanModeUserPreference slot=1 client=" + client + " config=" + describeConfig(config));
        return Reflects.call(etm, "setCiwlanModeUserPreference", Const.SLOT_TARGET, client, config);
    }

    Object setNetworkSelectionAutomaticSlot1() {
        Safety.assertTargetSlot(Const.SLOT_TARGET, "setNetworkSelectionModeAutomatic");
        if (client == null) {
            ensureClient();
        }
        if (etm == null || client == null) {
            return null;
        }
        LogX.i("[FN1] ExtTelephonyManager.setNetworkSelectionModeAutomatic(slot=1, ACCESS_MODE_PLMN, client)");
        return Reflects.call(etm, "setNetworkSelectionModeAutomatic",
                Const.SLOT_TARGET, Const.ACCESS_MODE_PLMN, client);
    }

    private final class ServiceCb implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String n = method.getName();
            if ("onConnected".equals(n)) {
                handler.post(ExtPhoneGateway.this::onConnected);
            } else if ("onDisconnected".equals(n)) {
                connected = false;
                LogX.w("ExtTelephonyService onDisconnected");
                handler.postDelayed(ExtPhoneGateway.this::pollConnected, 2000L);
            } else if ("toString".equals(n)) {
                return "CIWLAN_FIX.ServiceCallback";
            } else if ("hashCode".equals(n)) {
                return System.identityHashCode(proxy);
            } else if ("equals".equals(n)) {
                return args != null && args.length > 0 && proxy == args[0];
            }
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) {
                return false;
            }
            if (rt == int.class) {
                return 0;
            }
            return null;
        }
    }
}
