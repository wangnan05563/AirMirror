package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.View
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.wangnan05563.airmirror.R
import io.github.wangnan05563.airmirror.crossnet.SignalingSettings
import io.github.wangnan05563.airmirror.device.DisplayFit
import io.github.wangnan05563.airmirror.device.DisplayMode
import io.github.wangnan05563.airmirror.dlna.localIpv4
import io.github.wangnan05563.airmirror.device.DeviceProbe
import io.github.wangnan05563.airmirror.device.FourGates
import io.github.wangnan05563.airmirror.device.GateReport
import io.github.wangnan05563.airmirror.device.Grade
import io.github.wangnan05563.airmirror.device.QualityTier
import io.github.wangnan05563.airmirror.device.QualityTiers
import io.github.wangnan05563.airmirror.pairing.CastCode
import io.github.wangnan05563.airmirror.pairing.DeviceStore
import io.github.wangnan05563.airmirror.pairing.Pairing
import io.github.wangnan05563.airmirror.privacy.Diagnostics
import io.github.wangnan05563.airmirror.protocol.ProtocolBoundary
import io.github.wangnan05563.airmirror.receiver.ReceiverHost
import io.github.wangnan05563.airmirror.util.Onboarding
import io.github.wangnan05563.airmirror.receiver.ReceiverService
import io.github.wangnan05563.airmirror.receiver.ReceiverSession
import kotlin.concurrent.thread

/**
 * 接收端待连接页（PRD §11.2）：设备名居中最上方，二维码与 6 位投屏码并列，下方两行引导文案；
 * 投屏中整屏只保留画面，仅在连接成功时叠加 3 秒设备名。
 */
class ReceiverActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var store: DeviceStore
    private lateinit var pairing: Pairing
    private lateinit var host: ReceiverHost

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    /** PRD FS-02 图片推送的画面：图片不走 Surface，单独一层盖在视频面上。 */
    private lateinit var dlnaImageView: ImageView
    private lateinit var waiting: LinearLayout
    /** PRD UI-02 主视觉区：只含设备名 + 投屏码（环形倒计时）+ 二维码三件。 */
    private lateinit var mainArea: LinearLayout
    /** 次要入口收进「更多信息」，与主区间距 ≥24dp；浏览器入口在其内（§7.3 裁定）。 */
    private lateinit var moreInfoToggle: TextView
    private lateinit var moreInfoBody: LinearLayout
    private lateinit var deviceNameText: TextView
    private lateinit var codeSlot: FrameLayout
    private lateinit var codeA: TextView
    private lateinit var codeB: TextView
    private lateinit var browserText: TextView
    private lateinit var browserQr: ImageView
    private var lastBrowserUrl = ""
    private lateinit var countdownText: TextView
    /** PRD UI-04：C 级设备用静态高对比态（不依赖半透明字与底色的合成值）。 */
    private val highContrastUi: Boolean by lazy { DeviceProbe.policy(this).grade == Grade.C }
    private lateinit var ring: CountdownRingView
    private lateinit var qrView: ImageView
    private lateinit var statusText: TextView
    /** 设备分级与诊断摘要收进设置页（UI-02 / §7.3），不再占主视觉区。 */
    private var probeSummary = ""
    private lateinit var overlayName: TextView
    private lateinit var noticeOverlay: TextView
    /** 断线保留窗口内的常驻提示：与 3 秒浮层不同，要一直显示到恢复或 30 秒收口（PRD FS-10）。 */
    private lateinit var recoveryBar: TextView
    private lateinit var cornerHint: TextView
    private lateinit var settingsPanel: LinearLayout
    private lateinit var settingsScroller: ScrollView
    private lateinit var settingsEntry: TextView
    /** 投屏中盖在画面上接长按：ScrollView 会吃掉落在画面上的触摸，根布局收不到长按。 */
    private lateinit var longPressCatcher: View
    private lateinit var promptBar: LinearLayout
    private lateinit var promptText: TextView
    private lateinit var promptDeny: Button
    private lateinit var promptAllow: Button
    private lateinit var endBar: LinearLayout
    private lateinit var endText: TextView
    private lateinit var endCancelButton: Button
    private lateinit var endConfirmButton: Button
    private val modeRows = LinkedHashMap<DisplayMode, TextView>()
    private lateinit var rotationRow: TextView
    private lateinit var labelRow: TextView
    private lateinit var autoStartRow: TextView
    private lateinit var crossNetRow: TextView
    /** 画质角标：默认不显示，用户在设置页打开后常驻右下角（PRD FS-05「画面标识」） */
    private lateinit var qualityBadge: TextView
    /** 设置页里「自动 + 各可达档位」的行；null 键 = 自动。不可达的档位连行都不生成。 */
    private val qualityRows = LinkedHashMap<QualityTier?, TextView>()
    private lateinit var qualityContainer: LinearLayout
    private lateinit var gateLine: TextView
    private lateinit var badgeRow: TextView
    /** 隐私分组（PRD FS-11）：诊断默认关、可清除，说明与清理入口都在设置页里。 */
    private lateinit var diagnostics: Diagnostics
    private lateinit var diagnosticsRow: TextView
    private lateinit var clearDataRow: TextView
    /** 首启三步引导（PRD §1.2）：覆盖在待连接页之上的全屏卡片；非首启时整块 GONE。 */
    private lateinit var onboarding: FrameLayout
    private lateinit var onboardStepHint: TextView
    private lateinit var onboardTitle: TextView
    private lateinit var onboardBody: TextView
    private lateinit var onboardInput: EditText
    private lateinit var onboardAutoToggle: TextView
    private lateinit var onboardSkipAll: TextView
    private lateinit var onboardBack: Button
    private lateinit var onboardSkip: Button
    private lateinit var onboardNext: Button
    private var onboardStep = 1
    /** 本机上限（分级 ∩ 硬解 ∩ 四道门）与门判定结果，后台探测完回填；未探到为 null */
    private var localCeiling: QualityTier? = null
    private var gateReport: GateReport? = null
    private var qualityLock: QualityTier? = null

    private var displayMode = DisplayMode.FIT
    private var followRotation = true
    private var portraitLabel = true
    /** 实际送入画面的方向：关闭旋转跟随后冻结在最后一次跟随到的方向 */
    private var shownWidth = 0
    private var shownHeight = 0
    /**
     * 本地媒体（DLNA / 浏览器推送）的原始画幅。与镜像的 shown* 分开存：
     * 镜像那套带旋转跟随语义，两路混用会把「竖屏」提示与前一次的方向带到下一次播放上。
     */
    private var dlnaWidth = 0
    private var dlnaHeight = 0
    /** 最近一帧的真实方向，重新打开旋转跟随时立即采纳 */
    private var latestWidth = 0
    private var latestHeight = 0
    private var lastPortrait = false
    private var modeHintShown = false

    private var lastPayload = ""
    private var isCasting = false
    /** 码槽里当前可读的一层；另一层专门用来承接下一码，角色交换不新建 View。 */
    private lateinit var activeCode: TextView
    private lateinit var inactiveCode: TextView
    /** 上一次真正显示的值（含锁定占位符）；null 表示首帧还没画，不能等动画。 */
    private var shownCode: String? = null
    /** 无障碍码描述的变更键（锁定态:码值）：只在换码/锁定翻转时更新描述并播报，每秒倒计时不触发（R8）。 */
    private var lastCodeA11yKey: String? = null
    private var resumed = true
    /** 最近一次投屏的发送端名：首帧读数晚于浮层弹出时，要用它把整行重拼一次。 */
    private var lastSenderName = ""
    private var endConfirmUntil = 0L
    private val main = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshWaitingPage()
            tickEndConfirm()
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        host = ReceiverHost.get(this)
        store = host.store
        pairing = host.pairing
        displayMode = store.displayMode()
        followRotation = store.followRotation()
        portraitLabel = store.portraitLabel()
        qualityLock = store.qualityLock()
        diagnostics = Diagnostics(this)
        // T05669：诊断接管已由 ReceiverHost.init（开机自启路径）与 MainActivity 单点安装，
        // install 幂等但没必要在每次 Receiver 冷启再走一遍同步块。
        // 7 天过期清理是磁盘遍历 + 删除，首屏并不等它的返回值——移出 onCreate 主线程，
        // 避免慢速软件渲染设备叠加文件 I/O 后越过 ANR 阈值（PRD §13.2 老设备基线）。
        thread(name = "airmirror-diag-purge", isDaemon = true) { diagnostics.purge() }
        root = FrameLayout(this).apply { setBackgroundColor(Theme.BACKGROUND) }

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        A11y.decorative(surfaceView)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        dlnaImageView = ImageView(this).apply {
            visibility = View.GONE
            scaleType = scaleTypeFor(displayMode)
            setBackgroundColor(Theme.BACKGROUND)
            A11y.decorative(this)
        }
        // 加在 SurfaceView 之后、待连接页之前：图片显示时待连接页已让位，图片被它盖住正好
        root.addView(
            dlnaImageView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        // 待连接页可滚动：小屏上正文超出屏幕时不该把底部文案裁掉
        root.addView(
            ScrollView(this).apply {
                isFillViewport = true
                // 方向键（电视遥控器）要能落到右上角「显示设置」。滚动容器默认可聚焦，
                // 焦点进了 ScrollView 就只在它内部找下一个目标；内层全是纯文本，
                // 于是方向键与确认键都被容器吃掉，用户永远打不开设置。
                // 触摸滚动不依赖焦点，关掉不影响手机上的手势。
                isFocusable = false
                addView(buildWaitingPage(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            },
            fullScreen(),
        )
        longPressCatcher = View(this).apply {
            visibility = View.GONE
            A11y.decorative(this)
            setOnLongClickListener {
                showSettings()
                true
            }
        }
        root.addView(longPressCatcher, fullScreen())
        root.addView(buildNameOverlay(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = Theme.dp(this@ReceiverActivity, Theme.SPACE_XL_DP) })
        root.addView(buildNoticeOverlay(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = Theme.dp(this@ReceiverActivity, 72) })
        // 断线恢复提示放屏幕正中：最后一帧仍在，用户要一眼看出是网络在恢复而不是卡死
        root.addView(buildRecoveryBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        root.addView(buildCornerHint(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            rightMargin = Theme.dp(this@ReceiverActivity, Theme.SPACE_XL_DP)
            bottomMargin = Theme.dp(this@ReceiverActivity, Theme.SPACE_XXL_DP)
        })
        // 画质角标固定左下角：右下角留给竖屏/模式提示，两处同时出现会互相盖住
        root.addView(buildQualityBadge(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START,
        ).apply {
            leftMargin = Theme.dp(this@ReceiverActivity, Theme.SPACE_XL_DP)
            bottomMargin = Theme.dp(this@ReceiverActivity, Theme.SPACE_XXL_DP)
        })
        root.addView(buildSettingsEntry(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply {
            rightMargin = Theme.dp(this@ReceiverActivity, 20)
            topMargin = Theme.dp(this@ReceiverActivity, 20)
        })
        root.addView(buildPromptBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))
        root.addView(buildEndBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))
        // 画质档位行加上来后面板比屏幕高，套一层滚动容器：角标开关和「完成」不能永远够不着
        root.addView(buildSettingsScroller(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ).apply { setMargins(Theme.dp(this@ReceiverActivity, Theme.SPACE_XXL_DP), 0, Theme.dp(this@ReceiverActivity, Theme.SPACE_XXL_DP), 0) })
        // 首启三步引导覆盖在最上层（FrameLayout 后 add 的盖在先 add 的之上）
        root.addView(buildOnboarding(), fullScreen())
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refitVideo() }
        setContentView(root)
        // 待连接页 / 设置面板 / 授权与结束按钮统一接遥控器焦点
        TvFocus.applyTree(root, requestInitialFocus = false)
        // T04908 裁决 1（2026-11-12）：视觉「显示设置」(右上) 先于「更多信息」(待连接页内)，
        // 但视图层级里待连接页子树在前，几何焦点搜索与深度优先遍历都先落到更多信息。
        // D-pad 输入焦点用 nextFocusId 串链（API 11+），TalkBack 线性朗读用 API 22 的
        // setAccessibilityTraversalBefore/After 显式指定；两者互补，开局焦点定位到显示设置。
        TvFocus.chain(settingsEntry, moreInfoToggle)
        A11y.traversalOrder(settingsEntry, moreInfoToggle)

        deviceNameText.text = receiverName()
        if (store.firstLaunch()) {
            // 引导结束（或跳过）后才真正起会话，保证用户最后设的设备名生效（PRD §1.2 首启零阻塞）
            showOnboarding()
        } else {
            startSessions(receiverName())
            main.post(ticker)
            root.post { settingsEntry.requestFocus() }
        }
        Log.i(TAG, "activity attached to host casting=${host.casting} recovering=${host.recovering}")
    }

    private fun fullScreen() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )

    private fun buildWaitingPage(): LinearLayout {
        waiting = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Theme.BACKGROUND)
            setPadding(Theme.dp(this@ReceiverActivity, Theme.SPACE_XXL_DP), Theme.dp(this@ReceiverActivity, 28), Theme.dp(this@ReceiverActivity, Theme.SPACE_XXL_DP), Theme.dp(this@ReceiverActivity, Theme.SPACE_XL_DP))
        }
        // PRD UI-02：主视觉区只保留设备名 + 投屏码（含环形倒计时）+ 二维码三件，降低 P-06 密度。
        mainArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        deviceNameText = TextView(this).apply {
            textSize = Theme.TITLE_SP
            setTextColor(Theme.TEXT_PRIMARY)
            gravity = Gravity.CENTER
            // setLetterSpacing 是 API 21：接收端硬下限 19，4.4 上不能在这里崩
            if (Build.VERSION.SDK_INT >= 21) letterSpacing = 0.02f
        }
        mainArea.addView(deviceNameText, matchWidth())

        val side = Theme.dp(this, 190)
        qrView = ImageView(this).apply {
            setBackgroundColor(Theme.QR_BACKGROUND)
            A11y.describe(this, A11yCopy.mirrorQr())
        }
        mainArea.addView(qrView, centeredBlock(side).apply { topMargin = Theme.dp(this@ReceiverActivity, Theme.SPACE_LG_DP) })

        // 投屏码单独占一行：和二维码并排时剩下的宽度放不下 6 位数字，会把码拆成两截
        // 两层同规格 TextView 叠在固定尺寸码槽里：换码时旧码淡出、新码淡入同时进行，中间不出现空白帧
        codeSlot = FrameLayout(this)
        ring = CountdownRingView(this).apply { A11y.decorative(this) }
        codeSlot.addView(ring, FrameLayout.LayoutParams(side, side))
        codeA = TextView(this).apply { codeStyle(this) }
        codeB = TextView(this).apply {
            codeStyle(this)
            alpha = 0f
            visibility = View.INVISIBLE
        }
        val codeLayerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        codeSlot.addView(codeA, codeLayerParams)
        codeSlot.addView(codeB, FrameLayout.LayoutParams(codeLayerParams))
        activeCode = codeA
        inactiveCode = codeB
        mainArea.addView(codeSlot, centeredBlock(side).apply { topMargin = Theme.dp(this@ReceiverActivity, Theme.SPACE_LG_DP) })
        waiting.addView(mainArea, matchWidth())

        // PRD UI-02 / §7.3：次要入口收进「更多信息」，与主区间距 ≥24dp，不与二维码争夺视觉中心。
        moreInfoToggle = TextView(this).apply {
            text = MORE_INFO_LABEL
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_SECONDARY)
            gravity = Gravity.CENTER
            A11y.describe(this, A11yCopy.moreInfo(false))
            setPadding(0, Theme.dp(this@ReceiverActivity, Theme.SPACE_SM_DP), 0, Theme.dp(this@ReceiverActivity, Theme.SPACE_SM_DP))
            setOnClickListener { toggleMoreInfo() }
        }
        waiting.addView(moreInfoToggle, matchWidth().apply { topMargin = Theme.dp(this@ReceiverActivity, Theme.SPACE_XL_DP) })

        moreInfoBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        // PRD §04 / §13.1：只写本机真能做到的入口，AirPlay 与 Miracast 在补齐前明确说不支持
        moreInfoBody.addView(TextView(this).apply {
            text = ProtocolBoundary.receiverMirrorLine()
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@ReceiverActivity, Theme.SPACE_SM_DP), 0, 0)
        }, matchWidth())
        moreInfoBody.addView(TextView(this).apply {
            text = ProtocolBoundary.receiverPushLine()
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@ReceiverActivity, Theme.SPACE_XS_DP), 0, Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP))
        }, matchWidth())
        moreInfoBody.addView(TextView(this).apply {
            text = ProtocolBoundary.receiverUnsupportedLine()
            textSize = Theme.BODY_SP
            setTextColor(StatusColors.offlineText())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP))
        }, matchWidth())

        countdownText = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(StatusColors.secondaryText(highContrastUi))
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP), 0, 0)
        }
        moreInfoBody.addView(countdownText, matchWidth())

        // PRD §5.9：访客免装入口。地址带一次性 token，只有看得到大屏的人才能上传；
        // 服务未起来时整行隐藏，不给一个打不开的地址。UI-02 把该入口收进「更多信息」。
        browserText = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@ReceiverActivity, 10), 0, 0)
            visibility = View.GONE
            A11y.describe(this, A11yCopy.browserUrl(""))
        }
        moreInfoBody.addView(browserText, matchWidth())
        // PRD §5.9：地址之外再给一张二维码，访客扫一下就能打开，不用手抄 30 多个字符
        browserQr = ImageView(this).apply {
            setBackgroundColor(Theme.QR_BACKGROUND)
            visibility = View.GONE
            A11y.describe(this, A11yCopy.browserQr())
        }
        moreInfoBody.addView(
            browserQr,
            centeredBlock(Theme.dp(this@ReceiverActivity, 120)).apply {
                topMargin = Theme.dp(this@ReceiverActivity, Theme.SPACE_SM_DP)
            },
        )

        statusText = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@ReceiverActivity, 20), 0, 0)
            A11y.liveStatus(this)
        }
        moreInfoBody.addView(statusText, matchWidth())
        moreInfoBody.addView(TextView(this).apply {
            text = "局域网直连 · 无广告 · 不保存画面"
            textSize = Theme.BODY_SP
            setTextColor(StatusColors.offlineText())
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@ReceiverActivity, Theme.SPACE_LG_DP), 0, 0)
        }, matchWidth())
        // FS-13 降级提示：系统禁止自启时，用户至少知道从应用列表手动打开
        moreInfoBody.addView(TextView(this).apply {
            text = "电视重启后若未自动打开，请从应用列表打开"
            textSize = Theme.BODY_SP
            setTextColor(StatusColors.offlineText())
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@ReceiverActivity, 6), 0, 0)
        }, matchWidth())
        waiting.addView(moreInfoBody, matchWidth())

        // FS-12 本机等级 + FS-05 四道门结论：探测要遍历解码器和显示模式表，放后台算完再回填到设置页
        Thread { probeLocalQuality() }.start()
        return waiting
    }

    /** 「更多信息」展开/收起：浏览器入口与次要文案都在其内，主视觉区始终只有三件（PRD UI-02）。 */
    private fun toggleMoreInfo() {
        val show = moreInfoBody.visibility != View.VISIBLE
        moreInfoBody.visibility = if (show) View.VISIBLE else View.GONE
        moreInfoToggle.text = if (show) MORE_INFO_COLLAPSE_LABEL else MORE_INFO_LABEL
        A11y.describe(moreInfoToggle, A11yCopy.moreInfo(show))
    }

    /**
     * 本机画质上限（分级 ∩ 硬解 ∩ 四道门）。设置页与接收端上报必须用同一个数，
     * 所以两边都从 [DeviceProbe.localCeiling] 取；不可达的档位在这里之后就再也拿不到。
     */
    private fun probeLocalQuality() {
        val report = FourGates.evaluate(DeviceProbe.gates(this))
        val ceiling = DeviceProbe.localCeiling(this)
        val summary = DeviceProbe.summary(DeviceProbe.policy(this))
        // 探测结果要能在日志里对上：设置页画质行缺失时，先分清是「没探完」还是「探完没画」
        Log.i(TAG, "quality probe: ceiling=${ceiling.label} report=${report.maxTier.label} summary=${summary.replace('\n', ' ')}")
        runOnUiThread {
            gateReport = report
            localCeiling = ceiling
            probeSummary = summary
            populateQualityRows()
        }
    }

    private fun buildNameOverlay(): TextView {
        overlayName = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            setBackgroundColor(Theme.BACKGROUND_ALPHA_80)
            setPadding(Theme.dp(this@ReceiverActivity, Theme.SPACE_XL_DP), Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP), Theme.dp(this@ReceiverActivity, Theme.SPACE_XL_DP), Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP))
            visibility = View.GONE
        }
        return overlayName
    }

    /** 投屏中状态栏和待连接页都不可见：暂停、接管请求、接管超时等状态在这里短暂提示。 */
    private fun buildNoticeOverlay(): TextView {
        noticeOverlay = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(StatusColors.overlayText())
            setBackgroundColor(StatusColors.OVERLAY_LAYER)
            setPadding(Theme.dp(this@ReceiverActivity, 20), Theme.dp(this@ReceiverActivity, 10), Theme.dp(this@ReceiverActivity, 20), Theme.dp(this@ReceiverActivity, 10))
            visibility = View.GONE
        }
        return noticeOverlay
    }

    private fun showNotice(text: String) {
        // DLNA 播放不置 casting，但同样整屏是画面，浮层是唯一看得见的通道
        if (text.isEmpty() || (!isCasting && !host.dlnaPlaying)) return
        Log.i(TAG, "notice overlay: $text")
        noticeOverlay.text = text
        noticeOverlay.visibility = View.VISIBLE
        main.removeCallbacks(hideNotice)
        main.postDelayed(hideNotice, NOTICE_MS)
    }

    private val hideNotice = Runnable { noticeOverlay.visibility = View.GONE }

    private fun buildRecoveryBar(): TextView {
        recoveryBar = TextView(this).apply {
            text = CastStatus.line(CastStatus.State.RECOVERING)
            textSize = Theme.BODY_SP
            maxLines = 1
            setTextColor(StatusColors.textOf(CastStatus.State.RECOVERING))
            setBackgroundColor(StatusColors.BAR_LAYER)
            setPadding(
                Theme.dp(this@ReceiverActivity, 20), Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP),
                Theme.dp(this@ReceiverActivity, 20), Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP),
            )
            visibility = View.GONE
        }
        return recoveryBar
    }

    private fun showRecovery(active: Boolean) {
        Log.i(TAG, "recovery banner=$active")
        recoveryBar.visibility = if (active) View.VISIBLE else View.GONE
    }

    /** 角落提示：竖屏标签与「比例不同可切换模式」都只出现 3 秒（PRD FS-08 步骤 5）。 */
    private fun buildCornerHint(): TextView {
        cornerHint = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            setBackgroundColor(Theme.BACKGROUND_ALPHA_80)
            setPadding(Theme.dp(this@ReceiverActivity, Theme.SPACE_LG_DP), Theme.dp(this@ReceiverActivity, Theme.SPACE_SM_DP), Theme.dp(this@ReceiverActivity, Theme.SPACE_LG_DP), Theme.dp(this@ReceiverActivity, Theme.SPACE_SM_DP))
            visibility = View.GONE
        }
        return cornerHint
    }

    private fun showCornerHint(text: String) {
        Log.i(TAG, "corner hint: $text")
        cornerHint.text = text
        cornerHint.visibility = View.VISIBLE
        main.removeCallbacks(hideCornerHint)
        main.postDelayed(hideCornerHint, NOTICE_MS)
    }

    private val hideCornerHint = Runnable { cornerHint.visibility = View.GONE }

    /** 画面标识（PRD FS-05）：默认不显示画质信息，用户在设置页开启后才常驻当前画幅与码率。 */
    private fun buildQualityBadge(): TextView {
        qualityBadge = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_SECONDARY)
            setPadding(
                Theme.dp(this@ReceiverActivity, Theme.SPACE_LG_DP), Theme.dp(this@ReceiverActivity, Theme.SPACE_SM_DP),
                Theme.dp(this@ReceiverActivity, Theme.SPACE_LG_DP), Theme.dp(this@ReceiverActivity, Theme.SPACE_SM_DP),
            )
            visibility = View.GONE
        }
        return qualityBadge
    }

    /** 读数只反映「此刻真的在放画面」：没在投屏时角标留着会把最后一帧的码率当成现状。 */
    private fun refreshQualityBadge() {
        val width = host.qualityWidth
        if (!store.qualityBadgeEnabled() || !isCasting || width <= 0) {
            qualityBadge.visibility = View.GONE
            return
        }
        qualityBadge.text = "${width}x${host.qualityHeight} · ${bitRateText(host.qualityKbps)}"
        qualityBadge.visibility = View.VISIBLE
    }

    /**
     * 角标上的码率写法：够 1 Mbps 才写成 Mbps（带一位小数），否则直接写 kbps。
     * 镜像一屏静止内容约 50kbps，取整成 Mbps 就会写成「0 Mbps」，
     * 用户读到的是「没在传」，实际是「画面没变化」——这两件事必须能分清。
     */
    private fun bitRateText(kbps: Int): String =
        if (kbps < 1_000) "$kbps kbps" else "${kbps / 1000}.${kbps % 1000 / 100} Mbps"

    /** 待连接页右上角的显示设置入口；投屏中收起，长按画面仍可唤出。 */
    private fun buildSettingsEntry(): TextView {
        settingsEntry = TextView(this).apply {
            text = "显示设置"
            textSize = Theme.BODY_SP
            setTextColor(Theme.ACCENT)
            setBackgroundColor(Theme.BACKGROUND_ALPHA_80)
            setPadding(
                Theme.dp(this@ReceiverActivity, Theme.SPACE_LG_DP), Theme.dp(this@ReceiverActivity, 10),
                Theme.dp(this@ReceiverActivity, Theme.SPACE_LG_DP), Theme.dp(this@ReceiverActivity, 10),
            )
            setOnClickListener { showSettings() }
            A11y.describe(this, A11yCopy.settingsEntry(false))
        }
        return settingsEntry
    }

    /** 新编码配置到达：跟随方向并按模式重新计算画面区域（PRD FS-08 步骤 2）。 */
    private fun onVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        latestWidth = width
        latestHeight = height
        if (shownWidth <= 0 || shownHeight <= 0 || followRotation) {
            shownWidth = width
            shownHeight = height
        }
        Log.i(TAG, "video size ${width}x$height follow=$followRotation shown=${shownWidth}x$shownHeight")
        refitVideo()
        // 关闭旋转跟随后画面冻结在旧方向：新方向既不上屏，也不该提示「竖屏」
        val shownPortrait = shownHeight > shownWidth
        val adopted = shownWidth == width && shownHeight == height
        if (isCasting && adopted && shownPortrait && portraitLabel && !lastPortrait) showCornerHint("竖屏")
        if (isCasting && !shownPortrait && !modeHintShown && DisplayFit.mismatched(root.width, root.height, shownWidth, shownHeight)) {
            modeHintShown = true
            showCornerHint("比例与屏幕不同 · 长按可切换显示模式")
        }
        lastPortrait = shownPortrait
    }

    private fun refitVideo() {
        // 同一块 Surface 同时只承载一路内容：本地媒体有画幅时优先按它摆放，
        // 否则用镜像的 CONFIG 尺寸。否则推送视频会被 Surface 直接拉满，竖屏片变横扁
        val videoW = if (dlnaWidth > 0 && dlnaHeight > 0) dlnaWidth else shownWidth
        val videoH = if (dlnaWidth > 0 && dlnaHeight > 0) dlnaHeight else shownHeight
        if (videoW <= 0 || videoH <= 0) return
        val viewport = DisplayFit.viewport(displayMode, root.width, root.height, videoW, videoH)
        if (viewport.width <= 0 || viewport.height <= 0) return
        Log.i(
            TAG,
            "fit ${displayMode.name} video=${videoW}x$videoH box=${root.width}x${root.height} -> ${viewport.width}x${viewport.height}",
        )
        val params = surfaceView.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.width == viewport.width && params.height == viewport.height) return
        params.width = viewport.width
        params.height = viewport.height
        params.gravity = Gravity.CENTER
        surfaceView.layoutParams = params
    }

    private fun buildSettingsScroller(): ScrollView {
        val panel = buildSettingsPanel()
        settingsScroller = ScrollView(this).apply {
            isFillViewport = true
            addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            visibility = View.GONE
        }
        return settingsScroller
    }

    private fun buildSettingsPanel(): LinearLayout {
        settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BACKGROUND)
            setPadding(Theme.dp(this@ReceiverActivity, Theme.SPACE_XL_DP), Theme.dp(this@ReceiverActivity, 20), Theme.dp(this@ReceiverActivity, Theme.SPACE_XL_DP), Theme.dp(this@ReceiverActivity, Theme.SPACE_LG_DP))
        }
        settingsPanel.addView(TextView(this).apply {
            text = "显示设置"
            textSize = Theme.TITLE_SP
            setTextColor(Theme.TEXT_PRIMARY)
            setPadding(0, 0, 0, Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP))
        })
        for (mode in DisplayMode.entries) {
            val row = TextView(this).apply {
                textSize = Theme.BODY_SP
                setPadding(0, Theme.dp(this@ReceiverActivity, 10), 0, Theme.dp(this@ReceiverActivity, 10))
                setOnClickListener { chooseMode(mode) }
            }
            modeRows[mode] = row
            settingsPanel.addView(row, matchWidth())
        }
        settingsPanel.addView(View(this).apply { setBackgroundColor(Theme.DIVIDER) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(this@ReceiverActivity, 1)))
        rotationRow = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            setPadding(0, Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP), 0, Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP))
            setOnClickListener {
                followRotation = !followRotation
                store.saveFollowRotation(followRotation)
                if (followRotation && latestWidth > 0) {
                    shownWidth = latestWidth
                    shownHeight = latestHeight
                    lastPortrait = shownHeight > shownWidth
                }
                Log.i(TAG, "follow rotation -> $followRotation shown=${shownWidth}x$shownHeight")
                refreshSettings()
                refitVideo()
            }
        }
        settingsPanel.addView(rotationRow, matchWidth())
        labelRow = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            setPadding(0, 0, 0, Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP))
            setOnClickListener {
                portraitLabel = !portraitLabel
                store.savePortraitLabel(portraitLabel)
                refreshSettings()
            }
        }
        settingsPanel.addView(labelRow, matchWidth())
        autoStartRow = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            setPadding(0, 0, 0, Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP))
            setOnClickListener {
                store.saveAutoStartEnabled(!store.autoStartEnabled())
                refreshSettings()
            }
        }
        settingsPanel.addView(autoStartRow, matchWidth())
        // PRD FS-04：信令端点由部署方决定，所以只做手填入口，不内置任何默认外部地址
        crossNetRow = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            setPadding(0, Theme.dp(this@ReceiverActivity, Theme.SPACE_XS_DP), 0, Theme.dp(this@ReceiverActivity, Theme.SPACE_SM_DP))
            setOnClickListener { editSignaling() }
        }
        settingsPanel.addView(crossNetRow, matchWidth())
        settingsPanel.addView(TextView(this).apply {
            text = "填了才启用；只交换建连信息，画面不经服务器"
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_SECONDARY)
            setPadding(0, 0, 0, Theme.dp(this@ReceiverActivity, 10))
        }, matchWidth())
        settingsPanel.addView(divider(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            Theme.dp(this@ReceiverActivity, 1),
        ))
        // FS-05：先把「本机最高支持哪一档、为什么没有 4K」写在档位列表上方，
        // 再列出可选档位——不可达的档位由 populateQualityRows 决定连行都不生成
        gateLine = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_SECONDARY)
            setPadding(0, 0, 0, Theme.dp(this@ReceiverActivity, Theme.SPACE_SM_DP))
        }
        settingsPanel.addView(gateLine, matchWidth())
        qualityContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsPanel.addView(qualityContainer, matchWidth())
        badgeRow = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            setPadding(0, Theme.dp(this@ReceiverActivity, 10), 0, Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP))
            setOnClickListener {
                store.saveQualityBadgeEnabled(!store.qualityBadgeEnabled())
                refreshSettings()
                refreshQualityBadge()
            }
        }
        settingsPanel.addView(badgeRow, matchWidth())
        settingsPanel.addView(divider(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            Theme.dp(this@ReceiverActivity, 1),
        ))
        settingsPanel.addView(TextView(this).apply {
            text = "隐私"
            textSize = Theme.TITLE_SP
            setTextColor(Theme.TEXT_PRIMARY)
            setPadding(0, Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP), 0, Theme.dp(this@ReceiverActivity, Theme.SPACE_SM_DP))
        }, matchWidth())
        settingsPanel.addView(TextView(this).apply {
            // PRD FS-11「首次启动一句话数据边界说明」在设置页也要有一句，不使用长文弹窗
            text = "画面、文件名、地址都不保存；投屏内容只在本次会话内使用"
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_SECONDARY)
            setPadding(0, 0, 0, Theme.dp(this@ReceiverActivity, 10))
        }, matchWidth())
        diagnosticsRow = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            setPadding(0, Theme.dp(this@ReceiverActivity, 10), 0, Theme.dp(this@ReceiverActivity, 10))
            setOnClickListener { toggleDiagnostics() }
        }
        settingsPanel.addView(diagnosticsRow, matchWidth())
        clearDataRow = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            setPadding(0, Theme.dp(this@ReceiverActivity, 10), 0, Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP))
            setOnClickListener { clearLocalData() }
        }
        settingsPanel.addView(clearDataRow, matchWidth())
        settingsPanel.addView(Button(this).apply {
            text = "完成"
            setTextColor(Theme.ACCENT)
            A11y.describe(this, A11yCopy.finish())
            setOnClickListener { hideSettings() }
        }, matchWidth())
        refreshSettings()
        return settingsPanel
    }

    private fun divider() = View(this).apply { setBackgroundColor(Theme.DIVIDER) }

    /** 探测结果到位后才建档位行：列表内容取决于本机上限，早建会把 4K 行留在页面上。 */
    private fun populateQualityRows() {
        val ceiling = localCeiling ?: return
        if (qualityRows.isNotEmpty()) return
        Log.i(TAG, "quality rows built for ${ceiling.label} (${QualityTiers.reachable(ceiling).size} tiers)")
        val headline = gateReport?.let { FourGates.headline(ceiling, it) } ?: "本机最高支持 ${ceiling.label}"
        gateLine.text = if (probeSummary.isBlank()) headline else "$headline\n$probeSummary"
        val options = ArrayList<QualityTier?>()
        options += null
        options += QualityTiers.reachable(ceiling)
        for (tier in options) {
            val row = TextView(this).apply {
                textSize = Theme.BODY_SP
                setPadding(0, Theme.dp(this@ReceiverActivity, 10), 0, Theme.dp(this@ReceiverActivity, 10))
                setOnClickListener { chooseQuality(tier) }
            }
            qualityRows[tier] = row
            qualityContainer.addView(row, matchWidth())
        }
        refreshSettings()
    }

    private fun chooseQuality(tier: QualityTier?) {
        if (tier == qualityLock) {
            hideSettings()
            return
        }
        qualityLock = tier
        store.saveQualityLock(tier)
        // 投屏中改锁定要立刻重谈上限，否则这一档要到下次投屏才生效
        host.applyQualityLock()
        Log.i(TAG, "quality lock -> ${tier?.name ?: "auto"}")
        refreshSettings()
    }

    private fun refreshSettings() {
        for ((mode, row) in modeRows) {
            row.text = "${if (mode == displayMode) "●" else "○"} ${mode.label}\n${mode.hint}"
            row.setTextColor(if (mode == displayMode) Theme.ACCENT else Theme.TEXT_SECONDARY)
            A11y.describe(row, A11yCopy.mode(mode.label, mode.hint, mode == displayMode))
            A11y.switchRow(row, mode == displayMode)
        }
        rotationRow.text = "旋转跟随：${if (followRotation) "开" else "关"}"
        A11y.describe(rotationRow, A11yCopy.toggle("旋转跟随", followRotation))
        A11y.switchRow(rotationRow, followRotation)
        labelRow.text = "竖屏标签：${if (portraitLabel) "开" else "关"}"
        A11y.describe(labelRow, A11yCopy.toggle("竖屏标签", portraitLabel))
        A11y.switchRow(labelRow, portraitLabel)
        autoStartRow.text = "开机自启：${if (store.autoStartEnabled()) "开" else "关"}"
        A11y.describe(autoStartRow, A11yCopy.toggle("开机自启", store.autoStartEnabled()))
        A11y.switchRow(autoStartRow, store.autoStartEnabled())
        val signaling = store.signalingUrl()
        val crossNetOn = SignalingSettings.enabled(signaling)
        crossNetRow.text = if (crossNetOn) "跨网信令：已配置（$signaling）" else "跨网信令：未配置（仅局域网）"
        crossNetRow.setTextColor(if (crossNetOn) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY)
        A11y.describe(crossNetRow, A11yCopy.signaling(crossNetOn, signaling))
        for ((tier, row) in qualityRows) {
            row.text = if (tier == null) {
                "${if (qualityLock == null) "●" else "○"} 自动（推荐）"
            } else {
                "${if (qualityLock == tier) "●" else "○"} ${tier.label} · ${tier.width}x${tier.height} ${tier.fps}帧"
            }
            row.setTextColor(if (tier == qualityLock) Theme.ACCENT else Theme.TEXT_SECONDARY)
            if (tier == null) {
                A11y.describe(row, A11yCopy.qualityAuto(qualityLock == null))
                A11y.switchRow(row, qualityLock == null)
            } else {
                A11y.describe(row, A11yCopy.quality(tier.label, tier.width, tier.height, tier.fps, qualityLock == tier))
                A11y.switchRow(row, qualityLock == tier)
            }
        }
        badgeRow.visibility = if (qualityRows.isEmpty()) View.GONE else View.VISIBLE
        badgeRow.text = "画质角标：${if (store.qualityBadgeEnabled()) "开" else "关"}"
        A11y.describe(badgeRow, A11yCopy.toggle("画质角标", store.qualityBadgeEnabled()))
        A11y.switchRow(badgeRow, store.qualityBadgeEnabled())
        diagnosticsRow.text = "崩溃日志：${if (diagnostics.enabled()) "开（仅本机 · 7 天自动清除）" else "关（默认）"}"
        A11y.describe(diagnosticsRow, A11yCopy.diagnostics(diagnostics.enabled()))
        A11y.switchRow(diagnosticsRow, diagnostics.enabled())
        clearDataRow.text = "清除本地日志与缓存（${diagnostics.count()} 条）"
        A11y.describe(clearDataRow, A11yCopy.clearData(diagnostics.count()))
        // 画质档位行是探完能力后动态 addView 的，刷新时再扫一次树；不抢当前焦点
        TvFocus.applyTree(settingsPanel, requestInitialFocus = false)
    }

    /** PRD FS-04：留空即关闭跨网，大屏不向任何外部服务发请求；填了才启动待命。 */
    private fun editSignaling() {
        val input = EditText(this).apply {
            setText(store.signalingUrl())
            hint = "https://你的信令服务"
            setSingleLine()
            setTextColor(Theme.TEXT_PRIMARY)
        }
        AlertDialog.Builder(this)
            .setTitle("跨网信令服务")
            .setMessage("留空则不启用跨网。信令只交换建连信息，画面仍点对点直连、不经服务器。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                store.saveSignalingUrl(input.text.toString())
                host.reloadCrossNet()
                refreshSettings()
                val on = SignalingSettings.enabled(store.signalingUrl())
                showCornerHint(if (on) "跨网已启用" else "跨网已关闭（仅局域网）")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** PRD FS-11：诊断默认关闭；开启前把口径说明白，不做静默开启。 */
    private fun toggleDiagnostics() {
        if (diagnostics.enabled()) {
            diagnostics.setEnabled(false)
            refreshSettings()
            showCornerHint("崩溃日志已关闭并清除")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("开启崩溃日志？")
            .setMessage("只在本机记录崩溃堆栈与设备型号，不含画面、文件名和地址；不上传，7 天后自动清除。可随时关闭并清除。")
            .setPositiveButton("开启") { _, _ ->
                diagnostics.setEnabled(true)
                refreshSettings()
                showCornerHint("崩溃日志已开启（仅本机）")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** PRD FS-11「用户清理缓存：二次确认；清空后不影响已授权设备」。 */
    private fun clearLocalData() {
        AlertDialog.Builder(this)
            .setTitle("清除本地日志与缓存？")
            .setMessage("删除本机崩溃日志与临时缓存；已授权设备、投屏码和显示设置不受影响。")
            .setPositiveButton("清除") { _, _ ->
                val removed = diagnostics.clearCaches()
                refreshSettings()
                showCornerHint("已清除 $removed 项 · 已授权设备保留")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSettings() {
        diagnostics.purge()
        refreshSettings()
        settingsScroller.visibility = View.VISIBLE
        // 覆盖层隔离（设计 §8.1）：底页与设置入口退出无障碍树，焦点落到当前选中模式行（§8.3）
        A11y.hideSubtree(waiting)
        A11y.hideSubtree(settingsEntry)
        modeRows[displayMode]?.let {
            it.requestFocus()
            it.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
        }
        A11y.announce(settingsPanel, A11yCopy.settingsOpened(), "settings-open")
    }

    private fun hideSettings() {
        settingsScroller.visibility = View.GONE
        A11y.showSubtree(waiting)
        A11y.showSubtree(settingsEntry)
        settingsEntry.requestFocus()
        settingsEntry.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
        A11y.announce(settingsEntry, A11yCopy.settingsClosed(), "settings-close")
    }

    private fun chooseMode(mode: DisplayMode) {
        if (mode == displayMode) {
            hideSettings()
            return
        }
        if (mode.needsConfirm) {
            AlertDialog.Builder(this)
                .setTitle("使用拉伸？")
                .setMessage("拉伸会让画面变形，之后可以随时改回完整显示。")
                .setPositiveButton("确定") { _, _ -> applyMode(mode) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        applyMode(mode)
    }

    private fun applyMode(mode: DisplayMode) {
        displayMode = mode
        store.saveDisplayMode(mode)
        Log.i(TAG, "display mode -> ${mode.name}")
        // 图片不走 Surface，refitVideo 管不到它，只能靠 scaleType 表达同一种模式
        dlnaImageView.scaleType = scaleTypeFor(mode)
        refreshSettings()
        refitVideo()
        hideSettings()
    }

    /**
     * 图片层的适配方式与显示模式对齐。图片由 ImageView 直接渲染，
     * 与镜像/推送视频共用的那块 Surface 无关，所以模式要在这里单独落一次。
     */
    private fun scaleTypeFor(mode: DisplayMode): ImageView.ScaleType = when (mode) {
        DisplayMode.FIT -> ImageView.ScaleType.FIT_CENTER
        DisplayMode.FILL -> ImageView.ScaleType.CENTER_CROP
        DisplayMode.STRETCH -> ImageView.ScaleType.FIT_XY
    }

    private fun buildPromptBar(): View {
        promptBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.SURFACE_ALPHA_90)
            visibility = View.GONE
            setPadding(Theme.dp(this@ReceiverActivity, Theme.SPACE_LG_DP), Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP), Theme.dp(this@ReceiverActivity, Theme.SPACE_LG_DP), Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP))
        }
        promptText = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            A11y.liveStatus(this)
        }
        promptBar.addView(promptText)
        promptDeny = Button(this).apply {
            text = "拒绝"
            setTextColor(Theme.DANGER)
            setOnClickListener { respondToPrompt(false) }
        }
        promptBar.addView(promptDeny)
        promptAllow = Button(this).apply {
            text = "允许"
            setTextColor(Theme.ACCENT)
            setOnClickListener { respondToPrompt(true) }
        }
        promptBar.addView(promptAllow)
        return promptBar
    }

    private fun buildEndBar(): View {
        endBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.SURFACE_ALPHA_90)
            visibility = View.GONE
            setPadding(Theme.dp(this@ReceiverActivity, Theme.SPACE_LG_DP), Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP), Theme.dp(this@ReceiverActivity, Theme.SPACE_LG_DP), Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP))
        }
        endText = TextView(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        endBar.addView(endText)
        endCancelButton = Button(this).apply {
            text = "取消"
            setTextColor(Theme.TEXT_SECONDARY)
            A11y.describe(this, A11yCopy.endCancel())
            setOnClickListener { cancelEndConfirm() }
        }
        endBar.addView(endCancelButton)
        endConfirmButton = Button(this).apply {
            text = "结束"
            setTextColor(Theme.DANGER)
            A11y.describe(this, A11yCopy.endConfirm())
            setOnClickListener { confirmEnd() }
        }
        endBar.addView(endConfirmButton)
        return endBar
    }

    /**
     * 只用来回答「现在能不能启动过渡」：后台每秒仍会刷新并提交新码，
     * 但不能在不可见时排队动画。会话生命周期仍由 ReceiverHost 管，不在这里启停。
     */
    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    /** FS-09：投屏中第一次返回只提示，10 秒内不再按就自动取消，第二次才真的结束。 */
    @Deprecated("Activity's back callback; keeps the app free of AndroidX")
    override fun onBackPressed() {
        if (!isCasting) {
            super.onBackPressed()
            return
        }
        if (endBar.visibility == View.VISIBLE) confirmEnd() else showEndConfirm()
    }

    private fun showEndConfirm() {
        endConfirmUntil = System.currentTimeMillis() + END_CONFIRM_MS
        endBar.visibility = View.VISIBLE
        endText.text = "结束投屏？"
        A11y.announce(endBar, A11yCopy.endConfirmBar(), "end-confirm")
    }

    private fun tickEndConfirm() {
        if (endBar.visibility != View.VISIBLE) return
        val leftMs = endConfirmUntil - System.currentTimeMillis()
        if (leftMs <= 0L) {
            cancelEndConfirm()
            return
        }
        endText.text = "结束投屏？${(leftMs + 999) / 1000} 秒后自动取消"
    }

    private fun cancelEndConfirm() {
        endConfirmUntil = 0L
        endBar.visibility = View.GONE
    }

    private fun confirmEnd() {
        cancelEndConfirm()
        host.endActiveSession()
        statusText.text = "已结束投屏"
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun centeredBlock(side: Int) = LinearLayout.LayoutParams(side, side).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun receiverName(): String = store.deviceName(ReceiverHost.defaultDeviceName())

    private fun startSessions(name: String) {
        host.start(name)
        host.attachUi(hostUi)
        ReceiverService.start(this)
    }

    // ───────────────────────── 首启三步引导（PRD §1.2） ─────────────────────────

    /** 覆盖待连接页的全屏卡片：步骤提示 + 标题 + 正文 +（按步出现的输入框/开关）+ 导航按钮 + 跳过全部。 */
    private fun buildOnboarding(): FrameLayout {
        onboarding = FrameLayout(this).apply { setBackgroundColor(Theme.BACKGROUND); visibility = View.GONE }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Theme.SURFACE)
            setPadding(Theme.dp(this@ReceiverActivity, Theme.SPACE_XXL_DP), Theme.dp(this@ReceiverActivity, 28), Theme.dp(this@ReceiverActivity, Theme.SPACE_XXL_DP), Theme.dp(this@ReceiverActivity, 28))
        }
        onboardStepHint = TextView(this).apply { textSize = Theme.BODY_SP; setTextColor(Theme.TEXT_SECONDARY); gravity = Gravity.CENTER }
        card.addView(onboardStepHint, matchWidth())
        onboardTitle = TextView(this).apply {
            textSize = Theme.TITLE_SP; setTextColor(Theme.TEXT_PRIMARY); gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@ReceiverActivity, Theme.SPACE_LG_DP), 0, 0)
        }
        card.addView(onboardTitle, matchWidth())
        onboardBody = TextView(this).apply {
            textSize = Theme.BODY_SP; setTextColor(Theme.TEXT_SECONDARY); gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@ReceiverActivity, Theme.SPACE_LG_DP), 0, 0)
        }
        card.addView(onboardBody, matchWidth())
        onboardInput = EditText(this).apply {
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_PRIMARY)
            setHintTextColor(Theme.TEXT_SECONDARY)
            setBackgroundColor(Theme.BACKGROUND)
            hint = "设备名"
            gravity = Gravity.CENTER
            visibility = View.GONE
            A11y.describe(this, A11yCopy.onboardInputLabel())
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            p.topMargin = Theme.dp(this@ReceiverActivity, Theme.SPACE_LG_DP)
            p.leftMargin = Theme.dp(this@ReceiverActivity, Theme.SPACE_XL_DP)
            p.rightMargin = Theme.dp(this@ReceiverActivity, Theme.SPACE_XL_DP)
            layoutParams = p
        }
        card.addView(onboardInput)
        onboardAutoToggle = TextView(this).apply {
            textSize = Theme.BODY_SP; setTextColor(Theme.TEXT_PRIMARY); gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@ReceiverActivity, Theme.SPACE_LG_DP), 0, 0)
            setOnClickListener { store.saveAutoStartEnabled(!store.autoStartEnabled()); refreshOnboardAutoToggle() }
        }
        card.addView(onboardAutoToggle, matchWidth())

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, Theme.dp(this@ReceiverActivity, Theme.SPACE_XL_DP), 0, 0) }
        onboardBack = Button(this).apply { text = "上一步"; setTextColor(Theme.TEXT_SECONDARY); A11y.describe(this, A11yCopy.onboardingBack()); setOnClickListener { prevOnboardingStep() } }
        onboardSkip = Button(this).apply { text = "跳过"; setTextColor(Theme.ACCENT); A11y.describe(this, A11yCopy.onboardingSkip()); setOnClickListener { skipOnboardingStep() } }
        onboardNext = Button(this).apply { text = "下一步"; setTextColor(Theme.ACCENT); A11y.describe(this, A11yCopy.onboardingNext(false)); setOnClickListener { advanceOrFinish() } }
        row.addView(onboardBack); row.addView(onboardSkip); row.addView(onboardNext)
        card.addView(row, matchWidth())

        onboarding.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ).apply {
            leftMargin = Theme.dp(this@ReceiverActivity, 48)
            rightMargin = Theme.dp(this@ReceiverActivity, 48)
        })
        onboardSkipAll = TextView(this).apply {
            text = "跳过全部"
            textSize = Theme.BODY_SP
            setTextColor(Theme.TEXT_SECONDARY)
            A11y.describe(this, A11yCopy.onboardingSkipAll())
            setPadding(Theme.dp(this@ReceiverActivity, Theme.SPACE_LG_DP), Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP), Theme.dp(this@ReceiverActivity, Theme.SPACE_LG_DP), Theme.dp(this@ReceiverActivity, Theme.SPACE_MD_DP))
            setOnClickListener { finishOnboarding(store.deviceName(Onboarding.defaultDeviceName(Build.MODEL))) }
        }
        onboarding.addView(onboardSkipAll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END,
        ).apply { rightMargin = Theme.dp(this@ReceiverActivity, 20); topMargin = Theme.dp(this@ReceiverActivity, 20) })
        return onboarding
    }

    private fun showOnboarding() {
        onboardStep = 1
        renderOnboardingStep()
        onboarding.visibility = View.VISIBLE
        A11y.hideSubtree(waiting)
        A11y.hideSubtree(settingsEntry)
        onboardNext.requestFocus()
        onboardNext.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    private fun renderOnboardingStep() {
        onboardStepHint.text = "第 $onboardStep / 3 步"
        when (onboardStep) {
            1 -> {
                onboardTitle.text = "允许局域网访问"
                onboardBody.text = "AirMirror 通过局域网接收投屏。请让手机与这台电视连接同一个 Wi-Fi。无需任何额外授权，跳过也可正常使用。"
                onboardInput.visibility = View.GONE
                onboardAutoToggle.visibility = View.GONE
                onboardBack.visibility = View.GONE
                onboardNext.text = "下一步"
            }
            2 -> {
                onboardTitle.text = "允许后台运行 / 自启"
                onboardBody.text = "开启后电视重启会自动打开接收端，并在后台持续等待投屏。若系统拦截自启，需到厂商设置里加入白名单（PRD FS-13）。"
                onboardInput.visibility = View.GONE
                onboardAutoToggle.visibility = View.VISIBLE
                refreshOnboardAutoToggle()
                onboardBack.visibility = View.VISIBLE
                onboardNext.text = "下一步"
            }
            else -> {
                onboardTitle.text = "设置设备名"
                onboardBody.text = "其他设备会在列表里看到这个名字。可保留默认，也可改成好记的名字。"
                if (onboardInput.text.toString().isEmpty()) {
                    onboardInput.setText(store.deviceName(Onboarding.defaultDeviceName(Build.MODEL)))
                }
                onboardInput.visibility = View.VISIBLE
                onboardInput.setSelection(onboardInput.text.length)
                onboardAutoToggle.visibility = View.GONE
                onboardBack.visibility = View.VISIBLE
                onboardNext.text = "完成"
            }
        }
        A11y.describe(onboardNext, A11yCopy.onboardingNext(onboardStep == 3))
        val stepTitle = onboardTitle.text.toString()
        val stepCopy = A11yCopy.onboardingStep(onboardStep, 3, stepTitle)
        A11y.describe(onboardStepHint, stepCopy)
        A11y.announce(onboarding, stepCopy, "onboard-step-$onboardStep")
    }

    private fun refreshOnboardAutoToggle() {
        onboardAutoToggle.text = "开机自启：${if (store.autoStartEnabled()) "开" else "关"}（点击切换）"
        A11y.describe(onboardAutoToggle, A11yCopy.toggle("开机自启", store.autoStartEnabled()))
        A11y.switchRow(onboardAutoToggle, store.autoStartEnabled())
    }

    /** 主行动按钮：前两步推进到下一步，第三步收尾引导。 */
    private fun advanceOrFinish() {
        if (onboardStep < 3) {
            onboardStep++
            renderOnboardingStep()
        } else {
            finishOnboarding(onboardInput.text.toString())
        }
    }

    /** 每步可跳过：前两步跳过即推进；第三步跳过即沿用默认设备名收尾（丢弃输入框改动）。 */
    private fun skipOnboardingStep() {
        if (onboardStep < 3) {
            onboardStep++
            renderOnboardingStep()
        } else {
            finishOnboarding(store.deviceName(Onboarding.defaultDeviceName(Build.MODEL)))
        }
    }

    private fun prevOnboardingStep() {
        if (onboardStep > 1) {
            onboardStep--
            renderOnboardingStep()
        }
    }

    /** 引导结束（正常完成或跳过全部）：存名、清首启标记、起会话、露出待连接页。 */
    private fun finishOnboarding(rawName: String) {
        val name = Onboarding.sanitizeName(rawName, store.deviceName(Onboarding.defaultDeviceName(Build.MODEL)))
        store.saveDeviceName(name)
        store.setFirstLaunch(false)
        deviceNameText.text = name
        onboarding.visibility = View.GONE
        A11y.showSubtree(waiting)
        A11y.showSubtree(settingsEntry)
        // 引导结束回到待连接页：起始焦点落到显示设置（§8.3，与 hideSettings 一致）
        settingsEntry.requestFocus()
        settingsEntry.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
        startSessions(name)
        main.post(ticker)
    }


    /** 宿主把会话与 DLNA 的进度回放到界面；界面不持有监听生命周期。 */
    private val hostUi = object : ReceiverHost.Ui {
        override fun onStatus(text: String) = runOnUiThread { statusText.text = text }
        override fun onPrompt(prompt: ReceiverSession.Prompt) = runOnUiThread { showPrompt(prompt) }
        override fun onPromptDismissed() = runOnUiThread { promptBar.visibility = View.GONE }
        override fun onCasting(casting: Boolean, senderName: String) =
            runOnUiThread { this@ReceiverActivity.onCasting(casting, senderName) }
        override fun onNotice(text: String) = runOnUiThread { showNotice(text) }
        override fun onRecovery(active: Boolean) = runOnUiThread { showRecovery(active) }
        override fun onVideoSize(width: Int, height: Int) =
            runOnUiThread { this@ReceiverActivity.onVideoSize(width, height) }
        override fun onQualityReadout(width: Int, height: Int, kiloBits: Int) =
            runOnUiThread {
                refreshQualityBadge()
                refreshCastingOverlay()
            }
        override fun onDlnaStatus(text: String) = runOnUiThread { statusText.text = text }
        override fun onDlnaPlaying(playing: Boolean) = runOnUiThread {
            if (playing) {
                showCasting("")
            } else if (!isCasting) {
                // 推送放完或放失败都回到待连接页：停在最后一帧就是 PRD FS-02「格式兜底」里禁止的死胡同
                onCasting(false, "")
            }
        }
        override fun onDlnaImage(image: Bitmap, title: String) = runOnUiThread {
            // 图片走 ImageView，不占 Surface：把媒体画幅清掉，免得下次视频沿用上一部的尺寸
            dlnaWidth = 0
            dlnaHeight = 0
            dlnaImageView.setImageBitmap(image)
            dlnaImageView.visibility = View.VISIBLE
            // 图片画面也是「投屏中」：待连接页必须让位，否则 QR 码会一直压在图上
            waiting.visibility = View.GONE
        }
        override fun onDlnaImageCleared() = runOnUiThread {
            dlnaImageView.setImageDrawable(null)
            dlnaImageView.visibility = View.GONE
            if (!isCasting) waiting.visibility = View.VISIBLE
        }

        override fun onDlnaVideoSize(width: Int, height: Int) = runOnUiThread {
            if (width <= 0 || height <= 0) return@runOnUiThread
            dlnaWidth = width
            dlnaHeight = height
            refitVideo()
        }
    }

    /** 两层码字必须同规格：字号、字距、行数任一不同，淡化中都会看到跳动。 */
    private fun codeStyle(view: TextView) {
        view.textSize = Theme.CODE_SP
        view.setTextColor(Theme.ACCENT)
        view.gravity = Gravity.CENTER
        if (Build.VERSION.SDK_INT >= 21) view.letterSpacing = 0.02f
        view.maxLines = 1
    }

    /**
     * 码值变化才改视图：每秒刷新在这里被 [CodeFadePolicy.shouldCommit] 挡掉。
     * 两层同时开始、同一时长，任一瞬间至少一层不透明，过渡期间不出现空白帧。
     */
    private fun renderCode(next: String) {
        val animationsEnabled = DeviceProbe.policy(this).animationsEnabled
        val input = CodeFadePolicy.Input(
            animationsEnabled = animationsEnabled,
            firstRender = shownCode == null,
            previous = shownCode,
            next = next,
            pageVisible = waiting.visibility == View.VISIBLE,
            resumed = resumed,
        )
        if (!CodeFadePolicy.shouldCommit(input)) return
        val animate = CodeFadePolicy.shouldAnimate(input)
        val fadeMs = FocusPolicy.codeFadeMs(animationsEnabled)
        val incoming = inactiveCode
        val outgoing = activeCode
        // 锁定/解锁可能紧跟一次轮换：未跑完的过渡先取消，避免两条动画队列叠加
        incoming.animate().cancel()
        outgoing.animate().cancel()
        incoming.text = next
        incoming.visibility = View.VISIBLE
        incoming.bringToFront()
        A11y.showSubtree(incoming)
        // 过渡中旧层对无障碍隐藏，读屏只会读到新码这一层
        A11y.decorative(outgoing)
        if (!animate) {
            incoming.alpha = 1f
            outgoing.alpha = 0f
            outgoing.visibility = View.INVISIBLE
            A11y.showSubtree(outgoing)
        } else {
            incoming.alpha = 0f
            outgoing.animate().alpha(0f).setDuration(fadeMs).start()
            incoming.animate().alpha(1f).setDuration(fadeMs)
                .withEndAction {
                    outgoing.visibility = View.INVISIBLE
                    outgoing.alpha = 0f
                    A11y.showSubtree(outgoing)
                }
                .start()
        }
        activeCode = incoming
        inactiveCode = outgoing
        shownCode = next
    }

    private fun refreshWaitingPage() {
        val code = pairing.code
        val locked = code.state() == CastCode.State.LOCKED
        // 锁定期内展示的数字输了也不认，宁可用占位符也不给一个「看着能用」的码
        renderCode(if (locked) LOCK_PLACEHOLDER else code.code())
        val seconds = code.secondsUntilRotation()
        // 描述只在换码/锁定翻转时改写与播报：每秒倒计时刷新不触发，避免读屏被打断（R8）
        val codeValue = if (locked) "" else code.code()
        val a11yKey = "$locked:$codeValue"
        if (a11yKey != lastCodeA11yKey) {
            val first = lastCodeA11yKey == null
            lastCodeA11yKey = a11yKey
            A11y.describe(activeCode, A11yCopy.code(codeValue, locked, seconds))
            if (!first) {
                if (locked) A11y.announce(codeSlot, A11yCopy.codeLocked(), "code-locked")
                else A11y.announce(codeSlot, A11yCopy.codeRotated(codeValue, seconds), "code-rotated")
            }
        }
        countdownText.text = if (locked) "投屏码已临时锁定" else "${seconds} 秒后更换投屏码"
        ring.fraction = if (locked) 0f else seconds / 60f
        val address = localIpv4() ?: "127.0.0.1"
        val port = host.port
        val browser = host.browserCastUrl()
        if (browser.isNullOrBlank()) {
            browserText.visibility = View.GONE
            browserQr.visibility = View.GONE
            lastBrowserUrl = ""
        } else {
            browserText.visibility = View.VISIBLE
            browserText.text = getString(R.string.browser_cast_url, browser)
            if (browser != lastBrowserUrl) {
                lastBrowserUrl = browser
                Qr.into(browserQr, browser, BROWSER_QR_PIXELS)
                A11y.describe(browserText, A11yCopy.browserUrl(browser))
            }
            browserQr.visibility = View.VISIBLE
        }
        val payload = if (port > 0 && !locked) pairing.qrPayload(address, port, deviceNameText.text.toString()) else ""
        if (payload.isNotEmpty() && payload != lastPayload) {
            lastPayload = payload
            Qr.into(qrView, payload, QR_PIXELS)
        }
    }

    private fun showPrompt(prompt: ReceiverSession.Prompt) {
        promptText.text = "允许「${prompt.deviceName}」投屏？"
        A11y.describe(promptDeny, A11yCopy.promptDeny(prompt.deviceName))
        A11y.describe(promptAllow, A11yCopy.promptAllow(prompt.deviceName))
        promptBar.visibility = View.VISIBLE
    }

    private fun respondToPrompt(allowed: Boolean) {
        promptBar.visibility = View.GONE
        host.resolve(allowed)
    }

    private fun onCasting(casting: Boolean, senderName: String) {
        val wasCasting = isCasting
        isCasting = casting
        // 投屏中整屏只留画面；但遥控器与 TalkBack 用户执行不了长按，必须保留一个可达入口（设计 §8.2）
        val keepSettingsEntry = TvFocus.isTvMode(this) || A11y.talkBackEnabled(this)
        settingsEntry.visibility = if (casting && !keepSettingsEntry) View.GONE else View.VISIBLE
        A11y.describe(settingsEntry, A11yCopy.settingsEntry(casting))
        longPressCatcher.visibility = if (casting) View.VISIBLE else View.GONE
        refreshQualityBadge()
        if (casting) {
            // 镜像投屏要整屏显示画面；DLNA 的图片层留在上面会把它挡住
            dlnaImageView.visibility = View.GONE
            // 本地媒体的画幅作废：镜像按自己的 CONFIG 尺寸摆放，不能被上一部推送的尺寸带偏
            dlnaWidth = 0
            dlnaHeight = 0
            // CONFIG 每次换方向都会再报一次投屏中，设备名只在真正开始投屏时叠一次
            if (!wasCasting) showCasting(senderName)
            // 第一帧的 CONFIG 早于「投屏中」，方向提示在这里补一次
            if (lastPortrait && portraitLabel) {
                showCornerHint("竖屏")
            } else if (!modeHintShown && shownWidth > 0 && DisplayFit.mismatched(root.width, root.height, shownWidth, shownHeight)) {
                modeHintShown = true
                showCornerHint("比例与屏幕不同 · 长按可切换显示模式")
            }
        } else {
            waiting.visibility = View.VISIBLE
            promptBar.visibility = View.GONE
            settingsScroller.visibility = View.GONE
            main.removeCallbacks(hideNotice)
            main.removeCallbacks(hideCornerHint)
            noticeOverlay.visibility = View.GONE
            recoveryBar.visibility = View.GONE
            cornerHint.visibility = View.GONE
            cancelEndConfirm()
        }
    }

    /** 投屏中整屏只留画面，连接成功时把「谁在投 + 通道 + 画质」叠 3 秒（PRD §11.2）。 */
    private fun showCasting(senderName: String) {
        waiting.visibility = View.GONE
        promptBar.visibility = View.GONE
        if (senderName.isEmpty()) return
        lastSenderName = senderName
        overlayName.text = castingOverlayText(senderName)
        overlayName.visibility = View.VISIBLE
        // PRD §11.5「连接成功 200ms 淡入」；C 级按 8.8 关闭动效，直接置终态
        val fadeMs = FocusPolicy.fadeDurationMs(DeviceProbe.policy(this).animationsEnabled)
        if (fadeMs == 0L) {
            overlayName.alpha = 1f
        } else {
            overlayName.alpha = 0f
            overlayName.animate().alpha(1f).setDuration(fadeMs).start()
        }
        main.removeCallbacks(hideOverlay)
        main.postDelayed(hideOverlay, OVERLAY_MS)
    }

    /**
     * PRD §11.2 要求这一行同时回答「谁在投、走哪条通道、什么画质」。
     * 文案本身归 [ProtocolBoundary]：那是全站用户可见口径的唯一出口，浮层不该另造一套。
     */
    private fun castingOverlayText(senderName: String): String =
        ProtocolBoundary.castingOverlayLine(senderName, host.qualityWidth, host.qualityHeight)

    /** 首帧读数到达时补齐浮层里的画质；浮层已淡出就不必再动。 */
    private fun refreshCastingOverlay() {
        if (overlayName.visibility != View.VISIBLE) return
        if (lastSenderName.isNotEmpty()) overlayName.text = castingOverlayText(lastSenderName)
    }

    private val hideOverlay = Runnable { overlayName.visibility = View.GONE }

    override fun surfaceCreated(holder: SurfaceHolder) {
        host.attachSurface(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // 只摘掉 Surface，会话继续收流；回到前台会重新挂载
        host.detachSurface(holder)
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        main.removeCallbacks(hideOverlay)
        main.removeCallbacks(hideNotice)
        main.removeCallbacks(hideCornerHint)
        // 界面退出不结束监听：前台服务与宿主继续等待投屏（PRD FS-13）
        host.detachUi(hostUi)
        Log.i(TAG, "activity destroyed, host keeps listening")
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AirMirrorUI"
        const val TICK_MS = 500L
        const val OVERLAY_MS = 3_000L
        const val NOTICE_MS = 3_000L
        const val END_CONFIRM_MS = 10_000L
        const val QR_PIXELS = 420
        /** 访客入口二维码按 120dp 显示，240px 足够 TV 与手机摄像头识别 */
        const val BROWSER_QR_PIXELS = 240
        const val LOCK_PLACEHOLDER = "------"
        const val MORE_INFO_LABEL = "更多信息"
        const val MORE_INFO_COLLAPSE_LABEL = "收起信息"
    }
}
