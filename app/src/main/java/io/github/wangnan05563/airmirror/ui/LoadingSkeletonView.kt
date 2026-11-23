package io.github.wangnan05563.airmirror.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View

/**
 * T06104 加载骨架：三条圆角占位条 + 一条缓慢扫过的浅色高光，全部代码绘制，不读任何位图。
 *
 * 动效只在附着窗口时跑，离开时立即取消；读屏把它当纯装饰（真实状态由 [LoadingStateCard.title] 播报）。
 */
class LoadingSkeletonView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barColor = Theme.component.tagOfflineBg
    private val shimmerColor = Theme.component.tagProtocolBg
    private val radius = Theme.dp(context, Theme.RADIUS_INSET_DP).toFloat()
    private val gap = Theme.dp(context, Theme.SPACE_XS_DP).toFloat()
    private val barHeight = Theme.dp(context, 10).toFloat()
    private val bounds = RectF()

    private var phase = 0f
    private var shimmerRunning = false
    private var shimmerStartedAtMs = 0L

    init {
        A11y.decorative(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE && windowVisibility == VISIBLE) startShimmer()
    }

    override fun onDetachedFromWindow() {
        stopShimmer()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) startShimmer() else stopShimmer()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isAttachedToWindow && this.visibility == VISIBLE) {
            startShimmer()
        } else {
            stopShimmer()
        }
    }

    private fun startShimmer() {
        if (shimmerRunning) return
        shimmerRunning = true
        shimmerStartedAtMs = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    private fun stopShimmer() {
        shimmerRunning = false
        phase = 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        if (shimmerRunning) {
            val elapsedMs = (SystemClock.uptimeMillis() - shimmerStartedAtMs) % SHIMMER_MS
            phase = elapsedMs.toFloat() / SHIMMER_MS
        }
        val count = BARS
        val totalGaps = gap * (count - 1)
        val heightPerBar = ((height - totalGaps) / count).coerceAtMost(barHeight).coerceAtLeast(1f)
        val widths = floatArrayOf(1f, 0.86f, 0.66f)
        var top = 0f
        for (index in 0 until count) {
            val barWidth = width * widths[index]
            bounds.set(0f, top, barWidth, top + heightPerBar)
            paint.color = barColor
            canvas.drawRoundRect(bounds, radius, radius, paint)
            // 高光只在条内扫过，避免在卡片底色上留下一条发光的矩形
            val highlightWidth = barWidth * 0.28f
            val x = -highlightWidth + (barWidth + highlightWidth) * phase
            val save = canvas.save()
            canvas.clipRect(bounds)
            paint.color = shimmerColor
            canvas.drawRoundRect(
                x,
                top,
                x + highlightWidth,
                top + heightPerBar,
                radius,
                radius,
                paint,
            )
            canvas.restoreToCount(save)
            top += heightPerBar + gap
        }
        if (shimmerRunning) postInvalidateOnAnimation()
    }

    private companion object {
        const val BARS = 3
        const val SHIMMER_MS = 1_400L
    }
}
