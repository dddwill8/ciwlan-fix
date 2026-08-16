# CIWLAN Fix

LSPosed module that lets **SIM 2 (slot 1)** place calls over **SIM 1’s cellular data** when SIM 2 has no service and Wi-Fi is on but not associated to an AP (Android Cross-SIM / QTI CIWLAN / ePDG-over-cellular).

在卡 2 无服务、Wi-Fi 开着但没连热点时，用卡 1 的蜂窝数据给卡 2 打电话。

**Tested on:** Xiaomi 17 Pro (Qualcomm) / HyperOS / Android 16, LSPosed, Magisk. Other QTI devices may work; no promises.

## What it does

One switch in the LSPosed module settings: **开启卡 2 通话辅助**. Turning it on runs three things together (they are not useful separately):

1. Keep slot 1 from camping on a local network (`setNetworkSelectionModeManual("99999", persist=false)`).
2. Set slot 1 QTI `CiwlanConfig` to `ONLY/ONLY` (or restore `PREFERRED/ONLY` when a real home AP is associated).
3. Inject slot 1 QNS IMS as IWLAN (`5`) and report WLAN PS as HOME so IMS can register over the other SIM’s data.

Turning the switch **off** restores slot 1 automatic network selection and the previous CiwlanConfig. Cross-SIM on the subscription is left on if Xiaomi 通话辅助 (`cross_sim_call_1`) is still on.

Settings are only reachable from **LSPosed → CIWLAN Fix** (no launcher icon).

## Scope

Enable the module for:

| Package | Required |
| --- | --- |
| `com.qti.phone` | Yes |
| `vendor.qti.iwlan` | Yes |
| `com.android.phone` | **No** (do not tick by default) |

## Safety

This module is **runtime-only**: LSPosed hooks and public/hidden telephony APIs.

It will **not**:

- Flash, format, or write any partition
- Use Magisk overlay / remount of system or vendor
- Write QCN / EFS / MBN / NV, or use QPST / QFIL
- Write SIM FPLMN / EHPLMN
- Power down the UICC or call `setUiccApplicationsEnabled(false)` on slot 1
- Use `persist=true` network selection
- Write global `CELLULAR_I_WLAN_PREFERENCE_STATE_KEY` (that can break slot 0)

If an API cannot be used safely, it logs `CIWLAN_FIX` / `SKIP` and does nothing.

**Fake-locked / “假回锁” devices:** do not flash partitions. This APK only lives in `/data`.

**To disable cleanly:** turn the switch off → wait for restore in `adb logcat -s CIWLAN_FIX:D` → disable the module in LSPosed → reboot. Do not disable the module first, or ONLY/99999 can stick.

## Install

1. Install the debug APK from [GitHub Actions](https://github.com/dddwill8/ciwlan-fix/actions) (`app-debug` artifact) or build it yourself.
2. LSPosed → enable **CIWLAN Fix** → scope `com.qti.phone` and `vendor.qti.iwlan`.
3. Reboot. LSPosed package updates need a reboot.
4. Optional: `adb shell su 0 pm grant dev.ciwlanfix.lsposed android.permission.WRITE_SECURE_SETTINGS`

Keep Xiaomi 通话辅助 / Cross-SIM on. SIM 1 stays the data SIM. For Cross-SIM (not ordinary WFC), leave Wi-Fi on but do not join an AP.

## Logs

```bash
adb logcat -s CIWLAN_FIX:D
```

The in-app “专业日志” block is a snapshot of a few Global keys. Success is a real SIM 2 call (IMS over `MOBILE[IWLAN]`), not green UI text.

## Build

JDK 17, Android SDK compileSdk 36. Or push to `main` and use Actions.

```bash
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## License

Copyright © 2026 dddwill8. No license is granted; ask before reuse or redistribution.
