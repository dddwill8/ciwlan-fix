package dev.ciwlanfix.lsposed.xposed;

import android.content.Context;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

final class CrossSimSlot1 {
    private CrossSimSlot1() {}

    static boolean shouldEnable(Context ctx) {
        return Prefs.fn2On(ctx) || Prefs.crossSimCall1(ctx) == 1;
    }

    static void sync(Context ctx, String why) {
        if (ctx == null) {
            return;
        }
        if (shouldEnable(ctx)) {
            setEnabled(ctx, true, why);
            return;
        }
        if (Prefs.crossSimCall1(ctx) == 0) {
            setEnabled(ctx, false, why);
        }
    }

    static void setEnabled(Context ctx, boolean enable, String why) {
        if (ctx == null) {
            return;
        }
        int subId = Slot.subIdSlot1(ctx);
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            LogX.skip("[FN2] Cross-SIM skip, no subId for slot 1 (" + why + ")");
            return;
        }
        Object mgr = mmTel(ctx, subId);
        if (mgr == null) {
            LogX.skip("[FN2] ImsMmTelManager missing (" + why + ")");
            return;
        }
        try {
            Boolean before = (Boolean) Reflects.callOrNull(mgr, "isCrossSimCallingEnabled");
            if (Boolean.valueOf(enable).equals(before)) {
                Prefs.writeGlobal(ctx, Const.G_CROSS_SIM_SUB1, enable ? "1" : "0");
                return;
            }
            Reflects.call(mgr, "setCrossSimCallingEnabled", enable);
            Boolean after = (Boolean) Reflects.callOrNull(mgr, "isCrossSimCallingEnabled");
            Prefs.writeGlobal(ctx, Const.G_CROSS_SIM_SUB1, Boolean.TRUE.equals(after) ? "1" : "0");
            LogX.i("[FN2] setCrossSimCallingEnabled(subId=" + subId + "," + enable
                    + ") before=" + before + " after=" + after + " why=" + why);
            logIms(ctx, why);
        } catch (Throwable t) {
            LogX.e("[FN2] setCrossSimCallingEnabled failed (" + why + ")", t);
        }
    }

    static void logIms(Context ctx, String why) {
        try {
            TelephonyManager tm = Slot.tmForSlot1(ctx);
            if (tm == null) {
                return;
            }
            Object registered = Reflects.callOrNull(tm, "isImsRegistered");
            Object tech = Reflects.callOrNull(tm, "getImsRegistrationTech");
            LogX.i("[FN2] ims slot1 " + why + " registered=" + registered
                    + " tech=" + tech + " (2=IWLAN)");
        } catch (Throwable t) {
            LogX.w("[FN2] ims query: " + t);
        }
    }

    private static Object mmTel(Context ctx, int subId) {
        try {
            Class<?> cls = Class.forName("android.telephony.ims.ImsMmTelManager");
            try {
                return cls.getMethod("createForSubscriptionId", int.class).invoke(null, subId);
            } catch (NoSuchMethodException ignored) {
            }
            Class<?> imsCls = Class.forName("android.telephony.ims.ImsManager");
            Object ims = ctx.getSystemService(imsCls);
            if (ims != null) {
                Object mgr = Reflects.callOrNull(ims, "getImsMmTelManager", subId);
                if (mgr != null) {
                    return mgr;
                }
            }
        } catch (Throwable t) {
            LogX.w("[FN2] ImsMmTelManager: " + t);
        }
        return null;
    }
}
