# AirMirror UI 风格规范冻结（T04812）

> 依据：专项 PRD §2.2「现有 UI 风格规范（本轮必须继承，不推倒重来）」+ 主 PRD §11.5 / §7.7。
> 代码唯一真相源：`ui/Theme.kt`、`ui/FocusPolicy.kt`、`ui/TvFocus.kt`、`error/ErrorNotice.kt`（HEAD `b896dfa`，2026-11-09 只读采集）。
> 效力：本轮 M1–M4 的视觉改造**必须继承**以下取值；确需变更须产品签字，并在本文件回填新值。

## 一、冻结结论

| 维度 | 冻结值 | 代码出处 |
| --- | --- | --- |
| 背景 / 面 / 强调 | `#0B1F3A` / `#12314F` / `#17C3B2` | `Theme.BACKGROUND` / `SURFACE` / `ACCENT`（L12-14） |
| 文字主 / 次 | `#FFFFFF` / 70% 白 `#B3FFFFFF` | `Theme.TEXT_PRIMARY` / `TEXT_SECONDARY`（L15-16） |
| 离线 / 危险 | 30% 白 `#4DFFFFFF` / `#FF6B6B` | `Theme.OFFLINE` / `DANGER`（L17-18） |
| 字号 | TV 标题 40sp、TV 正文 24sp、手机正文 14sp、投屏码 36sp | `Theme.TITLE_SP` / `BODY_SP` / `PHONE_BODY_SP` / `CODE_SP`（L20-25） |
| 手机标题 | 32sp（TV 走 40sp） | `FocusPolicy.titleSp`（L42） |
| 焦点态 | 2dp 青绿描边 + 40% 深蓝底 + 1.04 放大 + 120ms | `FocusPolicy.OUTLINE_DP` / `FOCUSED_SCALE` / `FOCUS_ANIM_MS`（L12-16）、`TvFocus.outline`（L84-87） |
| 连接成功淡入 | 200ms；C 级设备 0ms | `FocusPolicy.CONNECT_FADE_MS`（L19）、`fadeDurationMs`（L28） |
| 文案词汇 | 只用「设备 / 投屏 / 镜像 / 推送 / 投屏码」；禁用「投射 / 同屏 / 共享」 | `ErrorNotice.kt` L7、L47（`MAX_HEADLINE_CHARS = 18`） |

## 二、色板（不可改值）

| 令牌 | 值 | 语义 | 现值 |
| --- | --- | --- | --- |
| BACKGROUND | `0xFF0B1F3A` | 页面底色、浮层底 | `Theme.kt` L12 |
| SURFACE | `0xFF12314F` | 卡片、面板、控制条 | L13 |
| ACCENT | `0xFF17C3B2` | 主强调、焦点描边、进度环 | L14 |
| TEXT_PRIMARY | `0xFFFFFFFF` | 主文字 | L15 |
| TEXT_SECONDARY | `0xB3FFFFFF`（70%） | 次级说明 | L16 |
| OFFLINE | `0x4DFFFFFF`（30%） | 离线态文字 | L17 |
| DANGER | `0xFFFF6B6B` | 错误、危险动作 | L18 |

派生透明度色（已在使用，冻结为「底色的透明度变体」，令牌化时**不得改变渲染值**）：

| 现值 | 透明度 | 用途与位置 |
| --- | --- | --- |
| `0xCC0B1F3A` | 80% | 接收端浮层/遮罩（`ReceiverActivity` L425/437/482/538） |
| `0xE60B1F3A` | 90% | 接收端恢复条（`ReceiverActivity` L462） |
| `0x660B1F3A` | 40% | TV 焦点填充（`TvFocus` L85） |
| `0xE612314F` | 90% | 面板（`ReceiverActivity` L914/941） |
| `0x3312314F` | 20% | 分隔线/内嵌块（`ReceiverActivity` L621/741） |
| `0xFF12314F` | 100% | 卡片（`SenderActivity` L306） |

## 三、字号与焦点（不可改值）

- **字号下限**：TV 正文 ≥24sp、TV 标题 ≥40sp；手机正文 ≥14sp（`FocusPolicyTest` 已锁）。投屏码 36sp 是为 6 位码在环内单行放下（`Theme.kt` L24 注释），不得为「更醒目」调大。
- **焦点态**：2dp `ACCENT` 描边 + `0x660B1F3A` 填充 + 圆角 6dp + 1.04 放大 + 120ms；触屏模式不显示焦点框（`TvFocus` 置 `isFocusableInTouchMode=false`）。
- **开局焦点**：总是尝试给首个可点控件（`FocusPolicy.shouldRequestInitialFocus() = true`），页面首次建立才请求，设置页刷新不得抢焦点。
- **动效分级**：焦点 120ms、连接成功 200ms、C 级设备 0ms（直接置终态）。新增过渡（如投屏码轮换）时长不在本单冻结，由 T04819 / T04837 定稿后回填，且须遵守「轻微过渡、不闪烁」。

## 四、文案词汇表（不可扩词）

- 允许：设备、投屏、镜像、推送、投屏码（`ErrorNotice.kt` L7）。
- 禁止：投射、同屏、共享。
- 结构：错误提示首行 ≤18 字（`MAX_HEADLINE_CHARS`），每 Cause 必须含「发生了什么 + 下一步」；`ErrorNoticeTest` 12 用例覆盖 23 个 Cause。
- 状态文案 6 态（未连接 / 连接中 / 正在投 / 已暂停 / 恢复中 / 出错）按体验指标方案 TC-03 收敛，冻结期不改状态集合。

## 五、冻结期发现的偏差（只登记，本单不改）

| 偏差 | 位置 | 处置 |
| --- | --- | --- |
| 访客页主按钮 `#4da3ff`、边框 `#2a4a6b` 与令牌不同源（P-08） | `web/BrowserCastServer.kt` L214-215 | T04822 / T04832 / T04848 收敛到 ACCENT / SURFACE 派生色 |
| 访客页底/卡已同源（`#0b1f3a` / `#12314f`） | 同文件 L209/L213 | 保持不动 |
| 用户可见文案出现禁用词「共享」 | `web/BrowserCastServer.kt` L228 | 已建派生单 T04879 |
| `Theme.kt` 外硬编码色值与字号 | `ReceiverActivity` L425/437/462/482/538/603/621/741/914/941、`SenderActivity` L162/306/467、`TvFocus` L85 | 冻结值不变，由 T04817 / T04834 / T04841 / T04857 令牌化 |
| 手机标题 32sp 定义在 `FocusPolicy` 而非 `Theme` | `FocusPolicy.kt` L42 | 令牌化时迁入 Theme，取值不变（T04817） |

## 六、验收与复验命令

```bash
# 色板与字号
grep -n "BACKGROUND =\|SURFACE =\|ACCENT =\|TEXT_PRIMARY =\|TEXT_SECONDARY =\|OFFLINE =\|DANGER =\|TITLE_SP\|BODY_SP\|PHONE_BODY_SP\|CODE_SP" app/src/main/java/io/github/wangnan05563/airmirror/ui/Theme.kt

# 焦点与动效
grep -n "OUTLINE_DP\|FOCUSED_SCALE\|FOCUS_ANIM_MS\|CONNECT_FADE_MS" app/src/main/java/io/github/wangnan05563/airmirror/ui/FocusPolicy.kt
grep -n "setStroke\|0x660B1F3A\|cornerRadius" app/src/main/java/io/github/wangnan05563/airmirror/ui/TvFocus.kt

# 词汇表
grep -n "MAX_HEADLINE_CHARS\|词汇表" app/src/main/java/io/github/wangnan05563/airmirror/error/ErrorNotice.kt
grep -rn "投射\|同屏\|共享" app/src/main/java/io/github/wangnan05563/airmirror/ui app/src/main/java/io/github/wangnan05563/airmirror/error
```
