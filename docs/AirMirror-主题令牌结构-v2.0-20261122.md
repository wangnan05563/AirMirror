# AirMirror 主题令牌结构 v2.0（T06099 / REQ-008）

| 项 | 内容 |
| --- | --- |
| 任务 | T06099 [PRD] 定义主题令牌结构 |
| 需求 | REQ-008：原始/语义/组件三层令牌，禁止硬编码颜色；默认 Midnight 像素等价迁移，调试预览页，OLED 接口预留 |
| 验收 | AC-08：代码无硬编码颜色；默认主题像素等价迁移差异 ≤1% |
| 基线 | HEAD `1ed55fc`（2026-11-22）；`Theme.kt` 已有颜色/字号/间距常量，但缺三层结构与主题注册表 |
| 产物性质 | 结构定义与迁移口径；本单不实现页面改造，不改变任何现有渲染值 |

## 01 结论先行

1. 采用 **Raw → Semantic → Component** 三层令牌。页面只能引用 Component 或 Semantic，不得直接引用 Raw。
2. 首发主题固定为 **Midnight**，所有令牌值与当前 `Theme.kt` / `StatusColors.kt` 现值逐项等价，迁移前后像素差异目标 ≤1%，实际上限为 0 个可见色值变化。
3. 现有 7 个基础色、9 个派生色、13 个字号/圆角/间距值全部纳入语义层；`StatusColors` 与 `DeviceCardTag` 中的 5 个透明度字面量改为语义令牌别名。
4. OLED 只预留接口：`ThemeId.OLED` 注册位、能力位 `supportsTrueBlack`，本期不发布、不改变默认主题。
5. 调试预览页只在 debug 构建提供，展示三层令牌与组件样例；release 不打包入口。
6. **5% 安全区**按屏幕短边计：电视 1920×1080 时左右各 96px、上下各 54px；所有标题、状态、主操作、投屏码必须落在安全区内。

## 02 三层结构

### 2.1 Raw 原始层

只描述颜色、字阶、间距、圆角、时长本身，不带页面语义。

| 分组 | 令牌 | 值 | 说明 |
| --- | --- | --- | --- |
| 色板 | `ink-950` | `#0B1F3A` | 深蓝底 |
| 色板 | `ink-900` | `#12314F` | 面 |
| 色板 | `ink-800` | `#1A3D61` | 抬升面 |
| 色板 | `teal-500` | `#17C3B2` | 强调 |
| 色板 | `white` | `#FFFFFF` | 正文白 |
| 色板 | `red-400` | `#FF6B6B` | 危险 |
| 透明度 | `white-70` | `#B3FFFFFF` | 70% 白 |
| 透明度 | `white-30` | `#4DFFFFFF` | 30% 白 |
| 透明度 | `ink-950-40/80/90` | `#660B1F3A` / `#CC0B1F3A` / `#E60B1F3A` | 遮罩/焦点底/浮层 |
| 透明度 | `ink-900-20/90` | `#3312314F` / `#E612314F` | 分隔线/面板 |
| 透明度 | `teal-500-20` | `#3317C3B2` | 低对比描边 |
| 字阶 | `display/title/body/caption` | 40/32/24/14sp | 角色名，不按页面命名 |
| 间距 | `space-1..8` | 4/6/8/10/12/16/20/24dp | 同值同名，不做四舍五入 |
| 圆角 | `radius-button/card/inset/dialog/focus` | 14/16/12/24/6dp | 组件造型 |
| 时长 | `duration-fast/connect/code-fade` | 120/200/150ms | 动效唯一来源 |

### 2.2 Semantic 语义层

描述“是什么”，不描述“长什么样”。

| 语义令牌 | 引用 Raw | 现值 | 用途 |
| --- | --- | --- | --- |
| `background` | `ink-950` | `#0B1F3A` | 页面底 |
| `surface` | `ink-900` | `#12314F` | 卡片/控制条 |
| `surfaceElevated` | `ink-800` | `#1A3D61` | 设置卡/诊断卡 |
| `accent` | `teal-500` | `#17C3B2` | 主操作/焦点/进度 |
| `textPrimary` | `white` | `#FFFFFF` | 正文 |
| `textSecondary` | `white-70` | `#B3FFFFFF` | 次级说明 |
| `offline` | `white-30` | `#4DFFFFFF` | 非文字装饰；离线文字改用 `textSecondary` |
| `danger` | `red-400` | `#FF6B6B` | 错误/危险 |
| `scrim` | `ink-950-80` | `#CC0B1F3A` | 投屏浮层底 |
| `scrimStrong` | `ink-950-90` | `#E60B1F3A` | 恢复条/确认浮层 |
| `panel` | `ink-900-90` | `#E612314F` | 设置面板 |
| `divider` | `ink-900-20` | `#3312314F` | 分隔线/内嵌块 |
| `focusFill` | `ink-950-40` | `#660B1F3A` | TV 焦点底 |
| `surfaceStroke` | `teal-500-20` | `#3317C3B2` | 柔和描边 |
| `qrLight` | `white` | `#FFFFFF` | 二维码白底，不参与改色 |

### 2.3 Component 组件层

只允许组件名，不允许页面名；同一组件在手机/电视只换尺寸，不换色。

| 组件令牌 | 引用 Semantic | 取值 |
| --- | --- | --- |
| `page.background` | `background` | `#0B1F3A` |
| `card.background` | `surface` | `#12314F` |
| `card.elevated` | `surfaceElevated` | `#1A3D61` |
| `card.stroke` | `surfaceStroke` | `#3317C3B2` |
| `card.radius` | `radius-card` | 16dp |
| `button.primary.bg` | `accent` | `#17C3B2` |
| `button.primary.fg` | `ink-950` | `#0B1F3A` |
| `button.secondary.bg` | `surfaceElevated` | `#1A3D61` |
| `button.secondary.fg` | `textPrimary` | `#FFFFFF` |
| `button.radius` | `radius-button` | 14dp |
| `dialog.scrim` | `scrimStrong` | `#E60B1F3A` |
| `dialog.radius` | `radius-dialog` | 24dp |
| `status.connected` | `accent` | `#17C3B2` |
| `status.recovering` | `textPrimary` | `#FFFFFF` |
| `status.error` | `danger` | `#FF6B6B` |
| `emptyDiagnosis.card` | `surfaceElevated` | `#1A3D61` |
| `codeSlot.ring` | `accent` | `#17C3B2` |
| `codeSlot.bg` | `surface` | `#12314F` |
| `qr.background` | `qrLight` | `#FFFFFF` |
| `wizard.stepActive` | `accent` | `#17C3B2` |
| `wizard.stepIdle` | `textSecondary` | `#B3FFFFFF` |

## 03 主题注册与 OLED 接口

```kotlin
enum class ThemeId { MIDNIGHT, OLED }

data class ThemeCapabilities(
    val supportsTrueBlack: Boolean,
    val debugPreview: Boolean,
)

interface ThemeSpec {
    val id: ThemeId
    val capabilities: ThemeCapabilities
    val semantic: SemanticTokens
}
```

- `MIDNIGHT`：默认主题，`supportsTrueBlack=false`，与当前版本逐值一致。
- `OLED`：仅注册接口，`supportsTrueBlack=true`；本期不实现、不切换、不进入 release 设置页。
- 解析顺序：`ThemeResolver.resolve(ThemeId.MIDNIGHT)`；页面不得自行 `when(themeId)` 拼色。
- 主题切换只允许改 Semantic/Component，Raw 色板不可被页面覆盖。

## 04 5% 安全区

| 屏幕 | 安全区左右 | 安全区上下 | 说明 |
| --- | --- | --- | --- |
| 1920×1080 | 96px | 54px | 5% 短边/对应轴，电视 overscan 最保守口径 |
| 1280×720 | 64px | 36px | 同比例 |
| 3840×2160 | 192px | 108px | 同比例 |

约束：

1. 标题、主状态、主操作、投屏码、错误提示均在安全区内。
2. 背景、装饰、焦点外发光可越界；文字与可点击控件不可越界。
3. TV 布局以安全区为父容器计算，不以物理屏幕全宽计算。
4. 手机不受电视 overscan 约束，按 16dp 页面边距。

## 05 与现有代码的映射

| 现有位置 | 现值 | 目标语义令牌 | 迁移动作 |
| --- | --- | --- | --- |
| `Theme.BACKGROUND` | `#0B1F3A` | `background` | 保留常量名作为兼容别名 |
| `Theme.SURFACE` | `#12314F` | `surface` | 保留 |
| `Theme.ACCENT` | `#17C3B2` | `accent` | 保留 |
| `Theme.TEXT_PRIMARY` | `#FFFFFF` | `textPrimary` | 保留 |
| `Theme.TEXT_SECONDARY` | `#B3FFFFFF` | `textSecondary` | 保留 |
| `Theme.OFFLINE` | `#4DFFFFFF` | `offline` | 仅装饰使用 |
| `Theme.DANGER` | `#FF6B6B` | `danger` | 保留 |
| `Theme.BACKGROUND_ALPHA_*` | 3 个派生色 | `scrim` / `scrimStrong` / `focusFill` | 逐步改为语义别名 |
| `Theme.SURFACE_ALPHA_90` | `#E612314F` | `panel` | 逐步改为语义别名 |
| `Theme.DIVIDER` | `#3312314F` | `divider` | 保留 |
| `Theme.SURFACE_ELEVATED` | `#1A3D61` | `surfaceElevated` | 保留 |
| `Theme.SURFACE_STROKE` | `#3317C3B2` | `surfaceStroke` | 保留 |
| `StatusColors.BAR_LAYER` / `OVERLAY_LAYER` | 2 个字面量 | `scrimStrong` / `scrim` | 改引用，不改值 |
| `DeviceCardTag` 3 个底色 | 3 个字面量 | `accent-12` / `white-10` / `white-8` | 新增组件令牌后改引用 |

## 06 迁移与门禁

### 6.1 迁移顺序

1. 新增 `ThemeTokens.kt`：Raw/Semantic/Component 数据类与 `Midnight` 注册表。
2. `Theme.kt` 常量改为注册表别名；保留旧常量名，避免大面积改调用点。
3. `StatusColors` / `DeviceCardTag` 的透明色改语义引用。
4. `ReceiverActivity` / `SenderActivity` 只替换硬编码，不改布局与尺寸。
5. debug 预览页展示色板、字阶、组件、安全区框。
6. source guard 锁“Theme 外不得出现视觉 ARGB”；协议魔数、像素掩码、二维码黑白进白名单。

### 6.2 验收门禁

- **G1**：`ThemeTokenTest` 三层引用完整、默认主题值不变、语义令牌无悬空。
- **G1**：source guard 扫描 `ui/` 下 ARGB 字面量，除白名单外为 0。
- **G2**：模拟器截屏前后像素差异 ≤1%；关键页面 360dp/1080p 各一张。
- **G3**：debug 预览页截图与令牌表逐项对照。

## 07 评审记录

| 角色 | 结论 | 日期 |
| --- | --- | --- |
| 产品 | 三层命名、Midnight 等价迁移、OLED 只预留接口，通过 | 2026-11-22 |
| 设计 | 5% 安全区按短边计，组件层不出现页面名，通过 | 2026-11-22 |
| Android | 旧常量名保留为别名，避免一次性大改，通过 | 2026-11-22 |
| QA | 迁移前后像素差异 ≤1%、source guard 可执行，通过 | 2026-11-22 |

## 08 交付证据

- 本文件：三层令牌、5% 安全区、迁移映射、验收门禁。
- 代码基线：`app/src/main/java/io/github/wangnan05563/airmirror/ui/Theme.kt`
- 关联状态色：`app/src/main/java/io/github/wangnan05563/airmirror/ui/StatusColors.kt`
- 关联标签色：`app/src/main/java/io/github/wangnan05563/airmirror/ui/DeviceCardTag.kt`
- 下游实现：T06102（主题令牌系统）、T06103（TV 布局套用安全区）、T06104（空态视觉组件）
