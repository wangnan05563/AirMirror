package io.github.wangnan05563.airmirror.ui

/**
 * PRD UX-02 投屏码轮换的输入容错（纯逻辑，不依赖 android.*）。
 *
 * 发送端没有配对前的实时通道，「换码」只在用旧码连接被拒这一刻可观测：
 * 接收端 CastCode 每 60 秒轮换，用户输到一半或输完旧码再连，就会拿到码类拒绝。
 * 此时不清空输入、不弹模态框，只按输入态给一句可重新确认的提示；锁定态沿用既有文案。
 * 轮换与锁定规则本身仍归 CastCode，这里只决定「提示什么、要不要保留输入」。
 */
object CodeInputPolicy {

    /** 与 CastCode.CODE_LENGTH 同口径；输入框 LengthFilter 也用它。 */
    const val CODE_LENGTH = 6

    enum class InputState { EMPTY, PARTIAL, FULL }

    fun inputState(length: Int): InputState = when {
        length <= 0 -> InputState.EMPTY
        length < CODE_LENGTH -> InputState.PARTIAL
        else -> InputState.FULL
    }

    /** 换码提示：输入框已有内容才需要「请重新确认」，空输入无可确认。 */
    fun rotationNotice(): String = "投屏码已更新，请重新确认"

    /** 锁定态沿用既有文案（A11yCopy 是锁定期文案的唯一出口），不换成换码提示。 */
    fun lockedNotice(): String = A11yCopy.codeLocked()

    data class Decision(val preserveInput: Boolean, val notice: String?)

    /**
     * 四分支：空 / 部分 / 满 6 位 / 锁定。
     * 任何分支都保留输入（preserveInput 恒真）——清空输入正是 P-05 的痛点。
     * 锁定优先于输入态：锁定期连输码机会都没有，提示「已更新请重新确认」会让用户白跑一轮。
     */
    fun onCodeRotated(inputLength: Int, locked: Boolean): Decision {
        if (locked) return Decision(preserveInput = true, notice = lockedNotice())
        return when (inputState(inputLength)) {
            InputState.EMPTY -> Decision(preserveInput = true, notice = null)
            InputState.PARTIAL, InputState.FULL -> Decision(preserveInput = true, notice = rotationNotice())
        }
    }
}