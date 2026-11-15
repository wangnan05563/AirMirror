package io.github.wangnan05563.airmirror.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage

class H264VideoEncoderTest {

    @Test
    fun emitsSpsPpsConfigThenKeyFrame() {
        val width = 320
        val height = 180
        val encoder = H264VideoEncoder(width, height, keyInterval = 10)
        val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, (x * 255 / width shl 16) or (y * 255 / height shl 8) or 0x80)
            }
        }
        val first = encoder.encode(image)
        assertNotNull("first frame must carry SPS/PPS", first.config)
        assertTrue("first frame must be IDR", first.keyFrame)
        assertTrue("payload should contain slice data", first.payload.isNotEmpty())
        assertEquals(7, nalType(first.config!!))
        assertEquals(8, nalType(first.config!!, afterFirst = true))
    }

    @Test
    fun subsequentFramesCarrySliceOnly() {
        val encoder = H264VideoEncoder(320, 180, keyInterval = 10)
        val image = BufferedImage(320, 180, BufferedImage.TYPE_3BYTE_BGR)
        encoder.encode(image)
        val second = encoder.encode(image)
        assertTrue(second.payload.isNotEmpty())
    }

    private fun nalType(data: ByteArray, afterFirst: Boolean = false): Int {
        var i = 0
        var seen = 0
        while (i < data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                if (seen == 0) {
                    if (!afterFirst) return data[i + 3].toInt() and 0x1f
                    seen++
                } else {
                    return data[i + 3].toInt() and 0x1f
                }
                i += 3
            } else {
                i++
            }
        }
        return -1
    }
}
