package com.mipay.wanmei.lsp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * system_server 侧逻辑：
 * 1. 监听 Settings.Secure：
 *    - [NfcUtils.KEY_PENDING_RESTORE]：小米智能卡切换成功后写标记，这里排程 30s 自动还原；
 *    - [NfcUtils.KEY_LAUNCH_REQUEST]：由 system uid 拉起完美校园的私有 VirtualCard_NFC 页面
 *      （MiPay 进程没有 START_ANY_ACTIVITY 权限，Android 14+ 上会 "not exported from uid" 被拒）。
 * 2. 回到小米刷卡页 / 离开完美校园时还原用户原本的默认 NFC 应用。
 * 3. 兼容旧 ROM：放行 VirtualCard_NFC 的跨应用启动权限检查。
 *
 * 定时器放在 system_server（进程常驻、不会被后台冻结），触发信号是模组自己写的标记而不是
 * "默认应用变成了完美校园"，因此用户手动把完美校园设为默认钱包时不会被误还原。
 */
class SystemHook {

    companion object {
        const val SYSTEM_PKG = "android"

        /** 完美校园私有 NFC 校园卡页面 */
        private const val TARGET_ACTIVITY = "com.newcapec.mobile.virtualcard.acivity.VirtualCard_NFC"

        /** 注册设置监听的重试间隔与次数（system_server 刚起来时 ContentResolver 可能还没就绪） */
        private const val OBSERVER_RETRY_MS = 5_000L
        private const val MAX_OBSERVER_ATTEMPTS = 24
    }

    private var lastStartedPackage: String? = null
    private var settingsObserver: ContentObserver? = null
    private var pendingRevert: Runnable? = null

    @Volatile
    private var observerAttempts = 0

    /** "临时切换到完美校园"窗口是否进行中（由设置监听维护，避免 Activity 热路径查库） */
    @Volatile
    private var pendingRestore = false

    @Volatile
    private var worker: HandlerThread? = null

    fun hookSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 先注册设置监听：即使 ActivityTaskSupervisor 在未来 ROM 中改名，30s 自动还原与直达页面依然可用
        registerSettingsObserver()

        val supervisorClass = try {
            XposedHelpers.findClass(
                "com.android.server.wm.ActivityTaskSupervisor",
                lpparam.classLoader
            )
        } catch (t: Throwable) {
            NfcUtils.log("SystemHook: ActivityTaskSupervisor not found: ${t.message}")
            return
        }

        // 回到小米刷卡页 / 离开完美校园时还原默认 NFC
        try {
            val hooked = XposedBridge.hookAllMethods(
                supervisorClass,
                "realStartActivityLocked",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val record = param.args[0]
                            val packageName = XposedHelpers.getObjectField(record, "packageName") as? String
                                ?: return
                            val atmService = XposedHelpers.getObjectField(record, "mAtmService")
                            val context = XposedHelpers.getObjectField(atmService, "mContext") as? Context
                                ?: return

                            // 此刻系统上下文已完全就绪，顺便确保设置监听已注册
                            registerSettingsObserver(context)

                            // 临时切换窗口内不还原：由 30s 定时器统一收回，方便用户双击电源回到刷卡页继续刷
                            if (pendingRestore) {
                                NfcUtils.log("SystemHook: Wanmei switch pending, skip restore for $packageName")
                                lastStartedPackage = packageName
                                return
                            }

                            if (packageName == MainHook.MIPAY_PKG) {
                                val shortComponentName =
                                    XposedHelpers.getObjectField(record, "shortComponentName") as? String
                                if (shortComponentName != null && shortComponentName.contains("DoubleClickActivity")) {
                                    NfcUtils.log("SystemHook: DoubleClickActivity starts -> restore default NFC")
                                    cancelRevert()
                                    NfcUtils.setNfcComponent(context, NfcUtils.resolveDefault(context))
                                }
                            } else if (packageName != NfcUtils.WANMEI_PKG && lastStartedPackage == NfcUtils.WANMEI_PKG) {
                                NfcUtils.log("SystemHook: leaving Wanmei to $packageName -> restore default NFC")
                                cancelRevert()
                                NfcUtils.setNfcComponent(context, NfcUtils.resolveDefault(context))
                            }
                            lastStartedPackage = packageName
                        } catch (t: Throwable) {
                            NfcUtils.log("SystemHook realStartActivityLocked error: ${t.message}")
                        }
                    }
                }
            )
            NfcUtils.log("SystemHook: hooked realStartActivityLocked x${hooked.size}")
        } catch (t: Throwable) {
            NfcUtils.log("SystemHook hook realStartActivityLocked failed: ${t.message}")
        }

        // 兼容 Android 13 及更早版本：权限检查就在这个方法里
        try {
            val hooked = XposedBridge.hookAllMethods(
                supervisorClass,
                "checkStartAnyActivityPermission",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            for (arg in param.args) {
                                if (arg is Intent && arg.component?.className == TARGET_ACTIVITY) {
                                    NfcUtils.log("SystemHook: bypass VirtualCard_NFC permission check (legacy path)")
                                    param.result = true
                                    return
                                }
                            }
                        } catch (t: Throwable) {
                            NfcUtils.log("SystemHook permission hook error: ${t.message}")
                        }
                    }
                }
            )
            NfcUtils.log("SystemHook: hooked checkStartAnyActivityPermission x${hooked.size} (legacy path)")
        } catch (t: Throwable) {
            NfcUtils.log("SystemHook hook checkStartAnyActivityPermission failed: ${t.message}")
        }

        NfcUtils.log("SystemHook ready")
    }

    // ---------------------------------------------------------------- 设置监听

    /**
     * 注册设置监听。system_server 刚加载模块时 ContentResolver 可能还没就绪，失败会定时重试，
     * 也会在 Activity 启动时用已经就绪的上下文再试一次。
     */
    private fun registerSettingsObserver(readyContext: Context? = null) {
        if (settingsObserver != null) return
        val context = readyContext ?: resolveSystemContext()
        if (context == null) {
            NfcUtils.log("SystemHook: no context yet, retry settings observer later")
            scheduleObserverRetry()
            return
        }
        try {
            val observer = object : ContentObserver(handler()) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    onSettingsChanged(context, uri)
                }
            }
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(NfcUtils.KEY_NFC_PAYMENT), false, observer
            )
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(NfcUtils.KEY_PENDING_RESTORE), false, observer
            )
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(NfcUtils.KEY_LAUNCH_REQUEST), false, observer
            )
            settingsObserver = observer
            NfcUtils.log("SystemHook: settings observer registered (attempt ${observerAttempts + 1})")
        } catch (t: Throwable) {
            NfcUtils.log("SystemHook registerSettingsObserver failed: ${t.message}")
            scheduleObserverRetry()
        }
    }

    private fun scheduleObserverRetry() {
        if (observerAttempts >= MAX_OBSERVER_ATTEMPTS) {
            NfcUtils.log("SystemHook: give up registering settings observer after $observerAttempts attempts")
            return
        }
        observerAttempts++
        try {
            handler().postDelayed({ registerSettingsObserver() }, OBSERVER_RETRY_MS)
        } catch (t: Throwable) {
            NfcUtils.log("scheduleObserverRetry failed: ${t.message}")
        }
    }

    private fun onSettingsChanged(context: Context, uri: Uri?) {
        try {
            when (uri?.lastPathSegment) {
                NfcUtils.KEY_PENDING_RESTORE -> {
                    val pending = !NfcUtils.readSettingDirect(context, NfcUtils.KEY_PENDING_RESTORE).isNullOrEmpty()
                    pendingRestore = pending
                    NfcUtils.log("[watch] pending restore flag = $pending")
                    if (pending) scheduleRevert(context) else cancelRevert()
                }
                NfcUtils.KEY_LAUNCH_REQUEST -> {
                    val request = NfcUtils.readSettingDirect(context, NfcUtils.KEY_LAUNCH_REQUEST)
                    if (request.isNullOrEmpty()) return
                    NfcUtils.log("[watch] launch card page request")
                    NfcUtils.clearSetting(context, NfcUtils.KEY_LAUNCH_REQUEST)
                    launchVirtualCard(context)
                }
                else -> {
                    val current = NfcUtils.readCurrentComponent(context)
                    NfcUtils.log("[watch] nfc default -> $current")
                    if (current.isNullOrBlank() || !NfcUtils.isWanmeiComponent(current)) {
                        NfcUtils.rememberDefault(current)
                        cancelRevert()
                    }
                }
            }
        } catch (t: Throwable) {
            NfcUtils.log("onSettingsChanged error: ${t.message}")
        }
    }

    // ---------------------------------------------------------------- 30s 自动还原

    private fun scheduleRevert(context: Context) {
        cancelRevert()
        val runnable = Runnable {
            try {
                val current = NfcUtils.readCurrentComponent(context)
                NfcUtils.log("auto revert fired: current nfc default = $current")
                if (NfcUtils.isWanmeiComponent(current)) {
                    val target = NfcUtils.resolveDefault(context)
                    NfcUtils.log("auto revert: restore default NFC -> $target")
                    NfcUtils.setNfcComponent(context, target)
                } else {
                    NfcUtils.log("auto revert: skipped (no longer Wanmei)")
                }
                pendingRestore = false
                NfcUtils.markPendingRestore(context, false)
            } catch (t: Throwable) {
                NfcUtils.log("auto revert error: ${t.message}")
            }
        }
        pendingRevert = runnable
        handler().postDelayed(runnable, NfcUtils.REVERT_DELAY_MS)
        NfcUtils.log("auto revert scheduled in ${NfcUtils.REVERT_DELAY_MS / 1000}s")
    }

    private fun cancelRevert() {
        val runnable = pendingRevert ?: return
        pendingRevert = null
        try {
            handler().removeCallbacks(runnable)
            NfcUtils.log("auto revert cancelled")
        } catch (t: Throwable) {
            NfcUtils.log("cancelRevert error: ${t.message}")
        }
    }

    // ---------------------------------------------------------------- 直达校园卡页面

    /**
     * 由 system uid 拉起完美校园的私有页面（system_server 持有 START_ANY_ACTIVITY，
     * MiPay 进程没有该权限，直接 startActivity 会被 "not exported from uid" 拒绝）。
     */
    private fun launchVirtualCard(context: Context) {
        try {
            val intent = Intent().apply {
                component = ComponentName(NfcUtils.WANMEI_PKG, TARGET_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            NfcUtils.log("SystemHook: started VirtualCard_NFC from system_server")
            return
        } catch (t: Throwable) {
            NfcUtils.log("SystemHook: start VirtualCard_NFC failed: ${t.message}")
        }
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(NfcUtils.WANMEI_PKG)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(launchIntent)
                NfcUtils.log("SystemHook: fallback started Wanmei main activity")
            } else {
                NfcUtils.log("SystemHook: Wanmei app not found")
            }
        } catch (t: Throwable) {
            NfcUtils.log("SystemHook: fallback start failed: ${t.message}")
        }
    }

    // ---------------------------------------------------------------- 基础设施

    private fun handler(): Handler {
        var thread = worker
        if (thread == null || !thread.isAlive) {
            thread = HandlerThread("WanmeiNfcWorker").also { it.start() }
            worker = thread
        }
        return Handler(thread.looper)
    }

    private fun resolveSystemContext(): Context? {
        try {
            val cls = Class.forName("android.app.AndroidAppHelper")
            val app = cls.getDeclaredMethod("currentApplication").invoke(null) as? Context
            if (app != null) return app
        } catch (t: Throwable) {
            NfcUtils.log("resolveSystemContext AndroidAppHelper failed: ${t.message}")
        }
        try {
            val cls = Class.forName("android.app.ActivityThread")
            val thread = cls.getDeclaredMethod("currentActivityThread").invoke(null)
            return cls.getDeclaredMethod("getSystemContext").invoke(thread) as? Context
        } catch (t: Throwable) {
            NfcUtils.log("resolveSystemContext getSystemContext failed: ${t.message}")
        }
        return null
    }
}
