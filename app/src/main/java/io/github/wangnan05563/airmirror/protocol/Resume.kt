package io.github.wangnan05563.airmirror.protocol

/**
 * 断线恢复（PRD FS-10）的共用口径：恢复握手不重走投屏码，凭设备标识在保留窗口内续会。
 * 原因串两端共用，避免一侧改文案另一侧认不出。
 */
object Resume {
    /** 接收端正在保留窗口内等原设备回来，其他设备此刻不能接入。 */
    const val REASON_BUSY = "正在恢复中，请稍候"

    /** 保留窗口已失效（接收端重启或超时）：设备标识还在，但会话没了，必须重新连接。 */
    const val REASON_LOST = "电视已重启，请重新连接"

    /** 恢复成功时 AUTH_RESULT 的 reason，发送端据此确认走的是续会而不是重新授权。 */
    const val RESULT_RESUMED = "resumed"
}