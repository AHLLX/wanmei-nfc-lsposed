package com.mipay.wanmei.lsp

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import android.graphics.Color
import android.graphics.Typeface

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(64, 96, 64, 96)
            setBackgroundColor(Color.parseColor("#F9FAFB"))
        }

        val titleView = TextView(this).apply {
            text = "完美校园 NFC 集成模组"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111827"))
            gravity = Gravity.CENTER
        }
        layout.addView(titleView)

        val subTitleView = TextView(this).apply {
            text = "MiPay Wanmei Xiaoyuan NFC Integration ${appVersionName()}"
            textSize = 14f
            setTextColor(Color.parseColor("#6B7280"))
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 48)
        }
        layout.addView(subTitleView)

        // 检测完美校园安装状态
        val isInstalled = isAppInstalled(NfcUtils.WANMEI_PKG)
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 36, 48, 36)
            setBackgroundColor(Color.WHITE)
            elevation = 8f
        }

        val statusTitle = TextView(this).apply {
            text = if (isInstalled) "✓ 完美校园已检测到 (Installed)" else "✗ 未检测到完美校园 (Not Installed)"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isInstalled) Color.parseColor("#059669") else Color.parseColor("#DC2626"))
        }
        statusCard.addView(statusTitle)

        val statusDesc = TextView(this).apply {
            val currentNfc = NfcUtils.readCurrentComponent(this@MainActivity) ?: "(未获取到)"
            val nfcReady = NfcUtils.isWanmeiComponent(currentNfc)
            val stateText = if (nfcReady) "完美校园 · 可以刷卡" else "非完美校园 · 未启用"
            text = "包名 (Package): ${NfcUtils.WANMEI_PKG}\n" +
                    "HCE 服务 (Service): ${NfcUtils.getWanmeiHceComponent(this@MainActivity)}\n" +
                    "当前默认 NFC (Current): $currentNfc\n" +
                    "状态 (State): $stateText"
            textSize = 12f
            setTextColor(Color.parseColor("#4B5563"))
            setPadding(0, 12, 0, 0)
        }
        statusCard.addView(statusDesc)

        layout.addView(statusCard)

        // 说明文字
        val guideView = TextView(this).apply {
            text = """
                💡 使用指南 / Quick Start:
                1. 在 LSPosed 管理器中启用本模块 (Enable in LSPosed)
                2. 勾选作用域 (Select Scopes, 两个都要):
                   - 系统框架 / System Framework (android) —— 30s 定时还原
                   - 小米智能卡 / MiPay (com.miui.tsmclient) —— 按钮与切卡
                3. 重启手机或强行停止小米智能卡 (Restart or Force-stop com.miui.tsmclient)
                4. 双击电源键呼出刷卡页，单击「完美校园」即切卡（徽标倒计时 xx s，
                   不跳转页面，直接把手机靠近读卡器）；长按按钮可直达校园卡页面。
            """.trimIndent()
            textSize = 13f
            setTextColor(Color.parseColor("#374151"))
            setPadding(0, 48, 0, 0)
            setLineSpacing(8f, 1f)
        }
        layout.addView(guideView)

        setContentView(layout)
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName ?: ""
            } else {
                packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            }
        } catch (t: Throwable) {
            ""
        }
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
