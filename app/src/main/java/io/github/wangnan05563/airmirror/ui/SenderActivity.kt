package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.wangnan05563.airmirror.crossnet.CandidateCollector
import io.github.wangnan05563.airmirror.crossnet.CrossNetConnector
import io.github.wangnan05563.airmirror.crossnet.CrossNetLinkRegistry
import io.github.wangnan05563.airmirror.crossnet.CrossNetProtocol
import io.github.wangnan05563.airmirror.crossnet.HttpSignalingTransport
import io.github.wangnan05563.airmirror.crossnet.SignalingClient
import io.github.wangnan05563.airmirror.crossnet.SignalingSettings
import io.github.wangnan05563.airmirror.dlna.localIpv4
import io.github.wangnan05563.airmirror.dlna.localIpv4s
import io.github.wangnan05563.airmirror.error.ErrorNotice
import io.github.wangnan05563.airmirror.net.DeviceRegistry
import io.github.wangnan05563.airmirror.net.DiscoveryLadder
import io.github.wangnan05563.airmirror.net.ProbeResponder
import io.github.wangnan05563.airmirror.net.ProbeScanner
import io.github.wangnan05563.airmirror.net.RecentTargets
import io.github.wangnan05563.airmirror.net.ServiceBrowser
import io.github.wangnan05563.airmirror.net.UdpProbeTransport
import io.github.wangnan05563.airmirror.pairing.DeviceStore
import io.github.wangnan05563.airmirror.pairing.LastCastTarget
import io.github.wangnan05563.airmirror.pairing.PairingLink
import io.github.wangnan05563.airmirror.protocol.ProtocolBoundary
import io.github.wangnan05563.airmirror.sender.CaptureService
import io.github.wangnan05563.airmirror.sender.RuntimePermission
import io.github.wangnan05563.airmirror.sender.SenderControl
import io.github.wangnan05563.airmirror.sender.SenderReport
import io.github.wangnan05563.airmirror.sender.SystemAudio
import io.github.wangnan05563.airmirror.update.ApkDownload
import io.github.wangnan05563.airmirror.update.ApkInstaller
import io.github.wangnan05563.airmirror.update.ApkProvider
import io.github.wangnan05563.airmirror.update.ApkSignatureState
import io.github.wangnan05563.airmirror.update.ReleaseInfo
import io.github.wangnan05563.airmirror.update.UpdateChecker
import io.github.wangnan05563.airmirror.update.UpdateResult
import io.github.wangnan05563.airmirror.update.UrlHttpText
import java.io.File
import kotlin.concurrent.thread

/**
 * 发送端选择页（PRD FR-02 + §11.3）：设备卡片只给设备名与协议角标，不展示 IP 等实现细节；
 * 列表为空要说明原因，并留出投屏码与手动地址两条后备路径。
 */
class SenderActivity : Activity() {

    /** 同一套页面在电视上要按 3 米观看距离放大字号（PRD §11.5），标题/正文按端取角色字号。 */
    private val isTv: Boolean by lazy { TvFocus.isTvMode(this) }

    private data class Target(val name: String, val host: String, val port: Int, val fingerprint: String)

    private lateinit var store: DeviceStore
    private lateinit var status: TextView
    /** 当前状态条的语义态：只影响取色，不参与任何状态判定（PRD UI-04 依赖约束）。 */
    private var statusState = CastStatus.State.IDLE
    private lateinit var hint: TextView
    private lateinit var permissionFix: Button
    private lateinit var list: LinearLayout
    private lateinit var codeInput: EditText
    private lateinit var entryPanel: LinearLayout
    private lateinit var scanRow: LinearLayout
    private lateinit var codeRow: LinearLayout
    private lateinit var entryNote: TextView
    private lateinit var scanButton: Button
    private lateinit var scanToCodeButton: Button
    private lateinit var codeToScan: TextView
    private var entryMode = TwoStepCastPolicy.EntryMode.CODE
    private var entryReason = ""
    private var cameraAvailable = false
    /** 用户手动改过入口：此后不再自动选中，避免“我正在输码它却连了另一台”。 */
    private var userChoseEntry = false
    /** 本轮页面是否已自动选过一次：自动连接只给一次机会，失败后回到列表由用户决定。 */
    private var autoAttempted = false
    private var autoReadyAt = 0L
    private val decisions = TwoStepCastPolicy.Budget()
    private var qrCaptureFile: File? = null
    /** 当前正在投屏/待投屏的设备；首帧到达时才把它写成“上次成功设备”。 */
    private var castingTarget: Target? = null
    private lateinit var addressInput: EditText
    private lateinit var pauseButton: Button
    private lateinit var stopButton: Button
    private lateinit var audioButton: Button
    private lateinit var takeoverBar: LinearLayout
    private lateinit var takeoverText: TextView
    private lateinit var takeoverDeny: Button
    private lateinit var takeoverYield: Button
    /** 投屏中的目标设备名：停止按钮的可访问名要带上它（UI-03 §6）。 */
    private var castingTargetName = ""
    private var browser: ServiceBrowser? = null
    private var pending: Target? = null
    private var pendingCredential: String = ""
    private var pendingTarget: Pair<Target, String>? = null
    /** 跨网链路在进程内的交接 token；局域网路径恒为 null。 */
    private var pendingCrossNetToken: String? = null
    private var pendingAudio = false
    private lateinit var crossNetRow: TextView
    private lateinit var updateRow: TextView
    /** 检查/下载进行中：避免连点叠出多条并发下载 */
    private var updateBusy = false
    private var lastCasting = false
    /** 当前展开的设备卡片；同一时刻只展开一张（PRD §11.3）。 */
    private var expandedFingerprint: String? = null
    private lateinit var controlBar: LinearLayout
    private lateinit var listScroller: ScrollView
    private var scanner: ProbeScanner? = null
    /** UX-01 空列表自救卡：T06104 起由 [ErrorStateCard] 渲染三套插画、稳定错误码与固定三动作。 */
    private lateinit var diagnosisCard: ErrorStateCard
    private lateinit var diagnosisHeadline: TextView
    private lateinit var diagnosisNextStep: TextView
    /** T06104 首次连接加载卡：10 秒前只显示进度，10 秒整切“仍在连接”并露出取消。 */
    private lateinit var loadingCard: LoadingStateCard
    private var loadingName = ""
    private var loadingStartedAt = 0L
    /** 录屏授权已通过、CaptureService 已启动：取消加载时要发 ACTION_STOP 收尾。 */
    private var captureStarted = false
    /** 连接代次：取消或发起新连接后，旧的跨网打洞结果不得再弹授权/起服务。 */
    private var connectGeneration = 0

    private val devices = DeviceRegistry(now = { System.currentTimeMillis() }, sameSubnet = ::sameSubnet)
    private val main = Handler(Looper.getMainLooper())
    private var lastReport = ""

    /** 刚点过「去设置」：下次回到前台时状态行要按系统当前授予态重写，而不是继续用旧文案 */
    private var awaitingSettings = false

    /** 投屏中被系统收回的权限，已经提示过的不再重复提示（重新授予后清掉） */
    private val revokedNoted = mutableSetOf<String>()

    /** 本进程里见过授予的权限：只有「先授予后丢失」才算被收回，从未授予过的不算 */
    private val everGranted = mutableSetOf<String>()

    /** MediaProjection 是 API 21：只在 [startCapture] 的版本守卫之后取，4.4 上不会触碰这个类 */
    @android.annotation.TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun projectionManager(): MediaProjectionManager =
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    /** 采集在后台服务里跑，界面按拍轮询它的结论。 */
    private val refresh = Runnable { refreshStatus() }

    /** 10 秒到点只切一次文案，不做每秒重绘；后续显隐仍由连接结果驱动。 */
    private val loadingTimeout = Runnable {
        if (!::loadingCard.isInitialized || loadingCard.visibility != View.VISIBLE) return@Runnable
        loadingCard.bind(loadingName, SystemClock.uptimeMillis() - loadingStartedAt)
    }

    /** 发现稳定窗到点后重算一次列表：第二台电视若在窗口内出现，自动选中会降级为列表。 */
    private val autoSettle = Runnable { renderList() }

    private fun refreshStatus() {
        updateStatusLine()
        // 有画质说明即表示会话在跑；暂停只停送帧，按钮仍留在原位
        val casting = SenderReport.quality.isNotEmpty()
        updateCastingChrome(casting)
        if (casting) updateCastingControls()
        refreshTakeover(casting)
        main.postDelayed(refresh, POLL_MS)
    }

    /** 状态行：异常文案优先，没有异常时展示协商后的画质，两者都不在就保持当前提示。 */
    private fun updateStatusLine() {
        val report = SenderReport.last
        val note = if (report.isEmpty()) SenderReport.quality else report
        if (note == lastReport) return
        // 有报错或首帧画质说明，首次连接就不再是“加载中”；跨网/局域网同一口径。
        if (report.isNotEmpty() || SenderReport.quality.isNotEmpty()) endLoading()
        lastReport = note
        // UX-02：码类拒绝是发送端唯一可观测的「换码」时刻，按输入态给可重新确认的提示，
        // 输入框保留不清空（这里从不 setText("")）；非码类错误照旧走原提示。
        val rotation = codeRotationNotice(report)
        if (rotation != null && entryMode == TwoStepCastPolicy.EntryMode.SCAN) {
            // 扫码令牌/投屏码被接收端拒绝：这是 PRD 规定的回退时刻，直接露出投屏码，不再让用户猜。
            switchToCode("投屏码或扫码令牌已失效，请输入电视上的新码", charge = false)
        }
        status.text = rotation ?: note.ifEmpty { hint.text.ifEmpty { SEARCHING } }
        statusState = statusStateOf(report)
        status.setTextColor(StatusColors.textOf(statusState))
        // 出错时把输入放开让用户重试；正常投屏中保持禁用，避免重复发起
        codeInput.isEnabled = report.isNotEmpty()
    }

    private fun statusStateOf(report: String): CastStatus.State = when {
        report.isNotEmpty() -> CastStatus.State.ERROR
        SenderReport.quality.isNotEmpty() ->
            if (SenderControl.paused) CastStatus.State.PAUSED else CastStatus.State.CASTING
        else -> CastStatus.State.IDLE
    }

    /** 投屏态切换时调整可见性：投屏中收起设备列表，退出后恢复。 */
    private fun updateCastingChrome(casting: Boolean) {
        if (casting == lastCasting) return
        lastCasting = casting
        controlBar.visibility = if (casting) View.VISIBLE else View.GONE
        // PRD §11.3「投屏中列表收起」
        listScroller.visibility = if (casting) View.GONE else View.VISIBLE
        if (casting) {
            expandedFingerprint = null
            endLoading()
            rememberCastSuccess()
        } else {
            captureStarted = false
            castingTarget = null
            decisions.reset()
        }
        refreshEntryVisibility()
    }

    /** 投屏中的按钮文案与无障碍描述：暂停、停止、声音三键始终反映当前状态。 */
    private fun updateCastingControls() {
        // UX-03：暂停是发送端本地状态（只停送帧），状态条跟着切换；错误文案优先级更高
        if (SenderReport.last.isEmpty()) {
            val pausedLine = CastStatus.line(CastStatus.State.PAUSED)
            if (SenderControl.paused) {
                if (status.text != pausedLine) showStatus(CastStatus.State.PAUSED, pausedLine)
            } else if (status.text == pausedLine) {
                showStatus(CastStatus.State.CASTING, SenderReport.quality.ifEmpty { CastStatus.castingLine() })
            }
        }
        pauseButton.text = DeviceCardPolicy.pauseLabel(SenderControl.paused)
        audioButton.text = audioLabel()
        A11y.describe(pauseButton, A11yCopy.pause(SenderControl.paused))
        A11y.describe(stopButton, A11yCopy.stop(castingTargetName))
        A11y.describe(audioButton, A11yCopy.audio(SenderControl.audio))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DeviceStore(this)
        cameraAvailable = QrPhotoScan.canCapture(this)
        entryMode = TwoStepCastPolicy.defaultMode(cameraAvailable)
        if (!cameraAvailable) entryReason = "这台设备没有可用相机，已切换到投屏码"
        autoReadyAt = SystemClock.uptimeMillis() + TwoStepCastPolicy.AUTO_SETTLE_MS
        // 同机双端时接收端的探测公告会回环到自己：本机指纹交给列表过滤掉
        devices.selfFingerprint = store.deviceId()
        val pad = Theme.dp(this, 20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BACKGROUND)
            setPadding(pad, Theme.dp(this@SenderActivity, Theme.SPACE_XXL_DP), pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "投到其他设备"
            textSize = FocusPolicy.titleSp(isTv)
            setTextColor(Theme.TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
        })
        // PRD §04 / §13.1：能做什么先说清楚，Miracast 不在承诺内
        root.addView(TextView(this).apply {
            text = "支持镜像到同网 AirMirror 设备；不支持 Miracast"
            textSize = FocusPolicy.bodySp(isTv)
            setTextColor(Theme.TEXT_SECONDARY)
            setPadding(0, Theme.dp(this@SenderActivity, Theme.SPACE_XS_DP), 0, 0)
        })
        status = TextView(this).apply {
            text = SEARCHING
            textSize = FocusPolicy.bodySp(isTv)
            setTextColor(StatusColors.textOf(CastStatus.State.IDLE))
            setPadding(0, Theme.dp(this@SenderActivity, 6), 0, Theme.dp(this@SenderActivity, 14))
            A11y.liveStatus(this)
        }
        root.addView(status)
        // PRD 7.4「权限缺失：跳转设置」：真到了只能去设置的地步（系统不再询问），
        // 说明框里已经问不出结果，这里留一个持久入口，位置就在拒绝后的状态行下面
        permissionFix = UiKit.button(this, kind = UiKit.Kind.Ghost).apply {
            text = "去设置打开权限"
            visibility = View.GONE
            setOnClickListener { openPermissionSettings() }
        }
        root.addView(permissionFix, matchWidth())
        root.addView(buildTakeoverBar(), matchWidth())

        codeInput = makeInput("投屏码或 PIN", InputType.TYPE_CLASS_NUMBER)
        codeInput.filters = arrayOf(InputFilter.LengthFilter(CODE_LENGTH))
        // T06105：默认扫码；没有相机/用户在扫码后失败时切到投屏码。两块共用同一个输入框，
        // 入口优先级由 TwoStepCastPolicy 管，页面只负责显隐与接线。
        buildEntryPanel()
        root.addView(entryPanel, matchWidth())
        // PRD FS-04：跨网入口与局域网并列，但只在「局域网没搜到设备」时才会尝试，
        // 填了端点才启用；不填就不向任何外部服务发请求。
        crossNetRow = TextView(this).apply {
            textSize = FocusPolicy.bodySp(isTv)
            setTextColor(Theme.TEXT_SECONDARY)
            setPadding(Theme.dp(this@SenderActivity, Theme.SPACE_XS_DP), 0, 0, 0)
            setOnClickListener { editCrossNet() }
            UiKit.touchTarget(this)
        }
        root.addView(crossNetRow, matchWidth())
        // PRD 通用需求「菜单新增在线更新」：内置仓库地址，不开放手填；只查 GitHub Release（Q-6）
        updateRow = TextView(this).apply {
            text = "检查更新"
            textSize = FocusPolicy.bodySp(isTv)
            setTextColor(Theme.TEXT_SECONDARY)
            setPadding(Theme.dp(this@SenderActivity, Theme.SPACE_XS_DP), 0, 0, 0)
            setOnClickListener { checkUpdate() }
            UiKit.touchTarget(this)
            A11y.describe(this, A11yCopy.checkUpdate())
        }
        root.addView(updateRow, matchWidth())

        hint = TextView(this).apply {
            textSize = FocusPolicy.bodySp(isTv)
            setTextColor(Theme.TEXT_SECONDARY)
            setPadding(Theme.dp(this@SenderActivity, Theme.SPACE_XS_DP), 0, 0, Theme.dp(this@SenderActivity, Theme.SPACE_SM_DP))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        diagnosisCard = buildDiagnosisCard()
        loadingCard = buildLoadingCard()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint, matchWidth())
            addView(loadingCard, matchWidth())
            addView(diagnosisCard, matchWidth())
            addView(list, matchWidth())
        }
        listScroller = ScrollView(this).apply { addView(column) }
        root.addView(
            listScroller,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        addressInput = makeInput("IP 地址或配对链接", InputType.TYPE_CLASS_TEXT)
        root.addView(rowWith(addressInput, "直接连接") { connectByAddress() }, matchWidth())
        root.addView(TextView(this).apply {
            text = "局域网直连 · 无广告 · 不经服务器"
            textSize = FocusPolicy.bodySp(isTv)
            setTextColor(StatusColors.offlineText())
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@SenderActivity, 10), 0, 0)
        }, matchWidth())
        // PRD §11.3：投屏中列表收起，底部固定一条「暂停 / 停止 / 声音」控制条
        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            UiKit.surface(this, color = Theme.component.cardElevated, elevationDp = 2)
            val inset = Theme.dp(this@SenderActivity, Theme.SPACE_SM_DP)
            setPadding(inset, inset, inset, inset)
        }
        pauseButton = UiKit.button(this).apply {
            text = DeviceCardPolicy.pauseLabel(false)
            setTextColor(Theme.ACCENT)
            setOnClickListener { SenderControl.paused = !SenderControl.paused }
            A11y.describe(this, A11yCopy.pause(SenderControl.paused))
        }
        controlBar.addView(pauseButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        stopButton = UiKit.button(this).apply {
            text = "停止"
            setTextColor(Theme.DANGER)
            setOnClickListener {
                // 复用已有的停止通道：发 ACTION_STOP 让前台服务收尾，不直接杀进程
                startService(Intent(this@SenderActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                expandedFingerprint = null
            }
            A11y.describe(this, A11yCopy.stop(castingTargetName))
        }
        controlBar.addView(
            stopButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(this@SenderActivity, Theme.SPACE_SM_DP)
            },
        )
        audioButton = UiKit.button(this).apply {
            text = audioLabel()
            setTextColor(Theme.ACCENT)
            setOnClickListener {
                if (SenderControl.audio) setMirrorAudio(false) else enableMirrorAudio()
            }
            A11y.describe(this, A11yCopy.audio(SenderControl.audio))
        }
        controlBar.addView(
            audioButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(this@SenderActivity, Theme.SPACE_SM_DP)
            },
        )
        root.addView(controlBar, matchWidth())
        setContentView(root)

        // PRD §04「权限申请在真正用到时才弹出，并说明用途」：这里不弹任何权限框，
        // 通知权限在发起投屏时申请、录音权限在要出声时申请（见 requestCapture / enableMirrorAudio）。
        SenderControl.audio = store.mirrorAudioEnabled()
        refreshCrossNetRow()
        startBrowsing()
        startScanning()
        main.post(refresh)
        main.postDelayed(autoSettle, TwoStepCastPolicy.AUTO_SETTLE_MS)
        refreshEntryVisibility()
        // 冷启动静默查一次：只有发现新版本才弹框，已最新/检查失败都不打扰（手动入口仍走 checkUpdate 默认形态）
        checkUpdate(silent = true)

        // 联调入口：adb 直接拉起时带 host/port/凭据，跳过发现
        val directHost = intent.getStringExtra(EXTRA_TARGET_HOST)
        val directPort = intent.getIntExtra(EXTRA_TARGET_PORT, 0)
        if (!directHost.isNullOrEmpty() && directPort > 0) {
            beginLoading("直连设备")
            requestCapture(Target("direct", directHost, directPort, directHost), intent.getStringExtra(EXTRA_TARGET_CREDENTIAL).orEmpty())
        }
    }

    /** FS-09 步骤 4：第二台设备申请接管时，由当前发送端决定让出还是拒绝。 */
    private fun buildTakeoverBar(): View {
        takeoverBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            UiKit.surface(this, color = Theme.component.cardElevated, elevationDp = 1)
            visibility = View.GONE
            val gap = Theme.dp(this@SenderActivity, Theme.SPACE_SM_DP)
            setPadding(gap, gap, gap, gap)
        }
        takeoverText = TextView(this).apply {
            textSize = FocusPolicy.bodySp(isTv)
            setTextColor(Theme.TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        takeoverBar.addView(takeoverText)
        takeoverDeny = UiKit.button(this).apply {
            text = "拒绝"
            setTextColor(Theme.DANGER)
            setOnClickListener { SenderControl.answerTakeover(false) }
        }
        takeoverBar.addView(takeoverDeny)
        takeoverYield = UiKit.button(this, kind = UiKit.Kind.Primary).apply {
            text = "让出"
            setOnClickListener { SenderControl.answerTakeover(true) }
        }
        takeoverBar.addView(
            takeoverYield,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = Theme.dp(this@SenderActivity, Theme.SPACE_SM_DP) },
        )
        return takeoverBar
    }

    /** 60 秒内不表态由接收端按超时拒绝；界面到点自动收起，避免留一个失效的按钮。 */
    private fun refreshTakeover(casting: Boolean) {
        val from = SenderControl.takeoverFrom
        if (!casting || from.isEmpty()) {
            takeoverBar.visibility = View.GONE
            if (!casting) SenderControl.clearTakeover()
            return
        }
        val leftMs = SenderControl.takeoverDeadline - System.currentTimeMillis()
        if (leftMs <= 0L) {
            SenderControl.clearTakeover()
            takeoverBar.visibility = View.GONE
            return
        }
        takeoverBar.visibility = View.VISIBLE
        val seconds = ((leftMs + 999) / 1000).toInt()
        takeoverText.text = "$from 请求接管投屏 · $seconds 秒后拒绝"
        A11y.describe(takeoverDeny, A11yCopy.takeover(from, seconds, yield = false))
        A11y.describe(takeoverYield, A11yCopy.takeover(from, seconds, yield = true))
        A11y.announce(takeoverBar, "「$from」请求接管投屏，请在 $seconds 秒内选择", "takeover-$from")
    }

    private fun makeInput(label: String, inputType: Int) = EditText(this).apply {
        hint = label
        this.inputType = inputType
        setTextSize(TypedValue.COMPLEX_UNIT_SP, FocusPolicy.bodySp(isTv))
        setTextColor(Theme.TEXT_PRIMARY)
        setHintTextColor(StatusColors.offlineText())
        UiKit.inset(this, color = Theme.component.cardElevated)
        minHeight = Theme.dp(this@SenderActivity, UiKit.TOUCH_TARGET_DP)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(Theme.dp(this@SenderActivity, Theme.SPACE_MD_DP), Theme.dp(this@SenderActivity, 6), Theme.dp(this@SenderActivity, Theme.SPACE_MD_DP), Theme.dp(this@SenderActivity, 6))
        A11y.describe(this, if (label.startsWith("投屏码")) A11yCopy.codeInputLabel() else A11yCopy.addressInputLabel())
    }

    /** T06105 入口面板：扫码默认，投屏码作为扫描失败/无相机时的回退。 */
    private fun buildEntryPanel() {
        scanButton = UiKit.button(this, kind = UiKit.Kind.Primary).apply {
            text = "扫码连接"
            setOnClickListener { startQrScan() }
            A11y.describe(this, A11yCopy.scanStart())
        }
        scanToCodeButton = UiKit.button(this).apply {
            text = "改用投屏码"
            setOnClickListener { switchToCode("", charge = true) }
            A11y.describe(this, A11yCopy.scanToCode())
        }
        scanRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(scanButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                scanToCodeButton,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = Theme.dp(this@SenderActivity, Theme.SPACE_SM_DP)
                },
            )
        }
        codeRow = rowWith(codeInput, "用码连接") { connectByCode() }
        entryNote = TextView(this).apply {
            textSize = FocusPolicy.bodySp(isTv)
            setTextColor(Theme.TEXT_SECONDARY)
            setPadding(Theme.dp(this@SenderActivity, Theme.SPACE_XS_DP), Theme.dp(this@SenderActivity, Theme.SPACE_XS_DP), 0, 0)
        }
        codeToScan = TextView(this).apply {
            text = "返回扫码"
            textSize = FocusPolicy.bodySp(isTv)
            setTextColor(Theme.ACCENT)
            setPadding(Theme.dp(this@SenderActivity, Theme.SPACE_XS_DP), Theme.dp(this@SenderActivity, Theme.SPACE_XS_DP), 0, 0)
            UiKit.touchTarget(this)
            setOnClickListener { switchToScan(charge = true) }
            A11y.describe(this, A11yCopy.codeToScan())
        }
        entryPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scanRow, matchWidth())
            addView(codeRow, matchWidth())
            addView(entryNote, matchWidth())
            addView(codeToScan, matchWidth())
        }
    }

    private fun applyEntryMode(focusCode: Boolean) {
        refreshEntryVisibility()
        if (focusCode && entryMode == TwoStepCastPolicy.EntryMode.CODE) {
            main.postDelayed({ if (!isFinishing) focusInput(codeInput) }, Theme.DURATION_FOCUS_MS)
        }
    }

    private fun refreshEntryVisibility() {
        if (!::entryPanel.isInitialized || !::scanRow.isInitialized || !::codeRow.isInitialized) return
        val busy = lastCasting || (::loadingCard.isInitialized && loadingCard.visibility == View.VISIBLE)
        val rows = devices.rows()
        val codeMode = entryMode == TwoStepCastPolicy.EntryMode.CODE
        val scanVisible = !busy && TwoStepCastPolicy.scanEntryVisible(rows, entryMode)
        scanRow.visibility = if (scanVisible) View.VISIBLE else View.GONE
        codeRow.visibility = if (!busy && codeMode) View.VISIBLE else View.GONE
        codeToScan.visibility = if (!busy && codeMode && cameraAvailable) View.VISIBLE else View.GONE
        entryPanel.visibility = if (scanVisible || (!busy && codeMode)) View.VISIBLE else View.GONE
        entryNote.text = when {
            codeMode && entryReason.isNotEmpty() -> entryReason
            codeMode -> "输入电视上的 4 位 PIN 或 6 位投屏码"
            entryReason.isNotEmpty() -> entryReason
            else -> "电视上的二维码；相机不可用可直接用投屏码"
        }
        entryNote.setTextColor(if (entryReason.isNotEmpty()) Theme.ACCENT else Theme.TEXT_SECONDARY)
    }

    /**
     * 切投屏码：扫码失败/无相机的自动回退不扣决策；用户主动点「改用投屏码」算一次选择。
     * 返回 false 只在预算已用完且这次是主动选择时出现，界面不再偷偷替用户做第三次决定。
     */
    private fun switchToCode(reason: String, charge: Boolean): Boolean {
        if (entryMode == TwoStepCastPolicy.EntryMode.CODE && reason.isEmpty()) return true
        if (charge && entryMode != TwoStepCastPolicy.EntryMode.CODE && !decisions.take()) {
            toast("本次投屏已用 2 步，请直接选择已发现的电视，或重试扫码")
            return false
        }
        entryMode = TwoStepCastPolicy.EntryMode.CODE
        if (reason.isNotEmpty()) entryReason = reason
        userChoseEntry = true
        applyEntryMode(focusCode = true)
        return true
    }

    private fun switchToScan(charge: Boolean) {
        if (charge && !decisions.take()) {
            toast("本次投屏已用 2 步，请直接选择已发现的电视")
            return
        }
        entryMode = TwoStepCastPolicy.EntryMode.SCAN
        entryReason = ""
        userChoseEntry = true
        applyEntryMode(focusCode = false)
    }

    private fun startQrScan() {
        if (!cameraAvailable) {
            switchToCode("这台设备没有可用相机，已切换到投屏码", charge = false)
            return
        }
        if (!decisions.take()) {
            toast("本次投屏已用 2 步，请直接选择已发现的电视")
            return
        }
        val file = runCatching { QrPhotoScan.newCaptureFile(this) }.getOrNull()
        val intent = file?.let { QrPhotoScan.startIntent(this, it) }
        if (file == null || intent == null || intent.resolveActivity(packageManager) == null) {
            file?.delete()
            switchToCode("没有可用的系统相机，已切换到投屏码", charge = false)
            return
        }
        qrCaptureFile = file
        userChoseEntry = true
        try {
            startActivityForResult(intent, REQUEST_QR)
        } catch (_: ActivityNotFoundException) {
            file.delete()
            qrCaptureFile = null
            switchToCode("没有可用的系统相机，已切换到投屏码", charge = false)
        }
    }

    /** 扫码返回文本 → 本地校验 → 直接把一次性令牌交给既有连接链路；失败只切投屏码，不弹堆栈。 */
    private fun handleQrCaptureResult(resultCode: Int) {
        val file = qrCaptureFile
        qrCaptureFile = null
        if (resultCode != RESULT_OK) {
            file?.delete()
            // 取消不是失败：不切投屏码、不额外扣一次决策，用户可重拍
            decisions.reset()
            toast("已取消扫码")
            return
        }
        val text = file?.let { QrPhotoScan.decodeFile(it) }
        file?.delete()
        if (text.isNullOrBlank()) {
            switchToCode("没识别到投屏二维码，已切换到投屏码", charge = false)
            toast(A11yCopy.scanResultFailed())
            return
        }
        val link = PairingLink.parse(text)
        if (link == null || link.host.isBlank() || link.port !in 1..65_535) {
            switchToCode("这不是 AirMirror 投屏二维码，已切换到投屏码", charge = false)
            toast("二维码不完整，请用电视待连接页上的二维码")
            return
        }
        val target = Target(
            link.name.ifBlank { "电视" },
            link.host,
            link.port,
            link.deviceId,
        )
        connectTo(target, link.token, "正在连接 ${target.name}…")
    }


    private fun rowWith(input: EditText, action: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(UiKit.button(this@SenderActivity, kind = UiKit.Kind.Primary).apply {
            text = action
            A11y.describe(this, if (action == "用码连接") A11yCopy.codeConnect() else A11yCopy.addressConnect())
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            // 输入框与按钮之间留 8dp，贴在一起时点错率明显变高
            leftMargin = Theme.dp(this@SenderActivity, Theme.SPACE_SM_DP)
        })
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun startBrowsing() {
        // 每次开搜都重取本机地址：Wi-Fi 切换后旧地址不该继续挡掉真邻居
        val selfAddresses = localIpv4s()
        browser = ServiceBrowser(
            this,
            onFound = { service ->
                // NSD 回调跑在系统线程，而 DeviceRegistry 与扫描阶梯都不是线程安全的：
                // 整段回主线程，不能只把 renderList 包起来——否则 report 会在网络线程里
                // 改 LinkedHashMap，和主线程的 rows()/markConnected() 撞出丢条目或并发异常。
                runOnUiThread {
                    scanner?.noteMulticastHit()
                    // mDNS 的 TXT 记录在部分系统上拿不到，服务名只是展示名；解析出的地址落在本机
                    // 那就是同机双端回环，直接跳过——这一条不依赖 TXT 是否可用
                    if (service.host !in selfAddresses) {
                        // 用指纹而不是服务名入表：同一台接收端还会从 UDP 探测那条路报一次，
                        // 两条路用同一个身份才能合并成一条，也才能被 selfFingerprint 挡掉
                        devices.report(service.fingerprint, service.name, PROTOCOL, "${service.host}:${service.port}")
                        renderList()
                    }
                }
            },
            onLost = { fingerprint ->
                runOnUiThread {
                    devices.markLost(fingerprint)
                    renderList()
                }
            },
        ).also { it.start() }
    }

    /**
     * FS-07「组播被禁用 → 受控广播/直连探测」发送侧：NSD 之外再并行两路 UDP 探测。
     * 命中即静默降级（不提示），三轮全空才把 AP 隔离/双频分离的可执行原因写进列表提示。
     */
    private fun startScanning() {
        val sink = object : ProbeScanner.Sink {
            override fun onDevice(fingerprint: String, name: String, host: String, port: Int, protocols: List<String>) {
                // DeviceRegistry 不是线程安全的，一律回主线程再改列表
                runOnUiThread {
                    val address = "$host:$port"
                    for (protocol in protocols.ifEmpty { listOf(PROTOCOL) }) {
                        devices.report(fingerprint, name, protocol, address)
                    }
                }
            }

            override fun onLost(fingerprint: String) {
                runOnUiThread { devices.markLost(fingerprint) }
            }

            override fun onNotice(text: String) {
                // 空态诊断改由结构化判决驱动（renderList 读 scanner.verdict()），不再存整句中文
            }

            override fun onChanged() {
                runOnUiThread { renderList() }
            }
        }
        scanner = ProbeScanner(
            transport = UdpProbeTransport(),
            sink = sink,
            recent = RecentTargets.decode(store.recentTargets()),
            interfaces = { ProbeResponder.localInterfaces() },
            senderName = "AirMirror",
        ).also { it.start() }
    }

    private fun renderList() {
        list.removeAllViews()
        val rows = devices.rows()
        for (row in rows) {
            val target = targetOf(row.fingerprint, row.name) ?: continue
            list.addView(deviceCard(row, target), matchWidth())
        }
        renderEmptyState()
        refreshEntryVisibility()
        maybeAutoConnect(rows)
    }

    /** T06105：唯一在线且上次成功投屏的设备才自动选中；没有稳定/不是唯一/用户已动手都不抢。 */
    private fun maybeAutoConnect(rows: List<DeviceRegistry.Row>) {
        val trusted = store.lastCastTarget()
        val nowWall = System.currentTimeMillis()
        val target = TwoStepCastPolicy.autoTarget(
            TwoStepCastPolicy.Input(
                rows = rows.map {
                    TwoStepCastPolicy.Candidate(
                        fingerprint = it.fingerprint,
                        name = it.name,
                        online = it.online,
                        supported = ProtocolBoundary.bestSupported(it.protocols) != null,
                    )
                },
                trusted = trusted?.let { TwoStepCastPolicy.Trusted(it.fingerprint, it.usedAt) },
                now = nowWall,
                discoverySettled = SystemClock.uptimeMillis() >= autoReadyAt,
                userChoseEntry = userChoseEntry,
                autoAttempted = autoAttempted,
                connecting = ::loadingCard.isInitialized && loadingCard.visibility == View.VISIBLE,
                casting = lastCasting || SenderReport.quality.isNotEmpty(),
            ),
        ) ?: return
        if (autoAttempted) return
        autoAttempted = true
        val real = targetOf(target.fingerprint, target.name) ?: return
        connectTo(real, credential = "", statusLine = "上次设备在线，正在自动连接 ${real.name}…")
    }

    /** 首帧真正出现才记“上次成功设备”；失败尝试不写，避免下次自动连到一台从没出过画面的电视。 */
    private fun rememberCastSuccess() {
        val target = castingTarget ?: return
        if (target.fingerprint.isBlank() || target.fingerprint == "direct" || target.fingerprint == "crossnet") return
        store.saveLastCastTarget(
            LastCastTarget.Record(
                fingerprint = target.fingerprint,
                name = target.name,
                usedAt = System.currentTimeMillis(),
            ),
        )
        devices.markConnected(target.fingerprint)
        renderList()
    }

    /** 一张设备卡：标题行 + 离线/最近使用说明 + 展开后的两个动作。 */
    private fun deviceCard(row: DeviceRegistry.Row, target: Target): View {
        // 一台设备可能同时广播多种协议：有能用的就按能用的展示，全都用不了才标不支持
        val usable = ProtocolBoundary.bestSupported(row.protocols)
        val card = DeviceCardPolicy.Card(
            fingerprint = row.fingerprint,
            online = row.online,
            supported = usable != null,
            lastConnectedAt = row.lastConnectedAt,
        )
        val actions = DeviceCardPolicy.actions(card)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (row.online) {
                UiKit.surface(this, color = Theme.component.cardElevated, elevationDp = 2)
            } else {
                setBackgroundColor(Theme.BACKGROUND)
            }
            val gap = Theme.dp(this@SenderActivity, Theme.SPACE_MD_DP)
            setPadding(gap, gap, gap, gap)
            addView(deviceHeader(row, card, usable, actions), matchWidth())
            addSubtitle(this, card)
            if (row.fingerprint == expandedFingerprint && actions.isNotEmpty()) {
                addView(
                    actionRow(row, target, actions, gap),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        Theme.dp(this@SenderActivity, DeviceCardPolicy.ACTION_ROW_DP) + gap,
                    ),
                )
            }
        }
    }

    /** 标题行：设备名 + 协议角标；点击按「先展开动作、再选动作」处理（PRD §11.3）。 */
    private fun deviceHeader(
        row: DeviceRegistry.Row,
        card: DeviceCardPolicy.Card,
        usable: String?,
        actions: List<DeviceCardPolicy.CardAction>,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Theme.dp(this@SenderActivity, DeviceCardPolicy.TOUCH_TARGET_DP)
        A11y.describe(
            this,
            A11yCopy.card(row.name, row.online, usable != null, row.fingerprint == expandedFingerprint, lastConnectedLabel(row)),
        )
        addView(TextView(this@SenderActivity).apply {
            text = row.name
            textSize = Theme.DEVICE_NAME_SP
            setTextColor(if (row.online) Theme.TEXT_PRIMARY else StatusColors.offlineText())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            A11y.decorative(this)
        })
        addView(DeviceCardTag.chip(this@SenderActivity, DeviceCardPolicy.tag(card, usable?.let { ProtocolBoundary.badgeFor(it) }.orEmpty())).apply {
            A11y.decorative(this)
        })
        // PRD §11.3：点击先展开两个动作，不再「点一下就先连上再选」
        setOnClickListener {
            if (actions.isEmpty()) {
                toast(if (row.online) ProtocolBoundary.describe(row.strongest).text() else DeviceCardPolicy.offlineHint())
            } else {
                if (expandedFingerprint != row.fingerprint && !decisions.take()) {
                    toast("本次投屏已用 2 步，请直接点「镜像屏幕」")
                    return@setOnClickListener
                }
                userChoseEntry = true
                expandedFingerprint = DeviceCardPolicy.toggle(expandedFingerprint, row.fingerprint)
                renderList()
            }
        }
    }

    /** PRD UI-05：离线说清「上次连接 + 可能原因」，在线且近期连过给「最近使用」，其余不占行。 */
    private fun addSubtitle(container: LinearLayout, card: DeviceCardPolicy.Card) {
        val note = DeviceCardPolicy.subtitle(card, System.currentTimeMillis()) ?: return
        container.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Theme.dp(this@SenderActivity, 6), 0, 0)
                if (DeviceCardPolicy.needsOfflineMarker(card)) {
                    addView(DeviceCardTag.offlineDot(this@SenderActivity))
                }
                addView(TextView(this@SenderActivity).apply {
                    text = note
                    textSize = FocusPolicy.bodySp(isTv)
                    setTextColor(StatusColors.offlineText())
                })
            },
            matchWidth(),
        )
    }

    private fun actionRow(
        row: DeviceRegistry.Row,
        target: Target,
        actions: List<DeviceCardPolicy.CardAction>,
        gap: Int,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, gap, 0, 0)
        for ((index, action) in actions.withIndex()) {
            addView(
                actionButton(row, target, action),
                LinearLayout.LayoutParams(
                    0,
                    Theme.dp(this@SenderActivity, DeviceCardPolicy.ACTION_ROW_DP),
                    1f,
                ).apply {
                    if (index > 0) leftMargin = Theme.dp(this@SenderActivity, Theme.SPACE_SM_DP)
                },
            )
        }
    }

    private fun actionButton(row: DeviceRegistry.Row, target: Target, action: DeviceCardPolicy.CardAction): Button =
        UiKit.button(this).apply {
            text = when (action) {
                DeviceCardPolicy.CardAction.MIRROR -> "镜像屏幕"
                DeviceCardPolicy.CardAction.PUSH_VIDEO -> "推送视频"
            }
            setTextColor(Theme.ACCENT)
            A11y.describe(this, A11yCopy.cardAction(row.name, action == DeviceCardPolicy.CardAction.MIRROR))
            setOnClickListener {
                if (!decisions.take()) {
                    toast("本次投屏已用 2 步，请重新扫码或输入投屏码")
                    return@setOnClickListener
                }
                userChoseEntry = true
                expandedFingerprint = null
                renderList()
                when (action) {
                    DeviceCardPolicy.CardAction.MIRROR -> connectTo(target)
                    // 本片不伪装修好推送链路：按 7.3 给可执行替代路径
                    DeviceCardPolicy.CardAction.PUSH_VIDEO -> toast(DeviceCardPolicy.pushVideoFallback())
                }
            }
        }

    /** 最近一次连接距今多久：没有记录就说「还没连过」，有记录才提示间隔。 */
    private fun lastConnectedLabel(row: DeviceRegistry.Row): String {
        val connectedAt = row.lastConnectedAt
        if (connectedAt == null || connectedAt <= 0L) return "还没连过"
        val minutes = ((System.currentTimeMillis() - connectedAt) / 60_000L).coerceAtLeast(0L)
        return if (minutes < 1L) "刚刚连过" else "上次连接 $minutes 分钟前"
    }

    /** 空列表自救卡：能给出成因就换成诊断卡；搜索中且 0 台也先给无设备入口，不再只留一行字。 */
    private fun renderEmptyState() {
        if (::loadingCard.isInitialized && loadingCard.visibility == View.VISIBLE) {
            diagnosisCard.visibility = View.GONE
            hint.visibility = View.GONE
            return
        }
        val registry = devices.emptyReason()
        val verdict = scanner?.verdict()
            ?: DiscoveryLadder.Verdict.Nothing(DiscoveryLadder.Empty.SEARCHING, "")
        val cause = EmptyDiagnosis.of(verdict, registry)
        if (cause == null) {
            val searchingEmpty = verdict is DiscoveryLadder.Verdict.Nothing &&
                verdict.reason == DiscoveryLadder.Empty.SEARCHING &&
                registry == DeviceRegistry.EmptyReason.NONE_FOUND &&
                list.childCount == 0
            if (searchingEmpty) {
                // T06104：搜索窗口未结束但一台都没出现，显式给无设备空态；三动作保持 EmptyDiagnosis 固定顺序。
                diagnosisCard.bind(ErrorNotice.Cause.NO_DISCOVERY, EmptyDiagnosis.ACTIONS)
                diagnosisCard.visibility = View.VISIBLE
                hint.text = ""
                hint.visibility = View.GONE
            } else {
                diagnosisCard.visibility = View.GONE
                hint.text = if (registry == DeviceRegistry.EmptyReason.HAS_ONLINE) "点设备名开始投屏" else ""
                hint.visibility = if (hint.text.isEmpty()) View.GONE else View.VISIBLE
            }
            return
        }
        // 三套插画、稳定错误码、原因/下一步、主次兜底三动作都由 ErrorStateCard 统一渲染。
        diagnosisCard.bind(cause, EmptyDiagnosis.ACTIONS)
        diagnosisCard.visibility = View.VISIBLE
        hint.text = ""
        hint.visibility = View.GONE
    }

    /**
     * UX-01 空列表自救卡（设计 §4）：T06104 起交给 [ErrorStateCard]，
     * 保持固定三动作、就地生效、48dp 触控高度；页面只负责把动作接到输入框/搜索器。
     */
    private fun buildDiagnosisCard(): ErrorStateCard = ErrorStateCard(this).apply {
        visibility = View.GONE
        onAction = ::onDiagnosisAction
        // CompactPagesRenderTest 仍按旧字段名取文案；这里保留只读别名，避免测试和页面各改一套。
        diagnosisHeadline = headline
        diagnosisNextStep = nextStep
    }

    /** T06104 首次连接加载卡：骨架/进度/设备名/10 秒后取消，组件内部只回调宿主。 */
    private fun buildLoadingCard(): LoadingStateCard = LoadingStateCard(this).apply {
        visibility = View.GONE
        onCancel = ::cancelLoading
    }

    /** T06104：首次连接期间只展示加载卡，不叠诊断卡；失败/取消后由 [endLoading] 恢复空态。 */
    private fun beginLoading(deviceName: String) {
        loadingName = deviceName
        loadingStartedAt = SystemClock.uptimeMillis()
        loadingCard.bind(deviceName, 0)
        loadingCard.visibility = View.VISIBLE
        diagnosisCard.visibility = View.GONE
        hint.visibility = View.GONE
        if (::entryPanel.isInitialized) entryPanel.visibility = View.GONE
        main.removeCallbacks(loadingTimeout)
        main.postDelayed(loadingTimeout, ErrorVisualSpec.CANCEL_AFTER_MS)
    }

    private fun endLoading() {
        if (!::loadingCard.isInitialized || loadingCard.visibility != View.VISIBLE) return
        loadingCard.visibility = View.GONE
        main.removeCallbacks(loadingTimeout)
        // 失败回落到设备列表：空列表要把诊断卡还原，避免错误后只剩一条状态行。
        if (list.childCount == 0 && !lastCasting) renderEmptyState()
        refreshEntryVisibility()
    }

    /** 加载中取消：清掉待交接目标/跨网 token，已起服务则按既有 ACTION_STOP 收尾。 */
    private fun cancelLoading() {
        val serviceStarted = captureStarted
        connectGeneration++
        resetPendingConnection()
        endLoading()
        codeInput.isEnabled = true
        decisions.reset()
        if (serviceStarted) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        }
        showStatus(CastStatus.State.IDLE, "已取消连接")
        renderList()
    }

    private fun resetPendingConnection() {
        pending = null
        pendingTarget = null
        pendingCrossNetToken?.let { CrossNetLinkRegistry.discard(it) }
        pendingCrossNetToken = null
        captureStarted = false
    }

    /** PRD UI-04：状态文案与六态语义色一起落，避免同一条状态行在不同入口换出不同颜色。 */
    private fun showStatus(state: CastStatus.State, line: CharSequence?) {
        if (state == CastStatus.State.ERROR) {
            // 一次失败就是一次新回合：保留错误入口，但不让上一轮的 2 步预算把恢复路径卡死。
            decisions.reset()
        }
        if (state == CastStatus.State.ERROR || state == CastStatus.State.IDLE) endLoading()
        statusState = state
        status.text = line
        status.setTextColor(StatusColors.textOf(state))
    }

    /** UX-01 三动作就地生效（设计 §3.3）：不跳页、不清空已输入内容、不自动提交。 */
    private fun onDiagnosisAction(action: ErrorNotice.Action) {
        when (action) {
            ErrorNotice.Action.RETRY_DISCOVERY -> {
                // 重起一轮搜索窗口，保留最近设备缓存；不改轮次/通道/探测间隔
                scanner?.begin()
                decisions.reset()
                autoAttempted = false
                autoReadyAt = SystemClock.uptimeMillis() + TwoStepCastPolicy.AUTO_SETTLE_MS
                main.removeCallbacks(autoSettle)
                main.postDelayed(autoSettle, TwoStepCastPolicy.AUTO_SETTLE_MS)
                showStatus(CastStatus.State.IDLE, SEARCHING)
                renderList()
            }

            ErrorNotice.Action.ENTER_CODE -> {
                if (!switchToCode("", charge = true)) return
                focusInput(codeInput)
            }

            ErrorNotice.Action.ENTER_IP -> focusInput(addressInput)
            else -> Unit
        }
    }

    private fun focusInput(input: EditText) {
        input.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun targetOf(fingerprint: String, name: String): Target? {
        val address = devices.addressOf(fingerprint) ?: return null
        val port = address.substringAfterLast(':').toIntOrNull() ?: return null
        if (port !in 1..65_535) return null
        return Target(name, address.substringBefore(':'), port, fingerprint)
    }

    /** 投屏码路径：一台能用 AirMirror 的设备直接连，多台时先点选，避免投错屏。 */
    /**
     * UX-02：码类拒绝（不正确/已过期/已用过/锁定）是发送端唯一可观测的「换码」时刻。
     * 反查回 Cause 再交给 CodeInputPolicy 按输入态判决；非码类错误返回 null，不接管原提示。
     * 输入框始终保留（判决 preserveInput 恒真，界面从不 setText("")），不弹模态框。
     */
    private fun codeRotationNotice(report: String): String? {
        val cause = ErrorNotice.causeOfRendered(report) ?: return null
        val locked = cause == ErrorNotice.Cause.CODE_LOCKED
        val isCode = locked || cause == ErrorNotice.Cause.CODE_WRONG ||
            cause == ErrorNotice.Cause.CODE_EXPIRED || cause == ErrorNotice.Cause.CODE_USED
        if (!isCode) return null
        return CodeInputPolicy.onCodeRotated(codeInput.text.length, locked).notice
    }

    private fun connectByCode() {
        userChoseEntry = true
        val code = codeInput.text.toString().trim()
        if ((code.length != CODE_LENGTH && code.length != PIN_LENGTH) || !code.all(Char::isDigit)) {
            toast("请输入电视上的 4 位 PIN 或 6 位投屏码")
            return
        }
        val online = devices.rows().filter { it.online }
        val usable = online.filter { ProtocolBoundary.bestSupported(it.protocols) != null }
        when {
            online.isEmpty() -> {
                // PRD FS-04：同网搜不到设备不等于不能投——跨网信令正是为这种场景兜底。
                // 未配置信令时保持原来的可执行提示，不偷偷替用户开启任何外部服务。
                if (SignalingSettings.enabled(store.signalingUrl())) {
                    connectCrossNet(code)
                } else {
                    toast("没搜到设备，在下面填电视地址")
                }
            }
            usable.isEmpty() -> toast(
                ProtocolBoundary.describe(online.first().strongest).text(),
            )
            usable.size == 1 -> {
                val row = usable.first()
                val target = targetOf(row.fingerprint, row.name)
                if (target == null) toast("设备地址还没解析出来，稍后再试") else connectTo(target, code)
            }
            else -> toast("发现多台设备，先点选要投的那台")
        }
    }

    /**
     * 跨网：先用信令配对并打洞，成功后把隧道交给前台采集服务。
     * 这一步必须在后台线程做（信令轮询与 UDP 打洞都是阻塞 IO）。
     */
    private fun connectCrossNet(code: String) {
        val generation = ++connectGeneration
        castingTarget = null
        beginLoading("跨网设备")
        showStatus(CastStatus.State.CONNECTING, "正在跨网连接…")
        codeInput.isEnabled = false
        thread(name = "airmirror-crossnet-out", isDaemon = true) {
            val outcome = runCatching {
                val client = SignalingClient(HttpSignalingTransport(store.signalingUrl()))
                CrossNetConnector(
                    role = CrossNetProtocol.Role.SENDER,
                    client = client,
                    collect = { socket ->
                        CandidateCollector.collect(
                            socket,
                            CandidateCollector.Config(stunHost = store.stunHost()),
                        )
                    },
                ).connectSender(code)
            }.getOrElse { error ->
                Log.w(TAG, "cross-net connect failed: ${error.message}")
                CrossNetConnector.Outcome(
                    state = io.github.wangnan05563.airmirror.crossnet.CrossNetState.FAILED,
                    prompt = "跨网连接失败，请检查网络后重试",
                    failure = io.github.wangnan05563.airmirror.crossnet.FailureReason.NO_INTERNET,
                )
            }
            main.post {
                // 用户已取消或已发起新一轮连接：这轮打洞结果作废，先关隧道再退出。
                if (generation != connectGeneration) {
                    outcome.link?.let { runCatching { it.close() } }
                    return@post
                }
                if (isFinishing) {
                    outcome.link?.let { runCatching { it.close() } }
                    return@post
                }
                if (outcome.connected && outcome.link != null) {
                    val token = CrossNetLinkRegistry.put(outcome)
                    pendingCrossNetToken = token
                    requestCapture(Target("跨网设备", "", 0, "crossnet"), code, token)
                } else {
                    codeInput.isEnabled = true
                    showStatus(CastStatus.State.ERROR, outcome.prompt.ifEmpty { "跨网连接失败" })
                    toast(outcome.prompt.ifEmpty { "跨网连接失败" })
                }
            }
        }
    }

    private fun connectByAddress() {
        val text = addressInput.text.toString().trim()
        if (text.isEmpty()) {
            toast("填电视的 IP，或粘贴待连接页的配对链接")
            return
        }
        val link = PairingLink.parse(text)
        val host = link?.host ?: text.substringBefore(':')
        val port = link?.port ?: text.substringAfter(':', "").toIntOrNull() ?: DEFAULT_PORT // NOSONAR(kotlin:S6619) toIntOrNull() 返回 Int?，此 elvis 必需
        val code = codeInput.text.toString().trim()
        if (host.isEmpty() || port !in 1..65_535) {
            toast("地址不完整")
            return
        }
        val credential = when {
            code.length == CODE_LENGTH || code.length == PIN_LENGTH -> code
            else -> link?.token.orEmpty()
        }
        connectTo(Target(link?.name.orEmpty().ifEmpty { host }, host, port, host), credential)
    }

    private fun connectTo(
        target: Target,
        credential: String = codeInput.text.toString().trim(),
        statusLine: String? = null,
    ) {
        connectGeneration++
        castingTarget = target
        beginLoading(target.name)
        showStatus(
            CastStatus.State.CONNECTING,
            statusLine ?: CastStatus.line(CastStatus.State.CONNECTING),
        )
        lastReport = ""
        codeInput.isEnabled = false
        // 记进 10 分钟缓存：下次组播/广播全被挡住时，这个地址就是直连探测的唯一入口。
        // 直接连接时这台设备可能还没被任何一轮发现建出条目，先登记再记时间；
        // 顺序反了的话会话结束后卡片会写「离线 · 还没连过」，与刚发生的事实相反。
        devices.report(target.fingerprint, target.name, PROTOCOL, "${target.host}:${target.port}")
        devices.markConnected(target.fingerprint)
        scanner?.recordConnected(target.fingerprint, target.name, target.host, target.port)
        scanner?.let { store.saveRecentTargets(it.snapshot()) }
        requestCapture(target, credential)
    }

    private fun requestCapture(target: Target, credential: String, crossNetToken: String? = null) {
        pendingCrossNetToken = crossNetToken
        // 发起投屏才是「真正用到」前台服务通知与声音采集的时刻；缺什么先说明用途再一起申请一次
        val missing = RuntimePermission.needs(
            wantsAudio = SenderControl.audio && SystemAudio.capable(),
            wantsCastingNotification = Build.VERSION.SDK_INT >= 33,
            granted = grantedKeys(),
        )
        if (missing.isEmpty()) {
            startCapture(target, credential)
            return
        }
        askPermission(
            missing = missing,
            title = "投屏前要这些权限",
            onAsk = { askable ->
                pendingTarget = target to credential
                requestPermissionNow(askable)
            },
            onDecline = {
                // 明确不用这两个权限：画面照投，声音按缺权限的口径关掉
                if (missing.contains(RuntimePermission.MIRROR_AUDIO)) setMirrorAudio(false)
                noteDeclined(missing)
                startCapture(target, credential)
            },
        )
    }

    private fun startCapture(target: Target, credential: String) {
        // MediaProjection 从 API 21 才有：4.4 手机点了也只会崩，明确告知改用推送
        if (Build.VERSION.SDK_INT < 21) {
            toast("当前系统版本过低，无法镜像；请改用 DLNA 推送视频")
            return
        }
        pending = target
        pendingCredential = credential
        castingTargetName = target.name
        startActivityForResult(projectionManager().createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    /** 「声音：开」是采集本机播放的声音，用到这一刻才申请；关声音永远不需要权限 */
    private fun enableMirrorAudio() {
        if (!SystemAudio.capable()) {
            toast("当前系统未开放声音采集")
            return
        }
        if (grantedKeys().contains(RuntimePermission.MIRROR_AUDIO.key)) {
            setMirrorAudio(true)
            return
        }
        askPermission(
            missing = listOf(RuntimePermission.MIRROR_AUDIO),
            title = "镜像带声音需要这个权限",
            onAsk = { askable ->
                pendingAudio = true
                requestPermissionNow(askable)
            },
            onDecline = { toast(RuntimePermission.MIRROR_AUDIO.declined) },
        )
    }

    private fun setMirrorAudio(enabled: Boolean) {
        SenderControl.audio = enabled
        store.saveMirrorAudioEnabled(enabled)
        audioButton.text = audioLabel()
    }

    private fun grantedKeys(): Set<String> {
        val keys = RuntimePermission.entries
            .filter { packageManager.checkPermission(it.key, packageName) == PackageManager.PERMISSION_GRANTED }
            .mapTo(mutableSetOf()) { it.key }
        // 顺手记账：判断「投屏中被收回」需要知道它曾经真被授予过
        everGranted.addAll(keys)
        return keys
    }

    /**
     * 某项权限是不是已被系统关死。判据全在纯函数里（可单测），这里只负责把三个输入凑齐：
     * 授予态、系统还给不给理由、我们到底问过没有。
     */
    private fun isSystemBlocked(permission: RuntimePermission, granted: Set<String> = grantedKeys()): Boolean =
        RuntimePermission.isBlocked(
            granted = permission.key in granted,
            // shouldShowRequestPermissionRationale 是 API 23：旧系统没有「不再询问」概念
            rationale = Build.VERSION.SDK_INT >= 23 && shouldShowRequestPermissionRationale(permission.key),
            asked = store.permissionAsked(permission.key),
        )

    /**
     * 缺的权限分两拨：还能问的问，已经关死的只能给设置入口。
     * 混在一起处理会出现「继续申请」按钮点了却再也不弹系统框的假动作。
     */
    private fun askPermission(
        missing: List<RuntimePermission>,
        title: String,
        onAsk: (List<RuntimePermission>) -> Unit,
        onDecline: () -> Unit,
    ) {
        val granted = grantedKeys()
        val blocked = missing.filter { isSystemBlocked(it, granted) }
        val askable = missing.filter { !isSystemBlocked(it, granted) }
        Log.i(
            TAG,
            "permission ask: " + askable.joinToString(",") { it.key } +
                "; blocked: " + blocked.joinToString(",") { it.key },
        )
        if (askable.isEmpty()) {
            // 系统已经不再询问：再摆出「继续申请」就是骗人，这里只说明用途与后果并给设置入口
            UiKit.dialog(this)
                .setTitle("权限已在系统里关掉")
                .setMessage(RuntimePermission.blockedMessage(blocked))
                .setPositiveButton("去设置") { _, _ -> openPermissionSettings() }
                .setNegativeButton("先不用") { _, _ -> onDecline() }
                .setOnCancelListener { onDecline() }
                .show()
            return
        }
        val body = RuntimePermission.explain(askable) +
            if (blocked.isEmpty()) "" else "\n\n" + RuntimePermission.blockedMessage(blocked)
        UiKit.dialog(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton("继续申请") { _, _ -> onAsk(askable) }
            .setNegativeButton("先不用") { _, _ -> onDecline() }
            // 「去设置」在说明框里也常驻：有些拒绝只给一次机会，用户想一步到位时不必先点「继续申请」再被拒
            .setNeutralButton("去设置") { _, _ -> openPermissionSettings() }
            .setOnCancelListener { onDecline() }
            .show()
    }

    /** 真正向系统发起申请前先落盘「问过了」，这是把「从未问过」与「不再询问」分开的唯一依据 */
    private fun requestPermissionNow(askable: List<RuntimePermission>) {
        // 运行时权限从 API 23 才存在；更早的系统权限在安装时授予，
        // 调用 requestPermissions 会 NoSuchMethodError，直接按已授予继续。
        if (Build.VERSION.SDK_INT < 23) return
        for (p in askable) store.savePermissionAsked(p.key)
        requestPermissions(askable.map { it.key }.toTypedArray(), REQUEST_PERMISSION)
    }

    /**
     * 拒绝之后界面要说清「少了什么、还能用什么」，不能留一个看起来是坏的开关。
     * 若这次拒绝让系统转成「不再询问」，文案换成只能去设置的那句，并把持久入口亮出来。
     */
    private fun noteDeclined(missing: List<RuntimePermission>) {
        Log.i(TAG, "permission declined: " + missing.joinToString(",") { it.key })
        val blocked = missing.filter { isSystemBlocked(it) }
        val line = if (blocked.isEmpty()) missing.joinToString("；") { it.declined } else RuntimePermission.blockedMessage(blocked)
        // 状态行会被随后的录屏授权流程覆盖，原文留一份在日志里，取证与客服都能对得上用户看到的字
        Log.i(TAG, "permission note: " + line.replace("\n", " / "))
        showStatus(CastStatus.State.ERROR, line)
        toast(if (blocked.isEmpty()) line else "权限已在系统里关掉，请点「去设置打开权限」")
        refreshFixEntry()
    }

    /** 只在真的没救时露出「去设置打开权限」，能问的时候露出来反而把用户往设置里赶 */
    private fun refreshFixEntry(granted: Set<String> = grantedKeys()) {
        val blocked = RuntimePermission.entries.filter { isSystemBlocked(it, granted) }
        permissionFix.visibility = if (blocked.isEmpty()) View.GONE else View.VISIBLE
        if (blocked.isNotEmpty()) A11y.describe(permissionFix, A11yCopy.permissionFix(blocked.map { it.label }))
    }

    /**
     * PRD 7.4「跳转设置」：落点是本应用的应用信息页（带包名），系统的权限区就在这一页里。
     * 只用公开的 ACTION_APPLICATION_DETAILS_SETTINGS：各版本都在，OEM 定制的「权限总页」不是公开契约，
     * 强依赖它只会在个别机型上变成点不动的按钮。
     */
    private fun openPermissionSettings() {
        awaitingSettings = true
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            Log.i(TAG, "permission settings opened")
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "permission settings unavailable")
            awaitingSettings = false
            toast("这台设备没有可用的应用设置页")
        }
    }

    /**
     * 回到前台一律以系统当前授予态为准（用户可能刚在设置里改过）：
     * 1) 状态行重写、入口显隐；
     * 2) 投屏中被收回的权限按 PRD 异常表口径降级。
     *
     * 边界（2026-11-06 真机实测）：标准 Android 上撤销运行期权限会把整个进程杀掉
     * （模拟器 API 34 上 `pm revoke RECORD_AUDIO` 后 pid 从 5807 变成 7378），
     * 这种情况下本方法没有机会执行，会话必然中断。它真正覆盖的是「权限在同一进程内消失、
     * 进程没被杀」的路径（AppOps 静默拒绝、部分 OEM ROM 的权限开关）。
     */
    override fun onResume() {
        super.onResume()
        if (!::store.isInitialized) return
        val granted = grantedKeys()
        refreshFixEntry(granted)
        if (awaitingSettings) {
            awaitingSettings = false
            val missing = RuntimePermission.entries.filter { it.key !in granted }
            Log.i(TAG, "permission settings back: missing=" + missing.joinToString(",") { it.key })
            // 投屏中的状态行归画质文案，这里只在没投屏时占位，避免刚写就被轮询覆盖成假象
            if (!lastCasting) showStatus(CastStatus.State.ERROR, RuntimePermission.settingsReturnNote(missing))
            toast(if (missing.isEmpty()) "权限已全部打开" else "还有权限没打开：" + missing.joinToString("、") { it.label })
        }
        reconcileRevoked(granted)
    }

    /**
     * 投屏到一半权限消失时的降级（PRD 异常表：权限缺失 → 画面继续，不中断会话）：
     * 声音权限没了就自动回「关」，画面照投；通知权限没了只是通知栏不再有停止入口，停人用本页按钮。
     * 每次变化只提示一次，重新授予后清掉记录，下次再被收回还能提。
     *
     * 前提是进程还活着。真正的「用户在设置里撤销权限」在标准 Android 上会先杀进程
     * （见 [onResume] 的实测说明），那种情况走到这里的概率极低；这里主要兜 AppOps 静默拒绝等不杀进程的路径。
     */
    private fun reconcileRevoked(granted: Set<String>) {
        if (!lastCasting) return
        // 先收账：重新授予的权限清掉提示记录，下次再被收回还能再提
        for (key in granted) revokedNoted.remove(key)
        for (permission in RuntimePermission.entries) {
            if (permission.key in granted) continue
            // 从未授予过的不叫「被收回」：那是用户一开始就没给，拒绝文案已经说过了
            if (permission.key !in everGranted) continue
            if (!revokedNoted.add(permission.key)) continue
            when (permission) {
                RuntimePermission.MIRROR_AUDIO -> {
                    setMirrorAudio(false)
                    val line = "声音权限已被系统收回，画面继续；要恢复声音请到设置打开权限后再开"
                    Log.i(TAG, "permission revoked mid-cast: " + permission.key)
                    showStatus(CastStatus.State.ERROR, line)
                    toast(line)
                    refreshFixEntry(granted)
                }

                RuntimePermission.CASTING_NOTIFICATION -> {
                    Log.i(TAG, "permission revoked mid-cast: " + permission.key)
                    val line = "通知权限已被收回：通知栏不再有停止入口，请在本页点「停止」"
                    showStatus(CastStatus.State.ERROR, line)
                    toast(line)
                }
            }
        }
    }

    @Deprecated("Activity result API kept dependency-free")
    override fun onRequestPermissionsResult( // NOSONAR(kotlin:S1133) 权限回调必须覆写（项目无 AndroidX），不能删除
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSION) return
        val granted = permissions.filterIndexed { i, _ -> grantResults[i] == PackageManager.PERMISSION_GRANTED }
            .toSet()
        // 只报这一刻真被拒的那些：没问过的权限不能算进拒绝名单
        val missing = RuntimePermission.entries.filter { it.key in permissions && it.key !in granted }
        if (missing.isNotEmpty()) noteDeclined(missing)
        // 批了的那几项也可能改变「是否需要设置入口」的判断，收尾一律按当前授予态重算一次
        refreshFixEntry()
        val audioAsked = permissions.contains(RuntimePermission.MIRROR_AUDIO.key)
        val audioGranted = granted.contains(RuntimePermission.MIRROR_AUDIO.key)
        // 声音开关必须跟着权限走：批了才开（从开关发起时）、拒了就关（从投屏发起时也一样），
        // 否则按钮会显示一个做不到的状态
        if (audioAsked && !audioGranted) setMirrorAudio(false)
        if (audioAsked && audioGranted && pendingAudio) setMirrorAudio(true)
        pendingAudio = false
        val next = pendingTarget
        pendingTarget = null
        if (next != null) startCapture(next.first, next.second)
    }

    @Deprecated("Activity result API kept dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { // NOSONAR(kotlin:S1133) 授权结果回调必须覆写（项目无 AndroidX），不能删除
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_QR) {
            handleQrCaptureResult(resultCode)
            return
        }
        if (requestCode != REQUEST_CAPTURE) return
        val target = pending ?: return
        if (resultCode != RESULT_OK || data == null) {
            codeInput.isEnabled = true
            // 没拿到录屏授权就没有会话，跨网隧道必须在此时释放，否则线程和 socket 一直挂着
            resetPendingConnection()
            castingTarget = null
            decisions.reset()
            showStatus(CastStatus.State.ERROR, if (lastReport.isEmpty()) hint.text else lastReport)
            // 用统一口径而不是另写一句：接收端与服务侧同样的拒绝都走 ErrorNotice，
            // 两套说法会让用户在「还能怎么投」上得到不同答案（PRD 7.3 自解释口径）
            toast(ErrorNotice.textFor(ErrorNotice.Cause.PERMISSION_CAPTURE))
            return
        }
        val token = pendingCrossNetToken
        val intent = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            putExtra(CaptureService.EXTRA_DEVICE_ID, store.deviceId())
            putExtra(CaptureService.EXTRA_CREDENTIAL, pendingCredential)
            if (token != null) {
                // 跨网没有可解析的 host/port：只带进程内 token，服务端按 token 取已打通的隧道
                putExtra(CaptureService.EXTRA_CROSS_NET_TOKEN, token)
                putExtra(CaptureService.EXTRA_SERVICE_NAME, "跨网设备")
            } else {
                putExtra(CaptureService.EXTRA_HOST, target.host)
                putExtra(CaptureService.EXTRA_PORT, target.port)
                // 断线后按服务名重新解析：IP 变了才能找到同一台设备（PRD FS-10）
                putExtra(CaptureService.EXTRA_SERVICE_NAME, target.name)
            }
        }
        pendingCrossNetToken = null
        captureStarted = true
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        showStatus(CastStatus.State.CASTING, CastStatus.line(CastStatus.State.CASTING, if (token != null) "跨网设备" else target.name))
    }

    /** 跨网端点与应用内说明；与接收端共用同一份落盘设置，手机上填一次即可。 */
    private fun refreshCrossNetRow() {
        val url = store.signalingUrl()
        val on = SignalingSettings.enabled(url)
        crossNetRow.text = if (on) "跨网信令：已配置（$url）" else "跨网信令：未配置（点此设置，仅跨网时使用）"
        crossNetRow.setTextColor(if (on) Theme.ACCENT else Theme.TEXT_SECONDARY)
        A11y.describe(crossNetRow, A11yCopy.crossNetSender(on, url))
    }

    private fun editCrossNet() {
        val input = EditText(this).apply {
            setText(store.signalingUrl())
            hint = "https://你的信令服务"
            setSingleLine()
            setTextColor(Theme.TEXT_PRIMARY)
        }
        UiKit.dialog(this)
            .setTitle("跨网信令服务")
            .setMessage("留空则不启用跨网。信令只交换建连信息，画面仍点对点直连、不经服务器。")
            .setView(input)
            .setPositiveButton("保存") { _, _ -> saveSignaling(input.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 留空即关闭跨网：保存后立刻刷新该行并提示当前状态。 */
    private fun saveSignaling(url: String) {
        store.saveSignalingUrl(url)
        refreshCrossNetRow()
        val on = SignalingSettings.enabled(store.signalingUrl())
        toast(if (on) "跨网已启用；同网搜不到设备时会自动尝试" else "跨网已关闭")
    }

    /** 检查更新：远端是 GitHub Release，本端按 ABI 优先挑独立包，其次 universal。
     *  silent=true 供启动自检：不改状态行、不弹「已最新/失败」提示，只有发现新版本才弹框。 */
    private fun checkUpdate(silent: Boolean = false) {
        if (updateBusy) return
        updateBusy = true
        if (!silent) updateRow.text = "检查更新：正在检查…"
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        val versionCode = installedVersionCode()
        val abi = deviceAbi()
        thread(name = "airmirror-update-check") {
            val result = UpdateChecker(
                UrlHttpText(),
                version,
                preferAbi = abi,
                currentVersionCode = versionCode,
            ).check()
            main.post {
                if (isFinishing) return@post
                updateBusy = false
                if (!silent) updateRow.text = "检查更新"
                applyUpdateResult(result, silent, version)
            }
        }
    }

    /** 本机 versionCode：在线更新按它判断线上包是否真的能覆盖安装（API 28 起用 long 版本）。 */
    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Long = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }.getOrDefault(0L)

    /** 更新检查结果落到界面：静默自检只在发现新版本时才弹框，其余提示一律不吵用户。 */
    private fun applyUpdateResult(result: UpdateResult, silent: Boolean, version: String) {
        when (result) {
            is UpdateResult.UpToDate -> if (!silent) toast("已是最新版本（${version.ifEmpty { "未知" }}）")
            is UpdateResult.Failed -> if (!silent) toast(result.reason)
            is UpdateResult.Available -> promptUpdate(result.release)
        }
    }

    /** 本机 ABI 主标识：minSdk 19 上 SUPPORTED_ABIS 不可用，退回单值 CPU_ABI（已 deprecated 但仍可用）。 */
    @Suppress("DEPRECATION")
    private fun deviceAbi(): String {
        val abis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS
        } else {
            arrayOf(Build.CPU_ABI)
        }
        return when {
            abis.any { it.equals("arm64-v8a", ignoreCase = true) } -> "arm64-v8a"
            abis.any { it.equals("armeabi-v7a", ignoreCase = true) } -> "armeabi-v7a"
            else -> ""
        }
    }

    private fun promptUpdate(release: ReleaseInfo) {
        val notes = release.notes.take(400).ifBlank { "该版本没有填写更新说明。" }
        val dialog = UiKit.dialog(this)
            .setTitle("发现新版本 ${release.tag}")
            .setMessage(notes)
            .setPositiveButton("下载并安装") { _, _ -> downloadUpdate(release) }
            .setNegativeButton("稍后", null)
            .create()
        dialog.setOnShowListener {
            dialog.findViewById<TextView>(android.R.id.message)?.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                Theme.PHONE_BODY_SP,
            )
            UiKit.styleDialogButtons(dialog)
        }
        dialog.show()
    }

    private fun downloadUpdate(release: ReleaseInfo) {
        if (updateBusy) return
        updateBusy = true
        updateRow.text = "正在下载更新…"
        thread(name = "airmirror-update-download") {
            val target = File(ApkProvider.dir(this), ApkDownload.safeName(release.apkName))
            val file = runCatching {
                ApkDownload.download(release.apkUrl, target) { done, total -> reportProgress(done, total) }
            }.getOrNull()
            main.post {
                updateBusy = false
                updateRow.text = "检查更新"
                if (isFinishing) return@post
                if (file == null) {
                    toast("下载失败，请检查网络后重试")
                    return@post
                }
                installUpdate(file)
            }
        }
    }

    /** 下载进度：总量未知时干脆不报百分比，免得显示一个假的 100%。 */
    private fun reportProgress(done: Long, total: Long) {
        if (total <= 0) return
        val percent = (done * 100 / total).toInt()
        main.post { if (!isFinishing) updateRow.text = "正在下载更新… $percent%" }
    }

    /** Android 8+ 必须先由用户为本应用打开「安装未知应用」，App 不能自批。 */
    private fun installUpdate(file: File) {
        // 版本码比本机低时 Android 必定拒绝，提前说清原因，别让系统只回一句「安装失败」。
        if (ApkInstaller.isDowngrade(this, file)) {
            toast("该更新包的版本低于当前已安装版本，Android 不允许覆盖安装；请等待更高版本发布")
            return
        }
        // 先自己判签名：系统安装器只会甩「软件包似乎无效 / 签名不一致」，用户无从下手。
        when (ApkInstaller.signatureState(this, file)) {
            ApkSignatureState.Unknown -> {
                toast("下载的安装包已损坏或没有签名，请重新检查更新再试")
                return
            }
            ApkSignatureState.Mismatch -> {
                UiKit.dialog(this)
                    .setTitle("需要先卸载旧版本")
                    .setMessage("更新包的签名与当前安装的版本不同，Android 不允许直接覆盖安装。卸载当前版本后再点「检查更新」下载安装即可（本地数据会一并清除）。")
                    .setPositiveButton("去卸载") { _, _ ->
                        runCatching { startActivity(ApkInstaller.uninstallIntent(this)) }
                            .onFailure { toast("没有可用的卸载入口，请到系统设置里手动卸载") }
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return
            }
            ApkSignatureState.Match -> Unit
        }
        if (ApkInstaller.needsInstallPermission(this)) {
            UiKit.dialog(this)
                .setTitle("还需要一步授权")
                .setMessage("系统要求你为本应用单独允许「安装未知应用」，之后才能完成更新。")
                .setPositiveButton("去设置") { _, _ -> openInstallSettings() }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        runCatching { startActivity(ApkInstaller.install(this, file)) }
            .onFailure { toast("没有可用的安装器") }
    }

    private fun openInstallSettings() {
        // 部分定制 ROM 没有这个设置页，静默失败会让用户以为按钮坏了
        runCatching { startActivity(ApkInstaller.permissionSettings(this)) }
            .onFailure { toast("系统没有这个设置页，请到系统设置里手动允许安装") }
    }

    private fun sameSubnet(address: String): Boolean {
        val local = localIpv4() ?: return false
        return address.substringBefore(':').substringBeforeLast('.') == local.substringBeforeLast('.')
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /** FS-01 参数「音频」的开关文案：状态直接写在按钮上，投屏中一眼能看出现在有没有声音 */
    private fun audioLabel() = if (SenderControl.audio) "声音：开" else "声音：关"

    override fun onDestroy() {
        main.removeCallbacks(refresh)
        main.removeCallbacks(loadingTimeout)
        main.removeCallbacks(autoSettle)
        qrCaptureFile?.delete()
        qrCaptureFile = null
        // 已建好但还没交给前台服务的跨网隧道必须在这里释放，daemon 线程不会替我们做这件事
        pendingCrossNetToken?.let { CrossNetLinkRegistry.discard(it) }
        pendingCrossNetToken = null
        browser?.stop()
        scanner?.let { store.saveRecentTargets(it.snapshot()); it.stop() }
        scanner = null
        // 发送端离开界面即停止采集，避免后台持续录屏
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AirMirrorSender"
        private const val REQUEST_CAPTURE = 1
        private const val REQUEST_PERMISSION = 2
        private const val REQUEST_QR = 3
        private const val SEARCHING = "正在搜索同一网络里的电视…"
        private const val PROTOCOL = "AMRC"
        private const val CODE_LENGTH = 6
        private const val PIN_LENGTH = 4
        private const val DEFAULT_PORT = 41523
        private const val POLL_MS = 800L
        const val EXTRA_TARGET_HOST = "io.github.wangnan05563.airmirror.TARGET_HOST"
        const val EXTRA_TARGET_PORT = "io.github.wangnan05563.airmirror.TARGET_PORT"
        const val EXTRA_TARGET_CREDENTIAL = "io.github.wangnan05563.airmirror.TARGET_CREDENTIAL"
    }
}
