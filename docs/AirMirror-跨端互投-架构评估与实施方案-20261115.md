# AirMirror 跨端互投（PC + HarmonyOS）架构评估与实施方案

- 任务：**T05757 AirMirror 新增 PC 及鸿蒙客户端实现多端互投**
- 日期：2026-11-15
- 结论：**当前仓库只有 Android 客户端，PC / HarmonyOS 均为从零新增模块；本环境无法在单轮交付可执行安装包。建议按「先冻结协议 → Windows PC 发送端 → Windows PC 接收端 → HarmonyOS PoC → 鸿蒙 MVP → 多端集成测试」分阶段实施。**
- 本轮交付：架构评估、协议事实盘点、可行性矩阵、WBS 与风险清单；**不包含 PC/鸿蒙客户端源码或安装包**。

---

## 1. 结论摘要

| 问题 | 结论 |
|---|---|
| 现有架构能否直接支持 PC / 鸿蒙？ | **协议可以复用，客户端不能直接复用**。私有通道协议是纯 JVM 逻辑（`protocol/*` 0 处 `import android`），可抽成独立模块供 Android 与 PC 共用；鸿蒙需按协议规范另行实现 |
| PC 端可行性 | **高**。Windows 可用 Kotlin/JVM 复用协议层，新增屏幕采集、H.264 编码、UI、打包与防火墙适配；macOS 需另做采集与签名公证 |
| HarmonyOS 端可行性 | **中低**。HarmonyOS NEXT 不兼容 APK，ArkTS/NAPI 需重写协议、媒体与加密；且当前环境无 DevEco Studio / HarmonyOS SDK / 鸿蒙设备 |
| 能否单轮交付安装包？ | **不能**。工作量约 73–100 人日（不含真机测试排队），且 HarmonyOS 工具链缺失 |
| 建议范围 | **Windows PC 优先**；macOS 与 HarmonyOS 作为后续阶段；HarmonyOS 先做协议 PoC，再决定是否投入完整客户端 |
| 需要产品裁决 | ① PC 是否重新进入 P1（2026-11-08 曾裁决桌面端移出 P1）；② 鸿蒙目标是 HarmonyOS NEXT 还是兼容 Android 的旧版；③ 延迟指标是否接受首版 120–200ms、逐步优化到 100ms |

---

## 2. 现状盘点（源码事实）

### 2.1 工程结构

- `settings.gradle.kts` 仅 `include(":app")`，**没有 desktop / harmony / shared 模块**。
- `README.md` 平台徽标为 Android 5.1+；接收端目标为 Android TV/盒子。
- `app/src/main` 下只有 `java`、`res` 等目录，**无 `jni/`、无 `cpp/`、无 `.so`**：现有能力全部是 Kotlin + Android Framework，没有可复用的 C++/Rust 核心。
- `docs/AirMirror-剩余待办分诊-20261108.md` 记录：T04246「桌面端整体未开工」；2026-11-08 产品裁决「桌面端移出 P1」。**T05757 与该裁决冲突，需重新确认范围。**

### 2.2 私有通道协议（可直接复用的事实）

| 项 | 现状 | 代码位置 |
|---|---|---|
| 帧头 | 固定 20 字节，大端：`magic(4) version(1) type(1) flags(1) reserved(1) ptsUs(8) payloadLen(4)` | `protocol/Protocol.kt` |
| magic | `0x414D5243`（"AMRC"） | 同上 |
| 帧类型 | `HELLO(1) / HELLO_ACK(2) / VIDEO(3) / AUDIO(4) / CONTROL(5) / PING(6) / PONG(7) / BYE(8) / ERROR(9) / AUTH(10) / AUTH_RESULT(11)` | 同上 |
| 版本 | `CURRENT=1`、`MIN_SUPPORTED=1`，握手取双方区间最高公共版本 | `protocol/Version.kt` |
| 载荷上限 | 媒体帧 8MB；握手帧 64KB（防未认证大分配） | `protocol/Protocol.kt` |
| 能力位 | `VIDEO_H264`、`AUDIO_AAC`、`TOUCH_INPUT` | `protocol/Handshake.kt` |
| 编码标志 | `KEY_FRAME / CONFIG / END_OF_STREAM / CODEC_HEVC / ENCRYPTED` | `protocol/Protocol.kt` |
| 端到端加密 | 每条 TCP 连接临时 ECDH P-256；HKDF-SHA256 派生双向 AES-256 密钥；载荷 AES-256-GCM（`iv(12)+密文+tag(16)`），帧头作 AAD | `protocol/KeyExchange.kt`、`protocol/MediaCipher.kt` |
| 投屏码锚 | 投屏码参与 HKDF salt（`CodeAnchor.anchorTag`），带码与不带码会话密钥不同 | `protocol/KeyExchange.kt` |
| 发现 | mDNS `_airmirror._tcp.`；UDP 探测 `41524`、通知 `41525`；组播失败降级定向广播/直连 | `net/Discovery.kt`、`net/ProbeProtocol.kt`、`net/DiscoveryLadder.kt` |
| 配对 | 6 位投屏码；二维码 `airmirror://pair?id=&t=&n=&h=&p=`，token 一次性 | `pairing/CastCode.kt`、`pairing/PairingLink.kt` |
| 媒体 | H.264 / H.265、AAC；`MediaCodec` 硬编硬解 | `media/VideoEncoder.kt`、`media/VideoDecoder.kt`、`media/H264.kt`、`media/H265.kt` |
| 跨网 | 可选信令 + UDP 打洞 + 隧道，默认关闭 | `crossnet/*` |

### 2.3 可直接复用 / 必须重写的边界

| 层 | Android 现有实现 | PC（Windows/macOS） | HarmonyOS NEXT |
|---|---|---|---|
| 帧编解码 | Kotlin（纯 JVM） | **可直接抽取复用** | 需 ArkTS/NAPI 重写 |
| 握手/版本协商 | Kotlin（纯 JVM） | 可复用 | 需重写 |
| ECDH/HKDF/AES-GCM | JVM `java.security` / `javax.crypto` | 可复用（JVM 原生支持） | 需系统 crypto 能力重写 |
| 发现 | Android `NsdManager` + UDP | mDNS/Bonjour + UDP（新写） | 系统 mDNS + UDP（新写） |
| 采集 | `MediaProjection` | Windows Graphics Capture / DXGI、macOS AVFoundation（新写） | 系统屏幕捕获（新写） |
| 编码 | `MediaCodec` | Media Foundation / VideoToolbox / OpenH264（新写） | 系统 AVCodec（新写） |
| 渲染 | `SurfaceView` + `MediaCodec` | 窗口/纹理渲染（新写） | XComponent/AVPlayer（新写） |
| UI | Android View | Compose Desktop / 原生窗口（新写） | ArkTS（新写） |
| 打包 | Gradle + APK | MSI/EXE、macOS .app/公证 | HAP（DevEco） |

---

## 3. 可行性评估

### 3.1 平台矩阵

| 目标 | 可行性 | 主要工作 | 主要风险 |
|---|---|---|---|
| Windows PC 发送端 | **高** | 采集 + H.264/AAC 编码 + 协议复用 + 发现/配对 + UI + 防火墙 + 安装包 | 采集 API 兼容、编码延迟、杀软误报、H.264 专利 |
| Windows PC 接收端 | **高—中** | TCP 服务端 + 协议复用 + 解码渲染 + 输入注入 + 托盘/多显示器 | 输入注入权限、全屏渲染、显卡兼容 |
| macOS 客户端 | **中** | AVFoundation 采集、VideoToolbox 编码、签名公证、权限（屏幕录制） | 公证/签名、权限引导、Intel/Apple Silicon |
| HarmonyOS NEXT | **中低** | ArkTS UI + 协议重写 + 系统媒体/加密 + 分布式权限 + HAP 打包 | 无 Android 兼容、无 DevEco/SDK、生态 API 差异、芯片适配 |
| HarmonyOS（兼容 Android 旧版） | **中** | 可直接安装现有 APK，验证发现/镜像兼容 | 非 NEXT，长期会被淘汰；不能算“鸿蒙原生客户端” |

### 3.2 关键技术判断

1. **协议层是最适合复用的资产**：`protocol/*` 纯 JVM、无 Android 依赖，抽成 `:protocol-core` 后 Android 与 PC 共用；鸿蒙用同一份“协议规范 + 测试向量”做语言无关一致性验证。
2. **不能直接移植 Android 采集/编码**：`MediaProjection` 与 `MediaCodec` 都是 Android Framework API，PC 必须另建采集与编码管线。
3. **鸿蒙没有可复用的 native 核心**：仓库无 C++/Rust 库，所谓“封装现有核心库”不成立；要么 ArkTS 重写，要么新建 C++ 核心（成本更高，且 Android 也要改）。
4. **加密协议可平移**：ECDH P-256 + HKDF-SHA256 + AES-256-GCM 在 PC（JVM）与鸿蒙（系统 crypto）都有标准实现；但要严格复现 AAD、salt transcript、投屏码锚，否则跨端握手必失败。
5. **延迟指标需重设**：现有代码没有跨端 glass-to-glass 实测数据；PC 软件采集 + 编码首版做到 100ms 以内不现实，建议首版验收 120–200ms，再通过硬编、零拷贝、帧率自适应优化。

---

## 4. 推荐架构

### 4.1 方案选择

| 方案 | 说明 | 评价 |
|---|---|---|
| A. 抽出 `:protocol-core`（Kotlin/JVM）+ PC 复用 + 鸿蒙按规范实现 | Android/PC 共用协议代码；鸿蒙用测试向量对齐 | **推荐**，成本最低、风险可控 |
| B. 新建 C++ 核心，三端 NAPI/JNI 绑定 | 三端共用一套 native 协议/编解码 | 成本最高，Android 也要重写，不建议现阶段做 |
| C. 改用 AirPlay / Google Cast / Cast+ | 依赖第三方协议栈 | **不可行**：项目已裁定三者均未实现，不作为可用协议 |

### 4.2 目标模块划分

```
AirMirror
├── app/                 # 现有 Android 客户端（发送/接收）
├── protocol-core/       # 新增：纯 Kotlin/JVM，帧/握手/加密/版本协商/测试向量
├── pc-client/           # 新增：Windows 优先，Compose Desktop 或原生窗口
│   ├── capture/         # Windows Graphics Capture / DXGI（macOS 后续接 AVFoundation）
│   ├── codec/           # Media Foundation / OpenH264；AAC
│   ├── transport/       # TCP + mDNS + UDP 发现 + 配对
│   └── ui/              # 设备列表、投屏码、设置、托盘
└── harmony-client/      # 新增（后续）：ArkTS UI + NAPI 媒体/加密
    ├── entry/           # 发现、配对、会话
    ├── media/           # 系统 AVCodec 编解码
    └── crypto/          # ECDH/HKDF/AES-GCM
```

### 4.3 协议冻结物（M0 必须先做）

1. `docs/protocol/AirMirror-Wire-Spec-v1.md`：帧头、类型、标志、握手、版本协商、错误码、加密与 AAD 逐字节说明。
2. `protocol-core/src/test/resources/vectors/*.json`：HELLO/HELLO_ACK、HKDF、AES-GCM、投屏码锚、异常帧的输入/期望输出。
3. 跨语言一致性脚本：任一实现跑同一组向量，输出必须逐字节一致。
4. 兼容策略：新端必须支持 v1；新增能力只加 capability 位/flag，不改帧头布局。

### 4.4 PC 采集与编码建议

- **Windows**：优先 Windows Graphics Capture（Win10 1903+）或 DXGI Desktop Duplication；采集 NV12 → Media Foundation 硬编 H.264；无硬编时回退 OpenH264（BSD）。
- **macOS**：ScreenCaptureKit / AVFoundation + VideoToolbox。
- **音频**：Windows WASAPI loopback；macOS ScreenCaptureKit 音频；AAC-LC 编码（注意 AAC 专利许可）。
- **避免 GPL 污染**：不使用 libx264（GPL）；FFmpeg 仅以 LGPL 组件方式引入或不引入。
- **帧率/分辨率**：首版 1080p30 自适应；按接收端能力位协商；保留关键帧请求与码率回退。

### 4.5 鸿蒙实现建议

- 目标优先确认 **HarmonyOS NEXT**（原生 HAP）；若只要求“能装”，旧兼容版可直接跑 APK，但不算原生客户端。
- UI 用 ArkTS；协议、加密、媒体通过 NAPI 调系统能力或 native 库。
- 必须复现：mDNS 服务类型、UDP 41524/41525、HELLO 公钥/投屏码签名、HKDF salt/AAD、TCP 帧头。
- 权限：屏幕捕获、局域网/多播、前台服务/长时任务；需在真机验证后台保活。
- 先在 DevEco 模拟器/真机做**协议 PoC**（发现 + HELLO + AUTH + 一帧视频），再投入完整 UI。

---

## 5. 分阶段实施计划（WBS）

| 阶段 | 内容 | 交付物 | 预估 | 依赖 |
|---|---|---|---|---|
| **M0 协议冻结** | 抽取 `:protocol-core`；写 wire spec；生成测试向量；Android 回归 | 规范文档 + JVM 模块 + 向量 + 单测 | 5–8 人日 | 无 |
| **M1 Windows PC 发送端 MVP** | 采集、H.264/AAC 编码、发现/配对、TCP 传输、设备列表 UI | Windows 安装包（未签名可测） | 15–20 人日 | M0 |
| **M2 Windows PC 接收端 MVP** | TCP 服务端、解码渲染、输入注入、托盘/多显示器基础 | Windows 安装包 | 10–12 人日 | M0、M1 共用传输层 |
| **M3 HarmonyOS PoC** | DevEco 工程、mDNS/UDP、HELLO/AUTH、单帧视频、加密 | PoC HAP + 打通记录 | 10–15 人日 | HarmonyOS SDK/真机 |
| **M4 HarmonyOS 客户端 MVP** | ArkTS UI、采集/编码/解码、设置、权限、HAP 打包 | HAP 安装包 | 25–35 人日 | M3 |
| **M5 多端集成测试** | 6 组组合矩阵、延迟/稳定性/断线重连/未授权接入 | 测试报告 + 性能数据 | 8–10 人日 | M1/M2/M4 + 多设备 |

**合计约 73–100 人日**（单开发、不含设备排队与返工）。建议先批准 M0–M2（Windows 先行），M3 起需 SDK 与真机到位后再启动。

### 5.1 验收矩阵

| 组合 | 必测项 |
|---|---|
| PC → Android TV/手机 | 发现、投屏码/扫码、镜像、音频、断线重连 |
| Android → PC | PC 接收端发现、解码渲染、窗口/全屏、输入 |
| PC → HarmonyOS | 握手、加密、分辨率协商、延迟 |
| HarmonyOS → PC | 采集、编码、协议兼容 |
| Android ↔ HarmonyOS | 双向镜像、投屏码、权限 |
| 异常 | 错误 magic/版本、重放、未授权、密钥错误、半帧断开 |

性能基线：1080p30 首版 120–200ms；稳定性 2 小时无崩溃；断网 10s 内重连；未授权设备 0 接入。

---

## 6. 风险与待裁决

| 编号 | 风险/决策 | 影响 | 建议 |
|---|---|---|---|
| R1 | T05757 与 2026-11-08「桌面端移出 P1」裁决冲突 | 范围反复、验收口径不一致 | 产品明确恢复 PC 为 P1，或把本单降为“预研” |
| R2 | 鸿蒙目标版本未定（NEXT vs 兼容 Android） | 决定是否需要原生 HAP | 优先确认；若只需兼容版，直接验证 APK 即可，不新建客户端 |
| R3 | 当前环境无 DevEco/SDK/鸿蒙真机 | M3/M4 无法开工 | 先装 DevEco + 借测真机，或把鸿蒙阶段挂起 |
| R4 | H.264/AAC 专利与 FFmpeg 许可证 | 分发合规风险 | 用平台硬编 / OpenH264；AAC 走系统编码器；避免 GPL |
| R5 | <100ms 延迟目标 | 首版很难达标 | 改为 glass-to-glass 实测，首版 120–200ms，后续优化 |
| R6 | Windows 防火墙/杀软拦截 mDNS/UDP/TCP | 发现失败、误报 | 安装时申请网络权限；提供手动 IP/投屏码兜底 |
| R7 | 三套客户端长期维护成本 | 人力与回归成本 | 协议核心共用 + 向量化 CI；UI 各自维护 |
| R8 | 多设备测试环境缺失 | 组合矩阵无法验收 | 至少 1 台 Android TV、1 台 PC、1 台鸿蒙真机 |

---

## 7. 本轮未交付项（诚实标注）

- 未生成 PC / 鸿蒙客户端源码或安装包。
- 未做跨端实机联调与延迟测量。
- 未改任何现有 Android 代码；未触碰并发会话 WIP。
- 原因：新增平台属新模块，工作量 73–100 人日；HarmonyOS SDK/DevEco 与多设备测试环境在当前环境缺失；且 PC 范围与既有产品裁决冲突，需先确认。

## 8. 建议下一步

1. **产品裁决 R1/R2/R5**：PC 是否恢复 P1、鸿蒙目标版本、延迟验收口径。
2. 批准 **M0 协议冻结**：先抽 `:protocol-core` 与测试向量，风险最低且三端都需要。
3. 批准 **M1 Windows 发送端 MVP**：以“PC → Android TV”单链路打通为里程碑。
4. 鸿蒙阶段在 DevEco/SDK/真机到位后启动 M3 PoC，PoC 通过再投入 M4。

---

## 附录：证据位置

- 帧协议：`app/src/main/java/io/github/wangnan05563/airmirror/protocol/Protocol.kt`
- 版本协商：`.../protocol/Version.kt`
- 握手与能力：`.../protocol/Handshake.kt`
- 密钥协商：`.../protocol/KeyExchange.kt`
- 媒体加密：`.../protocol/MediaCipher.kt`
- 发现：`.../net/Discovery.kt`、`ProbeProtocol.kt`、`DiscoveryLadder.kt`
- 配对：`.../pairing/CastCode.kt`、`PairingLink.kt`
- 工程结构：`settings.gradle.kts`（仅 `:app`）
- 范围裁决：`docs/AirMirror-剩余待办分诊-20261108.md`
