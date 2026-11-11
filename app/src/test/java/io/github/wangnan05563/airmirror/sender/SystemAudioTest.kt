package io.github.wangnan05563.airmirror.sender

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T04487 第 2 条（Android 11 以下采不到声音）的判定层：真机只能确认系统行为，
 * 「哪一版开始算开放」这条边界必须由单测锁住，否则改常量时没人发现。
 */
class SystemAudioTest {

    @Test
    fun `capable only from API 30`() {
        assertFalse("API 29 及以下系统没有 AudioPlaybackCapture", SystemAudio.capable(29))
        assertTrue("API 30 是分界点", SystemAudio.capable(30))
        assertTrue("更高版本同样开放", SystemAudio.capable(34))
    }
}
