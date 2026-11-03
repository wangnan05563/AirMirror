package io.github.wangnan05563.airmirror.dlna

import io.github.wangnan05563.airmirror.error.ErrorNotice

/**
 * 「推过去了但没有画面可渲染」的判定（纯函数，JVM 可验）。
 *
 * 接收端退后台或息屏时 SurfaceView 的面会被销毁，此时推**无音轨的纯视频**：
 * MediaPlayer 一路 prepared → start 全成功，却一帧都不进，进度永远 `00:00:00`。
 * FS-02 的 15 秒看门狗管的是「未就绪」，够不着这一类；而直接判失败又会让
 * 「只想听声音」的有声片源被拒，所以口径是**提醒但不拒**。
 */
object RenderSurfaceWatch {

    /**
     * @param wantsPlayback 控制点是否已经要求播放（`Play` 已落到状态机，含媒体还没就绪时的排队中）。
     *                      STOPPED / NO_MEDIA 时没画面是常态，不该出声。
     */
    fun causeFor(upnpClass: String?, wantsPlayback: Boolean, hasSurface: Boolean): ErrorNotice.Cause? {
        if (hasSurface || !wantsPlayback) return null
        if (upnpClass?.contains("videoItem") != true) return null
        return ErrorNotice.Cause.DISPLAY_UNREADY
    }
}
