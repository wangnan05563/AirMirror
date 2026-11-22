# AirMirror 跨端联调缺陷修复与回归证据（T06122 / REQ-001、REQ-013）

| 项 | 内容 |
| --- | --- |
| 任务 | T06122 [PRD] 修复跨端联调缺陷 |
| 需求 | REQ-001 三端互投闭环；REQ-013 飞行模式/AP 隔离时明确提示原因，给打开设置/重试按钮，不展示技术堆栈 |
| 基线 | HEAD `1ed55fc`（2026-11-22），含 DLNA `URLBase` 动态修复、AV 音画偏差提示收敛、发现重试修复 |
| 产物性质 | 缺陷修复包 + 回归证据；Android 单测与 PC 握手测试已执行，物理路由器 AP 隔离待发布回归单复核 |

## 01 结论先行

1. Android 发送端的 AP 隔离、双频分离、离线接收端三条路径已收敛到 **一个判决入口**：`DiscoveryLadder` → `EmptyDiagnosis` → `ErrorNotice`。界面不再各自拼中文或抛异常。
2. 离线接收端优先于网络判决：已知设备全离线时显示“设备已离线”，不会误报 AP 隔离；AP 隔离时显示“网络禁止设备互访”，并给出“重试发现 / 用投屏码 / 手动 IP”三个动作。
3. 所有用户可见错误均来自 `ErrorNotice`，首行 ≤18 字、第二行给下一步；不出现异常类名、堆栈、裸端口。
4. PC 端协议握手测试 `PcConnectionTest` 通过：HELLO→HELLO_ACK→AUTH→AUTH_RESULT，握手后 `secure=true`。
5. 本轮执行 Android 定向回归 **30 项通过 / 0 失败**；PC 客户端 **7 项通过 / 0 失败**。
6. 尚未做物理路由器 AP 隔离实测（需关闭/开启路由器隔离功能），列入 T06129 发布回归矩阵，不在本单伪造“真机通过”。

## 02 缺陷与修复映射

| 现象 | 根因 | 修复/现状 | 代码落点 |
| --- | --- | --- | --- |
| 搜索结束仍显示“正在搜索” | 判决未区分 SEARCHING 与已耗尽 | `DiscoveryLadder.exhausted()` 三轮后才下结论 | `net/DiscoveryLadder.kt` |
| 无设备时只说“搜不到” | 空列表没有结构化原因 | `EmptyDiagnosis.of()` 输出唯一 Cause | `ui/EmptyDiagnosis.kt` |
| AP 隔离与双频分离混用 | 只看“没搜到”，未看缓存网段 | `cachedCrossSubnet` → DUAL_BAND，否则 AP_ISOLATED | `net/DiscoveryLadder.kt` |
| 已知设备全离线却提示网络问题 | 注册表原因未参与判决 | `DeviceRegistry.EmptyReason.ALL_OFFLINE` → `DEVICE_OFFLINE` | `ui/EmptyDiagnosis.kt` |
| 错误提示只有一句“连接失败” | 文案散落、无下一步 | 30+ Cause 统一表，首行 + 下一步 + 动作 | `error/ErrorNotice.kt` |
| 技术堆栈泄漏到界面 | 异常直接 `toString()` 上屏 | 界面只消费 `ErrorNotice`，日志区保留技术细节 | `ui/SenderActivity.kt` / `ReceiverActivity.kt` |
| DLNA 描述文件公告旧网段 | `URLBase` 写死 | 启动时按当前网卡地址动态生成 | `dlna/`（见 DLNA 报告） |
| 音画偏差反复提示 | 每次对齐都弹同一条 | 提示去重/状态条收敛；持续超阈值才提示兼容模式 | `ui/` + `media/`（见 AV 修复记录） |
| PC 握手异常回退不明确 | 只抛异常，无用户提示 | PC 侧握手测试覆盖；UI 提示待 T06108/T06111 接入 | `pc-client/PcConnection.kt` |

## 03 REQ-013 场景验收

| 场景 | 触发 | 期望 | 现状 | 证据 |
| --- | --- | --- | --- | --- |
| 飞行模式 | 手机无网络 | 明确“没有网络”，给打开设置/重试 | `UNREACHABLE` / `NO_DISCOVERY` 文案与动作已定义；真机待回归 | `ErrorNoticeTest` |
| AP 隔离 | 同网段三路无命中 | “网络禁止设备互访” + 重试/投屏码/手动 IP | `DiscoveryLadder.AP_ISOLATED` → `DISCOVERY_AP_ISOLATED` | `EmptyDiagnosisTest` |
| 双频分离 | 缓存地址跨网段 | “不在同一频段” + 同一 Wi-Fi/投屏码 | `DUAL_BAND` 映射 | `EmptyDiagnosisTest` |
| 接收端离线 | 已知设备心跳失败 | “设备已离线” + 重试发现/投屏码/IP | `ALL_OFFLINE` 优先映射 | `EmptyDiagnosisTest` |
| 码错误 | 服务端 WRONG | 2 秒内“投屏码不正确” + 重试/刷新 | `CODE_WRONG` 已定义 | `ErrorNoticeTest` |
| 码过期 | 服务端 EXPIRED | 2 秒内“投屏码已过期” + 刷新/重试 | `CODE_EXPIRED` 已定义 | `ErrorNoticeTest` |
| 大屏拒绝 | 用户点拒绝 | “大屏拒绝了投屏” + 等一分钟/重试 | `REFUSED_BY_USER` 已定义 | `ErrorNoticeTest` |
| 技术堆栈 | 任意异常 | 主提示不出现异常类名/堆栈 | 界面只消费统一文案 | 源码守卫 + 人工检查 |

## 04 已执行验证

### 4.1 Android 定向回归

```powershell
$env:JAVA_HOME='D:\code\Java\zulu17.52.17-ca-jdk17.0.12-win_x64'
.\gradlew.bat --offline :app:testDebugUnitTest `
  --tests 'io.github.wangnan05563.airmirror.ui.EmptyDiagnosisTest' `
  --tests 'io.github.wangnan05563.airmirror.error.ErrorNoticeTest' `
  --tests 'io.github.wangnan05563.airmirror.net.DiscoveryLadderTest' `
  --console=plain
```

结果：**BUILD SUCCESSFUL**，30 项通过 / 0 失败：

| 测试类 | 数量 | 失败 |
| --- | --- | --- |
| `ErrorNoticeTest` | 13 | 0 |
| `DiscoveryLadderTest` | 5 | 0 |
| `EmptyDiagnosisTest` | 12 | 0 |

### 4.2 PC 协议握手

```powershell
$env:JAVA_HOME='D:\code\Java\zulu17.52.17-ca-jdk17.0.12-win_x64'
.\gradlew.bat :pc-client:test --console=plain
```

结果：**BUILD SUCCESSFUL**，7 项通过 / 0 失败：

| 测试类 | 数量 | 失败 |
| --- | --- | --- |
| `AnnexBParserTest` | 2 | 0 |
| `H264VideoEncoderTest` | 2 | 0 |
| `PcConnectionTest` | 1 | 0 |
| `SenderEngineReconnectTest` | 1 | 0 |
| `SenderEngineTest` | 1 | 0 |

说明：首次离线运行因 `org.jcodec:jcodec:0.2.5` 未缓存失败，改为在线补齐后通过；不是代码失败。

## 05 修复包内容

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/.../net/DiscoveryLadder.kt` | 三路发现判决、AP/双频原因 |
| `app/src/main/java/.../ui/EmptyDiagnosis.kt` | 判决 → Cause 唯一映射 |
| `app/src/main/java/.../error/ErrorNotice.kt` | 30+ Cause 文案、动作、18 字硬门 |
| `app/src/test/java/.../ui/EmptyDiagnosisTest.kt` | 12 项 AP/离线/双频/动作回归 |
| `app/src/test/java/.../error/ErrorNoticeTest.kt` | 13 项文案与动作硬门 |
| `app/src/test/java/.../net/DiscoveryLadderTest.kt` | 5 项发现节拍/判决 |
| `pc-client/src/test/java/.../PcConnectionTest.kt` | PC 握手与加密标志 |

## 06 剩余风险

1. **物理 AP 隔离未实测**：单测构造的是判决输入，未在真实路由器上关闭“AP 隔离/客户端隔离”验证。列入 T06129。
2. **PC UI 未接入**：PC 端目前只有握手测试与 Swing 单页，错误提示仍偏技术化；T06108 设计稿已完成，T06111 实施后复验。
3. **接收端离线判定依赖缓存**：首次安装无缓存时只能按网络判决提示；这是产品口径，不是缺陷。
4. **多网卡 PC**：默认路由网卡选择待 T06109/T06111 实现；当前手动 IP 可绕过。

## 07 结论

T06122 的代码级修复与回归证据已闭环：AP 隔离、离线接收端、码错误/过期、PC 握手均有可复核测试。物理路由器 AP 隔离与 PC UI 提示属于后续发布回归/实施项，已在风险中显式登记，不冒充真机通过。
