package dev.ciwlanfix.lsposed.xposed;

import android.content.Context;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

final class Slot {
    private Slot() {}

    static int subIdSlot1(Context ctx) {
        if (ctx == null) {
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
        try {
            SubscriptionManager sm = ctx.getSystemService(SubscriptionManager.class);
            if (sm != null) {
                SubscriptionInfo info = sm.getActiveSubscriptionInfoForSimSlotIndex(Const.SLOT_TARGET);
                if (info != null && info.getSubscriptionId() >= 0) {
                    return info.getSubscriptionId();
                }
            }
        } catch (Throwable t) {
            LogX.e("getActiveSubscriptionInfoForSimSlotIndex(1) failed", t);
        }
        try {
            int[] ids = (int[]) SubscriptionManager.class
                    .getMethod("getSubscriptionIds", int.class)
                    .invoke(null, Const.SLOT_TARGET);
            if (ids != null && ids.length > 0 && ids[0] >= 0) {
                return ids[0];
            }
        } catch (Throwable t) {
            LogX.w("SubscriptionManager.getSubscriptionIds(1) unavailable: " + t);
        }
        LogX.skip("[slot] cannot resolve subId for slot 1; refusing hardcoded fallback (may be slot 0)");
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    static TelephonyManager tmForSlot1(Context ctx) {
        if (ctx == null) {
            return null;
        }
        TelephonyManager base = ctx.getSystemService(TelephonyManager.class);
        if (base == null) {
            LogX.skip("[slot] TelephonyManager missing");
            return null;
        }
        int subId = subIdSlot1(ctx);
        if (subId < 0) {
            return null;
        }
        TelephonyManager tm = base.createForSubscriptionId(subId);
        LogX.i("[slot] target slot=1 subId=" + subId + " tm=" + tm);
        return tm;
    }
}
