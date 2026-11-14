# AirMirror 文档索引

本目录集中存放项目的 PRD、评审、设计、验收与分诊文档。**仓库根目录只保留 `README.md`
（GitHub 首页）与构建入口**，其余文档一律在本目录维护。

> **路径口径**：本文档目录内的所有 `app/...`、`scripts/...`、`build/mtask/...` 路径均指
> **仓库根**下的路径，不是相对本目录。2026-11-12 之前写在仓库根的同名文档已整体迁入本目录，
> 文件名未变；历史任务结论里若出现裸文件名或带行号的旧路径，按同名在本目录查找即可。

## 产品与需求

| 文件 | 内容 |
| --- | --- |
| [AirMirror-PRD-v1.1.md](AirMirror-PRD-v1.1.md) | 产品需求文档（当前 v1.6；文件名保留 v1.1 以避免历史断链） |
| [AirMirror-PRD-v1.0.html](AirMirror-PRD-v1.0.html) | PRD v1.0 的 HTML 历史快照，仅供对照，不再维护 |
| [AirMirror-需求详细评审-T03781.md](AirMirror-需求详细评审-T03781.md) | P0 边界、协议支持与验收标准的逐行评审，含 12 条裁决 |
| [AirMirror-PRD修订草案-T03781.md](AirMirror-PRD修订草案-T03781.md) | 上述评审落到 PRD 的修订清单（已写入 v1.3/v1.4） |
| [AirMirror-代表设备选型-T03931.md](AirMirror-代表设备选型-T03931.md) | A/B/C 三级代表设备与 2026-11-12 的 C 级重新选型 |

## 规划与分诊

| 文件 | 内容 |
| --- | --- |
| [AirMirror-WBS收敛dry-run表.md](AirMirror-WBS收敛dry-run表.md) | 工作分解结构收敛方案与执行记录 |
| [AirMirror-待办分诊报告.md](AirMirror-待办分诊报告.md) | 待办批量导入后的分诊结论（2026-11-01/02） |
| [AirMirror-剩余待办分诊-20261108.md](AirMirror-剩余待办分诊-20261108.md) | 2026-11-08 剩余待办分组与解锁条件 |
| [AirMirror-通用需求10单归属判定.md](AirMirror-通用需求10单归属判定.md) | 跨项目搬运需求的归属与执行时点判定 |
| [AirMirror-项目现状总览-20261105.md](AirMirror-项目现状总览-20261105.md) | 能力地图、需裁事项与收束动作清单 |
| [AirMirror-交接-20261105.md](AirMirror-交接-20261105.md) | 会话交接快照与踩坑记录 |
| [AirMirror-版本口径与HEAD基线-20261109.md](AirMirror-版本口径与HEAD基线-20261109.md) | 版本口径与 HEAD 基线核对 |

## 专项设计与验收

| 文件 | 内容 |
| --- | --- |
| [AirMirror-体验与UI优化专项PRD-竞品调研-v1.0.md](AirMirror-体验与UI优化专项PRD-竞品调研-v1.0.md) | 体验与 UI 优化专项 PRD（UX-01～06 / UI-01～07）与竞品调研 |
| [AirMirror-UI风格规范冻结-20261109.md](AirMirror-UI风格规范冻结-20261109.md) | UI 风格规范冻结基线 |
| [AirMirror-视觉常量收敛方案-20261109.md](AirMirror-视觉常量收敛方案-20261109.md) | 设计令牌单一来源的收敛方案 |
| [AirMirror-无障碍属性补全设计-20261109.md](AirMirror-无障碍属性补全设计-20261109.md) | 无障碍属性补全与 §8.3 TC-08 焦点顺序基线 |
| [AirMirror-空列表诊断映射设计-20261109.md](AirMirror-空列表诊断映射设计-20261109.md) | UX-01 判决式空列表的映射与文案设计 |
| [AirMirror-投屏码轮换过渡设计-20261109.md](AirMirror-投屏码轮换过渡设计-20261109.md) | 投屏码轮换的过渡与容错设计 |
| [AirMirror-专项范围边界确认-20261109.md](AirMirror-专项范围边界确认-20261109.md) | 体验专项的范围边界与不做清单 |
| [AirMirror-体验指标验证方案-20261109.md](AirMirror-体验指标验证方案-20261109.md) | 体验指标的度量与验证方案 |
| [AirMirror-竞品调研数据清洗-20261109.md](AirMirror-竞品调研数据清洗-20261109.md) | 竞品调研数据的清洗口径与可复核性说明 |

## 代码质量

| 文件 | 内容 |
| --- | --- |
| [sonar-reports/AirMirror-SonarQube-修复前报告-20261114.md](sonar-reports/AirMirror-SonarQube-修复前报告-20261114.md) | 2026-11-14 首轮 SonarQube 扫描：70 条问题的级别/规则/文件分布、修复策略与误报判定 |
| [sonar-reports/AirMirror-SonarQube-修复后报告-20261114.md](sonar-reports/AirMirror-SonarQube-修复后报告-20261114.md) | 复扫 0 问题、质量门禁状态、测试与生产构建结果 |
| [sonar-reports/AirMirror-SonarQube-问题修复记录-20261114.md](sonar-reports/AirMirror-SonarQube-问题修复记录-20261114.md) | 逐规则修复方式与代码落点、8 处 NOSONAR 理由、编译与测试过程 |

## 维护约定

- 新增文档一律放本目录，并在上表登记；不要放在仓库根。
- 仓库根 `README.md` 是唯一例外（GitHub 首页），其中的文档链接统一指向 `docs/`。
- 单测里的「项目根」探测统一以 `settings.gradle.kts` 为标记，**不要**再依赖任何文档文件名。
