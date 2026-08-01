package com.mipay.wanmei.lsp

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.cardemulation.HostApduService
import android.util.Log

object NfcUtils {
    const val TAG = "WanmeiNfcLsp"

    // 小米智能卡 default NFC component
    const val MIPAY_COMPONENT = "com.android.nfc/com.android.nfc.cardemulation.ESEWalletDummyService"
    
    // 完美校园 package & default HCE component
    const val WANMEI_PKG = "com.newcapec.mobile.ncp"
    const val WANMEI_DEFAULT_COMPONENT = "com.newcapec.mobile.ncp/cn.newcapec.hce.service.CapecHostApduService"

    private const val KEY_NFC_PAYMENT = "nfc_payment_default_component"

    /**
     * 安全日志记录：同时打到 Logcat 和 XposedBridge (反射调用，防御 ClassNotFoundException)
     */
    fun log(msg: String) {
        Log.d(TAG, msg)
        try {
            val cls = Class.forName("de.robv.android.xposed.XposedBridge")
            val method = cls.getDeclaredMethod("log", String::class.java)
            method.invoke(null, "$TAG: $msg")
        } catch (ignored: Throwable) {
            // 在非 Xposed 进程中忽略
        }
    }

    /**
     * 修改 Settings.Secure 中的 nfc_payment_default_component
     */
    fun setNfcComponent(context: Context, component: String) {
        try {
            val cr = context.contentResolver
            val cls = Class.forName("android.provider.Settings\$Secure")
            val method = cls.getDeclaredMethod(
                "putStringForUser",
                ContentResolver::class.java,
                String::class.java,
                String::class.java,
                Int::class.java
            )
            val result = method.invoke(null, cr, KEY_NFC_PAYMENT, component, -2) as Boolean
            log("setNfcComponent: $component -> success: $result")

            // 触发系统 NFC 重新路由
            refreshNfcService(context)
        } catch (e: Throwable) {
            log("setNfcComponent failed: ${e.message}")
        }
    }

    /**
     * 动态查找完美校园中的 HostApduService，兼容未来版本升级
     */
    fun getWanmeiHceComponent(context: Context): String {
        try {
            val pm = context.packageManager
            val intent = Intent(HostApduService.SERVICE_INTERFACE).apply {
                setPackage(WANMEI_PKG)
            }
            val services = pm.queryIntentServices(intent, 0)
            if (services.isNotEmpty()) {
                val serviceInfo = services[0].serviceInfo
                val component = "${serviceInfo.packageName}/${serviceInfo.name}"
                log("Dynamic query Wanmei HCE Service found: $component")
                return component
            }
        } catch (e: Throwable) {
            log("getWanmeiHceComponent error: ${e.message}")
        }
        log("Fallback to default Wanmei HCE Component: $WANMEI_DEFAULT_COMPONENT")
        return WANMEI_DEFAULT_COMPONENT
    }

    /**
     * 触发系统 NFC 服务刷新卡模拟路由 (maybeUpdateCardEmulationRoute)
     */
    private fun refreshNfcService(context: Context) {
        try {
            val nfcAdapter = NfcAdapter.getDefaultAdapter(context)
            if (nfcAdapter != null && nfcAdapter.isEnabled) {
                try {
                    val method = nfcAdapter.javaClass.getDeclaredMethod("maybeUpdateCardEmulationRoute")
                    method.isAccessible = true
                    method.invoke(nfcAdapter)
                    log("Triggered maybeUpdateCardEmulationRoute successfully")
                } catch (e: Throwable) {
                    log("maybeUpdateCardEmulationRoute failed/skipped: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            log("refreshNfcService failed: ${e.message}")
        }
    }
}
