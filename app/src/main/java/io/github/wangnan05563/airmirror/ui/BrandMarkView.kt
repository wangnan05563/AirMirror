package io.github.wangnan05563.airmirror.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * UI-07 品牌线性标记：用平台 Canvas 画一个 24dp 的线性「A」，不新增位图、不引第三方图标库。
 *
 * - 线宽取 [FocusPolicy.OUTLINE_DP]（2dp），与焦点描边同源；
 * - 尺寸由布局参数固定为 24dp，小于投屏码字高，不抢主视觉；
 * - 纯装饰，调用方用 [A11y.decorative] 从无障碍树移除。
 */
class BrandMarkView(context: Context) : View(context) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.ACCENT
        style = Paint.Style.STROKE
        strokeWidth = Theme.dp(context, FocusPolicy.OUTLINE_DP).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val padX = w * 0.16f
        val padY = h * 0.14f
        // 线性 A：左斜、右斜、横杠，三条线都只描边，保持「线性标记」而不是实心图标。
        canvas.drawLine(padX, h - padY, w / 2f, padY, stroke)
        canvas.drawLine(w / 2f, padY, w - padX, h - padY, stroke)
        canvas.drawLine(w * 0.31f, h * 0.63f, w * 0.69f, h * 0.63f, stroke)
    }
}
