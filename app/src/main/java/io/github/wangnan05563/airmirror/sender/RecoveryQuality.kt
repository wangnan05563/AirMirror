package io.github.wangnan05563.airmirror.sender

import io.github.wangnan05563.airmirror.device.QualityTier
import io.github.wangnan05563.airmirror.device.QualityTiers

/**
 * FS-10 恢复画质：重连成功后先按原档位低一档起步，10 秒后回到协商档位。
 *
 * [tick] 只在到点时返回一次 true，发送循环据此重建编码器，避免每帧都重复重建。
 */
class RecoveryQuality(
    private val now: () -> Long = System::currentTimeMillis,
    private val restoreMillis: Long = RESTORE_MS,
) {
    private var fullTier: QualityTier = QualityTier.OFF
    private var startedAt = 0L
    private var recovering = false
    private var restored = true

    val active: Boolean get() = recovering && !restored

    fun start(full: QualityTier) {
        fullTier = full
        startedAt = now()
        recovering = true
        restored = false
    }

    fun stop() {
        recovering = false
        restored = true
    }

    /** 当前该用的档位：恢复期低一档，其余时候就是协商档。 */
    fun tier(): QualityTier = if (active) QualityTiers.downgrade(fullTier) else fullTier

    /** 10 秒到点返回一次 true，表示该回档了。 */
    fun tick(): Boolean {
        if (!active || now() - startedAt < restoreMillis) return false
        restored = true
        return true
    }

    fun fullTier(): QualityTier = fullTier

    /** 界面文案：让用户知道现在是低一档，且多久能回到原档。 */
    fun text(): String = if (active) {
        "已恢复投屏 · ${tier().label}，10 秒内回到 ${fullTier.label}"
    } else {
        "已恢复投屏"
    }

    companion object {
        const val RESTORE_MS = 10_000L
    }
}