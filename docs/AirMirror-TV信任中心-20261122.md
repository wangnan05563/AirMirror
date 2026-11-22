# AirMirror TV 信任中心与三档访问模式 UI（T06113）

| 项 | 内容 |
| --- | --- |
| 任务 | T06113《实现 TV 信任 UI》 |
| 关联需求 | REQ-005（三种授权模式、撤销即时生效、30 天未用重新确认）、REQ-022（信任数据 schema 可迁移、可清空、迁移失败回退每次确认） |
| 验收项 | REQ-022（UI 复用 T06112 的 v2 schema）；AC-05 中“撤销信任立即断开”可在本 UI 触发的部分 |
| 日期 | 2026-11-22 |
| 代码基线 | `ReceiverActivity.kt`、`AccessMode.kt`、`DeviceStore.kt`、`Pairing.kt`、`TrustRegistry.kt`、`TrustUiPolicy.kt`、`A11yCopy.kt` |
| 截图证据 | `build/trust-settings-20261122.png`（Robolectric 1920×1080 TV 比例） |

## 01 基线问题

T06112 完成后，可信记录已经有 30 天 TTL 与 v2 落盘，但电视端仍有三处缺口：

1. 设置页只能看到显示、画质、隐私分组，**没有任何信任设备入口**；`TrustRegistry.revoke()` 只能从代码调用。
2. 没有“谁能上屏”的档位选择；PRD 要求“每次确认 / PIN / 仅信任设备”三档，密码模式不进 v2.0。
3. `Pairing.clearTrusted()` 只有代码路径，用户无法在 TV 上清空信任数据；同时“清除日志”明确保留已授权设备，两者不能混用一句话。

本任务只做 **TV 信任 UI + 模式选择持久化 + 改名/撤销/清空闭环**。PIN 的输入与 5 次锁定属 T06114；真实电视遥控器输入、断电重启、30 天边界属 T06115。

## 02 设置页结构

设置页仍是全代码绘制，无 layout XML。新增“访问控制”分组插在“画质”与“隐私”之间：

| 顺序 | 内容 |
| --- | --- |
| 1 | 访问控制说明：新设备如何获得投屏权限，已信任设备 30 天免确认 |
| 2 | 三档模式行：每次确认 / PIN / 仅信任设备，单选实心点 + 强调色 |
| 3 | 信任列表标题 + 汇总（`已信任 N 台` 或空态） |
| 4 | 每台设备一张圆角行：设备名、指纹头尾、上次使用、正在投屏标记 |
| 5 | “清除全部信任设备（N 台）”，列表为空时 GONE |
| 6 | 隐私分组（保持原有日志/缓存清理） |

设备行整行可点，进入详情弹窗：

- 详情：完整设备名、完整指纹、相对时间、可信状态与“正在投屏”。
- “改名”：单行输入，去首尾空白，1–24 字；为空或超长就地报错，不写半截状态。
- “撤销”：二次确认；若该设备正在投屏，先撤销并立即结束会话。
- “关闭”：仅关闭详情，不改状态。

## 03 三档访问模式

`AccessMode` 是与 UI 解耦的枚举，落盘键 `access_mode`；认不出的值回落到 `CONFIRM`，不静默提升权限。

| 模式 | 产品语义 | 本轮落地 |
| --- | --- | --- |
| `CONFIRM` 每次确认 | 新设备首次连接由电视确认；已信任设备 30 天内免确认 | 默认值；与既有 `AuthGate` 空凭据 `PendingPrompt` 路径一致 |
| `PIN` | 新设备输入电视上的 4 位 PIN；已信任设备仍免确认 | 只持久化选择；4 位 PIN、错误 5 次锁 60 秒、跨重启保留由 T06114 接入 |
| `TRUSTED_ONLY` 仅信任设备 | 只允许信任列表内设备，其他连接拒绝 | 只持久化选择；判定接入与 PIN 一并由 T06114 完成，避免本轮出现“开关只对一档生效”的半成品 |

> 边界：T06113 不修改 `AuthGate` 的授权判定。这样既不会让 PIN 档在未实现输入时误放行，也不会把“仅信任设备”做成只拦截一种凭据路径的假安全。三档的**行为验收**在 T06114/T06115 完成；本轮通过的是 UI、持久化与信任管理闭环。

## 04 撤销与清空

T06112 的 `revoke/clear` 只清内存，T06113 补上“正在投屏”反馈与落盘：

| 操作 | `TrustRegistry` | `Pairing` | UI |
| --- | --- | --- | --- |
| 改名 | `rename()` 只改 `name`，不刷新 `lastUsedAt` | `renameTrusted()` 限 1–24 字并立即 `persistTrusted()` | 详情 → 改名弹窗 |
| 撤销 | `revoke()` 返回“撤销时是否持有活动会话” | `revokeTrusted()` 立即落盘并透传该布尔 | 若 true，调用 `host.endActiveSession()`，提示“已撤销并断开投屏” |
| 清空 | `clear()` 只把“原本属于信任列表的活动会话”计为 true | `clearTrusted()` 落盘并透传 | 二次确认；仅受信活动会话被断，一次性访客会话不被误杀 |

“清除日志与缓存”仍保留原语义：只删 diagnostics，**已授权设备保留**；两处入口文案与二次确认分别写明，避免用户误清信任。

## 05 展示口径

`TrustUiPolicy` 是无 Android 依赖的纯逻辑层：

| 规则 | 口径 |
| --- | --- |
| 相对时间 | 60s 内“刚刚”；60min 内“N 分钟前”；24h 内“N 小时前”；否则“N 天前” |
| 时钟回拨 | `now - lastUsedAt` 负值按 0 处理，显示“刚刚”，不出现负数 |
| 0 时间 | “尚未使用” |
| 指纹 | 列表为前 8 + `…` + 后 4；详情给完整指纹 |
| 列表过滤 | 直接读 `TrustRegistry.trusted()`，过期项不出现；打开设置前 `prune()` 清理过期活动会话 |
| 排序 | 由 `TrustRegistry` 按 `lastUsedAt` 倒序 |
| 空态 | “尚无信任设备 · 新设备连接需先通过访问控制” |

无障碍文案全部走 `A11yCopy`：模式行上报 `isCheckable/isChecked`；设备行描述包含名字、上次使用、是否正在投屏与“双击查看详情”；清空与撤销动作分别有独立描述。所有新增可点行均补 `UiKit.touchTarget()` 48dp 基线，设置页仍由 `TvFocus.applyTree()` 统一接焦点。

## 06 验证

### 定向单测

```powershell
$env:JAVA_HOME='D:\code\Java\zulu17.52.17-ca-jdk17.0.12-win_x64'
$env:ANDROID_HOME='E:\Android\sdk'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain `
  --tests "io.github.wangnan05563.airmirror.ui.TrustSettingsUiTest" `
  --tests "io.github.wangnan05563.airmirror.ui.TrustUiPolicyTest" `
  --tests "io.github.wangnan05563.airmirror.pairing.AccessModeTest" `
  --tests "io.github.wangnan05563.airmirror.pairing.TrustRegistryTest" `
  --tests "io.github.wangnan05563.airmirror.pairing.PairingMigrationTest" `
  --tests "io.github.wangnan05563.airmirror.ui.A11yCopyTest"
```

结果：**69/69 通过**，0 failure / 0 error。覆盖：

- `TrustSettingsUiTest` 4 项：三档切换并落盘；列表 → 详情 → 改名 → 撤销 → 清空；清空后无幽灵行；TV 比例截图。
- `TrustUiPolicyTest` 7 项：相对时间边界、时钟回拨、指纹缩写、空态/有数据、行文案、完整指纹、名字 1–24 字。
- `AccessModeTest` 2 项：非法/缺失回落 `CONFIRM`；`DeviceStore` 默认与跨实例持久化。
- `TrustRegistryTest` 26 项：原 21 项 + 改名不续期、未知/空名拒绝、撤销活动会话返回 true、`clear()` 只统计受信活动会话 2 项。
- `PairingMigrationTest` 5 项：原 3 项 + 改名/撤销立即落盘、活动会话撤销返回 true。
- `A11yCopyTest`：新增 8 条信任/访问模式文案进 40 字与禁用词检查。

### 全量 Android 单测

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain
```

结果：**106 个测试类、808 项测试，0 failure / 0 error / 0 skipped**。T06112 的 788 项基线保持通过，新增 20 项。另修复 `BrowserCastServer.stop()` 在 `synchronizedSet` 上裸迭代导致的偶发 `ConcurrentModificationException`（全量回归暴露，非 T06113 功能变更）。

### 截图证据

`build/trust-settings-20261122.png`：1920×1080、`w960dp-h540dp-320dpi` 的 TV 比例，滚动到访问控制分组，含三档模式、2 台信任设备（其中 1 台“正在投屏”）与清空入口。

### 未覆盖 / 需真机验证

- 真实电视 D-pad 逐行遍历、TalkBack 朗读顺序、遥控器软键盘改名：Robolectric 只能证明可点与文案，不能替代 3 米实测。
- PIN 档行为、4 位 PIN 输入、错误 5 次锁 60 秒跨重启：T06114。
- `TRUSTED_ONLY` 对未知设备/6 位码/扫码令牌的最终拒绝路径：T06114 接入后需回归；本轮不声称已拦截。
- 撤销正在投屏的设备“1 秒内停止”：已接 `host.endActiveSession()`，需在 T06115 真机测端到端时延。
- 真实断电重启后的列表/模式持久化、系统时间篡改下的 30 天边界：T06115。

## 07 文件与下一步

| 文件 | 变更 |
| --- | --- |
| `app/src/main/java/.../pairing/AccessMode.kt` | 新增三档枚举、标签/提示、非法值回落 |
| `app/src/main/java/.../pairing/DeviceStore.kt` | 新增 `access_mode` 读写，默认 `CONFIRM` |
| `app/src/main/java/.../pairing/TrustRegistry.kt` | `rename()`；`revoke()/clear()` 返回活动受信会话布尔 |
| `app/src/main/java/.../pairing/Pairing.kt` | `renameTrusted()`、`revokeTrusted()`、`clearTrusted()` 落盘与 24 字上限 |
| `app/src/main/java/.../ui/TrustUiPolicy.kt` | 新增相对时间、指纹缩写、行/详情/空态文案纯逻辑 |
| `app/src/main/java/.../ui/ReceiverActivity.kt` | 访问控制/信任列表分组、改名/撤销/详情/清空弹窗、活动会话断开 |
| `app/src/main/java/.../ui/A11yCopy.kt` | 8 条访问模式/信任设备无障碍文案 |
| `app/src/main/java/.../web/BrowserCastServer.kt` | `stop()` 对 `synchronizedSet` 持锁迭代，修偶发并发异常 |
| `app/src/test/java/.../ui/TrustSettingsUiTest.kt` | 4 项 Robolectric UI/落盘/截图测试 + 单例 host 隔离 |
| `app/src/test/java/.../ui/TrustUiPolicyTest.kt` | 7 项纯逻辑测试 |
| `app/src/test/java/.../pairing/AccessModeTest.kt` | 2 项模式/落盘测试 |
| `app/src/test/java/.../pairing/TrustRegistryTest.kt` | 新增 5 项改名/撤销/清空测试 |
| `app/src/test/java/.../pairing/PairingMigrationTest.kt` | 新增 2 项立即落盘/活动撤销测试 |

下一步 **T06114**：在 `Pairing/AuthGate` 读取 `accessMode()`，实现 4 位 PIN、错误 5 次锁 60 秒、锁定状态跨重启与 IP 掩码；随后由 T06115 做真机 M2 联调。
