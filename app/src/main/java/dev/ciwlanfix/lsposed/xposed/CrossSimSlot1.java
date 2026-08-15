package dev.ciwlanfix.lsposed.xposed;

import android.content.Context;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsMmTelManager;

final class CrossSimSlot1 {
    private static Boolean applied;

    private CrossSimSlot1() {}

    static void setEnabled(Context ctx, boolean enable, String why) {
        if (ctx == null) {
            return;
        }
        if (applied != null && applied == enable) {
            return;
        }
        int subId = Slot.subIdSlot1(ctx);
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            LogX.skip("[FN2] Cross-SIM skip, no subId for slot 1 (" + why + ")");
            return;
        }
        try {
            ImsMmTelManager mgr = ImsMmTelManager.createForSubscriptionId(subId);
            boolean before = false;
            try {
                before = mgr.isCrossSimCallingEnabled();
            } catch (Throwable ignored) {
            }
            if (before != enable) {
                mgr.setCrossSimCallingEnabled(enable);
            }
            boolean after = mgr.isCrossSimCallingEnabled();
            applied = after == enable ? enable : null;
            Prefs.writeGlobal(ctx, Const.G_CROSS_SIM_SUB1, after ? "1" : "0");
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
}
