package dev.ciwlanfix.lsposed.xposed;

final class Fn2CiwlanPref {
    private static boolean lastFn2 = true;
    private static boolean initialized;
    private static boolean appliedOnly;
    private static boolean lastWifiAssoc;
    private static ExtPhoneGateway gw;

    private Fn2CiwlanPref() {}

    static void onServiceReady(ExtPhoneGateway gateway) {
        gw = gateway;
        gateway.handler().post(() -> run("service-ready"));
    }

    static void installWatcher(ExtPhoneGateway gateway) {
        gw = gateway;
        gateway.handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                tick();
                gateway.handler().postDelayed(this, Const.PREF_POLL_MS);
            }
        }, Const.PREF_POLL_MS);
    }

    private static void tick() {
        if (gw == null || !gw.connected()) {
            return;
        }
        boolean on = Prefs.fn2On(gw.context());
        boolean wifi = WifiAssoc.associated(gw.context());
        if (!initialized) {
            initialized = true;
            lastFn2 = on;
            lastWifiAssoc = wifi;
            run("first-tick");
            return;
        }
        if (on && !lastFn2) {
            LogX.i("[FN2] toggle ON");
            run("toggle-on");
        } else if (!on && lastFn2) {
            LogX.i("[FN2] toggle OFF -> restore previous slot 1 CiwlanConfig");
            restore("toggle-off");
        } else if (on && wifi != lastWifiAssoc) {
            run(wifi ? "wifi-assoc" : "wifi-lost");
        } else {
            CrossSimSlot1.sync(gw.context(), "fn2-tick");
        }
        lastFn2 = on;
        lastWifiAssoc = wifi;
    }

    private static void run(String why) {
        if (gw == null) {
            return;
        }
        CrossSimSlot1.sync(gw.context(), why);
        if (gw.etm() == null) {
            LogX.skip("[FN2] ExtTelephonyManager not ready (" + why + ")");
            return;
        }
        if (!Prefs.fn2On(gw.context())) {
            LogX.i("[FN2] off, skip set (" + why + ")");
            restore("fn2-off/" + why);
            Prefs.writeGlobal(gw.context(), Const.G_FN2_DONE, "1");
            return;
        }
        try {
            Safety.assertTargetSlot(Const.SLOT_TARGET, "FN2");
            Object before = gw.getUserPref(Const.SLOT_TARGET);
            int home = gw.homeMode(before);
            int roam = gw.roamMode(before);
            LogX.i("[FN2] slot1 preference before " + gw.describeConfig(before) + " why=" + why);
            savePreviousIfNeeded(home, roam);

            if (WifiAssoc.associated(gw.context())) {
                applyHomeWfc(why);
            } else {
                applyOnlyOnly(why, home, roam);
            }
            gw.handler().postDelayed(() -> requery("after-set/" + why), Const.FN2_REQUERY_MS);
            gw.handler().postDelayed(() -> requery("after-set-3s/" + why), 3000L);
            gw.handler().postDelayed(() -> requery("after-set-8s/" + why), 8000L);
        } catch (Throwable t) {
            LogX.e("[FN2] failed (" + why + ")", t);
            Prefs.writeGlobal(gw.context(), Const.G_FN2_DONE, "1");
        }
    }

    private static void applyOnlyOnly(String why, int home, int roam) {
        if (home == Const.CIWLAN_ONLY && roam == Const.CIWLAN_ONLY) {
            LogX.i("[FN2] slot1 already ONLY/ONLY, no set needed");
            appliedOnly = true;
            return;
        }
        Object only = gw.newCiwlanConfig(Const.CIWLAN_ONLY, Const.CIWLAN_ONLY);
        Object token = gw.setUserPrefSlot1(only);
        LogX.i("[FN2] set ONLY/ONLY token=" + token + " why=" + why);
        appliedOnly = true;
    }

    private static void applyHomeWfc(String why) {
        int home = Prefs.readGlobalInt(gw.context(), Const.G_PREV_HOME, Const.CIWLAN_PREFERRED);
        int roam = Prefs.readGlobalInt(gw.context(), Const.G_PREV_ROAM, Const.CIWLAN_ONLY);
        if (home == Const.CIWLAN_ONLY && roam == Const.CIWLAN_ONLY) {
            home = Const.CIWLAN_PREFERRED;
            roam = Const.CIWLAN_ONLY;
        }
        Object before = gw.getUserPref(Const.SLOT_TARGET);
        if (gw.homeMode(before) == home && gw.roamMode(before) == roam) {
            LogX.i("[FN2] slot1 already home-WFC " + gw.describeConfig(before) + " why=" + why);
            appliedOnly = false;
            return;
        }
        Object cfg = gw.newCiwlanConfig(home, roam);
        Object token = gw.setUserPrefSlot1(cfg);
        appliedOnly = false;
        LogX.i("[FN2] home Wi-Fi associated, CiwlanConfig -> home=" + LogX.modeName(home)
                + " roam=" + LogX.modeName(roam) + " token=" + token + " why=" + why);
    }

    private static void requery(String why) {
        try {
            gw.dumpSlot(Const.SLOT_TARGET);
            boolean avail = gw.isCiwlanAvailable(Const.SLOT_TARGET);
            Boolean rawAvail = QtiPhoneHooks.rawCiwlanSlot1();
            boolean epdg = gw.isEpdgOverCellular(Const.SLOT_TARGET);
            Object pref = gw.getUserPref(Const.SLOT_TARGET);
            LogX.i("[FN2] requery " + why
                    + " pref=" + gw.describeConfig(pref)
                    + " isCiwlanAvailable(1)=" + avail
                    + " raw=" + rawAvail
                    + " isEpdgOverCellularDataSupported(1)=" + epdg);
            Prefs.writeGlobal(gw.context(), Const.G_FN2_DONE, "1");
            boolean reportAvail = rawAvail != null ? rawAvail : avail;
            Prefs.writeGlobal(gw.context(), Const.G_SLOT1_CIWLAN_AVAILABLE, reportAvail ? "1" : "0");
            Prefs.writeGlobal(gw.context(), Const.G_SLOT1_EPDG_CELL, epdg ? "1" : "0");
            CrossSimSlot1.logIms(gw.context(), why);
            if (!reportAvail) {
                LogX.i("[FN2] modem isCiwlanAvailable(1)=false after ONLY/ONLY; Function 3 will fake WLAN HOME");
            }
        } catch (Throwable t) {
            LogX.e("[FN2] requery failed", t);
            Prefs.writeGlobal(gw.context(), Const.G_FN2_DONE, "1");
        }
    }

    private static void savePreviousIfNeeded(int home, int roam) {
        if (Prefs.readGlobalInt(gw.context(), Const.G_PREV_SAVED, 0) == 1) {
            return;
        }
        if ((home == Const.CIWLAN_ONLY && roam == Const.CIWLAN_ONLY)
                || (home == Const.CIWLAN_INVALID && roam == Const.CIWLAN_INVALID)) {
            home = Const.CIWLAN_PREFERRED;
            roam = Const.CIWLAN_ONLY;
            LogX.w("[FN2] previous unusable, remember live-known default home=PREFERRED roam=ONLY");
        }
        Prefs.writeGlobal(gw.context(), Const.G_PREV_HOME, String.valueOf(home));
        Prefs.writeGlobal(gw.context(), Const.G_PREV_ROAM, String.valueOf(roam));
        Prefs.writeGlobal(gw.context(), Const.G_PREV_SAVED, "1");
        LogX.i("[FN2] saved previous slot1 CiwlanConfig home=" + LogX.modeName(home)
                + " roam=" + LogX.modeName(roam));
    }

    private static void restore(String why) {
        CrossSimSlot1.sync(gw != null ? gw.context() : null, why);
        if (gw == null || gw.etm() == null) {
            LogX.skip("[FN2] restore skipped, service not ready (" + why + ")");
            return;
        }
        if (!appliedOnly && Prefs.readGlobalInt(gw.context(), Const.G_PREV_SAVED, 0) != 1) {
            LogX.i("[FN2] nothing to restore (" + why + ")");
            return;
        }
        int home = Prefs.readGlobalInt(gw.context(), Const.G_PREV_HOME, Const.CIWLAN_PREFERRED);
        int roam = Prefs.readGlobalInt(gw.context(), Const.G_PREV_ROAM, Const.CIWLAN_ONLY);
        if (home == Const.CIWLAN_ONLY && roam == Const.CIWLAN_ONLY) {
            home = Const.CIWLAN_PREFERRED;
            roam = Const.CIWLAN_ONLY;
        }
        try {
            Object before = gw.getUserPref(Const.SLOT_TARGET);
            LogX.i("[FN2] restore before " + gw.describeConfig(before) + " why=" + why);
            Object cfg = gw.newCiwlanConfig(home, roam);
            Object token = gw.setUserPrefSlot1(cfg);
            LogX.i("[FN2] restored slot1 home=" + LogX.modeName(home)
                    + " roam=" + LogX.modeName(roam) + " token=" + token);
            appliedOnly = false;
            gw.handler().postDelayed(() -> {
                gw.dumpSlot(Const.SLOT_TARGET);
                LogX.i("[FN2] restored slot1 now " + gw.describeConfig(gw.getUserPref(Const.SLOT_TARGET)));
            }, Const.FN2_REQUERY_MS);
        } catch (Throwable t) {
            LogX.e("[FN2] restore failed (" + why + ")", t);
            LogX.skip("[FN2] cannot invent a partition/NV workaround to restore CiwlanConfig");
        }
    }
}
