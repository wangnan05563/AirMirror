# AirMirror

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%204.4%2B-green.svg)](#技术栈)
[![minSdk](https://img.shields.io/badge/minSdk-19-lightgrey.svg)](#技术栈)

多协议无线投屏 / 镜像接收端（Android TV 与盒子）。仓库已有可运行的 Android 工程：`minSdk 19`、JVM 单测全绿、M0/M1 已完成，M2 的 P0 承诺（发现、镜像、推送、投屏码、4K、音画同步、断线恢复、隐私边界、老设备降级、安装自启等）均已实现并通过单测与模拟器验收。

## 现状

- 主文档：[AirMirror 产品需求文档 v1.4](AirMirror-PRD-v1.1.md) —— 注意文件名仍是 `v1.1`，内部版本号已到 v1.4，改名会在 §15 与评审文档里留下断链，故保留。
- 里程碑路线：M0 协议验证 → M1 最小可用 → M2 P0 完整 → M3 P1 → M4 P2（见 PRD §15）。
- 目标设备：接收端为 Android 4.4（API 19）及以上的电视与盒子，硬下限已由 PRD 的 Q-5 裁定。

## 实现状态（速览）

> 验收层级记号：**U** = JVM 单测锁死；**E** = 模拟器设备腿实测；**R** = 必须真机 / 第二台设备，目前拿不到证据。
> 完整逐条映射见 PRD 能力地图与《AirMirror 项目现状总览》。

**已实现并通过单测 + 模拟器验收（U+E）**：FS-01 屏幕镜像、FS-02 DLNA 媒体推送、FS-03 投屏码、FS-05 4K 四道门、FS-06 低延迟音画同步、FS-07 设备发现（含组播禁用降级）、FS-08 竖屏与画面适配、FS-09 会话控制与接管、FS-10 断线与恢复、FS-11 隐私与数据边界（端到端加密 + 防滥用限流 + 数据零留存）、FS-12 老设备 A/B/C 分级与降级、FS-13 接收端安装与自启、6.1 电视端视觉与焦点、6.2 手机端设备卡片交互、7.1 扩展项合规守卫、1.1 双 ABI 安装包。

**未实现 / 待真机（R）**：FS-04 跨网投屏（M3 中继模块，未开工）；部分端到端验收需第二台同网设备、真 TV 遥控器或真 4K 源（发现回环、遥控器 D-pad、HDR 画质、真机延迟听测等），已逐项挂出派生取证单。

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

产物在 `app/build/outputs/apk/debug/`（`app-universal-debug.apk` 为全 ABI 包）。装到已连接的设备并拉起接收端：

```cmd
scripts\push-apk.cmd                       :: 单台设备
scripts\push-apk.cmd emulator-5554         :: 指定设备
scripts\push-apk.cmd emulator-5554 clean   :: 清 App 数据，复验首启引导
```

## 技术栈

- Kotlin 2.0 + AGP 8.7，`minSdk 19` / `targetSdk 35`；无 AndroidX，界面用平台 View 代码绘制（包体 ≈1.05MB）。
- 协议与判定拆成不引用 `android.*` 的纯逻辑，JVM JUnit4 覆盖；设备侧（MediaProjection、MediaPlayer、SSDP/HTTP）保留在 android 层。
- 私有通道（AMRC 帧格式 + HKDF 密钥派生 + AES 端到端加密）、DLNA 接收端（SSDP/描述/SOAP/媒体播放）、局域网发现（NSD + 广播 + 定向探测）。
- 安全与隐私：投屏内容不落盘、诊断默认关闭、连接限流、中继侧不可解密（帧头明文，内容加密）。

## 仓库里的评审与规划文档

| 文件 | 内容 |
| --- | --- |
| `AirMirror-PRD-v1.1.md` | 产品需求文档（当前 v1.4） |
| `AirMirror-需求详细评审-T03781.md` | P0 边界、协议支持与验收标准的逐行评审，含 12 条裁决 |
| `AirMirror-PRD修订草案-T03781.md` | 上述评审落到 PRD 的修订清单（已写入 v1.3/v1.4） |
| `AirMirror-代表设备选型-T03931.md` | A/B/C 三级代表设备（当贝 MAX1、腾讯极光 5S 2GB、EC6108V9、创维 T2） |
| `AirMirror-WBS收敛dry-run表.md` | 工作分解结构收敛方案与执行记录 |
| `AirMirror-待办分诊报告.md` | 待办批量导入后的分诊结论 |
| `AirMirror-通用需求10单归属判定.md` | 跨项目搬运需求的归属与执行时点判定 |

## 许可与复用边界

项目已确定开源（PRD 的 Q-2 裁定），**许可证为 Apache-2.0**（2026-11-05 用户裁定；全文见 `LICENSE`，第三方参考清单与版权声明见 `NOTICE`）。无论选型如何，仍按 PRD §8.10 的保守口径执行：GPL / LGPL / AGPL 的同类实现（UxPlay、airplay2-receiver、miraclecast、deskreen 等）只参考协议流程与架构设计，不链接、不复制代码；Apache-2.0 / MIT 项目（scrcpy、QtScrcpy、go2tv）可引入，但需保留版权声明与许可副本。

AirPlay、Google Cast 为私有协议，本项目独立实现，不含任何厂商商标；相关合规评估在上架前完成。

## 开发取证

- **验证拓扑**：单台模拟器（x86_64，装 universal 包）内同时跑发送端与接收端；宿主侧 `adb forward tcp:14523 tcp:41523` 让 Node 伪发送端打进设备，`adb reverse` 供设备取宿主媒体；Node 中间代理充当「中继等价物」逐字节抓包，用于验证端到端加密与「中继不可解密」判据。
- **临时目录布局（新片一律遵守）**：`.mtask_tmp/<片号>_<主题>/{scripts,evidence,logs}`，每片一个子目录；根目录只放通用工具（`mcp.py`、`ui_tap.js`、`gate.js`）。历史平铺文件保持原位不搬家（任务结论里引用的路径不能断链），新产生的取证一律进新布局。
- **取证安全纪律**：含凭据的 dump/截图测后即删，日志留存前先扫描；单据只走 MCP/REST 通道，绝不直接写 sqlite（只读 `mode=ro` 取证可以）；`mtask_update_task_result` 是整文替换，追加前必须先读回 `handle_result` 再拼接。

## 贡献

- 提交前跑 `scripts/build.ps1`：单测与打包必须全绿；协议与判定逻辑优先放在不依赖 `android.*` 的纯函数里，并补 JVM 单测。
- 不要引入 GPL / LGPL / AGPL 代码（见「许可与复用边界」）；引入 Apache-2.0 / MIT 组件需保留版权与许可副本，并在 `NOTICE` 登记。
- 提交即表示同意以 Apache-2.0 授权该贡献；问题与建议请开 issue，附复现步骤、设备型号与系统版本。

