package com.mipay.wanmei.lsp

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook {

    companion object {
        const val MIPAY_PKG = "com.miui.tsmclient"
        private const val INJECT_TAG = "mipay_wanmei_btn"
    }

    fun hookMiPay(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val targetClass = XposedHelpers.findClass(
                "com.miui.tsmclient.ui.quick.DoubleClickActivity", lpparam.classLoader
            )

            // onCreate: 确保 NFC 恢复为小米钱包
            XposedHelpers.findAndHookMethod(
                targetClass, "onCreate", android.os.Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as Activity
                        NfcUtils.log("DoubleClickActivity.onCreate (before) -> 恢复默认 NFC = 小米钱包")
                        NfcUtils.setNfcComponent(activity, NfcUtils.MIPAY_COMPONENT)
                    }
                }
            )

            // onResume: 确认 NFC = 小米钱包并注入「完美校园」按钮
            XposedHelpers.findAndHookMethod(
                targetClass, "onResume",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as Activity
                        NfcUtils.log("DoubleClickActivity.onResume (before) -> 确认默认 NFC = 小米钱包")
                        NfcUtils.setNfcComponent(activity, NfcUtils.MIPAY_COMPONENT)
                        injectButton(activity)
                    }
                }
            )

        } catch (e: Throwable) {
            NfcUtils.log("MiPay hook 失败: ${e.message}")
        }
    }

    private fun injectButton(activity: Activity) {
        try {
            val decor = activity.window.decorView as? ViewGroup ?: return
            if (decor.findViewWithTag<View>(INJECT_TAG) != null) return

            val btn = WanmeiButtonView(decor.context).apply {
                tag = INJECT_TAG
            }

            val density = decor.context.resources.displayMetrics.density
            val w = (130 * density).toInt()
            val h = (48 * density).toInt()

            btn.post {
                val pw = decor.width
                val ph = decor.height
                if (pw > 0 && ph > 0) {
                    // 放置在右下角 GPay 按钮正上方 (topMargin = ph - h - 170dp)，右侧对其
                    btn.layoutParams = FrameLayout.LayoutParams(w, h).apply {
                        leftMargin = pw - w - (16 * density).toInt()
                        topMargin = ph - h - (170 * density).toInt()
                    }
                }
            }

            decor.post { decor.addView(btn) }
            NfcUtils.log("完美校园按钮成功注入 DoubleClickActivity GPay 按钮上方")
        } catch (e: Throwable) {
            NfcUtils.log("injectButton error: ${e.message}")
        }
    }
}
