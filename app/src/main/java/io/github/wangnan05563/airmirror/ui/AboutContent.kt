package io.github.wangnan05563.airmirror.ui

/**
 * 关于页文案与许可信息（UI-07）。
 *
 * 只放纯文本常量，便于 JVM 单测锁定「版本号、许可证摘要、开源声明入口」三件事；
 * Activity 只负责排版，不改口径。第三方组件以实际依赖为准：
 * AppCompat / Material Components / ZXing Core 均为 Apache-2.0。
 */
object AboutContent {

    const val PROJECT_URL = "https://github.com/wangnan05563/AirMirror"

    const val LICENSE_SUMMARY =
        "本应用使用以下开源组件：AndroidX AppCompat 1.7.0、Material Components 1.12.0、ZXing Core 3.5.3。" +
            "三者均按 Apache License 2.0 授权；完整许可证文本与第三方声明见项目仓库。"

    const val OPEN_SOURCE_NOTICE =
        "AirMirror 开源组件声明\n\n" +
            "AndroidX AppCompat 1.7.0 — Apache License 2.0\n" +
            "Material Components 1.12.0 — Apache License 2.0\n" +
            "ZXing Core 3.5.3 — Apache License 2.0\n\n" +
            "Apache License 2.0：https://www.apache.org/licenses/LICENSE-2.0\n" +
            "项目仓库：$PROJECT_URL\n\n" +
            "本应用不包含广告、统计或推送 SDK；二维码保持黑白高对比，品牌标记由平台绘制能力生成，不新增位图。"

    fun versionLine(versionName: String): String = "版本 ${versionName.ifBlank { "未知" }}"

    fun packageLine(packageName: String): String = "包名 $packageName"
}
