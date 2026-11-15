package io.github.wangnan05563.airmirror.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton

/**
 * 全站控件工厂（2026-11-15 用户口径）。
 *
 * 1. 所有按钮统一走开源库 **Material Components**（Apache-2.0）的 `MaterialButton`：
 *    系统默认 Button 在各家 ROM 上内边距/圆角/最小宽度都不一样，同一页面并排就会高低不齐。
 * 2. 字号按「角色」取：标题 [FocusPolicy.titleSp]、正文 [FocusPolicy.bodySp]，
 *    按钮文字手机 16sp / TV 24sp —— 各页面不再各写一套数字。
 */
object UiKit {

    /** 按钮语义：主行动（强调色实底）、次行动（面底）、弱行动（纯文字）。 */
    enum class Kind { Primary, Secondary, Ghost }

    fun button(
        context: Context,
        text: String = "",
        kind: Kind = Kind.Secondary,
    ): MaterialButton {
        val isTv = TvFocus.isTvMode(context)
        val px = { dp: Int -> Theme.dp(context, dp) }
        return MaterialButton(context).apply {
            this.text = text
            isAllCaps = false
            gravity = Gravity.CENTER
            textSize = if (isTv) Theme.BUTTON_SP else Theme.PHONE_BUTTON_SP
            cornerRadius = px(Theme.RADIUS_BUTTON_DP)
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minimumWidth = 0
            minHeight = px(if (isTv) 56 else 48)
            minimumHeight = px(if (isTv) 56 else 48)
            setPadding(px(Theme.SPACE_LG_DP), 0, px(Theme.SPACE_LG_DP), 0)
            // 触屏/遥控器都由 TvFocus 统一接管焦点态，这里不额外开涟漪以外的动画
            stateListAnimator = null
            when (kind) {
                Kind.Primary -> {
                    backgroundTintList = ColorStateList.valueOf(Theme.ACCENT)
                    setTextColor(Theme.BACKGROUND)
                }
                Kind.Secondary -> {
                    backgroundTintList = ColorStateList.valueOf(Theme.SURFACE)
                    setTextColor(Theme.TEXT_PRIMARY)
                }
                Kind.Ghost -> {
                    backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    setTextColor(Theme.ACCENT)
                }
            }
        }
    }

    /** 按钮通用布局参数：整行铺满 + 指定上下间距，避免像改造前那样两个按钮贴在一起。 */
    fun fullWidth(
        context: Context,
        gapTopDp: Int,
        gapBottomDp: Int = 0,
        heightDp: Int? = null,
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            heightDp?.let { Theme.dp(context, it) } ?: LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = Theme.dp(context, gapTopDp)
            bottomMargin = Theme.dp(context, gapBottomDp)
        }
}
