package dev.ciwlanfix.lsposed.xposed;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }
        try {
            switch (lpparam.packageName) {
                case Const.PKG_QTI_PHONE:
                    LogX.i("load " + Const.PKG_QTI_PHONE + " process=" + lpparam.processName);
                    QtiPhoneHooks.install(lpparam);
                    break;
                case Const.PKG_IWLAN:
                    LogX.i("load " + Const.PKG_IWLAN + " process=" + lpparam.processName);
                    Context existing = null;
                    try {
                        existing = AndroidAppHelper.currentApplication();
                    } catch (Throwable ignored) {
                    }
                    Fn3QnsFallback.install(lpparam.classLoader, existing);
                    hookAppCreate(lpparam, Fn3QnsFallback::attachContext);
                    break;
                case Const.PKG_ANDROID_PHONE:
                    LogX.i("load " + Const.PKG_ANDROID_PHONE + " process=" + lpparam.processName
                            + " (optional FN1 scope)");
                    hookAppCreate(lpparam, (ctx) -> Fn1ForceOos.installPhoneProcess(lpparam.classLoader, ctx));
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            LogX.e("handleLoadPackage failed for " + lpparam.packageName, t);
        }
    }

    private static void hookAppCreate(XC_LoadPackage.LoadPackageParam lpparam, AppReady ready) {
        final java.util.concurrent.atomic.AtomicBoolean once =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        AppReady guarded = ctx -> {
            if (ctx == null) {
                return;
            }
            if (once.compareAndSet(false, true)) {
                try {
                    ready.onReady(ctx);
                } catch (Throwable t) {
                    LogX.e("app-ready callback failed", t);
                }
            }
        };
        try {
            Context existing = AndroidAppHelper.currentApplication();
            if (existing != null) {
                guarded.onReady(existing);
            }
        } catch (Throwable t) {
            LogX.w("currentApplication: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Application)) {
                        return;
                    }
                    Application app = (Application) param.thisObject;
                    Context ctx = app.getApplicationContext();
                    if (ctx == null) {
                        ctx = app;
                    }
                    guarded.onReady(ctx);
                }
            });
        } catch (Throwable t) {
            LogX.e("hook Application.onCreate failed", t);
        }
    }

    private interface AppReady {
        void onReady(Context ctx);
    }
}
