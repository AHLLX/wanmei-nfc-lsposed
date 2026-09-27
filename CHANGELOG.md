# 更新日志 / Changelog

本文件记录本模组的重要变更，版本号对应 GitHub 上的 tag 与 Release。

## [v0.2.0] - 2026-09-27

### 新增 Added
- **按钮状态徽标 + 倒计时**：注入的「完美校园」按钮右上角叠加一个小号胶囊，实时（500ms 轮询）显示系统**真实**的默认 NFC 应用状态——已是完美校园显示绿色「可以刷卡 23s」（距离自动还原的剩余秒数），否则显示红色「未启用」（深浅色模式各一套配色）。
- **30 秒自动还原**：点击切换成功 30 秒后自动切回**用户原本的默认 NFC 应用**。定时器运行在 `system_server`（不会被后台冻结/杀进程影响），触发信号是模组自己写的 `Settings.Secure` 标记 `wanmei_nfc_lsp_pending_restore`，因此用户手动把完美校园设为默认钱包时不会被误还原。窗口期内（徽标「可以刷卡」）双击电源回到刷卡页不会提前还原，由定时器统一收回。
- **真实默认值记忆**：不再硬编码还原成小米钱包，而是记录切换前用户真实的默认 NFC 组件并还原它。
- **写后读回校验**：切卡后立即读回设置确认是否生效，日志输出 `setNfcComponent: target=… applied=…`。
- **单击只切卡、不跳转**：点击按钮只切换默认 NFC，不再跳转任何页面（HCE 刷卡与前台页面无关），留在刷卡页看倒计时即可。
- **长按直达校园卡页面**：长按按钮时写 `wanmei_nfc_lsp_launch_request` 标记，由 system_server（system uid 持有 `START_ANY_ACTIVITY`）拉起私有 `VirtualCard_NFC`。小米智能卡进程没有该权限，Android 14+ / HyperOS 上直接启动会被 `Permission Denial: not exported from uid` 拒绝。
- **主界面诊断信息**：模组 App 现在显示自身版本（读取 versionName）与当前默认 NFC 应用。

### 修复 Fixed
- **状态读取被伪装 Hook 污染**：小米智能卡进程内的 `Settings.Secure.getStringForUser` 会被伪装成"默认 NFC 仍是小米钱包"，导致任何状态显示都会误报「未启用」。现在统一改为直接查 settings 数据库拿真实值。
- **注入视图定位竞态**：原 `btn.post {}` 在窗口尚未测量时会丢失 margin（按钮跑到左上角），改为带重试的定位，并在每次 onResume 校正位置。
- **Hook 串联失败**：`MainHook` 原来 5 个 Hook 共用一个 try/catch，任一失败会导致后续 Hook 全部失效，现改为逐个 Hook 独立捕获并记录日志。
- **system_server 热路径写设置**：`realStartActivityLocked` 中每次 Activity 启动都会写设置，现改为"值相同不写"。

### 变更 Changed
- 还原路径统一使用 `NfcUtils.resolveDefault()`，`setNfcComponent()` 返回是否生效（Boolean）。
- 设置写入改用公开 API `Settings.Secure.putString`，保留 `putStringForUser` 作为旧 ROM 回退。
- **窗口期语义**：`SystemHook` / `MainHook` 在"临时切换窗口"内跳过所有还原路径，保证倒计时期间双击电源回到刷卡页时徽标仍是可刷卡状态。
- `SystemHook` 改用 `ContentObserver` 监听设置变更实现定时与直达，不再依赖 `ActivityTaskSupervisor` 的方法名（Android 16 上旧的 `checkStartAnyActivityPermission` 已不在调用路径上，仅作旧 ROM 兼容保留）。

### 移除 Removed
- 删除死代码 `WanmeiLifecycleHook.kt`（早期方案，因 360 加固风险已禁用且无任何引用）。
- 删除 GPay 时代残留的无用资源：`gpay_pill_bg.xml`、`ic_google_g.xml`、`ic_google_pay.xml`、`ic_google_pay_logo.xml`、`values/colors.xml`。
- 清理 5 个源文件的 UTF-8 BOM 与缺失的文件末尾换行。

### 构建 Build
- 修正 `gradle.properties` 中失效的 `org.gradle.java.home`（原指向不存在的 redhat.java 1.55.0 / JDK 21.0.11，`gradlew` 直接报 "Java home supplied is invalid"）。
- `versionCode 100 → 200`，`versionName v0.1.0 → v0.2.0`。

## [v0.1.0] - 2026-08-01

### 新增 Added
- 首个版本：在小米智能卡 `DoubleClickActivity` 注入「完美校园」按钮。
- 点击自动把 `Settings.Secure.nfc_payment_default_component` 切到完美校园 HCE 服务，并经 `system_server` 放行权限直达 `VirtualCard_NFC`。
- 下一次唤出刷卡页 / 离开完美校园时还原默认 NFC。
