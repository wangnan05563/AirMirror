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
        ENTER_CODE,
        ENTER_IP,
        REGET_CODE,
        RETRY_REQUEST,
        /** 空列表诊断：重新开始一轮发现搜索（UX-01），语义不同于配对重试 RETRY_REQUEST */
        RETRY_DISCOVERY,
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
        /** 搜索结束、已知设备全部离线（UX-01 空列表自救，优先于网络判决） */
        DEVICE_OFFLINE,
        /** 搜索结束且无任何命中：网络禁止设备互访（AP 隔离） */
        DISCOVERY_AP_ISOLATED,
        /** 搜索结束且无任何命中：手机与电视不在同一频段（双频分离） */
        DISCOVERY_DUAL_BAND,
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
        /** 系统把「采集本机声音」算在录音权限里，没给权限就只能投画面（PRD FS-01 音频口径） */
        PERMISSION_AUDIO,
        /** 系统版本或厂商限制，压根不提供声音采集通道 */
        AUDIO_UNAVAILABLE,
        /** 接收端找不到音频输出设备（PRD FR-09 异常「未检测到扬声器」） */
        NO_SPEAKER,
        /** 音频轨被来电或其他应用占用超过 1 秒，画面不受影响 */
        AUDIO_INTERRUPTED,
        /** 声明了解 H.265 硬解却建不起解码器（PRD 8.7 异常表「不支持 H.265 硬解」） */
        CODEC_FALLBACK,
        /** 用户锁了档位又连续超标：只提示，画质一格不动（PRD FS-05「手动锁定」） */
        TIER_UNSTABLE,
        /** 实测带宽低于当前档位下限：不逐级试探，一步跳到可维持档位（PRD FS-05「带宽骤降」） */
        BANDWIDTH_JUMP,
    }

    private val table: Map<Cause, Notice> = mapOf(
        Cause.UNREACHABLE to Notice(
            Category.NETWORK, "连不上对方设备", "确认两台设备连同一个 Wi-Fi，或用投屏码连接。",
            listOf(Action.ENTER_CODE, Action.ENTER_IP),
        ),
        Cause.NO_DISCOVERY to Notice(
            Category.NETWORK, "列表里没有设备", "路由器可能屏蔽了设备发现，改用手机投屏码连接。",
            listOf(Action.ENTER_CODE, Action.ENTER_IP),
        ),
        Cause.DEVICE_OFFLINE to Notice(
            Category.NETWORK, "设备已离线", "可能换网或关机；重试发现或用投屏码连接。",
            listOf(Action.RETRY_DISCOVERY, Action.ENTER_CODE, Action.ENTER_IP),
        ),
        Cause.DISCOVERY_AP_ISOLATED to Notice(
            Category.NETWORK, "网络禁止设备互访", "关闭路由器的 AP 隔离，或用投屏码连接。",
            listOf(Action.RETRY_DISCOVERY, Action.ENTER_CODE, Action.ENTER_IP),
        ),
        Cause.DISCOVERY_DUAL_BAND to Notice(
            Category.NETWORK, "手机和电视不在同一频段", "把两台设备连到同一个 Wi-Fi，或用投屏码连接。",
            listOf(Action.RETRY_DISCOVERY, Action.ENTER_CODE, Action.ENTER_IP),
        ),
        Cause.DEVICE_UNSUPPORTED to Notice(
            Category.CAPABILITY, "这台设备不支持", "本机条件不足，改用电视自带投屏或换一台设备。",
            listOf(Action.NONE),
        ),
        Cause.DISPLAY_UNREADY to Notice(
            Category.SESSION, "接收端画面未就绪", "大屏正在放声音但没画面，点亮或回到 AirMirror 界面就会自动接上。",
            listOf(Action.AUTO_RECOVER),
        ),
        Cause.PERMISSION_AUDIO to Notice(
            Category.PERMISSION, "没给声音采集权限", "镜像带声音要用系统的录音权限；不给也能投画面，声音留在手机上。",
            listOf(Action.OPEN_SETTINGS, Action.USE_FALLBACK),
        ),
        Cause.AUDIO_UNAVAILABLE to Notice(
            Category.CAPABILITY, "这台手机不让采声音", "当前系统限制，声音从手机播放，画面照常投，会话不中断。",
            listOf(Action.NONE),
        ),
        Cause.NO_SPEAKER to Notice(
            Category.CAPABILITY, "未检测到扬声器", "这台设备找不到音频输出，接上外放或换一台有声的设备，画面不受影响。",
            listOf(Action.NONE),
        ),
        Cause.AUDIO_INTERRUPTED to Notice(
            Category.SESSION, "声音被其他应用占用", "画面继续投，等声音空出来会自动接上，不用重新投屏。",
            listOf(Action.AUTO_RECOVER),
        ),
        Cause.CODEC_FALLBACK to Notice(
            Category.CAPABILITY, "已切换到兼容编码", "这台设备解不动 H.265，改用 H.264 并降一档，画质可能下降。",
            listOf(Action.DOWNGRADE),
        ),
        Cause.TIER_UNSTABLE to Notice(
            Category.SESSION, "当前画质放不稳", "你锁定了画质档位，系统不会自动降档；取消锁定就能自动降到能稳住的一档。",
            listOf(Action.OPEN_SETTINGS, Action.AUTO_RECOVER),
        ),
        Cause.BANDWIDTH_JUMP to Notice(
            Category.NETWORK, "带宽不够已直接降档", "这条链路撑不住当前码率，一步降到能维持的档位，不用重新投屏。",
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
            Category.PAIRING, "投屏码暂时锁定", "连续输错 5 次会锁 60 秒，等一分钟再试。",
            listOf(Action.RETRY_REQUEST),
        ),
        Cause.CODE_USED to Notice(
            Category.PAIRING, "投屏码已用过", "一个投屏码只能用一次，请改用大屏上正在显示的新码。",
            listOf(Action.REGET_CODE),
        ),
        Cause.REFUSED_BY_USER to Notice(
            // 两个来源共用这一条：大屏当场点「拒绝」，以及拒绝后的静默期。
            // 静默期内对方就算在大屏点允许也进不来，所以副文案要给出「等一会儿」这条路
            Category.PAIRING, "大屏拒绝了投屏", "需要对方在大屏上点允许；刚被拒绝过的话，等一分钟再试。",
            listOf(Action.RETRY_REQUEST),
        ),
        Cause.NO_AUTH to Notice(
            Category.PAIRING, "这台设备未授权", "先在投屏端输入大屏上的投屏码完成配对。",
            listOf(Action.ENTER_CODE),
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
            listOf(Action.RECONNECT),
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

    /** 渲染文案 → Cause 的反查表：report 里存的是 textFor 的结果，causeOfLegacyReason 不能round-trip。 */
    private val renderedToCause: Map<String, Cause> = table.keys.associateBy { textFor(it) }

    fun noticeFor(cause: Cause): Notice = table.getValue(cause)

    /**
     * 把已经渲染成文案的 report 反查回 Cause；非 ErrorNotice 文案（画质/自定义提示）回 null。
     * 供界面区分「码类拒绝」与其它错误，避免在界面层重新散落中文串匹配。
     */
    fun causeOfRendered(text: String): Cause? = renderedToCause[text]

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

    /** 动作按钮文案（UX-01 空态三按钮固定口径）；界面据此渲染，不再各自拼中文。 */
    fun actionLabel(action: Action): String = when (action) {
        Action.ENTER_CODE -> "用投屏码连接"
        Action.ENTER_IP -> "手动输 IP"
        Action.REGET_CODE -> "重新获取投屏码"
        Action.RETRY_REQUEST -> "重试"
        Action.RETRY_DISCOVERY -> "重试发现"
        Action.OPEN_SETTINGS -> "去设置"
        Action.USE_FALLBACK -> "改用兼容方式"
        Action.DOWNGRADE -> "降低画质"
        Action.SWITCH_TO_PUSH -> "改用推送"
        Action.SWITCH_TO_MIRROR -> "改用镜像"
        Action.AUTO_RECOVER -> "自动恢复"
        Action.RECONNECT -> "重新连接"
        Action.CHANGE_SOURCE -> "换个片源"
        Action.NONE -> ""
    }

    /**
     * PRD FS-05「4K 播放卡顿」的原话，档位随实际那一档代入（4K 时与需求文档逐字一致）。
     * 单独抽出来是因为它要说清「限制在哪一端、哪一档」，是 7.3 自解释口径的要求。
     */
    fun tierUnstable(tierLabel: String): String = "当前网络或设备不足以稳定播放 $tierLabel"
}
