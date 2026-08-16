package dev.ciwlanfix.lsposed.xposed;

final class Const {
    static final String TAG = "CIWLAN_FIX";
    static final String MODULE_PKG = "dev.ciwlanfix.lsposed";
    static final String PREF_FILE = "ciwlan_fix";

    static final String PKG_QTI_PHONE = "com.qti.phone";
    static final String PKG_ANDROID_PHONE = "com.android.phone";
    static final String PKG_IWLAN = "vendor.qti.iwlan";

    static final int SLOT_FORBIDDEN = 0;
    static final int SLOT_TARGET = 1;

    static final int FEATURE_GET_CIWLAN_CONFIG = 103;
    static final int FEATURE_CIWLAN_MODE_PREFERENCE = 105;

    static final int CIWLAN_INVALID = -1;
    static final int CIWLAN_ONLY = 0;
    static final int CIWLAN_PREFERRED = 1;
    static final int CIWLAN_UNSUPPORTED = 2;

    static final int ACCESS_MODE_PLMN = 1;
    static final int ACCESS_NETWORK_EUTRAN = 3;
    static final int ACCESS_NETWORK_IWLAN = 5;
    static final int APN_TYPE_IMS = 64;

    static final String K_FN1 = "force_oos_slot1";
    static final String K_PLMN = "force_oos_plmn";
    static final String K_FN2 = "set_ciwlan_only_slot1";
    static final String K_FN3 = "force_qns_iwlan_fallback";

    static final String G_FN1 = "ciwlan_fix_force_oos_slot1";
    static final String G_PLMN = "ciwlan_fix_force_oos_plmn";
    static final String G_FN2 = "ciwlan_fix_set_ciwlan_only_slot1";
    static final String G_FN3 = "ciwlan_fix_force_qns_iwlan_fallback";

    static final String G_PREV_HOME = "ciwlan_fix_slot1_prev_home";
    static final String G_PREV_ROAM = "ciwlan_fix_slot1_prev_roam";
    static final String G_PREV_SAVED = "ciwlan_fix_slot1_prev_saved";
    static final String G_FN2_DONE = "ciwlan_fix_fn2_done";
    static final String G_SLOT1_CIWLAN_AVAILABLE = "ciwlan_fix_slot1_ciwlan_available";
    static final String G_SLOT1_EPDG_CELL = "ciwlan_fix_slot1_epdg_over_cellular";
    static final String G_QNS_SLOT1_IMS_PREF = "ciwlan_fix_qns_slot1_ims_pref";
    static final String G_FN3_LATCHED = "ciwlan_fix_fn3_latched";
    static final String G_SEEN_QNS3 = "ciwlan_fix_seen_qns_slot1_pref3";
    static final String G_FN3_WLAN_REG = "ciwlan_fix_fn3_wlan_reg";
    static final String G_FN3_SETUP = "ciwlan_fix_fn3_setup";
    static final String ACTION_FN3_STATUS = "dev.ciwlanfix.lsposed.FN3_STATUS";

    static final String FN3_AUTO = "auto";
    static final String FN3_ON = "on";
    static final String FN3_OFF = "off";

    static final String DEFAULT_PLMN = "99999";
    static final String CROSS_SIM_CALL_1 = "cross_sim_call_1";
    static final String G_CROSS_SIM_SUB1 = "ciwlan_fix_cross_sim_sub1";

    static final long FN1_MIN_INTERVAL_MS = 15_000L;
    static final long FN1_MAX_BACKOFF_MS = 120_000L;
    static final long PREF_POLL_MS = 2_000L;
    static final long FN2_REQUERY_MS = 1_200L;
    static final long CROSS_SIM_IDLE_MS = 30_000L;

    private Const() {}
}
