package com.mipay.wanmei.lsp

import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class SystemHook {

    companion object {
        const val SYSTEM_PKG = "android"
        private const val TARGET_ACTIVITY = "com.newcapec.mobile.virtualcard.acivity.VirtualCard_NFC"
    }

    fun hookSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val supervisorClass = XposedHelpers.findClass(
                "com.android.server.wm.ActivityTaskSupervisor",
                lpparam.classLoader
            )

            // Hook ActivityTaskSupervisor 的 checkStartAnyActivityPermission 方法
            XposedBridge.hookAllMethods(
                supervisorClass,
                "checkStartAnyActivityPermission",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            for (arg in param.args) {
                                if (arg is Intent) {
                                    val cmp = arg.component
                                    if (cmp != null && cmp.className == TARGET_ACTIVITY) {
                                        NfcUtils.log("SystemHook: 绕过 VirtualCard_NFC 跨应用启动权限检查")
                                        param.result = true
                                        return
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            NfcUtils.log("SystemHook check error: ${t.message}")
                        }
                    }
                }
            )
            NfcUtils.log("SystemHook 成功挂钩 ActivityTaskSupervisor")
        } catch (e: Throwable) {
            NfcUtils.log("SystemHook 失败: ${e.message}")
        }
    }
}
