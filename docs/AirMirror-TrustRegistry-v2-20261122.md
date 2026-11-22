# AirMirror TrustRegistry v2 结构与 30 天信任有效期（T06112）

| 项 | 内容 |
| --- | --- |
| 任务 | T06112《升级 TrustRegistry 结构》 |
| 关联需求 | REQ-005（三种授权模式、撤销即时生效、30 天未用重新确认）、REQ-022（信任数据 schema 可迁移、可清空、迁移失败回退每次确认） |
| 验收项 | AC-05（30 天未用重新确认；PIN 锁定跨重启；撤销信任立即断开）中可由本层验证的部分 |
| 日期 | 2026-11-22 |
| 代码基线 | `TrustRegistry.kt`、`Pairing.kt`、`TrustRegistryTest.kt`、`PairingMigrationTest.kt` |

## 01 基线问题

升级前 `TrustRegistry` 已有 `TrustedDevice.lastUsedAt`，但存在三处缺口：

1. `decide()` 只要记录存在就返回 `TRUSTED`，从不检查最后使用时间；“30 天未用重新确认”没有落地。
2. 落盘没有版本头，旧行、半截行、未知格式无法区分；迁移失败时缺少明确回退语义。
3. `restore()` 只做合并，遇到不可解释的 schema 仍可能保留“看起来像可信设备”的记录，安全性不足。

本任务不改变二维码/投屏码的一次性授权语义，也不实现 TV 信任列表 UI（T06113）或 PIN 锁定（T06114）。

## 02 v2 数据模型

### 内存记录

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `fingerprint` | String | 设备指纹，可信列表主键；不存令牌 |
| `name` | String | 设备名，仅本地展示 |
| `lastUsedAt` | Long | 最后一次**真正建立投屏会话**的本地毫秒时间 |

上限仍为 32 台；超出时淘汰 `lastUsedAt` 最早的记录。`allow(..., permanent=true)` 写入并刷新时间；一次性凭据仍不写入可信列表。

### v2 落盘格式

```text
# AirMirror TrustRegistry v2
<escaped-fingerprint>\t<escaped-name>\t<lastUsedAt>
```

- 首行是固定版本头 `# AirMirror TrustRegistry v2`。
- 第二行起每行三列；指纹、设备名沿用 `PairingLink.escape`，防止 tab/换行破坏行结构。
- 空列表也写版本头（表示“可信列表为空”），不是空字符串。
- 旧 v1 格式为无版本头的三列行，读取时自动识别。

## 03 30 天有效期

| 规则 | 实现 |
| --- | --- |
| TTL | 默认 `30 * 24h = 2,592,000,000ms`，由 `TrustRegistry(expiryMillis=...)` 可替换用于测试 |
| 过期判定 | `now >= lastUsedAt && now - lastUsedAt >= expiryMillis` |
| 过期动作 | 从内存记录移除；若该设备正是活动会话，同时释放会话；`decide()` 返回 `ASK` |
| 刷新时机 | 只在 `acquireSession()` 成功、真正开始投屏会话时刷新 `lastUsedAt`；`decide()`、打开列表、仅扫码探测都不算使用，不自动续期 |
| 边界 | 第 30 天整点即过期；时钟回拨（`now < lastUsedAt`）不判过期，避免设备时间异常导致误清 |
| 落盘 | `snapshot()` 先 `prune()`；过期记录不会写回，不“复活” |
| 关闭 TTL | `expiryMillis <= 0` 表示不启用过期，只用于测试/诊断，不进入产品默认配置 |

## 04 迁移与失败回退

`TrustRegistry.restore(text)` 返回四态：

| 输入 | 结果 | 行为 |
| --- | --- | --- |
| 空白/首次启动 | `EMPTY` | 不写回；可信列表为空 |
| 无版本头，至少一条有效三列行 | `MIGRATED` | 旧 v1 迁移；坏行丢弃、有效行保留，按最近使用排序后合并 |
| 合法 v2 版本头 | `RESTORED` | 以整份快照替换内存列表；v2 是完整状态，不保留旧行的“半截合并”语义 |
| v2 版本头 + 任一坏行 | `FAILED` | 整份快照不采信，清空 |
| 无版本头 + 全部坏行 | `FAILED` | 视为迁移失败，清空 |
| 未知 `#` 版本头 | `FAILED` | 不猜测 schema，清空 |

`FAILED` 的统一动作：

1. 清空可信记录、拒绝静默表、活动会话；
2. `Pairing` 启动时把 `trust.snapshot()`（即仅版本头）写回，避免每次启动重复解析坏文件；
3. 后续所有无有效凭据的设备一律走 `AuthGate` 的 `PendingPrompt`，即“每次确认”；有效 6 位码/一次性令牌仍按原一次性路径放行，不写入可信列表。

`Pairing.init` 的写回规则：

| restore 结果 | 是否写回 v2 快照 |
| --- | --- |
| `MIGRATED` | 是，完成 v1→v2 迁移 |
| `FAILED` | 是，落空表并停止重试坏数据 |
| `EMPTY` / `RESTORED` | 否，避免无意义 IO |

提供 `TrustRegistry.clear()` 清空内存状态，并由 `Pairing.clearTrusted()` 同步写入仅版本头的快照，供 T06113 的“清除信任数据”入口直接调用；重启后不会把旧列表读回。

## 05 撤销与并发约束

- `revoke(fingerprint)` 立即删除记录；若该设备正持有活动会话，同时释放，下一次连接必须重新确认。
- `deny(fingerprint)` 维持 10 分钟静默，并释放该设备会话；静默表上限仍为 32，防止随机指纹撑爆。
- 同一时刻只允许一个 `activeSession`；别的设备尝试接管时仍由既有的 `TakeoverRequired` 路径处理。
- 所有读写方法保持 `@Synchronized`，与现有单进程接收端模型一致；不引入跨进程锁或云同步。

## 06 验证

### 已通过的 JVM 单测

```powershell
$env:JAVA_HOME='D:\code\Java\zulu17.52.17-ca-jdk17.0.12-win_x64'
$env:ANDROID_HOME='E:\Android\sdk'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain `
  --tests "io.github.wangnan05563.airmirror.pairing.TrustRegistryTest"
```

结果：`TrustRegistryTest` **21/21 通过**（原有 15 项 + 新增 6 项），0 failure / 0 error。

新增覆盖：

| 用例 | 断言 |
| --- | --- |
| `trustedDeviceExpiresAfterThirtyDaysWithoutUse` | 30 天差 1ms 仍 `TRUSTED`，整 30 天变 `ASK`，列表为空 |
| `actuallyUsingATrustedDeviceRefreshesItsThirtyDayWindow` | 第 20 天真实使用后，第 45 天仍可信；第 50 天过期 |
| `snapshotDropsExpiredDevicesInsteadOfResurrectingThem` | 过期后快照只剩版本头，不写回旧记录 |
| `legacySnapshotMigratesToV2AndKeepsTrust` | 无版本头旧行返回 `MIGRATED`，快照带 v2 头，设备仍可信 |
| `unknownSchemaVersionClearsTrustAndFallsBackToAsk` | 未知版本返回 `FAILED`，列表清空，`decide=ASK`，落盘只剩 v2 头 |
| `malformedV2SnapshotAlsoFallsBackToAsk` | v2 坏行整体回退，不保留半张表 |

### Pairing 启动迁移

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain `
  --tests "io.github.wangnan05563.airmirror.pairing.PairingMigrationTest"
```

覆盖：

- v1 快照经 `Pairing` 启动后写回 v2 头，设备仍可信；
- 未知版本启动后列表为空、`decide=ASK`，落盘只剩 v2 头，不重复解析坏文件；
- `clearTrusted()` 清空内存列表并写回仅版本头的快照。

> 该用例使用 Robolectric + `ApplicationProvider`，验证 `DeviceStore` 的真实 SharedPreferences 路径，不只测内存对象。

### 全量 Android 单测

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain
```

结果：**103 个测试类、788 项测试，0 failure / 0 error / 0 skipped**。`AuthGateTest` 15 项、`PairingLinkTest` 7 项、`CastCodeTest` 20 项均保持通过；新增的 `PairingMigrationTest` 3 项、`TrustRegistryTest` 21 项一并计入。

### 未覆盖/需在真机验证

- TV 设置页的信任列表、30 天提示文案与“清除信任数据”按钮属 T06113，本轮没有 UI。
- PIN 模式与 5 次锁定跨重启属 T06114，本轮未改 `CastCode`。
- 真实电视断电重启、系统时间被修改、30 天边界实测未做；JVM 时钟注入只证明判定逻辑。
- 生产旧版本用户的实际 `airmirror-pairing` SharedPreferences 迁移未在真机采样；只覆盖了同一 schema 的字符串迁移。

## 07 文件与下一步

| 文件 | 变更 |
| --- | --- |
| `app/src/main/java/io/github/wangnan05563/airmirror/pairing/TrustRegistry.kt` | v2 版本头、30 天 TTL、四态 restore、失败清空、`clear()` |
| `app/src/main/java/io/github/wangnan05563/airmirror/pairing/Pairing.kt` | `MIGRATED`/`FAILED` 启动写回 v2；`clearTrusted()` 内存与落盘同步清空 |
| `app/src/test/java/io/github/wangnan05563/airmirror/pairing/TrustRegistryTest.kt` | 21 项单测 |
| `app/src/test/java/io/github/wangnan05563/airmirror/pairing/PairingMigrationTest.kt` | 3 项迁移/清空落盘测试 |

下一步 T06113 在设置页暴露可信设备列表（名称、指纹、最后使用时间）、撤销/改名/清空，并接入 `clear()`；T06114 在 `CastCode` 上补 PIN 锁定持久化。两者都不得绕过本层的 `TRUSTED/ASK` 判定。
