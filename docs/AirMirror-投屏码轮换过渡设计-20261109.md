# AirMirror 投屏码轮换过渡设计（P-05 / UI-06）

> 关联任务：T04819（[PRD] 2.4 投屏码过渡动效设计）、实施 T04837（4.8 码轮换过渡与时长令牌）、验收 T04870（模拟器）/ T04871（真机）。
> 上游依据：专项 PRD UI-06（L292-298）、主 PRD §11.2「轮换时有轻微过渡，不闪烁」、§7.6 轮换与锁定规则。
> 基线日期：2026-11-09；只读取证于当前工作区（工作树含其他会话改动）。

---

## 1. 目标与问题

P-05 的事实是：**投屏码每秒被整段重写，轮换时直接从旧数字跳到新数字**。

- `ReceiverActivity.refreshWaitingPage()` 每秒执行一次 `codeText.text = ...`（L1226），不区分「秒数没变」与「码变了」。
- 轮换时没有过渡：6 位数字瞬间替换，视觉上是一次跳变；专项 PRD 要求「轻微过渡，不闪烁」。
- 结束确认条每秒重写（L987）与投屏码属于同类「每秒刷新」，但本单只处理投屏码，避免把动效扩散到倒计时文本。
- 全项目没有统一的时长令牌：`FocusPolicy` 自带 120/200（L16/L19），`Theme` 无 `DURATION_*`；UI-06 要求所有时长收进 `Theme`。

目标：在不改轮换周期、旧码宽限、锁定规则、二维码生成逻辑的前提下，把「码值变化」做成 **150ms 交叉淡化**，C 级设备 0ms，且过渡期间不出现空白帧。

### 1.1 「150ms 淡出/淡入」的解释

UI-06 写「150ms 淡出 / 淡入」，同时要求「过渡期间不出现空白帧」。若按「150ms 淡出 + 150ms 淡入」串行执行，中点两层都不可见，必然出现空白帧；若理解为「一次 150ms 的过渡，旧码淡出与新码淡入同时进行」，两个要求同时成立。

**本设计采用后者**：总时长 150ms，旧码 alpha 1→0 与新码 alpha 0→1 同时进行（交叉淡化）。这是对 PRD 的**时序解释**，在实施评审记录，不改 PRD 正文。

---

## 2. 现状审计（只读取证）

| 事实 | 证据 | 影响 |
| --- | --- | --- |
| 码每秒整段重写 | `refreshWaitingPage` L1222-1229：`codeText.text = if (locked) LOCK_PLACEHOLDER else code.code()` | 每秒都触发一次文本设置，轮换与普通秒刷新无法区分 |
| 锁定期占位符 | `LOCK_PLACEHOLDER = "------"` L1364 | 锁/解锁也是一次显示值变化，应与换码同样过渡 |
| 单一码视图 | `codeText` 声明 L59、创建 L325、加入码槽 L333、更新 L1226；全文 4 处 | 改造成本低，但单视图无法交叉淡化 |
| 码容器固定尺寸 | `codeBlock` 为 `side × side`（L322-336），`side = Theme.dp(190)`（L317） | 双层叠加不会改变布局，不会推挤下方内容 |
| 码文字规格 | `textSize = Theme.CODE_SP`、`maxLines = 1`、`letterSpacing = 0.02f`（L325-331） | 新旧两层必须完全同规格，否则淡化中字号/字距跳动 |
| 环形倒计时每秒更新 | `ring.fraction = seconds / 60f`（L1229） | 本单不动；轮换时环归位是既有行为 |
| 连接浮层已有 200ms 淡入 | `FocusPolicy.fadeDurationMs`（L28）→ `showCasting` L1306-1313 | 证明 `DeviceProbe.policy(...).animationsEnabled` 的 C 级关闭动效路径可复用 |
| 无时长令牌 | `Theme.kt` 无 `DURATION_*`；`FocusPolicy` 有 `FOCUS_ANIM_MS=120`、`CONNECT_FADE_MS=200` | 本单新增 `Theme.DURATION_CODE_FADE_MS`；120/200 迁入 Theme 属 UI-01，不在本单重复定义 |
| 无逐帧/第三方动画 | 全项目无 `ObjectAnimator`/`AnimationDrawable`/动画库；仅 `animate()` | 交叉淡化只用 `ViewPropertyAnimator`，不引入依赖 |
| 轮换参数 | `CastCode`：TTL 60s、宽限 10s、锁定 60s、错误上限 5（L167-171） | **本单不改**，仅读 |
| 无障碍联动 | T04818 设计：码变化播报一次、每秒内容不进 live region | 过渡开始即提交新描述，旧层对无障碍隐藏 |

---

## 3. 设计

### 3.1 视图结构：双层码槽

把单 `TextView` 改成固定尺寸的码槽，内部两层同规格文本：

```
FrameLayout codeSlot (side × side, 居中)
 ├─ TextView codeA (MATCH_PARENT, gravity=center, CODE_SP, maxLines=1, letterSpacing=0.02)
 └─ TextView codeB (同上, 初始 INVISIBLE, alpha=0)
```

- 两层共享同一套样式函数 `codeStyle(TextView)`，避免字号/字距漂移。
- `ReceiverActivity` 目前没有 `onResume` / `onPause`；本设计新增这两个回调维护 `resumed` 标志（只用于「是否允许启动过渡」，不改会话生命周期）。
- 任一时点只有一层是「当前码」；另一层是「下一码」。
- 角色交换而不是每轮新建 View：零分配、零布局变化。
- `codeSlot` 的尺寸、位置、与下方 `countdownText` 的间距全部不变。

### 3.2 状态机（纯逻辑）

新增 `CodeFadePolicy`（无 `android.*`）：

```kotlin
object CodeFadePolicy {
    data class Input(
        val animationsEnabled: Boolean,  // DeviceProbe.policy(...).animationsEnabled
        val firstRender: Boolean,        // 页面首次显示
        val previous: String?,           // 上一次实际显示值
        val next: String,                // 本次应显示值（码或 LOCK_PLACEHOLDER）
        val pageVisible: Boolean,        // waiting.visibility == VISIBLE
        val resumed: Boolean,            // Activity 处于前台
    )

    fun shouldAnimate(i: Input): Boolean =
        i.animationsEnabled && !i.firstRender && i.previous != null &&
            i.previous != i.next && i.pageVisible && i.resumed

    fun shouldCommit(i: Input): Boolean = i.previous != i.next
}
```

判定顺序：

1. **首次显示**：直接置终态（首帧不能等 150ms）。
2. **值未变**：什么都不做（消除每秒重写；秒数在 `countdownText`，不在这里）。
3. **页面不可见 / Activity 不在前台 / C 级关闭动效**：直接置终态，不排队动画。
4. **值变化且允许动效**：交叉淡化。

### 3.3 过渡执行

```kotlin
private fun renderCode(next: String, animate: Boolean) {
    if (next == shownCode) return
    val incoming = inactive
    val outgoing = active
    incoming.text = next
    incoming.alpha = 0f
    incoming.visibility = View.VISIBLE
    incoming.bringToFront()
    A11y.describe(incoming, A11yCopy.code(next, locked, seconds))   // T04818 口径
    A11y.decorative(outgoing)                                       // 过渡中旧层不读
    if (!animate) {
        outgoing.visibility = View.INVISIBLE
        outgoing.alpha = 0f
        incoming.alpha = 1f
    } else {
        val ms = FocusPolicy.codeFadeMs(animationsEnabled)
        outgoing.animate().alpha(0f).setDuration(ms).start()
        incoming.animate().alpha(1f).setDuration(ms)
            .withEndAction {
                outgoing.visibility = View.INVISIBLE
                outgoing.alpha = 0f
                A11y.describe(outgoing, "") // 或恢复 AUTO，确保只剩一层可读
            }
            .start()
    }
    swapRoles()
    shownCode = next
}
```

硬约束：

- **同时开始、同时结束**：两层的 `setDuration(fadeMs)` 必须相同，且在同一帧启动。
- **抢占**：每次启动前对两层 `animate().cancel()`；取消后立即把「当前目标值」置为终态，避免动画队列叠加。
- **同规格**：两层字号、字距、行数、gravity 相同；`codeSlot` 固定尺寸，淡化不触发布局。
- **不空帧**：两层 alpha 之和在过渡期间恒定（1→0 与 0→1），任何一帧至少一层可见。
- **a11y 单节点**：过渡中旧层 `importantForAccessibility = NO`，新层在开始时即带新描述；播报按 T04818「换码一次、不刷屏」。
- **不动环**：`ring.fraction` 保持每秒归位；不参与淡化。
- **不动二维码**：QR 图在换码时立即重算（扫码可靠性），不与码文字同步淡化。

### 3.4 时长与降级

| 条件 | 时长 | 依据 |
| --- | --- | --- |
| A/B 级设备（`animationsEnabled=true`） | 150ms | UI-06；`Theme.DURATION_CODE_FADE_MS` |
| C 级设备（`animationsEnabled=false`） | 0ms，直接置终态 | 专项 PRD §8.8 / UI-06 |
| 页面不可见或 Activity 暂停 | 0ms | 不在后台排队动画 |
| 首次显示 | 0ms | 首帧不等 |
| 值未变化 | 不执行 | 每秒刷新不应产生动画 |

`FocusPolicy.codeFadeMs(enabled) = if (enabled) Theme.DURATION_CODE_FADE_MS else 0L`。`Theme.DURATION_CODE_FADE_MS = 150L` 是唯一真相源；若 UI-01 先落地同名令牌，则直接复用，不重复定义。连接浮层 200ms 与焦点 120ms 保持不变。

---

## 4. 时序图

```
t=0ms     旧码 alpha=1  新码 alpha=0   （新码 text 已设、描述已换、旧层已对 a11y 隐藏）
t=75ms    旧码 alpha=0.5 新码 alpha=0.5 （视觉上等同一次轻淡切换，无空白）
t=150ms   旧码 alpha=0  新码 alpha=1   （旧层 INVISIBLE，角色交换完成）
t≥150ms   稳定显示新码，直到下一次值变化或 60s 轮换
```

总时长 150ms，远小于轮换周期 60s 与宽限 10s；不改变任何配对判定。

---

## 5. 测试设计

### 5.1 JVM：`CodeFadePolicyTest`（≥10 用例）

1. `animationsEnabled=true` → `codeFadeMs == Theme.DURATION_CODE_FADE_MS == 150L`。
2. `animationsEnabled=false` → `0L`。
3. 首次显示 → `shouldAnimate=false`。
4. `previous == next` → `shouldAnimate=false` 且 `shouldCommit=false`。
5. 码变化 → `shouldAnimate=true`。
6. 码 → 锁定占位符 → `shouldAnimate=true`。
7. 锁定 → 新码 → `shouldAnimate=true`。
8. 页面不可见 → `shouldAnimate=false`（但仍 `shouldCommit=true`）。
9. Activity 暂停 → `shouldAnimate=false`。
10. 持续时间 < 1000ms 且 < TTL（60_000ms）且 < 宽限（10_000ms）。
11. 两层使用同一 `codeFadeMs`（纯逻辑输出只有一个时长值）。
12. 轮换参数不被本设计触碰：`CastCode` 的 TTL/宽限/锁定常量源码不变（源码守卫）。

### 5.2 源码守卫：`CodeFadeGuardTest`

- `ui/ReceiverActivity.kt` 中不再存在「单 `TextView` 直接写码」：`codeText` 声明与赋值清零；码槽必须有两个同规格 `TextView`。
- 全项目禁止 `ObjectAnimator` / `AnimationDrawable` / `android.animation.*` / GIF 播放库。
- 数字 `150` 只允许出现在 `Theme.DURATION_CODE_FADE_MS`；`ReceiverActivity` 必须通过 `FocusPolicy.codeFadeMs(...)` 取值。
- 两层动画的 `setDuration` 调用必须使用同一局部变量（同名）——防止一边 150、一边 200。
- `refreshWaitingPage` 必须先比较 `code()` 与 `shownCode` 再写视图；不允许每秒无条件赋值。
- `codeSlot` 尺寸不引用与字号联动的变量（固定 `Theme.dp(190)`），防止淡化改变布局。

### 5.3 模拟器验收（T04870）

| 断言 | 方法 |
| --- | --- |
| E1 无空白帧 | `screenrecord` 覆盖一次轮换，抽 33ms 帧；6 位码区域每帧至少一层 alpha > 0 |
| E2 不闪烁 | 连续帧的码区域亮度单调过渡，无「亮→白→亮」尖峰 |
| E3 无布局跳动 | 码槽与倒计时文本的边界矩形在过渡前后完全一致 |
| E4 时长 | 旧值最后一帧到新值稳定帧 ≤ 200ms（目标 150ms + 采样误差） |
| E5 C 级瞬切 | 强制 `animationsEnabled=false`，相邻两帧直接完成切换，无中间 alpha |
| E6 单播报 | 一次换码只产生一条无障碍播报；每秒刷新不产生事件（T04818 A8 复用） |
| E7 单可读节点 | UI dump 中码节点恰一个，文本为旧码/新码，永不空、永不读占位符 |

### 5.4 真机（T04871，R 级）

3 个场景：正常轮换观感、锁定→解锁、C 级盒子（若可得）瞬时切换；记录录屏与帧差。

---

## 6. 落地与文件清单

| 阶段 | 内容 | 文件 |
| --- | --- | --- |
| P1 | 新增令牌与纯策略 | `ui/Theme.kt`（+`DURATION_CODE_FADE_MS`）、`ui/FocusPolicy.kt`（+`codeFadeMs`）、`ui/CodeFadePolicy.kt`（新） |
| P2 | 码槽与渲染 | `ui/ReceiverActivity.kt`：`codeText` → `codeSlot/codeA/codeB`、`renderCode`、`refreshWaitingPage` 变化检测 |
| P3 | 测试 | `test/.../ui/CodeFadePolicyTest.kt`、`test/.../ui/CodeFadeGuardTest.kt` |
| P4 | 验收 | T04870 帧提取脚本、T04871 记录 |

依赖：无新增三方库；不引入 AndroidX；`ViewPropertyAnimator` 为平台 API，minSdk 19 可用。

---

## 7. 验收指标

1. 码值变化时 150ms 交叉淡化；任何采样帧码区域不空。
2. C 级 / 关动效设备 0ms 瞬切。
3. 每秒刷新不触发动画、不产生无障碍事件。
4. 过渡前后码槽与倒计时边界不变。
5. `Theme.DURATION_CODE_FADE_MS` 单测锁定 150；无第二处 150 字面量。
6. 轮换 60s / 宽限 10s / 锁定 60s / 5 次错误规则源码不变。
7. 无新增依赖、无新增图片资源、release 包体不增。
8. TalkBack：换码播报一次，过渡中只读新码。

---

## 8. 风险与边界

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| 150ms 解释分歧 | 「淡出/淡入」可能被理解为串行 300ms | §1.1 明确交叉淡化；实施评审记录 |
| 每秒刷新误触发动画 | 现有代码每秒赋值 | 先比较 `shownCode`，值变才渲染 |
| 动画叠加 | 锁定/解锁可能紧随换码 | 每次启动前 cancel 并置终态 |
| 后台动画 | 投屏中页面 GONE 仍每秒刷新 | 不可见/暂停时直接置终态 |
| 双层可读 | TalkBack 可能读到两层 | 旧层过渡中 `NO`，新层开始时即带描述 |
| 设备掉帧 | C 级设备淡化卡顿 | 直接 0ms，不做逐帧补偿 |
| 与 UI-01 令牌重复 | 两边都加 `DURATION_*` | 同名复用；本单只在缺失时新增，禁止重复定义 |

---

## 9. 明确不做

- 不改投屏码轮换周期、旧码宽限、锁定与错误次数规则。
- 不淡化环形倒计时、二维码、倒计时文本、结束确认条。
- 不改连接成功 200ms 淡入与焦点 120ms 动效。
- 不引入属性动画框架、逐帧动画、GIF、Lottie 等任何依赖。
- 不做扫码/输入框的过渡反馈（属 X-02 容错与 UX-02）。
- 不改 PRD 正文；时序解释在实施评审记录。
