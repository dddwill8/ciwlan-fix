# CIWLAN Fix

国行安卓插一张海外卡当卡 2，本来很难像 iPhone 那样用：有 Wi-Fi 时打 Wi-Fi 电话，没 Wi-Fi 时借国内卡的流量打电话、收短信。这个 LSPosed 模块就是做这件事的。

只改运行时电话接口，不刷分区、不写 SIM。卡 2 锁无服务会写成 modem 里的手动选网（关掉开关会还原）。

## 这是干啥的

常见用法：卡 1 是国内卡（上网），卡 2 是海外号（写在小白卡上的 eSIM，或实体外卡）。

国行系统会拦两件事：

1. **Wi-Fi 通话被藏起来。** 外卡明明支持 Wi-Fi Calling，设置里没有开关，不拨 `*#*#869434#*#*` 用不了。
2. **没 Wi-Fi 时，外卡不会借国内卡的流量打电话。** 它要么没服务，要么去连国内漫游网（比如显示 Ultra），一打电话就是国际漫游费。

打开模块后：

- **有 Wi-Fi：** 卡 2 走普通 Wi-Fi 通话。不用先拨暗码。
- **没 Wi-Fi：** 卡 2 保持无服务，借卡 1 的流量走跨卡通话（CIWLAN）。
- 卡 2 不会去驻留国内网，避免 Ultra 这类漫游。手动选网会持久化，避免大约每 25 分钟闪一次无服务。
- 状态栏可以出现 VoWiFi 图标。
- **不需要 HyperCeiler。** 通话辅助、Wi-Fi 通话开关、图标，模块自己解开。

关掉模块开关会还原。

## 谁能用

我只在一台国行小米 17 Pro（25098PN5AC，HyperOS 3）上打通过。高通小米、能看到电话相关设置、而且有 `com.qti.phone` 和 `vendor.qti.iwlan` 的，可以自己试试。联发科和其他品牌别装。

手机需要 Root + LSPosed（Zygisk）。假回锁（abl / efisp）可以用，模块不碰分区。

## 怎么装

1. 从 [Releases](https://github.com/dddwill8/ciwlan-fix/releases) 或 [Actions](https://github.com/dddwill8/ciwlan-fix/actions) 里的 `app-debug` 下 APK。
2. `adb install -r` 装上。没有桌面图标。
3. 打开 LSPosed，启用 **CIWLAN Fix**，把推荐应用都勾上（模块会提示）。至少要有：

   - `com.qti.phone`
   - `vendor.qti.iwlan`
   - `com.android.phone`
   - `org.codeaurora.ims`
   - `com.android.settings`
   - `com.android.systemui`
   - `miui.systemui.plugin`
   - `com.android.imsserviceentitlement`

4. 重启。
5. LSPosed → CIWLAN Fix，打开「开启卡 2 通话辅助」。数据开在卡 1。

开关写不进去的话，在电脑执行：

```bash
adb shell su 0 pm grant dev.ciwlanfix.lsposed android.permission.WRITE_SECURE_SETTINGS
```

## 怎么用

打开开关就行。

| 你在干什么 | 卡 2 会怎样 |
| --- | --- |
| 连着家里 / 公司 Wi-Fi | 普通 Wi-Fi 通话 |
| Wi-Fi 关掉，只用卡 1 流量 | 借卡 1 流量打电话、收短信 |
| 人在国内 | 卡 2 保持无服务，不连 Ultra / 国内漫游网 |

测跨卡那条路时，先别连 Wi-Fi：连上了会走普通 Wi-Fi 通话，看不出跨卡有没有生效。

T-Mobile 等美卡第一次开 Wi-Fi 通话，运营商有时还要紧急地址。模块会跳过手机上的开通网页；如果仍然注册不上，需要在运营商 App 或网页里补一次地址。

## 怎么卸

1. 先关掉模块里的开关，等它把卡 2 改回自动选网（看设置页或日志确认）。
2. 再去 LSPosed 禁用模块，重启。
3. 最后才卸 APK。

直接禁模块或直接卸，卡 2 会停在无服务，而且这次锁网是持久的，重启也还在。必须先关开关还原。

## 自己编

```bash
./gradlew :app:assembleDebug
```

APK 用仓库里的 `app/ciwlan-fix.keystore` 签名，云编译和本地编出来的包可以互相覆盖安装。

日志：

```bash
adb logcat -s CIWLAN_FIX:D
```

## License

[MIT](LICENSE)
