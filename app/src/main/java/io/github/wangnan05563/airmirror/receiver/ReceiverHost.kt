package io.github.wangnan05563.airmirror.receiver

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import io.github.wangnan05563.airmirror.dlna.DlnaServer
import io.github.wangnan05563.airmirror.pairing.DeviceStore
import io.github.wangnan05563.airmirror.pairing.Pairing

/**
 * 接收端常驻宿主（PRD FS-13 / 8.2「后台常驻与发现」）：
 * 会话与 DLNA 渲染端挂在进程级单例上，界面只订阅状态。
 * 界面被系统回收时监听与广播不中断，重新回到前台后按快照恢复画面与横幅。
 */
class ReceiverHost private constructor(context: Context) {
    private val appContext: Context = context.applicationContext ?: context

    interface Ui {
        fun onStatus(text: String)
        fun onPrompt(prompt: ReceiverSession.Prompt)
        fun onCasting(casting: Boolean, senderName: String)
        fun onNotice(text: String)
        fun onRecovery(active: Boolean)
        fun onVideoSize(width: Int, height: Int)
        fun onDlnaStatus(text: String)
        fun onDlnaPlaying(playing: Boolean)
    }

    val store = DeviceStore(appContext)
    val pairing = Pairing(store)

    var session: ReceiverSession? = null
        private set

    private var dlna: DlnaServer? = null

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
        if (session != null) return
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
        )
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
        renderer.start(deviceName)
        dlna = renderer
        holder?.let { mount(it) }
    }

    /** 界面回到前台：先订阅后续变化，再按宿主快照把当前画面/横幅补回界面。 */
    fun attachUi(next: Ui) {
        ui = next
        if (lastStatus.isNotEmpty()) next.onStatus(lastStatus)
        next.onCasting(casting, senderName)
        if (videoWidth > 0 && videoHeight > 0) next.onVideoSize(videoWidth, videoHeight)
        if (dlnaPlaying) next.onDlnaPlaying(true)
        if (recovering) next.onRecovery(true)
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
            }

        fun defaultDeviceName(): String =
            "AirMirror-${Build.MODEL}".replace(Regex("[^A-Za-z0-9-]"), "-").take(30)

        private const val TAG = "AirMirrorHost"
    }
}