package dev.ciwlanfix.lsposed.xposed;

import android.app.Notification;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Drop slot-2 "couldn't connect to selected network" toasts. */
final class NotifyGuard {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static long lastBlockLogMs;

    private NotifyGuard() {}

    static void install(ClassLoader cl) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        Class<?> nm = Reflects.findOrNull(cl, "android.app.NotificationManager");
        if (nm == null) {
            LogX.w("[UI] NotificationManager not found");
            return;
        }
        for (Method m : nm.getDeclaredMethods()) {
            if (!"notify".equals(m.getName()) || m.getParameterCount() < 1) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (block(param.args)) {
                            param.setResult(null);
                        }
                    }
                });
            } catch (Throwable t) {
                LogX.w("[UI] hook notify: " + t);
            }
        }
        LogX.i("[UI] NotificationManager.notify filter installed");
    }

    private static boolean block(Object[] args) {
        if (args == null) {
            return false;
        }
        Notification n = null;
        for (Object a : args) {
            if (a instanceof Notification) {
                n = (Notification) a;
                break;
            }
        }
        if (n == null || n.extras == null) {
            return false;
        }
        CharSequence title = n.extras.getCharSequence("android.title");
        CharSequence text = n.extras.getCharSequence("android.text");
        CharSequence ticker = n.tickerText;
        String s = ((title == null ? "" : title) + " " + (text == null ? "" : text)
                + " " + (ticker == null ? "" : ticker)).toLowerCase();
        if (s.contains("99999")
                || s.contains("46001") || s.contains("46011") || s.contains("46015")
                || s.contains("所选网络")
                || s.contains("无法连接到所选")
                || s.contains("无法找到网络")
                || s.contains("couldn't connect to the network")
                || s.contains("couldn't find")
                || s.contains("selected network")
                || s.contains("network you selected")) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastBlockLogMs > 10_000L) {
                lastBlockLogMs = now;
                LogX.i("[UI] blocked slot2 network notification: " + s.trim());
            }
            return true;
        }
        return false;
    }
}
