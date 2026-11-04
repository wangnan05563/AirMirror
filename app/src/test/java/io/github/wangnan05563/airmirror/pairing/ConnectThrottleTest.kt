package io.github.wangnan05563.airmirror.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PRD 8.9「单设备连接请求频率限制」：60 秒内被拒 6 次起静默，再犯翻倍，最多 15 分钟。
 * 时钟由测试注入，窗口边界逐毫秒断言；这里只验判定，不碰任何 IO。
 */
class ConnectThrottleTest {

    private var clock = 2_000_000L

    private fun newThrottle(trackedKeys: Int = ConnectThrottle.DEFAULT_TRACKED_KEYS): ConnectThrottle =
        ConnectThrottle(now = { clock }, trackedKeys = trackedKeys)

    @Test
    fun sixRefusalsInsideOneWindowStartTheSilentPeriod() {
        val throttle = newThrottle()
        repeat(5) { assertEquals(0L, throttle.refuse(KEY)) }
        assertEquals(0L, throttle.blockedFor(KEY))
        assertEquals(60_000L, throttle.refuse(KEY))
        assertEquals(60_000L, throttle.blockedFor(KEY))
    }

    @Test
    fun otherSourcesAreNotPunishedForThisOnesBehaviour() {
        val throttle = newThrottle()
        repeat(6) { throttle.refuse(KEY) }
        assertEquals(0L, throttle.blockedFor("192.168.1.10"))
        assertEquals(0L, throttle.refuse("192.168.1.10"))
    }

    @Test
    fun slowSteadyRefusalsNeverAccumulateIntoAWindow() {
        val throttle = newThrottle()
        // 每 30 秒被拒一次：窗口里最多同时留 3 笔，手滑重试的真人到不了闸门
        repeat(12) {
            clock += 30_000L
            assertEquals(0L, throttle.refuse(KEY))
        }
        assertEquals(0L, throttle.blockedFor(KEY))
    }

    @Test
    fun silentPeriodLiftsOnItsOwn() {
        val throttle = newThrottle()
        repeat(6) { throttle.refuse(KEY) }
        clock += 59_999L
        assertEquals(1L, throttle.blockedFor(KEY))
        assertEquals(1, throttle.secondsLeft(KEY))
        clock += 1L
        assertEquals(0L, throttle.blockedFor(KEY))
    }

    @Test
    fun knockingDuringTheSilentPeriodDoesNotExtendIt() {
        val throttle = newThrottle()
        repeat(6) { throttle.refuse(KEY) }
        val endsAt = clock + 60_000L
        repeat(3) {
            clock += 10_000L
            assertEquals(endsAt - clock, throttle.refuse(KEY))
        }
        clock = endsAt
        assertEquals(0L, throttle.blockedFor(KEY))
    }

    @Test
    fun repeatOffenderEscalatesAndStopsAtTheCap() {
        val throttle = newThrottle()
        for (seconds in intArrayOf(60, 120, 240, 480, 900, 900)) {
            repeat(5) { throttle.refuse(KEY) }
            assertEquals(seconds * 1_000L, throttle.refuse(KEY))
            // 服刑期间每隔 30 秒继续敲门：这才算「上一轮结束还继续犯」，安静离开的人另有一例
            val endsAt = clock + seconds * 1_000L
            while (clock + 30_000L < endsAt) {
                clock += 30_000L
                assertEquals(endsAt - clock, throttle.refuse(KEY))
            }
            clock = endsAt + 1L
        }
    }

    @Test
    fun offenderThatWentQuietComesBackOnBaseTerms() {
        val throttle = newThrottle()
        repeat(6) { throttle.refuse(KEY) }
        // 一次都不敲，静默到期后又安静满一个窗口：按新犯对待，不该一路顶到 15 分钟
        clock += 60_000L + 60_000L + 1L
        repeat(5) { assertEquals(0L, throttle.refuse(KEY)) }
        assertEquals(60_000L, throttle.refuse(KEY))
    }

    @Test
    fun authorizationClearsTheLedger() {
        val throttle = newThrottle()
        repeat(5) { throttle.refuse(KEY) }
        throttle.allow(KEY)
        assertEquals(0L, throttle.blockedFor(KEY))
        // 之前打错的那几笔不该继续背着：重新数满 6 次才会进静默
        repeat(5) { assertEquals(0L, throttle.refuse(KEY)) }
        assertEquals(60_000L, throttle.refuse(KEY))
    }

    @Test
    fun secondsLeftRoundsUp() {
        val throttle = newThrottle()
        repeat(6) { throttle.refuse(KEY) }
        clock += 58_999L
        assertEquals(1_001L, throttle.blockedFor(KEY))
        assertEquals(2, throttle.secondsLeft(KEY))
    }

    @Test
    fun unknownSourceIsNeverGated() {
        val throttle = newThrottle()
        // 拿不到地址时各笔只能挤成一个空键，宁可放行也不把无关设备捆在一起拦掉
        repeat(20) { throttle.refuse("") }
        assertEquals(0L, throttle.blockedFor(""))
    }

    @Test
    fun rotatingSourcesUnderOneDeviceIdHitTheDeviceGate() {
        val throttle = newThrottle()
        // 攻击者每轮换一个来源：每个来源本身都撞不满闸门，但同一个设备标识累计到 6 笔就该被设备维度拦住
        listOf("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4", "10.0.0.5").forEach { source ->
            assertEquals(0L, throttle.refuse(source))
            assertEquals(0L, throttle.refuse("victim-phone"))
        }
        assertEquals(0L, throttle.refuse("10.0.0.6"))
        assertEquals(60_000L, throttle.refuse("victim-phone"))
        assertEquals(60_000L, throttle.blockedFor("victim-phone"))
        // 换过的每个来源各自只欠一笔，仍不该被误伤
        assertEquals(0L, throttle.blockedFor("10.0.0.1"))
        // 同网段的其他设备完全不受影响
        assertEquals(0L, throttle.blockedFor("192.168.1.30"))
    }

    @Test
    fun successOnEitherDimensionReopensBoth() {
        val throttle = newThrottle()
        repeat(4) { throttle.refuse(KEY) }
        repeat(4) { throttle.refuse("victim-phone") }
        throttle.allow(KEY)
        throttle.allow("victim-phone")
        repeat(5) { assertEquals(0L, throttle.refuse(KEY)) }
        assertEquals(60_000L, throttle.refuse(KEY))
    }

    @Test
    fun ledgerCapKeepsSourcesStillServingTheirTime() {
        val throttle = newThrottle(trackedKeys = 1)
        repeat(6) { throttle.refuse(KEY) }
        clock += 1L
        listOf("10.0.0.2", "10.0.0.3").forEach { throttle.refuse(it) }
        throttle.prune()
        assertEquals(60_000L - 1L, throttle.blockedFor(KEY))
    }

    @Test
    fun ledgerCapDropsIdleSources() {
        val throttle = newThrottle(trackedKeys = 2)
        // 五个来源各欠 5 笔，都没到闸门；超出上限时按最久未出现剪掉前三笔
        listOf("10.0.0.1", "10.0.0.2", "10.0.0.3").forEach { source -> repeat(5) { throttle.refuse(source) } }
        clock += 1L
        listOf("10.0.0.4", "10.0.0.5").forEach { source -> repeat(5) { throttle.refuse(source) } }
        throttle.prune()
        // 最久未出现的那笔已被剪掉，重来一遍只数到 1 笔，撞不满 6 笔
        repeat(5) { assertEquals(0L, throttle.refuse("10.0.0.1")) }
        // 还在账上的那笔补一笔就满 6 次，证明闸门本身没被剪坏
        clock += 1L
        assertEquals(60_000L, throttle.refuse("10.0.0.4"))
    }

    private companion object {
        const val KEY = "192.168.1.9"
    }
}
