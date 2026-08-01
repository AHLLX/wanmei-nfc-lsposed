# MiPay Wanmei NFC LSP (完美校园 MIUI/HyperOS NFC LSPosed 集成模组)

[English](#english) | [中文说明](#中文说明)

---

<a name="中文说明"></a>
## 🇨🇳 中文说明

LSPosed 模块：在小米智能卡（MiPay）双击电源键刷卡页面注入 **「完美校园」** 快捷按钮。无需手动进入系统设置切换默认 NFC 应用，一键动态切卡并秒级直达完美校园 **NFC 虚拟校园卡** 界面，刷卡离场后自动恢复小米钱包默认状态。

### ✨ 功能特性

- **无缝集成**：Hook 小米智能卡 `DoubleClickActivity`，在刷卡页面左下角显示完美校园胶囊按钮。
- **动态 Monet 主题**：自适应系统深色/浅色模式及 Material Design 3 Monet 动态着色，与系统风格融合。
- **免手动切 NFC**：点击按钮自动将系统 `Settings.Secure.nfc_payment_default_component` 修改为完美校园 HCE 服务 (`cn.newcapec.hce.service.CapecHostApduService`) 并刷新 NFC 芯片路由。
- **跨应用直达**：通过系统框架 (`android`) Hook 放行私有页面启动许可，直接进入完美校园 **`VirtualCard_NFC`** 专属刷卡界面。
- **无感还原**：下一次唤出小米刷卡页或使用小米钱包时自动还原默认 NFC，不影响门禁卡、公交卡及 Mi Pay 使用。
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

1. **下载 APK**：从 Releases 下载最新 `mipay_wanmei_lsp.apk` 并安装。
2. **启用模块**：在 LSPosed 管理器中启用模块。
3. **勾选作用域**：
   - `系统框架` (`android`)
   - `小米智能卡` (`com.miui.tsmclient`)
4. **重启生效**：重启手机，或在终端强行停止小米智能卡：
   ```bash
   adb shell am force-stop com.miui.tsmclient
   ```
5. **开始使用**：双击电源键唤出刷卡页，点击左下角「完美校园」即可快速刷卡！

### 📋 架构设计与工作流程

```
┌──────────────────────────────────────────────────────────┐
│              小米智能卡 (DoubleClickActivity)            │
│                                                          │
│     ┌────────────┐                         ┌───────┐     │
│     │ 完美校园卡 │                         │ GPay  │     │  ← 注入胶囊按钮
│     └─────┬──────┘                         └───────┘     │
└───────────┼──────────────────────────────────────────────┘
            │
            ▼
┌──────────────────────────────────────────────────────────┐
│ 1. 切换 Settings.Secure (nfc_payment_default_component)  │
│ 2. SystemHook 绕过 exported=false 权限检查                │
│ 3. 直接拉起 VirtualCard_NFC 专属校园卡界面                │
└──────────────────────────────────────────────────────────┘
```

---

<a name="english"></a>
## 🇬🇧 English Documentation

An LSPosed module designed for Xiaomi MIUI and HyperOS devices. It injects a **"Wanmei Xiaoyuan" (Perfect Campus)** quick action button into the MiPay (Xiaomi Smart Card) double-click wallet interface. It allows users to switch default NFC payment applications on-the-fly and immediately jump to the **NFC Virtual Campus Card** interface without manually modifying system NFC settings.

### ✨ Key Features

- **Seamless UI Integration**: Injects a Material Design 3 pill button on the bottom-left corner of MiPay's `DoubleClickActivity`.
- **Dynamic Monet Theme**: Automatically adapts to dark/light modes and Android Monet accent colors.
- **Instant NFC Switching**: Dynamically changes system `Settings.Secure.nfc_payment_default_component` to Wanmei Xiaoyuan's Host APDU Service (`cn.newcapec.hce.service.CapecHostApduService`) and updates NFC routing on click.
- **Direct Activity Access**: Intercepts `system_server` permission checks to launch Wanmei's private `VirtualCard_NFC` activity directly.
- **Auto-Restoration**: Automatically restores default NFC back to Xiaomi Wallet on the next power-button double-click.
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

1. **Install APK**: Download and install `mipay_wanmei_lsp.apk` from Releases.
2. **Enable Module**: Enable the module in LSPosed Manager.
3. **Select Scopes**:
   - `System Framework` (`android`)
   - `Xiaomi Smart Card` (`com.miui.tsmclient`)
4. **Restart Application**: Reboot device or force stop Xiaomi Smart Card via ADB:
   ```bash
   adb shell am force-stop com.miui.tsmclient
   ```
5. **Usage**: Double-click power button, tap the "完美校园" button at the bottom-left corner, and swipe your campus card!

---

### 🛠️ Building from Source

```bash
# Clone the repository
git clone https://github.com/YourUsername/mipay_wanmei_lsp.git
cd mipay_wanmei_lsp

# Build Release APK
./gradlew assembleRelease

# APK Output Path
app/build/outputs/apk/release/app-release.apk
```

---

### 📄 License & Disclaimer

[MIT License](LICENSE)

> This project is created for educational and personal convenience purposes only. It is not affiliated with Xiaomi Inc. or Newcapec Electronics Co., Ltd.
