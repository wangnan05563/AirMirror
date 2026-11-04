package io.github.wangnan05563.airmirror.dlna

/**
 * AVTransport 状态机（纯逻辑，不含播放器调用）。
 *
 * 状态名用 UPnP 口径：NO_MEDIA_PRESENT / STOPPED / PLAYING / PAUSED_PLAYING / TRANSITIONING。
 * 播放进度由播放器提供，这里只管「能不能转、往哪转」。
 */
class TransportMachine {

    enum class State { NO_MEDIA, LOADING, PLAYING, PAUSED, STOPPED }

    /** 状态由 SOAP 线程改写、主线程播放器回调改写，读取方是另一个连接线程。 */
    @Volatile
    var state: State = State.NO_MEDIA
        private set

    @Volatile
    var media: Upnp.MediaMeta? = null
        private set

    @Volatile
    var playMode: String = "NORMAL"
        private set

    /** Play 先于媒体就绪时记下意图，等 onLoaded 兑现，避免中途谎报 PLAYING。 */
    @Volatile
    private var playRequested: Boolean = false

    /** 控制点读到的 TransportState。 */
    fun transportState(): String = when (state) {
        State.NO_MEDIA -> "NO_MEDIA_PRESENT"
        State.LOADING -> "TRANSITIONING"
        State.PLAYING -> "PLAYING"
        State.PAUSED -> "PAUSED_PLAYING"
        State.STOPPED -> "STOPPED"
    }

    fun transportStatus(): String = if (state == State.NO_MEDIA) "NOT_IMPLEMENTED" else "OK"

    /** GetTransportInfo 的 CurrentSpeed：UPnP 口径只有 0 与 1。 */
    fun currentSpeed(): String = if (state == State.PLAYING) "1" else "0"

    fun currentActions(): String = when (state) {
        State.PLAYING -> "Play,Pause,Stop,Seek,Next"
        State.PAUSED, State.STOPPED, State.LOADING -> "Play,Stop,Seek,Next"
        State.NO_MEDIA -> ""
    }

    /** 控制点是否已经要求播放（含 Play 早于就绪、正在排队等就绪的情况）。 */
    val wantsPlayback: Boolean get() = state == State.PLAYING || playRequested

    /** 收到新媒体描述：进入 LOADING，由服务端异步准备；未就绪前 Play 视为非法。 */
    fun setUri(meta: Upnp.MediaMeta?) {
        media = meta
        playRequested = false
        state = if (meta == null) State.NO_MEDIA else State.LOADING
    }

    /**
     * 媒体准备完成（尚未起播）。期间收到过 Play 就直接进 PLAYING（播放器侧已经
     * 排队了 start），否则回 STOPPED 等控制点再发。
     */
    fun onLoaded() {
        if (state == State.LOADING) state = if (playRequested) State.PLAYING else State.STOPPED
        playRequested = false
    }

    /**
     * Play 落在还没就绪的媒体上时**不能**先报 PLAYING。
     * UPnP 口径的 PLAYING 意味着画面真的在走；提前报会让控制点以为一切正常而持续
     * 轮询进度，大屏却还黑着——取流失效时尤其明显。未就绪期间保持 TRANSITIONING，
     * 由 onLoaded 在真正起播的那一刻补上。
     */
    fun onPlayInvoked() {
        when (state) {
            State.LOADING -> playRequested = true
            State.STOPPED, State.PAUSED -> state = State.PLAYING
            else -> Unit
        }
    }

    fun onPauseInvoked() {
        if (state == State.PLAYING) state = State.PAUSED
    }

    fun onStopInvoked() {
        playRequested = false
        if (media != null) state = State.STOPPED
    }

    fun onCompleted(loop: Boolean) {
        if (!loop && media != null) state = State.STOPPED
    }

    /**
     * 播放失败：状态必须回落到 STOPPED。
     * 媒体还挂在上面（片名、地址仍要能被 GetPositionInfo 读到），但速度一定是 0，
     * 否则控制点会以为还在播并一直轮询进度。
     */
    fun onPlaybackFailed() {
        playRequested = false
        if (media != null) state = State.STOPPED
    }

    /**
     * 服务停止时清掉会话内容：片名与片源地址只活在当前进程内存里，
     * 既不落盘也不跨会话保留（PRD FS-11「投屏内容留存：不保存」）。
     */
    fun clear() {
        media = null
        playRequested = false
        state = State.NO_MEDIA
    }

    /** Play 前置校验：返回 null 表示允许。 */
    fun canPlay(): Int? = if (media == null) Upnp.FAULT_NO_MEDIA else null

    fun canPauseOrSeek(): Int? = when (state) {
        State.NO_MEDIA -> Upnp.FAULT_NO_MEDIA
        State.PLAYING, State.PAUSED, State.STOPPED, State.LOADING -> null
    }

    fun setPlayMode(mode: String): Int? {
        if (mode !in PLAY_MODES) return Upnp.FAULT_INVALID_ARGS
        playMode = mode
        return null
    }

    companion object {
        val PLAY_MODES = setOf("NORMAL", "REPEAT_ALL", "REPEAT_ONE", "SHUFFLE", "SHUFFLE_NOREPEAT")
    }
}
