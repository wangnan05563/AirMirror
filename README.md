# AirMirror

多协议无线投屏 / 镜像接收端（Android TV 与盒子）。当前仓库处于**需求与设计阶段，尚无源码**。

## 现状

- 主文档：[AirMirror 产品需求文档 v1.4](AirMirror-PRD-v1.1.md) —— 注意文件名仍是 `v1.1`，内部版本号已到 v1.4，改名会在 §15 与评审文档里留下断链，故保留。
- 里程碑路线：M0 协议验证 → M1 最小可用 → M2 P0 完整 → M3 P1 → M4 P2（见 PRD §15）。
- 目标设备：接收端为 Android 4.4（API 19）及以上的电视与盒子，硬下限已由 PRD 的 Q-5 裁定。

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

项目已确定开源（PRD 的 Q-2 裁定），**许可证选型待定**。在选型落地前，按 PRD §8.10 的保守口径执行：GPL / LGPL / AGPL 的同类实现（UxPlay、airplay2-receiver、miraclecast、deskreen 等）只参考协议流程与架构设计，不链接、不复制代码；Apache-2.0 / MIT 项目（scrcpy、QtScrcpy、go2tv）可引入，但需保留版权声明与许可副本。

AirPlay、Google Cast 为私有协议，本项目独立实现，不含任何厂商商标；相关合规评估在上架前完成。
