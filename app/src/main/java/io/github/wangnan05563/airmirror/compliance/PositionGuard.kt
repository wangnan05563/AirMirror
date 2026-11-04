package io.github.wangnan05563.airmirror.compliance

/**
 * PRD §7「产品定位底线守护」/ §10 扩展纪律的策略单源：
 *
 * > 任何扩展都不得引入广告位、内容推荐、账号强制登录或把投屏能力拆成付费档。
 *
 * 对应的非目标条款（§2）：不以广告、会员、投屏时长/画质解锁作为收入来源；
 * §11.3「不做的交互」：开屏广告、签到、积分、任务中心、内容推荐流、强制登录、投屏前 5 秒倒计时。
 *
 * 本对象只做纯 JVM 判定，不接触 Android API：构建期由 PositionComplianceTest
 * 用它扫描本仓库的依赖坐标、Manifest 权限、main 源码与 res 文案，任何命中即构建失败。
 * 违禁清单本身只在本文件出现（扫描时按包名豁免），新增违禁项只改这里。
 */
object PositionGuard {

    enum class Kind { DEPENDENCY, PERMISSION, CODE, UI_COPY }

    data class Violation(val kind: Kind, val marker: String, val reason: String)

    /** 三条底线，结论文案与守卫失败信息都要带上，方便定位到 PRD 条款。 */
    val BOTTOM_LINES = listOf(
        "不得引入广告位与内容推荐",
        "不得引入账号强制登录",
        "不得把投屏能力拆成付费档（广告/会员/画质时长解锁）",
    )

    /** PRD §11.3「不做的交互」清单，作为 UI 文案层审查的口径来源。 */
    val FORBIDDEN_INTERACTIONS = listOf(
        "开屏广告", "签到", "积分", "任务中心", "内容推荐流", "强制登录", "投屏前倒计时",
    )

    /**
     * 违禁依赖坐标片段（小写比较）：广告 SDK 联盟 + 应用内计费。
     * 命中任意片段即视为引入了与「无广告、不分付费档」定位冲突的第三方。
     */
    val BANNED_DEPENDENCY_MARKERS = listOf(
        "play-services-ads", "admob", "com.google.android.gms.ads",
        "applovin", "audience-network-sdk", "mopub", "unity3d.ads", "unity-ads",
        "openadsdk", "pangle", "com.qq.e.ads", "gdt-ads",
        "billingclient", "com.android.vending.billing", "amazon-insights",
    )

    /** 违禁 Manifest 权限：计费、许可证校验、广告标识。 */
    val BANNED_PERMISSIONS = listOf(
        "com.android.vending.BILLING",
        "com.android.vending.CHECK_LICENSE",
        "com.google.android.gms.permission.AD_ID",
    )

    /**
     * 违禁代码标记 -> 原因。只收录「不可能以正常功能写法出现」的 API 名与运营化文案，
     * 不收录「广告/会员」这类会被否定句（如「无广告」说明文案）误伤的词。
     */
    val BANNED_CODE_MARKERS: Map<String, String> = linkedMapOf(
        "AdView" to "广告视图控件",
        "InterstitialAd" to "插屏广告",
        "RewardedAd" to "激励视频广告",
        "NativeAd" to "原生广告",
        "loadAd(" to "广告位加载",
        "BillingClient" to "应用内计费客户端",
        "launchBillingFlow" to "拉起计费流程",
        "queryProductDetails" to "付费商品目录查询",
        "querySkuDetails" to "付费 SKU 查询",
        "acknowledgePurchase" to "购买确认（计费链路）",
        "签到领" to "签到运营",
        "积分商城" to "积分运营",
        "任务中心" to "任务中心运营",
        "会员解锁" to "会员墙",
        "付费解锁" to "付费墙",
        "开通会员" to "会员墙",
        "强制登录后" to "强制登录",
        "登录后才能投屏" to "强制登录",
        "观看广告后" to "广告解锁",
    )

    /**
     * 扫描一段文本，返回全部违禁命中。
     * [fileName] 仅用于豁免判断与原因描述：compliance 包内的文件引用违禁清单本身，豁免。
     */
    fun check(fileName: String, content: String): List<Violation> {
        if (isPolicyFile(fileName)) return emptyList()
        val hits = mutableListOf<Violation>()
        for (marker in BANNED_DEPENDENCY_MARKERS) {
            if (content.lowercase().contains(marker)) {
                hits += Violation(Kind.DEPENDENCY, marker, "依赖坐标命中违禁 SDK 片段「$marker」")
            }
        }
        for (marker in BANNED_PERMISSIONS) {
            if (content.contains(marker)) {
                hits += Violation(Kind.PERMISSION, marker, "Manifest 声明了违禁权限「$marker」")
            }
        }
        for ((marker, reason) in BANNED_CODE_MARKERS) {
            val isUiCopy = reason.endsWith("运营") || reason.endsWith("会员墙") ||
                reason.endsWith("付费墙") || reason.endsWith("强制登录") || reason.endsWith("广告解锁")
            val kind = if (isUiCopy) Kind.UI_COPY else Kind.CODE
            if (content.contains(marker)) {
                hits += Violation(kind, marker, "源码/文案命中「$marker」（$reason）")
            }
        }
        return hits
    }

    fun compliant(violations: List<Violation>): Boolean = violations.isEmpty()

    /**
     * 7.3 口径的可执行结论文案：合规时说明守卫覆盖了什么；违例时指出条款与修复方向。
     */
    fun verdict(violations: List<Violation>): String {
        if (violations.isEmpty()) {
            return "符合产品定位底线（${BOTTOM_LINES.joinToString("；")}）。" +
                "守卫覆盖：依赖坐标、Manifest 权限、main 源码与 res 文案，违禁标记 ${totalMarkers()} 处口径全部 0 命中。"
        }
        return violations.joinToString("\n") { v ->
            "违反扩展纪律（PRD §10 / §7）：${v.reason}。处置：移除该引入；若确属定位内的能力，先回 PRD §7 底线条款重新裁定，不得静默保留。"
        }
    }

    fun totalMarkers(): Int = BANNED_DEPENDENCY_MARKERS.size + BANNED_PERMISSIONS.size + BANNED_CODE_MARKERS.size

    /** 路径归一化后判断是否策略文件自身（相对/绝对路径都要豁免）。 */
    internal fun isPolicyFile(fileName: String): Boolean =
        fileName.replace('\\', '/').contains("compliance/")
}
