#!/usr/bin/env python3
"""汇总 soak 采样 CSV 与 PerfTrace JSONL，输出 JSON/Markdown。

只做可复核的算术汇总，不判断设备档或 AC-09 是否通过。PSS 斜率用最后 10 分钟
的最小二乘拟合（KB/min），与 SLO v2.0 的“最后 10 分钟斜率 ≤2MB/min”对齐。
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import statistics
from datetime import datetime, timezone
from typing import Any


UNKNOWN = -1


def as_long(value: Any) -> int:
    try:
        return int(str(value))
    except (TypeError, ValueError):
        return UNKNOWN


def as_int(value: Any) -> int:
    try:
        return int(str(value))
    except (TypeError, ValueError):
        return 0


def load_perf(path: str | None) -> dict[str, Any]:
    if not path or not os.path.exists(path):
        return {"available": False, "session_id": "", "reason": "没有 perf.jsonl"}

    events: list[dict[str, Any]] = []
    bad_lines = 0
    with open(path, "r", encoding="utf-8-sig", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                bad_lines += 1
                continue
            if isinstance(obj, dict):
                events.append(obj)
            else:
                bad_lines += 1

    target = ""
    for event in events:
        sid = str(event.get("s") or "")
        if sid:
            target = sid
    if not target:
        return {
            "available": False,
            "session_id": "",
            "events": len(events),
            "bad_lines": bad_lines,
            "reason": "没有会话 id",
        }

    started_at = 0
    ended_at = 0
    summary: dict[str, Any] = {
        "session_id": target,
        "encrypted": False,
        "received": 0,
        "decoded": 0,
        "rendered": 0,
        "audio_frames": 0,
        "first_frame_auth_ms": UNKNOWN,
        "first_frame_config_ms": UNKNOWN,
        "codec": "",
        "width": 0,
        "height": 0,
        "audio_sample_rate": 0,
        "audio_channels": 0,
        "audio_codec": "",
        "p90_ms": UNKNOWN,
        "p95_ms": UNKNOWN,
        "drift_ms": 0,
        "dropped": 0,
        "audio_gaps": 0,
        "silent_gaps": 0,
    }

    for event in events:
        if str(event.get("s") or "") != target:
            continue
        t = as_long(event.get("t"))
        ended_at = max(ended_at, t)
        name = str(event.get("e") or "")
        values = event.get("v") or {}
        if not isinstance(values, dict):
            values = {}
        if name == "session_start":
            started_at = t
            summary["encrypted"] = str(values.get("encrypted", "")).lower() == "true"
        elif name == "session_end":
            summary["received"] = max(summary["received"], as_long(values.get("received")))
            summary["decoded"] = max(summary["decoded"], as_long(values.get("decoded")))
            summary["audio_frames"] = max(summary["audio_frames"], as_long(values.get("audio_frames")))
        elif name == "config":
            summary["codec"] = str(values.get("codec") or "")
            summary["width"] = as_int(values.get("width"))
            summary["height"] = as_int(values.get("height"))
        elif name == "first_frame":
            summary["first_frame_auth_ms"] = as_long(values.get("auth_ms"))
            summary["first_frame_config_ms"] = as_long(values.get("config_ms"))
        elif name == "metrics":
            summary["p90_ms"] = as_long(values.get("p90_ms"))
            summary["p95_ms"] = as_long(values.get("p95_ms"))
            summary["drift_ms"] = as_long(values.get("drift_ms"))
            summary["received"] = max(summary["received"], as_long(values.get("received")))
            summary["decoded"] = max(summary["decoded"], as_long(values.get("decoded")))
            summary["rendered"] = max(summary["rendered"], as_long(values.get("rendered")))
            summary["dropped"] = max(summary["dropped"], as_long(values.get("dropped")))
            summary["audio_frames"] = max(summary["audio_frames"], as_long(values.get("audio_frames")))
        elif name == "drop":
            until_key = as_long(values.get("until_key"))
            late = as_long(values.get("late"))
            if until_key >= 0 and late >= 0:
                summary["dropped"] = max(summary["dropped"], until_key + late)
        elif name == "audio_open":
            summary["audio_sample_rate"] = as_int(values.get("sr"))
            summary["audio_channels"] = as_int(values.get("ch"))
            summary["audio_codec"] = str(values.get("codec") or "")
        elif name == "audio_gap":
            summary["audio_gaps"] += 1
            if str(values.get("reason") or "") == "silent":
                summary["silent_gaps"] += 1

    summary["started_at_ms"] = started_at
    summary["ended_at_ms"] = ended_at
    summary["duration_ms"] = max(0, ended_at - started_at)
    summary["drop_rate_percent"] = (
        summary["dropped"] * 100.0 / summary["received"] if summary["received"] > 0 else -1.0
    )
    summary["available"] = True
    summary["events"] = len(events)
    summary["bad_lines"] = bad_lines
    return summary


def load_samples(path: str | None) -> dict[str, Any]:
    if not path or not os.path.exists(path):
        return {"available": False, "rows": 0}

    rows: list[dict[str, Any]] = []
    with open(path, "r", encoding="utf-8-sig", newline="") as fh:
        for row in csv.DictReader(fh):
            def num(key: str, cast=float):
                value = (row.get(key) or "").strip()
                if not value:
                    return None
                try:
                    return cast(float(value))
                except ValueError:
                    return None

            rows.append({
                "sample": num("sample", int),
                "elapsed_s": num("elapsed_s"),
                "pid": (row.get("pid") or "").strip(),
                "pss_total_kb": num("pss_total_kb"),
                "java_heap_kb": num("java_heap_kb"),
                "native_heap_kb": num("native_heap_kb"),
                "graphics_kb": num("graphics_kb"),
                "cpu_pct": num("cpu_pct"),
                "battery_temp_c": num("battery_temp_c"),
            })

    valid = [r for r in rows if r["pss_total_kb"] is not None]
    if not valid:
        return {"available": False, "rows": len(rows), "reason": "没有有效 PSS 样本"}

    durations = [r["elapsed_s"] for r in valid if r["elapsed_s"] is not None]
    elapsed_s = max(durations) if durations else 0
    baseline = valid[0]["pss_total_kb"]
    final = valid[-1]["pss_total_kb"]
    peak = max(r["pss_total_kb"] for r in valid)

    recent = [
        r for r in valid
        if r["elapsed_s"] is not None
        and r["pss_total_kb"] is not None
        and r["elapsed_s"] >= elapsed_s - 600
    ]
    slope = None
    if len(recent) >= 2:
        xs = [r["elapsed_s"] / 60.0 for r in recent]
        ys = [r["pss_total_kb"] for r in recent]
        x_mean = statistics.fmean(xs)
        y_mean = statistics.fmean(ys)
        denominator = sum((x - x_mean) ** 2 for x in xs)
        if denominator > 0:
            slope = sum((x - x_mean) * (y - y_mean) for x, y in zip(xs, ys)) / denominator

    cpu_values = [r["cpu_pct"] for r in valid if r["cpu_pct"] is not None]
    temps = [r["battery_temp_c"] for r in valid if r["battery_temp_c"] is not None]

    return {
        "available": True,
        "rows": len(rows),
        "valid_pss_rows": len(valid),
        "duration_s": elapsed_s,
        "pss_baseline_kb": baseline,
        "pss_final_kb": final,
        "pss_peak_kb": peak,
        "pss_growth_kb": final - baseline,
        "pss_growth_mb": round((final - baseline) / 1024.0, 2),
        "last10_slope_kb_per_min": round(slope, 1) if slope is not None else None,
        "cpu_avg_pct": round(statistics.fmean(cpu_values), 2) if cpu_values else None,
        "cpu_max_pct": round(max(cpu_values), 2) if cpu_values else None,
        "battery_temp_max_c": round(max(temps), 1) if temps else None,
        "checks": {
            "pss_growth_le_80mb": (final - baseline) <= 80 * 1024,
            "last10_slope_le_2mb_min": slope <= 2 * 1024 if slope is not None else None,
        },
    }


def build_summary(samples_path: str, perf_path: str | None, label: str) -> dict[str, Any]:
    samples = load_samples(samples_path)
    perf = load_perf(perf_path)
    checks: dict[str, Any] = {}
    if perf.get("available"):
        p95 = perf.get("p95_ms", UNKNOWN)
        first_frame = perf.get("first_frame_auth_ms", UNKNOWN)
        drop_rate = perf.get("drop_rate_percent", -1)
        checks = {
            "first_frame_le_3s": first_frame <= 3000 if first_frame >= 0 else None,
            "p95_le_200ms": p95 <= 200 if p95 >= 0 else None,
            "drop_rate_le_1pct": drop_rate <= 1.0 if drop_rate >= 0 else None,
        }
    return {
        "generated_at": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "label": label,
        "samples": samples,
        "perf": perf,
        "checks": checks,
        "verdict": "metrics_collected" if perf.get("available") and samples.get("available") else "insufficient",
        "notes": [
            "本摘要只汇总本机采样与 perf JSONL，不代表 AC-09 通过。",
            "AC-09 还需 A/B/C 代表设备、真实 1080p60 链路、音画偏差测量和现场操作记录。",
        ],
    }


def to_markdown(summary: dict[str, Any]) -> str:
    samples = summary["samples"]
    perf = summary["perf"]
    lines = [
        "# Android Soak 摘要",
        "",
        f"- 生成时间：{summary['generated_at']}",
        f"- 标签：{summary['label']}",
        f"- 结论：`{summary['verdict']}`（仅表示数据是否采到，不是 AC-09 判定）",
        "",
        "## 采样",
    ]
    if samples.get("available"):
        checks = samples.get("checks", {})
        lines.extend([
            f"- 采样行：{samples['rows']}（有效 PSS {samples['valid_pss_rows']}）",
            f"- 时长：{samples['duration_s']}s",
            f"- PSS 基线/结束/峰值：{samples['pss_baseline_kb']} / {samples['pss_final_kb']} / {samples['pss_peak_kb']} kB",
            f"- PSS 增长：{samples['pss_growth_mb']} MiB（≤80MB：{checks.get('pss_growth_le_80mb')}）",
            f"- 最后 10 分钟斜率：{samples['last10_slope_kb_per_min']} kB/min（≤2048：{checks.get('last10_slope_le_2mb_min')}）",
            f"- CPU 均值/峰值：{samples['cpu_avg_pct']}% / {samples['cpu_max_pct']}%",
            f"- 电池温度峰值：{samples['battery_temp_max_c']}°C",
        ])
    else:
        lines.append(f"- 未采到有效样本：{samples.get('reason', '无数据')}")

    lines.extend(["", "## 最近一次投屏会话"])
    if perf.get("available"):
        checks = summary.get("checks", {})
        lines.extend([
            f"- 会话：`{perf['session_id']}`，时长 {perf['duration_ms']}ms，{perf['events']} 条事件，坏行 {perf['bad_lines']}",
            f"- 首帧：授权起 {perf['first_frame_auth_ms']}ms / 配置起 {perf['first_frame_config_ms']}ms（≤3s：{checks.get('first_frame_le_3s')}）",
            f"- 延迟：P90 {perf['p90_ms']}ms / P95 {perf['p95_ms']}ms，偏差 {perf['drift_ms']}ms（≤200ms：{checks.get('p95_le_200ms')}）",
            f"- 丢帧：{perf['dropped']} / 收 {perf['received']}（{perf['drop_rate_percent']:.2f}%）（≤1%：{checks.get('drop_rate_le_1pct')}）",
            f"- 音频：{perf['audio_sample_rate']}Hz/{perf['audio_channels']}ch {perf['audio_codec']}，{perf['audio_frames']} 帧，中断 {perf['audio_gaps']}（静音 {perf['silent_gaps']}）",
            f"- 画质：{perf['codec']} {perf['width']}x{perf['height']}，解码 {perf['decoded']} / 渲染 {perf['rendered']}",
        ])
    else:
        lines.append(f"- 未采到 perf 会话：{perf.get('reason', '无数据')}")

    lines.extend(["", "## 使用边界", ""] + [f"- {note}" for note in summary["notes"]])
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="汇总 Android soak 采样与 PerfTrace JSONL")
    parser.add_argument("--samples", required=True, help="samples.csv")
    parser.add_argument("--perf", help="perf.jsonl；缺省表示未拉取")
    parser.add_argument("--out", required=True, help="summary.json")
    parser.add_argument("--markdown", help="summary.md")
    parser.add_argument("--label", default="unlabeled", help="报告标签，例如 smoke / T06121-A")
    args = parser.parse_args()

    summary = build_summary(args.samples, args.perf, args.label)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(summary, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    if args.markdown:
        with open(args.markdown, "w", encoding="utf-8") as fh:
            fh.write(to_markdown(summary))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
