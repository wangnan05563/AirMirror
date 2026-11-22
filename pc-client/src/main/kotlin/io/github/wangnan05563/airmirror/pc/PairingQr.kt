package io.github.wangnan05563.airmirror.pc

import com.github.sarxos.webcam.Webcam
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import io.github.wangnan05563.airmirror.pairing.PairingLink
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.concurrent.thread

/** 把二维码图片解成 PairingLink；二维码只承载一次性令牌，解析失败按“不是投屏码”处理。 */
object QrPairing {
    fun decode(image: BufferedImage): PairingLink.Link? =
        QrDecoder.decode(image)?.let(PairingLink::parse)

    fun decode(file: File): PairingLink.Link? =
        runCatching { ImageIO.read(file) }.getOrNull()?.let(::decode)
}

/** ZXing 纯 Java 解码；不使用 javase 模块，避免再引入一层 AWT 依赖。 */
object QrDecoder {
    fun decode(image: BufferedImage): String? {
        if (image.width <= 0 || image.height <= 0) return null
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        val source = RGBLuminanceSource(image.width, image.height, pixels)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
        }
        return runCatching { reader.decode(bitmap).text }.getOrNull()
    }
}

/**
 * PC 已配对设备记忆。只保存连接所需地址和展示名，不保存一次性令牌；
 * 下次连接仍要重新扫码或输入当前投屏码，避免把已作废的令牌当成长期密码。
 */
class PairedDeviceStore(private val file: Path = defaultPath()) {
    data class Device(
        val deviceId: String,
        val name: String,
        val host: String,
        val port: Int,
        val lastUsedAt: Long,
    )

    private val lock = Any()

    fun load(): List<Device> = synchronized(lock) {
        if (!Files.exists(file)) return emptyList()
        runCatching { parse(Files.readAllLines(file)) }.getOrDefault(emptyList())
    }

    fun remember(device: Device): List<Device> = synchronized(lock) {
        val current = load().toMutableList()
        val index = current.indexOfFirst {
            if (device.deviceId.isNotBlank() && it.deviceId.isNotBlank()) it.deviceId == device.deviceId
            else it.host == device.host && it.port == device.port
        }
        if (index >= 0) current.removeAt(index)
        current.add(0, device)
        val trimmed = current.sortedByDescending { it.lastUsedAt }.take(MAX_DEVICES)
        runCatching { persist(trimmed) }
        trimmed
    }

    fun forget(device: Device): List<Device> = synchronized(lock) {
        val current = load().toMutableList()
        current.removeAll {
            if (device.deviceId.isNotBlank() && it.deviceId.isNotBlank()) it.deviceId == device.deviceId
            else it.host == device.host && it.port == device.port
        }
        runCatching { persist(current) }
        current
    }

    private fun parse(lines: List<String>): List<Device> = lines.asSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split('|', limit = 5)
            if (parts.size != 5) return@mapNotNull null
            val id = decodeBase64(parts[0])
            val name = decodeBase64(parts[1])
            val host = parts[2].trim()
            val port = parts[3].toIntOrNull()?.takeIf { it in 1..65_535 } ?: return@mapNotNull null
            val used = parts[4].toLongOrNull() ?: return@mapNotNull null
            if (host.isBlank()) null else Device(id, name, host, port, used)
        }
        .sortedByDescending { it.lastUsedAt }
        .take(MAX_DEVICES)
        .toList()

    private fun persist(devices: List<Device>) {
        val parent = file.parent
        if (parent != null) Files.createDirectories(parent)
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        val text = buildString {
            appendLine("# AirMirror paired devices v1")
            for (device in devices) {
                append(encodeBase64(device.deviceId)).append('|')
                append(encodeBase64(device.name)).append('|')
                append(device.host).append('|')
                append(device.port).append('|')
                append(device.lastUsedAt).appendLine()
            }
        }
        Files.writeString(temp, text, Charsets.UTF_8)
        runCatching {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun encodeBase64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeBase64(value: String): String =
        runCatching {
            String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
        }.getOrDefault("")

    companion object {
        private const val MAX_DEVICES = 20

        fun defaultPath(): Path {
            val appData = System.getenv("APPDATA")
            val root = if (appData.isNullOrBlank()) System.getProperty("user.home") else appData
            return Paths.get(root, "AirMirror", "paired-devices.tsv")
        }
    }
}

/** PC 摄像头取帧；没有摄像头或驱动异常时返回 null，让界面回退到截图导入。 */
class CameraQrScanner : AutoCloseable {
    private val webcam: Webcam? = runCatching { Webcam.getDefault() }.getOrNull()

    val available: Boolean get() = webcam != null

    fun open(): Boolean = runCatching {
        webcam?.open()
        webcam?.isOpen == true
    }.getOrDefault(false)

    fun grab(): BufferedImage? = runCatching { webcam?.image }.getOrNull()

    override fun close() {
        runCatching { webcam?.close() }
    }
}

/**
 * 扫码对话框：默认打开摄像头连续解码；没有摄像头时可直接导入二维码截图。
 * 摄像头帧只在 EDT 的定时器里取，解码失败不会打断预览。
 */
class QrScanDialog(
    owner: Frame,
    private val onDetected: (PairingLink.Link) -> Unit,
) : JDialog(owner, "扫码配对", true) {
    private val preview = JLabel("摄像头启动中…", JLabel.CENTER).apply {
        preferredSize = Dimension(480, 320)
        border = BorderFactory.createLineBorder(java.awt.Color(0x1A3D61))
    }
    private val status = JLabel("把电视上的二维码对准摄像头", JLabel.CENTER)
    private val scanner = CameraQrScanner()
    private var timer: Timer? = null
    private var done = false

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        layout = BorderLayout(10, 10)
        (rootPane as javax.swing.JComponent).border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        add(preview, BorderLayout.CENTER)
        add(status, BorderLayout.NORTH)

        val actions = JPanel(FlowLayout(FlowLayout.CENTER, 8, 0))
        val importButton = JButton("导入二维码截图")
        val cancelButton = JButton("取消")
        actions.add(importButton)
        actions.add(cancelButton)
        add(actions, BorderLayout.SOUTH)

        importButton.addActionListener { importImage() }
        cancelButton.addActionListener { finish() }

        if (!scanner.available) {
            status.text = "没有检测到摄像头，可导入二维码截图"
        } else {
            thread(name = "airmirror-qr-camera-open", isDaemon = true) {
                val opened = scanner.open()
                javax.swing.SwingUtilities.invokeLater {
                    if (!opened) {
                        status.text = "摄像头不可用，可导入二维码截图"
                    } else {
                        preview.text = ""
                        startTimer()
                    }
                }
            }
        }
        pack()
        minimumSize = Dimension(520, 430)
        setLocationRelativeTo(owner)
    }

    private fun startTimer() {
        timer = Timer(250) {
            val image = scanner.grab() ?: return@Timer
            val scaled = image.getScaledInstance(preview.width.coerceAtLeast(1), preview.height.coerceAtLeast(1), java.awt.Image.SCALE_SMOOTH)
            preview.icon = ImageIcon(scaled)
            val link = QrPairing.decode(image)
            if (link != null && !done) {
                done = true
                status.text = "已识别：${link.name.ifBlank { link.host }}"
                timer?.stop()
                scanner.close()
                onDetected(link)
                dispose()
            }
        }.apply { start() }
    }

    private fun importImage() {
        val chooser = JFileChooser().apply {
            dialogTitle = "选择电视二维码截图"
            fileFilter = FileNameExtensionFilter("图片 (*.png, *.jpg, *.jpeg)", "png", "jpg", "jpeg")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val link = QrPairing.decode(chooser.selectedFile)
        if (link == null) {
            status.text = "这张图里没有识别到 AirMirror 配对二维码"
            return
        }
        done = true
        timer?.stop()
        scanner.close()
        onDetected(link)
        dispose()
    }

    private fun finish() {
        done = true
        timer?.stop()
        scanner.close()
        dispose()
    }

    override fun dispose() {
        timer?.stop()
        scanner.close()
        super.dispose()
    }
}
