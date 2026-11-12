# AirMirror

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%205.1%2B-green.svg)](#技术栈)
[![minSdk](https://img.shields.io/badge/minSdk-22-lightgrey.svg)](#技术栈)
[![GitHub](https://img.shields.io/badge/GitHub-wangnan05563%2FAirMirror-blue?logo=github)](https://github.com/wangnan05563/AirMirror)
[![Release](https://img.shields.io/github/v/release/wangnan05563/AirMirror?label=Release)](https://github.com/wangnan05563/AirMirror/releases)

多协议无线投屏 / 镜像接收端（Android TV 与盒子）。仓库已有可运行的 Android 工程：`minSdk 19`、JVM 单测全绿、M0/M1 已完成，M2 的 P0 承诺（发现、镜像、推送、投屏码、4K、音画同步、断线恢复、隐私边界、老设备降级、安装自启等）均已实现并通过单测与模拟器验收。

## 现状

- 主文档：[AirMirror 产品需求文档 v1.6](docs/AirMirror-PRD-v1.1.md) —— 注意文件名仍是 `v1.1`，内部版本号已到 v1.6，改名会在 §15 与评审文档里留下断链，故保留。
- 文档布局：PRD、评审、设计、验收与分诊文档统一放在 [`docs/`](docs/README.md)，仓库根只保留本文件与构建入口。
- 里程碑路线：M0 协议验证 → M1 最小可用 → M2 P0 完整 → M3 P1 → M4 P2（见 PRD §15）。
- 目标设备：接收端为 Android 5.1（API 22）及以上的电视与盒子。Q-5 原裁 Android 4.4（API 19），2026-11-12 经 T04908 裁决 1 重签为 API 22，以启用无障碍遍历顺序 API；Android 4.4–5.0 设备不再受支持。

## GitHub 仓库与发布

- **官方仓库**：<https://github.com/wangnan05563/AirMirror>（默认分支 `main`）。PRD、评审、WBS 与专项优化文档都随仓库提交，不另设 wiki；从仓库根目录的 `README.md`（本文件）进入即可。
- **唯一官方分发渠道是 GitHub Release**：PRD 的 Q-6 已裁定「只发 GitHub Release」，不走任何应用商店。App 内「检查更新」直接查询本仓库的 GitHub Release；安装新版本时由系统弹「未知来源」授权，App 不自批，也不内置任何第三方下载源。
- **从源码构建**（想自己编译的访客）：见下文「构建与安装」。Windows 用 `scripts/build.ps1`；其它平台直接用仓库自带的 Gradle wrapper（`./gradlew testDebugUnitTest assembleDebug --offline`）。JDK 17+ 与 Android SDK（`platforms` + `platform-tools`）是硬依赖。
- **发版流程**：见「构建与安装 → 一键发布」。脚本从 `git remote origin` 解析仓库，构建后打 tag 并创建 Release 上传 APK；缺凭据、工作区脏或产物不全时当场失败并回滚远端草稿，不会留下半成品。`-DryRun` 可只读校验流程。
- **议题与贡献**：Bug 与建议请到仓库开 issue（附复现步骤、设备型号、系统版本）；提交前跑 `scripts/build.ps1`，单测与打包须全绿。详细约定见「贡献」一节。

## 实现状态（速览）

> 验收层级记号：**U** = JVM 单测锁死；**E** = 模拟器设备腿实测；**R** = 必须真机 / 第二台设备，目前拿不到证据。
> 完整逐条映射见 PRD 能力地图与《AirMirror 项目现状总览》。

**已实现并通过单测 + 模拟器验收（U+E）**：FS-01 屏幕镜像、FS-02 DLNA 媒体推送、FS-03 投屏码、FS-06 低延迟音画同步、FS-07 设备发现（含组播禁用降级）、FS-08 竖屏与画面适配、FS-09 会话控制与接管、FS-10 断线与恢复、FS-11 隐私与数据边界（端到端加密 + 防滥用限流 + 数据零留存）、FS-13 接收端安装与自启、6.1 电视端视觉与焦点、6.2 手机端设备卡片交互、7.1 扩展项合规守卫、1.1 双 ABI 安装包；P2 的访客浏览器上传（一次性 token 入口 + 本地缓存 + 直接播放）也已落地并完成设备腿验证：`GET /w/<token>/` 返回 200、`POST /w/<token>/upload` 返回 `{"ok":true}`、接收端整屏显示上传图片，二次上传后缓存目录只保留最新一份。

**已实现但只有单测（U）**：FS-05 4K 四道门（判定链路在设备上只能看到 1080p60 上限，4K 画质需真机与真 4K 源）、FS-12 老设备 A/B/C 分级（策略与降级已实现，C 级长稳需低端真机）。

**未实现 / 待真机（R）**：FS-04 跨网投屏的客户端链路（信令、打洞、可靠 UDP 隧道、双端入口）已落地，并已用「模拟器 + 宿主机参考信令服务」跑通端到端（MATCH → 候选交换 → UDP 打洞 → 隧道 → HELLO/HELLO_ACK 码锚 → 端到端加密 → AUTH → H.264 编码 → 接收端解码渲染）；默认关闭、需自行配置信令端点，公网/跨网段真机验收仍属 R。其余端到端验收需第二台同网设备、真 TV 遥控器或真 4K 源（发现回环、遥控器 D-pad、HDR 画质、真机延迟听测等），已逐项挂出派生取证单。

## 跨网投屏（可选，默认关闭）

跨网只做「信令 + 打洞直连」，不自建中继（PRD Q-4），画面与音频仍点对点、不经服务器。信令服务只按投屏码哈希配对两端并转发候选地址，拿不到投屏码原文与媒体内容。

仓库自带一个 stdlib 参考服务，仅用于自测/自部署：

```powershell
python scripts\signal-server.py --host 0.0.0.0 --port 8787
```

电视端「显示设置」与手机端「跨网信令」填 `http://<运行服务的主机>:8787` 后生效；留空即完全关闭，App 不会向任何外部服务发请求。生产部署请放在 TLS 反向代理之后并做限流。

## 构建与安装

依赖：JDK 17+、Android SDK（`platforms` 与 `platform-tools`）、仓库自带 Gradle wrapper。Windows 上一条命令完成依赖检测、单测与打包：

```powershell
$env:JAVA_HOME = '<你的 JDK 17 目录>'
.\scripts\build.ps1            # 默认 testDebugUnitTest + assembleDebug；-Release 打未签名 release 包
```

不用脚本时等价于：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug --console=plain --offline
```

发布包默认**未签名**。配置好密钥后可显式签名（密钥路径与口令只走命令行参数，不入库）：

```powershell
.\gradlew.bat assembleRelease -PkeystoreFile=D:\keys\airmirror.jks `
  -PkeystorePassword=<store-password> -PkeyAlias=airmirror -PkeyPassword=<key-password>
```

App 内「检查更新」会查询内置仓库的 GitHub Release；安装新版本时由系统弹「未知来源」授权，App 不自批。

一键发布（两条凭据通道，任选其一，都只从环境变量读取）：

- `gh` 已 `gh auth login`；或
- 没有 `gh` 时设置 `GITHUB_TOKEN`（细粒度 token 需 `Contents: Read and write`），脚本经 GitHub REST API 创建远端 tag 与 Release，不需要 `git push` 凭据。

```powershell
$env:GITHUB_TOKEN = '<your token>'   # 仅无 gh 时需要；不要写进仓库
.\scripts\release.ps1 -DryRun     # 只校验流程与产物，不写任何远端
.\scripts\release.ps1             # 构建 → 打 tag → 建 Release → 上传 APK
```

脚本在前置检查全部通过后才写远端：缺凭据、工作区有未提交改动、产物不全或 tag 已存在时当场失败，不会留下「本地有 tag、远端没 Release」的半成品。REST 通道先建草稿 Release、传完资产再转正，任何一步失败都会回滚本次创建的远端 tag 与草稿。工作区检查是为了让 Release 里的包能从 tag 原样重建——`-DryRun` 只读，允许脏工作区。现有产物若带 `-unsigned` 会显式警告（未签名包安装会被系统拒绝）。

产物汇总在 `release/debug/`（`app-universal-debug.apk` 为全 ABI 包；构建脚本从 Gradle 输出目录复制过来，删掉 `release/` 即可彻底清理）。装到已连接的设备并拉起接收端：

```cmd
scripts\push-apk.cmd                       :: 单台设备
scripts\push-apk.cmd emulator-5554         :: 指定设备
scripts\push-apk.cmd emulator-5554 clean   :: 清 App 数据，复验首启引导
```

## 技术栈

- Kotlin 2.0 + AGP 8.7，`minSdk 22`（Q-5 重签） / `targetSdk 35`；无 AndroidX，界面用平台 View 代码绘制（包体 ≈1.05MB）。
- 协议与判定拆成不引用 `android.*` 的纯逻辑，JVM JUnit4 覆盖；设备侧（MediaProjection、MediaPlayer、SSDP/HTTP）保留在 android 层。
- 私有通道（AMRC 帧格式 + HKDF 密钥派生 + AES 端到端加密）、DLNA 接收端（SSDP/描述/SOAP/媒体播放）、局域网发现（NSD + 广播 + 定向探测）。
- 安全与隐私：投屏内容不落盘、诊断默认关闭、连接限流、中继侧不可解密（帧头明文，内容加密）。

## 仓库里的评审与规划文档

完整索引（含设计、验收与分诊文档）见 [`docs/README.md`](docs/README.md)。常用入口：

| 文件 | 内容 |
| --- | --- |
| [`docs/AirMirror-PRD-v1.1.md`](docs/AirMirror-PRD-v1.1.md) | 产品需求文档（当前 v1.6） |
| [`docs/AirMirror-需求详细评审-T03781.md`](docs/AirMirror-需求详细评审-T03781.md) | P0 边界、协议支持与验收标准的逐行评审，含 12 条裁决 |
| [`docs/AirMirror-PRD修订草案-T03781.md`](docs/AirMirror-PRD修订草案-T03781.md) | 上述评审落到 PRD 的修订清单（已写入 v1.3/v1.4） |
| [`docs/AirMirror-代表设备选型-T03931.md`](docs/AirMirror-代表设备选型-T03931.md) | A/B/C 三级代表设备（C 级已于 2026-11-12 重新选型，见该文档第九节） |
| [`docs/AirMirror-WBS收敛dry-run表.md`](docs/AirMirror-WBS收敛dry-run表.md) | 工作分解结构收敛方案与执行记录 |
| [`docs/AirMirror-待办分诊报告.md`](docs/AirMirror-待办分诊报告.md) | 待办批量导入后的分诊结论 |
| [`docs/AirMirror-通用需求10单归属判定.md`](docs/AirMirror-通用需求10单归属判定.md) | 跨项目搬运需求的归属与执行时点判定 |

## 许可与复用边界

项目已确定开源（PRD 的 Q-2 裁定），**许可证为 Apache-2.0**（2026-11-05 用户裁定；全文见 `LICENSE`，第三方参考清单与版权声明见 `NOTICE`）。无论选型如何，仍按 PRD §8.10 的保守口径执行：GPL / LGPL / AGPL 的同类实现（UxPlay、airplay2-receiver、miraclecast、deskreen 等）只参考协议流程与架构设计，不链接、不复制代码；Apache-2.0 / MIT 项目（scrcpy、QtScrcpy、go2tv）可引入，但需保留版权声明与许可副本。

AirPlay、Google Cast 为私有协议，本项目只参考其协议流程，当前接收端尚未实现；能力边界以 `ProtocolBoundary` 为准（仅 AMRC 私有通道、DLNA 推送、浏览器上传可用，其余协议统一展示「暂不支持」）。相关合规评估在上架前完成，实现时不含任何厂商商标。

## 开发取证

- **验证拓扑**：单台模拟器（x86_64，装 universal 包）内同时跑发送端与接收端；宿主侧 `adb forward tcp:14523 tcp:41523` 让 Node 伪发送端打进设备，`adb reverse` 供设备取宿主媒体；Node 中间代理充当「中继等价物」逐字节抓包，用于验证端到端加密与「中继不可解密」判据。
- **临时目录布局（新片一律遵守）**：`.mtask_tmp/<片号>_<主题>/{scripts,evidence,logs}`，每片一个子目录；根目录只放通用工具（`mcp.py`、`ui_tap.js`、`gate.js`）。历史平铺文件保持原位不搬家（任务结论里引用的路径不能断链），新产生的取证一律进新布局。
- **取证安全纪律**：含凭据的 dump/截图测后即删，日志留存前先扫描；单据只走 MCP/REST 通道，绝不直接写 sqlite（只读 `mode=ro` 取证可以）；`mtask_update_task_result` 是整文替换，追加前必须先读回 `handle_result` 再拼接。

## 贡献

- 提交前跑 `scripts/build.ps1`：单测与打包必须全绿；协议与判定逻辑优先放在不依赖 `android.*` 的纯函数里，并补 JVM 单测。
- **新增 TCP 服务必须同时设读超时与并发上限**：本项目所有服务都是「每连接一线程」，缺任一项都等于给局域网里任何人一个线程耗尽入口。三处现有实现可直接照抄：`DlnaServer`（请求头 10s + 16 路，实测空连接 10136ms 断开、第 17 个 30ms 被拒）、`ReceiverSession`（握手 10s + 16 路，实测 10017ms / 33ms）、`BrowserCastServer`（头 10s + 体 60s，实测空连接 0.244s 断开）。超限时直接关 socket，不要先起线程再判。
- 不要引入 GPL / LGPL / AGPL 代码（见「许可与复用边界」）；引入 Apache-2.0 / MIT 组件需保留版权与许可副本，并在 `NOTICE` 登记。
- 提交即表示同意以 Apache-2.0 授权该贡献；问题与建议请开 issue，附复现步骤、设备型号与系统版本。

