package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.View
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.wangnan05563.airmirror.dlna.DlnaServer
import io.github.wangnan05563.airmirror.dlna.localIpv4
import io.github.wangnan05563.airmirror.device.DeviceProbe
import io.github.wangnan05563.airmirror.pairing.CastCode
import io.github.wangnan05563.airmirror.pairing.DeviceStore
import io.github.wangnan05563.airmirror.pairing.Pairing
import io.github.wangnan05563.airmirror.protocol.ProtocolBoundary
import io.github.wangnan05563.airmirror.receiver.ReceiverSession

/**
 * 接收端待连接页（PRD §11.2）：设备名居中最上方，二维码与 6 位投屏码并列，下方两行引导文案；
 * 投屏中整屏只保留画面，仅在连接成功时叠加 3 秒设备名。
 */
class ReceiverActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var store: DeviceStore
    private lateinit var pairing: Pairing
    private var session: ReceiverSession? = null
    private var dlna: DlnaServer? = null

    private lateinit var waiting: LinearLayout
    private lateinit var deviceNameText: TextView
    private lateinit var codeText: TextView
    private lateinit var countdownText: TextView
    private lateinit var ring: CountdownRingView
    private lateinit var qrView: ImageView
    private lateinit var statusText: TextView
    private lateinit var gradeText: TextView
    private lateinit var overlayName: TextView
    private lateinit var noticeOverlay: TextView
    private lateinit var promptBar: LinearLayout
    private lateinit var promptText: TextView
    private lateinit var endBar: LinearLayout
    private lateinit var endText: TextView

    private var lastPayload = ""
    private var isCasting = false
    private var endConfirmUntil = 0L
    private val main = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshWaitingPage()
            tickEndConfirm()
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = DeviceStore(this)
        pairing = Pairing(store)
        val root = FrameLayout(this).apply { setBackgroundColor(Theme.BACKGROUND) }

        val surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        // 待连接页可滚动：小屏上正文超出屏幕时不该把底部文案裁掉
        root.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(buildWaitingPage(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            },
            fullScreen(),
        )
        root.addView(buildNameOverlay(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = Theme.dp(this@ReceiverActivity, 24) })
        root.addView(buildNoticeOverlay(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = Theme.dp(this@ReceiverActivity, 72) })
        root.addView(buildPromptBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))
        root.addView(buildEndBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))
        setContentView(root)

        deviceNameText.text = receiverName()
        startSessions(receiverName())
        main.post(ticker)
    }

    private fun fullScreen() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )

    private fun buildWaitingPage(): LinearLayout {
        waiting = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Theme.BACKGROUND)
            setPadding(Theme.dp(this@ReceiverActivity, 32), Theme.dp(this@ReceiverActivity, 28), Theme.dp(this@ReceiverActivity, 32), Theme.dp(this@ReceiverActivity, 24))
        }
        deviceNameText = TextView(this).apply {
            textSize = Theme.TITLE_SP
            setTextColor(Theme.TEXT_PRIMARY)
            gravity = Gravity.CENTER
            letterSpacing = 0.02f
        }
        waiting.addView(deviceNameText, matchWidth())
        // PRD §04 / §13.1：只写本机真能做到的入口，AirPlay 与 Miracast 在补齐前明确说不支持
        waiting.addView(TextView(this).apply {
            text = ProtocolBoundary.receiverMirrorLine()
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@ReceiverActivity, 8), 0, 0)
        }, matchWidth())
        waiting.addView(TextView(this).apply {
            text = ProtocolBoundary.receiverPushLine()
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@ReceiverActivity, 4), 0, Theme.dp(this@ReceiverActivity, 12))
        }, matchWidth())
        waiting.addView(TextView(this).apply {
            text = ProtocolBoundary.receiverUnsupportedLine()
            textSize = Theme.BODY_SP
            setTextColor(Theme.OFFLINE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, Theme.dp(this@ReceiverActivity, 12))
        }, matchWidth())

        val side = Theme.dp(this, 190)
        qrView = ImageView(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()) }
        waiting.addView(qrView, centeredBlock(side))

        // 投屏码单独占一行：和二维码并排时剩下的宽度放不下 6 位数字，会把码拆成两截
        val codeBlock = FrameLayout(this)
        ring = CountdownRingView(this)
        codeBlock.addView(ring, FrameLayout.LayoutParams(side, side))
        codeText = TextView(this).apply {
            textSize = Theme.CODE_SP
            setTextColor(Theme.ACCENT)
            gravity = Gravity.CENTER
            letterSpacing = 0.02f
            maxLines = 1
        }
        codeBlock.addView(
            codeText,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        waiting.addView(codeBlock, centeredBlock(side).apply { topMargin = Theme.dp(this@ReceiverActivity, 16) })

        countdownText = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@ReceiverActivity, 12), 0, 0)
        }
        waiting.addView(countdownText, matchWidth())

        // FS-12：本机等级与最高画质如实告知，用户据此判断该投哪一档；探测有开销，后台算完再回填
        gradeText = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@ReceiverActivity, 12), 0, 0)
        }
        waiting.addView(gradeText, matchWidth())
        Thread {
            val summary = DeviceProbe.summary(DeviceProbe.policy(this))
            runOnUiThread { gradeText.text = summary }
        }.start()

        statusText = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@ReceiverActivity, 20), 0, 0)
        }
        waiting.addView(statusText, matchWidth())
        waiting.addView(TextView(this).apply {
            text = "局域网直连 · 无广告 · 不保存画面"
            textSize = Theme.BODY_SP
            setTextColor(Theme.OFFLINE)
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@ReceiverActivity, 16), 0, 0)
        }, matchWidth())
        return waiting
    }

    private fun buildNameOverlay(): TextView {
        overlayName = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            setBackgroundColor(0xCC0B1F3A.toInt())
            setPadding(Theme.dp(this@ReceiverActivity, 24), Theme.dp(this@ReceiverActivity, 12), Theme.dp(this@ReceiverActivity, 24), Theme.dp(this@ReceiverActivity, 12))
            visibility = View.GONE
        }
        return overlayName
    }

    /** 投屏中状态栏和待连接页都不可见：暂停、接管请求、接管超时等状态在这里短暂提示。 */
    private fun buildNoticeOverlay(): TextView {
        noticeOverlay = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.ACCENT)
            setBackgroundColor(0xCC0B1F3A.toInt())
            setPadding(Theme.dp(this@ReceiverActivity, 20), Theme.dp(this@ReceiverActivity, 10), Theme.dp(this@ReceiverActivity, 20), Theme.dp(this@ReceiverActivity, 10))
            visibility = View.GONE
        }
        return noticeOverlay
    }

    private fun showNotice(text: String) {
        if (!isCasting || text.isEmpty()) return
        noticeOverlay.text = text
        noticeOverlay.visibility = View.VISIBLE
        main.removeCallbacks(hideNotice)
        main.postDelayed(hideNotice, NOTICE_MS)
    }

    private val hideNotice = Runnable { noticeOverlay.visibility = View.GONE }

    private fun buildPromptBar(): View {
        promptBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xE612314F.toInt())
            visibility = View.GONE
            setPadding(Theme.dp(this@ReceiverActivity, 16), Theme.dp(this@ReceiverActivity, 12), Theme.dp(this@ReceiverActivity, 16), Theme.dp(this@ReceiverActivity, 12))
        }
        promptText = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        promptBar.addView(promptText)
        promptBar.addView(Button(this).apply {
            text = "拒绝"
            setTextColor(Theme.DANGER)
            setOnClickListener { respondToPrompt(false) }
        })
        promptBar.addView(Button(this).apply {
            text = "允许"
            setTextColor(Theme.ACCENT)
            setOnClickListener { respondToPrompt(true) }
        })
        return promptBar
    }

    private fun buildEndBar(): View {
        endBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xE612314F.toInt())
            visibility = View.GONE
            setPadding(Theme.dp(this@ReceiverActivity, 16), Theme.dp(this@ReceiverActivity, 12), Theme.dp(this@ReceiverActivity, 16), Theme.dp(this@ReceiverActivity, 12))
        }
        endText = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        endBar.addView(endText)
        endBar.addView(Button(this).apply {
            text = "取消"
            setTextColor(Theme.TEXT_SECONDARY)
            setOnClickListener { cancelEndConfirm() }
        })
        endBar.addView(Button(this).apply {
            text = "结束"
            setTextColor(Theme.DANGER)
            setOnClickListener { confirmEnd() }
        })
        return endBar
    }

    /** FS-09：投屏中第一次返回只提示，10 秒内不再按就自动取消，第二次才真的结束。 */
    @Deprecated("Activity's back callback; keeps the app free of AndroidX")
    override fun onBackPressed() {
        if (!isCasting) {
            super.onBackPressed()
            return
        }
        if (endBar.visibility == View.VISIBLE) confirmEnd() else showEndConfirm()
    }

    private fun showEndConfirm() {
        endConfirmUntil = System.currentTimeMillis() + END_CONFIRM_MS
        endBar.visibility = View.VISIBLE
        endText.text = "结束投屏？"
    }

    private fun tickEndConfirm() {
        if (endBar.visibility != View.VISIBLE) return
        val leftMs = endConfirmUntil - System.currentTimeMillis()
        if (leftMs <= 0L) {
            cancelEndConfirm()
            return
        }
        endText.text = "结束投屏？${(leftMs + 999) / 1000} 秒后自动取消"
    }

    private fun cancelEndConfirm() {
        endConfirmUntil = 0L
        endBar.visibility = View.GONE
    }

    private fun confirmEnd() {
        cancelEndConfirm()
        session?.endActiveSession()
        statusText.text = "已结束投屏"
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun centeredBlock(side: Int) = LinearLayout.LayoutParams(side, side).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun receiverName(): String = store.deviceName(defaultReceiverName())

    private fun startSessions(name: String) {
        val active = session ?: ReceiverSession(
            context = this,
            pairing = pairing,
            onStatus = { text -> runOnUiThread { statusText.text = text } },
            onPrompt = { prompt -> runOnUiThread { showPrompt(prompt) } },
            onCasting = { casting, sender -> runOnUiThread { onCasting(casting, sender) } },
            onNotice = { text -> runOnUiThread { showNotice(text) } },
        ).also { it.start(name) }
        session = active
        val renderer = dlna ?: DlnaServer(this) { text -> runOnUiThread { statusText.text = text } }
            .apply { onPlayingChanged = { playing -> runOnUiThread { if (playing) showCasting("") } } }
            .also { dlna = it }
        renderer.start(name)
    }

    private fun defaultReceiverName(): String =
        "AirMirror-${Build.MODEL}".replace(Regex("[^A-Za-z0-9-]"), "-").take(30)

    private fun refreshWaitingPage() {
        val code = pairing.code
        val locked = code.state() == CastCode.State.LOCKED
        // 锁定期内展示的数字输了也不认，宁可用占位符也不给一个「看着能用」的码
        codeText.text = if (locked) LOCK_PLACEHOLDER else code.code()
        val seconds = code.secondsUntilRotation()
        countdownText.text = if (locked) "投屏码已临时锁定" else "${seconds} 秒后更换投屏码"
        ring.fraction = if (locked) 0f else seconds / 60f
        val host = localIpv4() ?: "127.0.0.1"
        val port = session?.port ?: 0
        val payload = if (port > 0 && !locked) pairing.qrPayload(host, port, deviceNameText.text.toString()) else ""
        if (payload.isNotEmpty() && payload != lastPayload) {
            lastPayload = payload
            qrView.setImageBitmap(Qr.bitmap(payload, QR_PIXELS))
        }
    }

    private fun showPrompt(prompt: ReceiverSession.Prompt) {
        promptText.text = "允许「${prompt.deviceName}」投屏？"
        promptBar.visibility = View.VISIBLE
    }

    private fun respondToPrompt(allowed: Boolean) {
        promptBar.visibility = View.GONE
        session?.resolve(allowed)
    }

    private fun onCasting(casting: Boolean, senderName: String) {
        isCasting = casting
        if (casting) showCasting(senderName) else {
            waiting.visibility = View.VISIBLE
            promptBar.visibility = View.GONE
            main.removeCallbacks(hideNotice)
            noticeOverlay.visibility = View.GONE
            cancelEndConfirm()
        }
    }

    /** 投屏中整屏只留画面，连接成功时把发送端设备名叠 3 秒。 */
    private fun showCasting(senderName: String) {
        waiting.visibility = View.GONE
        promptBar.visibility = View.GONE
        if (senderName.isEmpty()) return
        overlayName.text = senderName
        overlayName.visibility = View.VISIBLE
        main.removeCallbacks(hideOverlay)
        main.postDelayed(hideOverlay, OVERLAY_MS)
    }

    private val hideOverlay = Runnable { overlayName.visibility = View.GONE }

    override fun surfaceCreated(holder: SurfaceHolder) {
        session?.attachSurface(holder.surface)
        dlna?.attach(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // 只摘掉 Surface，会话继续收流；回到前台会重新挂载
        session?.detachSurface()
        dlna?.detach()
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        main.removeCallbacks(hideOverlay)
        main.removeCallbacks(hideNotice)
        session?.stop()
        session = null
        dlna?.stop()
        dlna = null
        super.onDestroy()
    }

    private companion object {
        const val TICK_MS = 500L
        const val OVERLAY_MS = 3_000L
        const val NOTICE_MS = 3_000L
        const val END_CONFIRM_MS = 10_000L
        const val QR_PIXELS = 420
        const val LOCK_PLACEHOLDER = "------"
    }
}
