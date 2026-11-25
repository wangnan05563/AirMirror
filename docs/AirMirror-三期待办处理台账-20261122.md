# AirMirror 三期项目待办处理台账（2026-11-25 更新）

| 项 | 内容 |
| --- | --- |
| 项目 | AirMirror 三期（MTask `afe29f1b-d4a9-4eb5-9b4e-e1d3136df58a`） |
| 待办总量 | 35 项（M1 9 / M2 8 / M3 6 / 系统 5 / 发布 7） |
| 本轮结论 | 21 项任务完成并回传；T06117/T06119 已做**代码级实现**（WASAPI 回环 + AAC-LC、本地 JSONL 埋点）；T06118 复核 M2 已落地的 40–80ms 缓冲/A-V 主时钟并补 WASAPI 连续静音看门狗、`audio_silent` 控制帧与接收端 DRM 条件提示；T06120 在 T06119 的本地 JSONL 上补 `metrics` 每秒采样、最近一次会话 SLO 摘要、设置页默认不显示的指标弹窗与 `Redactor` 脱敏导出；T06119 正式仍未完成，T06120 不记 done；真实采音/30 分钟音画/物理电视未验；T06115 已执行本机可做部分但**仍阻塞**（Win11/物理电视/AC-05 未验）；T06123 体积门通过、PSS 仅短时基线；T06124 安全/隐私代码级通过并补上访客 Token 5 分钟失效，真机现场项仍待补；T06125 因缺 A/B/C 设备、1080p60 链路与音画测量工具**阻塞**；T06128 已完成 AC-01～09 自动化复核矩阵，AC-03/09 与现场项未过，**不能判定发布通过**；11 项仍受前置依赖阻塞 |
| 执行原则 | 只处理 `blocked_by` 为空的任务；不跳过依赖，不伪造真机/性能通过 |
| 基线 | 本轮起点 HEAD `32c5c9a`，release `v0.1.0-m0.20261122-002` |

## 01 总览

| 指标 | 数量 | 说明 |
| --- | --- | --- |
| 总待办 | 35 | 全部为 urgent/high，无低优先级稀释 |
| 本轮完成 | 21 | T06099 / T06100 / T06101 / T06102 / T06103 / T06104 / T06105 / T06106 / T06107 / T06108 / T06109 / T06110 / T06111 / T06112 / T06113 / T06114 / T06116 / T06122 / T06123 / T06124 / T06127 |
| 下一波现场阻塞 | 2 | T06115 / T06128（均需真机/现场环境；T06125 已标缺环境阻塞） |
| 依赖阻塞 | 13 | T06125 缺 A/B/C 设备与音画测量；M2 之外的 M3 音频链、发布后段 |
| 已完成占比 | 57.1% | 20/35；按依赖波次推进，不按数量硬凑 |

## 02 本轮完成项

| 任务 | 标题 | 交付物 | 验证 | 状态 |
| --- | --- | --- | --- | --- |
| T06099 | 定义主题令牌结构 | `docs/AirMirror-主题令牌结构-v2.0-20261122.md`：Raw/Semantic/Component 三层、Midnight 映射、5% 安全区、OLED 接口 | 令牌表与现有 `Theme.kt` 逐值对照；评审记录 4 角色 | 完成 |
| T06100 | 设计 TV 关键页布局 | `docs/AirMirror-TV布局设计-20261123.md`；`TvLayoutSpec.kt`、`TvFocus.kt`、`ReceiverActivity`、`MainActivity` 接入、5 张 TV 关键页截图 `docs/assets/tv-layout-20261123/` | 目标 10 项通过；5 类受影响回归 28 项 / 0 失败；全量 113 类 / 849 项 / 0 失败；1080p/720p/4:3 无裁切、5% 安全区、正文 ≥24px、二维码 368/245/262px | 完成（真实电视 overscan/D-pad 接 T06115） |
| T06101 | 绘制错误状态插画 | `docs/AirMirror-错误状态插画设计-20261123.md`；`ErrorVisualSpec.kt`、`ErrorIllustrationView.kt`、`ErrorStateCard.kt`；9 张 360dp/720p/1080p 截图 `docs/assets/error-states-20261123/` | 定向 14 项 / 0 失败：三套 Canvas 绘制、无位图源码守卫、错误码/主次兜底按钮、10 秒加载口径、360dp/720p/1080p 无截断；720p 卡内动作区裁剪已修复 | 完成（`SenderActivity` 接入与加载态转 T06104） |
| T06102 | 实现主题令牌系统 | `docs/AirMirror-主题令牌实现-20261123.md`；`ThemeTokens.kt`、`Theme.kt` 别名化、`StatusColors`/`DeviceCardTag`/`UiKit` 迁移；debug `ThemePreviewActivity`；`docs/assets/theme-tokens-20261123/` 3 张证据图 | 定向 31 项 / 0 失败：三层完整性、Raw 越权守卫、`Theme.kt` 0 hex、17 组旧色/新令牌逐像素 0 差异、360dp/1080p 全长预览；源代码守卫例外收窄到 `ThemeTokens.kt` | 完成（页面接入按 T06103/T06104；OLED 只预留） |
| T06103 | 改造 TV 布局逻辑 | `docs/AirMirror-TV布局改造-T06103-20261123.md`；`TvLayoutSpec` 二维码 160/200/240dp 分档、`TvFocus` 横向/可见控件链与焦点描边去 tint、`ReceiverActivity` 配对/结束/设置/首启焦点与返回键、`MainActivity` 入口链；3 张新证据图 `docs/assets/tv-layout-20261123/` | 定向 18 项 / 0 失败；受影响 UI 回归 45 项 / 0 失败；全量 119 类 / 885 项 / 0 失败 / 0 跳过：1080p/720p/4:3/21:9 安全区与二维码像素、长设备名不重叠、五个关键页默认焦点与返回键、设置/首启/入口 nextFocus 链、焦点环未被 MaterialButton tint 覆盖 | 完成（4K 不硬验收；真机 overscan/D-pad/TalkBack 接 T06115） |
| T06104 | 开发空态视觉组件 | `docs/AirMirror-空态视觉组件-T06104-20261123.md`；`ErrorStateCard` 接入发送端、`LoadingStateCard`/`LoadingSkeletonView`、搜索中零设备空态、10 秒取消与连接代次清理；`docs/assets/empty-states-20261123/` 5 张页面/组件截图 | 定向 30 项 / 0 失败：三套插画/错误码/三动作、360dp 动作完整可见、10 秒取消、无堆栈、Sender 接线点 11 与组件 1+1；全量 120 类 / 891 项 / 0 失败 / 0 跳过 | 完成（真机加载动画/录屏授权时序接 T06115） |
| T06105 | 实现两步投屏交互 | `docs/AirMirror-两步投屏交互-T06105-20261123.md`；`TwoStepCastPolicy`、`QrPhotoScan`、`LastCastTarget`、Sender 扫码/投屏码入口面板与自动选中；`docs/assets/two-step-cast-20261123/` 2 张 360dp 截图 | 定向 41 项 / 0 失败：唯一在线自动选中/多台必须列表/过期与手动不抢、系统相机拍照四向解码、2 次预算、默认扫码与失败切码；全量 124 类 / 911 项 / 0 失败 / 0 跳过 | 完成（真机摄像头/电视确认/端到端首帧接 T06115） |
| T06106 | 编写空态网络检测 | `docs/AirMirror-空态网络检测-T06106-20261123.md`；`EmptyNetworkCheck`、`NetworkDiagnostics`、`ErrorNotice` 4 个新原因、`ErrorVisualSpec` 映射 | 定向 30 项 / 0 失败；全量 125 类 / 922 项 / 0 失败 / 0 跳过；15 秒窗口/5 秒重试/无 SSID 诊断数据 | 完成（服务层；页面接线由 T06107 补齐） |
| T06107 | M1 阶段自测与回归 | `docs/AirMirror-M1-自测与回归-T06107-20261124.md`；发送端空态接 `EmptyNetworkCheck`、15 秒窗口、`NO_NETWORK`/`NO_RECEIVER` 页面测试；`docs/assets/m1-regression-20261124/` 12 张证据图 | 全量 126 类 / 925 项 / 0 失败 / 0 错误 / 0 跳过；空态 5 原因 × 360dp/720p/1080p、TV 7 张矩阵、17 组旧色/新令牌 0 差异 | 完成（飞行模式/3 米/TV overscan/相机/首帧真机项接 T06115/T06129） |
| T06108 | 设计 PC 安装向导 | `docs/AirMirror-PC首启安装向导设计-v2.0-20261122.md` + `docs/assets/pc-wizard-20261122/pc-wizard-wireframe.svg`：四步、16 错误态、ZIP/卸载口径 | 四画板线框稿；Swing 现状差异逐项列出 | 完成 |
| T06109 | 实现 mDNS 发现协议 | `docs/AirMirror-PC-mDNS发现协议-v2.0-20261122.md`；`MdnsDiscovery.kt`、`MdnsPacketTest.kt`、PC 发现下拉框与 `--discover` 探针 | 协议测试 5/5；PC 全量 12/12；本机首台 41ms；真实局域网 5s 0 台（无广播接收端） | 完成（AC-04 现场项待 T06128） |
| T06110 | 开发二维码配对逻辑 | `docs/AirMirror-二维码配对-v2.0-20261122.md`；`PairingQr.kt`、`PairingQrTest.kt`、PC 扫码/最近设备 UI、授权回调 | 二维码解码 4/4；PC 全量 16/16；Android `PairingLinkTest` 7/7、`QrCacheTest` 6/6；摄像头真机未测 | 完成（摄像头与端到端待 T06115） |
| T06111 | 构建 PC 安装脚本 | `docs/AirMirror-PC安装包与首启向导-v2.0-20261122.md`；`package-pc.ps1`、`install.ps1`、`uninstall.ps1`、两份防火墙脚本、`Onboarding.kt`；PC ZIP 36.9MB | PC 单测 19/19；静默安装 40.6–53.9s；卸载 36.8–80.4s；防火墙 0→2→0；快捷方式/HKCU 可清空；未签名 | 完成（首投/摄像头/Win11 待 T06115） |
| T06112 | 升级 TrustRegistry 结构 | `docs/AirMirror-TrustRegistry-v2-20261122.md`；`TrustRegistry.kt`、`Pairing.kt`、`TrustRegistryTest.kt`、`PairingMigrationTest.kt` | 30 天 TTL 6 项新测；v1→v2 迁移、未知 schema 回退与清空 3 项；全量 Android 788 项通过 | 完成（TV 信任 UI 转 T06113） |
| T06113 | 实现 TV 信任 UI | `docs/AirMirror-TV信任中心-20261122.md`；`AccessMode.kt`、`TrustUiPolicy.kt`、设置页访问控制分组、改名/撤销/详情/清空；TV 截图 `build/trust-settings-20261122.png` | 定向 69 项通过；全量 Android 808 项 / 0 失败；三档切换落盘、改名/撤销/清空与活动会话断开 | 完成（PIN 与三档行为接 T06114，真机接 T06115） |
| T06114 | 开发 PIN 锁定机制 | `docs/AirMirror-PIN锁定机制-20261123.md`；`PinCode.kt`、`PeerMask.kt`、三模式 `AuthGate` 路由、PIN 等待页/倒计时/遥控器键盘、发送端 4/6 位输入；TV 截图 `build/pin-locked-20261123.png` | 定向 56 项通过；全量 Android 839 项 / 0 失败；5 次锁 60 秒、锁定跨重启、IP 只存掩码、PIN/仅信任拒绝 6 位码与令牌 | 完成（真机断电/遥控器/DLNA 边界接 T06115） |
| T06116 | 定义性能 SLO 指标 | `docs/AirMirror-性能SLO-v2.0-20261122.md`：6 核心 + 2 诊断指标、5GHz/2.4GHz 分档、12 条用例、30 分钟 soak | 目标与 REQ-009/AC-09 对齐；当前基线明确标“待测” | 完成 |
| T06122 | 修复跨端联调缺陷 | `docs/AirMirror-跨端联调缺陷修复-20261122.md`：AP 隔离/离线接收端/码错误/PC 握手映射与修复包 | Android 30 项单测通过；PC 7 项通过；物理 AP 隔离待 T06129 | 完成（代码级，现场项待回归） |
| T06127 | 构建发布版 APK/ZIP | release APK `5,338,905 B`；bundle ZIP `4,407,986 B`；SHA-256 清单 | `aapt`/`apksigner` 校验；模拟器安装启动；GitHub Release 资产 digest 一致 | 完成 |
| T06123 | 优化 APK 体积与内存 | `docs/AirMirror-APK体积与内存-T06123-20261124.md`；release R8 配置 `isMinifyEnabled=true` / `isShrinkResources=false`，`app/proguard-rules.pro` 仅保留行号信息；优化 APK `2,395,358 B`、SHA-256 `1fb517...` | 相对未优化 `5,404,397 B` 减少 `3,009,039 B（-55.7%）`；相对 T06127 基线减少 `2,943,547 B`，≤2MB 增量门通过；API34 AVD Main/Receiver 可渲染；PSS 6 点短时 idle 基线均约 40MB，未宣称内存收益 | 完成（PC→优化包握手/首帧、30 分钟 soak、API22/真机未验） |
| T06124 | 验证安全与隐私特性 | `docs/AirMirror-安全与隐私验证-T06124-20261124.md`；`BrowserCastServer` 增加访客 token 5 分钟 TTL、到期自动换新、旧地址/上传路径立即 404；新增 2 项到期测试；证据 `docs/assets/t06124-20261124/security-test-summary.txt` | 定向 9 类 / 96 项 / 0 失败；全量 126 类 / 927 项 / 0 失败 / 0 错误 / 0 跳过；PIN 跨实例锁定、30 天未用、撤销释放活动会话、日志脱敏均有单测；真实浏览器 5 分钟、真实断电、撤销后 1 秒断流未验 | 完成（代码级；AC-05 真机现场项转 T06115/T06128） |

## 03 下一波（依赖已解除，现场阻塞）

| 任务 | 标题 | 前置 | 本轮动作 | 产出 |
| --- | --- | --- | --- | --- |
| T06115 | 【缺环境】M2 联调与 Win 测试 | T06114 ✅、T06110 ✅ | 已做 Win10 安装/卸载/防火墙闭环与 PC→Android 14 握手/首帧；剩余 Win11、同网 mDNS、物理电视、AC-05 现场项 | M2 部分报告（`docs/AirMirror-M2-联调与Win测试-T06115-20261124.md`）+ 阻塞清单；保持 todo、不归档 |
| T06128 | 执行全量 AC 验收 | T06127 ✅ | 已认领并完成 AC-01~AC-09 自动化复核矩阵；AC-03/09 未过，AC-04/05/06/07 现场项未测 | `docs/AirMirror-全量AC验收-T06128-20261124.md` + `docs/assets/t06128-20261124/ac-matrix.csv`；保持 todo、不归档 |

## 04 全部 35 项台账

状态口径：`完成` = 本轮有交付物与证据；`就绪` = 前置为空或已解除，可下一轮执行；`阻塞` = 仍有未完成前置。

### M1 视觉与投屏体验（9）

| 任务 | 标题 | 前置 | 本轮状态 | 下一步 |
| --- | --- | --- | --- | --- |
| T06099 | 定义主题令牌结构 | — | 完成 | 进入 T06100/T06102 |
| T06100 | 设计 TV 关键页布局 | T06099 | 完成 | 进入 T06101/T06102 |
| T06101 | 绘制错误状态插画 | T06100 | 完成 | 插画组件已交付；页面接入转 T06104 |
| T06102 | 实现主题令牌系统 | T06101 ✅,T06099 ✅ | 完成 | 令牌代码与 debug 预览已交付；页面接入转 T06103/T06104 |
| T06103 | 改造 TV 布局逻辑 | T06102 ✅,T06100 ✅ | 完成 | TV 二维码分档/21:9/焦点链已交付；进入 T06104 |
| T06104 | 开发空态视觉组件 | T06101 ✅,T06102 ✅,T06103 ✅ | 完成 | 空态组件已接入发送端；进入 T06105 两步交互 |
| T06105 | 实现两步投屏交互 | T06104 ✅,T06102 ✅ | 完成 | 两步入口与状态机已交付；进入 T06106 |
| T06106 | 编写空态网络检测 | T06104 ✅,T06105 ✅ | 完成 | 四态判定/诊断数据接口已交付；页面接线已由 T06107 补齐 |
| T06107 | M1 阶段自测与回归 | T06106 ✅,T06103 ✅,T06105 ✅ | 完成 | M1 本地回归与空态接线已闭环；真机项转 T06115/T06129 |

### M2 PC 与信任（8）

| 任务 | 标题 | 前置 | 本轮状态 | 下一步 |
| --- | --- | --- | --- | --- |
| T06108 | 设计 PC 安装向导 | — | 完成 | 进入 T06109/T06111 |
| T06109 | 实现 mDNS 发现协议 | T06108 | 完成 | 进入 T06110 二维码配对 |
| T06110 | 开发二维码配对逻辑 | T06109 | 完成 | 进入 T06111 安装脚本 |
| T06111 | 构建 PC 安装脚本 | T06110,T06108 | 完成 | 进入 T06112 TrustRegistry |
| T06112 | 升级 TrustRegistry 结构 | T06111 ✅、T06110 ✅ | 完成 | 进入 T06113 信任 UI |
| T06113 | 实现 TV 信任 UI | T06112 ✅ | 完成 | 进入 T06114 PIN 锁定 |
| T06114 | 开发 PIN 锁定机制 | T06113 ✅ | 完成 | PIN/仅信任模式已接入，进入 T06115 联调 |
| T06115 | 【缺环境】M2 联调与 Win 测试 | T06114 ✅、T06110 ✅ | 阻塞（缺环境） | Win10 安装/卸载/防火墙与 PC→Android 14 握手/首帧已闭环；需 Win11 + 物理电视补同网发现、首投、AC-05 |

### M3 音频与性能（6）

| 任务 | 标题 | 前置 | 本轮状态 | 下一步 |
| --- | --- | --- | --- | --- |
| T06116 | 定义性能 SLO 指标 | — | 完成 | 进入 T06117/T06119 |
| T06117 | 实现 WASAPI 音频采集 | T06116 ✅,T06115（用户授权跳过现场） | 代码级完成（现场未验） | WASAPI 回环/AAC-LC/静音音量已落码；PC 31 项、全量 935 项通过；真实采音、音画与物理电视转 T06115/T06121；`docs/AirMirror-WASAPI音频与本地埋点-T06117-T06119-20261124.md` |
| T06118 | 开发音频流同步逻辑 | T06117 | 代码级完成（现场未验） | 复核 M2 `1e86e9f` 的 40–80ms 缓冲/主时钟/硬对齐/降档；补 WASAPI 连续静音看门狗、`audio_silent` 控制帧与接收端 `AUDIO_PROTECTED` 条件提示；PC 36 项、Android 937 项全过；30 分钟偏差、真实 DRM/受保护内容与物理电视转 T06115/T06121；`docs/AirMirror-音频同步与受保护内容提示-T06118-20261125.md` |
| T06119 | 构建本地性能埋点 | T06116 ✅,T06118 | 代码级完成（T06118 仍阻塞） | 本地 JSONL 中间件与接收端事件已落码；T06118 未完成，不把任务记通过；T06120 已按代码级推进，正式依赖待解除 |
| T06120 | 开发诊断指标看板 | T06119 | 代码级完成（T06119 仍 todo） | `PerfTrace.Event.METRICS` 每秒采样 P90/P95/偏差/丢帧/音频帧；`PerfSummaryReader` 取最近会话、缺样本显示「未采集」；设置页默认不显示，点开为指标弹窗；导出摘要前过 `Redactor`；Android 130 类 / 945 项全过，`assembleDebug` 通过；真实 TV 点按与分享面板待 T06121；`docs/AirMirror-诊断指标看板-T06120-20261125.md` |
| T06121 | M3 音频与性能测试 | T06120,T06118 | 工具就绪（现场阻塞） | 新增 `tools/soak/soak-android.ps1` 与 `summarize_soak.py`：5 分钟 PSS/CPU/温度采样、logcat、`run-as` 拉取 perf JSONL；5 项 Python 单测与 1 分钟模拟器冒烟通过；仍无 A/B/C 设备、1080p60 链路与外部音画测量，AC-03/AC-09 未验；`docs/AirMirror-M3-Soak测试工具-T06121-20261125.md` |

### 系统稳定性与发布（5）

| 任务 | 标题 | 前置 | 本轮状态 | 下一步 |
| --- | --- | --- | --- | --- |
| T06122 | 修复跨端联调缺陷 | — | 完成 | 进入 T06123/T06124 |
| T06123 | 优化 APK 体积与内存 | T06122 | 完成 | 体积门通过；PSS 短时基线；PC 握手与 30 分钟 soak 转 T06125 |
| T06124 | 验证安全与隐私特性 | T06122,T06123 | 完成 | 代码级 96/96 定向、927/927 全量；真实浏览器 5 分钟/断电/1 秒断流转 T06115/T06128 |
| T06125 | 【缺环境】[PRD] 执行 30 分钟 Soak 测试 | T06124 ✅,T06123 ✅ | 阻塞（缺环境） | 无 A/B/C 级设备、1080p60 同网链路与音画/PSS 测量；仅完成构建、安装与主界面冒烟；报告 `docs/AirMirror-Soak环境核查-T06125-20261124.md` |
| T06126 | 更新发布说明文档 | T06124 ✅,T06125 | 阻塞 | 待 Soak |

### 发布收口（7）

| 任务 | 标题 | 前置 | 本轮状态 | 下一步 |
| --- | --- | --- | --- | --- |
| T06127 | 构建发布版 APK/ZIP | — | 完成 | 进入 T06128 |
| T06128 | 执行全量 AC 验收 | T06127 ✅ | 阻塞（缺环境） | 已交付 AC-01~09 自动化矩阵；AC-03/09 未过，Win11/物理电视/TalkBack 现场项未验，不能判定发布通过；`docs/AirMirror-全量AC验收-T06128-20261124.md` |
| T06129 | 验证发布回归场景 | T06128 | 阻塞 | 待 AC |
| T06130 | 执行发布检查清单 | T06129 | 阻塞 | 待回归 |
| T06131 | 归档与版本发布 | T06130 | 阻塞 | 待清单 |
| T06132 | 收集发布反馈 | T06131 | 阻塞 | 待发布 |
| T06133 | 需求追溯与覆盖核对 | T06132 | 阻塞 | 待反馈 |

## 05 依赖链

```text
M1: T06099 -> T06100 -> T06101 ✅ -> T06102 ✅ -> T06103 ✅ -> T06104 ✅ -> T06105 ✅ -> T06106 ✅ -> T06107 ✅
                     \-> T06102 / T06103 / T06104 交叉前置

M2: T06108 -> T06109 -> T06110 -> T06111 -> T06112 -> T06113 -> T06114 -> T06115

M3: T06116 -> T06117 -> T06118 -> T06119 -> T06120 -> T06121
      \-> T06119 也依赖 T06118；T06117 依赖 T06115

系统: T06122 -> T06123 -> T06124 -> T06125 -> T06126
发布: T06127 -> T06128 -> T06129 -> T06130 -> T06131 -> T06132 -> T06133
```

## 06 本轮验证汇总

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| Android 跨端定向单测 | 30 通过 / 0 失败 | `EmptyDiagnosisTest` 12、`ErrorNoticeTest` 13、`DiscoveryLadderTest` 5 |
| PC 客户端测试 | 19 通过 / 0 失败 | `PcConnectionTest` 等 8 个类；新增 `OnboardingStateTest` 3 项 |
| PC 安装包 | 36,863,225 B；静默安装 40.6–53.9s；卸载 36.8–80.4s | ZIP 布局 11 项、包内 JRE self-check、快捷方式/HKCU 安装后清空 |
| T06115 Win10 ZIP 闭环 | 安装 3.7–8.4s / 卸载 8.8–18.7s；防火墙 0→2→2→0 | 完整安装 + 快捷方式 + HKCU 卸载后均为 0 残留；包内运行时自检通过；证据 `docs/assets/m2-win-20261124/pc-install-uninstall-firewall.log` |
| T06115 协议链路 | 21 帧 / 15s，JCodec 1.8–2.4fps | PC→Android 14 握手、加密、授权、首帧；错误投屏码正确拒绝；非同网 mDNS，不作 AC-04 发现证据 |
| PC 防火墙 | 0→2→2→0 | 创建、幂等、删除三次 `-Check`；规则为 Private 入站 TCP 41523/UDP 41524-41525 |
| PC 首启向导 | 状态机 3/3；UI 未截图 | `OnboardingStateTest`；首次首帧完成逻辑已接入，端到端待 T06115 |
| Android 二维码口径 | 13 通过 / 0 失败 | `PairingLinkTest` 7、`QrCacheTest` 6 |
| T06113 信任 UI 定向 | 69 通过 / 0 失败 | `TrustSettingsUiTest` 4、`TrustUiPolicyTest` 7、`AccessModeTest` 2、`TrustRegistryTest` 26、`PairingMigrationTest` 5、`A11yCopyTest` |
| T06113 TV 截图 | 1920×1080 已生成 | `build/trust-settings-20261122.png`，三档模式 + 2 台设备 + 清空入口 |
| T06114 PIN 锁定定向 | 56 通过 / 0 失败 | `PinCodeTest` 8、`PeerMaskTest` 6、`AuthGateAccessModeTest` 8、`ErrorNoticePinTest` 3、`PinAccessUiTest` 5、`AccessibilityManifestTest` 12、`BrowserCastServerTest` 14 |
| T06114 PIN 截图 | 1920×1080 已生成 | `build/pin-locked-20261123.png`，PIN + 60 秒锁定倒计时 + `192.168.1.*` 来源掩码 |
| T06100 TV 布局定向 | 10 通过 / 0 失败 | `TvLayoutSpecTest` 5、`TvLayoutUiTest` 5；5 张截图覆盖 1080p/720p/4:3 |
| T06100 受影响回归 | 28 通过 / 0 失败 | `TvLayoutSpecTest`、`TvLayoutUiTest`、`PinAccessUiTest`、`MainVisualGuardTest`、`CodeFadeGuardTest` |
| T06101 错误插画定向 | 14 通过 / 0 失败 | `ErrorVisualSpecTest` 6、`ErrorIllustrationViewTest` 5、`ErrorStateCardUiTest` 3；9 张 PNG 覆盖 360dp/720p/1080p |
| T06101 插画边界 | 3 场景 × 3 分辨率无截断 | 5% 安全区、卡顶/卡底/动作区三重断言；720p 初版按钮圆角裁切已修复 |
| T06101 无位图守卫 | 0 个位图调用 | `ErrorIllustrationView.kt` / `ErrorStateCard.kt` 源码扫描 `Bitmap`/`R.drawable`/`BitmapFactory` |
| T06102 主题令牌定向 | 31 通过 / 0 失败 | `ThemeTokensTest` 9、`ThemeTokenGuardTest` 9、`ThemeTokenTest` 7、`ThemeRenderEvidenceTest` 3、`ErrorStateCardUiTest` 3 |
| T06102 像素等价 | 17 组旧色/新令牌 0 差异 | `theme-legacy-vs-tokens-20261123.png` 上半旧值、下半令牌，逐像素断言 |
| T06102 预览页 | 360dp / 1080p 宽全长 | `theme-preview-360dp-full` 720×3105、`theme-preview-1080p-full` 1920×2250，三层令牌与 5% 安全区完整可见 |
| T06103 TV 布局改造定向 | 18 通过 / 0 失败 | `TvLayoutSpecTest` 6、`TvLayoutUiTest` 7、`TvFocusUiTest` 5；覆盖 1080p/720p/4:3/21:9、20 字符长名、配对/结束/设置/首启/入口焦点与返回键 |
| T06103 受影响回归 | 45 通过 / 0 失败 | `FocusCoverage`、`FocusPolicy`、`MainVisualGuard`、`CodeFadeGuard`、`PinAccessUi`、`TrustSettingsUi`、`ErrorStateCardUi`、`AccessibilityManifest` 8 类 |
| T06103 全量回归 | 119 类 / 885 项 / 0 失败 / 0 跳过 | 较 T06102 基线新增 `TvFocusUiTest` 1 类 5 项 + 既有两测试类 3 项；已知 Windows 临时文件抖动未复现 |
| T06103 TV 证据图 | 3 张新增 | `tv-layout-1080p-pairing`、`tv-layout-21x9-waiting`、`tv-layout-1080p-long-name`；与 T06100 的 5 张同目录 |
| T06104 空态定向 | 30 通过 / 0 失败 | `SenderEmptyStateUiTest` 6、`ErrorStateCardUiTest` 3、`CompactPagesRenderTest` 5、`EmptyDiagnosisGuardTest` 4、`AccessibilityManifestTest` 12 |
| T06104 空态证据 | 5 张新增 | `docs/assets/empty-states-20261123/`：360dp 空态/加载取消页面 2 张、组件加载 360dp 早期/10 秒 2 张、1080p 组件 1 张 |
| T06104 全量回归 | 120 类 / 891 项 / 0 失败 / 0 错误 / 0 跳过 | 较 T06103 新增 `SenderEmptyStateUiTest` 1 类 6 项；首轮两处守卫误命中已修（骨架去 `ValueAnimator`、动作守卫改查 `ErrorStateCard`），最终 9m39s 全绿 |
| T06105 两步投屏定向 | 41 通过 / 0 失败 | `TwoStepCastPolicyTest` 9、`LastCastTargetTest` 3、`QrPhotoScanTest` 4、`SenderTwoStepUiTest` 4、`AccessibilityManifestTest` 12、`EmptyDiagnosisGuardTest` 4、`CompactPagesRenderTest` 5；覆盖唯一在线自动选中、多台在线必须列表、30 天过期、2 次预算、拍照四向解码、默认扫码/失败切码 |
| T06105 两步投屏证据 | 2 张新增 | `docs/assets/two-step-cast-20261123/`：360dp 默认扫码、扫码失败切投屏码 |
| T06105 全量回归 | 124 类 / 911 项 / 0 失败 / 0 错误 / 0 跳过 | 较 T06104 新增 4 个测试类 20 项；既有守卫 `AccessibilityManifest` 点击接线点 11→16 已同步 |
| T06106 空态网络检测定向 | 30 通过 / 0 失败 | `EmptyNetworkCheckTest` 11、`ErrorVisualSpecTest` 6、`ErrorNoticeTest` 13；覆盖未联网、15 秒前/整点、无接收端、全离线、AP 隔离、双频、平台策略、配对失败、在线 READY、5 秒重试与诊断数据 |
| T06106 全量回归 | 125 类 / 922 项 / 0 失败 / 0 错误 / 0 跳过 | 较 T06105 的 124 类/911 项新增 `EmptyNetworkCheckTest` 1 类 11 项；新原因映射已并入 `ErrorVisualSpec` |
| T06107 空态接线定向 | 10 通过 / 0 失败 | `SenderEmptyNetworkUiTest` 3（飞行模式 / 15 秒无接收端 / 接线守卫）、`ErrorStateCardUiTest` 3、`EmptyDiagnosisGuardTest` 4；页面截图 2 张 |
| T06107 空态视觉矩阵 | 5 原因 × 3 尺寸 / 0 失败 | 新增 `error-no-network-*`、`error-no-receiver-*` 各 360dp/720p/1080p，共 6 张；旧 9 张同版重生成 |
| T06107 TV 矩阵 | 7 张 / 0 失败 | `docs/assets/m1-regression-20261124/tv-matrix/`：720p/1080p/4:3/21:9/长名/设置/入口 |
| T06107 主题回归 | 17 组 0 差异 / 9 项守卫 | `ThemeRenderEvidenceTest` 3、`ThemeTokenGuardTest` 9；360dp/1080p 全长预览 + 对比图归档 |
| T06107 全量回归 | 126 类 / 925 项 / 0 失败 / 0 错误 / 0 跳过 | 较 T06106 125 类/922 项新增 `SenderEmptyNetworkUiTest` 1 类 3 项；既有矩阵扩 2 原因不增方法数 |
| Android 全量单测 | 873 通过 / 4 环境失败 | 118 个测试类 / 877 项、0 skip；T06102 2 个新测试类 + guard 2 项全过；4 项失败为 `ApkDownloadTest` 1（`.part` 改名瞬时占用）与 `BrowserCastServerTest` 3（临时 mp4 删除/残留），与 T06101 基线同源，未触达 T06102 代码路径；`release/build/app/test-results/testDebugUnitTest` |
| PC 摄像头扫码 | 未实测 | 执行环境无摄像头；已留截图导入降级，真机项转 T06115 |
| PC mDNS 真实局域网 | 5s 探针 0 台 | 当前无接收端广播；AC-04 现场项转 T06128 |
| release 单测 | 779 通过 / 0 失败 | `release/build/app/test-results/testReleaseUnitTest` |
| release APK | 5,338,905 B；v1/v2 签名通过 | `aapt dump badging`、`apksigner verify` |
| T06123 优化后 APK | 2,395,358 B；SHA-256 `1fb517…` | 相对未优化 5,404,397 B 减少 3,009,039 B（-55.7%）；`aapt`/`apksigner` 与未优化包同 versionCode/versionName/minSdk/targetSdk |
| T06123 UI 渲染 | Main/Receiver 均可渲染 | 优化包在 API34 AVD 安装；`C:\Temp\airmirror_optimized_receiver4.png`、`airmirror_optimized_receiver5.png`；Receiver 码 942261/449064 |
| T06123 PSS 基线 | 6 点 / 约 2–3 分钟；均约 40 MB | 未优化均值 40,542 KB、优化后均值 40,683 KB；优化后 Native Heap 8.7–9.5 MB、Java Heap 4.4–5.2 MB；总 PSS 无下降，不能作为 AC-09 结论 |
| T06123 构建约束 | release 构建成功但耗时 17m23s | `isShrinkResources=true` 在 AAPT2 失败，回退 false；`-Xmx4096m` 触发 Windows 1455，默认 2048m 且停模拟器后成功 |
| T06124 安全定向 | 9 类 / 96 项 / 0 失败 | `BrowserCastServerTest` 16、`PinCodeTest` 8、`TrustRegistryTest` 26、`AuthGateAccessModeTest` 8、`CastCodeTest` 20、隐私 18；新增 token 5 分钟到期 2 项 |
| T06124 全量回归 | 126 类 / 927 项 / 0 失败 / 0 错误 / 0 跳过 | 较 T06107 基线 925 项新增 2 项；`BUILD SUCCESSFUL in 3m 13s`，Robolectric 临时目录清理告警不影响用例 |
| T06120 全量回归 | 130 类 / 945 项 / 0 失败 / 0 错误 / 0 跳过 | 较 T06118 的 128 类 / 937 项新增 `PerfSummaryTest` 6 项、`MetricsBoardUiTest` 2 项；`BUILD SUCCESSFUL in 2m 44s`，Robolectric 临时目录清理告警不影响用例 |
| T06120 构建 | `:app:assembleDebug` 31s | 产出 universal debug APK；`docs/assets/t06120-20261125/app-assemble-debug.log` |
| T06121 Soak 工具 | 5 个 Python 单测全过；1 分钟模拟器冒烟 3/3 个 PSS 样本 | `tools/soak/{soak-android.ps1,summarize_soak.py}`；`docs/assets/t06121-20261125/{python-unittest.txt,smoke2/}` |
| T06124 访客 Token | 5 分钟 TTL / 到期自动换新 | 到期前 1ms 200、到期旧地址 404；旧上传路径 404 且不落文件；真实浏览器等待 5 分钟未验 |
| 模拟器安装 | emulator-5556 安装并启动 | `build/release-smoke-20261122.png` |
| bundle ZIP | 4,407,986 B；4 个条目 | `release/release/AirMirror-0.1.0-m0.20261122-002-release-bundle.zip` |
| 物理 AP 隔离 | 未实测 | 列入 T06129，不伪造通过 |

## 07 风险与决策

1. **M2 已推进到联调**：T06114 已把 PIN 与仅信任判定接入 `AuthGate`，T06115 成为下一项；首次投屏/摄像头/Win11 现场项必须补测。
2. **三档模式已接入，仍需真机验收**：PIN 档拒绝 6 位码/令牌、仅信任档只放行白名单、锁定跨重启已有 JVM 证据；真实电视上的遥控器键盘、断电重启与端到端拒绝文案转 T06115。
3. **M1 实现链长**：T06102~T06107 共 6 项依赖串联；主题/布局/空态/两步交互任何一环返工都会拖到 M1 回归。
4. **性能指标尚无可信真机数据**：SLO 文档已定义，T06121 前不能对外宣称“首帧 3 秒/延迟 200ms 达标”。
5. **release 包与 debug 包不可直接比体积**：001 debug 6.60MB、002 release 5.34MB；体积结论必须同构建类型、同签名口径。
6. **物理 AP 隔离**：T06122 只有判决单测，T06129 必须在真实路由器开关 AP 隔离复验。
7. **mDNS 现场证据缺口**：T06109 只有协议与本机链路证据，真实 Wi‑Fi 首台 ≤3s 需在 T06128 用 Android 接收端实测；无组播时只能走手动 IP，不做 UDP 广播降级。
8. **PC 摄像头依赖 BridJ 原生库**：webcam-capture 在部分 Windows 驱动/安全软件下可能打不开；T06110 已提供截图导入降级，但“摄像头真机扫码”必须留在 T06115 补测，不能按协议测试冒充。
9. **PC 安装包现场缺口已收窄**：Win10 ZIP 安装 3.7–8.4s、卸载 8.8–18.7s、防火墙 0→2→0 已实测；同网发现 ≤3s、Win11、首投 ≤2min 与 FFmpeg 仍未验，需 T06115 现场补测。
10. **TV 信任/PIN/关键页 UI 未上真机**：D-pad 逐行、TalkBack 顺序、遥控器软键盘改名、PIN 九宫格连续输入、断电后锁定恢复与撤销正在投屏的 1 秒断开，均由 T06115 补测；T06100 只证明像素布局不裁切，真实电视 HDMI overscan 与 4:3 物理面板观感仍需现场确认。
11. **T06105 自动选中是发送端侧信任近似**：接收端 `TrustRegistry` 不可被发送端读取，自动选中只能以“本机上次首帧成功设备”为准；电视端首次确认仍可能弹出，按任务范围不计入 2 次决策。4.5s 完整发现窗、系统相机各 ROM 兼容、扫码到首帧 ≤15s、自动选中到首帧 ≤10s 都需 T06115 真机复验。
12. **T06107 已接页面，现场仍待真机**：`SenderActivity` 已消费 `EmptyNetworkCheck`，飞行模式与 15 秒无接收端有 Robolectric 页面截图；真实飞行模式/AP 隔离、电视 3 米可读、相机 ROM、扫码/自动选中到首帧时延仍未实测，不能按“页面单测通过”冒充端到端验收，统一转 T06115/T06129。
13. **T06123 只证明体积门与短时 PSS**：R8 后 APK 2.39MB，相对 T06127 基线减少 2.94MB；但总 PSS 与未优化包基本持平，native heap 下降被其他占用抵消。PC→优化包握手/首帧、30 分钟 soak、API22 与真实电视均未验，不能把“包更小”写成“性能达标”。
14. **T06124 补上 Token 生命周期，但仍是 JVM 证据**：访客 token 现有 5 分钟 TTL，到期旧地址/上传路径 404，待连接页 500ms ticker 会重画二维码；测试用注入时钟推进，未在真实浏览器等 5 分钟。PIN 跨重启是跨实例恢复，撤销“1 秒内断开”只证明活动会话释放与 UI 同步调用 `endActiveSession()`，均需真机补测。
15. **T06125 仍因设备缺失阻塞，采样工具已补**：当前只有 1 台 A 级手机（SM-G9980 / Android 15 / 1440×3200 / 10.5GB，当前 adb 未在线）与 API 29/34 模拟器，无 B/C 级物理盒子/电视、无同网 1080p60 链路、无外部音画偏差测量。2026-11-25 已新增 `tools/soak`（5 分钟 PSS/CPU/温度、logcat、perf JSONL 汇总），但只在 720×1280 / API 34 / 3GB 模拟器上做过 1 分钟冒烟，不能替代 A/B/C 30 分钟 soak，不能声明 AC-03/AC-09 通过。

16. **T06128 自动化覆盖 5/9，现场 0/9**：AC-08 代码级完全通过；AC-01/05/06/07 代码级通过但真实飞行模式、断电、3 米可读、TalkBack 未测；AC-02/04 仅部分通过；AC-03/09 未通过。全量单测 927 项复用同日结果，不代表 30 分钟链路或音画偏差通过。

17. **T06117/T06119 只有代码级证据**：WASAPI COM 回环、FFmpeg AAC-LC、静音/音量与 A/V PTS 已编译并过单测，但执行环境无 ffmpeg、未在真实音频端点采音；T06119 的 JSONL 只证明本地落盘/轮转/脱敏，正式 blocker T06118 仍在，不能把 T06119 记完成。全量单测增至 128 类 / 935 项，仅代表 JVM 逻辑与 Robolectric 页面，不代表真机音频链路。

18. **T06118 的 DRM 提示是条件句，不是判定**：M2 `1e86e9f` 已实现 40–80ms 缓冲、AudioTrack 主时钟、硬对齐与连续三次降档；本轮只补 WASAPI 静音包看门狗、`audio_silent` 控制帧与接收端 `AUDIO_PROTECTED` 文案。Windows 不提供受保护内容的直接查询，10 秒静音也可能是暂停/独占/音量静音，不能把提示写成“已检测到 DRM”。PC 36 项、Android 937 项通过，仍无真实 30 分钟偏差与 DRM 样本。

19. **T06120 的看板是文本摘要，不是真机 SLO 判定**：`metrics` 每秒落一行依赖 `logProgress` 每 30 帧触发，低帧率时采样间隔会大于 1 秒；只读最近 4 个文件、最多 1500 行，长会话的起始行可能滚出窗口（已让 reader 在缺少 `SESSION_START` 时仍取最后会话并显示“时长未采集”）。P95 ≤200ms、首帧 ≤3s、丢帧 ≤1% 是代码常量，不是现场结论；真实 TV 点按、`ACTION_SEND` 分享面板、30 分钟 soak 与 AC-09 仍待 T06121。旧 JSONL 没有 `metrics` 时显示“未采集”，不回填、不把 0 当达标。

## 08 下一轮建议

按依赖顺序执行：**T06125 待设备到位后重跑 → T06128 → T06129**。T06124 已完成代码级安全验证；T06125 已因 A/B/C 设备与音画测量缺失标为【缺环境】；T06128 已完成 9 项 AC 的自动化复核矩阵，但 AC-03/09 与 Win11/物理电视/TalkBack 现场项未过，保持 `todo`、不归档。T06126 继续被 T06125 阻塞。T06115 仍需 Win11/物理电视现场环境，不能伪造通过。T06118 代码级已补，待 T06117 现场与 T06121 30 分钟采样后再收口；T06120 代码级已补，待 T06119 正式解除与 T06121 真机/30 分钟采样后再收口。T06121 的 `tools/soak` 已就绪，设备到位后直接跑三档 30 分钟；当前仍只算工具冒烟，不把 AC-09 记通过。
