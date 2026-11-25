# Android Soak 采样器（T06121 / T06125）

用于 M3 性能验收的接收端采样，不启动投屏、不改设备设置。脚本按 SLO v2.0 每 5 分钟采一次 PSS/CPU/电池温度，全程抓 logcat，结束后用 `run-as` 拉取本机 `files/perf/*.jsonl` 并汇总最近一次会话。

## 前置条件

- Windows PowerShell 7。
- `adb` 在 PATH，或通过 `-Adb` 指定。
- 接收端已安装 debug 包（`run-as` 可用）：`io.github.wangnan05563.airmirror`。
- 人工先开始一次真实投屏；脚本只负责采样，不会替用户点按配对或启动投屏。

## 跑 30 分钟

```powershell
pwsh -File tools/soak/soak-android.ps1 `
  -Serial R5CR716VVEM `
  -DurationMinutes 30 `
  -IntervalSeconds 300 `
  -OutDir docs/assets/slo-T06121-A
```

跑完检查：

- `preflight.txt`：设备、系统、分辨率、包与 run-as 状态；
- `samples.csv`：PSS/CPU/温度时间序列；
- `logcat.txt`：应用崩溃、断流、重连日志；
- `perf.jsonl` / `summary.json` / `summary.md`：最近一次投屏会话的首帧、P90/P95、偏差、丢帧、音频中断；
- `snapshots/`：每次采样的 meminfo/cpuinfo/battery 原始输出。

## 只做工具冒烟

模拟器或非代表机可用于验证脚本本身，不能算 AC-09：

```powershell
pwsh -File tools/soak/soak-android.ps1 `
  -Serial emulator-5554 `
  -Package com.android.settings `
  -DurationMinutes 1 `
  -IntervalSeconds 30 `
  -OutDir docs/assets/t06121-smoke
```

## 直接汇总已有证据

```powershell
python tools/soak/summarize_soak.py `
  --samples docs/assets/slo-T06121-A/samples.csv `
  --perf docs/assets/slo-T06121-A/perf.jsonl `
  --out docs/assets/slo-T06121-A/summary.json `
  --markdown docs/assets/slo-T06121-A/summary.md `
  --label T06121-A
```

单元测试：

```powershell
python -m unittest tools/soak/test_summarize_soak.py -v
```

## 边界

- PSS 判定按“30 分钟后 ≤ 投屏前 +80MB，最后 10 分钟斜率 ≤2MB/min”给出布尔参考，不替 QA 下 AC-09 结论。
- P95/丢帧来自接收端本机 perf JSONL，是端内指标，不是玻璃到玻璃的音画偏差；AC-03 的 ≤80ms 仍需外部测量。
- release 包不可 `run-as` 时用 `-SkipPerfPull`，只能采 PSS/CPU/温度与 logcat。
