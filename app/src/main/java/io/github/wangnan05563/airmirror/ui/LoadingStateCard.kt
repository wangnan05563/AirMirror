package io.github.wangnan05563.airmirror.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/**
 * T06104 首次连接加载卡：设备名 + 进度 + 代码绘制骨架，10 秒后切到“仍在连接”并露出取消。
 *
 * 组件只负责呈现和回调，何时出现、取消后清理什么由宿主页面决定；不直接碰网络与 Service。
 */
class LoadingStateCard @JvmOverloads constructor(
    context: Context,
    private val compact: Boolean = context.resources.configuration.screenHeightDp < 700,
) : LinearLayout(context) {

    private val gap = Theme.dp(context, Theme.SPACE_SM_DP)
    private val isTv = TvFocus.isTvMode(context)

    val progress: ProgressBar
    val title: TextView
    val skeleton: LoadingSkeletonView
    val detail: TextView
    val cancelButton: MaterialButton

    /** 取消由宿主接线：清理 pending 目标、恢复输入态，必要时停止已启动的采集服务。 */
    var onCancel: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        UiKit.surface(this, color = Theme.component.emptyDiagnosisCard, elevationDp = 2)
        setPadding(gap, gap, gap, gap)

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        progress = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Theme.ACCENT)
        }
        val spinner = Theme.dp(context, 20)
        header.addView(progress, LayoutParams(spinner, spinner))
        title = TextView(context).apply {
            textSize = FocusPolicy.bodySp(isTv)
            setTextColor(Theme.TEXT_PRIMARY)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            A11y.liveStatus(this)
        }
        header.addView(
            title,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = gap
            },
        )
        addView(header, matchWidth())

        skeleton = LoadingSkeletonView(context)
        addView(
            skeleton,
            LayoutParams(LayoutParams.MATCH_PARENT, Theme.dp(context, if (compact) 52 else 64)).apply {
                topMargin = Theme.dp(context, Theme.SPACE_MD_DP)
            },
        )

        detail = TextView(context).apply {
            textSize = FocusPolicy.bodySp(isTv)
            setTextColor(Theme.TEXT_SECONDARY)
            setPadding(0, Theme.dp(context, Theme.SPACE_SM_DP), 0, 0)
        }
        addView(detail, matchWidth())

        cancelButton = UiKit.button(context, text = "取消", kind = UiKit.Kind.Ghost).apply {
            minimumHeight = Theme.dp(context, if (isTv) 56 else 48)
            visibility = View.GONE
            setTextColor(Theme.ACCENT)
            A11y.describe(this, A11yCopy.loadingCancel())
            setOnClickListener { onCancel?.invoke() }
        }
        addView(
            cancelButton,
            LayoutParams(LayoutParams.MATCH_PARENT, Theme.dp(context, if (isTv) 56 else 48)).apply {
                topMargin = gap
            },
        )
    }

    /** [elapsedMs] 由宿主按单次连接的起始时刻计算；10 秒整开始露出取消。 */
    fun bind(deviceName: String, elapsedMs: Long) {
        val copy = ErrorVisualSpec.loading(deviceName, elapsedMs)
        title.text = copy.line
        detail.text = if (copy.showCancel) {
            "电视端可能未响应；可取消后检查网络，或改用投屏码连接。"
        } else {
            "首次连接需要几秒，请保持电视端 AirMirror 在前台。"
        }
        cancelButton.visibility = if (copy.showCancel) View.VISIBLE else View.GONE
        cancelButton.isEnabled = copy.showCancel
    }

    private fun matchWidth() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
}
