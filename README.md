# CIWLAN Fix

## 为什么需要

国行手机一般绑不了 eSIM。要用海外号，得先把 eSIM 写到小白卡上（能写入 eSIM 的实体卡），再当卡 2 插进去；卡 1 还是国内 SIM，用来上网。

国行安卓有个问题：就算手动画网，把卡 2 的 eSIM 搞到无服务，也不能像 iPhone 那样让这张卡借卡 1 的流量走 Wi-Fi Calling。这个模块要做的，就是让国行安卓有接近 iPhone 的 eSIM 体验——把卡 2 锁在无服务，再借卡 1 的流量打电话、收短信。

我只在一台国行小米 17 Pro（25098PN5AC，HyperOS 3）上打通过。高通小米、设置里能看到「通话辅助」、而且有 `com.qti.phone` 和 `vendor.qti.iwlan` 的，可以自己试试。联发科和其他品牌别装。

## 原理

卡 2 得先保持无服务。一旦连上国内漫游，电话短信就会走漫游，又贵，也不是 Wi-Fi Calling。

然后打开卡 2 的跨卡 / CIWLAN，让它只走卡 1 的数据。系统自己会拦，所以还得 hook 一下，让它以为 IMS 已经走了 IWLAN，不然打不出去。

家里连上 Wi-Fi 会自动改回普通 Wi-Fi 通话。关掉开关会还原。只 hook 运行时，不写分区、不改 NV。

## 怎么用

APK 从 [Releases](https://github.com/dddwill8/ciwlan-fix/releases) 下。不想自己编的话，[Actions](https://github.com/dddwill8/ciwlan-fix/actions) 里也有 `app-debug`。没有桌面图标，设置从 **LSPosed → CIWLAN Fix** 进。

1. `adb install -r` 装上。
2. LSPosed 里启用模块，勾上 `com.qti.phone` 和 `vendor.qti.iwlan`，重启。`com.android.phone` 不用勾。
3. 系统设置打开「通话辅助」，数据开在卡 1。找不到菜单的话看下面。
4. 进模块把开关打开。测的时候 Wi-Fi 开着就行，先别连热点——连上了会走普通 Wi-Fi 通话，看不出模块有没有生效。

这个模块不依赖 HyperCeiler，但系统「通话辅助」得开着（打开后 `cross_sim_call_1` 会变成 `1`）。国行小米经常把这个菜单藏起来，藏起来了才需要 HyperCeiler：**电话服务 → 解锁通话辅助**，重启后再去系统设置里打开「通话辅助」。菜单本来就在、也能打开，就不用装 HyperCeiler。

HyperCeiler 里「启用网络类型选择菜单」和这个模块无关，开不开都行。

开关写不进去的话，在电脑跑这句：

```bash
adb shell su 0 pm grant dev.ciwlanfix.lsposed android.permission.WRITE_SECURE_SETTINGS
```

卸的时候先关模块里的开关，等它把卡 2 改回去，再去 LSPosed 禁用、重启，最后才卸 APK。直接禁模块或者直接卸，卡 2 可能还停在无服务。

日志：`adb logcat -s CIWLAN_FIX:D`。自己编：`./gradlew :app:assembleDebug`。

## License

[MIT](LICENSE)
