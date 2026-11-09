package io.github.wangnan05563.airmirror.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * PRD UI-05 的卡片标签与离线标记：全部用平台绘制（[GradientDrawable]），不引位图资源。
 *
 * 三种标签共用一种圆角形状、只换配色，避免协议角标各自画一套；
 * 离线另配一个圆点形状标记，让「离线」不只靠文字灰度区分。
 */
object DeviceCardTag {

    fun chip(context: Context, tag: DeviceCardPolicy.Tag): TextView = TextView(context).apply {
        text = tag.text
        textSize = Theme.PHONE_BODY_SP
        gravity = Gravity.CENTER
        setTextColor(StatusColors.offlineText().takeIf { tag.kind != DeviceCardPolicy.TagKind.PROTOCOL } ?: Theme.ACCENT)
        val padH = Theme.dp(context, 8)
        val padV = Theme.dp(context, 3)
        setPadding(padH, padV, padH, padV)
        background = GradientDrawable().apply {
            cornerRadius = Theme.dp(context, DeviceCardPolicy.TAG_RADIUS_DP).toFloat()
            setColor(backgroundColor(tag.kind))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    /** 标签底色：协议用强调色的 12% 衬底，离线/不支持用更低的白色衬底，避免抢设备名。 */
    fun backgroundColor(kind: DeviceCardPolicy.TagKind): Int = when (kind) {
        DeviceCardPolicy.TagKind.PROTOCOL -> 0x1F17C3B2
        DeviceCardPolicy.TagKind.OFFLINE -> 0x1AFFFFFF
        DeviceCardPolicy.TagKind.UNSUPPORTED -> 0x14FFFFFF
    }

    fun offlineDot(context: Context): View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(StatusColors.offlineMarker())
        }
        layoutParams = LinearLayout.LayoutParams(Theme.dp(context, 8), Theme.dp(context, 8)).apply {
            rightMargin = Theme.dp(context, 6)
        }
    }
}
