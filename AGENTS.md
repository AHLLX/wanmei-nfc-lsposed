# 完美校园 NFC 集成模组（wanmei-nfc-lsposed）

在小米智能卡（MiPay）双击电源键刷卡页注入「完美校园」按钮：单击临时切默认 NFC 并显示倒计时徽标，30 秒自动还原；长按直达完美校园校园卡页面。
人类文档看 `README.md`；完整开发/验证/发布流程看技能 `android-lsposed-hook-module`；这里只放每次会话都必须知道的事实与约束。

## 必知事实

| 项 | 值 |
| :--- | :--- |
| 目标 App | `com.miui.tsmclient`（小米智能卡；与 `com.android.nfc` 同 UID 1027，**没有** `START_ANY_ACTIVITY`） |
| 依赖 App | `com.newcapec.mobile.ncp`（完美校园）；HCE 服务 `cn.newcapec.hce.service.CapecHostApduService` |
| 私有页面 | `com.newcapec.mobile.virtualcard.acivity.VirtualCard_NFC`（未导出，只能由 system_server 代拉起） |
| 系统设置键 | `nfc_payment_default_component` |
| 自定义标记键 | `wanmei_nfc_lsp_pending_restore`（值 = arm 时刻 elapsedRealtime，用于倒计时与定时器）、`wanmei_nfc_lsp_launch_request`（请求直达页面） |
| 钱包角色 | `android.app.role.WALLET`（会跟随上面的设置切换） |
| 日志 TAG | `WanmeiNfcLsp` |
| 实测机 | 24122RKC7C / Android 16 (SDK 36) / HyperOS V816 / KernelSU root |

## 唯一正确的命令

```bash
./gradlew assembleRelease                                  # 构建（JDK 17~21，路径见 gradle.properties）
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell su -c "am force-stop com.miui.tsmclient"          # 只改 MainHook 时
adb shell su -c "setprop ctl.restart zygote"                # 改了 SystemHook（system_server）后必须软重启框架
adb shell "logcat -d -s WanmeiNfcLsp:V | tail -50"          # 必须带 -s，见下方 NEVER
adb shell settings get secure nfc_payment_default_component # 看真实默认 NFC
python tools/verify_nfc_flow.py                             # 真机自动回归（uiautomator2）
```

## 硬性约束

- **IMPORTANT**: LSPosed 作用域必须同时勾选 `系统框架(android)` 与 `小米智能卡(com.miui.tsmclient)`。缺 android 时 30 秒定时器与「长按直达页面」都不工作，而这点在目标 App 侧没有任何报错。
- **NEVER** 在小米智能卡进程里用 `Settings.Secure.getString` 判断默认 NFC：该进程内它被我们自己的 `getStringForUser` 伪装过，会永远返回小米钱包。一律用 `NfcUtils.readSettingDirect` 直查 settings 数据库。
- **NEVER** Hook 完美校园 App 本身（360 加固会闪退反噬）；跨进程协作只走 `Settings.Secure` 标记 + ContentObserver。
- **NEVER** 用不带 `-s` 的 `logcat | grep | tail -1`：adbd 会回显你执行的命令，取到的是回声。
- 定时器与需要 system uid 的动作只能放 system_server（目标 App 进程会被冻结/杀掉）。
- 还原默认 NFC 时还原「用户原本的值」（`NfcUtils.resolveDefault()`），不要写死小米钱包。
- 注入按钮 dp 常量的唯一来源是 `WanmeiButtonView` 的 companion，不要在 `MainHook` 里再写一份 130/48。

## 代码地图

| 文件 | 职责 |
| :--- | :--- |
| `app/src/main/java/com/mipay/wanmei/lsp/NfcUtils.kt` | 直查/写设置、写后读回、记忆用户默认值、pending/launch 标记、日志 |
| `MainHook.kt` | 小米智能卡进程：onCreate/onResume 还原与注入、CardEmulation 与 getStringForUser 伪装、布局重试 |
| `SystemHook.kt` | system_server：设置监听、30 秒自动还原、代拉起私有页面、Activity 启动还原（含旧 ROM 兼容 Hook） |
| `WanmeiButtonView.kt` | 自绘胶囊 + 右上角倒计时徽标，500ms 轮询，点击切卡 / 长按直达 |
| `tools/verify_nfc_flow.py` | uiautomator2 真机回归：定位按钮 → 点击重试 → 断言切卡/倒计时 → 断言 30 秒还原 |

## 坑（现象 → 根因 → 解法）

| 现象 | 根因 | 解法 |
| :--- | :--- | :--- |
| 改完代码真机没反应 | 进程里还是旧 dex | `am force-stop` 目标 App；动到 SystemHook 就软重启框架 |
| install 后第一次启动没注入 | LSPosed 在重新优化模组 APK | 启动重试 2~3 次（`verify_nfc_flow.py` 已内置） |
| 长按/定时器无效但无报错 | 少了 `android` 作用域 | 勾上并重启手机 |
| 徽标恒为「未启用」 | 被本进程的 `getStringForUser` 伪装污染 | 直查 settings 数据库 |
| 启动 `VirtualCard_NFC` 被拒 | Android 14+ 未导出页面 + 目标 App 无 `START_ANY_ACTIVITY` | 由 system_server 代拉起 |
| 自动点击第一次无效 | 系统吞掉首次注入触摸 | 点击后校验状态并重试（脚本已实现） |
| `gradlew` 报 Java home invalid | `org.gradle.java.home` 指向了被删掉的 JDK | 改成真实存在的 JDK 17~21 路径 |

## 当前状态

- 版本：`v0.2.0`（versionCode 200），tag 与 Release 资产 `wanmei-nfc-lsposed_v0.2.0.apk` 已发布；master 上另有发布后的文档/工具提交。
- 真机已验证：单击切卡、徽标倒计时、30 秒自动还原（实测 29.0s）、窗口期内重进刷卡页保持可刷卡、长按直达 `VirtualCard_NFC`。
- 待办：到闸机实测刷校园卡（需用户本人操作）；可选：连续 N 次循环回归压稳定性。
