package io.github.wangnan05563.airmirror.perf

/**
 * 音画同步判定（PRD FR-09 处理规则 + FS-06「同步参数 / 状态迁移」）。
 *
 * 这里只算不播：把 PRD 给的几处数字收敛成能在 JVM 上验证的纯判定，
 * 播放侧（[io.github.wangnan05563.airmirror.media.AudioOut]）只执行它给出的动作。
 */
object AvSync {
    /** 音频缓冲目标区间下限（PRD 同步参数「音频缓冲区间 40–80ms」） */
    const val MIN_BUFFER_MS = 40

    const val MAX_BUFFER_MS = 80

    /** 单次调整幅度上限：再大就会听出变速（PRD「避免产生可听出的变速或顿挫」） */
    const val MAX_ADJUST_MS = 10

    /** 偏差容忍区取 FS-06 验收口径「音画偏差 ≤80ms」，与缓冲上限同一数 */
    const val TOLERANCE_MS = 80

    /** 累计偏差超此值执行一次跳变对齐（PRD「硬对齐阈值 300ms」） */
    const val HARD_ALIGN_MS = 300

    /** 连续硬对齐到这个次数就降档（PRD「连续三次则降档」） */
    const val HARD_ALIGN_DOWNGRADE = 3

    /** 音频轨中断超 1 秒判为被占用（PRD FS-01/FS-06 异常表） */
    const val INTERRUPT_MS = 1_000

    /** 暂停、跳转、恢复后必须在 1 秒内回到容忍区 */
    const val RESYNC_WINDOW_MS = 1_000

    /**
     * 两次跳变对齐之间的最小间隔：跳变是把声音整段清空重来，一次就要命；
     * 没给够它起效的时间就再来一次，听感是一秒抽五六下，比偏差本身更糟。
     * 取与重新对齐窗口同一个数（1 秒内该回到容忍区）。
     */
    const val HARD_ALIGN_COOLDOWN_MS = RESYNC_WINDOW_MS

    enum class BufferMove {
        /** 水位在区间内，不动 */
        HOLD,

        /** 高于上限：追帧，把声音往前赶 */
        CATCH_UP,

        /** 低于下限：轻微拉长，把声音往后压一点 */
        STRETCH,
    }

    data class Buffer(val move: BufferMove, val adjustMs: Int)

    /**
     * 每 100ms 采一次缓冲水位后问一句：这一拍该怎么动。
     *
     * 方向看**音画偏差的符号**，不看水位：水位只用来刹车。
     * 声音比画面晚就抽帧追（CATCH_UP），声音比画面早就垫静音往后压（STRETCH），
     * 但抽不能把缓冲抽到区间下限以下、垫不能垫过上限——否则一次调整就把延迟做上去，
     * 偏差反而越修越大。单次幅度仍然锁在 [MAX_ADJUST_MS] 以内。
     */
    fun correction(driftMs: Int, levelMs: Int): Buffer = when {
        driftMs > TOLERANCE_MS ->
            if (levelMs > MIN_BUFFER_MS) Buffer(BufferMove.CATCH_UP, (driftMs - TOLERANCE_MS).coerceAtMost(MAX_ADJUST_MS))
            else Buffer(BufferMove.HOLD, 0)

        driftMs < -TOLERANCE_MS ->
            if (levelMs < MAX_BUFFER_MS) Buffer(BufferMove.STRETCH, (-driftMs - TOLERANCE_MS).coerceAtMost(MAX_ADJUST_MS))
            else Buffer(BufferMove.HOLD, 0)

        else -> Buffer(BufferMove.HOLD, 0)
    }

    /** 一帧 AAC 的时长（毫秒，48kHz/1024 样本约 21ms）：追帧与拉长都只能按整帧落地。 */
    const val FRAME_MS = 21

    /**
     * 把「每次 ≤10ms」的调整攒成整帧动作。
     *
     * 单次调整比一帧还短，直接按拍执行会变成每 100ms 抽掉一帧——那不是追帧，是卡顿。
     * 所以欠账攒够一整帧才真丢一个访问单元；拉长方向同理，攒够一帧才垫一次静音，
     * 且上限锁在一帧，避免缓冲越垫越深把延迟做上去。
     */
    class Pacer {
        enum class Action {
            NONE,

            /** 丢掉这么多个音频访问单元 */
            SKIP,

            /** 垫这么长的静音（毫秒）来把声音往后压 */
            PAD,
        }

        data class Move(val action: Action, val amount: Int)

        private var catchUpMs = 0
        private var stretchMs = 0

        fun onBuffer(buffer: Buffer): Move = when (buffer.move) {
            BufferMove.HOLD -> {
                catchUpMs = 0
                stretchMs = 0
                Move(Action.NONE, 0)
            }

            BufferMove.CATCH_UP -> {
                stretchMs = 0
                catchUpMs += buffer.adjustMs
                val frames = catchUpMs / FRAME_MS
                if (frames <= 0) {
                    Move(Action.NONE, 0)
                } else {
                    catchUpMs -= frames * FRAME_MS
                    Move(Action.SKIP, frames)
                }
            }

            BufferMove.STRETCH -> {
                catchUpMs = 0
                stretchMs = (stretchMs + buffer.adjustMs).coerceAtMost(FRAME_MS)
                Move(Action.PAD, stretchMs)
            }
        }

        /** 主时钟被重置（硬对齐、重新对齐）时欠账作废，否则会把上一段的状态带进新基准 */
        fun reset() {
            catchUpMs = 0
            stretchMs = 0
        }
    }

    enum class VideoAction {
        /** 按时呈现 */
        RENDER,

        /** 早到：等主时钟追上来（PRD「早到的帧等待」） */
        WAIT,

        /** 晚到超容忍区：丢掉这一帧，但关键帧不丢（PRD「丢非关键帧，避免花屏」） */
        DROP,
    }

    /** 视频帧按音频主时钟对齐：正数表示画面晚于声音。 */
    fun videoAction(framePtsUs: Long, audioClockUs: Long, keyFrame: Boolean): VideoAction {
        val driftMs = (audioClockUs - framePtsUs) / 1_000
        return when {
            driftMs > TOLERANCE_MS && !keyFrame -> VideoAction.DROP
            driftMs < -TOLERANCE_MS -> VideoAction.WAIT
            else -> VideoAction.RENDER
        }
    }

    enum class State {
        SYNCED,
        MICRO_ADJUST,

        /** 正在执行一次跳变对齐，之后必须回到 [SYNCED] */
        HARD_ALIGN,

        /** 暂停/恢复/跳转/音频恢复后的重新对齐窗口 */
        RESYNC,
    }

    enum class Action {
        NONE,

        /** 只调音频缓冲，不改档位（PRD 状态迁移 MICRO-ADJUST） */
        MICRO_ADJUST,

        /** 累计偏差超硬对齐阈值，执行一次跳变 */
        HARD_ALIGN,

        /** 连续硬对齐仍拉不回来，请求降档 */
        DOWNGRADE,
    }

    /**
     * 同步状态机。偏差取绝对值即可判达标与否，但状态迁移要看方向：
     * 回到容忍区才停调整，超阈值才跳变，跳变三次仍不回就降档。
     */
    class Tracker {
        var state: State = State.SYNCED
            private set

        /** 连续硬对齐次数，回到容忍区即清零（PRD 的「连续」） */
        var hardAlignStreak: Int = 0
            private set

        /** 重新对齐的截止时间（毫秒时刻），0 表示不在重新对齐中 */
        var resyncDeadlineMs: Long = 0L
            private set

        /** 是否曾经错过「1 秒内回到容忍区」，验收与取证要看这一条 */
        var resyncMissed: Boolean = false
            private set

        private var nextHardAlignAtMs = 0L

        fun onDrift(absDriftMs: Int, nowMs: Long): Action {
            if (absDriftMs <= TOLERANCE_MS) {
                // 回到容忍区即停手；但要记下这次是不是超过了 1 秒才回来（验收看这一条）
                val backLate = state == State.RESYNC && resyncDeadlineMs > 0L && nowMs > resyncDeadlineMs
                resyncMissed = resyncMissed || backLate
                hardAlignStreak = 0
                nextHardAlignAtMs = 0L
                state = State.SYNCED
                resyncDeadlineMs = 0L
                return Action.NONE
            }
            if (absDriftMs >= HARD_ALIGN_MS) {
                // 上一次跳变还在生效窗口里就再抽一次，声音会被切成卡顿；「连续三次」数的是真的试过三次
                if (nowMs < nextHardAlignAtMs) {
                    state = State.HARD_ALIGN
                    return Action.NONE
                }
                state = State.HARD_ALIGN
                hardAlignStreak++
                nextHardAlignAtMs = nowMs + HARD_ALIGN_COOLDOWN_MS
                resyncDeadlineMs = 0L
                return if (hardAlignStreak >= HARD_ALIGN_DOWNGRADE) {
                    hardAlignStreak = 0
                    state = State.SYNCED
                    Action.DOWNGRADE
                } else {
                    Action.HARD_ALIGN
                }
            }
            // 容忍区外、阈值内：微调音频缓冲；重新对齐窗口已过仍没回区，按错过记录并继续微调
            if (state == State.RESYNC && nowMs > resyncDeadlineMs) resyncMissed = true
            state = if (state == State.RESYNC) State.RESYNC else State.MICRO_ADJUST
            return Action.MICRO_ADJUST
        }

        /** 暂停恢复、跳转、音频通道重新出现：重新对齐，1 秒内要回到容忍区 */
        fun onResync(nowMs: Long) {
            state = State.RESYNC
            resyncDeadlineMs = nowMs + RESYNC_WINDOW_MS
            resyncMissed = false
            nextHardAlignAtMs = 0L
        }

        /** 系统时间跳变：重置基准重新对齐，不打扰用户（PRD 异常表「时间戳回退或大幅前跳」） */
        fun onClockJump(nowMs: Long) = onResync(nowMs)

        /** 跳变对齐执行完要落回 SYNCED（PRD「之后必须回到 SYNCED」） */
        fun afterHardAlign() {
            state = State.SYNCED
        }
    }

    /** 音频轨是否已被系统中断（上一次有声音的时刻到现在超过 1 秒） */
    fun audioInterrupted(lastAudioAtMs: Long, nowMs: Long): Boolean =
        lastAudioAtMs > 0L && nowMs - lastAudioAtMs > INTERRUPT_MS
}
