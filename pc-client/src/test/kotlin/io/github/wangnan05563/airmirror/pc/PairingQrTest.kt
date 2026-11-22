package io.github.wangnan05563.airmirror.pc

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.github.wangnan05563.airmirror.pairing.PairingLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files

/** T06110：二维码内容、解码与最近设备记忆的 JVM 测试；不依赖摄像头硬件。 */
class PairingQrTest {

    @Test
    fun `二维码图片可还原完整配对链接`() {
        val link = PairingLink.Link("dev-001", "客厅电视", "0123456789abcdef", "192.168.50.223", 41523)
        val image = qrImage(PairingLink.build(link))
        val parsed = QrPairing.decode(image)
        assertEquals(link, parsed)
    }

    @Test
    fun `非 AirMirror 二维码不产生配对结果`() {
        val image = qrImage("https://example.com/not-a-pair")
        assertNull(QrPairing.decode(image))
    }

    @Test
    fun `坏图与空图不抛异常`() {
        assertNull(QrPairing.decode(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)))
    }

    @Test
    fun `最近设备可保存去重并忘记`() {
        val dir = Files.createTempDirectory("airmirror-pairing")
        val file = dir.resolve("paired-devices.tsv")
        val store = PairedDeviceStore(file)
        val first = PairedDeviceStore.Device("dev-001", "客厅电视", "192.168.50.223", 41523, 100L)
        val second = PairedDeviceStore.Device("dev-002", "卧室电视", "192.168.50.224", 41524, 200L)

        store.remember(first)
        store.remember(second)
        assertEquals(listOf("dev-002", "dev-001"), store.load().map { it.deviceId })

        // 同一设备重复配对只更新时间，不产生第二条记录。
        store.remember(first.copy(name = "客厅电视 Pro", lastUsedAt = 300L))
        val afterUpdate = store.load()
        assertEquals(2, afterUpdate.size)
        assertEquals("客厅电视 Pro", afterUpdate.first().name)

        store.forget(first)
        assertEquals(listOf("dev-002"), store.load().map { it.deviceId })
        assertFalse(Files.readString(file).contains("0123456789abcdef"))
    }

    private fun qrImage(text: String): BufferedImage {
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            360,
            360,
            mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                image.setRGB(x, y, if (matrix.get(x, y)) Color.BLACK.rgb else Color.WHITE.rgb)
            }
        }
        return image
    }
}
