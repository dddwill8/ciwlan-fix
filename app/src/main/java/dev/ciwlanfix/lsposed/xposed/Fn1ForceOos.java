package dev.ciwlanfix.lsposed.xposed;

import android.content.Context;
import android.os.Handler;
import android.telephony.ServiceState;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

final class Fn1ForceOos {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private static ExtPhoneGateway gw;
    private static TelephonyManager tm;
    private static Slot1Watcher watcher;
    private static boolean watching;
    private static boolean lastFn1;
    private static long lastApplyMs;
    private static long backoffMs = Const.FN1_MIN_INTERVAL_MS;
    private static boolean restoreOncePending = true;

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
        if (gw != null) {
            LogX.i("[FN1] com.android.phone scoped but com.qti.phone already owns FN1");
            return;
        }
        LogX.i("[FN1] running inside com.android.phone (optional scope)");
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
                applyManual("toggle-on");
                startWatch();
            } else if (!on && lastFn1) {
                LogX.i("[FN1] toggle OFF (" + why + ") -> automatic");
                restoreAutomatic("toggle-off");
                stopWatch();
                lastApplyMs = 0L;
                backoffMs = Const.FN1_MIN_INTERVAL_MS;
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
            int sel = -1;
            try {
                sel = local.getNetworkSelectionMode();
            } catch (Throwable t) {
                LogX.w("[FN1] getNetworkSelectionMode failed: " + t);
            }
            LogX.i("[FN1] " + why
                    + " slot=1 subId=" + Slot.subIdSlot1(ctx)
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
                + " voice=" + ss.getVoiceRegState()
                + " data=" + ss.getDataRegState()
                + " emergencyOnly=" + ss.isEmergencyOnly()
                + " op=" + ss.getOperatorNumeric()
                + " raw=" + ss;
    }

    static boolean isOosOrEmergency(ServiceState ss) {
        if (ss == null) {
            return false;
        }
        int state = ss.getState();
        return state == ServiceState.STATE_OUT_OF_SERVICE
                || state == ServiceState.STATE_EMERGENCY_ONLY
                || ss.isEmergencyOnly();
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
        long now = android.os.SystemClock.elapsedRealtime();
        if (!"toggle-on".equals(why) && now - lastApplyMs < backoffMs) {
            LogX.i("[FN1] rate-limit skip apply why=" + why
                    + " waitMs=" + (backoffMs - (now - lastApplyMs)));
            return;
        }
        String plmn = Prefs.plmn(ctx);
        TelephonyManager local = tm();
        if (local == null) {
            LogX.skip("[FN1] no TelephonyManager for slot 1");
            return;
        }
        logSlot1State(ctx, "before apply/" + why);
        boolean ok = setManualPersistFalse(local, plmn);
        lastApplyMs = android.os.SystemClock.elapsedRealtime();
        if (ok) {
            backoffMs = Const.FN1_MIN_INTERVAL_MS;
            LogX.i("[FN1] apply manual PLMN=" + plmn + " persist=false slot=1 why=" + why + " ok=true");
        } else {
            backoffMs = Math.min(backoffMs * 2, Const.FN1_MAX_BACKOFF_MS);
            LogX.e("[FN1] apply failed; next backoffMs=" + backoffMs);
        }
        logSlot1State(ctx, "after apply/" + why);
    }

    private static boolean setManualPersistFalse(TelephonyManager local, String plmn) {
        try {
            Method m = TelephonyManager.class.getMethod(
                    "setNetworkSelectionModeManual", String.class, boolean.class);
            Object r = m.invoke(local, plmn, Boolean.FALSE);
            LogX.i("[FN1] TelephonyManager.setNetworkSelectionModeManual(" + plmn + ", persist=false) -> " + r);
            return !Boolean.FALSE.equals(r);
        } catch (Throwable t) {
            LogX.e("[FN1] TelephonyManager.setNetworkSelectionModeManual persist=false failed", t);
            LogX.skip("[FN1] ExtTelephonyManager.setNetworkSelectionModeManual has no persist flag "
                    + "(QtiSetNetworkSelectionMode). Not used, to avoid modem-permanent manual PLMN.");
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
                if (!isOosOrEmergency(serviceState)) {
                    LogX.i("[FN1] left OOS/emergency-only -> re-apply invalid PLMN persist=false");
                    applyManual("service-revert");
                }
            } catch (Throwable t) {
                LogX.e("[FN1] onServiceStateChanged failed", t);
            }
        }
    }

    private static final class HandlerThreadHolder {
        static Context ctx;
        static Handler handler;

        static void start(Context context, Runnable first) {
            if (context == null) {
                LogX.w("[FN1] phone-process start skipped: context is null");
                return;
            }
            Context app = context.getApplicationContext();
            ctx = app != null ? app : context;
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
