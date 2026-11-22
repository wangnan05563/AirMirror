package io.github.wangnan05563.airmirror.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/**
 * 三套错误状态插画（T06101 / PRD FR-2.0-07）。
 *
 * 纯 Canvas 线性绘制，不引位图、不引矢量资源：
 * 1. 无设备：空电视 + 搜索波纹；
 * 2. 网络异常：路由器 + 断开的 Wi-Fi 弧 + 斜杠；
 * 3. 接收端离线：空电视 + 电源符号 + 断开的电源线。
 *
 * 颜色只取 [Theme] 令牌；线宽取 [FocusPolicy.OUTLINE_DP]（2dp），与焦点环同源。
 * 装饰图由 [A11y.decorative] 从无障碍树移除，读屏只读原因与按钮。
 */
class ErrorIllustrationView(context: Context) : View(context) {

    enum class Kind {
        NO_DEVICE,
        NETWORK,
        OFFLINE,
    }

    var kind: Kind = Kind.NO_DEVICE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val minTouch = Theme.dp(context, FocusPolicy.OUTLINE_DP).toFloat()

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.TEXT_SECONDARY
        style = Paint.Style.STROKE
        strokeWidth = minTouch
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val faintLine = Paint(line).apply {
        color = Theme.OFFLINE
        strokeWidth = Theme.dp(context, 1).toFloat()
    }
    private val accent = Paint(line).apply {
        color = Theme.ACCENT
    }
    private val danger = Paint(line).apply {
        color = Theme.DANGER
    }
    private val dash = Paint(line).apply {
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }
    private val accentDash = Paint(accent).apply {
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.SURFACE
        style = Paint.Style.FILL
    }
    private val screenFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.BACKGROUND_ALPHA_80
        style = Paint.Style.FILL
    }

    init {
        A11y.decorative(this)
    }

    private fun dp(value: Float): Float = value * density

    private fun dp(value: Int): Float = Theme.dp(context, value).toFloat()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(Theme.dp(context, 200), widthMeasureSpec)
        val height = resolveSize(Theme.dp(context, 120), heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        drawGround(canvas, w, h)
        when (kind) {
            Kind.NO_DEVICE -> drawNoDevice(canvas, w, h)
            Kind.NETWORK -> drawNetwork(canvas, w, h)
            Kind.OFFLINE -> drawOffline(canvas, w, h)
        }
    }

    private fun drawGround(canvas: Canvas, w: Float, h: Float) {
        val y = h * 0.88f
        canvas.drawLine(w * 0.08f, y, w * 0.92f, y, faintLine)
    }

    /** 通用电视轮廓：机身、屏幕、支架、底座。坐标按 view 宽高比例，换尺寸不换构图。 */
    private fun drawTv(canvas: Canvas, w: Float, h: Float, left: Float, top: Float, right: Float, bottom: Float) {
        val radius = min(w, h) * 0.06f
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, fill)
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, line)

        val insetX = w * 0.035f
        val insetY = h * 0.035f
        val screen = RectF(left + insetX, top + insetY, right - insetX, bottom - insetY)
        canvas.drawRoundRect(screen, radius * 0.55f, radius * 0.55f, screenFill)
        canvas.drawRoundRect(screen, radius * 0.55f, radius * 0.55f, faintLine)

        val midX = (left + right) / 2f
        val standBottom = bottom + h * 0.08f
        canvas.drawLine(midX, bottom, midX, standBottom, line)
        canvas.drawLine(midX - w * 0.09f, standBottom, midX + w * 0.09f, standBottom, line)
    }

    private fun drawNoDevice(canvas: Canvas, w: Float, h: Float) {
        drawTv(canvas, w, h, w * 0.14f, h * 0.22f, w * 0.64f, h * 0.66f)

        // 空屏中央三点：不是“加载中”的省略号，而是“没有可选项”的空白语义。
        val cx = w * 0.39f
        val cy = h * 0.44f
        canvas.drawCircle(cx - dp(6f), cy, dp(2f), faintLine)
        canvas.drawCircle(cx, cy, dp(2f), faintLine)
        canvas.drawCircle(cx + dp(6f), cy, dp(2f), faintLine)

        // 右上角搜索波纹：虚线表示“正在找”，不是“已连上”的实线信号。
        val scanX = w * 0.80f
        val scanY = h * 0.18f
        for (radius in listOf(w * 0.055f, w * 0.095f, w * 0.135f)) {
            val oval = RectF(scanX - radius, scanY - radius, scanX + radius, scanY + radius)
            canvas.drawArc(oval, -70f, 140f, false, accentDash)
        }
        canvas.drawCircle(scanX, scanY, dp(2.5f), accent)
    }

    private fun drawNetwork(canvas: Canvas, w: Float, h: Float) {
        // 路由器机身 + 两根天线。
        val left = w * 0.28f
        val top = h * 0.57f
        val right = w * 0.72f
        val bottom = h * 0.76f
        val radius = min(w, h) * 0.05f
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, fill)
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, line)

        canvas.drawLine(w * 0.36f, top, w * 0.24f, h * 0.30f, line)
        canvas.drawLine(w * 0.64f, top, w * 0.76f, h * 0.30f, line)
        canvas.drawCircle(w * 0.24f, h * 0.30f, dp(2.5f), accent)
        canvas.drawCircle(w * 0.76f, h * 0.30f, dp(2.5f), accent)

        // Wi-Fi 弧：第三圈故意断开，再叠一道红色斜杠，表达“网络异常”而不是“无网络”。
        val cx = w * 0.50f
        val cy = h * 0.52f
        val radii = listOf(w * 0.12f, w * 0.20f, w * 0.28f)
        for ((index, radius) in radii.withIndex()) {
            val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            if (index < 2) {
                canvas.drawArc(oval, 200f, 140f, false, accent)
            } else {
                canvas.drawArc(oval, 202f, 48f, false, accentDash)
                canvas.drawArc(oval, 292f, 48f, false, accentDash)
            }
        }
        canvas.drawLine(w * 0.38f, h * 0.40f, w * 0.62f, h * 0.52f, danger)
        canvas.drawLine(w * 0.38f, h * 0.52f, w * 0.62f, h * 0.40f, danger)

        // 机身指示灯：两枚青绿点，避免整张图只剩“坏”的语义。
        canvas.drawCircle(w * 0.38f, h * 0.675f, dp(2.5f), accent)
        canvas.drawCircle(w * 0.44f, h * 0.675f, dp(2.5f), faintLine)
    }

    private fun drawOffline(canvas: Canvas, w: Float, h: Float) {
        drawTv(canvas, w, h, w * 0.14f, h * 0.22f, w * 0.64f, h * 0.66f)

        // 屏内电源符号：圆环缺口朝上 + 竖线，和 TV 设置里的关机语义一致。
        val cx = w * 0.39f
        val cy = h * 0.44f
        val radius = min(w, h) * 0.055f
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(oval, -55f, 290f, false, accent)
        canvas.drawLine(cx, cy - radius * 0.9f, cx, cy + radius * 0.15f, accent)

        // 断开的电源线：虚线到插头前留一个可见缺口，再用斜杠强调“没接上”。
        val cordStartX = w * 0.62f
        val cordStartY = h * 0.62f
        val cordEndX = w * 0.76f
        val cordEndY = h * 0.70f
        canvas.drawLine(cordStartX, cordStartY, cordEndX, cordEndY, dash)

        val plugLeft = w * 0.80f
        val plugTop = h * 0.66f
        val plugRight = w * 0.90f
        val plugBottom = h * 0.78f
        canvas.drawRoundRect(plugLeft, plugTop, plugRight, plugBottom, radius * 0.5f, radius * 0.5f, fill)
        canvas.drawRoundRect(plugLeft, plugTop, plugRight, plugBottom, radius * 0.5f, radius * 0.5f, line)
        canvas.drawLine(w * 0.90f, h * 0.70f, w * 0.95f, h * 0.70f, line)
        canvas.drawLine(w * 0.90f, h * 0.74f, w * 0.95f, h * 0.74f, line)

        canvas.drawLine(w * 0.755f, h * 0.62f, w * 0.79f, h * 0.70f, danger)
        canvas.drawLine(w * 0.755f, h * 0.70f, w * 0.79f, h * 0.62f, danger)
    }
}
