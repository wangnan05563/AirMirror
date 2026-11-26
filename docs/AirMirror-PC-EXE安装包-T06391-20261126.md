# AirMirror PC setup EXE（T06391，2026-11-26）

| 项 | 内容 |
| --- | --- |
| 任务 | T06391 修复 AirMirror 三期构建脚本缺失 PC 版 EXE 安装包 |
| 根因 | `package-pc.ps1` 只生成便携 ZIP，没有调用 NSIS；不是构建异常被吞。原 PRD 因无代码签名证书只承诺 ZIP，但没有把“可选未签名 setup”纳入构建链 |
| 产物 | `AirMirror-PC-0.1.0-m0.20261126-001-win-x64-setup.exe`（未签名 NSIS 安装器） |
| 说明 | 不伪造签名、不改系统安全策略；SmartScreen 仍会提示，签名 EXE 仍需正式证书 |

## 改动

1. 新增 `scripts/AirMirror-PC-installer.nsi`：NSIS 3 Unicode 安装器，按当前用户安装到 `%LOCALAPPDATA%\Programs\AirMirror-PC`，复制现有便携载荷（含 jlink JRE）、创建开始菜单快捷方式、写 HKCU 卸载项与 `Uninstall.exe`；卸载默认保留 `%APPDATA%\AirMirror` 配置。
2. `scripts/package-pc.ps1`：ZIP 生成后自动查找 NSIS `makensis.exe` 并编译
   `AirMirror-PC-<version>-win-x64-setup.exe`；NSIS 缺失时显式失败，支持 `-SkipInstaller` 跳过。
3. `scripts/release.ps1`：PC 产物校验与 Release 资产扩展为 ZIP + setup EXE；`-SkipBuild` 新鲜度校验同时覆盖两个文件。

## 复现

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\package-pc.ps1 -VersionPatch 20261126-001 -SkipBuild
```

输出目录 `release\release\` 同时得到 ZIP 与 setup EXE。

## 验证

- NSIS 3.12 编译成功，退出码 0。
- 静默安装：`setup.exe /S /D=C:\Temp\AirMirrorNsisTest`，退出码 0；安装后存在 `app\runtime\bin\javaw.exe`、`AirMirror-PC.bat`、`Uninstall.exe`。
- 注册表：`HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\AirMirrorPC` 的 DisplayName=`AirMirror PC`、DisplayVersion=`0.1.0-m0.20261126-001`、InstallLocation=`C:\Temp\AirMirrorNsisTest`。
- 静默卸载 `Uninstall.exe /S`：退出码 0；安装目录、卸载注册表项、开始菜单目录均不存在。
- ZIP 仍正常生成：38,392,795 B，SHA-256 `bfee7ff31497604f242238b94846cda75003bd28c509a9d936923812838db7e5`。
- setup EXE：35,390,729 B，SHA-256 `3fb2f339e780e0ca44855834d191217f840b308542aefc30b819027acf42ec98`；`Get-AuthenticodeSignature` 为 NotSigned，符合当前无证书状态。

## 验证缺口

- 未在真实 Windows 10/11 干净机器上做首次投屏 ≤2 分钟；该现场项仍归 T06115。
- 未生成代码签名；正式签名 EXE 仍是证书到位后的条件项。
