# 真机失败记录（改 FN1 / 驻网逻辑前先看）

设备：国行小米 17 Pro（25098PN5AC，HyperOS 3，高通）。卡 1 中国联通上网，卡 2 T-Mobile eSIM 走 WFC / CIWLAN。  
约束：只改运行时电话接口。禁止刷分区、Magisk overlay、QCN/EFS、写 SIM `EF_FPLMN`（`TelephonyManager.setForbiddenPlmns` 就是写这文件）。

当前 **main / 1.0.5**：手动选一个注册不上的国内 PLMN（默认电信 `46011`，可选广电 `46015`、联通 `46001`），`persist=true`。IWLAN HOME 不当成「逃出无服务」。WWAN 一旦 `HOME/ROAMING` 且是 460，再踢回所选网。

下面这些测试包都已删分支，**不要再捡回来当主方案**。

## 不要再试

| 包 | 做法 | 真机结果 |
| --- | --- | --- |
| **1.0.4** 的 `99999` | `setNetworkSelectionModeManual("99999", persist=true)` | 非法 PLMN。大约 **25 分钟**闪一次，系统 toast「无法连接到所选网络」；HPPLMN 仍可能贴上 Ultra / 中国移动。 |
| **1.0.5-test** `test/disable-wwan-slot2` | `setAllowedNetworkTypesForReason(USER, 0)` | 卡 2 USER 变成 0 / UNKNOWN，**WWAN 仍驻 Ultra**。allowed 已是 0 时 FN1 以为锁住了，不再踢网。 |
| **1.0.6-test** `test/radio-off-slot2` | 只对卡 2 `setRadioPower(false)` | 这台机 **两张卡都 `STATE_POWER_OFF`**，都不能打。IWLAN NRI 还在但运营商名空。废案。 |
| **1.0.7-test** `test/gsm-only-and-ux` | 卡 2 USER 只留 `GPRS\|EDGE\|GSM`（`32771`） | LTE/NR Ultra 贴不上。模组仍约 **10 分钟**搜 2G，合并语音变 `OUT_OF_SERVICE` 约 10 秒，界面闪「无服务」。卸模块前必须还原制式，否则卡 2 会停在 2G。 |
| **1.0.8-test** | IWLAN HOME 时把 `voiceRegState` 合成 `IN_SERVICE` | 搜网还在，只是界面假装没掉。**掩耳盗铃**，不当主方案。 |

AOSP `ServiceStateTracker.getCombinedRegState()` 在 IWLAN + WFC 时本来就会把 **SPN 显示**当成在服务。那是显示路径，不能代替把 460 踢掉。

## 版本号别混

| 名字 | 是什么 |
| --- | --- |
| **1.0.5（main，当前）** | 1.0.4 手动选网，PLMN 改成电信/广电/联通，可导出日志 |
| **1.0.5-test** | USER=0，已废弃 |
| 1.0.6-test / 1.0.7-test / 1.0.8-test | 关无线电 / GSM-only / 假 IN_SERVICE，已废弃 |

测试包 `versionCode` 到过 9。当前 main 用 `versionCode` 11，可以直接 `adb install -r` 盖上去。

## 1.0.4 还在用的结论（不要改掉）

- 合并 `ServiceState.getState()` 在备用通话 / IWLAN 起来时是 `IN_SERVICE`。**不能**据此认为 WWAN 逃出 OOS。只看 WWAN 的 NRI（CS/PS `TRANSPORT_WWAN`）。
- 公有 SDK 没有 `getNetworkRegistrationInfo` / 部分 `ServiceState` setter，编译必须反射。
- 小米设置页「通话辅助」是 VoiceLink（`enable_voice_link`），**不是** `ImsMmTelManager.setCrossSimCallingEnabled`。后端跨卡由模块写 `cross_sim_call_1` / `setCrossSimCallingEnabled`。不要让用户去勾小米那个开关当主开关。
- HyperCeiler 不是依赖。`XiaomiUi` 解开通话辅助入口和 VoWiFi 图标。
- 这台高通双卡：对卡 2 `setRadioPower(false)` 会把卡 1 一起关掉。不要再试分卡关无线电。

## 还原（若又踩到旧测试包）

**GSM-only / USER=0 残留**（`cmd phone get-allowed-network-types-for-users -s 1` 只有 `GPRS|EDGE|GSM` 或空）：

```bash
# 二进制 bitmask，不要传十进制。916479 = 改 GSM-only 之前存过的值
adb shell cmd phone set-allowed-network-types-for-users -s 1 11011111101111111111
```

只动 `-s 1`。卡 1 不要改。

**1.0.6-test 两卡无服务**：

```bash
adb shell settings put global ciwlan_fix_force_oos_slot1 0
```

等几秒看两卡是否离开 `POWER_OFF`，再装正式包。

**手动选网残留**（关掉模块开关会走 `setNetworkSelectionModeAutomatic`）：

先关模块里的开关，等卡 2 回到自动选网，再去 LSPosed 取消启用。

## 排查

```bash
adb logcat -s CIWLAN_FIX:D
adb shell cmd phone get-allowed-network-types-for-users -s 0
adb shell cmd phone get-allowed-network-types-for-users -s 1
adb shell settings list global | grep ciwlan
adb shell dumpsys telephony.registry   # Phone Id=1：WLAN NRI 与 WWAN NRI
```

卡 2 正常时：WLAN `HOME` / `IWLAN`，WWAN 不是 `HOME/ROAMING` 的 460，显示名 `T-Mobile备用通话`，`isWifiCallingEnabled=1`，`crossSimCallingEnabled=1`。

WWAN `NOT_REG_SEARCHING` 且小区 `mMcc=460` 只是在搜，还没驻上。`registrationState=HOME` 或 `ROAMING` 才算驻上，这时 FN1 必须再下发手动选网。
