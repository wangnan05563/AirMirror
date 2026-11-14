package io.github.wangnan05563.airmirror.packaging

/**
 * PRD 1.1 安装包与 ABI 适配策略单源（纯 JVM，不引用 android.*，便于在单元测试里锁死口径）。
 *
 * 发布侧只交付两个独立 ABI 包：
 *  - armeabi-v7a：32 位，老电视盒子（多数只能跑 32 位 elf）默认走它；
 *  - arm64-v8a：64 位，性能更好，现代设备优先。
 * universal 包仍含 x86_64，仅用于开发机与模拟器(emulator-5554)安装验证，不对外发布。
 *
 * 本对象的常量须与 [app/build.gradle.kts] 的 splits/ndk 配置保持一致；
 * [BuildAbiGuardTest] 会以源码守卫方式反向校验 build.gradle.kts 包含同样的 ABI 集合。
 */
object AbiPolicy {
    /** 接收端硬下限（Q-5 已裁，不得静默抬高）。 */
    const val MIN_SDK = 19

    /** ABI 名称单源：发布集合、默认值与选择逻辑共用，避免同一字符串散落多处。 */
    const val ABI_ARM64 = "arm64-v8a"
    const val ABI_ARMV7 = "armeabi-v7a"

    /** 对外发布的两个独立 ABI（不含 x86_64，它由 universal 兜底开发/模拟器通道）。 */
    val SHIPPED_ABIS: Set<String> = setOf(ABI_ARMV7, ABI_ARM64)

    /** 老盒子默认 32 位 arm；也是「未知设备」的兜底构建。 */
    const val DEFAULT_ABI = ABI_ARMV7

    /** 开发/模拟器通道 ABI（universal 包内含，不单独发布）。 */
    const val EMULATOR_ABI = "x86_64"

    /**
     * 从设备实际支持的 ABI 列表（通常来自 `Build.SUPPORTED_ABIS`）里挑一个我们发布的 ABI。
     * 优先 64 位 arm64-v8a（性能更好），否则退回 32 位 armeabi-v7a；
     * 若设备只支持 x86/x86_64 等非发布 ABI，返回 null —— 这类设备应安装 universal 包。
     */
    fun selectBest(deviceAbis: List<String>): String? {
        val set = deviceAbis.toSet()
        return when {
            ABI_ARM64 in set -> ABI_ARM64
            ABI_ARMV7 in set -> ABI_ARMV7
            else -> null
        }
    }

    fun isShipped(abi: String): Boolean = abi in SHIPPED_ABIS
}
