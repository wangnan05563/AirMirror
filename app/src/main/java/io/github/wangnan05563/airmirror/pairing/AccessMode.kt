package io.github.wangnan05563.airmirror.pairing

/**
 * T06113：电视端访问控制的三档模式（FR-2.0-05）。
 *
 * 密码模式按 PRD 8.1 决议不进 v2.0；PIN 的输入与锁定由 T06114 接入，
 * 本轮只负责模式选择与持久化，保证下一任务能直接读同一份落盘值。
 * 默认「每次确认」与既有空凭据会话行为一致：可信设备自动放行，未知设备由大屏确认。
 */
enum class AccessMode(
    val label: String,
    val hint: String,
) {
    CONFIRM("每次确认", "新设备首次连接由电视确认；已信任设备 30 天内免确认"),
    PIN("PIN", "新设备输入电视上的 4 位 PIN；已信任设备仍免确认"),
    TRUSTED_ONLY("仅信任设备", "只允许信任列表内设备；其他连接一律拒绝");

    companion object {
        /** 认不出的落盘值回落到最安全、最接近旧版本的「每次确认」，不静默提升权限。 */
        fun parse(raw: String?): AccessMode =
            entries.firstOrNull { it.name == raw } ?: CONFIRM
    }
}
