package io.github.wangnan05563.airmirror.pairing

import java.security.SecureRandom
import java.util.Locale

/**
 * T06114：电视端 4 位 PIN。PIN 与失败计数、锁定截止时间、来源掩码一起落盘，
 * 进程重启后锁定状态不会归零；只有真正输对一次才清失败账。
 *
 * 4 位 PIN 不是投屏码：投屏码每 60 秒轮换、只受理一次；PIN 是电视上的固定共享口令，
 * 只用于 PIN 模式，成功也不写可信列表。密码模式按 PRD 8.1 决议不进 v2.0。
 */
class PinCode(
    private val load: () -> String,
    private val save: (String) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val random: (Int) -> Int = { bound -> SECURE_RANDOM.nextInt(bound) },
    private val lockMillis: Long = DEFAULT_LOCK,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    data class Record(
        val pin: String,
        val failures: Int,
        val lockedUntil: Long,
        val peerMask: String,
    )

    enum class State { SHOWING, LOCKED }

    sealed interface Outcome {
        object Accepted : Outcome
        data class Rejected(val remainingAttempts: Int) : Outcome
        data class Locked(val secondsLeft: Int) : Outcome
    }

    private var record: Record? = null

    /** 电视待连接页展示的 PIN；首次启动生成并落盘，之后跨重启稳定。 */
    @Synchronized
    fun pin(): String = advance().pin

    @Synchronized
    fun state(): State = if (advance().lockedUntil > now()) State.LOCKED else State.SHOWING

    @Synchronized
    fun secondsLeft(): Int = seconds(advance().lockedUntil - now())

    @Synchronized
    fun remainingAttempts(): Int = (maxAttempts - advance().failures).coerceAtLeast(0)

    /** 最近一次错误来源的掩码，供锁定态展示；不保存原始 IP。 */
    @Synchronized
    fun peerMask(): String = advance().peerMask

    /**
     * 提交 4 位 PIN。位数不对不烧次数；连续错满 5 次锁 [lockMillis]，
     * 锁定期内连正确 PIN 也不认。成功清失败账并解除锁定。
     */
    @Synchronized
    fun submit(input: String, peer: String = ""): Outcome {
        val current = advance()
        val t = now()
        if (current.lockedUntil > t) {
            return Outcome.Locked(seconds(current.lockedUntil - t))
        }
        val digits = input.filter(Char::isDigit)
        if (digits.length != PIN_LENGTH) {
            return Outcome.Rejected(remainingAttempts = (maxAttempts - current.failures).coerceAtLeast(0))
        }
        if (digits == current.pin) {
            persist(current.copy(failures = 0, lockedUntil = 0L, peerMask = ""))
            return Outcome.Accepted
        }
        val failures = current.failures + 1
        val mask = PeerMask.mask(peer).ifEmpty { current.peerMask }
        val lockedUntil = if (failures >= maxAttempts) t + lockMillis else 0L
        persist(current.copy(failures = failures, lockedUntil = lockedUntil, peerMask = mask))
        return if (lockedUntil > 0L) {
            Outcome.Locked(seconds(lockMillis))
        } else {
            Outcome.Rejected(remainingAttempts = (maxAttempts - failures).coerceAtLeast(0))
        }
    }

    /** 机主在电视上用遥控器改 PIN：物理在场，允许同时清掉失败账与锁定。 */
    @Synchronized
    fun setPin(input: String): Boolean {
        val digits = input.filter(Char::isDigit)
        if (digits.length != PIN_LENGTH || digits != input.trim()) return false
        advance()
        persist(Record(pin = digits, failures = 0, lockedUntil = 0L, peerMask = ""))
        return true
    }

    private fun advance(): Record {
        val current = record ?: restore()
        val t = now()
        if (current.lockedUntil > 0L && t >= current.lockedUntil) {
            val open = current.copy(failures = 0, lockedUntil = 0L)
            persist(open)
            return open
        }
        return current
    }

    private fun restore(): Record {
        val parsed = PinStateCodec.decode(load())
        if (parsed != null) {
            record = parsed
            return parsed
        }
        // 空表或坏表都生成新 PIN：坏表不能沿用一个可能被读出来的旧值。
        val fresh = Record(newPin(), failures = 0, lockedUntil = 0L, peerMask = "")
        persist(fresh)
        return fresh
    }

    private fun persist(next: Record) {
        record = next
        save(PinStateCodec.encode(next))
    }

    private fun newPin(): String =
        String.format(Locale.US, "%04d", random(PIN_SPACE).coerceIn(0, PIN_SPACE - 1))

    private fun seconds(millis: Long): Int {
        if (millis <= 0L) return 0
        val value = (millis + 999L) / 1000L
        return if (value > Int.MAX_VALUE) Int.MAX_VALUE else value.toInt()
    }

    companion object {
        const val PIN_LENGTH = 4
        private const val PIN_SPACE = 10_000
        private const val DEFAULT_LOCK = 60_000L
        private const val DEFAULT_MAX_ATTEMPTS = 5
        private val SECURE_RANDOM = SecureRandom()
    }
}

/** PIN 落盘编码：一行版本头 + 一行四列；坏行/未知头一律返回 null，由 [PinCode] 重新生成。 */
object PinStateCodec {
    const val HEADER = "# AirMirror PIN v1"

    fun encode(record: PinCode.Record): String =
        "$HEADER\n${record.pin}\t${record.failures}\t${record.lockedUntil}\t${record.peerMask}"

    fun decode(text: String): PinCode.Record? {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size != 2 || lines[0] != HEADER) return null
        val parts = lines[1].split('\t')
        if (parts.size != 4) return null
        val pin = parts[0]
        val failures = parts[1].toIntOrNull()
        val lockedUntil = parts[2].toLongOrNull()
        val peerMask = parts[3]
        if (pin.length != PinCode.PIN_LENGTH || !pin.all(Char::isDigit)) return null
        if (failures == null || failures !in 0..5) return null
        if (lockedUntil == null || lockedUntil < 0L) return null
        if (peerMask.any { it == '\t' || it == '\n' }) return null
        return PinCode.Record(pin, failures, lockedUntil, peerMask)
    }
}
