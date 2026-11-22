package io.github.wangnan05563.airmirror.pc

import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Collections
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import kotlin.concurrent.thread

/**
 * 首启向导状态。文件固定为 `%APPDATA%\AirMirror\onboarding.json`；
 * `completed` 只在真实首帧发出后写 true，`skipped` 表示用户主动跳过，不再自动弹向导。
 */
class OnboardingStore(private val file: Path = defaultPath()) {
    data class State(
        val completed: Boolean = false,
        val skipped: Boolean = false,
        val completedAt: String = "",
        val networkStep: String = "pending",
        val firewallStep: String = "pending",
        val lastDeviceId: String = "",
        val lastDeviceName: String = "",
        val lastHost: String = "",
        val lastPort: Int = 0,
        val firstFrameAt: String = "",
    )

    private val lock = Any()

    fun load(): State = synchronized(lock) {
        if (!Files.exists(file)) return State()
        runCatching { OnboardingJson.decode(Files.readString(file, Charsets.UTF_8)) }.getOrDefault(State())
    }

    fun save(state: State): State = synchronized(lock) {
        runCatching {
            file.parent?.let { Files.createDirectories(it) }
            val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(temp, OnboardingJson.encode(state), Charsets.UTF_8)
            runCatching {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.onFailure {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        state
    }

    fun shouldShow(): Boolean {
        val state = load()
        return !state.completed && !state.skipped
    }

    fun markStep(networkStep: String? = null, firewallStep: String? = null): State = synchronized(lock) {
        val current = load()
        val updated = current.copy(
            networkStep = networkStep ?: current.networkStep,
            firewallStep = firewallStep ?: current.firewallStep,
        )
        save(updated)
    }

    fun markConnection(deviceId: String, deviceName: String, host: String, port: Int): State = synchronized(lock) {
        val current = load()
        save(
            current.copy(
                skipped = false,
                lastDeviceId = deviceId,
                lastDeviceName = deviceName,
                lastHost = host,
                lastPort = port,
            ),
        )
    }

    fun markCompleted(deviceName: String, host: String, port: Int): State = synchronized(lock) {
        val current = load()
        val now = Instant.now().toString()
        val updated = current.copy(
            completed = true,
            skipped = false,
            completedAt = now,
            firstFrameAt = now,
            lastDeviceName = deviceName.ifBlank { current.lastDeviceName },
            lastHost = host.ifBlank { current.lastHost },
            lastPort = port.takeIf { it > 0 } ?: current.lastPort,
        )
        save(updated)
    }

    fun markSkipped(): State = synchronized(lock) {
        val current = load()
        val updated = current.copy(skipped = true)
        save(updated)
    }

    companion object {
        fun defaultPath(): Path {
            val appData = System.getenv("APPDATA")
            val root = if (appData.isNullOrBlank()) System.getProperty("user.home") else appData
            return Paths.get(root, "AirMirror", "onboarding.json")
        }
    }
}

/** 扁平 JSON 编解码；状态字段固定，不引入额外 JSON 依赖。 */
internal object OnboardingJson {
    fun encode(state: OnboardingStore.State): String = buildString {
        append('{')
        string("schemaVersion", "1")
        append(',')
        bool("completed", state.completed)
        append(',')
        bool("skipped", state.skipped)
        append(',')
        string("completedAt", state.completedAt)
        append(',')
        string("networkStep", state.networkStep)
        append(',')
        string("firewallStep", state.firewallStep)
        append(',')
        string("lastDeviceId", state.lastDeviceId)
        append(',')
        string("lastDeviceName", state.lastDeviceName)
        append(',')
        string("lastHost", state.lastHost)
        append(',')
        append("\"lastPort\":").append(state.lastPort)
        append(',')
        string("firstFrameAt", state.firstFrameAt)
        append('}')
    }

    fun decode(text: String): OnboardingStore.State {
        val values = LinkedHashMap<String, String>()
        val pattern = Regex("\"([^\"]+)\"\\s*:\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|(true|false)|(-?\\d+))")
        for (match in pattern.findAll(text)) {
            val key = match.groupValues[1]
            val quoted = match.groupValues[2]
            val bool = match.groupValues[3]
            val number = match.groupValues[4]
            values[key] = when {
                match.groupValues[2].isNotEmpty() || text.contains("\"$key\":\"") -> unescape(quoted)
                bool.isNotEmpty() -> bool
                else -> number
            }
        }
        fun flag(key: String) = values[key]?.toBooleanStrictOrNull() ?: false
        fun int(key: String) = values[key]?.toIntOrNull() ?: 0
        return OnboardingStore.State(
            completed = flag("completed"),
            skipped = flag("skipped"),
            completedAt = values["completedAt"].orEmpty(),
            networkStep = values["networkStep"]?.takeIf { it.isNotBlank() } ?: "pending",
            firewallStep = values["firewallStep"]?.takeIf { it.isNotBlank() } ?: "pending",
            lastDeviceId = values["lastDeviceId"].orEmpty(),
            lastDeviceName = values["lastDeviceName"].orEmpty(),
            lastHost = values["lastHost"].orEmpty(),
            lastPort = int("lastPort"),
            firstFrameAt = values["firstFrameAt"].orEmpty(),
        )
    }

    private fun StringBuilder.string(key: String, value: String) {
        append('"').append(key).append("\":\"").append(escape(value)).append('"')
    }

    private fun StringBuilder.bool(key: String, value: Boolean) {
        append('"').append(key).append("\":").append(if (value) "true" else "false")
    }

    private fun escape(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }

    private fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch != '\\' || i + 1 >= value.length) {
                out.append(ch)
                i++
                continue
            }
            when (val next = value[i + 1]) {
                '\\' -> out.append('\\')
                '"' -> out.append('"')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    val hex = value.substring(i + 2, (i + 6).coerceAtMost(value.length))
                    out.append(runCatching { hex.toInt(16).toChar() }.getOrDefault('?'))
                    i += 4
                }
                else -> out.append(next)
            }
            i += 2
        }
        return out.toString()
    }
}

/**
 * 四步首启向导：网络检查 → 防火墙 → 发现设备 → 投屏验证。
 * 第 4 步只发起连接；真正的 completed 由 SenderEngine 的首帧回调写入，避免“点下一步就算成功”。
 */
class OnboardingWizard(
    private val ownerFrame: Frame,
    private val store: OnboardingStore = OnboardingStore(),
    private val onConnect: (host: String, port: Int, code: String, pairId: String) -> Unit,
    private val scriptDir: Path = defaultScriptDir(),
) : JDialog(ownerFrame, "AirMirror 首次设置", true) {
    private data class NetChoice(val label: String, val host: String, val vpn: Boolean) {
        override fun toString(): String = label
    }

    private data class DeviceItem(val device: MdnsDiscovery.Device?) {
        override fun toString(): String =
            device?.let { "${it.name}  ${it.host}:${it.port}" } ?: "正在查找同一网络里的电视…"
    }

    private var state = store.load()
    private val cards = CardLayout()
    private val cardPanel = JPanel(cards)
    private val stepLabel = JLabel("第 1 步，共 4 步").apply {
        foreground = Color(0x17C3B2)
        font = font.deriveFont(Font.BOLD, 13f)
    }
    private val titleLabel = JLabel("检查网络").apply { font = font.deriveFont(Font.BOLD, 22f) }
    private val bodyLabel = JLabel(" ").apply { foreground = Color(0xB8C7D9) }
    private val backButton = JButton("上一步")
    private val nextButton = JButton("下一步")
    private val skipButton = JButton("先跳过，进入主界面")

    private val networkStatus = JLabel("正在检查网络…")
    private val networkDetail = JLabel(" ")
    private val networkBox = JComboBox<NetChoice>()

    private val firewallStatus = JLabel("正在检查防火墙…")
    private val firewallDetail = JLabel(" ")
    private val fixFirewallButton = JButton("以管理员运行修复")
    private val recheckFirewallButton = JButton("重新检查")

    private val discoveryStatus = JLabel("正在同一网络里找电视…")
    private val discoveryDetail = JLabel(" ")
    private val deviceBox = JComboBox<DeviceItem>()
    private val refreshDiscoveryButton = JButton("重试发现")
    private val manualHost = JTextField(16)
    private val manualPort = JTextField("41523", 7)

    private val summaryLabel = JLabel(" ")
    private val codeField = JTextField(8)
    private val connectStatus = JLabel(" ")
    private val scanButton = JButton("扫码配对")

    private var discovery: MdnsDiscovery? = null
    private var discoveryStartedAt = 0L
    private var discoveryTimer: Timer? = null
    private var currentStep = 1
    private var targetName = ""
    private var targetHost = ""
    private var targetPort = 41523
    private var targetPairId = ""

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        layout = BorderLayout(0, 12)
        (rootPane as javax.swing.JComponent).border = BorderFactory.createEmptyBorder(16, 20, 16, 20)
        background = Color(0x0B1F3A)

        val header = JPanel(BorderLayout(0, 6)).apply {
            isOpaque = false
            add(stepLabel, BorderLayout.NORTH)
            add(titleLabel, BorderLayout.CENTER)
            add(bodyLabel, BorderLayout.SOUTH)
        }
        add(header, BorderLayout.NORTH)
        add(cardPanel, BorderLayout.CENTER)

        cardPanel.add(networkPanel(), "1")
        cardPanel.add(firewallPanel(), "2")
        cardPanel.add(discoveryPanel(), "3")
        cardPanel.add(verifyPanel(), "4")

        val footer = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(skipButton)
            add(backButton)
            add(nextButton)
        }
        add(footer, BorderLayout.SOUTH)

        backButton.isEnabled = false
        backButton.addActionListener { goTo(currentStep - 1) }
        nextButton.addActionListener { onNext() }
        skipButton.addActionListener { skip() }
        fixFirewallButton.addActionListener { runFirewallFixer() }
        recheckFirewallButton.addActionListener { checkFirewall() }
        refreshDiscoveryButton.addActionListener {
            discovery?.queryNow()
            discoveryStatus.text = "已重新发送发现请求…"
        }
        scanButton.addActionListener { scanQr() }

        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) = skip()
        })
        goTo(1)
        pack()
        minimumSize = Dimension(700, 500)
        setLocationRelativeTo(ownerFrame)
    }

    private fun networkPanel(): JPanel = formPanel().apply {
        add(sectionTitle("网络检查"), constraints(0))
        add(networkStatus, constraints(1))
        add(networkDetail, constraints(2))
        add(JLabel("用于投屏的网卡"), constraints(3))
        add(networkBox, constraints(4))
        add(
            JLabel("只检查本机网卡，不访问外网；VPN 可能隔离局域网。"),
            constraints(5),
        )
    }

    private fun firewallPanel(): JPanel = formPanel().apply {
        add(sectionTitle("防火墙"), constraints(0))
        add(firewallStatus, constraints(1))
        add(firewallDetail, constraints(2))
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(fixFirewallButton)
            add(recheckFirewallButton)
        }
        add(actions, constraints(3))
        add(
            JLabel("只放行专用网络的 TCP 41523 与 UDP 41524-41525；不改公用网络。"),
            constraints(4),
        )
    }

    private fun discoveryPanel(): JPanel = formPanel().apply {
        add(sectionTitle("发现设备"), constraints(0))
        add(discoveryStatus, constraints(1))
        add(discoveryDetail, constraints(2))
        val selectRow = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            add(deviceBox, BorderLayout.CENTER)
            add(refreshDiscoveryButton, BorderLayout.EAST)
        }
        add(selectRow, constraints(3))
        add(JLabel("找不到？可手动填写电视 IP"), constraints(4))
        val manualRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(manualHost)
            add(JLabel("端口"))
            add(manualPort)
        }
        add(manualRow, constraints(5))
    }

    private fun verifyPanel(): JPanel = formPanel().apply {
        add(sectionTitle("投屏验证"), constraints(0))
        add(summaryLabel, constraints(1))
        add(JLabel("在电视上打开 AirMirror，确认 6 位投屏码；也可直接扫电视二维码。"), constraints(2))
        val codeRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(JLabel("投屏码"))
            add(codeField)
            add(scanButton)
        }
        add(codeRow, constraints(3))
        add(connectStatus, constraints(4))
        add(JLabel("点击“开始投屏”后，首帧出现时才会把向导记为完成。"), constraints(5))
    }

    private fun formPanel(): JPanel = JPanel(GridBagLayout()).apply {
        isOpaque = false
        background = Color(0x12314F)
        border = BorderFactory.createEmptyBorder(18, 20, 18, 20)
    }

    private fun sectionTitle(text: String): JLabel = JLabel(text).apply {
        font = font.deriveFont(Font.BOLD, 17f)
        foreground = Color(0xFFFFFF)
    }

    private fun constraints(row: Int): GridBagConstraints = GridBagConstraints().apply {
        gridx = 0
        gridy = row
        weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        anchor = GridBagConstraints.WEST
        insets = Insets(4, 0, 4, 0)
    }

    private fun goTo(step: Int) {
        currentStep = step.coerceIn(1, 4)
        cards.show(cardPanel, currentStep.toString())
        stepLabel.text = "第 $currentStep 步，共 4 步"
        backButton.isEnabled = currentStep > 1
        when (currentStep) {
            1 -> {
                titleLabel.text = "检查网络"
                bodyLabel.text = "先确认电脑和电视在同一局域网。"
                nextButton.text = "下一步"
                nextButton.isEnabled = true
                if (state.networkStep == "pending") scanNetwork()
            }
            2 -> {
                titleLabel.text = "放行防火墙"
                bodyLabel.text = "设备发现和投屏需要专用网络入站规则。"
                nextButton.text = "下一步"
                nextButton.isEnabled = true
                checkFirewall()
            }
            3 -> {
                titleLabel.text = "发现设备"
                bodyLabel.text = "优先 mDNS；3 秒内没结果就用手动地址。"
                nextButton.text = "下一步"
                nextButton.isEnabled = true
                startDiscovery()
            }
            4 -> {
                titleLabel.text = "完成首次投屏"
                bodyLabel.text = "最后一步只发起连接，首帧后自动完成。"
                nextButton.text = "开始投屏"
                nextButton.isEnabled = true
                updateSummary()
                codeField.requestFocusInWindow()
            }
        }
    }

    private fun scanNetwork() {
        networkStatus.text = "正在检查网络…"
        thread(name = "airmirror-onboarding-network", isDaemon = true) {
            val choices = runCatching {
                Collections.list(NetworkInterface.getNetworkInterfaces())
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { face ->
                        Collections.list(face.inetAddresses)
                            .filterIsInstance<Inet4Address>()
                            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                            .map { address ->
                                val vpn = face.name.lowercase().let {
                                    it.contains("vpn") || it.contains("tap") || it.contains("wg") ||
                                        it.contains("wireguard") || it.contains("tailscale")
                                }
                                NetChoice("${face.displayName} · ${address.hostAddress}", address.hostAddress, vpn)
                            }
                    }
            }.getOrDefault(emptyList())
            SwingUtilities.invokeLater {
                networkBox.removeAllItems()
                choices.forEach(networkBox::addItem)
                if (choices.isEmpty()) {
                    val onlyLoopback = runCatching {
                        Collections.list(NetworkInterface.getNetworkInterfaces())
                            .filter { it.isUp }
                            .flatMap { Collections.list(it.inetAddresses) }
                            .any { it.isLoopbackAddress }
                    }.getOrDefault(false)
                    networkStatus.text = if (onlyLoopback) "只找到回环地址，无法发现电视" else "没有检测到网络"
                    networkDetail.text = "先连 Wi-Fi 或网线，再点“上一步/下一步”重试。"
                    state = state.copy(networkStep = if (onlyLoopback) "loopback" else "no_network")
                } else {
                    val vpn = choices.firstOrNull { it.vpn }
                    networkStatus.text = if (vpn != null) "网络正常 · VPN 可能隔离局域网" else "网络正常 · ${choices.first().host}"
                    networkDetail.text = if (vpn != null) "VPN 网卡：${vpn.label}；若发现失败请临时断开 VPN。" else "已选择默认网卡。"
                    networkBox.selectedIndex = 0
                    state = state.copy(networkStep = if (vpn != null) "vpn_warning" else "ok")
                }
                store.save(state)
            }
        }
    }

    private fun checkFirewall() {
        val script = scriptDir.resolve("fix-firewall.ps1")
        if (!Files.isRegularFile(script)) {
            firewallStatus.text = "没有找到防火墙修复脚本"
            firewallDetail.text = "确认 scripts\\fix-firewall.ps1 与 AirMirror-PC.bat 在同一目录。"
            fixFirewallButton.isEnabled = false
            return
        }
        firewallStatus.text = "正在检查防火墙…"
        fixFirewallButton.isEnabled = true
        thread(name = "airmirror-onboarding-firewall", isDaemon = true) {
            val result = runCatching { queryFirewall(script) }.getOrDefault(FirewallResult(0, false, "检查失败"))
            SwingUtilities.invokeLater {
                when {
                    result.rules >= 2 -> {
                        firewallStatus.text = "防火墙已放行 AirMirror"
                        firewallDetail.text = "TCP 41523 与 UDP 41524-41525 的专用网络规则均存在。"
                        state = state.copy(firewallStep = "ok")
                    }
                    !result.admin -> {
                        firewallStatus.text = "需要管理员权限"
                        firewallDetail.text = "让管理员运行 scripts\\fix-firewall.ps1，或点击右侧按钮走 UAC。"
                        state = state.copy(firewallStep = "no_admin")
                    }
                    else -> {
                        firewallStatus.text = "防火墙尚未放行 AirMirror"
                        firewallDetail.text = result.message.ifBlank { "缺少专用网络入站规则；运行修复脚本后重试。" }
                        state = state.copy(firewallStep = "blocked")
                    }
                }
                store.save(state)
            }
        }
    }

    private data class FirewallResult(val rules: Int, val admin: Boolean, val message: String)

    private fun queryFirewall(script: Path): FirewallResult {
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            script.toString(),
            "-Check",
        ).redirectErrorStream(true).start()
        process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
        if (process.isAlive) process.destroyForcibly()
        val output = process.inputStream.bufferedReader().readText()
        val rules = Regex("RULES=(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val admin = Regex("ADMIN=(true|false)", RegexOption.IGNORE_CASE).find(output)
            ?.groupValues?.get(1)?.equals("true", true) == true
        return FirewallResult(rules, admin, output.lineSequence().firstOrNull { it.startsWith("DETAIL=") }
            ?.removePrefix("DETAIL=").orEmpty())
    }

    private fun runFirewallFixer() {
        val script = scriptDir.resolve("fix-firewall.ps1")
        if (!Files.isRegularFile(script)) {
            firewallDetail.text = "修复脚本不存在：$script"
            return
        }
        val escaped = script.toString().replace("'", "''")
        val command = "Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList " +
            "@('-NoProfile','-ExecutionPolicy','Bypass','-File','$escaped')"
        runCatching {
            ProcessBuilder("powershell.exe", "-NoProfile", "-Command", command).start()
            firewallStatus.text = "等待管理员确认…"
            thread(name = "airmirror-onboarding-firewall-wait", isDaemon = true) {
                Thread.sleep(2500)
                SwingUtilities.invokeLater { checkFirewall() }
            }
        }.onFailure {
            firewallDetail.text = "无法启动管理员 PowerShell：${it.message}"
        }
    }

    private fun startDiscovery() {
        if (discovery != null) return
        deviceBox.model = DefaultComboBoxModel(arrayOf(DeviceItem(null)))
        discoveryStartedAt = System.nanoTime()
        val scanner = MdnsDiscovery(
            onFound = { device ->
                SwingUtilities.invokeLater {
                    val model = deviceBox.model as DefaultComboBoxModel<DeviceItem>
                    val exists = (0 until model.size).any { model.getElementAt(it).device?.fingerprint == device.fingerprint }
                    if (!exists) model.addElement(DeviceItem(device))
                    if (deviceBox.selectedIndex <= 0) deviceBox.selectedIndex = model.size - 1
                    discoveryStatus.text = "找到 ${model.size - 1} 台电视"
                    discoveryDetail.text = "选择要投屏的电视；多台时必须手动选择。"
                }
            },
            onLost = { fingerprint ->
                SwingUtilities.invokeLater {
                    val model = deviceBox.model as DefaultComboBoxModel<DeviceItem>
                    for (i in model.size - 1 downTo 1) {
                        if (model.getElementAt(i).device?.fingerprint == fingerprint) model.removeElementAt(i)
                    }
                }
            },
            onError = { message ->
                SwingUtilities.invokeLater { discoveryDetail.text = "发现异常：$message" }
            },
        )
        discovery = scanner
        scanner.start()
        discoveryTimer = Timer(500) {
            val seconds = (System.nanoTime() - discoveryStartedAt) / 1_000_000_000.0
            if ((deviceBox.model as DefaultComboBoxModel<DeviceItem>).size <= 1) {
                discoveryStatus.text = "正在同一网络里找电视… ${"%.1f".format(seconds)}s"
                if (seconds >= 3.0) {
                    discoveryDetail.text = "3 秒内没有结果：确认电视在同一 Wi-Fi，或手动填 IP。"
                }
            }
        }.apply { start() }
    }

    private fun stopDiscovery() {
        discoveryTimer?.stop()
        discoveryTimer = null
        discovery?.close()
        discovery = null
    }

    private fun selectedDevice(): MdnsDiscovery.Device? =
        (deviceBox.selectedItem as? DeviceItem)?.device

    private fun applyTarget(host: String, port: Int, name: String, pairId: String) {
        targetHost = host
        targetPort = port
        targetName = name
        targetPairId = pairId
        updateSummary()
    }

    private fun updateSummary() {
        if (targetHost.isBlank()) {
            summaryLabel.text = "尚未选择电视；返回上一步选择设备或填写 IP。"
        } else {
            summaryLabel.text = "目标：${targetName.ifBlank { targetHost }}  $targetHost:$targetPort"
        }
    }

    private fun scanQr() {
        QrScanDialog(ownerFrame) { link ->
            SwingUtilities.invokeLater {
                applyTarget(
                    link.host.ifBlank { targetHost },
                    link.port,
                    link.name,
                    link.deviceId,
                )
                codeField.text = link.token
                if (link.host.isNotBlank()) {
                    connectStatus.text = "已识别二维码：${link.name.ifBlank { link.host }}"
                } else {
                    connectStatus.text = "二维码没有地址，请手动填写 IP。"
                }
            }
        }.isVisible = true
    }

    private fun onNext() {
        when (currentStep) {
            1 -> goTo(2)
            2 -> goTo(3)
            3 -> {
                val device = selectedDevice()
                val manual = manualHost.text.trim()
                if (device != null) {
                    applyTarget(device.host, device.port, device.name, device.fingerprint)
                    goTo(4)
                } else if (manual.isNotEmpty()) {
                    val port = manualPort.text.trim().toIntOrNull()
                    if (port == null || port !in 1..65_535) {
                        discoveryDetail.text = "端口必须是 1-65535。"
                        return
                    }
                    applyTarget(manual, port, "手动地址", "")
                    goTo(4)
                } else {
                    discoveryDetail.text = "先选择一台电视，或填写 IP 和端口。"
                }
            }
            4 -> {
                if (targetHost.isBlank()) {
                    connectStatus.text = "请先返回上一步选择电视或填写 IP。"
                    return
                }
                val code = codeField.text.trim()
                if (code.isEmpty()) {
                    connectStatus.text = "请输入电视上的 6 位投屏码，或扫码。"
                    return
                }
                store.markConnection(targetPairId, targetName.ifBlank { targetHost }, targetHost, targetPort)
                onConnect(targetHost, targetPort, code, targetPairId)
                dispose()
            }
        }
    }

    private fun skip() {
        store.markSkipped()
        dispose()
    }

    override fun dispose() {
        stopDiscovery()
        super.dispose()
    }

    companion object {
        fun defaultScriptDir(): Path = Paths.get(System.getProperty("user.dir"), "scripts")
    }
}
