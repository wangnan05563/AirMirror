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
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

private data class Preset(val label: String, val width: Int, val height: Int, val fps: Int) {
    override fun toString(): String = label
}

class PcSenderFrame : JFrame("AirMirror PC 发送端") {
    private val hostField = JTextField("127.0.0.1", 14)
    private val portField = JTextField("41523", 6)
    private val codeField = JTextField(6)
    private val nameField = JTextField("AirMirror PC", 12)
    private val presetBox = JComboBox(
        arrayOf(
            Preset("1920x1080 @ 30fps", 1920, 1080, 30),
            Preset("1280x720 @ 30fps", 1280, 720, 30),
            Preset("960x540 @ 15fps", 960, 540, 15),
        ),
    )
    private val startButton = JButton("开始投屏")
    private val stopButton = JButton("停止").apply { isEnabled = false }
    private val statusLabel = JLabel("未连接")
    private val statsLabel = JLabel(" ")
    private val logArea = JTextArea(8, 48).apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }
    private var engine: SenderEngine? = null

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
        add("接收端 IP", hostField)
        add("端口", portField)
        add("投屏码", codeField)
        add("设备名", nameField)
        add("画质", presetBox)

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
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
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) = stop()
        })

        pack()
        minimumSize = Dimension(560, 360)
        setLocationRelativeTo(null)
    }

    private fun appendLog(line: String) {
        SwingUtilities.invokeLater {
            logArea.append(line + "\n")
            logArea.caretPosition = logArea.document.length
        }
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
        startButton.isEnabled = false
        stopButton.isEnabled = true
        statusLabel.text = "连接中…"
        statsLabel.text = " "
        appendLog("开始：$host:$port  ${preset.label}  code=$code")
        val sender = SenderEngine(
            SenderEngine.Config(
                host = host,
                port = port,
                code = code,
                deviceName = nameField.text.trim().ifEmpty { "AirMirror PC" },
                width = preset.width,
                height = preset.height,
                fps = preset.fps,
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
        if (args.isNotEmpty()) {
            runCli(args)
            return
        }
        SwingUtilities.invokeLater { PcSenderFrame().isVisible = true }
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
        val sender = SenderEngine(
            SenderEngine.Config(host, port, code, name, width, height, fps, bitrate),
            onStatus = { println("[status] $it") },
            onStats = { println("[stats] frames=${it.frames} fps=${"%.1f".format(it.fps)} encode=${it.encodeMs}ms") },
        )
        sender.start()
        Runtime.getRuntime().addShutdownHook(Thread { sender.stop() })
        Thread.sleep(Long.MAX_VALUE)
    }
}
