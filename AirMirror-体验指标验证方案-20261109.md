# AirMirror 体验指标验证方案（T04810）

> 依据：《AirMirror 体验与UI优化专项PRD》§1.2「预期达成的体验指标（可观测，不编造数值）」。
> 用途：把该表 5 个指标的「验证方式」一列，落成可执行的断言与用例，供后续实现单（T04830–T04839）与测试单（T04869–T04871）直接引用。
> 基线采集：2026-11-09，HEAD `b896dfa`，只读代码与源码计数，未改业务代码。
> 门禁分级：**G1** 合并门禁（JVM 必过）｜**G2** 发布门禁（UI dump / 真机）｜**G3** 测量记录（不阻断）。

## 一、指标与用例总览

| 指标（PRD §1.2） | 用例 | 门禁 | 当前基线 | 判定 |
| --- | --- | --- | --- | --- |
| 首屏零等待 | TC-01 / TC-02 / TC-11 | G1 + G3 | LAUNCHER 为 `MainActivity` 选择页，1 跳内到列表/待连接页 | **口径已定**（方案 2，T04877），见 §五 |
| 空列表可诊断 | TC-05 | G1 | 5 个空态都有文案；`DUAL_BAND` 缺「下一步」 | **4/5 达标** |
| 状态可见（6 态） | TC-03 / TC-04 | G1 | 6 态 × 2 端 = 12 组合，文案覆盖 **10/12**，无单一来源 | **未达标** |
| 3 米可读 | TC-09 / TC-10 | G1 + G2 | TV 字号断言已有（40/24sp）；主视觉区元素数未测 | **部分达标** |
| 遥控器可达 | TC-07 / TC-08 | G2 | 34 处点击入口，全项目 `contentDescription` **0 处** | **未达标** |
| （可执行错误提示，复用） | TC-06 | G1 | `ErrorNoticeTest` 12 个用例覆盖 23 个 Cause | **已达标** |

## 二、用例明细

### TC-01 首屏跳数（G1 + G3）

- **断言**：从选择页到「设备列表 / 待连接页」**1 次跳转**；本次视觉改造不得新增跳数。T04877 裁定方案 2：选择页不算前置页。
- **通道**：源码守卫（`AndroidManifest.xml` 只有一个 `category.LAUNCHER`）+ 真机 `adb shell am start -W` 记录 `TotalTime`（G3，不设数值阈值）。
- **基线**：`MainActivity`（101 行，2 个 Button：「投到其他设备」「接收别人投来的画面」）→ `SenderActivity` / `ReceiverActivity`，各 1 跳；到目标页前无开屏页、引导页、账号流程。
- **处置**：口径已定（方案 2）：不改现有交互，只订正文档；若要把选择页合并进列表 / 待连接页，另开实现单。

### TC-02 不得新增前置页（G1）

- **断言**：Manifest 中仅 `MainActivity` 带 LAUNCHER；不存在 Splash / Guide / Login 类 Activity；启动路径上无权限弹窗阻塞首帧。
- **通道**：JVM 源码守卫（沿用 `FocusCoverageTest` 的 `projectRoot()` 定位方式，读 Manifest 与 ui 源码）。
- **基线**：PASS。单一 LAUNCHER；隐私说明是页内文字 + 按需 `AlertDialog`（`MainActivity` 尾部），不阻塞首帧；无账号流程。

### TC-03 六态文案覆盖（G1）

- **断言**：状态表 `StateCopy` 覆盖 6 个键 × 2 端 = 12 条文案，均非空；同一状态同端文案唯一；「已暂停」在发送端与接收端语义一致（允许带设备名前缀）。
- **状态键**：`UNCONNECTED / CONNECTING / CASTING / PAUSED / RECOVERING / ERROR`。
- **基线（10/12）**：文案散落在 6 个文件——

| 状态 | 发送端 | 接收端 |
| --- | --- | --- |
| 未连接 | `"正在搜索同一网络里的电视…"`（SenderActivity L1092） | `"等待连接 · {设备名}"`（ReceiverSession L229） |
| 连接中 | `"正在连接…"`（SenderActivity L1093） | **缺失**（无独立文案；`正在连接` 在 receiver 包检索为 0，`连接中` 仅为「连接中断」子串） |
| 正在投 | `"正在投屏到 {name}"`（SenderActivity L945） | 浮层设备名（ReceiverActivity） |
| 已暂停 | `"已暂停投屏 · 大屏保留最后一帧"`（CaptureService L433） | `"{name} 已暂停"`（ReceiverSession L571） |
| 恢复中 | `"连接中断，正在恢复"`（CaptureService L736） | `"连接中断，正在恢复"`（ReceiverActivity L458） |
| 出错 | `ErrorNotice` 23 个 Cause | `ErrorNotice` + notice 浮层 |

- **落地**：把文案收敛到纯逻辑 `StateCopy`（实现单 T04831「六态统一文案与状态条」），再由本用例锁 12/12。

### TC-04 状态优先级不被覆盖（G1）

- **断言**：恢复中状态不被「传输中 / 正在投」回写；暂停不被心跳覆盖；出错提示 3 秒内不被普通状态替换。
- **通道**：纯逻辑单测（状态机抽函数后）。
- **基线**：`CaptureService` L139 已有「连接中断后不要把刚写上的提示盖回传输中」的注释与分支，但无对应测试；属 G1 缺口。

### TC-05 空列表诊断覆盖率（G1）

- **断言**：5 个空态判决各有唯一「原因 + 下一步 + 动作」，覆盖率 100%；文案不重复。
- **判决来源**：`DeviceRegistry.EmptyReason`（`NONE_FOUND / ALL_OFFLINE`，`HAS_ONLINE` 属非空态）+ `DiscoveryLadder.Empty`（`SEARCHING / AP_ISOLATED / DUAL_BAND`）。
- **基线（4/5）**：

| 判决 | 现有文案 | 原因 | 下一步/动作 |
| --- | --- | --- | --- |
| NONE_FOUND | `"正在搜索同一网络里的电视…"`（SenderActivity L517） | 有 | 搜索中，动作由页面提供 |
| ALL_OFFLINE | `"这些设备当前离线，可用投屏码连接"`（L518） | 有 | 有 |
| SEARCHING | 同上（`SEARCHING` 常量） | 有 | 搜索中 |
| AP_ISOLATED | `"当前网络禁止设备互访，可关闭 AP 隔离或改用扫码"` | 有 | 有 |
| DUAL_BAND | `"手机与电视可能不在同一频段"` | 有 | **缺**（无动作） |

- **落地**：`DUAL_BAND` 补下一步（如「改用投屏码或扫码」）；`NONE_FOUND / ALL_OFFLINE` 文案从 UI 下沉到纯逻辑后由本用例锁 5/5（实现单 T04830 / T04842）。

### TC-06 错误提示可执行（G1，复用）

- **断言**：每个 `ErrorNotice.Cause` 都有「发生了什么 + 下一步」，且头部不超单行预算；每个 Category 至少被一个 Cause 覆盖。
- **通道**：现有 `ErrorNoticeTest`（12 个用例）直接复用，不重复建设。
- **基线**：PASS（23 个 Cause）。

### TC-07 朗读描述覆盖率（G2）

- **断言**：`clickable=true` 的节点必须有非空 `text` 或 `content-desc`；纯图标、二维码、图形按钮必须有 `contentDescription`；覆盖率 100%。
- **通道**：`uiautomator dump` 解析节点树（脚本可放 `build/mtask/`，不入生产代码）。
- **基线（未达标）**：全项目主源码 `contentDescription` **0 处**；点击入口 34 处（Main 3 / Sender 11 / Receiver 20）。文字按钮可靠 text 朗读，图标与自绘控件（倒计时环、二维码、浮层）无朗读标签。
- **落地**：无障碍属性补全设计（T04818）与实现（T04844），本用例为验收线。

### TC-08 焦点顺序与视觉顺序一致（G2）

- **断言**：UI dump 中 focusable 节点的屏幕坐标按「从上到下、从左到右」单调；开局首焦点是首个可点控件。
- **通道**：UI dump + 坐标排序断言。
- **基线**：统一接线已存在（`TvFocus.applyTree` 3 处，`FocusCoverageTest` 锁住），运行时顺序未测；属 G2 缺口。

### TC-09 TV 字号下限（G1 + G2）

- **断言**：TV 模式正文 ≥24sp、标题 ≥40sp；主视觉区无小于 24sp 的正文；手机端正文 ≥14sp。
- **通道**：现有 `FocusPolicyTest`（已锁 40/24/14）+ 新增源码守卫（ui 源码中 TV 分支的字面量 `textSize` 不低于 24）+ UI dump 复核（G2）。
- **基线**：`FocusPolicyTest` PASS；ui 源码 `textSize` 赋值 55 处（Main 5 / Sender 11 / Receiver 39），尚未按「TV 主视觉区」筛分。

### TC-10 主视觉区元素 ≤3（G2）

- **断言**：TV 首屏主视觉区（标题、主状态、主操作）元素不超过 3 个；次要信息不在主视觉区。
- **通道**：UI dump 节点计数。
- **基线**：未测（需电视/模拟器 TV 模式）；`MainActivity` 当前 5 个 TextView + 2 个 Button，若整页算主视觉区则不达标，需按「主视觉区」定义筛分。

### TC-11 首帧可见时间（G3）

- **断言**：只记录，不设阈值（PRD 明确不编造数值）；同一设备连续 3 次取中位数，改造前后对比。
- **通道**：`adb shell am start -W -n <pkg>/.ui.MainActivity` 的 `TotalTime` / `WaitTime`。
- **基线**：未执行（本环境模拟器归其它会话使用）。

## 三、自动化落地映射

| 用例 | 落点 | 落地单 |
| --- | --- | --- |
| TC-01 / TC-02 / TC-09（源码守卫） | `app/src/test/java/.../ui/` 新增源码守卫测试 | T04869 |
| TC-03 / TC-04 / TC-05 / TC-06 | 纯逻辑 JVM 单测（`StateCopy`、`EmptyStateCopy`、状态机） | T04869 + 实现单 T04830/T04831 |
| TC-07 / TC-08 / TC-10 | `uiautomator dump` 断言脚本 | T04870（模拟器）/ T04871（真机） |
| TC-11 | 真机 `am start -W` 记录 | T04871 |

复用资产：`ErrorNoticeTest`（12 用例）、`FocusPolicyTest`（5 用例）、`DeviceCardPolicyTest`（7 用例）、`FocusCoverageTest`（3 用例）、`DeviceCardInteractionTest`（1 用例）、`DiscoveryLadderTest`（5 用例）、`DeviceRegistryTest`（空态判决 3 处）。

## 四、基线复验命令

```bash
# 朗读描述与点击入口
grep -rn "contentDescription" app/src/main/java | wc -l
grep -rc "setOnClickListener" app/src/main/java/io/github/wangnan05563/airmirror/ui/*.kt

# 首屏入口
grep -n "category.LAUNCHER" -B4 app/src/main/AndroidManifest.xml
wc -l app/src/main/java/io/github/wangnan05563/airmirror/ui/MainActivity.kt

# 空态判决与文案
grep -rn "enum class EmptyReason\|enum class Empty" app/src/main/java/io/github/wangnan05563/airmirror/net
grep -rn "TEXT_AP_ISOLATED\|TEXT_DUAL_BAND\|EmptyReason\." app/src/main/java

# 状态文案分布
grep -rn "已暂停\|正在恢复\|正在连接\|正在搜索" app/src/main/java --include=*.kt
```

## 五、口径裁定记录

- 原问题：PRD §1.2「首屏零等待」现状列把启动路径写成「一启动就是设备列表 / 待连接页」，但代码里 LAUNCHER 是 `MainActivity` 选择页（发送 / 接收两个入口），到目标页还要多点一次。
- **T04877 裁定（方案 2，2026-11-09）**：选择页不算前置页；首屏判据为「启动进入选择页，1 跳内到列表/待连接页；视觉改造不得新增跳数」。不改现有交互，只订正文档口径。
- 后续若要把选择页合并进列表 / 待连接页（方案 1）或改为直达上次使用的角色页（方案 3），另开实现单，本文不自行改口。
