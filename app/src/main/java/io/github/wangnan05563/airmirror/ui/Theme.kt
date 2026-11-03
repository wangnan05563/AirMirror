package io.github.wangnan05563.airmirror.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** PRD §11.5 的三端统一色板与字号：深蓝底、青绿表示「已连接」，大屏正文不小于 24px。 */
object Theme {
    const val BACKGROUND = 0xFF0B1F3A.toInt()
    const val SURFACE = 0xFF12314F.toInt()
    const val ACCENT = 0xFF17C3B2.toInt()
    const val TEXT_PRIMARY = 0xFFFFFFFF.toInt()
    const val TEXT_SECONDARY = 0xB3FFFFFF.toInt()
    const val OFFLINE = 0x4DFFFFFF.toInt()
    const val DANGER = 0xFFFF6B6B.toInt()

    const val TITLE_SP = 40f
    const val BODY_SP = 24f
    const val PHONE_BODY_SP = 14f

    /** 6 位投屏码要在倒计时环内单行放下，再大就会拆成两截。 */
    const val CODE_SP = 36f

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}

/** 投屏码外圈的环形倒计时：随剩余时间收缩，归零即换码。 */
class CountdownRingView(context: Context) : View(context) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Theme.SURFACE
    }
    private val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        color = Theme.ACCENT
    }
    private val bounds = RectF()

    var fraction: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val inset = track.strokeWidth
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(bounds, 0f, 360f, false, track)
        canvas.drawArc(bounds, -90f, 360f * fraction, false, progress)
    }
}
