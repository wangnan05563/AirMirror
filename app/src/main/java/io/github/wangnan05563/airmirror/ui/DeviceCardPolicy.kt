package io.github.wangnan05563.airmirror.ui

/**
 * 手机端设备卡片交互的纯逻辑口径（PRD §11.3 交互要点 + 7.3 错误提示规则）。
 *
 * 抽成不依赖 android.* 的一层：展开/收起、动作集合、控制条组成、离线原因与替代路径
 * 都能在 JVM 单测里钉死，UI 只负责把它们渲染出来。
 */
object DeviceCardPolicy {

    enum class CardAction { MIRROR, PUSH_VIDEO }
    enum class ControlAction { PAUSE, STOP, AUDIO }

    /** PRD UI-05 触控基线：卡片头行与展开动作按钮都不小于 48dp。 */
    const val TOUCH_TARGET_DP = 48

    /** 展开区高度固定：换一张卡片展开时动作行等高，列表不上下跳。 */
    const val ACTION_ROW_DP = 48

    /** 协议角标圆角半径：标签由平台 GradientDrawable 绘制，不引位图。 */
    const val TAG_RADIUS_DP = 10

    /** 「最近使用」时间窗：只读现有缓存里的连接时间，不新增网络请求。 */
    const val RECENT_WINDOW_MS = 24L * 60 * 60 * 1000

    enum class TagKind { PROTOCOL, OFFLINE, UNSUPPORTED }

    data class Tag(val kind: TagKind, val text: String)

    data class Card(
        val fingerprint: String,
        val online: Boolean,
        val supported: Boolean,
        val lastConnectedAt: Long?,
    )

    /** 点同一张收起、点另一张展开；同一时刻只展开一张，避免多张动作区堆叠。 */
    fun toggle(expanded: String?, tapped: String): String? = if (expanded == tapped) null else tapped

    /** 离线或能力不支持的卡片不展开动作，点击只给原因与替代路径。 */
    fun actions(card: Card): List<CardAction> =
        if (card.online && card.supported) listOf(CardAction.MIRROR, CardAction.PUSH_VIDEO) else emptyList()

    /** 投屏中控制条固定三件事：暂停、停止、声音；未投屏时整条不出现。 */
    fun controlActions(casting: Boolean): List<ControlAction> =
        if (casting) listOf(ControlAction.PAUSE, ControlAction.STOP, ControlAction.AUDIO) else emptyList()

    fun pauseLabel(paused: Boolean): String = if (paused) "继续投屏" else "暂停投屏"

    /** 离线卡片按 §11.3 显示「上次连接」，并把最可能的原因一并说出来。 */
    fun offlineLabel(lastConnectedAt: Long?, nowMs: Long): String {
        if (lastConnectedAt == null || lastConnectedAt <= 0L) return "离线 · 还没连过"
        val minutes = ((nowMs - lastConnectedAt) / 60_000L).coerceAtLeast(0L)
        return if (minutes < 1L) "刚刚连过 · 可能换网或关机" else "上次连接 ${minutes} 分钟前 · 可能换网或关机"
    }

    /** 7.3：提示必须含「发生了什么」+「下一步怎么做」，不能只写「离线」。 */
    fun offlineHint(): String =
        "设备已离线：可能换网、电视关机或 AP 隔离。可用投屏码或手动输 IP 连接"

    /**
     * 发送端本片未内置 DLNA 控制点与媒体选择器，不伪装修好「推送视频」。
     * 按 7.3 给可执行替代路径，并作为派生单跟踪真实实现。
     */
    fun pushVideoFallback(): String =
        "发送端暂未内置视频选择：请在视频 App 里用系统投屏按钮推送，或改用「镜像屏幕」"

    /** 卡片右侧只有一个标签位，三态互斥：离线 > 不支持 > 协议角标。 */
    fun tag(card: Card, protocolBadge: String): Tag = when {
        !card.online -> Tag(TagKind.OFFLINE, "离线")
        card.supported -> Tag(TagKind.PROTOCOL, protocolBadge)
        else -> Tag(TagKind.UNSUPPORTED, "不支持")
    }

    /** 24h 内连过算「最近使用」；时钟回拨造成的时间差按 0 处理，不当成很久以前。 */
    fun isRecent(lastConnectedAt: Long?, nowMs: Long): Boolean {
        if (lastConnectedAt == null || lastConnectedAt <= 0L) return false
        return (nowMs - lastConnectedAt).coerceAtLeast(0L) <= RECENT_WINDOW_MS
    }

    /** 卡片副行：离线说清上次连接与原因，在线近期连过给「最近使用」，都不满足时不占行。 */
    fun subtitle(card: Card, nowMs: Long): String? = when {
        !card.online -> offlineLabel(card.lastConnectedAt, nowMs)
        isRecent(card.lastConnectedAt, nowMs) -> "最近使用"
        else -> null
    }

    /** UI-05：离线不能只靠灰度，副行要同时挂一个形状标记。 */
    fun needsOfflineMarker(card: Card): Boolean = !card.online
}
