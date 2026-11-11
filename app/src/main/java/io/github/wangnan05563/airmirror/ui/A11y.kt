package io.github.wangnan05563.airmirror.ui

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button

/**
 * 无障碍适配层（PRD UI-03 / 设计 §4.2）：界面层只允许经此设置无障碍属性，
 * 不直接写 contentDescription / importantForAccessibility / accessibilityLiveRegion（源码守卫强制）。
 *
 * Q-5 重签后 minSdk 22：API 22 的 setAccessibilityTraversalBefore/After 可用（见 [traversalOrder]）；
 * 仍不引入 heading(API 28) / AndroidX，也不引入 AccessibilityAction 以免适配层接口膨胀。
 */
object A11y {

    /** announce 去重窗：同一 key 在 1 秒内只播一次，避免多路径重复触发。 */
    private const val THROTTLE_MS = 1_000L
    private val recent = HashMap<String, Long>()

    /** 设置可访问名；空串视为接线错误，开发期直接抛出而不是静默贴一个空名字。 */
    fun describe(view: View, text: CharSequence) {
        require(text.isNotBlank()) { "无障碍描述不能为空" }
        view.contentDescription = text
    }

    /** 开关/选择行：向读屏上报 isCheckable/isChecked，角色按按钮播报，选中态由系统补充。 */
    fun switchRow(view: View, checked: Boolean) {
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.isCheckable = true
                info.isChecked = checked
                info.className = Button::class.java.name
            }
        }
    }

    /** 纯装饰图形（二维码环、画面层、长按捕获层）：从无障碍树移除，避免无意义节点干扰遍历。 */
    fun decorative(view: View) {
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 覆盖层打开时隐藏底页整棵子树：D-pad / TalkBack 不再落到看不见的控件。 */
    fun hideSubtree(view: View) {
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** 覆盖层关闭后恢复底页可访问。 */
    fun showSubtree(view: View) {
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    /** 状态行：文本变化时礼貌播报一次。每秒刷新的倒计时/角标禁止调用此方法（设计 R8）。 */
    fun liveStatus(view: View) {
        view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    /** 事件播报：按 key 去重 + 1 秒节流；空文本不播。 */
    fun announce(view: View, text: String, key: String = text) {
        if (text.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        val last = recent[key]
        if (last != null && now - last < THROTTLE_MS) return
        recent[key] = now
        view.announceForAccessibility(text)
    }

    /**
     * 显式指定无障碍遍历先后（API 22）：[first] 先于 [second] 被 TalkBack 线性朗读。
     *
     * 两端必须已有 id（[TvFocus.chain] 会分配）。注意 `uiautomator dump` 输出的是视图树
     * 子节点索引顺序，不受本方法影响；验收判据应看本接线 + 真机 TalkBack，而非 dump 层级顺序
     * （T04908 实测：dump 顺序对该 API 结构性失明）。
     */
    fun traversalOrder(first: View, second: View) {
        require(first.id != View.NO_ID && second.id != View.NO_ID) { "遍历顺序两端必须先分配 id" }
        first.setAccessibilityTraversalBefore(second.id)
        second.setAccessibilityTraversalAfter(first.id)
    }

    /** TalkBack 是否开启：投屏中设置入口据此对读屏用户保持可达（设计 §8.2）。 */
    fun talkBackEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        return am.isTouchExplorationEnabled
    }
}