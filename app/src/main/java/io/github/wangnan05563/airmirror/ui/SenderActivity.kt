package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.app.AlertDialog
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
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
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
import io.github.wangnan05563.airmirror.error.ErrorNotice
import io.github.wangnan05563.airmirror.net.DeviceRegistry
import io.github.wangnan05563.airmirror.net.ProbeResponder
import io.github.wangnan05563.airmirror.net.ProbeScanner
import io.github.wangnan05563.airmirror.net.RecentTargets
import io.github.wangnan05563.airmirror.net.ServiceBrowser
import io.github.wangnan05563.airmirror.net.UdpProbeTransport
import io.github.wangnan05563.airmirror.pairing.DeviceStore
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

    private data class Target(val name: String, val host: String, val port: Int, val fingerprint: String)

    private lateinit var store: DeviceStore
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var permissionFix: Button
    private lateinit var list: LinearLayout
    private lateinit var codeInput: EditText
    private lateinit var addressInput: EditText
    private lateinit var pauseButton: Button
    private lateinit var audioButton: Button
    private lateinit var takeoverBar: LinearLayout
    private lateinit var takeoverText: TextView
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
    /** 三轮探测全空后由 DiscoveryLadder 给出的可执行原因；为空表示仍在搜或已经命中。 */
    private var discoveryNotice = ""

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
    private val refresh = object : Runnable {
        override fun run() {
            // 异常文案优先；没有异常时展示协商后的画质，两者都不在就保持当前提示
            val report = SenderReport.last
            val note = if (report.isEmpty()) SenderReport.quality else report
            if (note != lastReport) {
                lastReport = note
                // UX-02：码类拒绝是发送端唯一可观测的「换码」时刻，按输入态给可重新确认的提示，
                // 输入框保留不清空（这里从不 setText("")）；非码类错误照旧走原提示。
                status.text = codeRotationNotice(report) ?: note.ifEmpty { hint.text.ifEmpty { SEARCHING } }
                // 出错时把输入放开让用户重试；正常投屏中保持禁用，避免重复发起
                codeInput.isEnabled = report.isNotEmpty()
            }
            // 有画质说明即表示会话在跑；暂停只停送帧，按钮仍留在原位
            val casting = SenderReport.quality.isNotEmpty()
            if (casting != lastCasting) {
                lastCasting = casting
                controlBar.visibility = if (casting) View.VISIBLE else View.GONE
                // PRD §11.3「投屏中列表收起」
                listScroller.visibility = if (casting) View.GONE else View.VISIBLE
                if (casting) expandedFingerprint = null
            }
            if (casting) {
                pauseButton.text = DeviceCardPolicy.pauseLabel(SenderControl.paused)
                audioButton.text = audioLabel()
            }
            refreshTakeover(casting)
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DeviceStore(this)
        val pad = Theme.dp(this, 20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BACKGROUND)
            setPadding(pad, Theme.dp(this@SenderActivity, 32), pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "投到其他设备"
            textSize = 28f
            setTextColor(Theme.TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
        })
        // PRD §04 / §13.1：能做什么先说清楚，Miracast 不在承诺内
        root.addView(TextView(this).apply {
            text = "支持镜像到同网 AirMirror 设备；不支持 Miracast"
            textSize = Theme.PHONE_BODY_SP
            setTextColor(Theme.TEXT_SECONDARY)
            setPadding(0, Theme.dp(this@SenderActivity, 4), 0, 0)
        })
        status = TextView(this).apply {
            text = SEARCHING
            textSize = Theme.PHONE_BODY_SP
            setTextColor(Theme.ACCENT)
            setPadding(0, Theme.dp(this@SenderActivity, 6), 0, Theme.dp(this@SenderActivity, 14))
        }
        root.addView(status)
        // PRD 7.4「权限缺失：跳转设置」：真到了只能去设置的地步（系统不再询问），
        // 说明框里已经问不出结果，这里留一个持久入口，位置就在拒绝后的状态行下面
        permissionFix = Button(this).apply {
            text = "去设置打开权限"
            setTextColor(Theme.ACCENT)
            visibility = View.GONE
            setOnClickListener { openPermissionSettings() }
        }
        root.addView(permissionFix, matchWidth())
        root.addView(buildTakeoverBar(), matchWidth())

        codeInput = makeInput("投屏码", InputType.TYPE_CLASS_NUMBER)
        codeInput.filters = arrayOf(InputFilter.LengthFilter(CODE_LENGTH))
        root.addView(
            rowWith(codeInput, "用码连接") { connectByCode() },
            matchWidth(),
        )
        root.addView(TextView(this).apply {
            text = "电视待连接页上的 6 位数字"
            textSize = Theme.PHONE_BODY_SP
            setTextColor(Theme.TEXT_SECONDARY)
            setPadding(Theme.dp(this@SenderActivity, 4), 0, 0, Theme.dp(this@SenderActivity, 4))
        }, matchWidth())
        // PRD FS-04：跨网入口与局域网并列，但只在「局域网没搜到设备」时才会尝试，
        // 填了端点才启用；不填就不向任何外部服务发请求。
        crossNetRow = TextView(this).apply {
            textSize = Theme.PHONE_BODY_SP
            setTextColor(Theme.TEXT_SECONDARY)
            setPadding(Theme.dp(this@SenderActivity, 4), 0, 0, Theme.dp(this@SenderActivity, 12))
            setOnClickListener { editCrossNet() }
        }
        root.addView(crossNetRow, matchWidth())
        // PRD 通用需求「菜单新增在线更新」：内置仓库地址，不开放手填；只查 GitHub Release（Q-6）
        updateRow = TextView(this).apply {
            text = "检查更新"
            textSize = Theme.PHONE_BODY_SP
            setTextColor(Theme.TEXT_SECONDARY)
            setPadding(Theme.dp(this@SenderActivity, 4), 0, 0, Theme.dp(this@SenderActivity, 12))
            setOnClickListener { checkUpdate() }
        }
        root.addView(updateRow, matchWidth())

        hint = TextView(this).apply {
            textSize = Theme.PHONE_BODY_SP
            setTextColor(Theme.TEXT_SECONDARY)
            setPadding(Theme.dp(this@SenderActivity, 4), 0, 0, Theme.dp(this@SenderActivity, 8))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint, matchWidth())
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
            textSize = Theme.PHONE_BODY_SP
            setTextColor(Theme.OFFLINE)
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@SenderActivity, 10), 0, 0)
        }, matchWidth())
        // PRD §11.3：投屏中列表收起，底部固定一条「暂停 / 停止 / 声音」控制条
        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setBackgroundColor(Theme.SURFACE)
            val inset = Theme.dp(this@SenderActivity, 8)
            setPadding(inset, inset, inset, inset)
        }
        pauseButton = Button(this).apply {
            text = DeviceCardPolicy.pauseLabel(false)
            setTextColor(Theme.ACCENT)
            setOnClickListener { SenderControl.paused = !SenderControl.paused }
        }
        controlBar.addView(pauseButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controlBar.addView(Button(this).apply {
            text = "停止"
            setTextColor(Theme.DANGER)
            setOnClickListener {
                // 复用已有的停止通道：发 ACTION_STOP 让前台服务收尾，不直接杀进程
                startService(Intent(this@SenderActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                expandedFingerprint = null
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        audioButton = Button(this).apply {
            text = audioLabel()
            setTextColor(Theme.ACCENT)
            setOnClickListener {
                if (SenderControl.audio) setMirrorAudio(false) else enableMirrorAudio()
            }
        }
        controlBar.addView(audioButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(controlBar, matchWidth())
        setContentView(root)

        // PRD §04「权限申请在真正用到时才弹出，并说明用途」：这里不弹任何权限框，
        // 通知权限在发起投屏时申请、录音权限在要出声时申请（见 requestCapture / enableMirrorAudio）。
        SenderControl.audio = store.mirrorAudioEnabled()
        refreshCrossNetRow()
        startBrowsing()
        startScanning()
        main.post(refresh)
        // 冷启动静默查一次：只有发现新版本才弹框，已最新/检查失败都不打扰（手动入口仍走 checkUpdate 默认形态）
        checkUpdate(silent = true)

        // 联调入口：adb 直接拉起时带 host/port/凭据，跳过发现
        val directHost = intent.getStringExtra(EXTRA_TARGET_HOST)
        val directPort = intent.getIntExtra(EXTRA_TARGET_PORT, 0)
        if (!directHost.isNullOrEmpty() && directPort > 0) {
            requestCapture(Target("direct", directHost, directPort, directHost), intent.getStringExtra(EXTRA_TARGET_CREDENTIAL).orEmpty())
        }
    }

    /** FS-09 步骤 4：第二台设备申请接管时，由当前发送端决定让出还是拒绝。 */
    private fun buildTakeoverBar(): View {
        takeoverBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF12314F.toInt())
            visibility = View.GONE
            val gap = Theme.dp(this@SenderActivity, 8)
            setPadding(gap, gap, gap, gap)
        }
        takeoverText = TextView(this).apply {
            textSize = Theme.PHONE_BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        takeoverBar.addView(takeoverText)
        takeoverBar.addView(Button(this).apply {
            text = "拒绝"
            setTextColor(Theme.DANGER)
            setOnClickListener { SenderControl.answerTakeover(false) }
        })
        takeoverBar.addView(Button(this).apply {
            text = "让出"
            setTextColor(Theme.ACCENT)
            setOnClickListener { SenderControl.answerTakeover(true) }
        })
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
        takeoverText.text = "$from 请求接管投屏 · ${(leftMs + 999) / 1000} 秒后拒绝"
    }

    private fun makeInput(label: String, inputType: Int) = EditText(this).apply {
        hint = label
        this.inputType = inputType
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.PHONE_BODY_SP)
        setTextColor(Theme.TEXT_PRIMARY)
        setHintTextColor(Theme.OFFLINE)
        setBackgroundColor(Theme.SURFACE)
        setPadding(Theme.dp(this@SenderActivity, 12), Theme.dp(this@SenderActivity, 6), Theme.dp(this@SenderActivity, 12), Theme.dp(this@SenderActivity, 6))
    }

    private fun rowWith(input: EditText, action: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(Button(this@SenderActivity).apply {
            text = action
            setTextColor(Theme.ACCENT)
            setOnClickListener { onClick() }
        })
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun startBrowsing() {
        browser = ServiceBrowser(
            this,
            onFound = { service ->
                scanner?.noteMulticastHit()
                devices.report(service.name, service.name, PROTOCOL, "${service.host}:${service.port}")
                runOnUiThread { renderList() }
            },
            onLost = { name ->
                devices.markLost(name)
                runOnUiThread { renderList() }
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
                discoveryNotice = text
            }

            override fun onChanged() {
                runOnUiThread {
                    renderList()
                    if (discoveryNotice.isNotEmpty() && status.text == SEARCHING) status.text = discoveryNotice
                }
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
        for (row in devices.rows()) {
            val target = targetOf(row.fingerprint, row.name) ?: continue
            // 一台设备可能同时广播多种协议：有能用的就按能用的展示，全都用不了才标不支持
            val usable = ProtocolBoundary.bestSupported(row.protocols)
            val card = DeviceCardPolicy.Card(
                fingerprint = row.fingerprint,
                online = row.online,
                supported = usable != null,
                lastConnectedAt = row.lastConnectedAt,
            )
            val actions = DeviceCardPolicy.actions(card)
            list.addView(LinearLayout(this@SenderActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(if (row.online) Theme.SURFACE else Theme.BACKGROUND)
                val gap = Theme.dp(this@SenderActivity, 12)
                setPadding(gap, gap, gap, gap)
                addView(LinearLayout(this@SenderActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@SenderActivity).apply {
                        text = row.name
                        textSize = 18f
                        setTextColor(if (row.online) Theme.TEXT_PRIMARY else Theme.OFFLINE)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(TextView(this@SenderActivity).apply {
                        text = when {
                            !row.online -> DeviceCardPolicy.offlineLabel(row.lastConnectedAt, System.currentTimeMillis())
                            usable != null -> ProtocolBoundary.badgeFor(usable)
                            else -> "不支持"
                        }
                        textSize = Theme.PHONE_BODY_SP
                        setTextColor(if (row.online && usable != null) Theme.ACCENT else Theme.OFFLINE)
                    })
                    // PRD §11.3：点击先展开两个动作，不再「点一下就先连上再选」
                    setOnClickListener {
                        if (actions.isEmpty()) {
                            toast(if (row.online) ProtocolBoundary.describe(row.strongest).text() else DeviceCardPolicy.offlineHint())
                        } else {
                            expandedFingerprint = DeviceCardPolicy.toggle(expandedFingerprint, row.fingerprint)
                            renderList()
                        }
                    }
                }, matchWidth())
                if (row.fingerprint == expandedFingerprint && actions.isNotEmpty()) {
                    addView(LinearLayout(this@SenderActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, gap, 0, 0)
                        for (action in actions) {
                            addView(Button(this@SenderActivity).apply {
                                text = when (action) {
                                    DeviceCardPolicy.CardAction.MIRROR -> "镜像屏幕"
                                    DeviceCardPolicy.CardAction.PUSH_VIDEO -> "推送视频"
                                }
                                setTextColor(Theme.ACCENT)
                                setOnClickListener {
                                    expandedFingerprint = null
                                    renderList()
                                    when (action) {
                                        DeviceCardPolicy.CardAction.MIRROR -> connectTo(target)
                                        // 本片不伪装修好推送链路：按 7.3 给可执行替代路径
                                        DeviceCardPolicy.CardAction.PUSH_VIDEO -> toast(DeviceCardPolicy.pushVideoFallback())
                                    }
                                }
                            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        }
                    }, matchWidth())
                }
            }, matchWidth())
        }
        val reason = when (devices.emptyReason()) {
            DeviceRegistry.EmptyReason.NONE_FOUND -> discoveryNotice.ifEmpty { SEARCHING }
            DeviceRegistry.EmptyReason.ALL_OFFLINE -> "这些设备当前离线，可用投屏码连接"
            DeviceRegistry.EmptyReason.HAS_ONLINE -> "点设备名开始投屏"
        }
        hint.text = reason
        if (status.text == SEARCHING || status.text == CONNECTING) status.text = reason
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
        val code = codeInput.text.toString().trim()
        if (code.length != CODE_LENGTH) {
            toast("请输入电视上的 6 位投屏码")
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
        status.text = "正在跨网连接…"
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
                    status.text = outcome.prompt.ifEmpty { "跨网连接失败" }
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
        val port = link?.port ?: text.substringAfter(':', "").toIntOrNull() ?: DEFAULT_PORT
        val code = codeInput.text.toString().trim()
        if (host.isEmpty() || port !in 1..65_535) {
            toast("地址不完整")
            return
        }
        val credential = when {
            code.length == CODE_LENGTH -> code
            else -> link?.token.orEmpty()
        }
        connectTo(Target(link?.name.orEmpty().ifEmpty { host }, host, port, host), credential)
    }

    private fun connectTo(target: Target, credential: String = codeInput.text.toString().trim()) {
        status.text = CONNECTING
        lastReport = ""
        codeInput.isEnabled = false
        // 记进 10 分钟缓存：下次组播/广播全被挡住时，这个地址就是直连探测的唯一入口
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
            AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
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
        status.text = line
        toast(if (blocked.isEmpty()) line else "权限已在系统里关掉，请点「去设置打开权限」")
        refreshFixEntry()
    }

    /** 只在真的没救时露出「去设置打开权限」，能问的时候露出来反而把用户往设置里赶 */
    private fun refreshFixEntry(granted: Set<String> = grantedKeys()) {
        val blocked = RuntimePermission.entries.filter { isSystemBlocked(it, granted) }
        permissionFix.visibility = if (blocked.isEmpty()) View.GONE else View.VISIBLE
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
     * 2) 投屏中被收回的权限按 PRD 异常表口径降级，不终止会话。
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
            if (!lastCasting) status.text = RuntimePermission.settingsReturnNote(missing)
            toast(if (missing.isEmpty()) "权限已全部打开" else "还有权限没打开：" + missing.joinToString("、") { it.label })
        }
        reconcileRevoked(granted)
    }

    /**
     * 投屏到一半权限被系统收回（PRD 异常表：权限缺失 → 画面继续，不中断会话）：
     * 声音权限没了就自动回「关」，画面照投；通知权限没了只是通知栏不再有停止入口，提人用本页按钮停。
     * 每次变化只提示一次，重新授予后清掉记录，下次再被收回还能提。
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
                    status.text = line
                    toast(line)
                    refreshFixEntry(granted)
                }

                RuntimePermission.CASTING_NOTIFICATION -> {
                    Log.i(TAG, "permission revoked mid-cast: " + permission.key)
                    val line = "通知权限已被收回：通知栏不再有停止入口，请在本页点「停止」"
                    status.text = line
                    toast(line)
                }
            }
        }
    }

    @Deprecated("Activity result API kept dependency-free")
    override fun onRequestPermissionsResult(
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
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        val target = pending ?: return
        if (resultCode != RESULT_OK || data == null) {
            codeInput.isEnabled = true
            // 没拿到录屏授权就没有会话，跨网隧道必须在此时释放，否则线程和 socket 一直挂着
            pendingCrossNetToken?.let { CrossNetLinkRegistry.discard(it) }
            pendingCrossNetToken = null
            status.text = if (lastReport.isEmpty()) hint.text else lastReport
            toast("未获得屏幕录制授权")
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
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        status.text = if (token != null) "正在投屏到跨网设备" else "正在投屏到 ${target.name}"
    }

    /** 跨网端点与应用内说明；与接收端共用同一份落盘设置，手机上填一次即可。 */
    private fun refreshCrossNetRow() {
        val url = store.signalingUrl()
        val on = SignalingSettings.enabled(url)
        crossNetRow.text = if (on) "跨网信令：已配置（$url）" else "跨网信令：未配置（点此设置，仅跨网时使用）"
        crossNetRow.setTextColor(if (on) Theme.ACCENT else Theme.TEXT_SECONDARY)
    }

    private fun editCrossNet() {
        val input = EditText(this).apply {
            setText(store.signalingUrl())
            hint = "https://你的信令服务"
            setSingleLine()
            setTextColor(Theme.TEXT_PRIMARY)
        }
        AlertDialog.Builder(this)
            .setTitle("跨网信令服务")
            .setMessage("留空则不启用跨网。信令只交换建连信息，画面仍点对点直连、不经服务器。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                store.saveSignalingUrl(input.text.toString())
                refreshCrossNetRow()
                val on = SignalingSettings.enabled(store.signalingUrl())
                toast(if (on) "跨网已启用；同网搜不到设备时会自动尝试" else "跨网已关闭")
            }
            .setNegativeButton("取消", null)
            .show()
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
        // minSdk 19：SUPPORTED_ABIS 是 API 21，4.4 上退回单值 CPU_ABI（已 deprecated 但仍可用）
        @Suppress("DEPRECATION")
        val abis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS
        } else {
            arrayOf(Build.CPU_ABI)
        }
        val abi = when {
            abis.any { it.equals("arm64-v8a", ignoreCase = true) } -> "arm64-v8a"
            abis.any { it.equals("armeabi-v7a", ignoreCase = true) } -> "armeabi-v7a"
            else -> ""
        }
        thread(name = "airmirror-update-check") {
            val result = UpdateChecker(UrlHttpText(), version, preferAbi = abi).check()
            main.post {
                if (isFinishing) return@post
                updateBusy = false
                if (!silent) updateRow.text = "检查更新"
                when (result) {
                    is UpdateResult.UpToDate -> if (!silent) toast("已是最新版本（${version.ifEmpty { "未知" }}）")
                    is UpdateResult.Failed -> if (!silent) toast(result.reason)
                    is UpdateResult.Available -> promptUpdate(result.release)
                }
            }
        }
    }

    private fun promptUpdate(release: ReleaseInfo) {
        val notes = release.notes.take(400).ifBlank { "该版本没有填写更新说明。" }
        AlertDialog.Builder(this)
            .setTitle("发现新版本 ${release.tag}")
            .setMessage(notes)
            .setPositiveButton("下载并安装") { _, _ -> downloadUpdate(release) }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun downloadUpdate(release: ReleaseInfo) {
        if (updateBusy) return
        updateBusy = true
        updateRow.text = "正在下载更新…"
        thread(name = "airmirror-update-download") {
            val target = File(ApkProvider.dir(this), ApkDownload.safeName(release.apkName))
            val file = runCatching {
                ApkDownload.download(release.apkUrl, target) { done, total ->
                    if (total > 0) {
                        val percent = (done * 100 / total).toInt()
                        main.post { if (!isFinishing) updateRow.text = "正在下载更新… $percent%" }
                    }
                }
            }.getOrNull()
            main.post {
                updateBusy = false
                updateRow.text = "检查更新"
                if (isFinishing) return@post
                if (file == null) {
                    toast("下载失败，请检查网络后重试")
                    return@post
                }
                // Android 8+ 必须先由用户为本应用打开「安装未知应用」，App 不能自批
                if (ApkInstaller.needsInstallPermission(this)) {
                    AlertDialog.Builder(this)
                        .setTitle("还需要一步授权")
                        .setMessage("系统要求你为本应用单独允许「安装未知应用」，之后才能完成更新。")
                        .setPositiveButton("去设置") { _, _ ->
                            runCatching { startActivity(ApkInstaller.permissionSettings(this)) }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    runCatching { startActivity(ApkInstaller.install(this, file)) }
                        .onFailure { toast("没有可用的安装器") }
                }
            }
        }
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
        private const val SEARCHING = "正在搜索同一网络里的电视…"
        private const val CONNECTING = "正在连接…"
        private const val PROTOCOL = "AMRC"
        private const val CODE_LENGTH = 6
        private const val DEFAULT_PORT = 41523
        private const val POLL_MS = 800L
        const val EXTRA_TARGET_HOST = "io.github.wangnan05563.airmirror.TARGET_HOST"
        const val EXTRA_TARGET_PORT = "io.github.wangnan05563.airmirror.TARGET_PORT"
        const val EXTRA_TARGET_CREDENTIAL = "io.github.wangnan05563.airmirror.TARGET_CREDENTIAL"
    }
}
