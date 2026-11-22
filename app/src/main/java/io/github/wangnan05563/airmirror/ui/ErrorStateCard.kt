package io.github.wangnan05563.airmirror.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.wangnan05563.airmirror.error.ErrorNotice

/**
 * 空态/错误卡的视觉组件（T06101 设计稿的代码落点，供 T06104 接入）。
 *
 * 结构固定为：装饰插画 → 稳定错误码 → 原因一句 → 下一步一句 → 主/次/兜底三动作。
 * 插画只画不读；原因与按钮走真实文案；卡内不出现异常类名或堆栈。
 * 这里不直接监听网络，也不替调用方决定显隐，保持组件可单测。
 */
class ErrorStateCard @JvmOverloads constructor(
    context: Context,
    private val compact: Boolean = context.resources.configuration.screenHeightDp < 700,
) : LinearLayout(context) {

    private val isTv = TvFocus.isTvMode(context)
    private val gap = Theme.dp(context, Theme.SPACE_SM_DP)

    val illustration: ErrorIllustrationView = ErrorIllustrationView(context)
    private val art: FrameLayout = FrameLayout(context)
    val errorCode: TextView
    val headline: TextView
    val nextStep: TextView
    val actionsBar: LinearLayout

    /** 三动作都由宿主页面就地处理；组件本身不跳页、不弹窗。 */
    var onAction: ((ErrorNotice.Action) -> Unit)? = null

    init {
        orientation = VERTICAL
        UiKit.surface(this, color = Theme.component.emptyDiagnosisCard, elevationDp = 2)
        setPadding(gap, gap, gap, gap)

        val artWidth = Theme.dp(context, if (isTv) 240 else 200)
        val artHeight = Theme.dp(context, when {
            !isTv -> 120
            resources.configuration.screenHeightDp <= 400 -> 120
            else -> 144
        })
        art.addView(illustration, FrameLayout.LayoutParams(artWidth, artHeight, Gravity.CENTER_HORIZONTAL))
        addView(art, LayoutParams(LayoutParams.MATCH_PARENT, artHeight))

        errorCode = TextView(context).apply {
            textSize = if (isTv) 18f else 12f
            setTextColor(Theme.TEXT_SECONDARY)
            letterSpacing = 0.06f
            setPadding(0, gap, 0, 0)
        }
        addView(errorCode, matchWidth())

        headline = TextView(context).apply {
            textSize = FocusPolicy.bodySp(isTv)
            setTextColor(Theme.TEXT_PRIMARY)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, Theme.dp(context, Theme.SPACE_XS_DP), 0, 0)
        }
        addView(headline, matchWidth())

        nextStep = TextView(context).apply {
            textSize = FocusPolicy.bodySp(isTv)
            setTextColor(Theme.TEXT_SECONDARY)
            setPadding(0, Theme.dp(context, Theme.SPACE_XS_DP), 0, 0)
        }
        addView(nextStep, matchWidth())

        actionsBar = LinearLayout(context).apply {
            orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(0, gap, 0, 0)
        }
        addView(actionsBar, matchWidth())
    }

    /**
     * 绑定一个错误原因。只有 [ErrorVisualSpec.sceneFor] 支持的三类原因才显示插画；
     * 其他原因退化为文字卡，避免硬套一张不相关的图。
     */
    fun bind(cause: ErrorNotice.Cause, actions: List<ErrorNotice.Action> = EmptyDiagnosis.ACTIONS) {
        val notice = ErrorNotice.noticeFor(cause)
        val scene = ErrorVisualSpec.sceneFor(cause)
        art.visibility = if (scene == null) View.GONE else View.VISIBLE
        illustration.visibility = if (scene == null) View.GONE else View.VISIBLE
        illustration.kind = when (scene) {
            ErrorVisualSpec.Scene.NO_DEVICE -> ErrorIllustrationView.Kind.NO_DEVICE
            ErrorVisualSpec.Scene.NETWORK -> ErrorIllustrationView.Kind.NETWORK
            ErrorVisualSpec.Scene.OFFLINE -> ErrorIllustrationView.Kind.OFFLINE
            null -> ErrorIllustrationView.Kind.NO_DEVICE
        }
        errorCode.text = "错误码 ${ErrorVisualSpec.codeFor(cause)}"
        headline.text = notice.headline
        nextStep.text = notice.nextStep
        renderActions(actions)
    }

    private fun renderActions(actions: List<ErrorNotice.Action>) {
        actionsBar.removeAllViews()
        val buttonHeight = Theme.dp(context, if (isTv) 56 else 48)
        for ((index, action) in actions.withIndex()) {
            val role = ErrorVisualSpec.roleFor(index)
            val button = UiKit.button(
                context,
                text = ErrorNotice.actionLabel(action),
                kind = when (role) {
                    ErrorVisualSpec.ActionRole.PRIMARY -> UiKit.Kind.Primary
                    ErrorVisualSpec.ActionRole.SECONDARY -> UiKit.Kind.Secondary
                    ErrorVisualSpec.ActionRole.GHOST -> UiKit.Kind.Ghost
                },
            ).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (!isTv && compact) {
                    textSize = Theme.PHONE_BUTTON_COMPACT_SP
                    setPadding(
                        Theme.dp(context, Theme.SPACE_XS_DP),
                        0,
                        Theme.dp(context, Theme.SPACE_XS_DP),
                        0,
                    )
                }
                minimumHeight = buttonHeight
                A11y.describe(this, when (action) {
                    ErrorNotice.Action.RETRY_DISCOVERY -> A11yCopy.diagnosisRetry()
                    ErrorNotice.Action.ENTER_CODE -> A11yCopy.diagnosisCode()
                    ErrorNotice.Action.ENTER_IP -> A11yCopy.diagnosisIp()
                    else -> ErrorNotice.actionLabel(action)
                })
                setOnClickListener { onAction?.invoke(action) }
            }
            actionsBar.addView(button, if (compact) {
                LayoutParams(0, buttonHeight, 1f).apply {
                    if (index > 0) leftMargin = gap
                }
            } else {
                LayoutParams(LayoutParams.MATCH_PARENT, buttonHeight).apply {
                    if (index > 0) topMargin = gap
                }
            })
        }
    }

    private fun matchWidth() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
}
