# AirMirror 视觉常量收敛方案（P-03 / UI-01 前置）

| 项 | 内容 |
| --- | --- |
| 任务 | T04817 [PRD] 2.2 视觉常量收敛方案 |
| 需求 | 专项 PRD UI-01「视觉令牌单一来源」；痛点 P-03 |
| 基线 | HEAD `b896dfa`（2026-11-09）；只读扫描，不改代码 |
| 产物性质 | 方案文档；不含实现、不改 PRD |
| 下游实施 | T04834（4.5 设计令牌单一来源与 View 组件）、T04841（3.1.1 UI-01 视觉令牌收敛） |

---

## 01 结论先行

1. 当前真正的**视觉**硬编码只有 **14 处颜色 + 2 处字号 + 2 处描边/圆角**，集中在 `ReceiverActivity`、`SenderActivity`、`TvFocus` 三个文件；其余 7 处 `0x…` 是协议魔数与像素掩码，不是视觉常量，不得一起改。
2. 其中 2 处颜色是已有令牌的重复值（`0xFF0B1F3A`=`BACKGROUND`、`0xFF12314F`=`SURFACE`），直接替换；另 12 处需要 **6 个新令牌**（半透明遮罩 3 个、分隔线 1 个、焦点底 1 个、二维码白 1 个）。
3. 字号只需补 **2 个**：手机标题 28sp、卡片标题 18sp；TV 40/24、手机正文 14、投屏码 36 已有。
4. 描边/时长补 **1 个描边 + 3 个时长**：环形进度 10dp、焦点 120ms、连接 200ms、码轮换 150ms（后者供 UI-06 使用）。
5. 网页内联样式（8 个色值）不做手工对抄：由 Kotlin 令牌在 `page()` 里生成 CSS 变量，单一来源仍是 `Theme`；具体取色替换属 UX-04 实施，本方案只定机制。
6. 用一条**源码守卫单测**把「Theme 之外出现视觉字面量」变成红灯，白名单显式列出协议魔数、像素掩码与二维码黑白；否则这次收敛完，下一次又会散。

---

## 02 现状盘点（2026-11-09 实测）

### 2.1 视觉颜色：14 处

| 文件 | 行 | 字面量 | 语义 | 替换为 |
| --- | --- | --- | --- | --- |
| `ui/ReceiverActivity.kt` | 425, 437, 482, 538 | `0xCC0B1F3A` | 80% 深蓝遮罩 | `Theme.SCRIM`（新） |
| `ui/ReceiverActivity.kt` | 462 | `0xE60B1F3A` | 90% 深蓝遮罩 | `Theme.SCRIM_STRONG`（新） |
| `ui/ReceiverActivity.kt` | 603 | `0xFF0B1F3A` | 页面底色 | `Theme.BACKGROUND` |
| `ui/ReceiverActivity.kt` | 621, 741 | `0x3312314F` | 分隔线 | `Theme.DIVIDER`（新） |
| `ui/ReceiverActivity.kt` | 914, 941 | `0xE612314F` | 90% 面（设置浮层） | `Theme.PANEL`（新） |
| `ui/ReceiverActivity.kt` | 318, 350 | `0xFFFFFFFF` | 二维码白底 | `Theme.QR_LIGHT`（新） |
| `ui/SenderActivity.kt` | 306 | `0xFF12314F` | 卡片面 | `Theme.SURFACE` |
| `ui/TvFocus.kt` | 85 | `0x660B1F3A` | 焦点态半透明底 | `Theme.FOCUS_FILL`（新） |

小计：14 处；复用现有令牌 2 处，新增令牌承接 12 处。

### 2.2 字号：2 处

| 文件 | 行 | 字面量 | 语义 | 替换为 |
| --- | --- | --- | --- | --- |
| `ui/SenderActivity.kt` | 162 | `textSize = 28f` | 发送端页面标题 | `Theme.PHONE_TITLE_SP`（新，28f） |
| `ui/SenderActivity.kt` | 467 | `textSize = 18f` | 设备卡片名称 | `Theme.CARD_TITLE_SP`（新，18f） |

### 2.3 描边 / 圆角 / 时长

| 文件 | 行 | 现值 | 语义 | 替换为 |
| --- | --- | --- | --- | --- |
| `ui/Theme.kt` | 35, 40 | `strokeWidth = 10f` | 倒计时环线宽 | `Theme.RING_STROKE_DP = 10`（`Theme.dp` 落值） |
| `ui/TvFocus.kt` | 87 | `Theme.dp(context, 6)` | 焦点描边圆角 | `Theme.RADIUS_FOCUS_DP = 6` |
| `ui/FocusPolicy.kt` | 16 | `FOCUS_ANIM_MS = 120L` | 焦点动画 | 引用 `Theme.DURATION_FAST_MS` |
| `ui/FocusPolicy.kt` | 19 | `CONNECT_FADE_MS = 200L` | 连接淡入 | 引用 `Theme.DURATION_CONNECT_MS` |
| 新增 | — | `150L`（UI-06） | 投屏码淡入淡出 | `Theme.DURATION_CODE_FADE_MS` |

说明：`FocusPolicy` 的两个常量本身已是命名常量，不算散落字面量；收敛动作是让它们指向 `Theme`，保证「改时长只改一处」。

### 2.4 不是视觉常量的 `0x…`（白名单）

| 文件 | 行 | 值 | 性质 |
| --- | --- | --- | --- |
| `crossnet/CrossNetProtocol.kt` | 25, 240 | `0x414D4358` / `0x414D5031` | 协议魔数 |
| `crossnet/Stun.kt` | 19 | `0x2112A442` | STUN 魔数 |
| `protocol/Protocol.kt` | 17 | `0x414D5243` | 协议魔数 |
| `sender/CaptureService.kt` | 198, 199, 370, 371 | `0xFFFFFFFE` | 偶数对齐掩码 |

另：`ui/Qr.kt:12` 的 `Color.BLACK / Color.WHITE` 是二维码对比可靠性选择，保持不动（专项 PRD UI-07 明确二维码不着色）。

### 2.5 间距与组件尺寸（现状，供第二阶段）

- `Theme.dp(..., n)` 实测值：`1×3、4×8、6×6、8×13、10×16、12×28、14×1、16×19、20×11、24×13、28×3、32×10、48×2、56×1、72×1、120×1、190×1`。
- 其中 `48 / 56 / 72 / 120 / 190` 是组件尺寸（按钮高、页顶、控制条、二维码），不是间距；`1 / 6 / 10 / 14` 是栅格外的精细值。
- 本方案不把全部间距塞进全局令牌（会造成 10+ 个近似值互相替代、视觉悄悄变化）；只做「同值同名」：第一阶段收敛颜色/字号/描边/时长，间距在 T04834 随组件一起收敛。

### 2.6 网页内联样式：8 个色值

`web/BrowserCastServer.kt:209-218`：`#0b1f3a`（底）、`#f2f6fb`（正文）、`#a9b8cc`（次级）、`#12314f`（卡片）、`#2a4a6b`（边框）、`#4da3ff`（主按钮）、`#04101f`（按钮文字）、`#8fa2ba`（弱提示）。

- 与 App 令牌同值：`#0b1f3a`、`#12314f`。
- 其余 6 个是网页独有取值，需 UX-04 决定是改成 `TEXT_PRIMARY / TEXT_SECONDARY / ACCENT / ON_ACCENT / BORDER` 还是保留；本方案只要求**值从 Kotlin 生成**，不手工抄两份。

---

## 03 令牌设计

### 3.1 颜色令牌（新增 6 个，全部保持现值）

```kotlin
// Theme.kt
const val SCRIM = 0xCC0B1F3A.toInt()          // 80% 背景色：投屏中遮罩
const val SCRIM_STRONG = 0xE60B1F3A.toInt()   // 90% 背景色：确认/接管浮层
const val PANEL = 0xE612314F.toInt()          // 90% 面：设置浮层
const val DIVIDER = 0x3312314F.toInt()        // 20% 面：分隔线
const val FOCUS_FILL = 0x660B1F3A.toInt()     // 40% 背景色：焦点态底
const val QR_LIGHT = 0xFFFFFFFF.toInt()       // 二维码白底（不参与改色）
```

命名规则：

- 以**用途**命名，不以透明度命名（`SCRIM` 而不是 `BG_ALPHA_80`）；透明度值写进注释，换色时不被迫改名。
- 已有基础色（`BACKGROUND / SURFACE / ACCENT / TEXT_PRIMARY / TEXT_SECONDARY / OFFLINE / DANGER`）不改名、不改值。
- 不再新增 `SURFACE_ALPHA_*` 这类「基础色 × 透明度」的笛卡尔命名；只有真正被使用的 3 个组合才配令牌。

### 3.2 字号令牌（新增 2 个）

```kotlin
const val PHONE_TITLE_SP = 28f   // 发送端页面标题（现值 28）
const val CARD_TITLE_SP = 18f    // 设备卡片名称（现值 18）
```

现有：`TITLE_SP = 40f`（TV 标题）、`BODY_SP = 24f`（TV 正文）、`PHONE_BODY_SP = 14f`、`CODE_SP = 36f` 保持不变。

### 3.3 描边 / 圆角 / 时长

```kotlin
const val RING_STROKE_DP = 10
const val RADIUS_FOCUS_DP = 6
const val DURATION_FAST_MS = 120L
const val DURATION_CONNECT_MS = 200L
const val DURATION_CODE_FADE_MS = 150L
```

`FocusPolicy.FOCUS_ANIM_MS` 与 `CONNECT_FADE_MS` 改为 `const val = Theme.DURATION_*` 的别名；`FocusPolicyTest` 的断言值不变。

### 3.4 间距（第二阶段，不阻塞本方案验收）

若 T04834 决定收敛间距，按「同值同名」建 9 个令牌，不做四舍五入：

| 令牌 | 值 | 现状命中 |
| --- | --- | --- |
| `SPACE_1` | 4dp | 8 |
| `SPACE_2` | 6dp | 6 |
| `SPACE_3` | 8dp | 13 |
| `SPACE_4` | 10dp | 16 |
| `SPACE_5` | 12dp | 28 |
| `SPACE_6` | 16dp | 19 |
| `SPACE_7` | 20dp | 11 |
| `SPACE_8` | 24dp | 13 |
| `SPACE_PAGE` | 32dp | 10 |

组件尺寸不进入全局间距令牌：`QR_SIZE_DP = 190`、`QR_SMALL_DP = 120`、`CONTROL_HEIGHT_DP = 72`、`TOUCH_MIN_DP = 48`、`HERO_TOP_DP = 56` 作为组件自身常量。

---

## 04 网页 CSS 桥（单一来源）

`BrowserCastServer.page()` 里用 Kotlin 生成 `:root` 变量，页面样式只引用变量：

```kotlin
private fun cssColor(argb: Int): String =
    "#%06X".format(argb and 0xFFFFFF)   // 网页不使用 alpha，半透明另给 rgba

private fun tokenCss(): String = """
    :root{
      --bg:${cssColor(Theme.BACKGROUND)};
      --surface:${cssColor(Theme.SURFACE)};
      --accent:${cssColor(Theme.ACCENT)};
      --text:${cssColor(Theme.TEXT_PRIMARY)};
      --text-dim:${cssColor(Theme.TEXT_SECONDARY)};
    }
""".trimIndent()
```

- 本任务只定义机制与映射，不改页面取值；实际替换与「共享」等文案问题由 UX-04（T04832/T04848）承接。
- 不引入模板引擎、不新增依赖；字符串拼接即可，minSdk 19 无影响。
- 生成结果可用现有 `GET /w/<token>/` 冒烟断言 `--bg:#0B1F3A` 等特征串。

---

## 05 迁移顺序（实施时）

1. **加令牌**：在 `Theme.kt` 增加 6 色 + 2 字号 + 2 尺寸/描边 + 3 时长；不改任何调用点，跑一次单测确认编译。
2. **替换重复值**：`ReceiverActivity:603 → BACKGROUND`、`SenderActivity:306 → SURFACE`（零风险）。
3. **替换派生色**：按 2.1 表逐行替换 11 处；每处替换后与替换前 ARGB 相等，可用单测断言。
4. **替换字号/描边/时长**：`SenderActivity:162/467`、`Theme.CountdownRingView:35/40`、`TvFocus:87`、`FocusPolicy:16/19`。
5. **加守卫**：新增 `VisualTokenGuardTest`（见 06），先以白名单让现有违规归零。
6. **网页桥**：接入 `tokenCss()`，由 UX-04 完成取值替换。
7. **回归**：单测 + 模拟器截图对比（收件页/待连接页/设置页/发送端列表），确认像素级无变化。

每一步独立提交、独立回写；出问题按步 `git revert`，不一次性大改。

---

## 06 守卫设计（把规范变成红灯）

新增 `app/src/test/.../ui/VisualTokenGuardTest.kt`：

```kotlin
@Test fun `no visual color literals outside tokens`() {
    // 扫描 src/main/java/**/*.kt，命中 0x[0-9A-Fa-f]{8} 的文件/行
    // 允许：ui/Theme.kt；白名单：协议魔数与像素掩码（文件+行）、Qr.kt 的 Color.BLACK/WHITE
}

@Test fun `no textSize literals outside tokens`() {
    // 命中 textSize\s*=\s*[0-9.]+f；允许：Theme.kt
}

@Test fun `derived tokens keep exact argb`() {
    // SCRIM==0xCC0B1F3A、SCRIM_STRONG==0xE60B1F3A、PANEL==0xE612314F、
    // DIVIDER==0x3312314F、FOCUS_FILL==0x660B1F3A、QR_LIGHT==0xFFFFFFFF
}
```

- 源码路径用 `File("src/main/java")`（Gradle 单测工作目录为模块根），不依赖系统绝对路径。
- 白名单按「文件 + 行 + 原因」写死，新增协议常量必须显式加白，防止顺手放过视觉色值。
- 守卫测试同时是验收证据：实施后应为 0 违规。

---

## 07 验收判据（对齐 UI-01）

- [ ] `Theme.kt` 之外视觉色值命中 0（白名单外），协议魔数/像素掩码/Qr 黑白仍保留。
- [ ] `textSize` 字面量在 `Theme.kt` 之外命中 0。
- [ ] 6 个新色令牌 ARGB 与替换前逐字节相等（单测断言）。
- [ ] `FocusPolicy` 的 120/200 指向 `Theme.DURATION_*`，`FocusPolicyTest` 不改断言仍通过。
- [ ] 网页样式不再手写颜色，改为引用生成的 CSS 变量（UX-04 完成取值替换）。
- [ ] release 未签名包仍满足主 PRD §14.1 分级上限；未新增位图、未引入 AndroidX/Compose。
- [ ] 模拟器关键页截图与改造前一致（间距/字号/颜色均未变）。

---

## 08 风险与约束

| 风险 | 处置 |
| --- | --- |
| 把协议魔数当色值批量替换 | 守卫白名单显式列文件+行；只改 2.1 表内 14 处 |
| 半透明色改写后 ARGB 不等 | 单测锁 6 个令牌的精确值；替换时不重算透明度 |
| 字号换算 sp/dp 导致 TV 变大 | 新字号只承接现值 28/18，不调整既有 40/24/14/36 |
| 网页颜色与 App 令牌语义不同（网页是实色，App 是带 alpha 白） | 只生成有对应关系的 5 个变量；其余 6 个色值待 UX-04 裁决 |
| 单测读源码在 CI 路径不同 | 用相对模块根的 `File("src/main/java")`，并在测试里断言目录存在 |
| 一次性大改难回滚 | 按 05 的 7 步分批，每步可独立 revert |

约束：minSdk 19、无 AndroidX、release 满足主 PRD §14.1 分级上限；不改 `Qr.kt` 的二维码黑白对比；不改任何布局尺寸。

---

## 09 待决事项

1. 半透明令牌命名采用「用途名」（本方案：`SCRIM / PANEL / DIVIDER / FOCUS_FILL`），不采用 `SURFACE_ALPHA_*` 笛卡尔命名；若团队偏好后者，改名即可，值不变。
2. 间距是否在本轮一起收敛：本方案列为第二阶段，不阻塞颜色/字号验收；由 T04834 决定。
3. 网页 6 个独有颜色（`#f2f6fb / #a9b8cc / #2a4a6b / #4da3ff / #04101f / #8fa2ba`）是否并入 App 令牌，属 UX-04 范围，本文只提供生成机制。

---

## 10 证据（2026-11-09）

```text
扫描范围：app/src/main/java/**/*.kt，排除 ui/Theme.kt 与 ui/Qr.kt
颜色 0x[0-9A-Fa-f]{8}：22 处 / 7 文件
  - 视觉 14 处：ReceiverActivity 12 + SenderActivity 1 + TvFocus 1（明细见 2.1）
  - 非视觉 8 处：协议魔数 4（CrossNet 2 / Stun 1 / Protocol 1）+ 像素掩码 4（CaptureService）
textSize = Nf：2 处（SenderActivity 162/467）
Theme.dp 取值：17 种，4～190dp
网页内联色值：8 个（BrowserCastServer.kt:209-218）
```

- 行号复核：`ReceiverActivity` 425/437/462/482/538/603/621/741/914/941/318/350；`SenderActivity` 162/306/467；`TvFocus` 85/87；`Theme` 12-25/35/40；`FocusPolicy` 16/19；`Qr` 12；`BrowserCastServer` 209-218。
- 说明：`0xFFFFFFFF` 在 `ReceiverActivity` 的 2 处是二维码容器白底，计入视觉令牌；`Qr.kt` 内部的 `Color.BLACK/WHITE` 保持白名单。
