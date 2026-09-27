package com.mipay.wanmei.lsp

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.cardemulation.HostApduService
import android.provider.Settings
import android.util.Log

/**
 * NFC 工具集：
 * - 真实读取 / 写入 Settings.Secure.nfc_payment_default_component
 * - 记忆并还原"用户原本的默认 NFC 应用"
 * - 维护"临时切换到完美校园"标记，供 system_server 侧 30s 自动还原使用
 *
 * 注意：[MainHook] 会在小米智能卡进程内伪装 Settings.Secure.getStringForUser 的返回值，
 * 因此判断系统真实状态必须走 [readSettingDirect]（直接查 settings 数据库）。
 */
object NfcUtils {
    const val TAG = "WanmeiNfcLsp"

    // 小米钱包（ESE 钱包占位服务）：HyperOS 上"默认 NFC 应用 = 小米钱包"对应的值
    const val MIPAY_COMPONENT = "com.android.nfc/com.android.nfc.cardemulation.ESEWalletDummyService"

    // 完美校园 package & 默认 HCE 组件
    const val WANMEI_PKG = "com.newcapec.mobile.ncp"
    const val WANMEI_DEFAULT_COMPONENT = "com.newcapec.mobile.ncp/cn.newcapec.hce.service.CapecHostApduService"

    /** 系统默认 NFC 支付组件（Settings.Secure） */
    const val KEY_NFC_PAYMENT = "nfc_payment_default_component"

    /** 本模组自定义标记：非空表示"我们刚把 NFC 临时切到完美校园，等待自动还原" */
    const val KEY_PENDING_RESTORE = "wanmei_nfc_lsp_pending_restore"

    /** 本模组自定义标记：请 system_server（system uid）拉起完美校园私有页面（长按按钮触发） */
    const val KEY_LAUNCH_REQUEST = "wanmei_nfc_lsp_launch_request"

    /** 切换到完美校园后多久自动切回默认应用（system_server 侧定时器与徽标倒计时共用） */
    const val REVERT_DELAY_MS = 30_000L

    private const val SETTINGS_VALUE_COLUMN = "value"

    /** android.os.UserHandle.USER_CURRENT（-2），putStringForUser 的兼容回退参数 */
    private const val USER_CURRENT = -2

    /** 用户原本的默认 NFC 组件（进程内缓存） */
    @Volatile
    private var cachedDefault: String? = null

    /** 完美校园 HCE 组件（进程内缓存） */
    @Volatile
    private var cachedWanmeiHce: String? = null

    /**
     * 安全日志记录：同时打到 Logcat 和 XposedBridge（反射调用，防御 ClassNotFoundException）
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
     * 直接查 settings 数据库读取真实值。
     * 不使用 Settings.Secure.getString —— 它在小米智能卡进程内被 [MainHook] 伪装过。
     */
    fun readSettingDirect(context: Context?, key: String): String? {
        val ctx = context ?: return null
        return try {
            val uri = Settings.Secure.getUriFor(key)
            ctx.contentResolver.query(uri, arrayOf(SETTINGS_VALUE_COLUMN), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() } else null
            }
        } catch (t: Throwable) {
            log("readSettingDirect($key) failed: ${t.message}")
            null
        }
    }

    /** 当前系统真实的默认 NFC 应用组件 */
    fun readCurrentComponent(context: Context?): String? = readSettingDirect(context, KEY_NFC_PAYMENT)

    /** 该组件是否属于完美校园（按包名比较，兼容服务类名变更） */
    fun isWanmeiComponent(component: String?): Boolean =
        component?.substringBefore('/')?.trim() == WANMEI_PKG

    /** 当前默认 NFC 应用是否就是完美校园 */
    fun isWanmeiDefault(context: Context?): Boolean = isWanmeiComponent(readCurrentComponent(context))

    /** 是否处于"刚临时切到完美校园"的窗口内（窗口内不做还原，由 system_server 30s 定时器收回） */
    fun isPendingRestore(context: Context?): Boolean =
        !readSettingDirect(context, KEY_PENDING_RESTORE).isNullOrEmpty()

    /**
     * 距离自动还原还剩多少秒（标记值 = 切换时的 SystemClock.elapsedRealtime()）。
     * @return 剩余秒数（向上取整）；不在窗口内或已到期返回 -1
     */
    fun pendingRestoreRemainingSec(context: Context?): Int {
        val armed = readSettingDirect(context, KEY_PENDING_RESTORE)?.toLongOrNull() ?: return -1
        val left = REVERT_DELAY_MS - (android.os.SystemClock.elapsedRealtime() - armed)
        if (left <= 0) return -1
        return ((left + 999) / 1000).toInt()
    }

    /** 记住（并返回）用户原本的默认 NFC 组件 */
    fun resolveDefault(context: Context?): String {
        rememberDefault(readCurrentComponent(context))
        return cachedDefault ?: MIPAY_COMPONENT
    }

    /** 缓存用户原本的默认组件；传入值本身是完美校园时忽略 */
    fun rememberDefault(component: String?) {
        if (component.isNullOrBlank() || isWanmeiComponent(component)) return
        if (cachedDefault != component) {
            cachedDefault = component
            log("rememberDefault: user default NFC = $component")
        }
    }

    /** 供伪装 hook 使用：不触发数据库查询 */
    fun cachedDefaultOrMipay(): String = cachedDefault ?: MIPAY_COMPONENT

    /**
     * 写入默认 NFC 组件，写后读回校验。
     * @return 系统当前值是否已等于目标组件
     */
    fun setNfcComponent(context: Context?, component: String): Boolean {
        val ctx = context ?: return false
        val current = readCurrentComponent(ctx)
        if (current == component) {
            log("setNfcComponent: already $component, skip")
            return true
        }
        var putOk = false
        try {
            putOk = Settings.Secure.putString(ctx.contentResolver, KEY_NFC_PAYMENT, component)
        } catch (t: Throwable) {
            log("setNfcComponent put failed: ${t.message}, try putStringForUser")
            putOk = putStringForUser(ctx.contentResolver, component)
        }
        val readBack = readCurrentComponent(ctx)
        val applied = readBack == component
        log("setNfcComponent: target=$component put=$putOk readBack=$readBack applied=$applied")
        if (applied) refreshNfcService(ctx)
        return applied
    }

    /** 兼容旧 ROM：Settings.Secure.putStringForUser(cr, key, value, USER_CURRENT) */
    private fun putStringForUser(cr: ContentResolver, component: String): Boolean = try {
        val cls = Class.forName("android.provider.Settings\$Secure")
        val method = cls.getDeclaredMethod(
            "putStringForUser",
            ContentResolver::class.java,
            String::class.java,
            String::class.java,
            Int::class.java
        )
        method.invoke(null, cr, KEY_NFC_PAYMENT, component, USER_CURRENT) as Boolean
    } catch (t: Throwable) {
        log("putStringForUser failed: ${t.message}")
        false
    }

    /**
     * 置位/清除"临时切换"标记。system_server 侧监听该键来启动 30s 自动还原定时器，
     * 这样即使用户手动把完美校园设为默认钱包，也不会被误还原。
     */
    fun markPendingRestore(context: Context?, pending: Boolean) {
        val ctx = context ?: return
        try {
            // 写入单调递增的值而不是常量 "1"：保证每次点击都会触发 ContentObserver 回调（重新计时）
            val value = if (pending) android.os.SystemClock.elapsedRealtime().toString() else null
            Settings.Secure.putString(ctx.contentResolver, KEY_PENDING_RESTORE, value)
            log("markPendingRestore: $pending ($value)")
        } catch (t: Throwable) {
            log("markPendingRestore failed: ${t.message}")
        }
    }

    /**
     * 请求 system_server 拉起完美校园的 NFC 校园卡页面。
     * MiPay 进程没有 START_ANY_ACTIVITY 权限，Android 14+ 上直接 startActivity 私有页面会被拒绝。
     * @return 标记是否写入成功
     */
    fun requestCardPage(context: Context?): Boolean {
        val ctx = context ?: return false
        return try {
            val value = android.os.SystemClock.elapsedRealtime().toString()
            val ok = Settings.Secure.putString(ctx.contentResolver, KEY_LAUNCH_REQUEST, value)
            log("requestCardPage: $ok")
            ok
        } catch (t: Throwable) {
            log("requestCardPage failed: ${t.message}")
            false
        }
    }

    /** 清空某个 Settings.Secure 键 */
    fun clearSetting(context: Context?, key: String) {
        val ctx = context ?: return
        try {
            Settings.Secure.putString(ctx.contentResolver, key, null)
        } catch (t: Throwable) {
            log("clearSetting($key) failed: ${t.message}")
        }
    }

    /**
     * 动态查找完美校园中的 HostApduService，兼容未来版本升级（结果带缓存）
     */
    fun getWanmeiHceComponent(context: Context): String {
        cachedWanmeiHce?.let { return it }
        try {
            val pm = context.packageManager
            val intent = Intent(HostApduService.SERVICE_INTERFACE).apply {
                setPackage(WANMEI_PKG)
            }
            val services = pm.queryIntentServices(intent, 0)
            val chosen = services.firstOrNull { it.serviceInfo.name.contains("HostApduService") }
                ?: services.firstOrNull()
            if (chosen != null) {
                val component = "${chosen.serviceInfo.packageName}/${chosen.serviceInfo.name}"
                log("Dynamic query Wanmei HCE Service found: $component")
                cachedWanmeiHce = component
                return component
            }
        } catch (e: Throwable) {
            log("getWanmeiHceComponent error: ${e.message}")
        }
        log("Fallback to default Wanmei HCE Component: $WANMEI_DEFAULT_COMPONENT")
        cachedWanmeiHce = WANMEI_DEFAULT_COMPONENT
        return WANMEI_DEFAULT_COMPONENT
    }

    /**
     * 触发系统 NFC 服务刷新卡模拟路由 (best-effort，部分 ROM 无此方法)
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
