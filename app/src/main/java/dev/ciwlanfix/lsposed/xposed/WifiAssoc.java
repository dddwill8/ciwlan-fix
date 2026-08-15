package dev.ciwlanfix.lsposed.xposed;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

final class WifiAssoc {
    private WifiAssoc() {}

    static boolean associated(Context ctx) {
        try {
            WifiManager wm = ctx.getSystemService(WifiManager.class);
            if (wm != null && wm.isWifiEnabled()) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null
                        && info.getNetworkId() != -1
                        && info.getSupplicantState() == SupplicantState.COMPLETED) {
                    return true;
                }
            }
        } catch (Throwable t) {
            LogX.w("WifiManager association check failed: " + t);
        }
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            if (cm == null) {
                return false;
            }
            Network[] networks = cm.getAllNetworks();
            if (networks == null) {
                return false;
            }
            for (Network n : networks) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            LogX.w("ConnectivityManager wifi check failed: " + t);
        }
        return false;
    }
}
