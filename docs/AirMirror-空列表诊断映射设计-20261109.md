# AirMirror 空列表诊断映射设计（UX-01 / P-01）

| 项 | 内容 |
| --- | --- |
| 任务 | T04816 [PRD] 2.1 空列表诊断优化设计 |
| 需求 | 专项 PRD UX-01「判决式空列表自救」（P0），痛点 P-01 |
| 基线 | HEAD `b896dfa`（2026-11-09），工作区含其他会话未提交改动，本文只读代码、不碰实现 |
| 产物性质 | 设计文档；不改 PRD、不改业务代码、不改发现探测策略 |
| 下游实施 | T04830（4.1 排障空状态三段式实现）、T04842（3.1.2 UX-01 空列表自救） |

---

## 01 结论先行

1. 空列表只保留 **1 条进行中状态 + 3 条可执行诊断**，另加 1 条**静默**（组播禁用但其他通道命中），不把「还在搜」说成「搜不到」。
2. 每条诊断唯一映射到一个 `ErrorNotice.Cause`，复用现有「原因 + 下一步」表与 18 字硬门；界面不再自己拼中文。
3. 诊断出现时固定 3 个动作，顺序固定：**重试发现 / 用投屏码连接 / 手动输 IP**，全部就地生效、不跳新页面。
4. 「权限缺失」**不是**发现判决：现网运行期权限只有通知与录音两项，均不影响发现（证据见 2.5），不得塞进空态，否则是误诊。
5. 「扫码连接」在发送端**没有实现**（0 处相机/扫码代码），现有 `NO_DISCOVERY` 的 `SCAN_QR` 动作没有落地路径；本设计从发送端空态移除它，并把口径差异交给产品裁决（见 09）。

---

## 02 现状审计（可复核）

### 2.1 判决层：三种空态，只带一句话

- `net/DiscoveryLadder.kt:19`：`enum class Empty { SEARCHING, AP_ISOLATED, DUAL_BAND }`。
- `net/DiscoveryLadder.kt:25`：`Verdict.Nothing(reason, notice)` 只携带一个整句 `notice`，没有动作、没有结构化原因。
- `net/DiscoveryLadder.kt:71-81`：命中即 `Found(downgraded = MULTICAST !in hits)`；未走完三轮一律 `SEARCHING`；走完才按缓存网段给 AP 隔离或双频分离。
- `net/DiscoveryLadder.kt:91-92`：两句文案分别为 26 字与 13 字，直接面向用户。
- `net/DiscoveryLadder.kt:7-10` 注释已明确「广播无响应但可直连」时**不提示**，这是既有的静默降级契约。

### 2.2 展示层：单字符串，动作无处可放

- `ui/SenderActivity.kt:98-99`：只有一个 `discoveryNotice: String` 字段。
- `ui/SenderActivity.kt:424-426`：`onNotice(text)` 直接覆盖该字符串。
- `ui/SenderActivity.kt:431`：仅当 `status.text == SEARCHING` 时把整句塞进状态行。
- `ui/SenderActivity.kt:516-522`：空列表时 `hint.text` 取同一句或两条固定句，均无按钮。
- 结果：用户读到「当前网络禁止设备互访，可关闭 AP 隔离或改用扫码」，但界面上既没有「关闭 AP 隔离」的下一步，也没有「扫码」入口。

### 2.3 文案层：词汇表与 18 字门已存在，但空态没用

- `error/ErrorNotice.kt:27-42`：已有 `Action` 枚举，含 `ENTER_CODE`、`ENTER_IP`、`RETRY_REQUEST`、`SCAN_QR`。
- `error/ErrorNotice.kt:47`：`MAX_HEADLINE_CHARS = 18`。
- `error/ErrorNotice.kt:100-103`：`Cause.NO_DISCOVERY` 的 headline 为「列表里没有设备」（7 字），动作 4 个（含发送端不可用的 `SCAN_QR`）。
- `app/src/test/.../error/ErrorNoticeTest.kt:22-48`：已锁「headline ≤18」「nextStep 不重复 headline」「禁用词」三条硬门，新增 Cause 自动继承。

### 2.4 离线层：两个来源说同一件事

- `net/DeviceRegistry.kt:104`：`EmptyReason { NONE_FOUND, ALL_OFFLINE, HAS_ONLINE }`。
- `ui/SenderActivity.kt:518`：`ALL_OFFLINE` 固定句「这些设备当前离线，可用投屏码连接」。
- `ui/DeviceCardPolicy.kt:35`：卡片侧另有「上次连接 N 分钟前 · 可能换网或关机」。
- `ui/DeviceCardPolicy.kt:42`：点击离线卡片再弹一句 `offlineHint()`。
- 结果：同一种「设备关机/换网」有 2～3 种说法，且都没有动作入口。

### 2.5 权限层：不存在「发现权限」

- `sender/RuntimePermission.kt:13-15`：运行期只申请 `POST_NOTIFICATIONS`（投屏中通知）与 `RECORD_AUDIO`（镜像带声音）。
- 二者都不参与设备发现；minSdk 19 起也没有「局域网访问」运行期权限。
- 因此专项 PRD UX-01 把「权限缺失」列为发现判决之一，与代码事实不一致；本设计不让它进入空态，避免出现「没权限所以搜不到」的错误因果。

### 2.6 扫码层：发送端无承接

- `ui/SenderActivity.kt` 全文 `扫码` / `Camera` / `zxing` 命中数均为 0（2026-11-09 实测）。
- 二维码只出现在接收端待连接页；发送端没有相机入口，`Action.SCAN_QR` 在发送端无 UI 可落。

---

## 03 设计

### 3.1 单一入口（纯逻辑）

新增不依赖 `android.*` 的判定函数，输入只有「发现判决 + 注册表空因」，输出结构化的 `Cause?`：

```kotlin
object EmptyDiagnosis {
    /** 搜索未结束、已命中、列表有在线设备时返回 null：界面不诊断、不打扰。 */
    fun of(
        verdict: DiscoveryLadder.Verdict,
        registry: DeviceRegistry.EmptyReason,
    ): ErrorNotice.Cause?
}
```

文案、下一步、动作集合全部由 `ErrorNotice.noticeFor(cause)` 提供，界面不再拼字符串。新增动作标签入口：

```kotlin
fun actionLabel(action: ErrorNotice.Action): String
```

这样「文案改一处、三端同一口径」的既有工法不变，空态只负责选择 Cause。

### 3.2 判决 → 文案 → 动作映射表

优先级：**搜索未结束不下结论 → 结束且全部已知设备离线 → 结束且无任何命中（按网段分 AP/双频）→ 有在线设备不诊断**。

| # | 输入（判决 + 注册表） | Cause | 首行（≤18 字） | 下一步 | 动作 |
| --- | --- | --- | --- | --- | --- |
| 1 | `Found(downgraded=true)` | `null` | —（静默降级） | — | — |
| 2 | `Found(downgraded=false)` | `null` | — | — | — |
| 3 | `Nothing(SEARCHING)` + 任意 | `null` | —（状态行「正在搜索同一网络里的电视…」） | — | — |
| 4 | 搜索结束 + `ALL_OFFLINE` | `DEVICE_OFFLINE` | 设备已离线（5） | 可能换网或关机；重试发现或用投屏码连接。 | 3 个 |
| 5 | 搜索结束 + `NONE_FOUND` + `AP_ISOLATED` | `DISCOVERY_AP_ISOLATED` | 网络禁止设备互访（8） | 关闭路由器的 AP 隔离，或用投屏码连接。 | 3 个 |
| 6 | 搜索结束 + `NONE_FOUND` + `DUAL_BAND` | `DISCOVERY_DUAL_BAND` | 手机和电视不在同一频段（11） | 把两台设备连到同一个 Wi-Fi，或用投屏码连接。 | 3 个 |
| 7 | `HAS_ONLINE` | `null` | —（列表非空，不诊断） | — | — |

说明：

- `ALL_OFFLINE` 优先于网络判决：用户上次连过的设备就在列表里，先说「设备离线」比说「网络禁止互访」更贴近他能做的事（开机/换网）；下一步仍保留投屏码路径。
- `SEARCHING` 期间即使注册表已知设备全离线也不下结论，沿用「三轮走完才允许说搜不到」的既有契约。
- 表中 3 条诊断的 headline 两两不同；`DEVICE_OFFLINE` 与两条网络原因互不复用。
- `Cause.NO_DISCOVERY` 不再由空态产生（它只有一句泛化文案、且动作含发送端不可用的 `SCAN_QR`），保留给旧调用；实施时把它从发送端空态路径移除，动作清单同步收口为 3 个非扫码动作。

### 3.3 动作表（固定 3 个、顺序固定）

| 顺序 | Action | 按钮文案 | 点击后 | 明确不做 |
| --- | --- | --- | --- | --- |
| 1 | `RETRY_DISCOVERY`（新增） | 重试发现 | 重新开始一轮 `DiscoveryLadder` 搜索窗口，保留最近设备缓存 | 不改轮次、通道与探测间隔；不弹 toast |
| 2 | `ENTER_CODE` | 用投屏码连接 | 聚焦现有 `codeInput` 并弹数字键盘 | 不清空已输入内容、不自动提交 |
| 3 | `ENTER_IP` | 手动输 IP | 聚焦现有 `addressInput`（「IP 地址或配对链接」） | 不预填、不提前校验格式 |

- 不使用 `SCAN_QR`：发送端无相机入口（见 2.6），给了也点不动。
- 不新增页面/弹窗：三个动作都在当前页就地生效（UX-01 硬要求）。
- `RETRY_DISCOVERY` 是新枚举值而不是复用 `RETRY_REQUEST`：后者在配对表里语义是「重新请求配对」，复用会让日志与文案语义混淆；现有代码没有对 `Action` 做穷举 `when`，新增值无兼容负担（2026-11-09 检索确认）。

### 3.4 生命周期

| 时点 | 行为 |
| --- | --- |
| 进入页面 / 重置搜索 | `SEARCHING`：状态行显示搜索中，无诊断卡、无按钮 |
| 三轮走完且无命中 | 按 3.2 出现诊断卡；按钮可点 |
| 任一通道命中 | 诊断卡立即消失，列表出现设备；不发「已找到」提示 |
| 组播禁用但广播/直连命中 | 全程静默，不出现诊断卡 |
| 已知设备全部离线 | 搜索结束后出现 `DEVICE_OFFLINE`；卡片仍显示「上次连接 N 分钟前」 |
| 旋转 / 重建 | 诊断由纯函数重算，不依赖保存的字符串；输入框内容不因诊断变化被清空 |

### 3.5 总覆盖与唯一性约束

- 定义域是 `Verdict`（2 个密封子类，`Nothing` 有 3 个 reason）× `EmptyReason`（3 值）的 12 种组合；函数对全部组合必须返回值或 `null`，不允许异常。
- 同一 `(verdict, registry)` 组合只对应一个 Cause；不同 Cause 的 headline 两两不同。
- `Found` 永不为非空 Cause；`SEARCHING` 永不为非空 Cause。

---

## 04 交互细节

1. **位置**：放在列表上方原 `hint` 区域；状态行继续承担「正在搜索 / 正在连接」，两者不重复同一句。
2. **结构**：首行诊断（加粗，正文色）→ 次行下一步（次级色）→ 一行 3 个按钮（等宽）。
3. **不遮挡**：诊断卡不覆盖设备卡片；列表为空时它才成为唯一内容。
4. **焦点**：手机触控无需初始焦点；若运行在 TV/盒子上，3 个按钮按左→右进入焦点链。**不主动抢焦点**——仅当当前页没有任何控件持有焦点时才给「重试发现」，避免用户正在输入投屏码时被拉走。
5. **朗读**：三个按钮各自 `contentDescription` 写「动作 + 结果」，例：「重试发现，重新搜索同一网络里的电视」；诊断首行作为独立文本节点朗读，不把三个按钮拼成一句。
6. **离线保留**：`DEVICE_OFFLINE` 只出现在列表仍有离线卡片时，卡片的「上次连接 N 分钟前 · 可能换网或关机」保持不变，不因诊断卡出现而隐藏。

---

## 05 视觉标准

| 项 | 口径 |
| --- | --- |
| 字号 | 诊断首行用正文级：手机 14sp / TV 24sp（`FocusPolicy.bodySp(isTv)`）；下一步同字号次级色 |
| 颜色 | 首行 `TEXT_PRIMARY`、下一步 `TEXT_SECONDARY`、按钮 `ACCENT`；不新增色值，不把网络问题标成 `DANGER` |
| 触控 | 按钮高度 ≥48dp（`Theme.dp(48)`），三按钮等宽，间距 8dp |
| 资源 | 纯文本 + 平台 Button，不引入位图、不引入外部字体 |
| 对比度 | 沿用 UI-04 的正文 ≥4.5:1；不单靠颜色传达状态 |

---

## 06 测试设计

### 6.1 JVM 单测（新增 `EmptyDiagnosisTest`）

| # | 用例 | 期望 |
| --- | --- | --- |
| 1 | 任一通道命中 | `null` |
| 2 | 组播未中、广播/直连命中（`downgraded=true`） | `null`（静默） |
| 3 | 三轮未走完 | `null`（不下结论） |
| 4 | 走完 + `NONE_FOUND` + AP 隔离 | `DISCOVERY_AP_ISOLATED` |
| 5 | 走完 + `NONE_FOUND` + 双频分离 | `DISCOVERY_DUAL_BAND` |
| 6 | 走完 + `ALL_OFFLINE`（含 AP/双频两种判决） | `DEVICE_OFFLINE` |
| 7 | `HAS_ONLINE` | `null` |
| 8 | 全部 12 种组合 | 不抛异常、返回值属于允许集合 |
| 9 | 3 条诊断 | 动作恰为 `[RETRY_DISCOVERY, ENTER_CODE, ENTER_IP]`，数量 3 |
| 10 | 3 条诊断 | headline 两两不同 |
| 11 | 新增 Cause | 自动通过既有 18 字 / 词汇 / nextStep 不重复三门的 `ErrorNoticeTest` |

### 6.2 模拟器 UI dump

- 制造空列表（关闭接收端或断网）→ 断言出现诊断首行 + 恰好 3 个按钮，文案依次为「重试发现 / 用投屏码连接 / 手动输 IP」。
- 点「用投屏码连接」→ `codeInput` 获得焦点、软键盘可见、Activity 栈深度不变。
- 点「手动输 IP」→ `addressInput` 获得焦点。
- 点「重试发现」→ 状态行回到搜索中；若下一轮仍空，诊断卡保持同一 Cause，不闪烁。

### 6.3 验证缺口（须在评审时指派）

- AP 隔离、双频分离、设备关机三种现场条件本机无法真实构造：由纯逻辑单测覆盖判决输入，真实网络表现需真机 R 级复验。
- TalkBack 朗读与 TV 遥控器焦点顺序属 R 级，模拟器只能断言节点与顺序。

---

## 07 验收判据（对齐 UX-01）

- [ ] 判决→文案→动作映射可单测，且每个判决唯一（6.1 #4-#10）。
- [ ] 模拟器 UI dump 可见恰好 3 个动作，顺序与文案符合 3.3。
- [ ] 全部 headline 通过 `ErrorNotice.MAX_HEADLINE_CHARS`，nextStep 不含 headline，无禁用词。
- [ ] 点击动作就地进入对应输入态，不跳新页面。
- [ ] 设备离线时保留「上次连接」与可能原因（卡片 + `DEVICE_OFFLINE` 并存）。
- [ ] 不改 `DiscoveryLadder` 的轮次、通道、降级与文案判定；不改 `ProbeScanner` 探测策略。
- [ ] 组播禁用但任一通道命中时，空态全程静默。

---

## 08 依赖与实施边界

- **实施单**：T04830（4.1 排障空状态三段式实现）与 T04842（3.1.2 UX-01 空列表自救）；本文是两者的输入，不重复实现。
- **预计改动点（实施时）**：`error/ErrorNotice.kt`（+3 Cause、+1 Action、`actionLabel`）、新增 `ui/EmptyDiagnosis.kt`（纯逻辑）、`ui/SenderActivity.kt`（把 `discoveryNotice: String` 换成结构化诊断渲染）、新增 `EmptyDiagnosisTest`。
- **不改**：`net/DiscoveryLadder.kt`、`net/ProbeScanner.kt`、`DeviceRegistry` 的探测与判定逻辑；不新增权限、不新增页面。
- **约束**：minSdk 19、无 AndroidX、release 仍满足主 PRD §14.1 分级上限；文案只用「设备 / 投屏 / 镜像 / 推送 / 投屏码」，不出现「投射 / 同屏 / 共享」。

---

## 09 评审待决（需产品裁决）

已合并为派生单 **T04881**【AI回写待审核】，以下三项为其裁决输入。

1. **「权限缺失」口径**：专项 PRD UX-01 把它列为发现判决，但现网运行期权限（通知、录音）都不影响发现。建议从 UX-01 的发现判决清单移除，保留在「投屏启动失败」的自解释表里；若要保留，需要说明是哪一项权限、在哪个系统版本上会阻断发现。
2. **发送端「扫码连接」**：主 PRD §7.3 网络行给出「扫码连接」，但发送端没有相机/扫码实现。建议二选一：补发送端扫码入口（新功能，需另立单），或把发送端文案收口为「投屏码 / 手动 IP」。本设计按后者落地。
3. **离线优先级**：搜索结束后「已知设备全离线」优先于「AP 隔离/双频分离」；若产品认为应先说网络，需要调整 3.2 的优先级并同步改单测。

---

## 10 证据命令与结果（2026-11-09）

```text
$ grep -n "enum class Empty\|Verdict.Nothing\|TEXT_AP_ISOLATED\|TEXT_DUAL_BAND" \
    app/src/main/java/io/github/wangnan05563/airmirror/net/DiscoveryLadder.kt
19:    enum class Empty { SEARCHING, AP_ISOLATED, DUAL_BAND }
25:        data class Nothing(val reason: Empty, val notice: String) : Verdict()
91:        const val TEXT_AP_ISOLATED = "当前网络禁止设备互访，可关闭 AP 隔离或改用扫码"
92:        const val TEXT_DUAL_BAND = "手机与电视可能不在同一频段"

$ grep -n "discoveryNotice" app/src/main/java/io/github/wangnan05563/airmirror/ui/SenderActivity.kt
98/99/425/431/517

$ grep -c "扫码\|Camera\|zxing" app/src/main/java/io/github/wangnan05563/airmirror/ui/SenderActivity.kt
0

$ grep -n "android.permission" app/src/main/java/io/github/wangnan05563/airmirror/sender/RuntimePermission.kt
13: POST_NOTIFICATIONS
15: RECORD_AUDIO
```

- 5 条候选首行长度实测：8 / 11 / 5 / 7（均 ≤18）。
- 现有 `ErrorNoticeTest` 已覆盖 headline 长度、nextStep 不重复、词汇表三条硬门；新增 Cause 无需复制这三条用例。
