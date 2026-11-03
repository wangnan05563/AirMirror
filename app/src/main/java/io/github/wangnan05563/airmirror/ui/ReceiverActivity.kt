package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import io.github.wangnan05563.airmirror.device.DisplayFit
import io.github.wangnan05563.airmirror.device.DisplayMode
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

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
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
    /** 断线保留窗口内的常驻提示：与 3 秒浮层不同，要一直显示到恢复或 30 秒收口（PRD FS-10）。 */
    private lateinit var recoveryBar: TextView
    private lateinit var cornerHint: TextView
    private lateinit var settingsPanel: LinearLayout
    private lateinit var settingsEntry: TextView
    /** 投屏中盖在画面上接长按：ScrollView 会吃掉落在画面上的触摸，根布局收不到长按。 */
    private lateinit var longPressCatcher: View
    private lateinit var promptBar: LinearLayout
    private lateinit var promptText: TextView
    private lateinit var endBar: LinearLayout
    private lateinit var endText: TextView
    private val modeRows = LinkedHashMap<DisplayMode, TextView>()
    private lateinit var rotationRow: TextView
    private lateinit var labelRow: TextView

    private var displayMode = DisplayMode.FIT
    private var followRotation = true
    private var portraitLabel = true
    /** 实际送入画面的方向：关闭旋转跟随后冻结在最后一次跟随到的方向 */
    private var shownWidth = 0
    private var shownHeight = 0
    /** 最近一帧的真实方向，重新打开旋转跟随时立即采纳 */
    private var latestWidth = 0
    private var latestHeight = 0
    private var lastPortrait = false
    private var modeHintShown = false

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
        displayMode = store.displayMode()
        followRotation = store.followRotation()
        portraitLabel = store.portraitLabel()
        root = FrameLayout(this).apply { setBackgroundColor(Theme.BACKGROUND) }

        surfaceView = SurfaceView(this)
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
        longPressCatcher = View(this).apply {
            visibility = View.GONE
            setOnLongClickListener {
                showSettings()
                true
            }
        }
        root.addView(longPressCatcher, fullScreen())
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
        // 断线恢复提示放屏幕正中：最后一帧仍在，用户要一眼看出是网络在恢复而不是卡死
        root.addView(buildRecoveryBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        root.addView(buildCornerHint(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            rightMargin = Theme.dp(this@ReceiverActivity, 24)
            bottomMargin = Theme.dp(this@ReceiverActivity, 32)
        })
        root.addView(buildSettingsEntry(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply {
            rightMargin = Theme.dp(this@ReceiverActivity, 20)
            topMargin = Theme.dp(this@ReceiverActivity, 20)
        })
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
        root.addView(buildSettingsPanel(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ).apply { setMargins(Theme.dp(this@ReceiverActivity, 32), 0, Theme.dp(this@ReceiverActivity, 32), 0) })
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refitVideo() }
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

    private fun buildRecoveryBar(): TextView {
        recoveryBar = TextView(this).apply {
            text = "连接中断，正在恢复"
            textSize = Theme.BODY_SP
            maxLines = 1
            setTextColor(Theme.TEXT_PRIMARY)
            setBackgroundColor(0xE60B1F3A.toInt())
            setPadding(
                Theme.dp(this@ReceiverActivity, 20), Theme.dp(this@ReceiverActivity, 12),
                Theme.dp(this@ReceiverActivity, 20), Theme.dp(this@ReceiverActivity, 12),
            )
            visibility = View.GONE
        }
        return recoveryBar
    }

    private fun showRecovery(active: Boolean) {
        Log.i(TAG, "recovery banner=$active")
        recoveryBar.visibility = if (active) View.VISIBLE else View.GONE
    }

    /** 角落提示：竖屏标签与「比例不同可切换模式」都只出现 3 秒（PRD FS-08 步骤 5）。 */
    private fun buildCornerHint(): TextView {
        cornerHint = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            setBackgroundColor(0xCC0B1F3A.toInt())
            setPadding(Theme.dp(this@ReceiverActivity, 16), Theme.dp(this@ReceiverActivity, 8), Theme.dp(this@ReceiverActivity, 16), Theme.dp(this@ReceiverActivity, 8))
            visibility = View.GONE
        }
        return cornerHint
    }

    private fun showCornerHint(text: String) {
        Log.i(TAG, "corner hint: $text")
        cornerHint.text = text
        cornerHint.visibility = View.VISIBLE
        main.removeCallbacks(hideCornerHint)
        main.postDelayed(hideCornerHint, NOTICE_MS)
    }

    private val hideCornerHint = Runnable { cornerHint.visibility = View.GONE }

    /** 待连接页右上角的显示设置入口；投屏中收起，长按画面仍可唤出。 */
    private fun buildSettingsEntry(): TextView {
        settingsEntry = TextView(this).apply {
            text = "显示设置"
            textSize = Theme.BODY_SP
            setTextColor(Theme.ACCENT)
            setBackgroundColor(0xCC0B1F3A.toInt())
            setPadding(
                Theme.dp(this@ReceiverActivity, 16), Theme.dp(this@ReceiverActivity, 10),
                Theme.dp(this@ReceiverActivity, 16), Theme.dp(this@ReceiverActivity, 10),
            )
            setOnClickListener { showSettings() }
        }
        return settingsEntry
    }

    /** 新编码配置到达：跟随方向并按模式重新计算画面区域（PRD FS-08 步骤 2）。 */
    private fun onVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        latestWidth = width
        latestHeight = height
        if (shownWidth <= 0 || shownHeight <= 0 || followRotation) {
            shownWidth = width
            shownHeight = height
        }
        Log.i(TAG, "video size ${width}x$height follow=$followRotation shown=${shownWidth}x$shownHeight")
        refitVideo()
        // 关闭旋转跟随后画面冻结在旧方向：新方向既不上屏，也不该提示「竖屏」
        val shownPortrait = shownHeight > shownWidth
        val adopted = shownWidth == width && shownHeight == height
        if (isCasting && adopted && shownPortrait && portraitLabel && !lastPortrait) showCornerHint("竖屏")
        if (isCasting && !shownPortrait && !modeHintShown && DisplayFit.mismatched(root.width, root.height, shownWidth, shownHeight)) {
            modeHintShown = true
            showCornerHint("比例与屏幕不同 · 长按可切换显示模式")
        }
        lastPortrait = shownPortrait
    }

    private fun refitVideo() {
        if (shownWidth <= 0 || shownHeight <= 0) return
        val viewport = DisplayFit.viewport(displayMode, root.width, root.height, shownWidth, shownHeight)
        if (viewport.width <= 0 || viewport.height <= 0) return
        Log.i(
            TAG,
            "fit ${displayMode.name} video=${shownWidth}x$shownHeight box=${root.width}x${root.height} -> ${viewport.width}x${viewport.height}",
        )
        val params = surfaceView.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.width == viewport.width && params.height == viewport.height) return
        params.width = viewport.width
        params.height = viewport.height
        params.gravity = Gravity.CENTER
        surfaceView.layoutParams = params
    }

    private fun buildSettingsPanel(): LinearLayout {
        settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B1F3A.toInt())
            setPadding(Theme.dp(this@ReceiverActivity, 24), Theme.dp(this@ReceiverActivity, 20), Theme.dp(this@ReceiverActivity, 24), Theme.dp(this@ReceiverActivity, 16))
            visibility = View.GONE
        }
        settingsPanel.addView(TextView(this).apply {
            text = "显示设置"
            textSize = Theme.TITLE_SP
            setTextColor(Theme.TEXT_PRIMARY)
            setPadding(0, 0, 0, Theme.dp(this@ReceiverActivity, 12))
        })
        for (mode in DisplayMode.entries) {
            val row = TextView(this).apply {
                textSize = Theme.BODY_SP
                setPadding(0, Theme.dp(this@ReceiverActivity, 10), 0, Theme.dp(this@ReceiverActivity, 10))
                setOnClickListener { chooseMode(mode) }
            }
            modeRows[mode] = row
            settingsPanel.addView(row, matchWidth())
        }
        settingsPanel.addView(View(this).apply { setBackgroundColor(0x3312314F.toInt()) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(this@ReceiverActivity, 1)))
        rotationRow = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            setPadding(0, Theme.dp(this@ReceiverActivity, 12), 0, Theme.dp(this@ReceiverActivity, 12))
            setOnClickListener {
                followRotation = !followRotation
                store.saveFollowRotation(followRotation)
                if (followRotation && latestWidth > 0) {
                    shownWidth = latestWidth
                    shownHeight = latestHeight
                    lastPortrait = shownHeight > shownWidth
                }
                Log.i(TAG, "follow rotation -> $followRotation shown=${shownWidth}x$shownHeight")
                refreshSettings()
                refitVideo()
            }
        }
        settingsPanel.addView(rotationRow, matchWidth())
        labelRow = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            setPadding(0, 0, 0, Theme.dp(this@ReceiverActivity, 12))
            setOnClickListener {
                portraitLabel = !portraitLabel
                store.savePortraitLabel(portraitLabel)
                refreshSettings()
            }
        }
        settingsPanel.addView(labelRow, matchWidth())
        settingsPanel.addView(Button(this).apply {
            text = "完成"
            setTextColor(Theme.ACCENT)
            setOnClickListener { hideSettings() }
        }, matchWidth())
        refreshSettings()
        return settingsPanel
    }

    private fun refreshSettings() {
        for ((mode, row) in modeRows) {
            row.text = "${if (mode == displayMode) "●" else "○"} ${mode.label}\n${mode.hint}"
            row.setTextColor(if (mode == displayMode) Theme.ACCENT else Theme.TEXT_SECONDARY)
        }
        rotationRow.text = "旋转跟随：${if (followRotation) "开" else "关"}"
        labelRow.text = "竖屏标签：${if (portraitLabel) "开" else "关"}"
    }

    private fun showSettings() {
        refreshSettings()
        settingsPanel.visibility = View.VISIBLE
    }

    private fun hideSettings() {
        settingsPanel.visibility = View.GONE
    }

    private fun chooseMode(mode: DisplayMode) {
        if (mode == displayMode) {
            hideSettings()
            return
        }
        if (mode.needsConfirm) {
            AlertDialog.Builder(this)
                .setTitle("使用拉伸？")
                .setMessage("拉伸会让画面变形，之后可以随时改回完整显示。")
                .setPositiveButton("确定") { _, _ -> applyMode(mode) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        applyMode(mode)
    }

    private fun applyMode(mode: DisplayMode) {
        displayMode = mode
        store.saveDisplayMode(mode)
        Log.i(TAG, "display mode -> ${mode.name}")
        refreshSettings()
        refitVideo()
        hideSettings()
    }

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
            onRecovery = { active -> runOnUiThread { showRecovery(active) } },
            onVideoSize = { width, height -> runOnUiThread { onVideoSize(width, height) } },
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
        val wasCasting = isCasting
        isCasting = casting
        settingsEntry.visibility = if (casting) View.GONE else View.VISIBLE
        longPressCatcher.visibility = if (casting) View.VISIBLE else View.GONE
        if (casting) {
            // CONFIG 每次换方向都会再报一次投屏中，设备名只在真正开始投屏时叠一次
            if (!wasCasting) showCasting(senderName)
            // 第一帧的 CONFIG 早于「投屏中」，方向提示在这里补一次
            if (lastPortrait && portraitLabel) {
                showCornerHint("竖屏")
            } else if (!modeHintShown && shownWidth > 0 && DisplayFit.mismatched(root.width, root.height, shownWidth, shownHeight)) {
                modeHintShown = true
                showCornerHint("比例与屏幕不同 · 长按可切换显示模式")
            }
        } else {
            waiting.visibility = View.VISIBLE
            promptBar.visibility = View.GONE
            settingsPanel.visibility = View.GONE
            main.removeCallbacks(hideNotice)
            main.removeCallbacks(hideCornerHint)
            noticeOverlay.visibility = View.GONE
            recoveryBar.visibility = View.GONE
            cornerHint.visibility = View.GONE
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
        main.removeCallbacks(hideCornerHint)
        session?.stop()
        session = null
        dlna?.stop()
        dlna = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AirMirrorUI"
        const val TICK_MS = 500L
        const val OVERLAY_MS = 3_000L
        const val NOTICE_MS = 3_000L
        const val END_CONFIRM_MS = 10_000L
        const val QR_PIXELS = 420
        const val LOCK_PLACEHOLDER = "------"
    }
}
