# AirMirror 竞品调研数据清洗（T04813）

> 依据：专项 PRD §00「调研方法与证据边界」+ REQ-409（证据纪律）、REQ-410（许可边界）、REQ-412（14 选 5，不重列全表）。
> 清洗基线：专项 PRD v1.0.1（2026-11-09）、主 PRD §12.2、仓库 `NOTICE` / `LICENSE`（HEAD `b896dfa`）。
> 方法：只做**证据分级、口径统一、不可复核项降级**；不重新抓取 star 数，不把快照当产品结论。

## 一、数据集边界

- 主 PRD §12.2 共 **14** 个同类实现；专项 PRD 按 REQ-412 选 **5** 个做 UX/UI 深挖，本文只处理这 5 个，不重列其余 9 个。
- 5 个仓库覆盖 5 种形态：桌面镜像与控制（scrcpy）、桌面图形化封装（QtScrcpy）、浏览器零安装副屏（deskreen）、DLNA 推送（go2tv）、AirPlay 接收（UxPlay）。
- 快照字段（star / 最近推送）保留为「2026-11-08 GitHub API 快照」，**不作为优劣结论**，本轮未重新抓取。

## 二、清洗后的结构化数据

| # | 仓库 | 形态 | 许可证 | 许可类别 | 可用方式 | 证据等级 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `Genymobile/scrcpy` | USB/TCP 镜像与控制 | Apache-2.0 | 宽松 | 可引入，保留版权与许可副本 | A（README + `doc/window.md` / `doc/shortcuts.md`） |
| 2 | `barry-ran/QtScrcpy` | 基于 scrcpy 的 Qt 客户端 | Apache-2.0 | 宽松 | 可引入，保留版权与许可副本 | A（README「界面按钮介绍」） |
| 3 | `pavlobu/deskreen` | 浏览器副屏（Electron + WebRTC） | AGPL-3.0 | 传染 | **只参考交互与协议流程**，不链接、不复制 | B（README + 官网；官网取色与评价见 §六） |
| 4 | `alexballas/go2tv` | DLNA / Cast 推送 | MIT | 宽松 | 可引入，登记 `NOTICE` | A（README Features / CLI 用法） |
| 5 | `FDH2/UxPlay` | AirPlay2 镜像与音频接收 | GPL-3.0 | 传染 | **只参考协议与架构**，不复制代码 | A（README 启动选项与排障） |

口径统一：

- 「证据等级 A」= 仓库文档明示且可定位（README / doc 文件）；「B」= 来源为官网自述或单条转述，不能独立复核。
- 「许可类别」只分宽松（Apache/MIT）与传染（GPL/AGPL），对应主 PRD Q-2 与 §8.10。
- `NOTICE` 已列 scrcpy / QtScrcpy / go2tv / UxPlay / deskreen 等参考项，并声明「仅协议研究、不链接不分发」。

## 三、竞品特性 → 证据映射（清洗后）

| 特性（保留） | 仓库 | 证据 | 等级 |
| --- | --- | --- | --- |
| 无账号、无广告、无需联网；窗口参数与快捷键成体系 | scrcpy | README user benefits；`doc/window.md`、`doc/shortcuts.md` | A |
| 30～120fps、35～70ms 延迟、约 1 秒首帧 | scrcpy | README 自述 | B（性能数值为作者自述，未独立复测） |
| 连接前配置、设备刷新/启停、多设备群控、按键映射 | QtScrcpy | README「界面按钮介绍」 | A |
| 作者另开发 QuickMirror（批量投屏、分组、按键映射可视化） | QtScrcpy | README 顶部作者自述 | B（作者自述，未进入本产品调研） |
| 浏览器零安装、整屏/单窗口、翻转模式、二维码与地址 | deskreen | README / 官网 | A |
| 约 210MB 磁盘、250MB 内存（每路 +100MB） | deskreen | 官网自述 | B（未独立复测） |
| 自动发现、GUI/CLI/Web UI、字幕、播放列表 | go2tv | README Features | A |
| 一次性 PIN、白名单、服务名、封面艺术、端口排障 | UxPlay | README 启动选项与排障段 | A |

清洗动作：

1. 删除无来源的形容词结论（如「体验最好」「用户普遍抱怨」），只保留文档明示事实。
2. 性能数值、资源占用统一标注「作者/官网自述」，不与实测数据混排。
3. 所有「启示」与「不可照搬」保留，但明确是**归纳**（§3.5 原文已声明），不作为竞品事实引用。

## 四、用户反馈与使用摩擦（严格分离）

| 类型 | 内容 | 状态 |
| --- | --- | --- |
| 公开用户评价 | 原 §3.4 的 deskreen「Windows 笔记本作 Linux 第二屏，约 5 分钟配置」中文转述 | **降级为不可引用**：2026-11-09 复核官网，栏目名为「What Others Say About Deskreen」，只有「Watch video demos / Find out more what people say on Reddit」入口，无该条评价原文（详见 §六） |
| 文档明示的使用摩擦 | UxPlay 终端启动/端口排障；go2tv CLI 先扫描再 `-t`；scrcpy 快捷键需查文档 | 保留（README 自述，非用户反馈） |
| issue / 商店评论 | 本轮未抓取 | 保持「不编造」，不得补写 |

结论：清洗后**可引用的公开用户反馈为 0 条**；任何「用户抱怨/用户普遍反馈」表述一律禁止。

## 五、许可边界核对

| 项目 | 现状 | 判定 |
| --- | --- | --- |
| 本仓许可证 | `LICENSE` = Apache-2.0 | 与主 PRD Q-2 一致 |
| 参考清单 | `NOTICE` 列 7 个参考项目，声明不链接、不分发 | 结构合规 |
| GPL/AGPL | UxPlay（GPL-3.0）、deskreen（AGPL-3.0） | 只参考交互/协议，不得链接或复制 |
| Apache/MIT | scrcpy、QtScrcpy（Apache-2.0）、go2tv（MIT） | 可引入，须保留版权声明与许可副本 |
| 地址一致性 | `NOTICE` 写 `antimof/UxPlay`；专项 PRD §3.2 写当前地址 `FDH2/UxPlay` | **不一致**，需订正（见 §六） |

## 六、清洗发现的问题（本单不改正文）

| # | 问题 | 证据 | 处置 |
| --- | --- | --- | --- |
| 1 | §3.5 称「Deskreen 页面用 `#0b1f3a` / `#12314f`」 | 2026-11-09 抓取 `deskreen.com` 的 HTML 与 3 个 CSS chunk，均无 `0b1f3a` / `12314f`；而本仓访客页正好使用这两个值（`BrowserCastServer.kt` L209/L213） | 高度疑似把本仓取值误记为竞品；应从「竞品惯例」移除或改注为本仓现状。已建派生单 T04880 |
| 2 | §3.4 的 deskreen 公开评价无法复核 | 官网当前仅有「What Others Say」入口与 Reddit 链接，无评价原文 | 降级为不可引用；如保留须补 Reddit 原文链接。已建派生单 T04880 |
| 3 | `NOTICE` 的 UxPlay 地址为旧仓 `antimof/UxPlay` | 专项 PRD §3.2 注记当前地址 `FDH2/UxPlay` | 统一地址并保留旧地址注记。已建派生单 T04880 |

## 七、仍不可复核 / 快照项（明确标注，不当作事实结论）

- 5 个仓库的 star 数与最近推送日期：2026-11-08 快照，本轮未重新抓取。
- scrcpy 延迟/帧率、deskreen 磁盘与内存占用：作者/官网自述，未独立复测。
- QtScrcpy 作者关于 QuickMirror 的自述：未进入本产品调研，不据此评估。
- 「均为 2026-11-08 实测存在」：保留原调研记录，本轮只复核了 deskreen 官网可访问。

## 八、复验命令

```bash
# 14 个同类实现与 5 个深挖对象
sed -n "$(grep -n '### 12.2' AirMirror-PRD-v1.1.md | head -1 | cut -d: -f1),+22p" AirMirror-PRD-v1.1.md
sed -n '111,126p' AirMirror-体验与UI优化专项PRD-竞品调研-v1.0.md

# 许可边界
head -20 NOTICE; head -3 LICENSE

# Deskreen 取色与评价入口（外部复核）
curl -L --max-time 30 -s https://deskreen.com/ | grep -oiE "0b1f3a|12314f|what others say" | sort -u
for u in babbcc4fca2118e6 787e6e58353906e4 b80b424ab487081c; do curl -L --max-time 30 -s "https://deskreen.com/_next/static/chunks/$u.css" | grep -oiE "0b1f3a|12314f"; done

# 本仓访客页取值（对照误记来源）
grep -n "0b1f3a\|12314f" app/src/main/java/io/github/wangnan05563/airmirror/web/BrowserCastServer.kt
```
