# AirMirror 专项范围边界确认（T04811）

> 依据：专项 PRD §1.3「范围边界」+ 2026-11-08 产品范围裁定 + 代码事实（HEAD `b896dfa`，2026-11-09 只读采集）。
> 对应需求：REQ-395（做）、REQ-396（不做）、REQ-397（推送视频提示文案承接）。
> 本单只做确认与取证，**不改 PRD 正文、不改业务代码**（PRD 改动沿用 T03781 口径，需产品签字）。

## 一、结论摘要

| 需求 | 结论 | 说明 |
| --- | --- | --- |
| REQ-395 做 | **确认成立** | 9 类范围项在本轮 M1–M4 均有承接任务，无遗漏、无扩围 |
| REQ-396 不做 | **确认成立** | 9 类越界项在代码中零实现；协议承诺统一由 `ProtocolBoundary` 收口 |
| REQ-397 推送视频文案承接 | **确认成立** | 只承接 `DeviceCardPolicy.pushVideoFallback()` 提示文案与控制条状态，不实现媒体选择 |

待签字项不在本单自行落笔：主 PRD 协议优先级仍按 P0/P1 表述，与 2026-11-08「按现状收口」不一致，已由 T04874 跟踪。

## 二、REQ-395「做」：9 类范围项与承接任务

| 范围项（专项 PRD §1.3） | 承接任务 |
| --- | --- |
| 界面信息层级 | T04820 待连接页布局重构、T04835 电视端主视觉区收敛、T04838 设备卡片展开层级、T04843 UI-02 主视觉区收敛、T04850 UI-05 设备卡片层次 |
| 状态与错误反馈 | T04830 排障空状态三段式、T04831 六态统一文案与状态条、T04847 UX-03 六态状态条、T04861 空列表自救引导 |
| 焦点与朗读 | T04818 无障碍属性补全设计、T04836 朗读描述与对比度增强、T04844 UI-03 无障碍可达 |
| 色彩与对比度 | T04823 离线态对比度校验、T04849 UI-04 状态语义色、T04858 对比度与字体缩放适配 |
| 字号与间距 | T04812 UI 风格规范冻结、T04817 视觉常量收敛方案、T04834 设计令牌单一来源、T04857 视觉令牌源码实现 |
| 动效时长 | T04819 投屏码过渡动效设计、T04837 码轮换过渡与时长令牌、T04854 UI-06 动效过渡 |
| 品牌标记 | T04855 UI-07 品牌关于页 |
| 访客页视觉 | T04822 访客页视觉统一、T04832 访客页视觉同源与失败下一步、T04848 UX-04 访客页同源 |
| 设计令牌与平台 View 组件 | T04817、T04834、T04841 UI-01 视觉令牌收敛、T04857 |

边界判定：以上均为「改文案 / 改样式 / 抽令牌 / 补无障碍属性」类改动，不含新增功能语义。

## 三、REQ-396「不做」：9 类越界项与代码证据

| 越界项 | 代码事实（HEAD `b896dfa`） |
| --- | --- |
| 编解码与画质协商 | 本轮无新增任务；画质相关仅保留既有首帧宽高回显（`ProtocolBoundary.castingOverlayLine`，宽高为 0 时省略） |
| 协议握手与加密 | `protocol/ProtocolBoundary.kt` 只做能力判定与文案（`supports` / `describe` / `badgeFor`），无握手、无加密实现 |
| 跨网信令与打洞 | 既有功能默认关闭，本轮不触碰；专项内无相关任务 |
| 设备发现探测策略 | 只改发现判决的文案（`DiscoveryLadder` / `DeviceRegistry` 的空态映射），不改探测与超时策略 |
| 媒体引擎与播放控制点 | 无新增；控制条仅三件事（`DeviceCardPolicy.controlActions` = 暂停 / 停止 / 声音） |
| DLNA 控制点与媒体选择器 | 发送端检索 `ACTION_PICK` / `ACTION_OPEN_DOCUMENT` / `ACTION_GET_CONTENT` / `MediaSelector` / `ControlPoint` 均为 **0 命中** |
| WebRTC 标签页共享 | 不在专项范围，无任务、无代码 |
| 更新检查与下载逻辑 | 专项 PRD §2.3 P-10 仅列为观察项、不立需求（`update/Update.kt` 保持原样） |
| 任何后端服务或云端能力 | 本轮无后端改动；访客浏览器通道为接收端内置本地服务 |

协议维度的「不做」另按产品裁定执行（2026-11-08）：AirPlay、Google Cast、华为 Cast+ **按现状收口**（未实现），Miracast 受平台限制不承诺。代码唯一出口 `ProtocolBoundary`：

- `implemented = {AMRC, DLNA, BROWSER}`（`ProtocolBoundary.kt` L35）；
- 未实现协议统一 `supported=false` 并给替代路径：AirPlay「iPhone 镜像暂未开放…」（L56）、Cast「Cast 接收暂未开放…」（L57）、Cast+「暂不支持华为 Cast+…」（L55）、Miracast「不支持 Miracast…」（L51-54）；
- 发送端角标非已实现协议一律「不支持」（L76）；接收端待连接页不写「电脑」（L61-65、`receiverMirrorLine` / `receiverPushLine` / `receiverUnsupportedLine`）。

范围裁定的其它两项：桌面端发送端移出 P1（专项 PRD §2.1「未开工：FR-14 电脑端发送、文件互传」；`receiverMirrorLine` 不含电脑）；协作与酒店场景不投入（本轮无对应任务）。

## 四、REQ-397「推送视频」承接的具体内容

**只承接两件事：提示文案、控制条状态。不实现媒体选择。**

1. 提示文案唯一出口：`DeviceCardPolicy.pushVideoFallback()`（`ui/DeviceCardPolicy.kt` L49-50）——
   「发送端暂未内置视频选择：请在视频 App 里用系统投屏按钮推送，或改用「镜像屏幕」」。
2. 展示路径：发送端设备卡片选中「推送视频」时以 toast 呈现（`ui/SenderActivity.kt` L507），不进入新页面、不弹媒体选择器。
3. 控制条状态：`controlActions(casting)` 固定「暂停 / 停止 / 声音」三项（L29-30），`pauseLabel(paused)` 提供「暂停投屏 / 继续投屏」（L32）；UX-03 只统一文案与色彩，不因状态扩容增加按钮（专项 PRD L224「依赖约束」）。
4. 测试锁定：`DeviceCardPolicyTest`「提示必须可执行 不能只报状态」断言文案含「暂未内置」且含替代路径（「投屏按钮」或「镜像屏幕」），并禁止只写「不支持」（L70-75）。
5. 明确不做：发送端媒体选择器、DLNA 控制点、`CastCode` 轮换与锁定规则改动（专项 PRD L216 / L224）。

## 五、待签字与未决（不在本单落笔）

| 事项 | 现状 | 跟踪单 |
| --- | --- | --- |
| 主 PRD 协议优先级（AirPlay/Cast P0、Cast+ P1）与「按现状收口」不一致 | 需产品签字后补实现状态注记 | T04874 |
| 专项 PRD §1.2 首屏现状列把启动路径写成直达设备列表 / 待连接页，与选择页事实不符 | 已裁定方案 2（选择页不算前置页，1 跳内到列表/待连接页），PRD 与验证方案已订正 | T04877 |
| 主 PRD 文件名 v1.1 / 正文 v1.4 抬头口径 | 需产品签字后统一 | T04876 |

## 六、复验命令

```bash
# 已实现协议集合与未实现文案
grep -n "implemented = setOf\|AIRPLAY ->\|CAST ->\|HUAWEI_CAST_PLUS ->\|MIRACAST ->" app/src/main/java/io/github/wangnan05563/airmirror/protocol/ProtocolBoundary.kt

# 推送视频文案承接
grep -n "pushVideoFallback\|controlActions\|pauseLabel" app/src/main/java/io/github/wangnan05563/airmirror/ui/DeviceCardPolicy.kt
grep -n "pushVideoFallback" app/src/main/java/io/github/wangnan05563/airmirror/ui/SenderActivity.kt

# 越界项零实现
grep -rn "ACTION_PICK\|ACTION_OPEN_DOCUMENT\|ACTION_GET_CONTENT\|MediaSelector\|ControlPoint" app/src/main/java | wc -l

# 范围条款原文
sed -n '54,58p;216,224p' AirMirror-体验与UI优化专项PRD-竞品调研-v1.0.md
```
