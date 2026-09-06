package dev.ciwlanfix.lsposed.xposed;

import android.content.Context;
import android.os.Handler;
import android.telephony.ServiceState;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

final class Fn1ForceOos {
    private static final int DOMAIN_CS = 1;
    private static final int DOMAIN_PS = 2;
    private static final int TRANSPORT_WWAN = 1;
    private static final int TRANSPORT_WLAN = 2;
    private static final int REG_HOME = 1;
    private static final int REG_ROAMING = 5;
    private static final int NETWORK_TYPE_IWLAN = 18;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private static ExtPhoneGateway gw;
    private static TelephonyManager tm;
    private static Slot1Watcher watcher;
    private static boolean watching;
    private static boolean lastFn1;
    private static long lastApplyMs;
    private static long backoffMs = Const.FN1_MIN_INTERVAL_MS;
    private static boolean restoreOncePending = true;
    private static boolean firstLockDone;
    private static long lastSkipLogMs;
    private static final java.util.concurrent.atomic.AtomicBoolean BOOT_RETRY =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private Fn1ForceOos() {}

    static void install(ExtPhoneGateway gateway) {
        gw = gateway;
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        Handler h = gateway.handler();
        h.post(() -> tick("install"));
        h.postDelayed(Fn1ForceOos::loop, Const.PREF_POLL_MS);
    }

    static void onServiceReady(ExtPhoneGateway gateway) {
        gw = gateway;
        gateway.handler().post(() -> tick("service-ready"));
    }

    static void installPhoneProcess(ClassLoader cl, Context ctx) {
        LogX.i("[FN1] running inside com.android.phone");
        HandlerThreadHolder.start(ctx, () -> tick("phone-process"));
    }

    private static void loop() {
        try {
            tick("poll");
        } catch (Throwable t) {
            LogX.e("[FN1] poll failed", t);
        } finally {
            if (gw != null) {
                gw.handler().postDelayed(Fn1ForceOos::loop, Const.PREF_POLL_MS);
            }
        }
    }

    private static synchronized void tick(String why) {
        try {
            Context ctx = context();
            if (ctx == null) {
                return;
            }
            boolean on = Prefs.fn1On(ctx);
            if (on && !lastFn1) {
                LogX.i("[FN1] toggle ON (" + why + ")");
                firstLockDone = false;
                applyManual("toggle-on");
                startWatch();
            } else if (on && !firstLockDone) {
                applyManual("boot-first");
                startWatch();
            } else if (!on && lastFn1) {
                LogX.i("[FN1] toggle OFF (" + why + ") -> automatic");
                restoreAutomatic("toggle-off");
                stopWatch();
                lastApplyMs = 0L;
                backoffMs = Const.FN1_MIN_INTERVAL_MS;
                firstLockDone = false;
            } else if (!on && restoreOncePending) {
                restoreOncePending = false;
                LogX.i("[FN1] module loaded with FN1 off -> ensure slot 1 automatic");
                restoreAutomatic("startup-heal");
            } else if (on && !watching) {
                startWatch();
            }
            lastFn1 = on;
        } catch (Throwable t) {
            LogX.e("[FN1] tick(" + why + ") failed", t);
        }
    }

    private static Context context() {
        if (gw != null) {
            return gw.context();
        }
        return HandlerThreadHolder.ctx;
    }

    private static Handler handler() {
        if (gw != null) {
            return gw.handler();
        }
        return HandlerThreadHolder.handler;
    }

    private static TelephonyManager tm() {
        Context ctx = context();
        if (ctx == null) {
            return null;
        }
        try {
            if (tm == null) {
                tm = Slot.tmForSlot1(ctx);
            }
            return tm;
        } catch (Throwable t) {
            LogX.e("[FN1] TelephonyManager for slot 1 unavailable", t);
            return null;
        }
    }

    static void logSlot1State(Context ctx, String why) {
        try {
            TelephonyManager local = Slot.tmForSlot1(ctx);
            if (local == null) {
                LogX.w("[FN1] " + why + " slot=1 no TelephonyManager");
                return;
            }
            ServiceState ss = local.getServiceState();
            int sel = selectionMode(local);
            String want = Prefs.plmn(ctx);
            LogX.i("[FN1] " + why
                    + " slot=1 subId=" + Slot.subIdSlot1(ctx)
                    + " wantPlmn=" + want + "(" + Const.plmnLabel(want) + ")"
                    + " applied=" + Prefs.readGlobal(ctx, Const.G_FN1_APPLIED_PLMN)
                    + " selection=" + LogX.selectionName(sel)
                    + " serviceState=" + describeSs(ss));
        } catch (Throwable t) {
            LogX.e("[FN1] logSlot1State failed", t);
        }
    }

    static String describeSs(ServiceState ss) {
        if (ss == null) {
            return "null";
        }
        return "state=" + ss.getState()
                + " voice=" + invokeSs(ss, "getVoiceRegState")
                + " data=" + invokeSs(ss, "getDataRegState")
                + " wwanCs=" + describeNri(nri(ss, DOMAIN_CS, TRANSPORT_WWAN))
                + " wwanPs=" + describeNri(nri(ss, DOMAIN_PS, TRANSPORT_WWAN))
                + " wlan=" + describeNri(nri(ss, DOMAIN_PS, TRANSPORT_WLAN))
                + " emergencyOnly=" + emergencyOnly(ss)
                + " op=" + ss.getOperatorNumeric()
                + " alpha=" + ss.getOperatorAlphaLong();
    }

    private static String describeNri(Object nri) {
        if (nri == null) {
            return "null";
        }
        Object reg = invoke(nri, "getNetworkRegistrationState");
        if (reg == null) {
            reg = invoke(nri, "getRegistrationState");
        }
        return "reg=" + reg
                + " rat=" + invoke(nri, "getAccessNetworkTechnology")
                + " registered=" + nriRegistered(nri);
    }

    static boolean isDomesticRoam(ServiceState ss) {
        if (ss == null || !wwanRegistered(ss)) {
            return false;
        }
        String op = wwanMccMnc(ss);
        if (op != null && op.startsWith("460")) {
            return true;
        }
        if (domesticName(ss.getOperatorAlphaLong()) || domesticName(ss.getOperatorAlphaShort())) {
            return true;
        }
        Object id = wwanCell(ss);
        return id != null && (domesticName(invoke(id, "getOperatorAlphaLong"))
                || domesticName(invoke(id, "getOperatorAlphaShort")));
    }

    private static boolean domesticName(Object name) {
        if (name == null) {
            return false;
        }
        String n = name.toString();
        if (n.isEmpty()) {
            return false;
        }
        String lower = n.toLowerCase();
        return lower.contains("ultra") || lower.contains("china mobile") || lower.contains("china unicom")
                || lower.contains("china telecom") || n.contains("移动") || n.contains("联通")
                || n.contains("电信");
    }

    static boolean wwanRegistered(ServiceState ss) {
        if (ss == null) {
            return false;
        }
        Object cs = nri(ss, DOMAIN_CS, TRANSPORT_WWAN);
        Object ps = nri(ss, DOMAIN_PS, TRANSPORT_WWAN);
        if (cs != null || ps != null) {
            return nriRegistered(cs) || nriRegistered(ps);
        }
        if (wlanServing(ss)) {
            return false;
        }
        return ss.getState() == ServiceState.STATE_IN_SERVICE;
    }

    static boolean wlanServing(ServiceState ss) {
        if (ss == null) {
            return false;
        }
        Object wlan = nri(ss, DOMAIN_PS, TRANSPORT_WLAN);
        if (wlan != null) {
            return nriRegistered(wlan);
        }
        Object rat = invokeSs(ss, "getDataNetworkType");
        return rat instanceof Integer && (Integer) rat == NETWORK_TYPE_IWLAN;
    }

    private static boolean nriRegistered(Object nri) {
        if (nri == null) {
            return false;
        }
        Object registered = invoke(nri, "isRegistered");
        if (registered instanceof Boolean) {
            return (Boolean) registered;
        }
        Object st = invoke(nri, "getNetworkRegistrationState");
        if (!(st instanceof Integer)) {
            st = invoke(nri, "getRegistrationState");
        }
        if (st instanceof Integer) {
            int v = (Integer) st;
            return v == REG_HOME || v == REG_ROAMING;
        }
        return false;
    }

    private static Object nri(ServiceState ss, int domain, int transport) {
        return invoke(ss, "getNetworkRegistrationInfo", domain, transport);
    }

    private static String wwanMccMnc(ServiceState ss) {
        Object id = wwanCell(ss);
        if (id != null) {
            Object mcc = invoke(id, "getMccString");
            Object mnc = invoke(id, "getMncString");
            if (mcc != null) {
                String mccStr = mcc.toString();
                if (!mccStr.isEmpty()) {
                    return mccStr + (mnc == null ? "" : mnc.toString());
                }
            }
        }
        return ss.getOperatorNumeric();
    }

    private static Object wwanCell(ServiceState ss) {
        Object[] infos = new Object[]{
                nri(ss, DOMAIN_CS, TRANSPORT_WWAN),
                nri(ss, DOMAIN_PS, TRANSPORT_WWAN),
        };
        for (Object info : infos) {
            if (!nriRegistered(info)) {
                continue;
            }
            Object id = invoke(info, "getCellIdentity");
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    private static Object invoke(Object target, String name, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            Method m = Reflects.match(target.getClass(), name, args);
            if (m == null) {
                return null;
            }
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean emergencyOnly(ServiceState ss) {
        Object v = invokeSs(ss, "isEmergencyOnly");
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        return ss.getState() == ServiceState.STATE_EMERGENCY_ONLY;
    }

    private static Object invokeSs(ServiceState ss, String name) {
        try {
            return ServiceState.class.getMethod(name).invoke(ss);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int selectionMode(TelephonyManager local) {
        if (local == null) {
            return -1;
        }
        try {
            return local.getNetworkSelectionMode();
        } catch (Throwable t) {
            LogX.w("[FN1] getNetworkSelectionMode failed: " + t);
            return -1;
        }
    }

    private static boolean isManualSelection(int sel) {
        return sel == TelephonyManager.NETWORK_SELECTION_MODE_MANUAL;
    }

    private static void logSkip(String msg) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSkipLogMs > 10_000L) {
            lastSkipLogMs = now;
            LogX.i(msg);
        }
    }

    private static void applyManual(String why) {
        Context ctx = context();
        if (ctx == null) {
            return;
        }
        if (Prefs.airplaneOn(ctx)) {
            LogX.i("[FN1] skip apply: airplane mode on");
            return;
        }
        TelephonyManager local = tm();
        if (local == null) {
            LogX.skip("[FN1] no TelephonyManager for slot 1");
            scheduleBootRetry();
            return;
        }
        ServiceState ss = null;
        try {
            ss = local.getServiceState();
        } catch (Throwable t) {
            LogX.w("[FN1] getServiceState: " + t);
        }
        int sel = selectionMode(local);
        boolean wwan = wwanRegistered(ss);
        String want = Prefs.plmn(ctx);
        String applied = Prefs.readGlobal(ctx, Const.G_FN1_APPLIED_PLMN);
        if (!wwan && isManualSelection(sel) && want.equals(applied)) {
            firstLockDone = true;
            logSkip("[FN1] skip apply: WWAN idle, MANUAL holding "
                    + want + "(" + Const.plmnLabel(want) + ") why=" + why);
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        long globalLast = Prefs.readGlobalLong(ctx, Const.G_FN1_LAST_APPLY_MS, 0L);
        if (globalLast > now) {
            globalLast = 0L;
        }
        long localWait = now - lastApplyMs;
        long globalWait = now - globalLast;
        if (localWait < backoffMs || (globalLast > 0L && globalWait < Const.FN1_MIN_INTERVAL_MS)) {
            firstLockDone = true;
            LogX.i("[FN1] rate-limit skip apply why=" + why
                    + " localWaitMs=" + localWait + " globalWaitMs=" + globalWait);
            return;
        }
        String plmn = Prefs.plmn(ctx);
        logSlot1State(ctx, "before apply/" + why);
        Prefs.writeGlobal(ctx, Const.G_FN1_LAST_APPLY_MS, String.valueOf(now));
        lastApplyMs = now;
        disableRoaming(local);
        boolean ok = setManualPersistTrue(local, plmn);
        if (!ok && isManualSelection(selectionMode(local))) {
            ok = true;
            LogX.i("[FN1] setNetworkSelectionModeManual returned false; already MANUAL, treat as holding");
        }
        if (ok) {
            backoffMs = Const.FN1_MIN_INTERVAL_MS;
            firstLockDone = true;
            Prefs.writeGlobal(ctx, Const.G_FN1_APPLIED_PLMN, plmn);
            if (!plmn.equals(Prefs.readGlobal(ctx, Const.G_PLMN))) {
                Prefs.writeGlobal(ctx, Const.G_PLMN, plmn);
            }
            LogX.i("[FN1] apply manual PLMN=" + plmn + "(" + Const.plmnLabel(plmn)
                    + ") persist=true slot=1 why=" + why + " ok=true wwanMccMnc="
                    + wwanMccMnc(ss) + " alpha=" + (ss == null ? null : ss.getOperatorAlphaLong()));
        } else {
            backoffMs = Math.min(Math.max(backoffMs, Const.FN1_MIN_INTERVAL_MS) * 2, Const.FN1_MAX_BACKOFF_MS);
            LogX.e("[FN1] apply failed PLMN=" + plmn + " why=" + why + "; next backoffMs=" + backoffMs);
            scheduleBootRetry();
        }
        logSlot1State(ctx, "after apply/" + why);
    }

    private static void scheduleBootRetry() {
        Handler h = handler();
        if (h == null || !BOOT_RETRY.compareAndSet(false, true)) {
            return;
        }
        h.postDelayed(() -> {
            BOOT_RETRY.set(false);
            Context ctx = context();
            if (ctx != null && Prefs.fn1On(ctx) && !firstLockDone) {
                tick("boot-first");
            }
        }, 200L);
    }

    private static void disableRoaming(TelephonyManager local) {
        try {
            Method m = TelephonyManager.class.getMethod("setDataRoamingEnabled", boolean.class);
            m.invoke(local, Boolean.FALSE);
            LogX.i("[FN1] setDataRoamingEnabled(false) slot=1");
        } catch (Throwable t) {
            LogX.w("[FN1] setDataRoamingEnabled: " + t);
        }
    }

    private static boolean setManualPersistTrue(TelephonyManager local, String plmn) {
        try {
            Method m = TelephonyManager.class.getMethod(
                    "setNetworkSelectionModeManual", String.class, boolean.class);
            Object r = m.invoke(local, plmn, Boolean.TRUE);
            LogX.i("[FN1] TelephonyManager.setNetworkSelectionModeManual(" + plmn + ", persist=true) -> " + r);
            return r == null || Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            LogX.e("[FN1] TelephonyManager.setNetworkSelectionModeManual persist=true failed", t);
            LogX.skip("[FN1] ExtTelephonyManager.setNetworkSelectionModeManual has no persist flag "
                    + "(QtiSetNetworkSelectionMode). Not used; restore still goes through "
                    + "TelephonyManager.setNetworkSelectionModeAutomatic.");
            return false;
        }
    }

    private static void restoreAutomatic(String why) {
        Context ctx = context();
        TelephonyManager local = tm();
        if (local == null) {
            LogX.skip("[FN1] restore: no TelephonyManager");
            return;
        }
        logSlot1State(ctx, "before restore/" + why);
        Prefs.writeGlobal(ctx, Const.G_FN1_APPLIED_PLMN, "");
        try {
            local.setNetworkSelectionModeAutomatic();
            LogX.i("[FN1] TelephonyManager.setNetworkSelectionModeAutomatic() slot1 why=" + why);
        } catch (Throwable t) {
            LogX.e("[FN1] TM setNetworkSelectionModeAutomatic failed", t);
            if (gw != null) {
                try {
                    Object token = gw.setNetworkSelectionAutomaticSlot1();
                    LogX.i("[FN1] Ext setNetworkSelectionModeAutomatic token=" + token);
                } catch (Throwable t2) {
                    LogX.e("[FN1] Ext automatic restore failed", t2);
                    LogX.skip("[FN1] cannot restore automatic without forbidden persist/partition work");
                }
            }
        }
        logSlot1State(ctx, "after restore/" + why);
    }

    private static void startWatch() {
        if (watching) {
            return;
        }
        TelephonyManager local = tm();
        Handler h = handler();
        if (local == null || h == null) {
            return;
        }
        try {
            watcher = new Slot1Watcher();
            local.registerTelephonyCallback(h::post, watcher);
            watching = true;
            LogX.i("[FN1] ServiceState watcher registered for slot 1");
        } catch (Throwable t) {
            LogX.e("[FN1] registerTelephonyCallback failed", t);
        }
    }

    private static void stopWatch() {
        TelephonyManager local = tm;
        if (local != null && watcher != null) {
            try {
                local.unregisterTelephonyCallback(watcher);
                LogX.i("[FN1] ServiceState watcher removed");
            } catch (Throwable t) {
                LogX.w("[FN1] unregisterTelephonyCallback: " + t);
            }
        }
        watcher = null;
        watching = false;
        tm = null;
    }

    /**
     * Merged {@code getState()} is IN_SERVICE when backup calling / IWLAN is up.
     * Only re-lock when WWAN itself has registered; do not treat IWLAN success as escaped OOS.
     */
    private static final class Slot1Watcher extends TelephonyCallback
            implements TelephonyCallback.ServiceStateListener {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            try {
                Context ctx = context();
                if (ctx == null || !Prefs.fn1On(ctx)) {
                    return;
                }
                LogX.d("[FN1] ServiceState slot1 " + describeSs(serviceState));
                if (serviceState != null && serviceState.getState() == ServiceState.STATE_POWER_OFF) {
                    LogX.i("[FN1] POWER_OFF, do not re-apply");
                    return;
                }
                if (wwanRegistered(serviceState)) {
                    String mcc = wwanMccMnc(serviceState);
                    String why = isDomesticRoam(serviceState) ? "ultra-roam" : "wwan-in-service";
                    LogX.i("[FN1] WWAN registered (" + why + ") mccMnc=" + mcc
                            + " alpha=" + serviceState.getOperatorAlphaLong()
                            + " -> re-apply " + Prefs.plmn(ctx));
                    applyManual(why);
                    return;
                }
                firstLockDone = true;
                TelephonyManager local = tm();
                int sel = selectionMode(local);
                String want = Prefs.plmn(ctx);
                String applied = Prefs.readGlobal(ctx, Const.G_FN1_APPLIED_PLMN);
                if (wlanServing(serviceState)) {
                    logSkip("[FN1] IWLAN serving, WWAN idle; holding "
                            + want + " selection=" + LogX.selectionName(sel));
                    return;
                }
                if ((isManualSelection(sel) || sel < 0) && want.equals(applied)) {
                    logSkip("[FN1] WWAN OOS selection=" + LogX.selectionName(sel)
                            + " holding " + want + "; skip apply");
                    return;
                }
                LogX.i("[FN1] lock lost (WWAN OOS AUTO or PLMN changed want="
                        + want + " applied=" + applied + ") -> re-apply");
                applyManual("lock-lost");
            } catch (Throwable t) {
                LogX.e("[FN1] onServiceStateChanged failed", t);
            }
        }
    }

    private static final class HandlerThreadHolder {
        static Context ctx;
        static Handler handler;
        static final AtomicBoolean STARTED = new AtomicBoolean(false);

        static void start(Context context, Runnable first) {
            if (context == null) {
                LogX.w("[FN1] phone-process start skipped: context is null");
                return;
            }
            Context app = context.getApplicationContext();
            ctx = app != null ? app : context;
            if (!STARTED.compareAndSet(false, true)) {
                if (handler != null && first != null) {
                    handler.post(first);
                }
                return;
            }
            android.os.HandlerThread ht = new android.os.HandlerThread("CIWLAN_FIX_FN1");
            ht.start();
            handler = new Handler(ht.getLooper());
            handler.post(first);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        tick("phone-poll");
                    } catch (Throwable t) {
                        LogX.e("[FN1] phone-poll failed", t);
                    }
                    handler.postDelayed(this, Const.PREF_POLL_MS);
                }
            }, Const.PREF_POLL_MS);
        }
    }
}
