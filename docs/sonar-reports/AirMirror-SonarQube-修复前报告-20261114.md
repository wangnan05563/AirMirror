# AirMirror SonarQube 代码质量报告（修复前基线）

- 项目：AirMirror（Android / Kotlin，projectKey `AirMirror`）
- 扫描时间：2026-11-14 17:13–17:27（Total 14:22，Kotlin 语义分析 7:37）
- 扫描器：sonar-scanner 8.0.1.6346（Windows x64）
- 服务：SonarQube 26.1.0.118079 @ http://127.0.0.1:9000（状态 UP），Kotlin 插件 3.3.1
- CE 任务：`d525d677-5057-4741-a9f9-996130a0bf60` → SUCCESS
- 原始数据：`before-issues.json`（70 条完整 JSON）、`sonar-scan-before.log`

## 1. 结论摘要

| 指标 | 结果 |
| --- | --- |
| 问题总数 | **70**（全部 Kotlin，29 个文件） |
| 严重级别 | CRITICAL 33 / MAJOR 21 / MINOR 13 / INFO 3 / BLOCKER 0 |
| 问题类型 | CODE_SMELL 65 / BUG 5 / VULNERABILITY 0 |
| 质量门禁 | **OK**（首轮分析的 new code 基线即自身，故未触发新增违规） |
| 新代码重复率 | 0.0% |
| 基线单元测试 | `testDebugUnitTest` 793 项全绿（退出码 0，见 `tests-baseline.log`） |
| 基线编译 | `compileDebugKotlin` 成功（见 `compile-baseline.log`） |

说明：首轮扫描的 70 条问题的 `line` 均为**修复前**文件行号；后续修复引入常量、拆分方法会导致行号漂移，定位以 `textRange` 列偏移 + 当前源码为准。

## 2. 规则分布

| 规则 | 数量 | 级别 | 含义 | 处理策略 |
| --- | --- | --- | --- | --- |
| `kotlin:S3776` | 23 | CRITICAL | 认知复杂度 > 15 | 代码重构（拆方法/提取类） |
| `kotlin:S1192` | 10 | CRITICAL | 重复字符串字面量 | 抽常量 |
| `kotlin:S899` | 5 | MAJOR | 忽略了返回 Boolean 的调用结果 | 消费返回值或显式承担 |
| `kotlin:S1874` | 4 | MAJOR | 使用了已弃用 API（`URL(String)`） | 改 `URI(...).toURL()` |
| `kotlin:S1128` | 4 | MINOR | 未使用的 import | 删除 |
| `kotlin:S6516` | 4 | MAJOR | 显式实现函数式接口 | 改为 `Runnable { ... }` lambda |
| `kotlin:S6518` | 4 | MAJOR | 数组/缓冲区访问方式告警 | 误报 → NOSONAR（理由见 §4） |
| `kotlin:S1133` | 3 | MINOR | 弃用方法覆写 | 误报 → NOSONAR（平台回调必须覆写） |
| `kotlin:S1066` | 2 | MAJOR | 可合并的嵌套 if | 合并条件 |
| `kotlin:S6510` | 2 | MINOR | `when` 各分支都 `return` | 改为 `return when` |
| `kotlin:S6615` | 2 | MINOR | 无用的初始化赋值 | 去掉初值 |
| `kotlin:S6514` | 2 | MAJOR | 可委托给父类型的方法实现 | `Closeable by ...` 委托 |
| `kotlin:S6519` | 1 | MINOR | `!x.equals(y)` 写法 | 改 `x != y` |
| `kotlin:S4144` | 1 | MAJOR | 与另一方法实现完全相同 | 委托调用 |
| `kotlin:S107` | 1 | MAJOR | 函数参数过多（>7） | 参数对象化 |
| `kotlin:S6619` | 1 | MAJOR | 可空 Boolean/Int 的冗余判断 | 误报 → NOSONAR（`toIntOrNull()` 返回 `Int?`） |
| `kotlin:S6517` | 1 | MINOR | 单方法接口未声明为 `fun interface` | 声明 `fun interface` |

## 3. 文件分布（Top）

| 文件 | 问题数 |
| --- | --- |
| `dlna/Upnp.kt` | 8 |
| `ui/SenderActivity.kt` | 8 |
| `dlna/DlnaServer.kt` | 5 |
| `receiver/ReceiverSession.kt` | 5 |
| `sender/CaptureService.kt` | 5 |
| `crossnet/UdpTunnel.kt` | 4 |
| `ui/ReceiverActivity.kt` | 4 |
| `media/VideoEncoder.kt` | 3 |
| `update/Update.kt` | 3 |
| 其余 20 个文件 | 各 1–2 |

完整逐条清单：`before-issues.json`。

## 4. 判定为误报的问题（不修代码，加 NOSONAR 并注明理由）

| 规则 | 位置 | 理由 |
| --- | --- | --- |
| `S6518` ×4 | `AudioEncoder`、`AudioOut`、`VideoEncoder`（2 处） | `ByteBuffer.get(byte[], int, int)` 是**批量拷贝**重载，不是“索引访问”，规则把它误判为常量索引；改为索引循环反而破坏批量语义与性能 |
| `S1133` ×3 | `ReceiverActivity.onBackPressed`、`SenderActivity.onRequestPermissionsResult / onActivityResult` | Android 平台回调必须覆写父类方法才能生效；项目无 AndroidX，不能换成 `OnBackPressedDispatcher` / ActivityResult API。`onBackPressed` 的弃用是平台演进，功能仍必须实现 |
| `S6619` ×1 | `SenderActivity` 端口解析行 | `toIntOrNull()` 返回 `Int?`，后续 `?:` 兜底是必要判空，规则误判为“总是非空” |

以上 8 处均按“注释与目标行同一行、保留换行”的规则放置 `NOSONAR`，未改动任何业务逻辑。

## 5. 修复顺序与门禁策略

按严重级别执行：CRITICAL（33）→ MAJOR（21）→ MINOR（13）→ INFO（3）；同一文件内串行修改，避免编辑冲突；每批修改后立即编译校验，编译通过才进入下一批。

约束：
- 修复不得引入新规则违规（复扫以 0 为验收线）；
- 测试执行前必须已完成问题清零与编译通过；
- 构建产物必须来自修复后的代码。

## 6. 环境备注

- 默认 `JAVA_HOME` 指向 JDK 25，超出项目允许的 17–22；本次所有 Gradle/scanner 调用显式设置 `JAVA_HOME=D:\code\Java\zulu17.52.17-ca-jdk17.0.12-win_x64`。
- Android SDK：`E:\Android\sdk`。
- `scripts/android-tools.config.json` 的 JDK 候选路径已为 `win_x64`，与实际安装目录一致，无需修改。
