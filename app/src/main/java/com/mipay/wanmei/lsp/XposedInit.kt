package com.mipay.wanmei.lsp

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class XposedInit : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            MainHook.MIPAY_PKG -> {
                NfcUtils.log("Loaded package: ${MainHook.MIPAY_PKG}")
                MainHook().hookMiPay(lpparam)
            }
            SystemHook.SYSTEM_PKG -> {
                NfcUtils.log("Loaded package: ${SystemHook.SYSTEM_PKG}")
                SystemHook().hookSystemServer(lpparam)
            }
        }
    }
}
