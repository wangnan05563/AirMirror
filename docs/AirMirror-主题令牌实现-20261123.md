# AirMirror 主题令牌实现（T06102 / REQ-008 / REQ-025）

| 项 | 内容 |
| --- | --- |
| 任务 | T06102 [PRD] 实现主题令牌系统 |
| 需求 | REQ-008 三层令牌、禁止硬编码颜色、Midnight 像素等价、调试预览页、OLED 接口预留；REQ-025 验收 |
| 验收 | AC-08：代码无硬编码颜色；默认主题像素等价迁移差异 ≤1% |
| 基线 | T06099《主题令牌结构 v2.0》、T06101 错误卡 |
| 产物性质 | 生产代码 + debug 预览页 + 单测/截图证据；不改布局尺寸与业务口径 |

## 01 结论先行

1. `ThemeTokens.kt` 成为唯一定义点：Raw 色板 → Semantic 语义 → Component 组件，页面/组件只引用 Semantic/Component 或旧 `Theme` 兼容别名。
2. `Theme.kt` 的旧常量全部改为令牌别名，**不再持有任何十六进制色值**；`StatusColors`、`DeviceCardTag` 也清掉了本地色值。
3. Midnight 与迁移前逐值相等，`ThemeRenderEvidenceTest` 用 17 组旧色/新令牌逐像素比对，差异 **0**，比 AC 的 ≤1% 更严。
4. OLED 只注册能力位 `supportsTrueBlack=true`、`releaseSelectable=false`，本期不可选、不进入 release 设置页。
5. debug 预览页展示三层令牌、字阶、圆角/动效与 5% 安全区；360dp 与 1080p 宽各导出一张全长图，release 不打包。
6. 源码守卫把例外收窄到 `ThemeTokens.kt` 一个文件；`Theme.kt`、`StatusColors.kt`、`DeviceCardTag.kt` 重新纳入扫描。

## 02 三层令牌

### 2.1 Raw 原始层

`ThemeTokens.kt` 的 `RawColors` / `RawPalette`（`internal`），只描述值本身：

- 色板：`ink-950/900/800`、`teal-500`、`white`、`red-400`；
- 透明度：`white-70/30`、`ink950-40/80/90`、`ink900-20/90`、`teal-500-20/12`、`white-10/8`；
- 字阶、间距、圆角、动效由 `TypographyTokens` / `SpacingTokens` / `RadiusTokens` / `MotionTokens` 承载。

`ThemeTokens.raw` 为 `internal`，页面不得引用；守卫扫描 `ui/` 下除 `ThemeTokens.kt` 外不得出现 `RawPalette` / `RawColors`。

### 2.2 Semantic 语义层

`SemanticTokens` 共 18 项：`background`、`surface`、`surfaceElevated`、`accent`、`textPrimary`、`textSecondary`、`offline`、`danger`、`scrim`、`scrimStrong`、`panel`、`divider`、`focusFill`、`surfaceStroke`、`qrLight`、`accentSubtle`、`surfaceSubtle`、`surfaceGhost`。

### 2.3 Component 组件层

`ComponentTokens` 按组件命名：页面底、卡片/抬升卡/描边、主/次/兜底按钮、对话框、六态状态色、空态诊断卡、码槽、二维码、向导步骤、协议/离线/不支持标签、内嵌块与三个圆角。组件层全部引用 Semantic 或 Radius，不再重复写色值。

## 03 迁移落点

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/io/github/wangnan05563/airmirror/ui/ThemeTokens.kt` | 新增三层数据类、`ThemeSpec`、`MidnightTheme`、`OledTheme`、`ThemeResolver`、`ThemeTokens` |
| `app/src/main/java/io/github/wangnan05563/airmirror/ui/Theme.kt` | 所有旧常量改为令牌别名；新增 `SCRIM`/`SCRIM_STRONG`/`PANEL`/`FOCUS_FILL`/`component` 入口；文件内 0 个十六进制色值 |
| `app/src/main/java/io/github/wangnan05563/airmirror/ui/StatusColors.kt` | `BAR_LAYER` → `scrimStrong`，`OVERLAY_LAYER` → `scrim`，最差底色用 `qrLight`；六态文字取 Component |
| `app/src/main/java/io/github/wangnan05563/airmirror/ui/DeviceCardTag.kt` | 3 个标签底色/前景改组件令牌，间距改 `SPACE_SM_DP` |
| `app/src/main/java/io/github/wangnan05563/airmirror/ui/UiKit.kt` | 主/次/兜底按钮、卡片、内嵌块、对话框按钮全部取 Component |
| `SenderActivity.kt` / `ReceiverActivity.kt` / `ErrorStateCard.kt` / `TvFocus.kt` / `ErrorIllustrationView.kt` | 旧派生名改为 `Theme.SCRIM`/`Theme.PANEL`/`Theme.FOCUS_FILL`/`Theme.component.cardElevated`，渲染值不变 |
| `app/src/debug/java/.../ThemePreviewActivity.kt` | 新增 debug-only 预览页与可测量 `ThemePreviewView` |
| `app/src/debug/AndroidManifest.xml` | 注册预览 Activity，release 不打包 |

`app/src/main/res/values/colors.xml` 仍是 Android 平台主题的资源镜像（manifest/启动图标只能读资源），值由 `ThemeTokenGuardTest` 锁定与 Raw 同值；布局代码不引用这些资源。

## 04 调试预览页

- 入口：`ThemePreviewActivity`，仅 debug；`adb shell am start -n io.github.wangnan05563.airmirror/.ui.ThemePreviewActivity`。
- 内容：Raw 全部 17 项（8 个主色块 + 9 个透明度文本行）、Semantic 全部 18 项、Component 按钮/标签、字阶、圆角/动效、5% 安全区（1920×1080 → 96px/54px）、OLED 能力位。
- 截图：
  - `docs/assets/theme-tokens-20261123/theme-preview-1080p-full-20261123.png`（1920×2250，1080p 宽，全长无截断）
  - `docs/assets/theme-tokens-20261123/theme-preview-360dp-full-20261123.png`（720×3105，360dp 宽，全长无截断）
  - `docs/assets/theme-tokens-20261123/theme-legacy-vs-tokens-20261123.png`（1088×128，上旧色下新令牌）

## 05 验收证据

- `ThemeTokensTest` 9 项：Raw/Semantic/Component 完整性、无悬空、圆角引用、字阶/间距/动效等价、旧常量别名、Resolver/OLED 能力位。
- `ThemeTokenGuardTest` 9 项：仅 `ThemeTokens.kt` 可有十六进制色值；`Theme.kt` 无 hex 且旧令牌齐全；Raw 不被页面引用；`colors.xml` 与 Raw 同值；原有 textSize/刻度/时长守卫继续生效。
- `ThemeTokenTest` 7 项：原有冻结值全部保持。
- `ThemeRenderEvidenceTest` 3 项：360dp/1080p 全长预览导出；17 组旧色与令牌逐像素 0 差异。
- `ErrorStateCardUiTest` 3 项：错误卡迁移后仍通过（UiKit/Component 改动未破坏 T06101）。
- 定向合计 **31 项 / 0 失败**。
- 全量回归：**118 类 / 877 项，873 通过 / 4 失败 / 0 跳过**（`C:\Temp\AirMirrorBuild6`）。4 项与 T06101 基线同源，均为既有 Windows 临时文件抖动，不在本单代码路径：`ApkDownloadTest` 1 项（`.part` 改名瞬时占用）、`BrowserCastServerTest` 3 项（临时 mp4 删除/残留）。
- 回归中曾暴露旧守卫 `CodeFadeGuardTest` 把 `150L` 的归属写死为 `Theme.kt`；令牌迁移后已改为断言 `ThemeTokens.kt`，修正后全量复跑通过，无新增代码失败。

## 06 与 T06099 结构表的差异

1. `button.secondary.bg` 取 `surface`（#12314F），不取 `surfaceElevated`（#1A3D61）：现网 `UiKit.button(Kind.Secondary)` 就是面底，改成抬升面会产生可见色差，违反“Midnight 像素等价”。已在 `ComponentTokens` 注释说明。
2. 新增 `accentSubtle`/`surfaceSubtle`/`surfaceGhost` 三个语义令牌，承接原 `DeviceCardTag` 的 `0x1F17C3B2`/`0x1AFFFFFF`/`0x14FFFFFF`，避免把标签衬底留在豁免文件里。
3. 间距只保留现网实际使用的 6 个刻度（4/8/12/16/24/32），不引入未落地的 6/10/20，避免“令牌很多但没人用”。

## 07 边界与下一步

- 本单不改布局、不改页面结构、不改协议；页面接入仍按 T06103/T06104。
- OLED 只预留接口与能力位，真实纯黑语义值与开关归后续单；本期 release 选择器只有 Midnight。
- `colors.xml` 属 Android 资源层，无法直接引用 Kotlin 常量；由测试锁值，若后续换主题需同步资源镜像或改用动态主题。
- 真机主题观感、OLED 纯黑面板与 TV overscan 仍归 T06115 现场验收。
