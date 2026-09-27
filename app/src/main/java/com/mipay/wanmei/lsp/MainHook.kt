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
        private const val TARGET_ACTIVITY = "com.miui.tsmclient.ui.quick.DoubleClickActivity"
    }

    fun hookMiPay(lpparam: XC_LoadPackage.LoadPackageParam) {
        val targetClass = try {
            XposedHelpers.findClass(TARGET_ACTIVITY, lpparam.classLoader)
        } catch (t: Throwable) {
            NfcUtils.log("MiPay hook failed: cannot find $TARGET_ACTIVITY: ${t.message}")
            return
        }

        // onCreate: 刷卡页创建时确保 NFC = 用户原本的默认应用
        try {
            XposedHelpers.findAndHookMethod(
                targetClass, "onCreate", android.os.Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as Activity
                        if (NfcUtils.isPendingRestore(activity)) {
                            NfcUtils.log("DoubleClickActivity.onCreate (before) -> Wanmei switch pending, keep NFC")
                        } else {
                            NfcUtils.log("DoubleClickActivity.onCreate (before) -> restore default NFC")
                            NfcUtils.setNfcComponent(activity, NfcUtils.resolveDefault(activity))
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            NfcUtils.log("hook onCreate failed: ${t.message}")
        }

        // onResume: 再次确保 NFC = 用户原本的默认应用，并注入「完美校园」按钮
        try {
            XposedHelpers.findAndHookMethod(
                targetClass, "onResume",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as Activity
                        if (NfcUtils.isPendingRestore(activity)) {
                            // 临时切换窗口内再次双击电源进来：保留完美校园，徽标继续显示「可以刷卡」
                            NfcUtils.log("DoubleClickActivity.onResume (before) -> Wanmei switch pending, keep NFC")
                        } else {
                            NfcUtils.log("DoubleClickActivity.onResume (before) -> restore default NFC + inject button")
                            NfcUtils.setNfcComponent(activity, NfcUtils.resolveDefault(activity))
                        }
                        injectButton(activity)
                    }
                }
            )
        } catch (t: Throwable) {
            NfcUtils.log("hook onResume failed: ${t.message}")
        }

        // 让小米智能卡认为它仍然是默认支付应用（不影响 NfcUtils 直查真实值）
        try {
            val cardEmulationClass = XposedHelpers.findClass(
                "android.nfc.cardemulation.CardEmulation", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                cardEmulationClass, "isDefaultServiceForCategory",
                android.content.ComponentName::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val component = param.args[0] as? android.content.ComponentName
                        val category = param.args[1] as? String
                        if (category == "payment" && component != null) {
                            val pkg = component.packageName
                            if (pkg == MIPAY_PKG || pkg == "com.android.nfc") {
                                param.result = true
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            NfcUtils.log("hook CardEmulation.isDefaultServiceForCategory failed: ${t.message}")
        }

        // 伪装 Settings.Secure.getStringForUser（小米智能卡进程内读取时仍认为默认 NFC 是自己）
        try {
            val secureClass = XposedHelpers.findClass(
                "android.provider.Settings\$Secure", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                secureClass, "getStringForUser",
                android.content.ContentResolver::class.java,
                String::class.java,
                Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = param.args[1] as? String
                        if (name == NfcUtils.KEY_NFC_PAYMENT) {
                            param.result = NfcUtils.cachedDefaultOrMipay()
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            NfcUtils.log("hook Settings.Secure.getStringForUser failed: ${t.message}")
        }

        NfcUtils.log("MiPay hooks installed")
    }

    private fun injectButton(activity: Activity) {
        try {
            val decor = activity.window.decorView as? ViewGroup ?: return
            val existing = decor.findViewWithTag<View>(INJECT_TAG)
            if (existing != null) {
                // 已经注入过：仅校正位置（屏幕尺寸/旋转可能变化）
                placeButton(decor, existing, 0)
                return
            }

            val btn = WanmeiButtonView(decor.context).apply {
                tag = INJECT_TAG
                visibility = View.INVISIBLE
            }
            decor.addView(btn)
            placeButton(decor, btn, 0)
            NfcUtils.log("Wanmei button injected into DoubleClickActivity")
        } catch (e: Throwable) {
            NfcUtils.log("injectButton error: " + e.message)
        }
    }

    /**
     * 定位注入视图：整体高度含徽标悬出部分，topMargin 相应补偿，
     * 保证主胶囊视觉位置与旧版本一致（距底部 170dp、距右边 16dp）。
     */
    private fun placeButton(decor: ViewGroup, btn: View, attempt: Int) {
        try {
            val density = decor.context.resources.displayMetrics.density
            val pw = decor.width
            val ph = decor.height
            if (pw <= 0 || ph <= 0) {
                if (attempt < 10) {
                    decor.postDelayed({ placeButton(decor, btn, attempt + 1) }, 100)
                } else {
                    NfcUtils.log("placeButton: decor never laid out, position skipped")
                }
                return
            }

            val w = (WanmeiButtonView.PILL_W_DP * density).toInt()
            val h = (WanmeiButtonView.TOTAL_H_DP * density).toInt()
            val overhang = (WanmeiButtonView.BADGE_OVERHANG_DP * density).toInt()
            val params = FrameLayout.LayoutParams(w, h).apply {
                leftMargin = pw - w - (16 * density).toInt()
                topMargin = ph - h - (170 * density).toInt() + overhang
            }
            btn.layoutParams = params
            btn.visibility = View.VISIBLE
            NfcUtils.log("placeButton: left=${params.leftMargin} top=${params.topMargin} size=${w}x$h")
        } catch (e: Throwable) {
            NfcUtils.log("placeButton error: " + e.message)
        }
    }
}
