package io.github.wangnan05563.airmirror.web

import io.github.wangnan05563.airmirror.ui.Theme
import java.util.Locale

/**
 * 访客上传页（UX-04）：页面不写死颜色，CSS 变量全部由 [Theme] 令牌生成，
 * 视觉标准（背景 / 卡片 / 主按钮 / 正文 ≥14px / 触控 ≥48px）因此只有一个真相源。
 *
 * 页面是接收端对访客的唯一门面，所以成功按文件类型说清楚「显示」还是「播放」，
 * 失败一律给「原因 + 下一步」，不留光秃秃的状态码（PRD 7.3 口径）。
 * 纯 Kotlin（不依赖 android.*），HTML 特征串可直测。
 */
object GuestPage {

    /** 正文 16px，满足 UX-04「正文 ≥14px」。 */
    const val BODY_PX = 16
    const val BUTTON_PX = 16
    /** 触控高度下限（UX-04 视觉标准 48dp）。 */
    const val TOUCH_MIN_PX = 48
    /** 网页圆角：App 只令牌化了焦点描边 6dp，卡片/控件的圆角值在此单点定义。 */
    const val RADIUS_CARD_PX = 14
    const val RADIUS_CONTROL_PX = 10

    const val OK_IMAGE = "已在大屏显示"
    const val OK_MEDIA = "已在大屏播放"
    const val OK_OTHER = "已发送到大屏"
    const val PICK_FIRST = "请先选择一个文件"
    const val RETRY = "重试"
    const val AGAIN = "再传一个"

    data class Failure(val reason: String, val next: String) {
        val line: String get() = "$reason，$next"
    }

    /** 0 表示网络层失败（xhr.onerror），其余是服务端状态码。 */
    fun failureOf(status: Int): Failure = when (status) {
        0 -> Failure("连不上大屏", "确认手机和大屏在同一网络后再试")
        400 -> Failure("上传中途断开", "点重试再传一次")
        411 -> Failure("上传请求不完整", "重新选一个文件再试")
        413 -> Failure("文件超过大屏能接收的大小", "换一个更小的文件再试")
        else -> Failure("上传失败（$status）", "点重试再传一次")
    }

    fun successOf(mime: String): String = when {
        mime.startsWith("image/") -> OK_IMAGE
        mime.startsWith("video/") || mime.startsWith("audio/") -> OK_MEDIA
        else -> OK_OTHER
    }

    fun cssColor(argb: Int): String = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

    fun cssAlpha(argb: Int): String = String.format(
        Locale.US,
        "rgba(%d,%d,%d,%.2f)",
        (argb shr 16) and 0xFF,
        (argb shr 8) and 0xFF,
        argb and 0xFF,
        (argb ushr 24) / 255.0,
    )

    /** 网页只引用变量；取值一律从这里出，页面里不再出现色值字面量。 */
    fun tokenCss(): String = """
        :root{
          --bg:${cssColor(Theme.BACKGROUND)};
          --surface:${cssColor(Theme.SURFACE)};
          --accent:${cssColor(Theme.ACCENT)};
          --text:${cssColor(Theme.TEXT_PRIMARY)};
          --text-dim:${cssAlpha(Theme.TEXT_SECONDARY)};
          --text-faint:${cssAlpha(Theme.OFFLINE)};
          --divider:${cssAlpha(Theme.DIVIDER)};
        }
    """.trimIndent()

    fun escapeHtml(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun jsString(raw: String): String = raw.replace("\\", "\\\\").replace("'", "\\'")

    private fun failureTableJs(): String {
        val entries = listOf(0, 400, 411, 413).joinToString(",") { "$it:'${jsString(failureOf(it).line)}'" }
        return "const FAIL={$entries};"
    }

    fun html(deviceName: String, uploadPath: String): String = """
        <!doctype html>
        <html lang="zh-CN"><head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>投到大屏</title>
        <style>
        ${tokenCss()}
        body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;margin:0;background:var(--bg);color:var(--text)}
        main{max-width:560px;margin:0 auto;padding:32px 20px}
        .eyebrow{color:var(--text-dim);font-size:14px;margin:0 0 4px}
        h1{font-size:${Theme.BODY_SP.toInt()}px;margin:0 0 8px;word-break:break-all}
        p{color:var(--text-dim);line-height:1.6;font-size:${BODY_PX}px}
        .card{background:var(--surface);border-radius:${RADIUS_CARD_PX}px;padding:20px;margin-top:20px}
        input[type=file]{width:100%;box-sizing:border-box;padding:12px;background:var(--bg);color:var(--text);border:1px solid var(--text-faint);border-radius:${RADIUS_CONTROL_PX}px;font-size:${BODY_PX}px}
        input[type=file]:focus{border-color:var(--accent);outline:2px solid var(--accent);outline-offset:1px}
        button{margin-top:16px;width:100%;min-height:${TOUCH_MIN_PX}px;padding:0 14px;border:0;border-radius:${RADIUS_CONTROL_PX}px;background:var(--accent);color:var(--bg);font-size:${BUTTON_PX}px;font-weight:600;cursor:pointer}
        button:disabled{opacity:.55;cursor:default}
        #state{margin-top:14px;color:var(--text-dim);font-size:14px}
        #state.ok{color:var(--accent)}
        #state.fail{color:var(--text)}
        #retry,#again{display:none;background:transparent;color:var(--accent);border:1px solid var(--accent)}
        .hint{font-size:14px;color:var(--text-faint);margin-top:18px}
        </style></head>
        <body><main>
          <p class="eyebrow">投到大屏</p>
          <h1>${escapeHtml(deviceName)}</h1>
          <p>选择手机或电脑里的视频、音频或图片，上传后会直接在电视上播放。不需要安装任何 App。</p>
          <div class="card">
            <input id="file" type="file" accept="video/*,audio/*,image/*">
            <button id="send" type="button">发送到大屏</button>
            <div id="state" role="status" aria-live="polite"></div>
            <button id="retry" type="button">$RETRY</button>
            <button id="again" type="button">$AGAIN</button>
          </div>
          <p class="hint">浏览器画面投屏需要 WebRTC 支持；当前版本请使用文件上传。</p>
        </main>
        <script>
          const input=document.getElementById('file');
          const send=document.getElementById('send');
          const state=document.getElementById('state');
          const retry=document.getElementById('retry');
          const again=document.getElementById('again');
          const OK_IMAGE='${jsString(OK_IMAGE)}',OK_MEDIA='${jsString(OK_MEDIA)}',OK_OTHER='${jsString(OK_OTHER)}';
          ${failureTableJs()}
          function show(text,cls){state.textContent=text;state.className=cls||'';retry.style.display='none';again.style.display='none';}
          function start(){
            const file=input.files[0];
            if(!file){show('${jsString(PICK_FIRST)}');return;}
            show('正在上传 0%');
            send.disabled=true;
            const xhr=new XMLHttpRequest();
            xhr.open('POST','$uploadPath');
            xhr.setRequestHeader('X-File-Name',encodeURIComponent(file.name));
            xhr.setRequestHeader('Content-Type',file.type||'application/octet-stream');
            xhr.upload.onprogress=function(e){if(e.lengthComputable){state.textContent='正在上传 '+Math.round(e.loaded*100/e.total)+'%';}};
            xhr.onload=function(){
              send.disabled=false;
              if(xhr.status===200){
                const type=file.type||'';
                state.textContent=type.indexOf('image/')===0?OK_IMAGE:((type.indexOf('video/')===0||type.indexOf('audio/')===0)?OK_MEDIA:OK_OTHER);
                state.className='ok';again.style.display='block';
              }else{
                state.textContent=FAIL[xhr.status]||('上传失败（'+xhr.status+'），点重试再传一次');
                state.className='fail';retry.style.display='block';
              }
            };
            xhr.onerror=function(){send.disabled=false;state.textContent=FAIL[0];state.className='fail';retry.style.display='block';};
            xhr.send(file);
          }
          send.onclick=start;retry.onclick=start;
          again.onclick=function(){input.value='';state.textContent='';state.className='';again.style.display='none';input.click();};
        </script>
        </body></html>
    """.trimIndent()
}
