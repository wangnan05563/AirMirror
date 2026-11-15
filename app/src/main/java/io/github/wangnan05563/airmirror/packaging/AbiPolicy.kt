package io.github.wangnan05563.airmirror.packaging

/**
 * PRD 1.1 安装包与 ABI 适配策略单源（纯 JVM，不引用 android.*，便于在单元测试里锁死口径）。
 *
 * 打包侧（2026-11-15 T05734 收敛）：工程不含任何 native 库，per-ABI 包只会产出三份内容完全相同的
 * 重复包（实测三者 SHA-256 一致、lib/ 条目为 0），因此只交付**一个 universal 包**
 * （`AirMirror-<versionName>-universal-<variant>.apk`，全 ABI 通吃，模拟器/开发机也装它）。
 * 将来引入 `.so` 后，按 PRD 1.1 在 [app/build.gradle.kts] 恢复 per-ABI splits 即可再拆双包，
 * 本对象只负责「设备侧 ABI 口径」，不描述打包切分方式。
 *
 * 本对象的常量须与 [app/build.gradle.kts] 的 minSdk / ABI 配置保持一致；
 * [BuildAbiGuardTest] 会以源码守卫方式反向校验。
 */
object AbiPolicy {
    /** 接收端硬下限（Q-5 于 2026-11-12 重签为 22，不得静默回退）。 */
    const val MIN_SDK = 22

    /** ABI 名称单源：支持集合、默认值与选择逻辑共用，避免同一字符串散落多处。 */
    const val ABI_ARM64 = "arm64-v8a"
    const val ABI_ARMV7 = "armeabi-v7a"

    /** 设备侧支持的 ABI 集合（单包分发后不再按 ABI 拆包，这里只用于设备能力判定）。 */
    val SUPPORTED_ABIS: Set<String> = setOf(ABI_ARMV7, ABI_ARM64)

    /** 老盒子默认 32 位 arm；也是「未知设备」的兜底口径。 */
    const val DEFAULT_ABI = ABI_ARMV7

    /** 开发/模拟器通道 ABI（universal 包同样可装，不单独发布）。 */
    const val EMULATOR_ABI = "x86_64"

    /**
     * 从设备实际支持的 ABI 列表（通常来自 `Build.SUPPORTED_ABIS`）里挑一个我们支持的 ABI。
     * 优先 64 位 arm64-v8a（性能更好），否则退回 32 位 armeabi-v7a；
     * 若设备只支持 x86/x86_64 等非发布 ABI，返回 null —— 这类设备由 universal 包覆盖。
     */
    fun selectBest(deviceAbis: List<String>): String? {
        val set = deviceAbis.toSet()
        return when {
            ABI_ARM64 in set -> ABI_ARM64
            ABI_ARMV7 in set -> ABI_ARMV7
            else -> null
        }
    }

    fun isSupported(abi: String): Boolean = abi in SUPPORTED_ABIS
}
