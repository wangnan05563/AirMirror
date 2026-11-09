package io.github.wangnan05563.airmirror.ui

/**
 * PRD UX-03 六态统一状态条（纯逻辑，不依赖 android.*）。
 *
 * 状态文案只在这里定义：发送端状态栏、接收端恢复条都从这里取，避免同一语义散落成多处长中文串。
 * 错误态不在此另造措辞——具体原因与下一步统一由 ErrorNotice（§7.3）给，状态表只负责枚举与其余五态。
 * 发送端的「未连接」位置被 UX-01 的判决式发现文案占用（更有下一步），IDLE 只在无判决可用时兜底。
 */
object CastStatus {
    /** 与 FS-10 的断线保留窗口同口径：恢复提示常驻到恢复或 30 秒收口。 */
    const val RECOVERY_WINDOW_MS = 30_000L

    enum class State { IDLE, CONNECTING, CASTING, PAUSED, RECOVERING, ERROR }

    /** 发送端控制条固定三件事（PRD UX-03）：不因状态扩容而增加按钮。 */
    enum class Control { PAUSE, STOP, SOUND }

    val CONTROLS: List<Control> = listOf(Control.PAUSE, Control.STOP, Control.SOUND)

    /** 状态 → 一行文案；错误态返回 null，界面改用 ErrorNotice 的逐因文案。 */
    fun line(state: State, target: String = ""): String? = when (state) {
        State.IDLE -> "未连接，选择设备或输入投屏码"
        State.CONNECTING -> "正在连接…"
        State.CASTING -> castingLine(target)
        State.PAUSED -> "已暂停，按遥控器继续"
        State.RECOVERING -> "连接中断，正在恢复"
        State.ERROR -> null
    }

    /** 投屏态文案；无目标时不留悬空介词。 */
    fun castingLine(target: String = ""): String = if (target.isEmpty()) "正在投屏" else "正在投屏到$target"

    /** 恢复提示是否仍在 30 秒保留窗口内（FS-10 / UX-03 常驻判据）。 */
    fun recoveryVisible(elapsedMs: Long): Boolean = elapsedMs in 0 until RECOVERY_WINDOW_MS
}
