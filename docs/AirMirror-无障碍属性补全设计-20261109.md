# AirMirror 无障碍属性补全设计（P-04 / UI-03）

> 关联任务：T04818（[PRD] 2.3 无障碍属性补全设计）、实施 T04844、验收 T04870/T04871。
> 上游依据：`AirMirror-体验与UI优化专项PRD-竞品调研-v1.0.md` §2.3 UI-03（L268-274）、`AirMirror-体验指标验证方案-20261109.md` TC-07/TC-08（L79-90）。
> 基线日期：2026-11-09；基线提交：工作区当前 HEAD（工作树含其他会话改动，本单只读源码）。

---

## 1. 目标与问题

P-04 的事实不是「少写了几个 contentDescription」，而是**界面对辅助技术没有可用的名字、状态与路径**：

1. 全项目 `contentDescription` **0 处**，TalkBack / 遥控器用户拿到的是一棵「无名、无状态、有隐形陷阱」的视图树。
2. 34 个点击接线点在运行期展开为**至少 39 个可点节点**（设备卡片、画质档位、显示模式是动态生成的），静态 grep 数不等于运行期节点数。
3. 现有状态大量靠颜色、●/○ 符号和每秒刷新的文本表达；其中「每秒刷新」一旦接上 live region 会变成**每秒打断朗读**。
4. 覆盖层（首启引导、显示设置）出现时，底层控件仍在焦点树里，D-pad / TalkBack 可以落到看不见的控件；投屏中设置入口又被整条隐藏，只剩「长按画面」这一条辅助技术走不通的手势。

本设计要交付的是**一套能落地、能测试、能防回归的无障碍口径**，不是逐控件贴标签。

### 1.1 验收口径（本设计定义）

- **可访问名覆盖率 100%**：`clickable=true` 的节点必须同时具备非空的可访问名（可见 text 或 contentDescription），且**状态类控件的名字随状态变化**。
- **图形替代 100%**：所有承载信息的 `ImageView`（投屏二维码、浏览器二维码）必须有替代描述；纯装饰图形（倒计时环）必须从无障碍树中移除。
- **播报不刷屏**：每秒变化的文本不进 live region，只在状态切换时播报一次。
- **路径可达**：投屏中，TV / TalkBack 用户必须能打开显示设置；覆盖层打开时，底层不得可聚焦。
- **顺序一致**：焦点遍历顺序与视觉顺序一致；动态重建后焦点回到用户刚才的位置。

> 注：PRD 原文写「所有可交互控件补 contentDescription」。本设计按**字面覆盖**执行——34 个接线点全部设置描述，即使可见文字已足够也设置「可见文字 + 状态/动作」，保证 UI dump 的 100% 门槛不靠解释过关；但对「有可见文字的控件」，描述**不得引入新词**（见 R10），避免同一控件两套说法。

---

## 2. 现状审计（只读取证）

### 2.1 基线计数（`app/src/main/java` 全量）

| 指标 | 数量 | 明细 |
| --- | --- | --- |
| `contentDescription` | 0 | — |
| `importantForAccessibility` | 0 | — |
| `accessibilityLiveRegion` | 0 | — |
| `announceForAccessibility` | 0 | — |
| `AccessibilityDelegate` | 0 | — |
| `AccessibilityManager` | 0 | 没有「TalkBack 是否开启」的读取点 |
| `setOnClickListener` | 34 | Main 3 / Sender 11 / Receiver 20 |
| `setOnLongClickListener` | 1 | Receiver 全屏长按呼出设置（L193） |
| `ImageView(` | 3 | `qrView`、`browserQr`、`dlnaImageView`（Receiver） |
| 无障碍相关单测 | 0 | 现有 12 个测试类均未覆盖 a11y |

### 2.2 运行时点击节点 ≥ 39

| 屏幕 | 接线点 | 运行期节点 | 说明 |
| --- | --- | --- | --- |
| MainActivity | 3 | 3 | 两个入口 Button + 「查看完整说明」TextView（L50/55/78） |
| SenderActivity | 11 | ≥13 | `rowWith` 生成「用码连接 / 直接连接」2 个；每台设备 1 个卡片容器；展开卡片再生成 0–2 个动作按钮（L481/501） |
| ReceiverActivity | 20 | ≥23 | 显示模式 3 行（L616）；画质「自动 + 可达档位」2–6 行（L756）；其余 18 处各 1 个 |
| 合计 | 34 | **≥39** | UI dump 断言必须按运行期节点算，不能按 grep 数算 |

### 2.3 已识别的缺口

| 编号 | 缺口 | 证据 | 后果 |
| --- | --- | --- | --- |
| G1 | 图形与自绘控件无名字 | `qrView` L318、`browserQr` L349、`CountdownRingView` L323 | TalkBack 读类名或空节点，二维码无法用语言替代 |
| G2 | 状态不进名字 | 暂停/声音只有 `pauseLabel`/`audioLabel`（L258/1073）；模式/画质行带 `●/○`（L779-795） | 焦点落在控件上只听到「暂停投屏」，听不到「当前正在投」；`●` 被读成「实心圆」 |
| G3 | 每秒刷新文本 | `refreshWaitingPage` 每秒写 `codeText`/`countdownText`（L1226-1229）；`tickEndConfirm` 每秒写结束条（L987） | 直接加 live region 会每秒打断朗读，比不播报更糟 |
| G4 | 覆盖层下的底层仍可聚焦 | `TvFocus.applyTree(root)` 在 L261 一次性把 waiting/settings 控件设为 focusable；引导层 L257 覆盖其上 | D-pad / TalkBack 落到看不见的按钮 |
| G5 | 投屏中设置无辅助路径 | `settingsEntry.visibility = if (casting) GONE`（L1267），仅 `longPressCatcher` 长按（L191-198） | TalkBack 无法执行长按；遥控器不知道有这个入口 |
| G6 | 动态重建丢焦点 | `renderList()` 先 `removeAllViews()`（L445） | 展开/收起设备卡片后键盘与无障碍焦点回到页面起点 |
| G7 | 锁定期占位符无替代 | `LOCK_PLACEHOLDER = "------"`（L1364）直接写进 `codeText` | 朗读为「六个横线」，用户不知道是锁定还是加载 |
| G8 | 纯装饰视图未隐藏 | `SurfaceView`（L162）、倒计时环（L323）、`dlnaImageView`（L168） | 无障碍树出现无意义节点，干扰遍历 |
| G9 | 无回归守卫 | 无任何 a11y 测试 | 新增控件必漏，覆盖率随时间倒退 |

---

## 3. 设计原则（写进单测的硬规则）

| 规则 | 内容 | 反例（禁止） |
| --- | --- | --- |
| R1 | 描述以**动作**开头，状态置后 | 「当前正在投，暂停投屏」 |
| R2 | 不写控件类型名（「按钮」「文本框」），由系统补角色 | 「暂停投屏按钮」 |
| R3 | 数字与单位说人话 | 「58s 后换码」→「58 秒后更换投屏码」 |
| R4 | 不朗读装饰符号（●/○/→/·），选择态用「已选中 / 未选中」 | 「实心圆 完整显示」 |
| R5 | 锁定期不回读旧码，也不读占位符 | 「投屏码 ------」 |
| R6 | 术语固定为「设备 / 投屏 / 镜像 / 推送 / 投屏码」，禁用「投射 / 同屏 / 共享」（与 `ErrorNoticeTest` 同一词汇表） | 「同屏码」 |
| R7 | 描述长度 ≤ 40 字（含标点）；二维码替代说明 ≤ 50 字 | 一段话把用途、限制、售后全写进去 |
| R8 | 每秒变化的内容**不进** live region；只在「换码 / 锁定 / 暂停 / 连接 / 接管请求 / 允许请求」等状态切换时播报一次 | 给 `countdownText` 设 `POLITE` |
| R9 | 「发生了什么 + 下一步」只在一处出现；按钮描述只写动作与状态，不重复整句诊断 | 每个按钮都把离线原因念一遍 |
| R10 | 有可见文字的控件，描述 = 可见文字 + 必要状态/动作，不引入新词 | 可见「结束」、描述「终止当前会话进程」 |

---

## 4. 分层架构

```
A11yCopy（纯 Kotlin，无 android.*）     ← 全部文案与状态组合，JVM 单测直接锁
        ↑
A11y（android.* 适配层）                 ← describe / switchRow / decorative / hideSubtree / announce / liveStatus
        ↑
MainActivity / SenderActivity / ReceiverActivity   ← 只接线，不写字面量
        ↑
UI dump 脚本（T04870）+ TalkBack 真机（T04871）    ← 运行期 100% 与朗读验收
```

选择理由：

- 文案放进纯逻辑层，才能像 `ErrorNoticeTest` 一样把「长度、术语、状态差异」钉死，而不是靠人工听一遍。
- `A11y` 适配层统一处理 API 差异与幂等：minSdk 19，`announceForAccessibility`（API 16）可用；`AccessibilityAction`（API 21）与 `accessibilityHeading`（API 28）**不使用**，避免版本分支。
- 界面层只允许调用 `A11y.*`，**禁止**直接写 `contentDescription = "..."`、`importantForAccessibility = ...`，由源码守卫测试强制（见 §10.2）。

### 4.1 A11yCopy API（纯逻辑）

```kotlin
object A11yCopy {
    // 手机端发送页
    fun pause(paused: Boolean): String            // 暂停投屏，当前正在投 / 继续投屏，当前已暂停
    fun stop(target: String): String              // 停止投屏，当前投到「客厅电视」
    fun audio(on: Boolean): String                // 关闭声音，当前有声 / 打开声音，当前无声
    fun permissionFix(missing: List<String>): String
    fun crossNetSender(configured: Boolean, endpoint: String): String
    fun checkUpdate(): String
    fun codeConnect(): String
    fun addressConnect(): String
    fun takeover(from: String, seconds: Int, yield: Boolean): String
    fun card(name: String, online: Boolean, supported: Boolean, expanded: Boolean, lastLabel: String): String
    fun cardAction(name: String, mirror: Boolean): String

    // 接收端设置页
    fun settingsEntry(casting: Boolean): String
    fun mode(label: String, hint: String, selected: Boolean): String
    fun qualityAuto(selected: Boolean): String
    fun quality(tierLabel: String, width: Int, height: Int, fps: Int, selected: Boolean): String
    fun toggle(label: String, on: Boolean): String
    fun signaling(configured: Boolean, endpoint: String): String
    fun diagnostics(enabled: Boolean): String
    fun clearData(count: Int): String
    fun finish(): String

    // 接收端待连接页 / 授权 / 结束
    fun code(code: String, locked: Boolean, seconds: Int): String
    fun mirrorQr(): String
    fun browserQr(): String
    fun browserUrl(url: String): String
    fun promptAllow(name: String): String
    fun promptDeny(name: String): String
    fun endCancel(): String
    fun endConfirm(): String

    // 首启引导
    fun onboardingStep(step: Int, total: Int, title: String): String
    fun onboardingBack(): String
    fun onboardingSkip(): String
    fun onboardingNext(lastStep: Boolean): String
    fun onboardingSkipAll(): String

    // 浮层播报
    fun nameOverlay(name: String): String
    fun recovery(): String
    fun codeRotated(code: String, seconds: Int): String
    fun codeLocked(): String
}
```

约束：`A11yCopy` 文件内不得出现 `import android.`，由源码守卫断言。

### 4.2 A11y 适配层 API

```kotlin
object A11y {
    fun describe(view: View, text: CharSequence)      // 设 contentDescription；空串视为错误（开发期抛/日志）
    fun switchRow(view: View, checked: Boolean)       // delegate 上报 isCheckable/isChecked，className=Button
    fun decorative(view: View)                        // importantForAccessibility = NO
    fun hideSubtree(view: View)                       // NO_HIDE_DESCENDANTS，用于覆盖层下的底页
    fun showSubtree(view: View)                       // AUTO 恢复
    fun liveStatus(view: View)                        // accessibilityLiveRegion = POLITE
    fun announce(view: View, text: String, key: String = text)  // 去重 + 1 秒节流
    fun talkBackEnabled(context: Context): Boolean    // AccessibilityManager.isTouchExplorationEnabled
}
```

`announce` 的去重键用「文本 + 时间窗」：同一 key 在 1 秒内只播一次，避免 `showNotice` / `showCornerHint` / `showNameOverlay` 多路径重复触发。

---

## 5. 全量入口清单（34 接线点）

### 5.1 MainActivity（3）

| # | 行 | 控件 | 可见文字 | 描述 |
| --- | --- | --- | --- | --- |
| M1 | 50 | Button | 投到其他设备 | 投到其他设备，打开发送端 |
| M2 | 55 | Button | 接收别人投来的画面 | 接收别人投来的画面，打开接收端 |
| M3 | 78 | TextView（可点） | 查看完整说明 | 查看完整说明，打开数据边界说明 |

### 5.2 SenderActivity（11 接线点 / ≥13 运行期节点）

| # | 行 | 控件 | 可见文字 | 描述（状态化） |
| --- | --- | --- | --- | --- |
| S1 | 186 | Button | 去设置打开权限 | 去设置打开权限：投屏中通知、镜像声音 |
| S2 | 209 | TextView（可点） | 跨网连接…（动态） | 跨网连接：未配置，双击填写电视地址 / 已配置（…），双击修改 |
| S3 | 218 | TextView（可点） | 检查更新 | 检查更新，双击查询新版本 |
| S4 | 260 | Button | 暂停投屏 / 继续投屏 | 暂停投屏，当前正在投 / 继续投屏，当前已暂停 |
| S5 | 266 | Button | 停止 | 停止投屏，当前投到「客厅电视」 |
| S6 | 275 | Button | 声音：开 / 关 | 关闭声音，当前有声 / 打开声音，当前无声 |
| S7 | 320 | Button | 拒绝 | 拒绝「手机」接管投屏 |
| S8 | 325 | Button | 让出 | 让出投屏给「手机」，当前投屏将结束 |
| S9 | 365 → 194 | Button（helper） | 用码连接 | 用码连接，使用电视上的 6 位投屏码 |
| S10 | 365 → 240 | Button（helper） | 直接连接 | 直接连接，使用电视地址或配对链接 |
| S11 | 481 | 卡片容器 | 名称 + 状态角标 | 「客厅电视」，在线，支持镜像；双击展开操作 / 已展开，双击收起 / 离线：上次连接 5 分钟前，可能换网或关机；双击查看替代连接方式 / 不支持当前协议，双击查看支持的投屏方式 |
| S12 | 501 | Button（每动作） | 镜像屏幕 / 推送视频 | 镜像屏幕到「客厅电视」 / 推送视频到「客厅电视」 |

### 5.3 ReceiverActivity（20 接线点 / ≥23 运行期节点）

| # | 行 | 控件 | 可见文字 | 描述（状态化） |
| --- | --- | --- | --- | --- |
| R1 | 543 | TextView（可点） | 显示设置 | 打开显示设置 |
| R2–R4 | 616 | TextView ×3 | ●/○ 模式名 + 说明 | 显示模式：完整显示，已选中。等比完整显示，不裁切画面 |
| R5 | 627 | TextView（可点） | 旋转跟随：开 | 旋转跟随，当前开，双击关闭 |
| R6 | 645 | TextView（可点） | 竖屏标签：开 | 竖屏标签，当前开，双击关闭 |
| R7 | 656 | TextView（可点） | 开机自启：关 | 开机自启，当前关，双击开启 |
| R8 | 667 | TextView（可点） | 跨网信令：未配置 | 跨网信令：未配置（仅局域网），双击填写 |
| R9 | 694 | TextView（可点） | 画质角标：关 | 画质角标，当前关，双击开启 |
| R10 | 722 | TextView（可点） | 崩溃日志：关（默认） | 崩溃日志，当前关，双击开启（仅本机） |
| R11 | 729 | TextView（可点） | 清除本地日志与缓存（N 条） | 清除本地日志与缓存，共 N 条，双击清除 |
| R12 | 735 | Button | 完成 | 完成，关闭显示设置 |
| R13 | 756 | TextView ×2–6 | ●/○ 自动 / 档位 | 画质：自动（推荐），已选中 / 画质：1080p60，1920x1080，60 帧，未选中 |
| R14 | 927 | Button | 拒绝 | 拒绝「手机」投屏 |
| R15 | 932 | Button | 允许 | 允许「手机」投屏 |
| R16 | 954 | Button | 取消 | 取消，继续投屏 |
| R17 | 959 | Button | 结束 | 结束投屏 |
| R18 | 1059 | TextView（可点） | 开机自启：关（点击切换） | 开机自启，当前关，双击开启 |
| R19 | 1064 | Button | 上一步 | 上一步，返回第 1 步 |
| R20 | 1065 | Button | 跳过 | 跳过本步 |
| R21 | 1066 | Button | 下一步 / 完成 | 下一步 / 完成，保存设备名并开始等待投屏 |
| R22 | 1081 | TextView（可点） | 跳过全部 | 跳过全部设置，使用默认设备名 |

> 行 616 生成 3 个模式行；行 756 生成「自动 + 可达档位」2–6 行（`QualityTiers.reachable`，上限越低行越少）。因此 Receiver 运行期节点 23–27 个。

### 5.4 非点击但必须处理的无障碍面（14）

| 面 | 现状 | 处理 |
| --- | --- | --- |
| 投屏二维码 `qrView` L318 | ImageView，无描述 | 描述「投屏二维码，手机扫码即可投屏；也可念出上方 6 位投屏码」 |
| 浏览器二维码 `browserQr` L349 | ImageView，无描述 | 描述「浏览器投屏二维码，扫码打开上传页」 |
| 本地图片 `dlnaImageView` L168 | ImageView，无描述 | `decorative`（画面内容无法用文字替代） |
| 表面 `surfaceView` L162 | SurfaceView | `decorative` |
| 倒计时环 `ring` L323 | 自绘 View | `decorative` |
| 投屏码 `codeText` L325 | 纯数字 | 描述「投屏码 123456，58 秒后更换」；锁定时「投屏码已临时锁定，60 秒后可重试」 |
| 倒计时 `countdownText` L360 | 每秒变化 | 保留可见文本；**不设 live region**、不设描述（避免与投屏码重复） |
| 访客地址 `browserText` L340 | URL 文本 | 描述「访客上传地址，可在浏览器打开」+ 原文 |
| 崩溃恢复条 `recoveryBar` L456 | 文本 | `liveStatus` + 显示时播报一次「连接中断，正在恢复」 |
| 名称浮层 `overlayName` L421 | 3 秒 | 显示时播报「「手机」正在投屏」 |
| 提示浮层 `noticeOverlay` L433 | 3 秒 | `announce`（去重） |
| 角落提示 `cornerHint` L478 | 3 秒 | `announce`（去重） |
| 状态行 `status`（Sender L173）/ `statusText`（Receiver L378） | 文本 | `liveStatus` |
| 输入框 `codeInput`/`addressInput`/`onboardInput` | hint 即标签 | 描述「投屏码输入框，6 位数字」/「设备地址输入框」/「设备名输入框」 |

> 数量按「面」计，不是控件数；实施时以 §5.1–§5.3 的 34 个接线点 + 本表逐项核对。

---

## 6. 状态文案矩阵

| 控件 | 状态来源 | 名字必须随状态变化 | 断言 |
| --- | --- | --- | --- |
| 暂停 | `SenderControl.paused` | 暂停投屏，当前正在投 ↔ 继续投屏，当前已暂停 | 两态描述互不包含 |
| 声音 | `SenderControl.audio` | 关闭声音，当前有声 ↔ 打开声音，当前无声 | 含「当前有声/无声」 |
| 停止 | `last target.name` | 停止投屏，当前投到「X」 | 含目标名 |
| 设备卡片 | online / supported / expanded / lastLabel | 在线/离线/不支持 + 展开态 + 替代路径 | 四态两两不同；离线含「上次连接」 |
| 接管按钮 | `takeoverFrom` + 剩余秒 | 拒绝「X」接管投屏 / 让出投屏给「X」，当前投屏将结束 | 含设备名 |
| 授权按钮 | `prompt.deviceName` | 允许/拒绝「X」投屏 | 两按钮都含设备名，避免只听到「允许」 |
| 显示模式 | `displayMode` | 已选中 / 未选中 + 模式说明 | 3×2 组合全覆盖；同屏恰一行含「已选中」 |
| 画质档位 | `qualityLock` + `QualityTiers.reachable` | 自动（推荐）/ 档位 + 分辨率 + 帧率 + 选中态 | 选中行含「已选中」；未选中含「未选中」 |
| 开关行 | `store.*` | 当前开/关 + 双击切换 | 两态描述不同 |
| 投屏码 | `pairing.code` | 6 位码 + 剩余秒；锁定：不回读旧码 | 锁定时描述不含数字 |
| 二维码 | `pairing.qrPayload` | 不朗读 payload；给「上方 6 位投屏码」替代 | 描述含「投屏码」 |
| 首启步骤 | `onboardStep` | 第 N 步，共 3 步：标题 | 三步标题各自命中 |

---

## 7. 播报策略（防刷屏）

| 事件 | 机制 | 文本 | 节流 |
| --- | --- | --- | --- |
| 发送端状态变化（连接中/已连接/失败） | `liveStatus(status)` | 沿用现有状态文本 | 文本变化才播 |
| 收到接管请求 | `announce` | 「手机」请求接管投屏，请在 60 秒内选择 | 1 秒去重 |
| 换码 | `announce`（仅当 `code()` 变化） | 投屏码已更换为 123456，58 秒后再次更换 | 每次换码一次 |
| 投屏码锁定 | `announce`（锁状态翻转） | 投屏码已临时锁定，60 秒后可重试 | 状态翻转一次 |
| 允许投屏请求 | `liveStatus(promptText)` | 「手机」请求投屏，允许或拒绝 | 请求出现时一次 |
| 结束确认条出现 | `announce` | 结束投屏？10 秒后自动取消 | 出现时一次；每秒倒计时不播 |
| 名称浮层 | `announce` | 「手机」正在投屏 | 每次投屏一次 |
| 恢复条 | `liveStatus` + `announce` | 连接中断，正在恢复 | 出现时一次 |
| 提示 / 角落提示 | `announce` | 原文 | 1 秒去重 |
| 设置打开/关闭 | `announce` | 显示设置已打开 / 已关闭 | 切换时一次 |
| 首启步骤切换 | `announce` | 第 2 步，共 3 步：允许后台运行 / 自启 | 步骤变化一次 |

**硬约束**：`countdownText`（每秒）、`endText`（每秒倒计时）、`qualityBadge`（码率变化）**永不**设置 live region；只更新可见文本。

---

## 8. 结构可达性与焦点顺序

### 8.1 覆盖层隔离（G4）

| 场景 | 处理 |
| --- | --- |
| 首启引导可见 | `A11y.hideSubtree(waiting)`、`A11y.hideSubtree(settingsEntry)`；引导关闭时 `showSubtree` 恢复 |
| 显示设置可见 | `A11y.hideSubtree(waiting)`、`A11y.hideSubtree(settingsEntry)`；关闭时恢复并把无障碍焦点移回设置入口 |
| 授权条 / 结束条 | 只覆盖投屏画面，waiting 已 GONE；条内文本 `liveStatus`，不隐藏画面（无控件可点） |
| 长按捕获层 | `A11y.decorative(longPressCatcher)`，避免全屏空节点 |

### 8.2 投屏中设置可达（G5）

- 现状：`settingsEntry` 投屏中 GONE，只剩长按手势。
- 设计：`visibility = if (!casting || isTv || A11y.talkBackEnabled(this)) VISIBLE else GONE`。
  - 手机纯触屏、未开 TalkBack：保持现状（不出现）。
  - TV 或 TalkBack：显示同一个「显示设置」入口，描述「打开显示设置」。
- 理由：遥控器是 TV 主输入（PRD UI-03），长按既不可发现也不易执行；TalkBack 无长按语义。该入口是既有控件而非水印，不违反「投屏中不显示常驻水印」。
- 记录：此项属于对 UI-02「投屏中整屏只保留画面」的**边界解释**，在实施单评审时确认；不新建产品裁决单。

### 8.3 焦点顺序（TC-08）

| 屏幕 | 期望顺序（从上到下、从左到右） | 起始焦点 |
| --- | --- | --- |
| Main | 投到其他设备 → 接收别人投来的画面 → 查看完整说明 | 投到其他设备（首个可点） |
| Sender（未投屏） | 去设置打开权限（可见时）→ 接管条（可见时）→ 投屏码输入 → 用码连接 → 跨网连接 → 检查更新 → 设备卡片（展开动作紧跟卡内）→ 地址输入 → 直接连接 | 权限修复按钮（可见时）否则投屏码输入 |
| Sender（投屏中） | 暂停 → 停止 → 声音 | 暂停 |
| Receiver 待连接 | 显示设置 → 更多信息（两个可点控件，顶部入口先于底部折叠入口） | 显示设置 |
| Receiver 设置页 | 3 个模式行 → 旋转跟随 → 竖屏标签 → 开机自启 → 跨网信令 → 画质行（自动→可达档位）→ 画质角标 → 崩溃日志 → 清除本地日志与缓存 → 完成 | 当前选中模式行 |
| Receiver 首启 | 跳过全部（右上）→ 上一步 → 跳过 → 下一步（步骤 2 起开关位于正文与导航之间，顺序为 开关 → 上一步 → 跳过 → 下一步） | 下一步（主行动，显式 requestFocus） |

> **基线订正（2026-11-10，T04908）**：「Receiver 待连接」行原写「显示设置（唯一可点）」，该前提在 UI-02 新增「更多信息」折叠入口后已失效——待连接页实为两个可点控件。期望顺序按视觉从上到下为「显示设置（y≈121）→ 更多信息（y≈1488）」。
>
> **判据修订与收口（2026-11-11，T04908 二次裁决）**：实测证明「UI dump 层级顺序」判据对本平台任何无障碍顺序修复都**结构性失明**——`uiautomator dump` 按子节点索引深度优先遍历，属性集不含 traversal / nextFocus 提示（原始 dump XML 为证：root 里含「更多信息」的 ScrollView 是 index 1，「显示设置」是 index 2，故前者恒先）。裁决 A（`addView` 先后）、C（同容器改造）、以及 minSdk 22+ 的 `setAccessibilityTraversalBefore/After` 三条路线均经新鲜包运行时验证后实测**无法让 dump 顺序变绿**（证据 `t04908_ui_probe.*.json`、`t04908r2_ui_probe.json`、`t04908r2_raw.xml`；dex 检出 `traversalOrder` 证明遍历 API 确已入包运行）。
>
> **决策反转（2026-11-12，T04908 裁决 1，最终口径）**：上一段（2026-11-11）「回退 minSdk 19、否掉 minSdk 22」已被用户**推翻**。最终裁决 1 = **采用 minSdk 22+（重签 Q-5 硬下限）**，用 API 22 的 `View.setAccessibilityTraversalBefore/After`（`A11y.traversalOrder(settingsEntry, moreInfoToggle)`）显式修正 TalkBack 线性朗读顺序。理由：dump 层级顺序对该 API「结构性失明」只是**可观测性缺失**（dump 按子节点索引深度优先，属性集不含 traversal 提示），并非 API 不生效——dex 已检出 `traversalOrder` 入包运行；而真机 TalkBack 朗读顺序本就该走 traversal API（D-pad 链 `TvFocus.chain` 仅管输入焦点、不读 nextFocusId），两者互补。代价是放弃 Android 4.4–5.0（§15 C 级代表设备 EC6108V9 / 创维 T2 需重新选型）。
>
> **验收判据（2026-11-12 口径）**：① **D-pad 焦点链断言**——从显示设置按 DOWN 落更多信息、按 UP 回显示设置，模拟器可实测（已在本轮 `-gpu host` AVD 双向验证通过）；② **`A11y.traversalOrder` 接线守卫**——`AccessibilityManifestTest` 正向断言 `setAccessibilityTraversalBefore/After` 成对出现且统一走 A11y 适配层（源码守卫可查）；③ TalkBack **真机线性朗读顺序**仍归 T04871 真机复验（R 级，本 AVD 无 TalkBack 不可验）。原「UI dump 层级顺序」判据正式作废（结构性失明）。

实现要点：

- 打开设置时 `modeRows[displayMode]?.requestFocus()`，并把无障碍焦点一并移过去（`sendAccessibilityEvent(TYPE_VIEW_FOCUSED)`）。
- 关闭设置时焦点回到 `settingsEntry`。
- 待连接页 D-pad 链：`TvFocus.chain(settingsEntry, moreInfoToggle)`（nextFocusId 覆盖几何焦点搜索，跨子树按视觉先后移动）；`applyTree(root, requestInitialFocus = false)` 后由非首启分支与 `finishOnboarding` 显式把起始焦点落到 `settingsEntry`（与 `hideSettings` 一致）。
- `renderList()` 重建后按 `expandedFingerprint` 恢复：展开态焦点到第一个动作按钮，收起态回到卡片容器。
- 动态新增的画质行沿用 `TvFocus.applyTree(settingsPanel, requestInitialFocus = false)`，不抢焦点（已有）。

---

## 9. 非颜色状态表达（P-09 / UI-03）

| 状态 | 现有非颜色线索 | 本设计补强 |
| --- | --- | --- |
| 设备在线/离线 | 角标「在线/离线 · 上次连接…」 | 卡片描述含在线/离线与替代路径 |
| 暂停/投屏中 | 按钮文字变化 | 描述含「当前正在投 / 当前已暂停」 |
| 声音开/关 | 「声音：开/关」 | 描述含「当前有声/无声」 |
| 显示模式选中 | ●/○ 形状 + 文字 | 描述「已选中/未选中」，符号不进描述 |
| 画质选中 | ●/○ 形状 | 同上 |
| 开关行 | 「开/关」文字 | 描述「当前开/关」 |
| 投屏码锁定 | 「------」+「已临时锁定」 | 描述「已临时锁定，60 秒后可重试」，不读占位符 |
| 错误/恢复 | `ErrorNotice` 文本 + 恢复条 | 恢复条 live region |

结论：现有界面**没有**只靠灰度表达状态的单点，唯一风险是 `●/○` 被朗读成符号；本设计通过描述层消除。

---

## 10. 测试设计

### 10.1 JVM：`A11yCopyTest`（纯逻辑，≥22 用例）

1. 每个函数在所有布尔组合下返回非空、非纯空白。
2. 暂停/声音/开关两态描述**互不相同**，且含「当前」状态词。
3. 含设备名的描述（停止/接管/卡片/动作/授权）在换名后随之变化。
4. 设备卡片四态（在线、离线、不支持、已展开）两两不同；离线含「上次连接」与替代路径。
5. 显示模式 3 模式 × 2 状态全覆盖；同一屏恰好一行含「已选中」。
6. 画质自动 + 每个可达档位；选中/未选中互斥。
7. 投屏码正常态含 6 位数字与秒数；锁动态**不含任何数字**。
8. 二维码描述含「投屏码」替代路径，且不包含 payload。
9. 首启 3 步标题各自命中；第 3 步按钮为「完成」。
10. 权限修复描述列出缺失权限的中文名，不出现权限常量。
11. 全部描述 ≤ 40 字（二维码 ≤ 50 字）。
12. 全部描述不含禁用词（投射/同屏/共享）、不含 ●/○/------、不含 `0x`。
13. 描述不以「按钮/文本框」结尾（R2）。
14. 同一函数重复调用结果稳定（无时间/随机依赖）。

### 10.2 源码守卫：`AccessibilityManifestTest`

- `setOnClickListener` 计数：Main 3 / Sender 11 / Receiver 20；总数 34。新增交互不改此表即失败，强迫作者做无障碍决策。
- `setOnLongClickListener` 计数 1（Receiver 长按），且 `longPressCatcher` 必须 `A11y.decorative`。
- `ui/**` 中禁止字面量 `contentDescription = "`、`importantForAccessibility =`、`accessibilityLiveRegion =`；只允许 `A11y.*`。
- `A11yCopy.kt` 不得 `import android.`。
- `countdownText` / `endText` / `qualityBadge` 附近不得出现 `liveStatus(` 或 `accessibilityLiveRegion`（防刷屏）。
- 3 个 `ImageView` 必须有描述或 `A11y.decorative`。

### 10.3 UI dump：`build/mtask/a11y_dump.py`（T04870）

| 断言 | 内容 |
| --- | --- |
| A1 | 所有 `clickable="true"` 节点：`text` 或 `content-desc` 非空；覆盖率必须 100% |
| A2 | 所有 `ImageView`/二维码节点有 `content-desc` 或不在树中 |
| A3 | 不存在仅由 `●`/`○`/`------` 表达的可见状态 |
| A4 | 倒计时环、SurfaceView、图片层不出现在无障碍树 |
| A5 | TV 模式下 `focusable="true"` 节点坐标按上→下、左→右单调；首焦点为期望控件 |
| A6 | 投屏中 `显示设置` 入口存在（TV / TalkBack 场景） |
| A7 | 引导可见时，底页控件不在可访问树；设置可见时底页同理 |
| A8 | 10 秒窗口内，`countdownText` 不产生无障碍事件（通过 `uiautomator events` 或脚本轮询节点变化） |

### 10.4 真机 R 级：T04871（TalkBack）

7 个脚本场景：待连接页听码与二维码替代、授权请求（含设备名）、投屏中打开设置、显示模式切换后的选中态、结束确认倒计时不刷屏、发送端设备卡片展开/收起焦点、首启三步。

---

## 11. 落地顺序与文件清单

| 阶段 | 内容 | 文件 |
| --- | --- | --- |
| P1 基础层 | `A11yCopy` + `A11y` + 两个单测 | `ui/A11yCopy.kt`（新）、`ui/A11y.kt`（新）、`test/.../ui/A11yCopyTest.kt`（新）、`test/.../ui/AccessibilityManifestTest.kt`（新） |
| P2 发送端 | 11 个接线点、状态行 live region、`renderList` 焦点恢复、输入框标签 | `ui/SenderActivity.kt` |
| P3 接收端 | 20 个接线点、码/锁播报、覆盖层隔离、投屏中设置入口、装饰视图降噪 | `ui/ReceiverActivity.kt` |
| P4 主入口 | 3 个接线点 | `ui/MainActivity.kt` |
| P5 验收 | UI dump 脚本 + 真机 TalkBack | `build/mtask/a11y_dump.py`（T04870）、T04871 记录 |

依赖：无新增三方库；不引入 AndroidX / Material / Compose；minSdk 19 安全（仅用 API 16 的 `announceForAccessibility` 与 API 16 的 `importantForAccessibility`）。

---

## 12. 验收指标

1. UI dump：可点节点可访问名覆盖率 **100%**；二维码替代 **100%**；无符号单独表状态。
2. `A11yCopyTest` ≥22 用例全绿；`AccessibilityManifestTest` 全绿。
3. 10 秒内每秒刷新文本产生的无障碍事件 = **0**；换码/锁定各播报 **1 次**。
4. 设置打开→焦点落当前模式行；关闭→回设置入口；卡片展开/收起→焦点回到原卡片。
5. 引导/设置可见时，底页可聚焦节点数 = **0**。
6. 投屏中 TV / TalkBack 可打开设置；手机纯触屏行为不变。
7. TalkBack 真机 7 场景通过（R 级，T04871）。
8. 无新增依赖；release 包体不因本设计增加资源。

---

## 13. 风险与边界

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| 描述与可见文字漂移 | 两套字符串各写各的 | 描述由 `A11yCopy` 从同一状态生成；开关行描述复用可见标签；源码守卫禁止字面量 |
| 播报过密 | 码每秒刷新、结束条每秒倒计时 | R8 + 变化检测 + 1 秒去重；10 秒事件数断言 |
| API 19 兼容 | `AccessibilityAction`/heading 不可用 | 只用 API 16 能力；版本分支为零 |
| 覆盖层隐藏范围过大 | 把该读的也藏了 | 只藏 `waiting` 子树与 `settingsEntry`；设置面板本身保持可访问 |
| 投屏中设置入口与 UI-02 冲突 | 画面中出现一个入口 | 仅 TV/TalkBack 显示；手机触屏保持现状；实施评审确认 |
| 动态列表重建 | 全量 `removeAllViews` | 按展开态恢复焦点；UI dump A5 断言 |
| 访客网页 | `BrowserCastServer` 内联 HTML | 不在本单范围（Web 页由访客浏览器朗读，原生按钮文本已足够）；如纳入另立单 |

---

## 14. 明确不做

- 不做屏幕阅读器专属布局（TalkBack 模式切换全站视图）。
- 不做语音控制/开关访问（Switch Access）适配。
- 不改视觉样式、字号、色板（UI-01 负责）。
- 不改投屏码轮换逻辑与会话状态机。
- 不引入 AndroidX `ViewCompat` / `AccessibilityNodeInfoCompat`。
- 不改 PRD 正文；UI-02 的边界解释在实施评审记录。
