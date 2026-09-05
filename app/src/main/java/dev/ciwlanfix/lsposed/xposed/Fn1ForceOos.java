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
                applyGsmOnly("toggle-on");
                startWatch();
            } else if (on && !firstLockDone) {
                applyGsmOnly("boot-first");
                startWatch();
            } else if (!on && lastFn1) {
                LogX.i("[FN1] toggle OFF (" + why + ") -> restore WWAN types + automatic");
                restoreGsmOnly("toggle-off");
                stopWatch();
                lastApplyMs = 0L;
                backoffMs = Const.FN1_MIN_INTERVAL_MS;
                firstLockDone = false;
            } else if (!on && restoreOncePending) {
                restoreOncePending = false;
                LogX.i("[FN1] module loaded with FN1 off -> restore slot 1 WWAN types");
                restoreGsmOnly("startup-heal");
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
            LogX.i("[FN1] " + why
                    + " slot=1 subId=" + Slot.subIdSlot1(ctx)
                    + " selection=" + LogX.selectionName(sel)
                    + " allowedUser=" + getAllowedUser(local)
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

    private static boolean isGsmOnlyCamp(ServiceState ss) {
        if (!wwanRegistered(ss) || isDomesticRoam(ss)) {
            return false;
        }
        int rat = -1;
        Object v = invokeSs(ss, "getRilVoiceRadioTechnology");
        if (v instanceof Integer) {
            rat = (Integer) v;
        }
        return rat == 1 || rat == 2 || rat == 16;
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

    private static void applyGsmOnly(String why) {
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
        long current = getAllowedUser(local);
        publishAllowed(ctx, current);
        if (current == Const.WWAN_GSM_ONLY) {
            firstLockDone = true;
            clearLegacyManual(local, why);
            logSkip("[FN1] skip apply: slot1 already GSM-only why=" + why);
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
        savePreviousAllowed(ctx, current);
        logSlot1State(ctx, "before apply/" + why);
        Prefs.writeGlobal(ctx, Const.G_FN1_LAST_APPLY_MS, String.valueOf(now));
        lastApplyMs = now;
        disableRoaming(local);
        boolean ok = setAllowedUser(local, Const.WWAN_GSM_ONLY);
        long after = getAllowedUser(local);
        publishAllowed(ctx, after);
        ok = ok && after == Const.WWAN_GSM_ONLY;
        clearLegacyManual(local, why);
        if (ok) {
            backoffMs = Const.FN1_MIN_INTERVAL_MS;
            firstLockDone = true;
            LogX.i("[FN1] slot1 USER allowed " + current + " -> GSM-only " + Const.WWAN_GSM_ONLY
                    + " why=" + why);
        } else {
            backoffMs = Math.min(Math.max(backoffMs, Const.FN1_MIN_INTERVAL_MS) * 2, Const.FN1_MAX_BACKOFF_MS);
            LogX.e("[FN1] GSM-only apply failed (" + why + ") after=" + after
                    + "; next backoffMs=" + backoffMs);
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

    private static int reasonUser() {
        try {
            Object v = TelephonyManager.class.getField("ALLOWED_NETWORK_TYPES_REASON_USER").get(null);
            if (v instanceof Integer) {
                return (Integer) v;
            }
        } catch (Throwable ignored) {
        }
        return Const.ALLOWED_NETWORK_TYPES_REASON_USER;
    }

    private static long getAllowedUser(TelephonyManager local) {
        if (local == null) {
            return -1L;
        }
        Object r = invoke(local, "getAllowedNetworkTypesForReason", reasonUser());
        if (r instanceof Long) {
            return (Long) r;
        }
        if (r instanceof Integer) {
            return ((Integer) r).longValue();
        }
        return -1L;
    }

    private static boolean setAllowedUser(TelephonyManager local, long bits) {
        if (local == null) {
            return false;
        }
        try {
            Object[] args = new Object[]{reasonUser(), bits};
            Method m = Reflects.match(local.getClass(), "setAllowedNetworkTypesForReason", args);
            if (m == null) {
                LogX.e("[FN1] setAllowedNetworkTypesForReason not found");
                return false;
            }
            m.setAccessible(true);
            m.invoke(local, args);
            LogX.i("[FN1] setAllowedNetworkTypesForReason(USER," + bits + ") ok");
            return true;
        } catch (Throwable t) {
            LogX.e("[FN1] setAllowedNetworkTypesForReason failed", t);
            return false;
        }
    }

    private static void savePreviousAllowed(Context ctx, long current) {
        if (ctx == null || Prefs.readGlobalInt(ctx, Const.G_FN1_ALLOWED_SAVED, 0) == 1) {
            return;
        }
        if (current <= 0L || current == Const.WWAN_GSM_ONLY) {
            LogX.w("[FN1] previous USER allowed unusable (" + current + "), not saved");
            return;
        }
        Prefs.writeGlobal(ctx, Const.G_FN1_PREV_ALLOWED, String.valueOf(current));
        Prefs.writeGlobal(ctx, Const.G_FN1_ALLOWED_SAVED, "1");
        LogX.i("[FN1] saved previous USER allowed=" + current);
    }

    private static void publishAllowed(Context ctx, long bits) {
        if (ctx == null || bits < 0L) {
            return;
        }
        Prefs.writeGlobal(ctx, Const.G_FN1_ALLOWED_NOW, String.valueOf(bits));
    }

    private static void clearLegacyManual(TelephonyManager local, String why) {
        int sel = selectionMode(local);
        if (!isManualSelection(sel)) {
            return;
        }
        try {
            local.setNetworkSelectionModeAutomatic();
            LogX.i("[FN1] cleared leftover MANUAL 99999 -> automatic why=" + why);
        } catch (Throwable t) {
            LogX.w("[FN1] clear leftover MANUAL failed: " + t);
            if (gw != null) {
                try {
                    Object token = gw.setNetworkSelectionAutomaticSlot1();
                    LogX.i("[FN1] Ext automatic clear token=" + token);
                } catch (Throwable t2) {
                    LogX.w("[FN1] Ext automatic clear failed: " + t2);
                }
            }
        }
    }

    private static void restoreGsmOnly(String why) {
        Context ctx = context();
        TelephonyManager local = tm();
        if (local == null) {
            LogX.skip("[FN1] restore: no TelephonyManager");
            return;
        }
        logSlot1State(ctx, "before restore/" + why);
        if (Prefs.readGlobalInt(ctx, Const.G_FN1_ALLOWED_SAVED, 0) == 1) {
            long prev = Prefs.readGlobalLong(ctx, Const.G_FN1_PREV_ALLOWED, 0L);
            if (prev > 0L && prev != Const.WWAN_GSM_ONLY) {
                boolean ok = setAllowedUser(local, prev);
                long after = getAllowedUser(local);
                publishAllowed(ctx, after);
                LogX.i("[FN1] restored USER allowed=" + prev + " after=" + after
                        + " ok=" + ok + " why=" + why);
                if (ok && after == prev) {
                    Prefs.writeGlobal(ctx, Const.G_FN1_ALLOWED_SAVED, "0");
                }
            }
        }
        clearLegacyManual(local, why);
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
                LogX.i("[FN1] ServiceState slot1 " + describeSs(serviceState));
                if (serviceState != null && serviceState.getState() == ServiceState.STATE_POWER_OFF) {
                    LogX.i("[FN1] POWER_OFF, do not re-apply");
                    return;
                }
                if (wwanRegistered(serviceState) && !isGsmOnlyCamp(serviceState)) {
                    String why = isDomesticRoam(serviceState) ? "ultra-roam" : "wwan-in-service";
                    LogX.i("[FN1] WWAN registered (" + why + ") -> GSM-only");
                    applyGsmOnly(why);
                    return;
                }
                firstLockDone = true;
                TelephonyManager local = tm();
                long allowed = getAllowedUser(local);
                if (allowed == Const.WWAN_GSM_ONLY) {
                    logSkip("[FN1] GSM-only holding, IWLAN/OOS skip apply");
                    return;
                }
                LogX.i("[FN1] USER allowed=" + allowed + " -> GSM-only");
                applyGsmOnly("lock-lost");
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
