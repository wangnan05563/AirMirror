# AirMirror SonarQube 代码质量报告（修复后复扫）

- 项目：AirMirror（Android / Kotlin，projectKey `AirMirror`）
- 复扫时间：2026-11-14 18:36–18:39（Total 5:24，增量分析 2:59）
- 扫描器：sonar-scanner 8.0.1.6346（Windows x64），SonarQube 26.1.0.118079，Kotlin 插件 3.3.1
- CE 任务：`cf3792bc-9d42-4040-9770-07da4ce9f9b9` → **SUCCESS**
- 日志：`sonar-scan-after.log`

## 1. 结论

| 指标 | 修复前 | 修复后 |
| --- | --- | --- |
| 问题总数 | 70 | **0** |
| BLOCKER | 0 | 0 |
| CRITICAL | 33 | 0 |
| MAJOR | 21 | 0 |
| MINOR | 13 | 0 |
| INFO | 3 | 0 |
| CODE_SMELL | 65 | 0 |
| BUG | 5 | 0 |
| VULNERABILITY | 0 | 0 |
| 重复行密度 | 0.0% | 0.0% |
| 技术债（sqale_index） | — | 0 分钟 |

复扫查询（`/api/issues/search?componentKeys=AirMirror&resolved=false`）返回 `total = 0`；项目度量中 `code_smells=0`、`bugs=0`、`vulnerabilities=0`。

## 2. 质量门禁

- 门禁状态：**ERROR**（唯一未满足项为 `new_coverage ≥ 80%`，实测 0.0%）
- 其余条件：`new_violations = 0`（OK）、`new_duplicated_lines_density = 0.0`（OK）
- 原因说明：项目未接入 JaCoCo，也未向 Sonar 提供覆盖率报告（`sonar-project.properties` 无 `sonar.coverage.jacoco.xmlReportPaths`），因此覆盖率恒为 0；这与代码质量本身无关。首轮扫描时门禁为 OK，是因为首次分析的 “new code” 基线尚未产生新增代码。
- 复扫后 `new_violations` 已归零，门禁的“新增违规”条件已满足。

## 3. 规模与复杂度

| 指标 | 数值 |
| --- | --- |
| ncloc | 14,359 |
| 圈复杂度合计 | 3,183 |
| 认知复杂度合计 | 2,260 |
| 代码重复 | 0.0% |

## 4. 复扫后的二次收敛（重要）

首轮修复后第一次复扫（CE `9c7c4afb-97a3-47ca-ba0a-02e9e5894661`）残留 8 条：7 条 S3776（认知复杂度 16–22，重构后新方法仍略超阈值）+ 1 条 S1172（重构后新方法存在未用参数）。已做第二轮收口：

| 残留位置 | 处理 |
| --- | --- |
| `Upnp.findClose` / `childElements` | 抽 `scanTag(xml, from): Tag?` 统一“跳过注释/声明 → 找 `>`”的扫描 |
| `CaptureService.runAudio` | 引入 `AudioLoop` 状态类 + `noteSilence(loop)` |
| `CaptureService.recover` | 成功分支整体抽 `onResumed(result, host, port)` |
| `CaptureService.watchPeer` | 抽 `readPeerLoop(conn)` |
| `ReceiverActivity.refreshQualityRows` | 抽 `renderQualityRow(tier, row)` |
| `SenderActivity.checkUpdate` | 抽 `applyUpdateResult(result, silent, version)` / `deviceAbi()` |
| `SenderActivity.addSubtitle` | 删除未使用的 `row` 参数 |

最终第 4 次扫描为 0。

## 5. 测试结果

| 项 | 结果 |
| --- | --- |
| debug 单元测试 | **793 项全部通过**（`tests-final.log`） |
| release 单元测试（构建脚本内） | 全部通过（`build-release.log`） |
| 集成测试 | 包含在 793 项内（`ReferenceServerIntegrationTest`、`BrowserCastServerTest`、DLNA/协议/信令等），最终全绿 |
| 端到端测试 | 项目无 androidTest 目录（无仪器化/端到端用例），以单元+集成测试为验收口径 |

测试期间两次偶发失败（重跑即过，与本次修改无关，详见修复记录 §4）：裸 socket 测试的 Windows RST、外部参考信令服务启动超时（与扫描并行导致高负载）。

## 6. 生产构建

`scripts\build.ps1 -Release` 成功（JDK 17、`E:\Android\sdk`、offline）：

| 产物 | 大小 |
| --- | --- |
| `release/release/AirMirror-arm64-v8a-release-unsigned.apk` | 1,218,917 B |
| `release/release/AirMirror-armeabi-v7a-release-unsigned.apk` | 1,218,917 B |
| `release/release/AirMirror-universal-release-unsigned.apk` | 1,218,917 B |

说明：未提供 keystore，按项目既有口径输出未签名 release 包；如需签名包，用 `scripts\build.ps1 -Release -KeystoreFile ... -KeystorePassword ... -KeyAlias ...`。

## 7. 交付物

| 文件 | 内容 |
| --- | --- |
| `AirMirror-SonarQube-修复前报告-20261114.md` | 70 条问题的分布、策略、误报判定 |
| `AirMirror-SonarQube-问题修复记录-20261114.md` | 逐规则修复方式与落点、编译/测试记录 |
| `before-issues.json` | 修复前 70 条原始 JSON |
| `sonar-scan-before.log` / `sonar-scan-after.log` | 扫描日志 |
| `tests-baseline.log` / `tests-final.log` / `build-release.log` | 基线测试、最终测试、生产构建日志 |
| `compile-baseline.log` / `compile-fixes-*.log` | 编译过程与两次失败定位 |
