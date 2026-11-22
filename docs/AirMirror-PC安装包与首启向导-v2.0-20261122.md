# AirMirror PC 安装包与首启向导 v2.0（T06111 / REQ-004 / AC-04）

| 项 | 内容 |
| --- | --- |
| 任务 | T06111 [PRD] 构建 PC 安装脚本 |
| 需求 | REQ-004：mDNS 自动发现、二维码/投屏码配对、记忆设备；交付 ZIP+脚本（无签名时），含首启向导；Win10/11 验证 |
| 验收 | AC-04：Win 安装 ≤2min 首投；同网发现 ≤3s；卸载无残留 |
| 产物 | `release/release/AirMirror-PC-0.1.0-m0.20261122-003-win-x64.zip`，36,863,225 B，SHA-256 `a7f37ebb5239d7794194d61e5c80e8ee7b39ca117d4528f93d63374696ec1800` |
| 未签名 | 按 REQ-004 只发 ZIP+PowerShell 脚本，不伪造代码签名 EXE |

## 01 结论先行

1. PC 包已能解压即用，也可一条 PowerShell 命令安装到当前用户；包内带精简 JRE 17，不依赖系统 Java。
2. 安装/卸载/防火墙脚本全部幂等；卸载默认保留配置，`-RemoveConfig` 才彻底删除。
3. 首启向导已落到代码：四步、可跳过、完成状态只在首帧发出后写 true。
4. Win10 已实测：静默安装 **40.6 秒**（另一次 53.9 秒）、卸载 **80.4 秒**（另一次 36.8 秒）、防火墙 0→2→0、快捷方式/卸载项安装后可清空。
5. AC-04 的首投 ≤2min 和同网发现 ≤3s 仍受“无在线接收端/无真实电视”限制，不冒充通过；现场项分别由 T06115 与 T06128 补测。

## 02 包结构

```text
AirMirror-PC/
├─ AirMirror-PC.bat          # 解压即用入口，优先包内 runtime
├─ install.ps1               # 当前用户安装 / -Silent
├─ app/
│  ├─ lib/                   # pc-client.jar + 依赖
│  ├─ runtime/               # jlink 精简 JRE 17（java.desktop 等）
│  ├─ pc-client              # Gradle 原始启动脚本（保留备用）
│  └─ pc-client.bat
├─ ffmpeg/                   # 可选；未传 -FfmpegDir 时为空目录
├─ scripts/
│  ├─ install.ps1
│  ├─ uninstall.ps1
│  ├─ fix-firewall.ps1
│  └─ remove-firewall.ps1
├─ README.txt
├─ VERSION
└─ BUILD-INFO.json
```

`AirMirror-PC.bat` 先 `pushd` 到包根，保证 `scripts\` 与 `ffmpeg\` 相对路径稳定；有 `app\runtime\bin\javaw.exe` 用包内 JRE，否则回退 PATH 上的 `javaw.exe`。

## 03 安装/卸载契约

### 3.1 install.ps1

- 默认目录：`%LOCALAPPDATA%\Programs\AirMirror-PC`，不需要管理员。
- `-Silent`：覆盖安装、不询问、不创建桌面快捷方式；适合无人值守。
- `-NoShortcuts` / `-NoRegistry`：测试/绿色部署用，不碰开始菜单和 HKCU。
- `-ConfigureFirewall`：安装后尝试创建防火墙规则；无管理员只警告，不阻断安装。
- `-DesktopShortcut`：显式创建桌面快捷方式。
- 安装后运行 `--self-check`，验证协议、ZXing、摄像头类和包内 JRE 均可加载；失败即退出非 0。

安装项：

| 项 | 默认 | 说明 |
| --- | --- | --- |
| 程序目录 | 当前用户 LocalAppData | 不写 Program Files，不请求管理员 |
| 开始菜单快捷方式 | 创建 | `AirMirror PC.lnk` |
| 桌面快捷方式 | 不创建 | 仅在 `-DesktopShortcut` 时创建 |
| HKCU 卸载项 | 创建 | DisplayName/Version/UninstallString/QuietUninstallString |
| 防火墙规则 | 不自动创建 | 向导第 2 步或 `-ConfigureFirewall` 显式触发 |
| 配置 | 不动 | `%APPDATA%\AirMirror` 与 `%APPDATA%\AirMirrorPC` |

### 3.2 uninstall.ps1

顺序：停止本安装目录的 Java 进程 → 删除防火墙规则 → 删快捷方式 → 删 HKCU 卸载项 → 删程序目录。

- 默认保留 `%APPDATA%\AirMirror`（向导状态/最近设备）与 `%APPDATA%\AirMirrorPC`（设备标识）。
- `-RemoveConfig` 删除上述两个配置目录；`-KeepConfig` 显式保留。
- 无管理员时防火墙删除返回 5，卸载继续，不阻断；程序目录与快捷方式仍清理。
- 删除程序目录用 `cmd /c rmdir /s /q`，避免 PowerShell 递归删除 jlink 小文件过慢。

## 04 防火墙脚本

固定两条规则，只放行专用网络：

| DisplayName | 方向 | 协议 | 端口 |
| --- | --- | --- | --- |
| `AirMirror PC (TCP 41523)` | Inbound | TCP | 41523 |
| `AirMirror PC (UDP 41524-41525)` | Inbound | UDP | 41524-41525 |

- `fix-firewall.ps1`：先删同名再建，幂等；`-Check` 只读输出 `ADMIN=`/`RULES=`/`DETAIL=`，供向导解析；`-DryRun` 只打印计划。
- `remove-firewall.ps1`：按 DisplayName 精确删除；`-DryRun` 打印待删规则；无规则视为成功。
- 两个脚本都检查管理员；无管理员时 `fix` 退出 5，`remove` 也退出 5，调用方按“提示不阻断”处理。
- 不改公用网络、不改域网络、不加自启。

## 05 首启向导

新增 `Onboarding.kt`：

- 状态文件：`%APPDATA%\AirMirror\onboarding.json`，原子写；字段 `completed/skipped/networkStep/firewallStep/lastDeviceId/lastDeviceName/lastHost/lastPort/firstFrameAt`。
- 首次判定：`!completed && !skipped`；用户点“先跳过”写 `skipped=true`，下次不自动弹，主界面保留“设置向导”按钮。
- 第 1 步网络：枚举非回环 IPv4 网卡，默认选第一张；只有回环/无网络给出对应文案；VPN 网卡给黄色警告。
- 第 2 步防火墙：调用 `scripts\fix-firewall.ps1 -Check`，读 `RULES`；缺规则时可“以管理员运行修复”，走 UAC，不直接提权。
- 第 3 步发现：启动 `MdnsDiscovery`，3 秒无结果转手动 IP；多台必须手动选择。
- 第 4 步投屏：可输入 6 位码或扫码；点击“开始投屏”只发起连接，**首帧真正发出后**由 `SenderEngine.onFirstFrame` 写 `completed=true`；没有首帧不会误记完成。
- 向导关闭/跳过会停止自己的 mDNS，不影响主界面发现。

## 06 发布链路

`scripts/release.ps1` 已接入 PC 包：

- 默认在 Android 产物后执行 `scripts/package-pc.ps1 -VersionPatch <tagPatch>`。
- PC ZIP 与 APK 一起进入 release record、GitHub Release 资产；`-SkipPc` 可显式跳过。
- `package-pc.ps1` 选 JDK 时按 `android-tools.config.json` 的 17–22 区间筛选，不再盲用环境 `JAVA_HOME`。
- 发布记录里的 `Artifacts` 同时含 `.apk` 与 `AirMirror-PC-*.zip`。

## 07 验证证据

环境：Microsoft Windows 10 企业版，`10.0.19045`，x64；JDK 17.0.12（包内 jlink runtime 同版本）。

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| PC JVM 单测 | **19 / 19 通过** | `release/build/pc-client/test-results/test`；新增 `OnboardingStateTest` 3 项 |
| 向导状态往返 | 通过 | 中文/引号/反斜杠 JSON 往返、损坏文件回退、skip/complete 判定 |
| ZIP 布局 | 通过 | 11 个必需条目全部存在：入口、安装脚本、lib、runtime java/javaw、三份脚本、README/VERSION/BUILD-INFO |
| 静默安装 | **40,593 ms** | 解压后 `install.ps1 -Silent -NoShortcuts -NoRegistry`，含包内运行时 self-check；另一次 53,938 ms |
| 包内运行时自检 | 通过 | `AIRMIRROR_PC_SELF_CHECK_OK version=17.0.12 ... os=Windows 10 arch=amd64` |
| 完整安装项 | 通过 | 安装目录=True、开始菜单快捷方式=True、HKCU 卸载项=True |
| 卸载 | **80,390 ms；程序目录残留=False** | 完整安装后 `uninstall.ps1 -Silent`；另一次 36,785 ms；快捷方式=False、卸载项=False |
| 防火墙创建 | 通过 | 0 → 2 条；再次执行仍 2 条（幂等）；规则名/端口/Private 全部命中 |
| 防火墙删除 | 通过 | `remove-firewall.ps1` 后 2 → 0；无同名残留 |
| 便携包 | 36,863,225 B | SHA-256 `a7f37ebb5239d7794194d61e5c80e8ee7b39ca117d4528f93d63374696ec1800` |
| 安装 ≤2min | **通过（40.6s；最慢 53.9s）** | 仅覆盖安装阶段；首次投屏计时需 T06115 |
| 同网发现 ≤3s | 未实测 | 当前无接收端广播；T06128 现场项 |
| Win11 | 未实测 | 脚本只用 Win10/11 共有 `NetSecurity`、`robocopy`、HKCU；T06115 需补 Win11 实机 |
| 首帧/首次投屏 | 未实测 | 无在线接收端；T06115 |

### 7.1 两个实测失败与修复

1. **JDK 选择错误**：首轮 `package-pc.ps1` 直接取环境 `JAVA_HOME`，命中 JDK 25，Gradle 报 `25.0.1` 后失败。修复：按 `android-tools.config.json` 的 17–22 区间逐个探测候选，命中 Zulu 17.0.12。
2. **安装耗时 244.5s 超 2min**：`install.ps1` 用 `Copy-Item` 递归复制 jlink 运行时数千个小文件。改为 `robocopy /E /MT:8 /R:1 /W:1` 后降到 **40.6s（另一次 53.9s）**；卸载同步改用 `rmdir /s /q`，从 88.5s 降到 **36.8–80.4s**（受磁盘/安全软件影响）。

## 08 未验证项与移交

| 项 | 原因 | 移交 |
| --- | --- | --- |
| PC↔TV 首次投屏、首帧时间 | 当前无在线接收端 | T06115 |
| 摄像头真机扫码 | 当前无摄像头 | T06115 |
| 真实 Wi‑Fi 首台发现 ≤3s | 无广播接收端 | T06128 |
| Win11 实机 | 当前仅 Win10 19045 | T06115 |
| AP 隔离 | 当前无可控路由器 | T06129 |
| FFmpeg 实际打包 | 本轮未提供 FFmpeg 目录，`ffmpeg/` 为空；JCodec 兜底可用 | 后续传 `-FfmpegDir` 再打包 |

## 09 文件清单

- `scripts/package-pc.ps1`：构建 staging、jlink runtime、压缩 ZIP、输出大小/SHA。
- `scripts/install.ps1`：便携/静默安装、快捷方式、HKCU 卸载项、self-check。
- `scripts/uninstall.ps1`：停进程、清规则/快捷方式/卸载项/程序目录，配置可选删除。
- `scripts/fix-firewall.ps1`：幂等创建 + `-Check`/`-DryRun`。
- `scripts/remove-firewall.ps1`：按固定 DisplayName 删除。
- `scripts/test-pc-package.ps1`：ZIP 布局 → 静默安装 → self-check → 防火墙 dry-run → 卸载，输出 `build/pc-package-test-result.json`。
- `pc-client/src/main/kotlin/.../Onboarding.kt`：向导状态存储 + 四步 Swing 向导。
- `pc-client/src/main/kotlin/.../Main.kt`：向导入口、扫码入口、`--self-check`、首帧完成回调。
- `pc-client/src/main/kotlin/.../SenderEngine.kt`：`onFirstFrame` 回调（FFmpeg/JCodec 两条路径共用）。
- `pc-client/src/test/kotlin/.../OnboardingStateTest.kt`：3 项状态测试。
- `scripts/release.ps1`：PC ZIP 纳入发布资产（`-SkipPc` 可跳过）。
