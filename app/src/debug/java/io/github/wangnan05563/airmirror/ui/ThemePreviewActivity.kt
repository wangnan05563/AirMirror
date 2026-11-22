package io.github.wangnan05563.airmirror.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Debug-only 主题预览页：展示 Raw / Semantic / Component 三层令牌、字阶、刻度与 5% 安全区。
 * release 不打包该 Activity（位于 src/debug），入口也不进设置页。
 */
class ThemePreviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ThemePreviewView(this))
    }
}

/** 预览内容独立成 View，便于 Robolectric 在 360dp/1080p 直接测量与截图。 */
class ThemePreviewView(context: Context) : ScrollView(context) {

    private val raw = ThemeTokens.raw
    private val semantic = ThemeTokens.semantic
    private val component = ThemeTokens.component

    init {
        isFillViewport = true
        setBackgroundColor(component.pageBackground)
        addView(buildContent(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun buildContent(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = Theme.dp(context, if (TvFocus.isTvMode(context)) 32 else 16)
        setPadding(pad, pad, pad, pad)
        addView(title())
        addView(section("Raw · 原始层", "只允许 ThemeTokens 与 debug 预览读取"))
        addView(chipGrid(
            listOf(
                "ink-950" to raw.ink950,
                "ink-900" to raw.ink900,
                "ink-800" to raw.ink800,
                "teal-500" to raw.teal500,
                "white" to raw.white,
                "red-400" to raw.red400,
                "white-70" to raw.white70,
                "white-30" to raw.white30,
            ),
        ))
        addView(tokenLine("Raw extras", listOf(
            "ink950-40" to raw.ink950_40,
            "ink950-80" to raw.ink950_80,
            "ink950-90" to raw.ink950_90,
            "ink900-20" to raw.ink900_20,
            "ink900-90" to raw.ink900_90,
            "teal-20" to raw.teal500_20,
            "teal-12" to raw.teal500_12,
            "white-10" to raw.white10,
            "white-8" to raw.white8,
        )))
        addView(section("Semantic · 语义层", "页面/组件按语义取色，不按外观命名"))
        addView(chipGrid(
            listOf(
                "background" to semantic.background,
                "surface" to semantic.surface,
                "surfaceElevated" to semantic.surfaceElevated,
                "accent" to semantic.accent,
                "textPrimary" to semantic.textPrimary,
                "textSecondary" to semantic.textSecondary,
                "offline" to semantic.offline,
                "danger" to semantic.danger,
            ),
        ))
        addView(tokenLine("Semantic extras", listOf(
            "scrim" to semantic.scrim,
            "scrimStrong" to semantic.scrimStrong,
            "panel" to semantic.panel,
            "divider" to semantic.divider,
            "focusFill" to semantic.focusFill,
            "surfaceStroke" to semantic.surfaceStroke,
            "qrLight" to semantic.qrLight,
            "accentSubtle" to semantic.accentSubtle,
            "surfaceSubtle" to semantic.surfaceSubtle,
            "surfaceGhost" to semantic.surfaceGhost,
        )))
        addView(section("Component · 组件层", "只按组件命名；手机/TV 只换尺寸不换色"))
        addView(componentRow())
        addView(tagRow())
        addView(section("Typography · 字阶", "40 / 32 / 24 / 14sp，投屏码单独 36sp"))
        addView(typeSamples())
        addView(section("Metrics · 刻度", "圆角 14/16/12/24/6dp；动效 120/200/150ms"))
        addView(metrics())
    }

    private fun title(): TextView = TextView(context).apply {
        text = "Midnight Theme Tokens"
        setTextColor(semantic.textPrimary)
        textSize = ThemeTokens.typography.title
        setPadding(0, 0, 0, Theme.dp(context, 8))
        contentDescription = "主题预览：Midnight，默认主题，OLED 仅预留接口"
    }

    private fun section(title: String, subtitle: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, Theme.dp(context, 16), 0, Theme.dp(context, 8))
        addView(TextView(context).apply {
            text = title
            setTextColor(semantic.accent)
            textSize = ThemeTokens.typography.sectionTitle
        })
        addView(TextView(context).apply {
            text = subtitle
            setTextColor(semantic.textSecondary)
            textSize = ThemeTokens.typography.caption
        })
    }

    private fun chipGrid(items: List<Pair<String, Int>>): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val columns = if (resources.configuration.screenWidthDp < 600) 2 else 4
        items.chunked(columns).forEach { rowItems ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, Theme.dp(context, 8))
            }
            rowItems.forEach { (name, color) ->
                row.addView(chip(name, color), LinearLayout.LayoutParams(0, Theme.dp(context, 44), 1f).apply {
                    rightMargin = Theme.dp(context, 8)
                })
            }
            addView(row)
        }
    }

    private fun tokenLine(label: String, items: List<Pair<String, Int>>): TextView = TextView(context).apply {
        text = label + "：" + items.joinToString(" · ") { (name, color) -> "$name ${hex(color)}" }
        setTextColor(semantic.textSecondary)
        textSize = ThemeTokens.typography.caption
        setPadding(0, 0, 0, Theme.dp(context, 8))
    }

    private fun chip(name: String, color: Int): View = TextView(context).apply {
        text = "$name\n${hex(color)}"
        gravity = Gravity.CENTER
        setTextColor(if (luminance(color) > 0.6) semantic.background else semantic.textPrimary)
        textSize = ThemeTokens.typography.caption
        background = GradientDrawable().apply {
            cornerRadius = Theme.dp(context, Theme.component.cardRadius / 2).toFloat()
            setColor(color)
        }
        contentDescription = "色板 $name ${hex(color)}"
    }

    private fun componentRow(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(UiKit.button(context, "主操作", UiKit.Kind.Primary), weight())
        addView(UiKit.button(context, "次操作", UiKit.Kind.Secondary), weight())
        addView(UiKit.button(context, "兜底", UiKit.Kind.Ghost), weight())
    }

    private fun tagRow(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, Theme.dp(context, 8), 0, 0)
        listOf(
            "协议标签" to component.tagProtocolBg,
            "离线标签" to component.tagOfflineBg,
            "不支持" to component.tagUnsupportedBg,
        ).forEach { (name, color) ->
            addView(TextView(context).apply {
                text = name
                gravity = Gravity.CENTER
                setTextColor(semantic.textSecondary)
                textSize = ThemeTokens.typography.caption
                background = GradientDrawable().apply {
                    cornerRadius = Theme.dp(context, 8).toFloat()
                    setColor(color)
                }
            }, LinearLayout.LayoutParams(0, Theme.dp(context, 36), 1f).apply {
                rightMargin = Theme.dp(context, 8)
            })
        }
    }

    private fun typeSamples(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        listOf(
            "Display 40 · AirMirror" to ThemeTokens.typography.display,
            "Title 32 · 连接电视" to ThemeTokens.typography.title,
            "Body 24 · 局域网直连，画面不出内网" to ThemeTokens.typography.body,
            "Caption 14 · 投屏码 6 位，120 秒有效" to ThemeTokens.typography.caption,
        ).forEach { (text, size) ->
            addView(TextView(context).apply {
                this.text = text
                setTextColor(semantic.textPrimary)
                textSize = size
                setPadding(0, 0, 0, Theme.dp(context, 4))
            })
        }
    }

    private fun metrics(): TextView = TextView(context).apply {
        val safe = TvLayoutSpec.safeInsets(1920, 1080)
        text = "radius button/card/inset/dialog/focus = " +
            "${Theme.component.buttonRadius}/${Theme.component.cardRadius}/" +
            "${ThemeTokens.radii.inset}/${Theme.component.dialogRadius}/${ThemeTokens.radii.focus}dp\n" +
            "motion focus/connect/codeFade = " +
            "${ThemeTokens.motion.focus}/${ThemeTokens.motion.connect}/${ThemeTokens.motion.codeFade}ms\n" +
            "5% safe area @1920×1080 = ${safe.horizontal}px / ${safe.vertical}px\n" +
            "OLED capabilities: supportsTrueBlack=${OledTheme.capabilities.supportsTrueBlack}, " +
            "releaseSelectable=${OledTheme.capabilities.releaseSelectable}"
        setTextColor(semantic.textSecondary)
        textSize = ThemeTokens.typography.caption
        setPadding(0, 0, 0, Theme.dp(context, 16))
    }

    private fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, Theme.dp(context, 48), 1f).apply {
            rightMargin = Theme.dp(context, 8)
        }

    private fun hex(color: Int): String = String.format("#%08X", color)

    private fun luminance(color: Int): Double {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
    }
}
