package dev.ciwlanfix.lsposed.xposed;

import android.util.Log;

final class LogX {
    private LogX() {}

    static void i(String msg) {
        Log.i(Const.TAG, msg);
    }

    static void w(String msg) {
        Log.w(Const.TAG, msg);
    }

    static void e(String msg) {
        Log.e(Const.TAG, msg);
    }

    static void e(String msg, Throwable t) {
        Log.e(Const.TAG, msg, t);
    }

    static void skip(String reason) {
        Log.e(Const.TAG, "SKIP: " + reason);
    }

    static String modeName(int mode) {
        switch (mode) {
            case Const.CIWLAN_ONLY:
                return "ONLY(0)";
            case Const.CIWLAN_PREFERRED:
                return "PREFERRED(1)";
            case Const.CIWLAN_UNSUPPORTED:
                return "UNSUPPORTED(2)";
            case Const.CIWLAN_INVALID:
                return "INVALID(-1)";
            default:
                return "UNKNOWN(" + mode + ")";
        }
    }

    static String selectionName(int mode) {
        switch (mode) {
            case 0:
                return "UNKNOWN(0)";
            case 1:
                return "AUTO(1)";
            case 2:
                return "MANUAL(2)";
            default:
                return "OTHER(" + mode + ")";
        }
    }
}
