package io.github.wangnan05563.airmirror.ui

import io.github.wangnan05563.airmirror.error.ErrorNotice

/**
 * 空态/错误/加载视觉的纯逻辑口径（T06101 / PRD FR-2.0-07 / UI-02）。
 *
 * 只回答四个问题，不碰 Android 绘制：
 * 1. 错误原因该配哪一套三选一插画；
 * 2. 错误卡用哪个稳定错误码，绝不把异常类名/堆栈带到界面；
 * 3. 三个就绪动作谁是主按钮、次按钮、兜底按钮；
 * 4. 首次连接超过 10 秒后，文案是否切到“仍在连接”并露出取消。
 *
 * 这样视觉规则可以在 JVM 单测里钉死，TV/手机共用同一套判定。
 */
object ErrorVisualSpec {

    /** 三套代码绘制插画的唯一枚举；新增第四套必须先改 PRD，不在这里扩。 */
    enum class Scene {
        NO_DEVICE,
        NETWORK,
        OFFLINE,
    }

    /** 动作层级：主按钮实底、次按钮面底、兜底弱按钮。 */
    enum class ActionRole {
        PRIMARY,
        SECONDARY,
        GHOST,
    }

    /** 10 秒是 PRD 明文阈值：首次连接不得一直空白，超过后必须可取消。 */
    const val CANCEL_AFTER_MS = 10_000L

    data class LoadingCopy(
        val line: String,
        val showCancel: Boolean,
    )

    /**
     * 只有三类发现/接收端空态映射到插画；码过期、PIN 错误等仍走各自文案卡，
     * 不硬套一张不相关的图。
     */
    fun sceneFor(cause: ErrorNotice.Cause?): Scene? = when (cause) {
        null, ErrorNotice.Cause.NO_DISCOVERY -> Scene.NO_DEVICE
        ErrorNotice.Cause.DEVICE_OFFLINE -> Scene.OFFLINE
        ErrorNotice.Cause.DISCOVERY_AP_ISOLATED,
        ErrorNotice.Cause.DISCOVERY_DUAL_BAND,
        ErrorNotice.Cause.UNREACHABLE,
        -> Scene.NETWORK
        else -> null
    }

    /**
     * 错误码是给用户报障用的短标识，不是异常类名。新增映射必须保持稳定，
     * 已有码不复用；未映射原因统一落到 AM-ERR-01。
     */
    fun codeFor(cause: ErrorNotice.Cause?): String = when (cause) {
        null, ErrorNotice.Cause.NO_DISCOVERY -> "AM-ND-01"
        ErrorNotice.Cause.DEVICE_OFFLINE -> "AM-OFF-01"
        ErrorNotice.Cause.DISCOVERY_AP_ISOLATED -> "AM-NET-01"
        ErrorNotice.Cause.DISCOVERY_DUAL_BAND -> "AM-NET-02"
        ErrorNotice.Cause.UNREACHABLE -> "AM-NET-03"
        else -> "AM-ERR-01"
    }

    /** 固定动作顺序的第一枚是主行动，第二枚是次行动，其余都是兜底。 */
    fun roleFor(index: Int): ActionRole = when (index) {
        0 -> ActionRole.PRIMARY
        1 -> ActionRole.SECONDARY
        else -> ActionRole.GHOST
    }

    /**
     * 首次连接的加载文案：设备名最多保留 20 字符，避免长名把“仍在连接”挤出屏幕；
     * 10 秒前只显示进度语义，10 秒整开始显示取消入口（不是第 11 秒）。
     */
    fun loading(deviceName: String, elapsedMs: Long): LoadingCopy {
        val name = deviceName.trim().take(20)
        val target = name.ifEmpty { "接收端" }
        return if (elapsedMs < CANCEL_AFTER_MS) {
            LoadingCopy("正在连接$target…", showCancel = false)
        } else {
            LoadingCopy("仍在连接$target…", showCancel = true)
        }
    }
}
