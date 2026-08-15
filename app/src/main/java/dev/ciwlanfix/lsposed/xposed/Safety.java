package dev.ciwlanfix.lsposed.xposed;

final class Safety {
    private Safety() {}

    static void assertTargetSlot(int slot, String op) {
        if (slot != Const.SLOT_TARGET) {
            throw new IllegalArgumentException(
                    "CIWLAN_FIX refuses " + op + " on slot=" + slot + " (only slot 1 is allowed)");
        }
    }

    static boolean persistIsFalse(Boolean persist) {
        return persist != null && !persist;
    }

    static void refuseForbidden(String api) {
        LogX.skip("refused forbidden API: " + api);
    }
}
