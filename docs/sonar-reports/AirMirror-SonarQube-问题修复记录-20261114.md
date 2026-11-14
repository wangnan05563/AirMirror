# AirMirror SonarQube 问题修复记录（2026-11-14）

本记录与 `before-issues.json`（70 条）一一对应，说明每条规则的修复方式与落点。复扫结果见《AirMirror-SonarQube-修复后报告-20261114.md》。

## 1. 汇总

| 项目 | 数值 |
| --- | --- |
| 基线问题 | 70 |
| 代码修复 | 62 |
| NOSONAR 抑制（附理由） | 8 |
| 复扫残余 | **0** |
| 修复涉及文件 | 29 个 main + 1 个 test（源码 lint 断言） |
| 编译轮次 | 5 次（前 2 次失败并定位修复，后 3 次通过） |

> 说明：每次编译失败都已定位到具体方法并修正，最终 0 错误；失败日志保留在 `compile-fixes-2.log` 以便追溯。

## 2. 按规则逐条说明

### S3776 认知复杂度（23 条，CRITICAL）——全部代码重构

| 文件 | 处理方式 |
| --- | --- |
| `crossnet/CrossNetConnector.connect` | 拆出 `matchSession(signaling, codeHash)`、`punchAndConnect(...): Outcome?`（null 表示状态机要求重试） |
| `dlna/DlnaServer.avTransport` | 拆成 `transportCommand`（setTransportUri/playTransport/pauseTransport/stopTransport/seekTransport）、`switchTrack`、`transportQuery`（positionInfo/mediaInfo/setPlayMode） |
| `dlna/Upnp` | 新增 `skipNonTag`，供 `elementText`/`findClose`/`childElements` 复用；二次复扫后再抽 `scanTag` 收敛剩余复杂度 |
| `media/AudioEncoder`、`AudioOut` | S6518 误报抑制（见 §3） |
| `media/VideoEncoder` | 拆 `emitConfig(result)` / `emitSample(result, index, info)` |
| `media/H265` | 合并嵌套 if |
| `media/VideoDecoder` | 拆 `onFrameRendered(decoder, index, info)` |
| `dlna/TransportMachine` | `onPlaybackFailed()` 委托 `onStopInvoked()`（同时解决 S4144） |
| `pairing/AuthGate` | 拆 `authorizeByCode` / `admit`，`when` 改 `return when` |
| `perf/LatencyPolicy` | 拆 `degrade(window, steps)` / `recover()` |
| `receiver/ReceiverSession` | 拆 `acceptSocket`（块体，因含 return）、`serveClient`、`acceptHello`、`resetSessionGauges`、`pumpFrames`、`acceptVideoConfig`、`onVideoData`、`feedFrame`、`onDecodeFailure`、`logProgress`、`onAudioData`、`onControl`、`finishSession`、`isDropped`；新增私有类 `FlowStats` |
| `sender/CaptureService` | 拆 `LiveSession`、`resumeAfterGone`、`handleTakeoverAnswer`、`handlePauseChange`、`handleAudioSwitch`、`handleRecoveryQuality`、`handleSyncRequest`、`sendFrames`、`sendVideoFrame`、`logSent`；`runAudio` 拆 `noteAudioGap`/`reanchorAudio`/`sendAudioChunk` 并引入 `AudioLoop` 状态类；`recover` 拆 `relocatedAddress()`（新增 `Address` data class）与 `onResumed`；`watchPeer` 拆 `handlePeerControl`/`handlePeerBye`/`readPeerLoop` |
| `ui/ReceiverActivity` | `refreshSettings` 拆 refreshModeRows/refreshToggleRows/refreshSignalingRow/refreshQualityRows/refreshMaintenanceRows；`refreshWaitingPage` 拆 refreshCodePanel/updateCodeA11y/refreshBrowserRow/refreshPairingQr；复扫后 `refreshQualityRows` 再拆 `renderQualityRow` |
| `ui/SenderActivity` | `renderList` 拆 `deviceCard`/`deviceHeader`/`addSubtitle`/`actionRow`/`actionButton`/`lastConnectedLabel`/`renderEmptyState`；`refresh` 拆 refreshStatus/updateStatusLine/statusStateOf/updateCastingChrome/updateCastingControls；`editCrossNet` 拆 `saveSignaling`；`downloadUpdate` 拆 `reportProgress`/`installUpdate`/`openInstallSettings`；复扫后 `checkUpdate` 拆 `applyUpdateResult`/`deviceAbi` |
| `util/Json.number` | 拆 `skipDigits` / `skipExponent` |

行为保持：所有拆分均为同文件内私有方法/私有类，不改协议、线程模型、错误文案与通知行为；仅 `addSubtitle` 删除了实际未使用的参数。

### S1192 重复字符串（10 条，CRITICAL）——抽常量

| 文件 | 常量 |
| --- | --- |
| `dlna/DlnaServer` | `MIME_XML`（5 处）、`imageRotationTick` 相关复用 |
| `dlna/Upnp` | `XML_DECLARATION`、`STATE_VAR_NO_HEAD`、`STATE_VAR_END` |
| `dlna/Ssdp` | `BOOTID_HEADER` |
| `web/BrowserCastServer` | `MIME_TEXT_PLAIN`（5 处） |
| `protocol/ProtocolBoundary` | 文件级 `private const val DLNA_LABEL` |
| `packaging/AbiPolicy` | `ABI_ARM64` / `ABI_ARMV7` |
| `receiver/ReceiverSession` | `SESSION_BUSY_TEXT` |

### S899 忽略 Boolean 返回值（5 条，MAJOR）

- `crossnet/UdpTunnel`：`await` 结果用 `val` 承载并处理超时；关闭时抛 `IOException`
- `net/Discovery`：`val completed = latch.await(...)` 并对未完成分支给结论
- `receiver/ReceiverSession`：2 处 `sessionDone?.await(...)` 加 `val` + `Log.w`
- `update/ApkDownload`：`target.exists() && !target.delete()` 抛 `IOException`

### S1874 弃用 API（4 条，MAJOR）

`Signaling`、`ImageFetcher`、`ApkDownload`、`Update`：`URL(url)` → `URI(url).toURL()`。

### S1128 未使用 import（4 条，MINOR）

`Theme`（Color/Paint 中已无用的那个）、`DlnaServer`（`android.os.Build`）、`Update`（`JsonValue`/`readAtMost`）、`BrowserCastServer`（`java.util.Locale`）——全部删除。

### S6516 函数式接口显式实现（4 条，MAJOR）

`DlnaServer.failImage`、`ReceiverHost.idleSweep`、`ReceiverActivity.ticker`、`SenderActivity.refresh`：改为 `Runnable { 具体方法() }`，方法体单独抽出。

### S1066 嵌套 if（2 条，MAJOR）

`UdpTunnel`、`H265`：合并条件表达式，保持短路顺序。

### S6510 when 全分支 return（2 条，MINOR）

`UdpTunnel.deliver` → `return when`；`AuthGate` → `return when`。

### S6615 无用初始化（2 条，MINOR）

`Upnp.parseClock`：去掉初值，直接在有值分支返回。

### S6514 可委托实现（2 条，MAJOR）

`net/StreamConnection`：`: Closeable by channel`；其 `TunnelChannel`：`: FrameChannel, Closeable by tunnel`。

### S6519 equals 取反（1 条，MINOR）

`UdpTunnel`：`!packet.address.equals(peer.address)` → `packet.address != peer.address`。

### S4144 相同实现（1 条，MAJOR）

`TransportMachine.onPlaybackFailed()` → 调用 `onStopInvoked()`。

### S107 参数过多（1 条，MAJOR）

`CaptureService`：用 `CaptureGrant(resultCode, data)` data class 收敛参数。

### S6517 单方法接口（1 条，MINOR）

`Update.HttpText` → `fun interface HttpText`。

### 复扫新发现（首轮扫描未报，重构后暴露）

| 规则 | 位置 | 处理 |
| --- | --- | --- |
| S1172 | `SenderActivity.addSubtitle` | 删除未使用的 `row` 参数 |
| S3776 | `Upnp.findClose` / `childElements` | 抽 `scanTag` 统一跳注释与找 `>` |
| S3776 | `CaptureService.runAudio` / `recover` / `watchPeer` | 见上表（`AudioLoop`、`onResumed`、`readPeerLoop`） |
| S3776 | `ReceiverActivity.refreshQualityRows` | 抽 `renderQualityRow` |
| S3776 | `SenderActivity.checkUpdate` | 抽 `applyUpdateResult` / `deviceAbi` |

## 3. NOSONAR 抑制清单（8 条，均有行内理由）

| 规则 | 数量 | 位置 | 理由 |
| --- | --- | --- | --- |
| S6518 | 4 | `AudioEncoder:89`、`AudioOut:102`、`VideoEncoder:98`、`VideoEncoder:151` | `ByteBuffer.get(byte[], int, int)` 是批量拷贝重载，不是索引访问，规则误判 |
| S1133 | 3 | `ReceiverActivity.onBackPressed`、`SenderActivity.onRequestPermissionsResult`、`SenderActivity.onActivityResult` | 平台回调必须覆写父类；项目无 AndroidX，无法替换为 Dispatcher/ActivityResult API |
| S6619 | 1 | `SenderActivity` 端口解析 | `toIntOrNull()` 返回 `Int?`，`?:` 兜底是必要判空 |

## 4. 编译与测试

| 阶段 | 结果 | 日志 |
| --- | --- | --- |
| 基线编译 | 成功 | `compile-baseline.log` |
| 第一批修复编译 | 成功 | `compile-fixes-1.log` |
| 第二批修复编译 | 失败（4 错误：1 处表达式体 `return`、3 处 ByteArray/String 类型不匹配）→ 已修 | `compile-fixes-2.log` |
| 重构后编译 | 失败（1 错误：`addSubtitle` 内 `addView` 作用域）→ 已修 | `compile-fixes-3.log` |
| 收尾编译 | 成功 | `compile-fixes-4.log`、`compile-fixes-5.log` |
| 全量单测（debug） | 793 项全绿 | `tests-final.log` |
| 全量单测（release，构建脚本内） | 全绿 | `build-release.log` |

测试过程中出现的两次偶发失败（非本次修改引入，重跑即绿）：
1. `BrowserCastServerTest > 超过上限直接拒绝 不产生文件`：裸 socket 客户端在服务端 413 后立即关闭连接时收到 Windows RST；
2. `ReferenceServerIntegrationTest`：外部参考信令服务在 20 秒内未就绪（端口 64961），发生在与 Sonar 扫描并行、机器负载高时。

## 5. 构建

`scripts\build.ps1 -Release`（JDK 17 / SDK `E:\Android\sdk`）成功，产物：

| 文件 | 大小 |
| --- | --- |
| `release/release/AirMirror-arm64-v8a-release-unsigned.apk` | 1,218,917 B |
| `release/release/AirMirror-armeabi-v7a-release-unsigned.apk` | 1,218,917 B |
| `release/release/AirMirror-universal-release-unsigned.apk` | 1,218,917 B |

未传签名参数，按项目既有口径（`app/build.gradle.kts` 注释：未传签名参数时产物为未签名包）输出 `-unsigned` 包。
