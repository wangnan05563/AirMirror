package io.github.wangnan05563.airmirror.util

import kotlin.random.Random

/**
 * 首启引导的纯逻辑（PRD §1.2 / §15 验收「2 三步引导，每步可跳过」「首启零阻塞」）。
 *
 * 全部为不依赖 Android 上下文的纯函数，便于 JVM 单测覆盖机型清洗、随机两位与回落分支。
 * 落盘与界面状态机的脏活在 [io.github.wangnan05563.airmirror.pairing.DeviceStore]
 * 与 [io.github.wangnan05563.airmirror.ui.ReceiverActivity] 里。
 */
object Onboarding {

    /** 设备名落盘前上限：过长会在发送端列表里被截断，且投屏码/二维码携带时浪费带宽。 */
    const val MAX_NAME_LEN = 24

    /**
     * 默认设备名 = 机型 + 随机两位。
     * - 机型串先清洗（去非字母数字与中文的字符，过长截断），避免杂牌型号里塞满符号空格。
     * - 清洗后为空（型号为空或仅符号）时回落到「电视」+ 随机两位（PRD §11.1）。
     * - 随机两位范围 00~99；[rng] 可注入以便单测，默认用真随机。
     *
     * 注意：本函数每次调用都会重新取随机，稳定性由调用方（[io.github.wangnan05563.airmirror.pairing.DeviceStore.ensureDefaultDeviceName]）
     * 生成一次后落盘保证，本函数只负责「生成」。
     */
    fun defaultDeviceName(
        model: String,
        rng: () -> Int = { Random.nextInt(0, 100) },
    ): String {
        val clean = model.trim().replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fa5]"), "").take(12)
        val base = if (clean.isEmpty()) "电视" else clean
        val suffix = rng().coerceIn(0, 99).toString().padStart(2, '0')
        return "$base-$suffix"
    }

    /**
     * 设备名落盘前的清洗：去首尾空白、内部连续空白压缩为单空格、超长截断；
     * 清洗后为空（用户没填或只填了空格）时回落到 [fallback]（即默认设备名）。
     */
    fun sanitizeName(raw: String, fallback: String): String {
        val collapsed = raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME_LEN)
        return if (collapsed.isEmpty()) fallback else collapsed
    }
}
