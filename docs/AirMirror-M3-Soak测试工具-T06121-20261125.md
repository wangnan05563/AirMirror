# AirMirror T06121 M3 Soak 工具与现场核查（2026-11-25）

## 01 范围与结论

T06121《M3 音频与性能测试》要求 30 分钟 1080p60 运行、A/V 偏差与丢帧率、Win10/11 对比，并以 A/B/C 级 30 分钟 soak 作为 AC-09 完成条件。当前现场仍只有一台 720×1280 / API 34 / 3GB 的 `emulator-5554`，没有 A/B/C 代表设备，也没有真实 1080p60 同网链路和外部 A/V 偏差测量条件，因此本轮**不能执行或声明 AC-09 通过**。

本轮交付的是可复用的 soak 采样工具及其冒烟证据，用来把“30 分钟采样”从手工操作变成可复核的 CSV/JSONL：

- 每 5 分钟采 PSS/Java/Native/Graphics、CPU、电池温度；
- 全程抓 logcat，保留崩溃、断流、重连证据；
- 结束后通过 `run-as` 拉取接收端 `files/perf/*.jsonl`；
- 汇总首帧、P90/P95、偏差、丢帧、音频中断与 PSS 增长/最后 10 分钟斜率；
- 用 `-DryRun` 先做设备/包预检，避免跑到一半才发现包未安装或 `run-as` 不可用。

T06121 在 MTask 中仍未 done；本轮只把执行会话置为 running，完成工具级闭环。

## 02 新增文件

| 文件 | 作用 |
| --- | --- |
| `tools/soak/soak-android.ps1` | Android soak 采样器：预检、5 分钟采样、logcat、perf 拉取、汇总调用 |
| `tools/soak/summarize_soak.py` | 解析 `samples.csv` + `perf.jsonl`，输出 `summary.json` / `summary.md` |
| `tools/soak/test_summarize_soak.py` | 5 项纯 Python 单测：最后会话、坏行、PSS 增长/斜率、缺样本、SLO 布尔项 |
| `tools/soak/README.md` | 30 分钟、工具冒烟、直接汇总与边界说明 |

采样器只读设备状态，不启动投屏、不配对、不修改分辨率或网络。投屏必须由人工先开始，避免脚本替用户点按或把空载当成投屏会话。

## 03 工具验证

| 项 | 结果 | 证据 |
| --- | --- | --- |
| 预检 DryRun | `emulator-5554` / `sdk_gphone64_x86_64` / API 34 / 720×1280；包 `com.android.settings` 可解析 | `docs/assets/t06121-20261125/dryrun/preflight.txt` |
| 1 分钟冒烟 | 3 个样本，PSS 有效 3/3；PSS 48,105 → 46,194 kB；CPU 均值 0.05%、峰值 0.1%；电池温度 25°C | `docs/assets/t06121-20261125/smoke2/{samples.csv,summary.json,summary.md}` |
| 标签与 run-as 失败路径 | `-Label T06121-tool-smoke` 写入 summary；`com.android.settings` 非 debuggable 时正确写 `perf-pull.txt`，不伪造 perf | `docs/assets/t06121-20261125/smoke3/{summary.md,perf-pull.txt,samples.csv}` |
| 首次冒烟异常 | 第一次 1 分钟冒烟在 30 秒时遇到模拟器 `DEAD_OBJECT`/device offline，未把该次结果计入；第二次运行稳定 | 首次目录 `smoke/` 保留原始现场，第二次为 `smoke2/` |
| Python 单测 | 5 项全过，覆盖最后会话、坏行、PSS 增长/斜率、缺样本、首帧/P95/丢帧布尔项 | `docs/assets/t06121-20261125/python-unittest.txt` |
| PowerShell 语法/执行 | `pwsh -NoProfile -File tools/soak/soak-android.ps1 ... -DryRun` 与 1 分钟采样均返回 0 | 同上 |

冒烟使用的是 `com.android.settings`，只验证采样器和汇总链路；它**不是 AirMirror 投屏会话**，没有 perf JSONL，`summary` 的 `verdict=insufficient` 是预期结果。`run-as` 拉取路径尚未在已安装 AirMirror debug 包的设备上实测，报告不把它写成已验证。

## 04 与 SLO v2.0 的对应

| SLO | 工具覆盖 | 仍缺 |
| --- | --- | --- |
| SLO-10 内存 | 每 5 分钟 PSS；`summary` 给出 +80MB 与最后 10 分钟斜率布尔参考 | 需要真实 30 分钟投屏会话；当前冒烟只有 60 秒 |
| SLO-11 soak | 30 分钟采样、logcat、断流/崩溃证据 | 需 A/B/C 代表设备与真实 1080p60 链路 |
| SLO-06/07 延迟 | `perf.jsonl` 的 P90/P95/drift 汇总 | 是接收端端内指标，不是玻璃到玻璃测量；需外部 A/V 测量 |
| SLO-08 丢帧 | `perf.jsonl` 的 dropped/received 汇总 | 需真实 1080p60 ≥10 分钟窗口；当前未采到 AirMirror 会话 |

AC-03 的“音画偏差 ≤80ms、静音切换 ≤1s、DRM 明确提示”仍需要外部音画测量与真实 DRM 内容；本轮不因工具能汇总 `drift_ms` 就宣称 AC-03 通过。

## 05 未验证与阻塞

1. **无 A/B/C 设备**：当前只有 `emulator-5554`，720×1280、API 34、3GB；不能替代 A/B/C 档。
2. **无 1080p60 同网链路**：模拟器分辨率与网络条件不满足；未执行 30 分钟 1080p60。
3. **无 AirMirror debug 包实测**：当前 adb 包列表没有 `io.github.wangnan05563.airmirror`；`run-as` 拉取只做了代码路径和合成 JSONL 单测，未在真实设备上拉取。
4. **无外部音画测量**：`drift_ms` 是接收端内部同步偏差，不是屏幕与扬声器的玻璃到玻璃差值。
5. **PC 端未确认可运行**：`pc-client` 当前构建产物与可执行文件未在本轮重新核对，Win10/11 对比未开始。
6. **首次冒烟模拟器不稳**：第一次 1 分钟采样在 30 秒遇到 `DEAD_OBJECT`，说明该 3GB 模拟器不适合作为长时稳定性证据；第二次稳定只证明脚本能连续采样 60 秒。

## 06 下一轮

- 设备到位后，在 A/B/C 各一台代表机上安装同一 debug 包，人工开始 1080p60 投屏，再运行：
  `pwsh -File tools/soak/soak-android.ps1 -Serial <serial> -DurationMinutes 30 -IntervalSeconds 300 -OutDir docs/assets/slo-T06121-<tier>`
- 同时布置外部 A/V 测量（摄像头拍屏幕+录扬声器，或经校准的采集卡），补 AC-03 的 ≤80ms 与静音 ≤1s。
- 对 Win10/Win11 各跑一次 PC 发送端 + 同一接收端，记录系统版本、网卡、路由与 5GHz 信道。
- 只有三档 30 分钟 soak、外部音画测量和现场记录齐全后，才把 T06121 与 AC-09 收口；当前保持阻塞。
