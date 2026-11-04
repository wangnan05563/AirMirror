package io.github.wangnan05563.airmirror.compliance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PositionGuard 纯逻辑用例：违禁依赖/权限/代码标记能被识别、
 * 干净输入不误报、compliance 包自我引用豁免、结论文案可执行。
 */
class PositionGuardTest {

    @Test
    fun `违禁广告依赖坐标被识别`() {
        val gradle = "implementation(\"com.google.android.gms:play-services-ads:22.1.0\")"
        val hits = PositionGuard.check("build.gradle.kts", gradle)
        assertTrue(hits.any { it.kind == PositionGuard.Kind.DEPENDENCY && it.marker == "play-services-ads" })
        assertEquals("坐标里只有这一个联盟片段命中", 1, hits.size)
    }

    @Test
    fun `违禁计费依赖与权限被识别`() {
        val gradle = "implementation(\"com.android.billingclient:billing-ktx:6.0.1\")"
        val manifest = "<uses-permission android:name=\"com.android.vending.BILLING\" />"
        assertTrue(PositionGuard.check("build.gradle.kts", gradle).any { it.kind == PositionGuard.Kind.DEPENDENCY })
        assertTrue(PositionGuard.check("AndroidManifest.xml", manifest).any { it.kind == PositionGuard.Kind.PERMISSION })
    }

    @Test
    fun `违禁广告与计费代码标记被识别且带原因`() {
        val code = "val ad = InterstitialAd(this); ad.loadAd(unit)"
        val hits = PositionGuard.check("SomeActivity.kt", code)
        assertTrue(hits.any { it.kind == PositionGuard.Kind.CODE && it.reason.contains("插屏广告") })
        assertTrue(hits.any { it.marker == "loadAd(" })
        assertTrue(PositionGuard.check("Pay.kt", "BillingClient.launchBillingFlow(..)").any { it.reason.contains("计费") })
    }

    @Test
    fun `运营化 UI 文案被识别为 UI_COPY`() {
        val hits = PositionGuard.check("strings.xml", "<string name=\"tip\">签到领会员，开通会员解锁4K</string>")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.kind == PositionGuard.Kind.UI_COPY })
    }

    @Test
    fun `干净输入不误报——含否定句说明文案`() {
        val ui = "局域网直连 · 无广告 · 不经服务器"
        val code = "val session = CastSession(id); session.pause()"
        val gradle = "implementation(\"com.google.zxing:core:3.5.3\")"
        assertTrue(PositionGuard.check("SenderActivity.kt", ui).isEmpty())
        assertTrue(PositionGuard.check("SenderActivity.kt", code).isEmpty())
        assertTrue(PositionGuard.check("build.gradle.kts", gradle).isEmpty())
    }

    @Test
    fun `compliance 包自我引用豁免`() {
        val quoted = PositionGuard.BANNED_DEPENDENCY_MARKERS.joinToString(", ")
        val hits = PositionGuard.check("compliance/PositionGuard.kt", quoted)
        assertEquals(0, hits.size)
    }

    @Test
    fun `违禁清单与不做交互清单齐备`() {
        assertTrue("依赖口径不能为空", PositionGuard.BANNED_DEPENDENCY_MARKERS.size >= 10)
        assertTrue("权限口径要含 BILLING 与 AD_ID", PositionGuard.BANNED_PERMISSIONS.contains("com.android.vending.BILLING"))
        assertTrue(
            "PRD 11.3 七项不做交互要全收录",
            PositionGuard.FORBIDDEN_INTERACTIONS.containsAll(listOf("开屏广告", "签到", "积分", "任务中心", "内容推荐流", "强制登录", "投屏前倒计时")),
        )
        assertTrue(PositionGuard.totalMarkers() >= 25)
    }

    @Test
    fun `结论文案合规与违例两种口径`() {
        val ok = PositionGuard.verdict(emptyList())
        assertTrue(ok.contains("符合产品定位底线"))
        PositionGuard.BOTTOM_LINES.forEach { assertTrue(ok.contains(it)) }
        val bad = PositionGuard.verdict(PositionGuard.check("X.kt", "InterstitialAd"))
        assertTrue(bad.contains("扩展纪律"))
        assertTrue(bad.contains("处置"))
    }
}
