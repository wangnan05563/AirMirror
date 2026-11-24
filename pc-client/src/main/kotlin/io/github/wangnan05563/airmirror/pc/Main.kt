package io.github.wangnan05563.airmirror.pc

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

private data class Preset(val label: String, val width: Int, val height: Int, val fps: Int) {
    override fun toString(): String = label
}

private data class DeviceChoice(val device: MdnsDiscovery.Device?, val label: String) {
    override fun toString(): String = label
}

private data class PairedChoice(val device: PairedDeviceStore.Device?, val label: String) {
    override fun toString(): String = label
}

class PcSenderFrame : JFrame("AirMirror PC 发送端") {
    private val hostField = JTextField("127.0.0.1", 14)
    private val portField = JTextField("41523", 6)
    private val codeField = JTextField(6)
    private val nameField = JTextField("AirMirror PC", 12)
    private val deviceBox = JComboBox<DeviceChoice>()
    private val refreshButton = JButton("刷新发现")
    private val pairedBox = JComboBox<PairedChoice>()
    private val forgetButton = JButton("忘记设备")
    private val scanButton = JButton("扫码配对")
    private val wizardButton = JButton("设置向导")
    private val presetBox = JComboBox(
        arrayOf(
            Preset("1920x1080 @ 30fps", 1920, 1080, 30),
            Preset("1280x720 @ 30fps", 1280, 720, 30),
            Preset("960x540 @ 15fps", 960, 540, 15),
        ),
    )
    private val startButton = JButton("开始投屏")
    private val stopButton = JButton("停止").apply { isEnabled = false }
    private val audioBox = JCheckBox("投送系统声音", true)
    private val volumeSlider = JSlider(0, 100, 100).apply {
        preferredSize = Dimension(140, 20)
        toolTipText = "发送增益 0–100；不改变本机与电视的系统音量"
    }
    private val volumeLabel = JLabel("音量 100%")
    private val statusLabel = JLabel("未连接")
    private val statsLabel = JLabel(" ")
    private val logArea = JTextArea(8, 48).apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }
    private var engine: SenderEngine? = null
    private val foundDevices = LinkedHashMap<String, MdnsDiscovery.Device>()
    private var updatingDevices = false
    private val pairedStore = PairedDeviceStore()
    private val onboardingStore = OnboardingStore()
    private var wizard: OnboardingWizard? = null
    private var pairedDevices: List<PairedDeviceStore.Device> = pairedStore.load()
    private var updatingPaired = false
    /** 扫码带出的接收端设备标识；授权成功后与地址一起写入最近设备。 */
    private var pendingPairId = ""
    private val discovery = MdnsDiscovery(
        onFound = { device -> SwingUtilities.invokeLater { onDeviceFound(device) } },
        onLost = { fingerprint -> SwingUtilities.invokeLater { onDeviceLost(fingerprint) } },
        onError = { message -> appendLog("发现异常：$message") },
    )

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        contentPane.layout = BorderLayout(10, 10)
        (rootPane as javax.swing.JComponent).border =
            BorderFactory.createEmptyBorder(12, 12, 12, 12)

        val form = JPanel(GridBagLayout())
        var row = 0
        fun add(label: String, field: java.awt.Component) {
            val c = GridBagConstraints()
            c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.WEST
            c.insets = Insets(4, 0, 4, 8)
            form.add(JLabel(label), c)
            c.gridx = 1; c.weightx = 1.0; c.fill = GridBagConstraints.HORIZONTAL
            form.add(field, c)
            row++
        }
        add("局域网设备", JPanel(BorderLayout(8, 0)).apply {
            add(deviceBox, BorderLayout.CENTER)
            add(refreshButton, BorderLayout.EAST)
        })
        add("最近设备", JPanel(BorderLayout(8, 0)).apply {
            add(pairedBox, BorderLayout.CENTER)
            add(forgetButton, BorderLayout.EAST)
        })
        add("接收端 IP", hostField)
        add("端口", portField)
        add("投屏码", codeField)
        add("设备名", nameField)
        add("画质", presetBox)
        add("声音", JPanel(BorderLayout(8, 0)).apply {
            add(audioBox, BorderLayout.WEST)
            add(volumeSlider, BorderLayout.CENTER)
            add(volumeLabel, BorderLayout.EAST)
        })

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        actions.add(scanButton)
        actions.add(wizardButton)
        actions.add(startButton)
        actions.add(stopButton)

        val top = JPanel(BorderLayout(0, 8))
        top.add(form, BorderLayout.CENTER)
        top.add(actions, BorderLayout.SOUTH)

        val bottom = JPanel(BorderLayout())
        bottom.add(statusLabel, BorderLayout.WEST)
        bottom.add(statsLabel, BorderLayout.EAST)

        contentPane.add(top, BorderLayout.NORTH)
        contentPane.add(JScrollPane(logArea), BorderLayout.CENTER)
        contentPane.add(bottom, BorderLayout.SOUTH)

        startButton.addActionListener { start() }
        stopButton.addActionListener { stop() }
        scanButton.addActionListener { scanPairing() }
        wizardButton.addActionListener { openWizard() }
        forgetButton.addActionListener { forgetSelectedDevice() }
        refreshButton.addActionListener {
            statusLabel.text = "正在搜索局域网接收端…"
            discovery.queryNow()
        }
        audioBox.addActionListener { engine?.setAudioEnabled(audioBox.isSelected) }
        volumeSlider.addChangeListener {
            volumeLabel.text = "音量 ${volumeSlider.value}%"
            engine?.setVolume(volumeSlider.value)
        }
        deviceBox.addActionListener {
            if (updatingDevices) return@addActionListener
            val device = (deviceBox.selectedItem as? DeviceChoice)?.device ?: return@addActionListener
            hostField.text = device.host
            portField.text = device.port.toString()
            statusLabel.text = "已选择 ${device.name}"
        }
        pairedBox.addActionListener {
            if (updatingPaired) return@addActionListener
            val device = (pairedBox.selectedItem as? PairedChoice)?.device ?: return@addActionListener
            hostField.text = device.host
            portField.text = device.port.toString()
            // 最近设备只记地址，不保存一次性令牌；连接前必须重新扫码或输入当前投屏码。
            codeField.text = ""
            pendingPairId = device.deviceId
            statusLabel.text = "已选择最近设备 ${device.name}，请输入当前投屏码或扫码"
        }
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) {
                stop()
                discovery.close()
            }
        })

        rebuildDeviceChoices()
        rebuildPairedChoices()
        pack()
        minimumSize = Dimension(600, 400)
        setLocationRelativeTo(null)
        discovery.start()
    }

    private fun appendLog(line: String) {
        SwingUtilities.invokeLater {
            logArea.append(line + "\n")
            logArea.caretPosition = logArea.document.length
        }
    }

    private fun onDeviceFound(device: MdnsDiscovery.Device) {
        foundDevices[device.fingerprint] = device
        appendLog("发现接收端：${device.name}  ${device.host}:${device.port}")
        rebuildDeviceChoices()
    }

    private fun onDeviceLost(fingerprint: String) {
        val removed = foundDevices.remove(fingerprint) ?: return
        appendLog("接收端离线：${removed.name}")
        rebuildDeviceChoices()
    }

    private fun rebuildDeviceChoices() {
        val selectedFingerprint = (deviceBox.selectedItem as? DeviceChoice)?.device?.fingerprint
        updatingDevices = true
        deviceBox.removeAllItems()
        val placeholder = if (foundDevices.isEmpty()) {
            "未发现接收端，可手动输入 IP"
        } else {
            "选择发现的接收端（${foundDevices.size}）"
        }
        deviceBox.addItem(DeviceChoice(null, placeholder))
        foundDevices.values.forEach { device ->
            val label = "${device.name}  ${device.host}:${device.port}"
            deviceBox.addItem(DeviceChoice(device, label))
        }
        selectedFingerprint?.let { foundDevices[it] }?.let { device ->
            val label = "${device.name}  ${device.host}:${device.port}"
            deviceBox.selectedItem = DeviceChoice(device, label)
        }
        updatingDevices = false
    }

    private fun scanPairing() {
        QrScanDialog(this) { link -> applyPairingLink(link) }.isVisible = true
    }

    /** 首次启动进入四步向导；用户点“设置向导”时也复用同一入口。 */
    fun showOnboardingIfNeeded() {
        if (onboardingStore.shouldShow()) openWizard()
    }

    private fun openWizard() {
        wizard?.dispose()
        val dialog = OnboardingWizard(this, onboardingStore, onConnect = ::connectFromWizard)
        wizard = dialog
        dialog.isVisible = true
        wizard = null
    }

    private fun connectFromWizard(host: String, port: Int, code: String, pairId: String) {
        hostField.text = host
        portField.text = port.toString()
        codeField.text = code
        pendingPairId = pairId
        start()
    }

    private fun applyPairingLink(link: io.github.wangnan05563.airmirror.pairing.PairingLink.Link) {
        val host = link.host.ifBlank { hostField.text.trim() }
        if (host.isBlank()) {
            statusLabel.text = "二维码里没有接收端地址，请手动填写 IP"
            return
        }
        hostField.text = host
        portField.text = link.port.toString()
        codeField.text = link.token
        pendingPairId = link.deviceId
        statusLabel.text = "已扫码：${link.name.ifBlank { host }}，点击开始投屏"
        appendLog("扫码配对：${link.name.ifBlank { "未命名电视" }}  $host:${link.port}")
    }

    private fun forgetSelectedDevice() {
        val device = (pairedBox.selectedItem as? PairedChoice)?.device
        if (device == null) {
            statusLabel.text = "先在最近设备里选择要忘记的电视"
            return
        }
        val wasCurrent = device.host == hostField.text.trim() && device.port == portField.text.trim().toIntOrNull()
        pairedDevices = pairedStore.forget(device)
        if (wasCurrent) stop()
        rebuildPairedChoices()
        statusLabel.text = "已忘记 ${device.name}"
        appendLog("忘记最近设备：${device.name}  ${device.host}:${device.port}")
    }

    private fun rebuildPairedChoices() {
        val selected = (pairedBox.selectedItem as? PairedChoice)?.device
        updatingPaired = true
        pairedBox.removeAllItems()
        pairedBox.addItem(
            PairedChoice(
                null,
                if (pairedDevices.isEmpty()) "暂无最近设备" else "选择最近设备（${pairedDevices.size}）",
            ),
        )
        pairedDevices.forEach { device ->
            pairedBox.addItem(PairedChoice(device, "${device.name}  ${device.host}:${device.port}"))
        }
        selected?.let { old ->
            pairedDevices.firstOrNull {
                if (old.deviceId.isNotBlank() && it.deviceId.isNotBlank()) it.deviceId == old.deviceId
                else it.host == old.host && it.port == old.port
            }
        }?.let { device ->
            pairedBox.selectedItem = PairedChoice(device, "${device.name}  ${device.host}:${device.port}")
        }
        updatingPaired = false
    }

    private fun start() {
        val host = hostField.text.trim()
        val port = portField.text.trim().toIntOrNull()
        val code = codeField.text.trim()
        val preset = presetBox.selectedItem as Preset
        if (host.isEmpty() || port == null || code.isEmpty()) {
            statusLabel.text = "请填写接收端 IP、端口和投屏码"
            return
        }
        val pairId = pendingPairId
        startButton.isEnabled = false
        stopButton.isEnabled = true
        statusLabel.text = "连接中…"
        statsLabel.text = " "
        appendLog("开始：$host:$port  ${preset.label}  ${if (code.length == 6) "code=******" else "扫码令牌"}")
        var connectedName = "电视"
        val sender = SenderEngine(
            SenderEngine.Config(
                host = host,
                port = port,
                code = code,
                deviceName = nameField.text.trim().ifEmpty { "AirMirror PC" },
                width = preset.width,
                height = preset.height,
                fps = preset.fps,
                audioEnabled = audioBox.isSelected,
                volumePercent = volumeSlider.value,
            ),
            onStatus = { message ->
                SwingUtilities.invokeLater { statusLabel.text = message }
                appendLog(message)
            },
            onStats = { stats ->
                SwingUtilities.invokeLater {
                    statsLabel.text = "帧=${stats.frames}  ${"%.1f".format(stats.fps)}fps  " +
                        (if (stats.encodeMs > 0) "编码=${stats.encodeMs}ms  " else "编码=原生  ") +
                        "${"%.1f".format(stats.bytes / 1024.0 / 1024.0)}MB"
                }
            },
            onAuthorized = { session ->
                connectedName = session.deviceName
                SwingUtilities.invokeLater {
                    val device = PairedDeviceStore.Device(
                        deviceId = pairId,
                        name = session.deviceName,
                        host = session.host,
                        port = session.port,
                        lastUsedAt = System.currentTimeMillis(),
                    )
                    pairedDevices = pairedStore.remember(device)
                    rebuildPairedChoices()
                    pendingPairId = ""
                    // 一次性令牌已消费，清空输入避免下次重连拿旧令牌再撞一次拒绝。
                    codeField.text = ""
                    statusLabel.text = "配对成功：${session.deviceName}，已加入最近设备"
                    appendLog("配对成功并记住：${session.deviceName}  ${session.host}:${session.port}")
                }
            },
            onFirstFrame = {
                if (!onboardingStore.load().completed) {
                    onboardingStore.markCompleted(connectedName, host, port)
                    appendLog("首帧已送达，首次设置完成")
                }
            },
        )
        engine = sender
        sender.start()
    }

    private fun stop() {
        engine?.stop()
        engine = null
        startButton.isEnabled = true
        stopButton.isEnabled = false
        statusLabel.text = "已停止"
    }
}

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--self-check") {
            runSelfCheck()
            return
        }
        if (args.firstOrNull() == "--discover") {
            runDiscovery(args.drop(1))
            return
        }
        if (args.isNotEmpty()) {
            runCli(args)
            return
        }
        SwingUtilities.invokeLater {
            val frame = PcSenderFrame()
            frame.isVisible = true
            frame.showOnboardingIfNeeded()
        }
    }

    /** 安装脚本用的无界面自检：只验证打包进去的运行时与 jar，不启动 UI。 */
    private fun runSelfCheck() {
        try {
            listOf(
                "io.github.wangnan05563.airmirror.protocol.Protocol",
                "io.github.wangnan05563.airmirror.pairing.PairingLink",
                "com.google.zxing.Result",
                "com.github.sarxos.webcam.Webcam",
                "kotlin.Unit",
            ).forEach { Class.forName(it, false, Main::class.java.classLoader) }
            println(
                "AIRMIRROR_PC_SELF_CHECK_OK version=${System.getProperty("java.version")} " +
                    "java_home=${System.getProperty("java.home")} os=${System.getProperty("os.name")} " +
                    "arch=${System.getProperty("os.arch")}",
            )
        } catch (t: Throwable) {
            println("AIRMIRROR_PC_SELF_CHECK_FAIL ${t.javaClass.simpleName}: ${t.message}")
            kotlin.system.exitProcess(1)
        }
    }

    private fun runDiscovery(args: List<String>) {
        val options = args.chunked(2).filter { it.size == 2 }.associate { it[0].removePrefix("--") to it[1] }
        val seconds = options["seconds"]?.toLongOrNull() ?: 5L
        val startedAt = System.nanoTime()
        val firstMs = java.util.concurrent.atomic.AtomicLong(-1)
        val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val discovery = MdnsDiscovery(
            onFound = { device ->
                seen += device.fingerprint
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000
                if (firstMs.compareAndSet(-1, elapsed)) {
                    println("[found] ${device.name} ${device.host}:${device.port} fp=${device.fingerprint} first=${elapsed}ms")
                } else {
                    println("[found] ${device.name} ${device.host}:${device.port} fp=${device.fingerprint}")
                }
            },
            onLost = { println("[lost] $it") },
            onError = { println("[error] $it") },
        )
        println("mDNS 搜索 ${MdnsDiscovery.DEFAULT_SERVICE_TYPE}，最长 ${seconds}s…")
        discovery.start()
        Thread.sleep(seconds * 1_000)
        discovery.stop()
        val first = firstMs.get()
        println("发现 ${seen.size} 台接收端；首台耗时 ${if (first < 0) "未发现" else "${first}ms"}。")
    }

    private fun runCli(args: Array<String>) {
        val options = args.toList().chunked(2)
            .filter { it.size == 2 }
            .associate { it[0].removePrefix("--") to it[1] }
        val host = options["host"] ?: error("--host required")
        val port = options["port"]?.toIntOrNull() ?: 41523
        val code = options["code"] ?: error("--code required")
        val width = options["width"]?.toIntOrNull() ?: 1920
        val height = options["height"]?.toIntOrNull() ?: 1080
        val fps = options["fps"]?.toIntOrNull() ?: 30
        val bitrate = options["bitrate"]?.toIntOrNull() ?: 8000
        val name = options["name"] ?: "AirMirror PC"
        val audio = options["audio"]?.toBooleanStrictOrNull() ?: true
        val volume = options["volume"]?.toIntOrNull() ?: 100
        val sender = SenderEngine(
            SenderEngine.Config(
                host, port, code, name, width, height, fps, bitrate,
                audioEnabled = audio, volumePercent = volume,
            ),
            onStatus = { println("[status] $it") },
            onStats = { println("[stats] frames=${it.frames} fps=${"%.1f".format(it.fps)} encode=${it.encodeMs}ms") },
        )
        sender.start()
        Runtime.getRuntime().addShutdownHook(Thread { sender.stop() })
        Thread.sleep(Long.MAX_VALUE)
    }
}
