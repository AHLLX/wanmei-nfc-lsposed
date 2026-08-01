package com.mipay.wanmei.lsp

import android.app.Activity
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicInteger

class WanmeiLifecycleHook {

    companion object {
        private val activeActivityCount = AtomicInteger(0)
    }

    fun hookWanmei(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            NfcUtils.log("开始 Hook 完美校园 Activity 生命周期...")

            val activityClass = XposedHelpers.findClass("android.app.Activity", lpparam.classLoader)

            // Activity.onStart
            XposedHelpers.findAndHookMethod(activityClass, "onStart", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    if (activity.packageName == NfcUtils.WANMEI_PKG) {
                        val count = activeActivityCount.incrementAndGet()
                        NfcUtils.log("完美校园 Activity onStart: ${activity.javaClass.simpleName}, activeCount=$count")
                    }
                }
            })

            // Activity.onStop
            XposedHelpers.findAndHookMethod(activityClass, "onStop", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    if (activity.packageName == NfcUtils.WANMEI_PKG) {
                        val count = activeActivityCount.decrementAndGet()
                        NfcUtils.log("完美校园 Activity onStop: ${activity.javaClass.simpleName}, activeCount=$count")
                        if (count <= 0) {
                            activeActivityCount.set(0)
                            NfcUtils.log("完美校园已退至后台/关闭 -> 自动还原默认 NFC 为小米钱包")
                            NfcUtils.setNfcComponent(activity, NfcUtils.MIPAY_COMPONENT)
                        }
                    }
                }
            })

            // Activity.onDestroy
            XposedHelpers.findAndHookMethod(activityClass, "onDestroy", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    if (activity.packageName == NfcUtils.WANMEI_PKG) {
                        if (activity.isFinishing && activeActivityCount.get() <= 0) {
                            NfcUtils.log("完美校园 Activity onDestroy (isFinishing) -> 确保还原默认 NFC 为小米钱包")
                            NfcUtils.setNfcComponent(activity, NfcUtils.MIPAY_COMPONENT)
                        }
                    }
                }
            })

        } catch (e: Throwable) {
            NfcUtils.log("WanmeiLifecycleHook 失败: ${e.message}")
        }
    }
}
