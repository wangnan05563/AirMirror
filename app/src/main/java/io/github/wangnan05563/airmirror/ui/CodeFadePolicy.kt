package io.github.wangnan05563.airmirror.ui

/**
 * PRD UI-06 投屏码轮换过渡的纯逻辑：只有「允许动效 + 非首次 + 值真的变了 + 页面可见 + 前台」
 * 才做交叉淡化，其余一律直接置终态。
 *
 * 每秒刷新会反复调用这里，但值没变时 [shouldCommit] 就返回 false——
 * 这是把「秒数刷新」与「码值变化」分开的唯一判据，不能退化成每次赋值都动画。
 */
object CodeFadePolicy {

    data class Input(
        val animationsEnabled: Boolean,
        val firstRender: Boolean,
        val previous: String?,
        val next: String,
        val pageVisible: Boolean,
        val resumed: Boolean,
    )

    /** 值变化才需要改视图；首次与同值都不算。 */
    fun shouldCommit(i: Input): Boolean = i.previous != i.next

    /** 只有可见、在前台、允许动效且不是首次显示时，才值得跑 150ms 过渡。 */
    fun shouldAnimate(i: Input): Boolean =
        i.animationsEnabled && !i.firstRender && i.previous != null &&
            i.previous != i.next && i.pageVisible && i.resumed
}
