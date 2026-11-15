package io.github.wangnan05563.airmirror.pc

import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage

/** 屏幕采集：首版用 AWT Robot（无需 native 依赖）；后续替换 Windows Graphics Capture。 */
class ScreenCapture(
    private val targetWidth: Int,
    private val targetHeight: Int,
) {
    private val robot = Robot()
    private val bounds: Rectangle = unionOfScreens()
    private val frame = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_3BYTE_BGR)
    private val graphics: Graphics2D = frame.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
    }

    val sourceBounds: Rectangle get() = bounds

    fun capture(): BufferedImage {
        val screen = robot.createScreenCapture(bounds)
        graphics.drawImage(screen, 0, 0, targetWidth, targetHeight, null)
        return frame
    }

    fun close() {
        graphics.dispose()
    }

    private fun unionOfScreens(): Rectangle {
        val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        if (devices.isEmpty()) return Rectangle(0, 0, 1920, 1080)
        var union = devices[0].defaultConfiguration.bounds
        for (device in devices.drop(1)) union = union.union(device.defaultConfiguration.bounds)
        return union
    }
}
