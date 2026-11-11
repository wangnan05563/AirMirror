package io.github.wangnan05563.airmirror.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import io.github.wangnan05563.airmirror.device.DeviceProbe
import java.util.Collections
import java.util.WeakHashMap

/**
 * 电视端遥控器焦点（PRD §11.5「TV 焦点态必须有 2px 高对比描边 + 轻微放大」）。
 *
 * 不逐处给控件写 `isFocusable`：全站可点控件有一半是 TextView（默认不可聚焦），
 * 且画质档位行是探完能力后动态 addView 进来的，逐处接线必漏。
 * 这里在 setContentView 之后遍历整棵 View 树统一接管，动态新增的行在 refreshSettings 时再扫一遍；
 * [apply] 用 tag 做幂等，重复扫描不会叠加监听器或覆盖原始 background。
 */
object TvFocus {

    /**
     * 幂等记录用弱引用集合，不要用 `View.setTag` 带 int key 的重载：
     * 后者要求 key 是应用资源 id，`View.generateViewId()` 给的不是，运行期直接抛
     * `IllegalArgumentException: The key must be an application-specific resource id.`（本轮模拟器实测踩到）。
     */
    private val applied: MutableSet<View> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<View, Boolean>()),
    )

    fun isTvMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION

    /**
     * [requestInitialFocus] 只在一个页面首次建立时给 true；
     * 设置页刷新时重复扫树不能把焦点从用户当前所在行抢走。
     */
    fun applyTree(root: View, requestInitialFocus: Boolean = true) {
        val animationsEnabled = DeviceProbe.policy(root.context).animationsEnabled
        val first = walk(root, animationsEnabled)
        // post 到下一帧：setContentView 当帧 View 树尚未完成首次布局，直接 requestFocus 可能落空
        if (requestInitialFocus && FocusPolicy.shouldRequestInitialFocus()) {
            root.post { first?.requestFocus() }
        }
    }

    /**
     * 显式串接 D-pad 焦点链（API 11+ nextFocusDown/Up/ForwardId）。
     * 视觉顺序与视图层级顺序不一致时（右上角「显示设置」视觉上先于待连接页内「更多信息」，
     * 但深度优先遍历/几何焦点搜索都先落到后者），nextFocusId 直接覆盖几何搜索，
     * 跨子树也按 [views] 先后移动：Down/Forward 走向下一个，Up 回到上一个。
     * 只修 D-pad 输入焦点；TalkBack 线性朗读顺序由 [A11y.traversalOrder] 用 API 22 遍历先后指定，
     * 不读 nextFocusId。真机朗读复验仍归 T04871（R 级）。
     */
    fun chain(vararg views: View) {
        for (v in views) if (v.id == View.NO_ID) v.id = View.generateViewId()
        for (i in 0 until views.size - 1) {
            val a = views[i]
            val b = views[i + 1]
            a.nextFocusDownId = b.id
            a.nextFocusForwardId = b.id
            b.nextFocusUpId = a.id
        }
    }

    /** 返回遍历到的第一个可聚焦控件（深度优先），供 TV 模式开局定位焦点。 */
    private fun walk(view: View, animationsEnabled: Boolean): View? {
        var first: View? = null
        if (FocusPolicy.isFocusTarget(view.isClickable, view.hasOnClickListeners())) {
            apply(view, animationsEnabled)
            first = view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = walk(view.getChildAt(i), animationsEnabled)
                if (first == null) first = child
            }
        }
        return first
    }

    private fun apply(view: View, animationsEnabled: Boolean) {
        if (!applied.add(view)) return
        view.isFocusable = true
        // 触屏模式不给焦点：手机端点一下不该留下遥控器焦点框
        view.isFocusableInTouchMode = false
        val originalBackground = view.background
        view.setOnFocusChangeListener { target, focused ->
            target.background = if (focused) outline(target.context) else originalBackground
            val scale = FocusPolicy.focusScale(animationsEnabled, focused)
            val duration = FocusPolicy.focusAnimMs(animationsEnabled)
            if (duration == 0L) {
                target.scaleX = scale
                target.scaleY = scale
            } else {
                target.animate().scaleX(scale).scaleY(scale).setDuration(duration).start()
            }
        }
    }

    /** 聚焦态：半透明深蓝底 + 2dp 青绿高对比描边；失焦时原样还原调用方设的背景。 */
    private fun outline(context: Context): GradientDrawable = GradientDrawable().apply {
        setColor(Theme.BACKGROUND_ALPHA_40)
        setStroke(Theme.dp(context, FocusPolicy.OUTLINE_DP), Theme.ACCENT)
        cornerRadius = Theme.dp(context, Theme.RADIUS_FOCUS_DP).toFloat()
    }
}
