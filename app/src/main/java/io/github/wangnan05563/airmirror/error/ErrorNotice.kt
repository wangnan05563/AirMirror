package io.github.wangnan05563.airmirror.error

/**
 * 错误自解释（PRD 7.3）：每条提示必须同时说明「发生了什么」和「下一步怎么做」，
 * 禁止只回错误码或一句「连接失败」。可选动作用 7.3 表里的口径，界面按此渲染按钮。
 *
 * 词汇表遵守 7.7：只用「设备、投屏、镜像、推送、投屏码」，不出现"投射/同屏/共享"。
 */
object ErrorNotice {

    enum class Category {
        /** 网络不可见 */
        NETWORK,
        /** 配对失败 */
        PAIRING,
        /** 权限缺失 */
        PERMISSION,
        /** 能力不匹配 */
        CAPABILITY,
        /** 会话中断 */
        SESSION,
        /** 片源问题 */
        SOURCE,
    }

    /** 可选动作（7.3 最后一列），界面据此决定给不给入口 */
    enum class Action {
        SCAN_QR,
        ENTER_CODE,
        ENTER_IP,
        REGET_CODE,
        RETRY_REQUEST,
        OPEN_SETTINGS,
        USE_FALLBACK,
        DOWNGRADE,
        SWITCH_TO_PUSH,
        SWITCH_TO_MIRROR,
        AUTO_RECOVER,
        RECONNECT,
        CHANGE_SOURCE,
        NONE,
    }

    data class Notice(val category: Category, val headline: String, val nextStep: String, val actions: List<Action>)

    /** 提示语第一行不超过 18 字（PRD 11.5），超出即为不合格文案，单测把这当硬门 */
    const val MAX_HEADLINE_CHARS = 18

    /**
     * 发生原因。把散落各处的中文串收敛成枚举，界面与日志都从同一份定义出文案，
     * 避免出现「同一件事三种说法」。
     */
    enum class Cause {
        UNREACHABLE,
        NO_DISCOVERY,
        CODE_EXPIRED,
        CODE_WRONG,
        CODE_LOCKED,
        CODE_USED,
        REFUSED_BY_USER,
        NO_AUTH,
        SESSION_BUSY,
        PERMISSION_CAPTURE,
        PERMISSION_BACKGROUND,
        PROTOCOL_VERSION,
        DECODE_UNSUPPORTED,
        RESOLUTION_TOO_HIGH,
        HEARTBEAT_LOST,
        PEER_RESTARTED,
        SESSION_ENDED,
        RECONNECT_EXHAUSTED,
        RECOVERY_TIMEOUT,
        SOURCE_FORBIDDEN,
        SOURCE_NOT_FOUND,
        SOURCE_FORMAT,
        DEVICE_UNSUPPORTED,
        /** 推送已经在放，但接收端此刻没有可用的渲染面（退后台/息屏），画面永远不会出现 */
        DISPLAY_UNREADY,
    }

    private val table: Map<Cause, Notice> = mapOf(
        Cause.UNREACHABLE to Notice(
            Category.NETWORK, "连不上对方设备", "确认两台设备连同一个 Wi-Fi，或用投屏码连接。",
            listOf(Action.ENTER_CODE, Action.SCAN_QR, Action.ENTER_IP),
        ),
        Cause.NO_DISCOVERY to Notice(
            Category.NETWORK, "列表里没有设备", "路由器可能屏蔽了设备发现，改用手机投屏码连接。",
            listOf(Action.ENTER_CODE, Action.SCAN_QR, Action.ENTER_IP),
        ),
        Cause.DEVICE_UNSUPPORTED to Notice(
            Category.CAPABILITY, "这台设备不支持", "本机条件不足，改用电视自带投屏或换一台设备。",
            listOf(Action.NONE),
        ),
        Cause.DISPLAY_UNREADY to Notice(
            Category.SESSION, "接收端画面未就绪", "大屏正在放声音但没画面，点亮或回到 AirMirror 界面就会自动接上。",
            listOf(Action.AUTO_RECOVER),
        ),
        Cause.CODE_EXPIRED to Notice(
            Category.PAIRING, "投屏码已过期", "投屏码每 60 秒换一次，请在大屏看新码后重新输入。",
            listOf(Action.REGET_CODE, Action.RETRY_REQUEST),
        ),
        Cause.CODE_WRONG to Notice(
            Category.PAIRING, "投屏码不正确", "对照大屏上的 6 位投屏码再输一次，注意区分 0 和 O。",
            listOf(Action.REGET_CODE, Action.RETRY_REQUEST),
        ),
        Cause.CODE_LOCKED to Notice(
            Category.PAIRING, "投屏码暂时锁定", "连续输错 5 次会锁 60 秒，等一分钟再试或改用扫码。",
            listOf(Action.SCAN_QR, Action.RETRY_REQUEST),
        ),
        Cause.CODE_USED to Notice(
            Category.PAIRING, "投屏码已用过", "一个投屏码只能用一次，请改用大屏上正在显示的新码。",
            listOf(Action.REGET_CODE, Action.SCAN_QR),
        ),
        Cause.REFUSED_BY_USER to Notice(
            Category.PAIRING, "大屏拒绝了投屏", "需要对方在大屏上点允许；请让对方重新确认后再投。",
            listOf(Action.RETRY_REQUEST),
        ),
        Cause.NO_AUTH to Notice(
            Category.PAIRING, "这台设备未授权", "先在投屏端输入大屏上的投屏码，或扫码完成配对。",
            listOf(Action.ENTER_CODE, Action.SCAN_QR),
        ),
        Cause.SESSION_BUSY to Notice(
            Category.SESSION, "已有设备在投屏", "同一时间只允许一路投屏，等对方结束或向大屏申请接管。",
            listOf(Action.RETRY_REQUEST),
        ),
        Cause.PERMISSION_CAPTURE to Notice(
            Category.PERMISSION, "还没给录屏权限", "开始投屏前需要在弹窗里允许录屏，否则镜像无法启动。",
            listOf(Action.OPEN_SETTINGS, Action.USE_FALLBACK),
        ),
        Cause.PERMISSION_BACKGROUND to Notice(
            Category.PERMISSION, "后台运行受限", "系统限制了后台服务，请在设置里允许 AirMirror 后台运行。",
            listOf(Action.OPEN_SETTINGS, Action.USE_FALLBACK),
        ),
        Cause.PROTOCOL_VERSION to Notice(
            Category.CAPABILITY, "两端版本不匹配", "请把两台设备上的 AirMirror 都升级到最新版本。",
            listOf(Action.USE_FALLBACK),
        ),
        Cause.DECODE_UNSUPPORTED to Notice(
            Category.CAPABILITY, "这台设备解不了", "接收端解码能力不足，已降到能放的档位，或改推本地播放。",
            listOf(Action.DOWNGRADE, Action.CHANGE_SOURCE),
        ),
        Cause.RESOLUTION_TOO_HIGH to Notice(
            Category.CAPABILITY, "画面分辨率过高", "接收端放不了这个分辨率，降低一档或改用推送播放。",
            listOf(Action.DOWNGRADE, Action.SWITCH_TO_PUSH),
        ),
        Cause.HEARTBEAT_LOST to Notice(
            Category.SESSION, "设备已断开", "连接中断，正在自动恢复；10 秒内没恢复请重新投屏。",
            listOf(Action.AUTO_RECOVER, Action.RECONNECT),
        ),
        Cause.PEER_RESTARTED to Notice(
            Category.SESSION, "电视已重启", "对方的会话已丢失，请重新连接同一台设备。",
            listOf(Action.RECONNECT, Action.SCAN_QR),
        ),
        Cause.SESSION_ENDED to Notice(
            Category.SESSION, "大屏已结束投屏", "对方主动结束了这次投屏，需要继续请重新发起。",
            listOf(Action.RECONNECT),
        ),
        Cause.RECONNECT_EXHAUSTED to Notice(
            Category.SESSION, "恢复失败已停止", "三次重连都没成功，请检查网络后重新发起投屏。",
            listOf(Action.RECONNECT, Action.ENTER_CODE),
        ),
        Cause.RECOVERY_TIMEOUT to Notice(
            Category.SESSION, "断线超过 30 秒", "已结束这次投屏，请确认两台设备在同一网络后重新连接。",
            listOf(Action.RECONNECT, Action.ENTER_CODE),
        ),
        Cause.SOURCE_FORBIDDEN to Notice(
            Category.SOURCE, "片源不让外放", "这是片源的限制，不是这台设备的问题，可改用镜像播放。",
            listOf(Action.SWITCH_TO_MIRROR, Action.CHANGE_SOURCE),
        ),
        Cause.SOURCE_NOT_FOUND to Notice(
            Category.SOURCE, "片源地址失效", "视频地址已过期或需要登录，请在 App 里重新推送一次。",
            listOf(Action.CHANGE_SOURCE, Action.SWITCH_TO_MIRROR),
        ),
        Cause.SOURCE_FORMAT to Notice(
            Category.SOURCE, "这个格式放不了", "接收端不支持该封装格式，换个片源或改用镜像。",
            listOf(Action.CHANGE_SOURCE, Action.SWITCH_TO_MIRROR),
        ),
    )

    fun noticeFor(cause: Cause): Notice = table.getValue(cause)

    /** 供旧代码把已经散在外面的中文原因串归一化；认不出的回落到最通用的一条 */
    fun causeOfLegacyReason(reason: String): Cause = when {
        reason.contains("已有设备") -> Cause.SESSION_BUSY
        reason.contains("次数过多") -> Cause.CODE_LOCKED
        reason.contains("已用过") -> Cause.CODE_USED
        reason.contains("不正确") -> Cause.CODE_WRONG
        reason.contains("拒绝") -> Cause.REFUSED_BY_USER
        reason.contains("缺少授权") -> Cause.NO_AUTH
        reason.contains("投屏") || reason.contains("连接") -> Cause.UNREACHABLE
        else -> Cause.UNREACHABLE
    }

    /** 界面用的一行文案：先说什么事，再说下一步 */
    fun textFor(cause: Cause): String = noticeFor(cause).let { "${it.headline}，${it.nextStep}" }
}
