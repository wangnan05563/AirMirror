package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.wangnan05563.airmirror.dlna.localIpv4
import io.github.wangnan05563.airmirror.net.DeviceRegistry
import io.github.wangnan05563.airmirror.net.ServiceBrowser
import io.github.wangnan05563.airmirror.pairing.DeviceStore
import io.github.wangnan05563.airmirror.pairing.PairingLink
import io.github.wangnan05563.airmirror.protocol.ProtocolBoundary
import io.github.wangnan05563.airmirror.sender.CaptureService
import io.github.wangnan05563.airmirror.sender.SenderControl
import io.github.wangnan05563.airmirror.sender.SenderReport

/**
 * 发送端选择页（PRD FR-02 + §11.3）：设备卡片只给设备名与协议角标，不展示 IP 等实现细节；
 * 列表为空要说明原因，并留出投屏码与手动地址两条后备路径。
 */
class SenderActivity : Activity() {

    private data class Target(val name: String, val host: String, val port: Int, val fingerprint: String)

    private lateinit var store: DeviceStore
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var list: LinearLayout
    private lateinit var codeInput: EditText
    private lateinit var addressInput: EditText
    private lateinit var pauseButton: Button
    private lateinit var takeoverBar: LinearLayout
    private lateinit var takeoverText: TextView
    private var browser: ServiceBrowser? = null
    private var pending: Target? = null
    private var pendingCredential: String = ""
    private var lastCasting = false

    private val devices = DeviceRegistry(now = { System.currentTimeMillis() }, sameSubnet = ::sameSubnet)
    private val main = Handler(Looper.getMainLooper())
    private var lastReport = ""

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    /** 采集在后台服务里跑，界面按拍轮询它的结论。 */
    private val refresh = object : Runnable {
        override fun run() {
            // 异常文案优先；没有异常时展示协商后的画质，两者都不在就保持当前提示
            val report = SenderReport.last
            val note = if (report.isEmpty()) SenderReport.quality else report
            if (note != lastReport) {
                lastReport = note
                status.text = note.ifEmpty { hint.text.ifEmpty { SEARCHING } }
                // 出错时把输入放开让用户重试；正常投屏中保持禁用，避免重复发起
                codeInput.isEnabled = report.isNotEmpty()
            }
            // 有画质说明即表示会话在跑；暂停只停送帧，按钮仍留在原位
            val casting = SenderReport.quality.isNotEmpty()
            if (casting != lastCasting) {
                lastCasting = casting
                pauseButton.visibility = if (casting) View.VISIBLE else View.GONE
            }
            if (casting) pauseButton.text = if (SenderControl.paused) "继续投屏" else "暂停投屏"
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
        pauseButton = Button(this).apply {
            text = "暂停投屏"
            setTextColor(Theme.ACCENT)
            visibility = View.GONE
            setOnClickListener { SenderControl.paused = !SenderControl.paused }
        }
        root.addView(pauseButton, matchWidth())
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
            setPadding(Theme.dp(this@SenderActivity, 4), 0, 0, Theme.dp(this@SenderActivity, 12))
        }, matchWidth())

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
        root.addView(
            ScrollView(this).apply { addView(column) },
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
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        startBrowsing()
        main.post(refresh)

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
                devices.report(service.name, service.name, PROTOCOL, "${service.host}:${service.port}")
                runOnUiThread { renderList() }
            },
            onLost = { name ->
                devices.markLost(name)
                runOnUiThread { renderList() }
            },
        ).also { it.start() }
    }

    private fun renderList() {
        list.removeAllViews()
        for (row in devices.rows()) {
            val target = targetOf(row.fingerprint, row.name) ?: continue
            // 一台设备可能同时广播多种协议：有能用的就按能用的展示，全都用不了才标不支持
            val usable = ProtocolBoundary.bestSupported(row.protocols)
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(if (row.online) Theme.SURFACE else Theme.BACKGROUND)
                val gap = Theme.dp(this@SenderActivity, 12)
                setPadding(gap, gap, gap, gap)
                addView(TextView(this@SenderActivity).apply {
                    text = row.name
                    textSize = 18f
                    setTextColor(if (row.online) Theme.TEXT_PRIMARY else Theme.OFFLINE)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@SenderActivity).apply {
                    text = when {
                        !row.online -> "离线"
                        usable != null -> ProtocolBoundary.badgeFor(usable)
                        else -> "不支持"
                    }
                    textSize = Theme.PHONE_BODY_SP
                    setTextColor(if (row.online && usable != null) Theme.ACCENT else Theme.OFFLINE)
                })
                setOnClickListener {
                    if (usable != null) {
                        connectTo(target)
                    } else {
                        toast(ProtocolBoundary.describe(row.strongest).text())
                    }
                }
            }, matchWidth())
        }
        val reason = when (devices.emptyReason()) {
            DeviceRegistry.EmptyReason.NONE_FOUND -> SEARCHING
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
    private fun connectByCode() {
        val code = codeInput.text.toString().trim()
        if (code.length != CODE_LENGTH) {
            toast("请输入电视上的 6 位投屏码")
            return
        }
        val online = devices.rows().filter { it.online }
        val usable = online.filter { ProtocolBoundary.bestSupported(it.protocols) != null }
        when {
            online.isEmpty() -> toast("没搜到设备，在下面填电视地址")
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
        requestCapture(target, credential)
    }

    private fun requestCapture(target: Target, credential: String) {
        pending = target
        pendingCredential = credential
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Activity result API kept dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        val target = pending ?: return
        if (resultCode != RESULT_OK || data == null) {
            codeInput.isEnabled = true
            status.text = if (lastReport.isEmpty()) hint.text else lastReport
            toast("未获得屏幕录制授权")
            return
        }
        val intent = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_HOST, target.host)
            putExtra(CaptureService.EXTRA_PORT, target.port)
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            putExtra(CaptureService.EXTRA_DEVICE_ID, store.deviceId())
            putExtra(CaptureService.EXTRA_CREDENTIAL, pendingCredential)
            // 断线后按服务名重新解析：IP 变了才能找到同一台设备（PRD FS-10）
            putExtra(CaptureService.EXTRA_SERVICE_NAME, target.name)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        status.text = "正在投屏到 ${target.name}"
    }

    private fun sameSubnet(address: String): Boolean {
        val local = localIpv4() ?: return false
        return address.substringBefore(':').substringBeforeLast('.') == local.substringBeforeLast('.')
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        main.removeCallbacks(refresh)
        browser?.stop()
        // 发送端离开界面即停止采集，避免后台持续录屏
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CAPTURE = 1
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
