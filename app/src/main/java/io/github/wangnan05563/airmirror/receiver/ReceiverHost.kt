package io.github.wangnan05563.airmirror.receiver

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import io.github.wangnan05563.airmirror.crossnet.CandidateCollector
import io.github.wangnan05563.airmirror.crossnet.CrossNetConnector
import io.github.wangnan05563.airmirror.crossnet.CrossNetInbound
import io.github.wangnan05563.airmirror.crossnet.CrossNetProtocol
import io.github.wangnan05563.airmirror.crossnet.HttpSignalingTransport
import io.github.wangnan05563.airmirror.crossnet.SignalingClient
import io.github.wangnan05563.airmirror.crossnet.SignalingSettings
import io.github.wangnan05563.airmirror.device.DeviceProbe
import io.github.wangnan05563.airmirror.dlna.DlnaServer
import io.github.wangnan05563.airmirror.net.ProbeResponder
import io.github.wangnan05563.airmirror.pairing.CastCode
import io.github.wangnan05563.airmirror.pairing.DeviceStore
import io.github.wangnan05563.airmirror.pairing.Pairing
import io.github.wangnan05563.airmirror.privacy.Diagnostics
import kotlin.concurrent.thread

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
        // 分级结果决定协议是否常驻；放到后台线程算，别让解码器枚举堵住待连接页
        thread(name = "airmirror-grade", isDaemon = true) {
            protocolsResident = DeviceProbe.policy(appContext).protocolsResident
        }
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
        /** PRD FS-02 图片推送：接收端把解好的图交给界面显示，不走 Surface。 */
        fun onDlnaImage(image: Bitmap, title: String)
        fun onDlnaImageCleared()
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
    private var crossNet: CrossNetInbound? = null

    /**
     * 本机等级决定协议是否常驻（PRD 1152）：A/B 级常驻，只有 C 级参与闲置释放。
     * 预先在后台线程算好：分级要枚举解码器能力，别在巡检那一刻占着主线程。
     */
    @Volatile
    private var protocolsResident = true

    /** 协议运行时的闲置计时（PRD 1152「按需加载，闲置 5 分钟释放」）。 */
    private val residency = ProtocolResidency(now = { System.currentTimeMillis() })

    /** 释放巡检：远小于 5 分钟窗口的粒度，模块不忙且闲置够久才放。 */
    private val idleSweep = object : Runnable {
        override fun run() {
            if (!protocolsResident) {
                for (module in residency.releaseCandidates()) {
                    val released = when (module) {
                        ProtocolModule.DLNA -> dlna?.releaseIdle() == true
                        // 私有通道没有可释放的常驻运行时，监听必须一直在
                        ProtocolModule.PRIVATE_CHANNEL -> false
                    }
                    if (released) residency.markReleased(module)
                }
            }
            main.postDelayed(this, ProtocolResidency.TICK_MILLIS)
        }
    }

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

    /** 后台收到的图片推送：界面重建后要能原样补回，不能因为 Activity 被回收就黑屏。 */
    @Volatile
    private var dlnaImage: Bitmap? = null
    @Volatile
    private var dlnaImageTitle = ""

    @Volatile
    var lastStatus = ""
        private set

    /** 幂等：界面与服务谁先起来都只建一套监听，避免第二个会话抢不到固定端口。 */
    @Synchronized
    fun start(deviceName: String) {
        if (session != null) {
            // 进程还在、服务被重启（或设置里改了设备名）时，只把探测应答器补齐/换名即可
            ensureProbe(deviceName)
            startCrossNet()
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
            // 播放状态变化既算活动，也决定这一刻能不能释放（PRD 1152）
            residency.activity(ProtocolModule.DLNA)
            residency.hold(ProtocolModule.DLNA, playing)
            post { it.onDlnaPlaying(playing) }
        }
        renderer.onActivity = { residency.activity(ProtocolModule.DLNA) }
        renderer.onImageShow = { image, title ->
            dlnaImage = image
            dlnaImageTitle = title
            post { it.onDlnaImage(image, title) }
        }
        renderer.onImageClear = {
            dlnaImage = null
            dlnaImageTitle = ""
            post { it.onDlnaImageCleared() }
        }
        // 只投「此刻值得让用户看见」的一条：界面在后台时收到的是 lastStatus，
        // 回到前台 Surface 重建那一刻由 announceSurfaceBack 再补一条浮层
        renderer.onNotice = { text -> post { it.onNotice(text) } }
        renderer.start(deviceName)
        dlna = renderer
        ensureProbe(deviceName)
        // 模块起来即记一笔活动：C 级的 5 分钟闲置窗口从这里开始算
        residency.activity(ProtocolModule.DLNA)
        main.removeCallbacks(idleSweep)
        main.postDelayed(idleSweep, ProtocolResidency.TICK_MILLIS)
        holder?.let { mount(it) }
        startCrossNet()
    }

    /**
     * 跨网待命（PRD FS-04）：只有配置了信令端点才启动，默认不向任何外部服务发请求。
     * 设置页改端点后调用 [reloadCrossNet]，避免旧端点继续收到本机的码哈希。
     */
    private fun startCrossNet() {
        if (crossNet != null) return
        val url = store.signalingUrl()
        if (!SignalingSettings.enabled(url)) return
        val client = SignalingClient(HttpSignalingTransport(url))
        val stun = store.stunHost()
        val inbound = CrossNetInbound(
            connect = { code ->
                CrossNetConnector(
                    role = CrossNetProtocol.Role.RECEIVER,
                    client = client,
                    collect = { socket ->
                        CandidateCollector.collect(socket, CandidateCollector.Config(stunHost = stun))
                    },
                ).connectReceiver(code)
            },
            // 锁定期不登记：这时大屏本身不接受任何码，登记只会让发送端匹配到一个必然被拒的会话
            credential = {
                if (pairing.code.state() == CastCode.State.LOCKED) "" else pairing.code.code()
            },
            onLink = { outcome -> outcome.link?.let { session?.offerTunnel(it) } },
        )
        inbound.start()
        crossNet = inbound
        Log.i(TAG, "cross-net standby started")
    }

    /** 设置页改完信令端点后调用；端点被清空即停掉待命，不再发任何请求。 */
    @Synchronized
    fun reloadCrossNet() {
        crossNet?.stop()
        crossNet = null
        startCrossNet()
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
        crossNet?.stop()
        crossNet = null
    }

    /** 界面回到前台：先订阅后续变化，再按宿主快照补回画面、横幅与角标读数。 */
    fun attachUi(next: Ui) {
        ui = next
        if (lastStatus.isNotEmpty()) next.onStatus(lastStatus)
        next.onCasting(casting, senderName)
        if (videoWidth > 0 && videoHeight > 0) next.onVideoSize(videoWidth, videoHeight)
        if (qualityWidth > 0) next.onQualityReadout(qualityWidth, qualityHeight, qualityKbps)
        if (dlnaPlaying) next.onDlnaPlaying(true)
        dlnaImage?.let { next.onDlnaImage(it, dlnaImageTitle) }
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

    /**
     * 只摘掉调用方那个面。Activity 重建时，旧实例的 `surfaceDestroyed` 常晚于新实例的
     * `surfaceCreated` 到达；不校验归属就会把新面一起摘掉，之后画面永久黑屏。
     */
    fun detachSurface(next: SurfaceHolder) {
        if (holder !== next) return
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