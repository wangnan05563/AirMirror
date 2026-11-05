package io.github.wangnan05563.airmirror.receiver

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import io.github.wangnan05563.airmirror.dlna.DlnaServer
import io.github.wangnan05563.airmirror.net.ProbeResponder
import io.github.wangnan05563.airmirror.pairing.DeviceStore
import io.github.wangnan05563.airmirror.pairing.Pairing
import io.github.wangnan05563.airmirror.privacy.Diagnostics

/**
 * 接收端常驻宿主（PRD FS-13 / 8.2「后台常驻与发现」）：
 * 会话与 DLNA 渲染端挂在进程级单例上，界面只订阅状态。
 * 界面被系统回收时监听与广播不中断，重新回到前台后按快照恢复画面与横幅。
 */
class ReceiverHost private constructor(context: Context) {
    private val appContext: Context = context.applicationContext ?: context

    init {
        // 开机自启路径不经过 MainActivity，诊断接管要在进程宿主起来时就装好（install 幂等）
        Diagnostics(appContext).install()
    }

    interface Ui {
        fun onStatus(text: String)
        fun onPrompt(prompt: ReceiverSession.Prompt)
        fun onCasting(casting: Boolean, senderName: String)
        fun onNotice(text: String)
        fun onRecovery(active: Boolean)
        fun onVideoSize(width: Int, height: Int)
        fun onDlnaStatus(text: String)
        fun onDlnaPlaying(playing: Boolean)
        /** 画面角标的读数（PRD FS-05）：默认不显示，界面开了才渲染，所以给个空实现。[kiloBits] 为实测入流码率 kbps */
        fun onQualityReadout(width: Int, height: Int, kiloBits: Int) = Unit
    }

    val store = DeviceStore(appContext)
    val pairing = Pairing(store)

    var session: ReceiverSession? = null
        private set

    private var dlna: DlnaServer? = null
    private var probe: ProbeResponder? = null
    private var probeName = ""

    /** 界面交下来的 Surface：宿主比 Surface 晚起来时（进程重启、界面重建）靠它补挂，否则画面会一直黑。 */
    private var holder: SurfaceHolder? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var ui: Ui? = null

    @Volatile
    var casting = false
        private set

    @Volatile
    var senderName = ""
        private set

    @Volatile
    var videoWidth = 0
        private set

    @Volatile
    var videoHeight = 0
        private set

    /** 画面角标的数据源（PRD FS-05）：实际在跑的画幅与实测入流码率 kbps，界面开了角标才渲染 */
    @Volatile
    var qualityWidth = 0
        private set

    @Volatile
    var qualityHeight = 0
        private set

    @Volatile
    var qualityKbps = 0
        private set

    @Volatile
    var recovering = false
        private set

    @Volatile
    var dlnaPlaying = false
        private set

    @Volatile
    var lastStatus = ""
        private set

    /** 幂等：界面与服务谁先起来都只建一套监听，避免第二个会话抢不到固定端口。 */
    @Synchronized
    fun start(deviceName: String) {
        if (session != null) {
            // 进程还在、服务被重启（或设置里改了设备名）时，只把探测应答器补齐/换名即可
            ensureProbe(deviceName)
            return
        }
        val active = ReceiverSession(
            context = appContext,
            pairing = pairing,
            onStatus = { text ->
                lastStatus = text
                post { it.onStatus(text) }
            },
            onPrompt = { prompt -> post { it.onPrompt(prompt) } },
            onCasting = { casting, sender ->
                this.casting = casting
                senderName = if (casting) sender else ""
                if (!casting) recovering = false
                post { it.onCasting(casting, sender) }
            },
            onNotice = { text -> post { it.onNotice(text) } },
            onRecovery = { active ->
                recovering = active
                post { it.onRecovery(active) }
            },
            onVideoSize = { width, height ->
                videoWidth = width
                videoHeight = height
                post { it.onVideoSize(width, height) }
            },
            onQualityReadout = { width, height, kiloBits ->
                qualityWidth = width
                qualityHeight = height
                qualityKbps = kiloBits
                post { it.onQualityReadout(width, height, kiloBits) }
            },
        )
        // 大屏锁过的档位要立刻带上，第一次上报的上限就得是锁定后的那一档
        active.applyQualityLock(store.qualityLock())
        active.start(deviceName)
        session = active
        Log.i(TAG, "host session started port=${active.port}")

        val renderer = DlnaServer(appContext) { text ->
            lastStatus = text
            post { it.onDlnaStatus(text) }
        }
        renderer.onPlayingChanged = { playing ->
            dlnaPlaying = playing
            post { it.onDlnaPlaying(playing) }
        }
        // 只投「此刻值得让用户看见」的一条：界面在后台时收到的是 lastStatus，
        // 回到前台 Surface 重建那一刻由 announceSurfaceBack 再补一条浮层
        renderer.onNotice = { text -> post { it.onNotice(text) } }
        renderer.start(deviceName)
        dlna = renderer
        ensureProbe(deviceName)
        holder?.let { mount(it) }
    }

    /**
     * FS-07 接收侧：组播被禁时，接收端仍要在 41524 回答探测、定时朝 41525 公告自己。
     * 两条 UDP 通道都不碰 41523 媒体端口，避免被 8.9 的限流闸门记成半途撤连接。
     */
    private fun ensureProbe(deviceName: String) {
        if (probe != null && probeName == deviceName) return
        probe?.stop()
        probeName = deviceName
        probe = ProbeResponder(appContext, store.deviceId()).also { it.start(deviceName, port) }
    }

    /** 常驻服务收尾：只停探测应答器，会话与界面快照保持原样（界面可能还活着）。 */
    @Synchronized
    fun shutdown() {
        probe?.stop()
        probe = null
        probeName = ""
    }

    /** 界面回到前台：先订阅后续变化，再按宿主快照补回画面、横幅与角标读数。 */
    fun attachUi(next: Ui) {
        ui = next
        if (lastStatus.isNotEmpty()) next.onStatus(lastStatus)
        next.onCasting(casting, senderName)
        if (videoWidth > 0 && videoHeight > 0) next.onVideoSize(videoWidth, videoHeight)
        if (qualityWidth > 0) next.onQualityReadout(qualityWidth, qualityHeight, qualityKbps)
        if (dlnaPlaying) next.onDlnaPlaying(true)
        if (recovering) next.onRecovery(true)
    }

    /**
     * 设置页改了画质锁定：把落盘值交给会话。投屏中改会立刻重谈一次上限，
     * 没在投屏时只记账，下次授权自然带上（PRD FS-05 手动锁定）。
     */
    fun applyQualityLock() {
        session?.applyQualityLock(store.qualityLock())
    }

    fun detachUi(next: Ui) {
        if (ui === next) ui = null
    }

    val port: Int get() = session?.port ?: 0

    fun resolve(allowed: Boolean) {
        session?.resolve(allowed)
    }

    fun endActiveSession() {
        session?.endActiveSession()
    }

    fun attachSurface(next: SurfaceHolder) {
        holder = next
        mount(next)
    }

    private fun mount(next: SurfaceHolder) {
        session?.attachSurface(next.surface)
        dlna?.attach(next)
    }

    fun detachSurface() {
        holder = null
        session?.detachSurface()
        dlna?.detach()
    }

    private fun post(action: (Ui) -> Unit) {
        main.post {
            val target = ui ?: return@post
            action(target)
        }
    }

    companion object {
        @Volatile
        private var instance: ReceiverHost? = null

        fun get(context: Context): ReceiverHost =
            instance ?: synchronized(this) {
                instance ?: ReceiverHost(context.applicationContext).also { instance = it }
            }.also { it.store.ensureDefaultDeviceName() }

        fun defaultDeviceName(): String =
            io.github.wangnan05563.airmirror.util.Onboarding.defaultDeviceName(Build.MODEL)

        private const val TAG = "AirMirrorHost"
    }
}