package io.github.wangnan05563.airmirror.ui

import android.content.Context
import android.graphics.Canvas
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

    /** 派生透明度色：取值与《UI 风格规范冻结》一致，令牌化不改渲染值（UI-01）。 */
    const val BACKGROUND_ALPHA_40 = 0x660B1F3A.toInt()
    const val BACKGROUND_ALPHA_80 = 0xCC0B1F3A.toInt()
    const val BACKGROUND_ALPHA_90 = 0xE60B1F3A.toInt()
    const val SURFACE_ALPHA_90 = 0xE612314F.toInt()
    /** 分隔线 / 内嵌块：20% 面。 */
    const val DIVIDER = 0x3312314F.toInt()
    /** 二维码底保持纯白：扫码可靠性优先，不着色。 */
    const val QR_BACKGROUND = 0xFFFFFFFF.toInt()

    const val TITLE_SP = 40f
    const val BODY_SP = 24f
    const val PHONE_BODY_SP = 14f
    /** 手机页标题（TV 走 TITLE_SP）；发送端页标题与设备名行各有固定字号。 */
    const val PHONE_TITLE_SP = 32f
    const val SENDER_TITLE_SP = 28f
    const val DEVICE_NAME_SP = 18f

    /** 6 位投屏码要在倒计时环内单行放下，再大就会拆成两截。 */
    const val CODE_SP = 36f

    /** PRD UI-06：换码时旧码淡出与新码淡入同时进行，加起来仍是一次 150ms 的过渡。 */
    const val DURATION_CODE_FADE_MS = 150L
    /** 动效时长唯一来源；C 级由 FocusPolicy 归零，取值不变（UI-01 / 冻结规范）。 */
    const val DURATION_FOCUS_MS = 120L
    const val DURATION_CONNECT_FADE_MS = 200L

    /** 焦点描边圆角。 */
    const val RADIUS_FOCUS_DP = 6

    /** 通用留边刻度；组件专属尺寸（码槽 190dp、二维码 120dp 等）不入刻度。 */
    const val SPACE_XS_DP = 4
    const val SPACE_SM_DP = 8
    const val SPACE_MD_DP = 12
    const val SPACE_LG_DP = 16
    const val SPACE_XL_DP = 24
    const val SPACE_XXL_DP = 32

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
