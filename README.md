# CIWLAN Fix

LSPosed 模块：在 **卡 2（slot 1）没有蜂窝服务**、Wi-Fi 开着但 **没有连上热点** 时，让卡 2 走 **卡 1 的蜂窝数据** 打电话（Cross-SIM / QTI CIWLAN / ePDG-over-cellular）。

设置页只有一个开关：**开启卡 2 通话辅助**。从 **LSPosed → CIWLAN Fix** 进入（没有桌面图标）。

下载：[Releases](https://github.com/dddwill8/ciwlan-fix/releases)

---

## 能用的机型

**只在一台机器上打通过：** 大陆小米 17 Pro（高通，25098PN5AC / pandora），Android 16，HyperOS 3，root + LSPosed。

代码里没有型号、没有 HyperOS 版本检查。17 Pro 是样机，不是适用范围。

| 范围 | 判断 |
| --- | --- |
| 高通小米，设置里有「通话辅助」，且存在 `com.qti.phone`、`vendor.qti.iwlan` | **目标机型，可以试。** HyperOS 2/3、Android 15/16 都算，不要求同代 17、不要求必须 HyperOS 3。 |
| 高通小米，但没有通话辅助，或 modem 没有 CIWLAN | 钩子可能挂上，跨卡不会通。 |
| 其他品牌高通 | **当前这包不要试。** 功能 3 看小米 `cross_sim_call_1`，键不是 1 就不跑。 |
| 联发科 / 三星 / Pixel / 没有上面两个包 | **不能用。** |

成功标准只有一个：**卡 2 能打通电话**（`MOBILE[IWLAN] extra:ims`）。设置页绿字、`setupDataCall`、IKE 都不算。

试之前：

```bash
adb shell pm path com.qti.phone
adb shell pm path vendor.qti.iwlan
adb shell settings get global cross_sim_call_1
```

两个 `pm path` 都要有。`cross_sim_call_1` 应是 `1`（系统「通话辅助」打开后）。

---

## HyperCeiler

验证机上开过 HyperCeiler 两项。没有做过开关对照，所以不能写成「不开也能打通」。按代码和因果链：

| HyperCeiler（电话服务） | 判断 |
| --- | --- |
| **解锁通话辅助** | **菜单被隐藏时需要。** 它只是让 `MiuiPhoneUtils.isSupportVoiceLinkFeature` 恒为 true，把系统「通话辅助」露出来。本模块不解锁菜单；功能 3 仍要求 `cross_sim_call_1=1`。设置里已经能看到并打开通话辅助，就不必开这项。解锁后还要自己去系统设置打开。 |
| **启用网络类型选择菜单** | **不是必要项。** 只是不让系统拿掉 5G/LTE/3G 首选菜单。本模块锁的是无效 PLMN `99999`，不读这个菜单。开着无害，别当成依赖。 |

本模块不依赖 HyperCeiler 进程，也不把它列入 LSPosed 作用域。

---

## 原理

跨卡通话：卡 2 的 IMS 不走卡 2 蜂窝，而走 **卡 1 已经通的数据**，modem 里拉 ePDG。AP 上看到 `rmnet_data*` + `MOBILE[IWLAN]`，不是 Google 的 `IkeSession`。

缺任何一段，拨号器会弹「移动网络不可用，需连接无线网络」——那是普通 WFC，不是跨卡。

```
卡 2 语音要 OOS（不能驻上移动 Ultra）
    → 功能 1：setNetworkSelectionModeManual("99999", persist=false)

小米「通话辅助」= settings global cross_sim_call_1=1
    → 这只是 UI。ImsManager 读订阅库 cross_sim_calling_enabled
    → 模块对 slot 1 的真实 subId 调 ImsMmTelManager.setCrossSimCallingEnabled(true)
      （公开 SDK 没有 createForSubscriptionId，运行时反射）

slot 1 的 QTI CiwlanConfig = ONLY/ONLY
    → 功能 2：ExtTelephonyManager.setCiwlanModeUserPreference(slot=1)
    → 只动 slot 1。不写 CELLULAR_I_WLAN_PREFERENCE_STATE_KEY（会伤卡 1）

QNS 把 slot 1 IMS 优选报成 5 (IWLAN)
    → 卡 2 OOS 时 modem 常常不报，或报 3 (EUTRAN)
    → 功能 3：在 vendor.qti.iwlan 注入 updateQualifiedNetworkTypes(IMS, [5])

WLAN PS 必须是 HOME / ROAMING
    → AOSP DataNetworkController：优选 WLAN 但 WLAN 未注册会硬拒
    → 功能 3：把 getNetworkRegistrationInfo 的 WLAN 伪造成 HOME/IWLAN

约 4–8 秒后 ImsPhone[1] onImsMmTelConnected imsTransportType=WLAN
    → rmnet 上出现运营商 IMS，才能用卡 2 打电话
```

连上家里 Wi-Fi 后让开跨卡：功能 3 停，CiwlanConfig 改回保存过的 previous（或 `PREFERRED/ONLY`），走普通 WFC。热点断开再回到 `ONLY/ONLY`。

重启后 `com.android.phone` 会把 `siminfo.cross_sim_calling_enabled` 写成 0。模块在 `com.qti.phone` 启动时按 slot 1 的真实 subId 再写回 true（不写死 subId=1）。

---

## 开关实际做了什么

打开 = 下面三件事一起做，缺一不可；关掉 = 还原选网和 CiwlanConfig。

| 内部 | 做什么 | 还原 |
| --- | --- | --- |
| 功能 1 | slot 1 的 `TelephonyManager.setNetworkSelectionModeManual("99999", false)`。modem 不记住 persist=false，离开 OOS 会再锁。 | `setNetworkSelectionModeAutomatic()` |
| 功能 2 | 绑定 `com.qti.phone/.ExtTelephonyService`，只把 **slot 1** 设成 `CiwlanConfig(ONLY, ONLY)`。跳过 `comparePreferences(slot=1)`。 | 写回打开前的 home/roam，读不到则 `PREFERRED/ONLY` |
| 功能 3 | Hook `vendor.qti.iwlan` 的 QNS / WLAN 注册；已 latch 且已是 `[5]` 不再每 8 秒空转。连上真 AP 则停。 | 停注入、停改写 |

通话辅助仍开着时，关开关 **不会** `setCrossSimCallingEnabled(false)`。

不用 `service call qti.radio.extphone`。不用 `ExtTelephonyManager.setNetworkSelectionModeManual`（没有 persist，会变成 modem 永久手动网）。

---

## LSPosed 作用域

| 包名 | 要不要勾 |
| --- | --- |
| `com.qti.phone` | **必须** |
| `vendor.qti.iwlan` | **必须** |
| `com.android.phone` | **不要勾**。只有功能 1 在 qti.phone 里权限失败时再考虑 |

---

## 安全

只做运行时 Hook 和公开/隐藏电话 API。不会：

- 刷写 / 格式化任何分区
- Magisk overlay、remount system/vendor
- QCN / EFS / MBN / NV / QPST / QFIL
- 写 SIM FPLMN / EHPLMN
- 对 slot 1 做 UICC 下电 / `setUiccApplicationsEnabled(false)`
- `persist=true` 选网
- 写全局 `CELLULAR_I_WLAN_PREFERENCE_STATE_KEY`

做不到就打 `CIWLAN_FIX` / `SKIP`，不另找旁路。假回锁设备：这个 APK 只在 `/data`。

**停用：** 先关开关 → `adb logcat -s CIWLAN_FIX:D` 看到还原 → 再去 LSPosed 禁用 → 重启。先禁模块，ONLY 和 99999 会留在卡 2 上。

---

## 安装

1. 从 [Releases](https://github.com/dddwill8/ciwlan-fix/releases) 下载 APK（或 [Actions](https://github.com/dddwill8/ciwlan-fix/actions) 的 `app-debug`）。
2. `adb install -r` 装上。
3. LSPosed 启用模块，勾 `com.qti.phone`、`vendor.qti.iwlan`，**重启**。
4. 系统「通话辅助」打开。卡 1 当数据卡。测跨卡时 Wi-Fi 开着、先别连热点。
5. 可选：`adb shell su 0 pm grant dev.ciwlanfix.lsposed android.permission.WRITE_SECURE_SETTINGS`

换包后必须再重启，钩子才是新的。

---

## 日志

```bash
adb logcat -s CIWLAN_FIX:D
```

设置页「现在怎样」给人看；「专业日志」是 Global 快照。QNS / latched 经常是空的（iwlan 进程写不了 Settings.Global），以 logcat 为准。

---

## 编译

JDK 17、compileSdk 36。没有 SDK 就 push `main`，用 GitHub Actions。

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

---

## License

[MIT](LICENSE) © 2026 dddwill8
