package com.mipay.wanmei.lsp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

class WanmeiButtonView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var svg: SVG? = null
    private var bmp: Bitmap? = null
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        isFakeBoldText = true
    }
    private val svgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    companion object {
        // 完美校园 NFC 虚拟校园卡 Activity
        private const val VIRTUAL_CARD_NFC_ACTIVITY = "com.newcapec.mobile.virtualcard.acivity.VirtualCard_NFC"

        // 精美校园卡 Icon SVG
        private const val CARD_SVG = """
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="#FFFFFF">
  <path d="M20 4H4C2.89 4 2 4.89 2 6V18C2 19.11 2.89 20 4 20H20C21.11 20 22 19.11 22 18V6C22 4.89 21.11 4 20 4ZM20 18H4V12H20V18ZM20 8H4V6H20V8ZM6 14H12V16H6V14Z"/>
</svg>
"""
    }

    private val bgColor: Int by lazy {
        val isDark = (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        when {
            isDark -> Color.parseColor("#1F2937")
            Build.VERSION.SDK_INT >= 31 -> try {
                context.theme.resources.getColor(
                    android.R.color.system_accent1_600, context.theme
                )
            } catch (e: Throwable) {
                Color.parseColor("#2563EB")
            }
            else -> Color.parseColor("#2563EB")
        }
    }

    init {
        isClickable = true
        loadSvg()

        setOnClickListener {
            try {
                NfcUtils.log("完美校园按钮被点击 - 切换 NFC 为完美校园 HCE Service")
                val hceComponent = NfcUtils.getWanmeiHceComponent(context)
                NfcUtils.setNfcComponent(context, hceComponent)

                // 调出完美校园真正的 VirtualCard_NFC 页面
                var launched = false
                try {
                    val intent = Intent().apply {
                        component = ComponentName(
                            NfcUtils.WANMEI_PKG,
                            VIRTUAL_CARD_NFC_ACTIVITY
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(intent)
                    launched = true
                    NfcUtils.log("通过 SystemHook 许可成功调出 VirtualCard_NFC 页面")
                } catch (e: Throwable) {
                    NfcUtils.log("startActivity VirtualCard_NFC 失败: ${e.message}")
                }

                // 降级启动
                if (!launched) {
                    val pm = context.packageManager
                    val launchIntent = pm.getLaunchIntentForPackage(NfcUtils.WANMEI_PKG)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        context.startActivity(launchIntent)
                    } else {
                        Toast.makeText(context, "未找到完美校园应用", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Throwable) {
                NfcUtils.log("调出完美校园失败: ${e.message}")
                Toast.makeText(context, "调出完美校园失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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
        setMeasuredDimension((130 * d).toInt(), (48 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        bgPaint.color = bgColor
        c.drawRoundRect(rect, h / 2, h / 2, bgPaint)

        val density = resources.displayMetrics.density
        val iconSize = 24 * density
        val text = "完美校园"

        textPaint.textSize = 14 * density
        val textWidth = textPaint.measureText(text)
        val spacing = 6 * density
        val totalContentWidth = iconSize + spacing + textWidth
        val startX = (w - totalContentWidth) / 2

        bmp?.let {
            val iconY = (h - iconSize) / 2
            c.drawBitmap(it, null, RectF(startX, iconY, startX + iconSize, iconY + iconSize), svgPaint)
        }

        val fontMetrics = textPaint.fontMetrics
        val textY = (h - fontMetrics.top - fontMetrics.bottom) / 2
        c.drawText(text, startX + iconSize + spacing, textY, textPaint)
    }
}
