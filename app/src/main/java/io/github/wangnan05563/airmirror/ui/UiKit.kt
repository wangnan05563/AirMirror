package io.github.wangnan05563.airmirror.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.wangnan05563.airmirror.R

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

    /** Android 无障碍触控基线：可点控件高度不得低于 48dp。 */
    const val TOUCH_TARGET_DP = 48

    /**
     * 给直接 clickable 的 TextView 补触控基线。
     * 主入口说明/关于、发送端跨网/更新、接收端设置行都是这种写法，只设内边距不够。
     */
    fun touchTarget(view: TextView, minDp: Int = TOUCH_TARGET_DP) {
        view.minHeight = Theme.dp(view.context, minDp)
        val horizontal = view.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
        view.gravity = horizontal or Gravity.CENTER_VERTICAL
    }

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
                    backgroundTintList = ColorStateList.valueOf(Theme.component.buttonPrimaryBg)
                    setTextColor(Theme.component.buttonPrimaryFg)
                }
                Kind.Secondary -> {
                    backgroundTintList = ColorStateList.valueOf(Theme.component.buttonSecondaryBg)
                    setTextColor(Theme.component.buttonSecondaryFg)
                }
                Kind.Ghost -> {
                    backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    setTextColor(Theme.component.buttonGhostFg)
                }
            }
        }
    }

    /**
     * 统一圆角面：卡片 16dp、内嵌块 12dp。深色底上只加 1dp 低对比描边，
     * 不使用高亮边框；抬升用极轻的 elevation，低版本自动退化为纯色圆角。
     */
    fun surface(
        view: View,
        radiusDp: Int = Theme.RADIUS_CARD_DP,
        color: Int = Theme.component.cardElevated,
        elevationDp: Int = 2,
        stroke: Boolean = true,
    ) {
        val context = view.context
        val radius = Theme.dp(context, radiusDp).toFloat()
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
            if (stroke) setStroke(Theme.dp(context, 1), Theme.component.cardStroke)
        }
        view.elevation = Theme.dp(context, elevationDp).toFloat()
    }

    /** 输入框/内嵌块：比卡片低一层，圆角 12dp，不抬升。 */
    fun inset(view: View, color: Int = Theme.component.insetBackground) {
        val context = view.context
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = Theme.dp(context, Theme.RADIUS_INSET_DP).toFloat()
            setColor(color)
            setStroke(Theme.dp(context, 1), Theme.component.cardStroke)
        }
        view.elevation = 0f
    }

    /** 全站对话框统一走 Material Components，并套 24dp 圆角的品牌主题。 */
    fun dialog(context: Context): MaterialAlertDialogBuilder =
        MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_AirMirror_Dialog)

    /** 弹窗按钮的“稍后 Ghost / 主行动 Primary”形态；非 MaterialButton 的系统按钮保持默认。 */
    fun styleDialogButtons(dialog: AlertDialog) {
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE) as? MaterialButton ?: return
        positive.backgroundTintList = ColorStateList.valueOf(Theme.component.buttonPrimaryBg)
        positive.setTextColor(Theme.component.buttonPrimaryFg)
        positive.cornerRadius = Theme.dp(dialog.context, Theme.RADIUS_BUTTON_DP)
        val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE) as? MaterialButton
        negative?.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        negative?.setTextColor(Theme.component.dialogNegativeFg)
        negative?.cornerRadius = Theme.dp(dialog.context, Theme.RADIUS_BUTTON_DP)
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
