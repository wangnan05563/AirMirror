package io.github.wangnan05563.airmirror.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * PRD §11.5 / REQ-008：旧常量名保留为兼容别名，真实取值在 [ThemeTokens]（Raw → Semantic → Component）。
 *
 * 页面与组件可以继续用旧名（它们已经指向语义令牌），新代码优先用 [Theme.component]。
 * Theme.kt 内不再出现任何十六进制色值；改色只能改 [ThemeTokens]，并由 ThemeTokenTest 锁值。
 */
object Theme {

    private val t get() = ThemeTokens.midnight
    private val s get() = t.semantic
    private val c get() = t.component

    // 语义色（旧名兼容）
    val BACKGROUND: Int get() = s.background
    val SURFACE: Int get() = s.surface
    val ACCENT: Int get() = s.accent
    val TEXT_PRIMARY: Int get() = s.textPrimary
    val TEXT_SECONDARY: Int get() = s.textSecondary
    val OFFLINE: Int get() = s.offline
    val DANGER: Int get() = s.danger

    /** 派生透明度色：语义别名，旧名保留给既有调用点。 */
    val BACKGROUND_ALPHA_40: Int get() = s.focusFill
    val BACKGROUND_ALPHA_80: Int get() = s.scrim
    val BACKGROUND_ALPHA_90: Int get() = s.scrimStrong
    val SURFACE_ALPHA_90: Int get() = s.panel
    val DIVIDER: Int get() = s.divider
    val SURFACE_ELEVATED: Int get() = s.surfaceElevated
    val SURFACE_STROKE: Int get() = s.surfaceStroke
    val QR_BACKGROUND: Int get() = s.qrLight

    /** 新代码可读的语义名，避免继续用「ALPHA_80」这种外观命名。 */
    val SCRIM: Int get() = s.scrim
    val SCRIM_STRONG: Int get() = s.scrimStrong
    val PANEL: Int get() = s.panel
    val FOCUS_FILL: Int get() = s.focusFill

    /** 组件层入口：按钮、卡片、标签、投屏码槽等按组件取色。 */
    val component: ComponentTokens get() = c

    // 字阶（旧名兼容）
    val TITLE_SP: Float get() = t.typography.display
    val BODY_SP: Float get() = t.typography.body
    val PHONE_BODY_SP: Float get() = t.typography.caption
    val SECTION_TITLE_SP: Float get() = t.typography.sectionTitle
    val PHONE_TITLE_SP: Float get() = t.typography.phoneTitle
    val SENDER_TITLE_SP: Float get() = t.typography.senderTitle
    val DEVICE_NAME_SP: Float get() = t.typography.deviceName
    val PHONE_BUTTON_SP: Float get() = t.typography.phoneButton
    val PHONE_BUTTON_DENSE_SP: Float get() = t.typography.phoneButtonDense
    val PHONE_BUTTON_COMPACT_SP: Float get() = t.typography.phoneButtonCompact
    val BUTTON_SP: Float get() = t.typography.tvButton
    val CODE_SP: Float get() = t.typography.code

    // 圆角与留边刻度
    val RADIUS_BUTTON_DP: Int get() = t.radii.button
    val RADIUS_CARD_DP: Int get() = t.radii.card
    val RADIUS_INSET_DP: Int get() = t.radii.inset
    val RADIUS_DIALOG_DP: Int get() = t.radii.dialog
    val RADIUS_FOCUS_DP: Int get() = t.radii.focus

    val SPACE_XS_DP: Int get() = t.spacing.xs
    val SPACE_SM_DP: Int get() = t.spacing.sm
    val SPACE_MD_DP: Int get() = t.spacing.md
    val SPACE_LG_DP: Int get() = t.spacing.lg
    val SPACE_XL_DP: Int get() = t.spacing.xl
    val SPACE_XXL_DP: Int get() = t.spacing.xxl

    // 动效时长唯一来源
    val DURATION_CODE_FADE_MS: Long get() = t.motion.codeFade
    val DURATION_FOCUS_MS: Long get() = t.motion.focus
    val DURATION_CONNECT_FADE_MS: Long get() = t.motion.connect

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
