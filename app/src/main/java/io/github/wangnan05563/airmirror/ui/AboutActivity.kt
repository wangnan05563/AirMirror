package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * UI-07 关于页：版本号、许可证摘要、开源声明入口与项目主页。
 *
 * 页面只用平台 View + MaterialButton（经 [UiKit]），不新增图片、不引第三方 UI 框架；
 * 版本号取 `PackageManager` 的 `versionName`，保证「关于页版本」与安装包一致。
 */
class AboutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isTv = TvFocus.isTvMode(this)
        val bodySp = FocusPolicy.bodySp(isTv)
        val titleSp = FocusPolicy.titleSp(isTv)
        val sectionSp = FocusPolicy.sectionTitleSp(isTv)
        val compact = resources.configuration.screenHeightDp < 700
        val pad = Theme.dp(this, Theme.SPACE_XL_DP)
        val topPadDp = if (compact) Theme.SPACE_MD_DP else Theme.SPACE_XL_DP
        val bottomPadDp = if (compact) Theme.SPACE_MD_DP else Theme.SPACE_XXL_DP
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Theme.dp(this@AboutActivity, topPadDp), pad, Theme.dp(this@AboutActivity, bottomPadDp))
        }

        val back = UiKit.button(this, "返回", UiKit.Kind.Ghost).apply {
            setOnClickListener { finish() }
            A11y.describe(this, A11yCopy.aboutBack())
        }
        content.addView(back, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@AboutActivity, if (compact) Theme.SPACE_MD_DP else Theme.SPACE_LG_DP), 0, 0)
        }
        brandRow.addView(BrandMarkView(this).apply { A11y.decorative(this) }, LinearLayout.LayoutParams(
            Theme.dp(this, Theme.SPACE_XXL_DP),
            Theme.dp(this, Theme.SPACE_XXL_DP),
        ).apply { marginEnd = Theme.dp(this@AboutActivity, Theme.SPACE_SM_DP) })
        brandRow.addView(TextView(this).apply {
            text = "AirMirror"
            textSize = titleSp
            setTextColor(Theme.TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        content.addView(brandRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        content.addView(TextView(this).apply {
            text = "局域网直连 · 无广告 · 不需要账号"
            textSize = bodySp
            setTextColor(Theme.TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@AboutActivity, Theme.SPACE_SM_DP), 0,
                Theme.dp(this@AboutActivity, if (compact) Theme.SPACE_MD_DP else Theme.SPACE_XL_DP))
        })

        val versionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            UiKit.surface(this)
            setPadding(Theme.dp(this@AboutActivity, Theme.SPACE_LG_DP), Theme.dp(this@AboutActivity, Theme.SPACE_LG_DP), Theme.dp(this@AboutActivity, Theme.SPACE_LG_DP), Theme.dp(this@AboutActivity, Theme.SPACE_LG_DP))
        }
        versionCard.addView(label("版本", sectionSp, Theme.ACCENT, topDp = 0))
        versionCard.addView(label(AboutContent.versionLine(versionName), bodySp, Theme.TEXT_PRIMARY, topDp = Theme.SPACE_SM_DP))
        versionCard.addView(label(AboutContent.packageLine(packageName), bodySp, Theme.TEXT_SECONDARY, topDp = Theme.SPACE_XS_DP))
        content.addView(versionCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Theme.dp(this@AboutActivity, if (compact) Theme.SPACE_MD_DP else Theme.SPACE_LG_DP) })

        content.addView(label("许可证摘要", sectionSp, Theme.ACCENT, topDp = if (compact) Theme.SPACE_LG_DP else Theme.SPACE_XL_DP))
        content.addView(label(AboutContent.LICENSE_SUMMARY, bodySp, Theme.TEXT_SECONDARY, topDp = Theme.SPACE_SM_DP))

        val notice = UiKit.button(this, "查看开源声明", UiKit.Kind.Secondary).apply {
            setOnClickListener { showOpenSourceNotice() }
            A11y.describe(this, A11yCopy.aboutNotice())
        }
        content.addView(notice, UiKit.fullWidth(this, if (compact) Theme.SPACE_LG_DP else Theme.SPACE_XL_DP, 0))

        val project = UiKit.button(this, "打开项目主页", UiKit.Kind.Ghost).apply {
            setOnClickListener { openProjectPage() }
            A11y.describe(this, A11yCopy.aboutProject())
        }
        content.addView(project, UiKit.fullWidth(this, Theme.SPACE_SM_DP, 0))

        content.addView(TextView(this).apply {
            text = "投屏内容只在本次会话内使用，不保存画面、文件名与地址。"
            textSize = bodySp
            setTextColor(StatusColors.offlineText())
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@AboutActivity, if (compact) Theme.SPACE_MD_DP else Theme.SPACE_XL_DP), 0, 0)
        })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Theme.BACKGROUND)
            addView(content)
        }
        setContentView(scroll)
        TvFocus.applyTree(scroll)
    }

    private fun label(text: String, sp: Float, color: Int, topDp: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sp
            setTextColor(color)
            setPadding(0, Theme.dp(this@AboutActivity, topDp), 0, 0)
        }

    private fun showOpenSourceNotice() {
        UiKit.dialog(this)
            .setTitle("开源声明")
            .setMessage(AboutContent.OPEN_SOURCE_NOTICE)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun openProjectPage() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AboutContent.PROJECT_URL)))
        }.onFailure {
            UiKit.dialog(this)
                .setTitle("无法打开浏览器")
                .setMessage("请手动访问 ${AboutContent.PROJECT_URL}")
                .setPositiveButton("知道了", null)
                .show()
        }
    }
}
