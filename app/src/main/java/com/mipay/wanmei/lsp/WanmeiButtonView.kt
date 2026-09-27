package com.mipay.wanmei.lsp

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.Toast
import com.caverock.androidsvg.SVG

/**
 * 注入到小米智能卡刷卡页的「完美校园」胶囊按钮。
 *
 * 视图整体高度 = 胶囊(48dp) + 徽标悬出(12dp) = 60dp：
 *  - 下部画 130x48dp 的主胶囊「完美校园」
 *  - 右上角叠加画一个小号状态徽标（与胶囊右上角重叠 6dp）：
 *      当前默认 NFC 应用 = 完美校园  -> 绿色「可以刷卡」
 *      否则                          -> 红色「未启用」
 */
class WanmeiButtonView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var svg: SVG? = null
    private var bmp: Bitmap? = null

    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
    }
    private val svgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    /** 当前系统默认 NFC 应用是否就是完美校园 */
    private var isReady = false

    /** 距离自动还原的剩余秒数；-1 表示不在倒计时窗口内 */
    private var remainingSec = -1

    /** 当前是否正在轮询状态 */
    private var polling = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshState()
            if (polling) postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    companion object {
        /** 主胶囊尺寸 */
        const val PILL_W_DP = 130
        const val PILL_H_DP = 48

        /** 状态徽标尺寸 */
        private const val BADGE_H_DP = 18
        private const val BADGE_PAD_H_DP = 6

        /** 徽标与胶囊重叠的高度（其余部分悬在胶囊上方） */
        private const val BADGE_OVERLAP_DP = 6

        /** 徽标悬出胶囊上方的高度 */
        const val BADGE_OVERHANG_DP = BADGE_H_DP - BADGE_OVERLAP_DP

        /** 注入视图总高度（胶囊 + 徽标悬出部分） */
        const val TOTAL_H_DP = PILL_H_DP + BADGE_OVERHANG_DP

        private const val POLL_INTERVAL_MS = 500L

        private const val TEXT_READY = "可以刷卡"
        private const val TEXT_DISABLED = "未启用"

        // 精美校园卡 Icon SVG
        private const val CARD_SVG = """
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="#FFFFFF">
  <path d="M20 4H4C2.89 4 2 4.89 2 6V18C2 19.11 2.89 20 4 20H20C21.11 20 22 19.11 22 18V6C22 4.89 21.11 4 20 4ZM20 18H4V12H20V18ZM20 8H4V6H20V8ZM6 14H12V16H6V14Z"/>
</svg>
"""
    }

    private val isNightMode: Boolean
        get() = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val pillBgColor: Int by lazy {
        when {
            isNightMode -> Color.parseColor("#1F2937")
            Build.VERSION.SDK_INT >= 31 -> try {
                context.theme.resources.getColor(android.R.color.system_accent1_600, context.theme)
            } catch (e: Throwable) {
                Color.parseColor("#2563EB")
            }
            else -> Color.parseColor("#2563EB")
        }
    }

    /** 「可以刷卡」= 绿色 */
    private val badgeReadyColor: Int
        get() = Color.parseColor(if (isNightMode) "#10B981" else "#059669")

    /** 「未启用」= 红色 */
    private val badgeDisabledColor: Int
        get() = Color.parseColor(if (isNightMode) "#EF4444" else "#DC2626")

    init {
        isClickable = true
        loadSvg()

        // 单击：只切 NFC 并开始 30s 倒计时，不跳转页面（HCE 刷卡与前台页面无关，
        // 留在刷卡页直接看徽标「可以刷卡 30s」倒计时更直观）
        setOnClickListener {
            try {
                NfcUtils.log("完美校园按钮被点击 - 切换 NFC 为完美校园 HCE Service")
                val target = NfcUtils.getWanmeiHceComponent(context)
                val switched = NfcUtils.setNfcComponent(context, target)
                if (switched) {
                    // 通知 system_server 侧：30s 后自动还原用户原本的默认 NFC 应用
                    NfcUtils.markPendingRestore(context, true)
                } else {
                    NfcUtils.log("切换默认 NFC 失败")
                    Toast.makeText(context, "切换默认 NFC 应用失败，请确认完美校园 HCE 服务已启用", Toast.LENGTH_SHORT).show()
                }
                postDelayed({ refreshState() }, 200)
            } catch (e: Throwable) {
                NfcUtils.log("切换完美校园失败: ${e.message}")
                Toast.makeText(context, "切换完美校园失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // 长按：仍然可以直达完美校园的 VirtualCard_NFC 页面（由 system_server 代拉起）
        setOnLongClickListener {
            NfcUtils.log("完美校园按钮长按 - 请求直达校园卡页面")
            if (!NfcUtils.requestCardPage(context)) {
                NfcUtils.log("请求打开校园卡页面失败 -> 降级打开完美校园主界面")
                val launchIntent = context.packageManager.getLaunchIntentForPackage(NfcUtils.WANMEI_PKG)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    context.startActivity(launchIntent)
                } else {
                    Toast.makeText(context, "未找到完美校园应用", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
    }

    private fun loadSvg() {
        try {
            svg = SVG.getFromString(CARD_SVG)
            prepareBmp()
            invalidate()
        } catch (e: Throwable) {
            NfcUtils.log("SVG parse error: ${e.message}")
        }
    }

    private fun prepareBmp() {
        val svgObj = svg ?: return
        val pic = svgObj.renderToPicture()
        bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply {
                save()
                scale(48f / pic.width, 48f / pic.height)
                drawPicture(pic)
                restore()
            }
        }
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val d = resources.displayMetrics.density
        setMeasuredDimension((PILL_W_DP * d).toInt(), (TOTAL_H_DP * d).toInt())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshState()
        if (windowVisibility == VISIBLE) startPolling()
    }

    override fun onDetachedFromWindow() {
        stopPolling()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            refreshState()
            startPolling()
        } else {
            stopPolling()
        }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        removeCallbacks(pollRunnable)
        post(pollRunnable)
    }

    private fun stopPolling() {
        polling = false
        removeCallbacks(pollRunnable)
    }

    /** 读取系统真实状态与倒计时，变化才重绘 */
    private fun refreshState() {
        val ready: Boolean
        val sec: Int
        try {
            ready = NfcUtils.isWanmeiDefault(context)
            sec = if (ready) NfcUtils.pendingRestoreRemainingSec(context) else -1
        } catch (t: Throwable) {
            NfcUtils.log("refreshState error: ${t.message}")
            return
        }
        if (ready != isReady || sec != remainingSec) {
            isReady = ready
            remainingSec = sec
            NfcUtils.log("NFC 状态变化 -> $ready, 剩余 ${sec}s")
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val badgeH = BADGE_H_DP * d
        val pillTop = BADGE_OVERHANG_DP * d
        val pillBottom = h

        // ---- 主胶囊 ----
        rect.set(0f, pillTop, w, pillBottom)
        pillBgPaint.color = pillBgColor
        c.drawRoundRect(rect, (pillBottom - pillTop) / 2, (pillBottom - pillTop) / 2, pillBgPaint)

        val iconSize = 24 * d
        val text = "完美校园"
        pillTextPaint.textSize = 14 * d
        val textWidth = pillTextPaint.measureText(text)
        val spacing = 6 * d
        val totalContentWidth = iconSize + spacing + textWidth
        val startX = (w - totalContentWidth) / 2
        val pillCenterY = (pillTop + pillBottom) / 2

        bmp?.let {
            val iconY = pillCenterY - iconSize / 2
            c.drawBitmap(it, null, RectF(startX, iconY, startX + iconSize, iconY + iconSize), svgPaint)
        }

        val fm = pillTextPaint.fontMetrics
        val textY = pillCenterY - (fm.top + fm.bottom) / 2
        c.drawText(text, startX + iconSize + spacing, textY, pillTextPaint)

        // ---- 右上角状态徽标 ----
        badgeTextPaint.textSize = 10 * d
        val badgeText = when {
            !isReady -> TEXT_DISABLED
            remainingSec > 0 -> "$TEXT_READY ${remainingSec.toString().padStart(2, '0')}s"
            else -> TEXT_READY
        }
        val badgeTextWidth = badgeTextPaint.measureText(badgeText)
        val badgeW = (badgeTextWidth + 2 * BADGE_PAD_H_DP * d).coerceAtMost(w)
        val badgeLeft = w - badgeW
        rect.set(badgeLeft, 0f, w, badgeH)
        badgeBgPaint.color = if (isReady) badgeReadyColor else badgeDisabledColor
        c.drawRoundRect(rect, badgeH / 2, badgeH / 2, badgeBgPaint)

        val badgeFm = badgeTextPaint.fontMetrics
        val badgeTextY = badgeH / 2 - (badgeFm.top + badgeFm.bottom) / 2
        c.drawText(badgeText, badgeLeft + (badgeW - badgeTextWidth) / 2, badgeTextY, badgeTextPaint)
    }
}
