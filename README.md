# CIWLAN Fix（LSPosed）

## 假回锁安全规则（先读这一段）

这台大陆版小米 17 Pro 是 **假回锁**。除了你自己已经在用的 boot / init_boot 流程以外，**碰任何分区都可能变砖**。

本模块 **只允许**：

- 运行时 LSPosed Hook
- 公开 / 隐藏电话 API（`TelephonyManager`、`ExtTelephonyManager` / `IExtPhone`、`vendor.qti.iwlan` 的 QNS 回调）

本模块 **禁止**，代码里也不会做：

- 写 / 刷 / 格式化 / 扩缩 **任何分区**（boot、init_boot、system、system_ext、vendor、odm、modem、persist、userdata、metadata、super 等）
- Magisk systemless overlay、remount vendor/system、解包重打包 vendor 镜像、往分区里装东西
- QCN / EFS / MBN / NV / QPST / QFIL / 基带校准 / radio 文件系统写入
- 往 SIM 写 FPLMN / EHPLMN 或同类文件
- `setSimPowerState` / UICC 下电 / 对 slot 1 调用 `setUiccApplicationsEnabled(false)`（会干掉 IMS/WFC）
- `persist=true` 的手动选网（会变成 modem/SIM 永久状态）。功能 1 的手动 PLMN **永远 persist=false**
- 写全局 `CELLULAR_I_WLAN_PREFERENCE_STATE_KEY`（会误伤 slot 0）

做不到就 **跳过并打 `CIWLAN_FIX` 日志说明原因**，不会发明分区/NV/SIM 旁路。

可逆性：

1. 先在模块里关掉功能 1 / 2 / 3（或点「关闭全部功能并还原 slot 1」）
2. 等 `adb logcat -s CIWLAN_FIX:D` 出现 restore / automatic
3. 再到 LSPosed 禁用模块并重启

设备上唯一新增物是用户自己装到 `/data` 的这份 LSPosed APK。

---

## 这模块做什么

让 **卡 2 / phoneId 1 / slot 1 / subId 1 的 eSIM Wi-Fi Calling**，在 slot 1 无服务或漫游、Wi-Fi 打开但 **没有关联 AP** 时，走 Android Cross-SIM / CIWLAN / ePDG-over-cellular，使用 **phoneId 0 的蜂窝数据**。

三个功能互相独立，可分别开关。

| 功能 | 默认 | 作用 |
| --- | --- | --- |
| 1 强制 slot 1 无服务 | **关** | 复现你已经验证过的 UI 路径：手动选到无法注册的运营商 → 无服务 / 仅紧急呼叫 |
| 2 设置 QTI CIWLAN 偏好 | **开** | 只把 **slot 1** 设成 `CiwlanConfig(ONLY, ONLY)` |
| 3 QNS IWLAN 回退 | **自动** | 仅当功能 2 之后 `isCiwlanAvailable(1)=false` 且 QNS slot1 IMS 仍报 3(EUTRAN) 时，运行时改报 5(IWLAN) |

## 作用域

| 包名 | 何时需要 |
| --- | --- |
| `com.qti.phone` | **必须**。功能 2；功能 1 的主路径也在这里 |
| `vendor.qti.iwlan` | 只要用功能 3（含默认 auto）就加上 |
| `com.android.phone` | **不要默认勾**。只有功能 1 在 `com.qti.phone` 里 `TelephonyManager` 报权限失败时再加 |

不要勾无关应用。

## 编译

需要本机 Android SDK（compileSdk 36）和 JDK 17。

```bash
# 在仓库根目录
export ANDROID_HOME=/path/to/Android/sdk
./gradlew :app:assembleDebug
```

产物：

```
app/build/outputs/apk/debug/app-debug.apk
```

Release 同样不混淆：

```bash
./gradlew :app:assembleRelease
```

## 安装与启用

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. 打开 LSPosed → 启用 **CIWLAN Fix**
3. 作用域先只勾 `com.qti.phone`
4. 重启
5. 需要功能 3 时再勾 `vendor.qti.iwlan`，再重启一次
6. 可选：`adb shell su 0 pm grant dev.ciwlanfix.lsposed android.permission.WRITE_SECURE_SETTINGS`  
   不授权也能用模块自己的 SharedPreferences；授权后可用 `settings put global` 遥控开关

开关也可以用：

```bash
adb shell settings put global ciwlan_fix_force_oos_slot1 0
adb shell settings put global ciwlan_fix_force_oos_plmn 99999
adb shell settings put global ciwlan_fix_set_ciwlan_only_slot1 1
adb shell settings put global ciwlan_fix_force_qns_iwlan_fallback auto
```

`force_qns_iwlan_fallback` 取值：`auto` / `on` / `off`。

看日志：

```bash
adb logcat -s CIWLAN_FIX:D
```

## 测试步骤

A. 安装 APK，LSPosed 先只开 `com.qti.phone`，重启。

B. slot 0 保持默认移动数据；Wi-Fi 打开但不要关联任何 AP；Cross-SIM / 通话辅助保持开（`cross_sim_call_1=1`）。

C. 打开功能 1。卡 2 应变成「无服务 / 仅紧急呼叫」，且 **不用** 你再去设置里手选运营商。等它自己回到有服务后，日志应出现 re-apply。关掉功能 1 后，slot 1 应回到自动选网。

D. 在 slot 1 无服务时（功能 1 或你自己手选），功能 2 必须打出 slot1 preference `home=ONLY roam=ONLY`。

E. **成功必须同时满足**，缺一条都不算：

- `CIWLAN_FIX` 显示 slot1 ONLY/ONLY
- `isCiwlanAvailable(1)=true` 和/或 `isEpdgOverCellularDataSupported(1)=true`
- QNS slot1 IMS pref network 变成 **5 (IWLAN)**
- 出现真实的 ePDG / IKE / IMS 注册尝试

不要只凭状态栏「通话辅助」、设置开关或 UI 文案宣布成功。

F. 回滚：模块内关掉全部功能 → 确认 slot 1 自动选网、CIWLAN 偏好已还原 → LSPosed 禁用模块 → 重启。确认没有分区 / NV / SIM FPLMN 变化。

## 实现要点（和真机 DEX 对齐）

类从 **宿主 ClassLoader** 解析，不写死 Binder transaction code，也不 `service call qti.radio.extphone`。

已从本机 `extphonelib.jar` / `QtiTelephony.apk` 核对：

- `CiwlanConfig(int home, int roam)`，`ONLY=0 PREFERRED=1 UNSUPPORTED=2 INVALID=-1`
- `ExtTelephonyManager.getInstance(Context)` → `connectService(ServiceCallback)`
- `registerCallback(String, IExtPhoneCallback)`；`ExtPhoneCallbackListener.mCallback` 才是 Stub
- `setCiwlanModeUserPreference(int slot, Client, CiwlanConfig)`
- `FEATURE_GET_CIWLAN_CONFIG=103`，`FEATURE_CIWLAN_MODE_PREFERENCE=105`
- **功能 1 不用** `ExtTelephonyManager.setNetworkSelectionModeManual`：它吃的是 `QtiSetNetworkSelectionMode`，**没有 persist 参数**。模块改走  
  `TelephonyManager.createForSubscriptionId(subId1).setNetworkSelectionModeManual(plmn, false)`

功能 2 只动 slot 1。开启时若 `QtiCiwlanModePreferenceController.comparePreferences(1)` 想把偏好对回 modem，Hook 会跳过 slot 1，避免把 ONLY/ONLY 打回去。slot 0 不拦截。

功能 3 Hook `QualifiedNetworksService.NetworkAvailabilityProvider.updateQualifiedNetworkTypes`。auto 模式要等功能 2 查询完成、`isCiwlanAvailable(1)=false`、并且已经见过 slot1 IMS pref=3，才会改写成 5。

## 回滚清单

1. 打开模块 UI，点「关闭全部功能并还原 slot 1」（功能 1 关、功能 2 关、功能 3 关）
2. `adb logcat -s CIWLAN_FIX:D` 确认：
   - `[FN1] ... setNetworkSelectionModeAutomatic`
   - `[FN2] restored slot1 home=PREFERRED roam=ONLY`（或你原来的值）
3. LSPosed 禁用本模块
4. 重启
5. 不要执行任何 fastboot/刷写/QPST/写分区命令

## 不会做的事

- 不 flash、不 remount、不写 vendor/system
- 不写 QCN / EFS / NV / MBN
- 不写 SIM FPLMN
- 不对 slot 1 做 UICC 下电
- 不用 `persist=true` 选网
- 不改 slot 0 的 CIWLAN / 选网 / 默认数据
