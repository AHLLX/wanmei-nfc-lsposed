# Wanmei NFC LSPosed (完美校园 MIUI/HyperOS NFC 集成模组)

[English](#english) | [中文说明](#中文说明)

---

<a name="中文说明"></a>
## 🇨🇳 中文说明

LSPosed 模块：在小米智能卡（MiPay）双击电源键刷卡页面注入 **「完美校园」** 快捷按钮。无需手动进入系统设置切换默认 NFC 应用，点一下就切到完美校园 HCE，**不跳转页面**，就留在刷卡页看徽标倒计时。按钮右上角的实时徽标显示 **「可以刷卡 30s」倒计时** 或 **「未启用」**，30 秒后自动切回你原本的默认 NFC 应用（窗口内双击电源回到刷卡页也不会被提前还原），离场不会把 NFC 留在错误的应用上；长按按钮仍可直达完美校园 **`VirtualCard_NFC`** 校园卡页面。

### ✨ 功能特性

- **无缝集成**：Hook 小米智能卡 `DoubleClickActivity`，在刷卡页面注入精致的完美校园胶囊按钮。
- **动态 Monet 主题**：自适应系统深色/浅色模式及 Material Design 3 Monet 动态着色，与系统风格完美融合。
- **免手动切 NFC**：点击按钮自动将系统 `Settings.Secure.nfc_payment_default_component` 修改为完美校园 HCE 服务 (`cn.newcapec.hce.service.CapecHostApduService`) 并刷新 NFC 芯片路由。
- **长按直达校园卡页面**：长按按钮时由 `system_server`（system uid 持有 `START_ANY_ACTIVITY`）代拉起完美校园 **`VirtualCard_NFC`** 私有页面。Android 14+ 上普通应用（含小米智能卡）直接启动该页面会被 `not exported from uid` 拒绝，因此改由系统框架代劳；单击不会跳转，HCE 刷卡与前台页面无关。
- **状态徽标 + 倒计时**：按钮右上角叠加一个小号胶囊，实时显示系统当前默认 NFC 应用是否已切到完美校园——绿色「**可以刷卡 23s**」（剩余秒数倒计时）/ 红色「未启用」，切换到底有没有生效、还剩多久还原，一眼可见。
- **30 秒自动还原**：切换成功后 30 秒自动切回你原本的默认 NFC 应用（定时器跑在 system_server，不会被后台冻结或杀进程影响）。窗口期内（徽标显示「可以刷卡」倒计时时）双击电源回到刷卡页**不会**被提前还原；窗口结束后，唤出刷卡页或离开完美校园时会立即还原，不影响门禁卡、公交卡及 Mi Pay 使用。
- **零侵入防闪退**：完全不 Hook 完美校园 App 本身，规避第三方支付 App 的反 Xposed 加固与闪退风控。

### 📱 系统要求

| 项目 | 要求 |
|------|------|
| **框架** | [LSPosed](https://github.com/LSPosed/LSPosed) (API 93+) |
| **系统** | Android 11+ (MIUI 12.5+ / Xiaomi HyperOS 1.0+) |
| **目标应用** | 小米智能卡 (`com.miui.tsmclient`) |
| **系统框架** | 系统框架 (`android`) |
| **依赖应用** | 完美校园 (`com.newcapec.mobile.ncp`) |

### 🚀 安装使用

1. **下载 APK**：从 Releases 下载最新 `wanmei-nfc-lsposed_v0.2.0.apk` 并安装（覆盖安装即可，签名不变）。
2. **启用模块**：在 LSPosed 管理器中启用模块。
3. **勾选作用域**（两个都要，缺一不可）：
   - `系统框架` (`android`)：**必需**。30 秒自动还原定时器和「直达校园卡页面」都在这里工作。
   - `小米智能卡` (`com.miui.tsmclient`)：按钮注入与切卡。
4. **重启生效**：重启手机，或在终端强行停止小米智能卡：
   ```bash
   adb shell am force-stop com.miui.tsmclient
   ```
5. **开始使用**：双击电源键唤出刷卡页，点击「完美校园」按钮即可切卡（不会跳走），徽标变成绿色「**可以刷卡 xx s**」并倒计时，此时直接把手机背面靠近读卡器即可。30 秒倒计时结束自动还原为小米钱包；想直接打开完美校园的校园卡页面，**长按**按钮即可。

### 🩺 故障排查

```bash
# 1. 看模块日志（建议过滤标签）
adb logcat -s WanmeiNfcLsp:*

# 2. 看系统真实的默认 NFC 应用
adb shell settings get secure nfc_payment_default_component
#   完美校园 = com.newcapec.mobile.ncp/cn.newcapec.hce.service.CapecHostApduService
#   小米钱包 = com.android.nfc/com.android.nfc.cardemulation.ESEWalletDummyService

# 3. 手动切一次，确认系统是否会跟随切换钱包角色（Android 14+ / HyperOS）
adb shell "su -c 'settings put secure nfc_payment_default_component com.newcapec.mobile.ncp/cn.newcapec.hce.service.CapecHostApduService'"
adb shell dumpsys nfc | grep wallet_role_holder_change
```

- **按钮没出现**：确认 LSPosed 作用域勾选了 `系统框架(android)` 和 `小米智能卡(com.miui.tsmclient)`，然后重启手机，或执行 `adb shell "su -c 'am force-stop com.miui.tsmclient'"` 后重新双击电源键。
- **徽标一直显示「未启用」**：看日志里的 `setNfcComponent: target=... applied=...`。`applied=false` 表示系统没有接受这个默认应用，请到「设置 → NFC → 默认钱包/支付应用」确认完美校园的 HCE 服务已被启用。
- **30 秒没有自动还原**：该功能跑在 `系统框架(android)` 作用域里，先确认 LSPosed 里勾选了它并重启过手机；日志里应有 `SystemHook ready`、`settings observer registered`、`auto revert scheduled in 30s`、`auto revert fired`。
- **点了按钮但没打开完美校园页面**：这是**预期行为**。单击只切 NFC 不跳转（HCE 刷卡与前台页面无关），要直达校园卡页面请**长按**按钮，日志里会出现 `SystemHook: started VirtualCard_NFC from system_server`。
- **点击后徽标显示「可以刷卡」，但小米钱包页面提示「卡片暂不可用」**：这是小米钱包自己的提示——此刻系统默认钱包确实是完美校园，属预期现象；30 秒后自动还原，或在刷卡页重新双击电源即恢复正常显示。
- **不想用 30 秒自动还原**：在 [SystemHook.kt](app/src/main/java/com/mipay/wanmei/lsp/SystemHook.kt) 里调整 `REVERT_DELAY_MS` 后重新编译。

### 🤖 自动化验证（可选）

```bash
pip install uiautomator2          # 首次运行会自动向设备推送 u2.jar / AdbKeyboard
python tools/verify_nfc_flow.py   # 连接设备后自动跑完整流程
```

脚本会自动：重启小米智能卡 → 打开刷卡页 → 用 uiautomator 层级定位注入按钮（点击失败**自动重试**）→
断言已切到完美校园、倒计时标记已写入 → 等待 30 秒断言自动还原与标记清除，最后打印 PASS/FAIL。
常用参数：`--revert 30`（期望还原秒数）、`--serial <序列号>`、`--tap-retry 4`。

---

<a name="english"></a>
## 🇬🇧 English Documentation

An LSPosed module designed for Xiaomi MIUI and HyperOS devices. It injects a **"Wanmei Xiaoyuan" (Perfect Campus)** quick action button into the MiPay (Xiaomi Smart Card) double-click wallet interface. One tap switches the default NFC payment application on the fly — **without leaving the wallet page** — and the badge shows a live `可以刷卡 30s` countdown until the previous default app is restored. Re-opening the wallet page inside the window keeps the switch alive, and a **long press** jumps straight into Wanmei's `VirtualCard_NFC` campus-card page.

### ✨ Key Features

- **Seamless UI Integration**: Injects a Material Design 3 pill button inside MiPay's `DoubleClickActivity`.
- **Dynamic Monet Theme**: Automatically adapts to dark/light modes and Android Monet accent colors.
- **Instant NFC Switching**: Dynamically changes system `Settings.Secure.nfc_payment_default_component` to Wanmei Xiaoyuan's Host APDU Service (`cn.newcapec.hce.service.CapecHostApduService`) and updates NFC routing on click.
- **Long-press Direct Activity Access**: The private `VirtualCard_NFC` activity is launched by `system_server` (the system uid holds `START_ANY_ACTIVITY`); on Android 14+ a normal app is rejected with `not exported from uid`. A normal tap does not navigate anywhere — HCE does not depend on the foreground activity.
- **Status Badge + Countdown**: A small pill on the button's top-right corner shows the real system state — green `可以刷卡 23s` with the remaining seconds, or red `未启用`.
- **30s Auto-Restoration**: 30 seconds after a successful switch, the previous default NFC app is restored automatically (the timer runs in `system_server`, so it survives background freezing and process kills). While the window is active (badge shows `可以刷卡`), re-opening the wallet page does **not** restore early; after the window it restores when the wallet page opens or when leaving the Wanmei app.
- **Anti-Crash & Safe**: Zero hooks into the Wanmei Xiaoyuan app process itself, avoiding anti-Xposed security detections and crashes.

### 📱 Requirements

| Item | Requirement |
|------|-------------|
| **Framework** | [LSPosed](https://github.com/LSPosed/LSPosed) (API 93+) |
| **OS** | Android 11+ (MIUI 12.5+ / Xiaomi HyperOS 1.0+) |
| **Target App** | Xiaomi Smart Card (`com.miui.tsmclient`) |
| **Framework Scope** | System Framework (`android`) |
| **Target Service** | Wanmei Xiaoyuan (`com.newcapec.mobile.ncp`) |

### 🚀 Quick Start

1. **Install APK**: Download and install `wanmei-nfc-lsposed_v0.2.0.apk` from Releases (in-place upgrade keeps the same signature).
2. **Enable Module**: Enable the module in LSPosed Manager.
3. **Select Scopes** (both are required):
   - `System Framework` (`android`) — **required**: hosts the 30s auto-restoration timer and the direct campus-card page launch.
   - `Xiaomi Smart Card` (`com.miui.tsmclient`) — button injection and NFC switching.
4. **Restart Application**: Reboot device or force stop Xiaomi Smart Card via ADB:
   ```bash
   adb shell am force-stop com.miui.tsmclient
   ```
5. **Usage**: Double-click the power button, tap the "完美校园" button (no page jump), then hold the phone near the reader while the badge counts down `可以刷卡 xx s`. It reverts to Xiaomi Wallet after 30 seconds; **long press** the button to open Wanmei's campus-card page directly.

---

### 🤖 Automated verification (optional)

```bash
pip install uiautomator2
python tools/verify_nfc_flow.py
```

The script restarts Xiaomi Smart Card, opens the wallet page, locates the injected button through the
uiautomator hierarchy (with automatic tap retries), asserts the NFC switch plus the countdown marker,
then waits for the 30s auto-restoration and prints PASS/FAIL.

### 🛠️ Building from Source

> Requires **JDK 17–21** (AGP 8.3.2 / Kotlin 1.9.23). If the build fails with `Java home supplied is invalid`, point `org.gradle.java.home` in [gradle.properties](gradle.properties) at your own JDK, or set `JAVA_HOME`.

```bash
# Clone the repository
git clone https://github.com/AHLLX/wanmei-nfc-lsposed.git
cd wanmei-nfc-lsposed

# Build Release APK
./gradlew assembleRelease

# APK Output Path
app/build/outputs/apk/release/app-release.apk
```

---

### 📄 License & Disclaimer

[MIT License](LICENSE)

> This project is created for educational and personal convenience purposes only. It is not affiliated with Xiaomi Inc. or Newcapec Electronics Co., Ltd.
