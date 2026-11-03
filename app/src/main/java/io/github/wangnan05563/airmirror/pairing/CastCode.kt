package io.github.wangnan05563.airmirror.pairing

import java.security.SecureRandom

/**
 * FS-03 投屏码：6 位纯数字、60 秒轮换、旧码轮换后 10 秒宽限、成功一次即失效、
 * 60 秒内错满 5 次锁定 60 秒。全部判定在本地完成，不产生任何网络请求。
 *
 * 时钟与随机数由外部注入，便于在 JVM 单测里精确复现轮换与限流窗口。
 */
class CastCode(
    private val now: () -> Long,
    private val random: () -> Int = { SECURE_RANDOM.nextInt(CODE_SPACE) },
    private val tokenSource: () -> String = { SECURE_RANDOM.nextHex(TOKEN_LENGTH / 2) },
    private val ttlMillis: Long = DEFAULT_TTL,
    private val graceMillis: Long = DEFAULT_GRACE,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val attemptWindowMillis: Long = DEFAULT_WINDOW,
    private val lockMillis: Long = DEFAULT_LOCK,
) {
    enum class State { SHOWING, LOCKED }

    sealed interface Outcome {
        /** 码或扫码令牌校验通过，调用方据此进入 PAIRING。 */
        object Accepted : Outcome

        /** 屏幕上这个码已经被用过，属于「重新读取当前码」而非「码错」。 */
        object Expired : Outcome

        data class Rejected(val remainingAttempts: Int) : Outcome

        data class Locked(val secondsLeft: Int) : Outcome
    }

    private class Entry(val code: String, val token: String, val issuedAt: Long) {
        var used = false
    }

    private var current: Entry? = null
    private var previous: Entry? = null
    private var lockedUntil = NO_LOCK
    private val failures = ArrayList<Long>()

    fun state(): State {
        advance(now())
        return if (now() < lockedUntil) State.LOCKED else State.SHOWING
    }

    /** 接收端待连接页展示的码；到期自动换码，倒计时不因输入而暂停。 */
    fun code(): String {
        advance(now())
        return ensureEntry(now()).code
    }

    /** 二维码里的一次性令牌：与投屏码同生同灭，截图后不能长期复用。 */
    fun token(): String {
        advance(now())
        return ensureEntry(now()).token
    }

    /** 扫码路径校验令牌而非码，一次性语义与码完全一致。 */
    fun submitToken(value: String): Outcome {
        val t = now()
        advance(t)
        if (t < lockedUntil) return Outcome.Locked(secondsLeft = ceilSeconds(lockedUntil - t))
        val entry = ensureEntry(t)
        val match = entry.takeIf { it.token == value }
            ?: previous?.takeIf { it.token == value && t - it.issuedAt < ttlMillis + graceMillis }
        if (match == null) return if (value.isBlank()) Outcome.Rejected(remainingAttempts()) else reject(t)
        if (match.used) return Outcome.Expired
        consume(match)
        return Outcome.Accepted
    }

    fun secondsUntilRotation(): Int {
        val issued = current?.issuedAt ?: return 0
        return ceilSeconds(issued + ttlMillis - now())
    }

    fun submit(input: String): Outcome {
        val t = now()
        advance(t)
        if (t < lockedUntil) return Outcome.Locked(secondsLeft = ceilSeconds(lockedUntil - t))
        val entry = ensureEntry(t)
        val digits = input.filter(Char::isDigit)
        val match = entry.takeIf { it.code == digits }
            ?: previous?.takeIf { it.code == digits && t - it.issuedAt < ttlMillis + graceMillis }
        if (match != null) {
            if (match.used) return Outcome.Expired
            consume(match)
            return Outcome.Accepted
        }
        if (digits.length != CODE_LENGTH) return Outcome.Rejected(remainingAttempts())
        return reject(t)
    }

    /** 一次性授权成功后作废当前码，宽限期内仍能认出它是「已用过的旧码」而不是「输错了」。 */
    private fun consume(match: Entry) {
        match.used = true
        failures.clear()
        previous = match
        current = null
    }

    private fun reject(t: Long): Outcome {
        failures.add(t)
        failures.removeAll { t - it >= attemptWindowMillis }
        if (failures.size >= maxAttempts) {
            lockedUntil = t + lockMillis
            current = null
            previous = null
            return Outcome.Locked(secondsLeft = ceilSeconds(lockMillis))
        }
        return Outcome.Rejected(remainingAttempts())
    }

    private fun remainingAttempts() = maxOf(0, maxAttempts - failures.size)

    private fun newEntry(t: Long) = Entry(formatCode(random()), tokenSource(), t)

    /** 校验凭据时也要有码可对比：界面还没刷过屏时按当前时刻生成，否则会误判成「码错」。 */
    private fun ensureEntry(t: Long): Entry = current ?: newEntry(t).also { current = it }

    private fun advance(t: Long) {
        if (lockedUntil != NO_LOCK && t >= lockedUntil) {
            lockedUntil = NO_LOCK
            failures.clear()
            current = null
            previous = null
        }
        val entry = current
        if (entry != null && t - entry.issuedAt >= ttlMillis) {
            previous = entry
            current = null
        }
        val stale = previous
        if (stale != null && t - stale.issuedAt >= ttlMillis + graceMillis) previous = null
    }

    companion object {
        const val CODE_LENGTH = 6
        const val TOKEN_LENGTH = 16
        private const val CODE_SPACE = 1_000_000
        private const val NO_LOCK = Long.MIN_VALUE
        private const val DEFAULT_TTL = 60_000L
        private const val DEFAULT_GRACE = 10_000L
        private const val DEFAULT_MAX_ATTEMPTS = 5
        private const val DEFAULT_WINDOW = 60_000L
        private const val DEFAULT_LOCK = 60_000L
        private val SECURE_RANDOM = SecureRandom()

        /** 补零到 6 位：000137 这类码必须原样展示，否则两端对齐不上。 */
        fun formatCode(value: Int): String {
            val digits = (value % CODE_SPACE).toString()
            return "0".repeat(CODE_LENGTH - digits.length) + digits
        }
    }
}

private fun SecureRandom.nextHex(bytes: Int): String {
    val buffer = ByteArray(bytes)
    nextBytes(buffer)
    return buffer.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/** 倒计时按秒向上取整：显示 0 秒时锁其实还没到期，避免最后一秒被读成已结束。 */
private fun ceilSeconds(millis: Long): Int {
    if (millis <= 0L) return 0
    val seconds = (millis + 999L) / 1000L
    return if (seconds > Int.MAX_VALUE) Int.MAX_VALUE else seconds.toInt()
}
