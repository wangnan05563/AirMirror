import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import summarize_soak as soak


class PerfSummaryTest(unittest.TestCase):
    def test_last_session_and_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "perf.jsonl")
            events = [
                {"s": "s-old", "t": "100", "e": "session_start", "v": {"encrypted": "false"}},
                {"s": "s-old", "t": "200", "e": "metrics", "v": {"p95_ms": "300"}},
                {"s": "s2", "t": "1000", "e": "session_start", "v": {"encrypted": "true"}},
                {"s": "s2", "t": "1100", "e": "config", "v": {"codec": "hevc", "width": "1920", "height": "1080"}},
                {"s": "s2", "t": "1200", "e": "first_frame", "v": {"auth_ms": "1800", "config_ms": "1200"}},
                {"s": "s2", "t": "1300", "e": "audio_open", "v": {"sr": "48000", "ch": "2", "codec": "opus"}},
                {"s": "s2", "t": "1400", "e": "audio_gap", "v": {"reason": "silent"}},
                {"s": "s2", "t": "1500", "e": "metrics", "v": {
                    "p90_ms": "150", "p95_ms": "180", "drift_ms": "42",
                    "received": "18000", "decoded": "17980", "rendered": "17950",
                    "dropped": "12", "audio_frames": "900",
                }},
                {"s": "s2", "t": "1600", "e": "session_end", "v": {
                    "received": "18000", "decoded": "17980", "audio_frames": "900",
                }},
                "not-json",
            ]
            with open(path, "w", encoding="utf-8") as fh:
                for event in events:
                    fh.write((json.dumps(event) if isinstance(event, dict) else event) + "\n")

            summary = soak.load_perf(path)
            self.assertTrue(summary["available"])
            self.assertEqual(summary["session_id"], "s2")
            self.assertEqual(summary["bad_lines"], 1)
            self.assertEqual(summary["duration_ms"], 600)
            self.assertEqual(summary["p95_ms"], 180)
            self.assertEqual(summary["drift_ms"], 42)
            self.assertEqual(summary["dropped"], 12)
            self.assertAlmostEqual(summary["drop_rate_percent"], 12 * 100 / 18000)
            self.assertEqual(summary["audio_sample_rate"], 48000)
            self.assertEqual(summary["audio_gaps"], 1)
            self.assertEqual(summary["silent_gaps"], 1)
            self.assertFalse(summary["available"] is False)

    def test_no_perf_returns_insufficient(self):
        self.assertFalse(soak.load_perf(None)["available"])
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "perf.jsonl")
            with open(path, "w", encoding="utf-8") as fh:
                fh.write("{}\n")
            summary = soak.load_perf(path)
            self.assertFalse(summary["available"])
            self.assertEqual(summary["reason"], "没有会话 id")


class SamplesSummaryTest(unittest.TestCase):
    def _write_csv(self, path, pss_values):
        with open(path, "w", encoding="utf-8") as fh:
            fh.write("sample,timestamp,elapsed_s,pid,pss_total_kb,java_heap_kb,native_heap_kb,graphics_kb,cpu_pct,battery_temp_c\n")
            for i, (elapsed, pss) in enumerate(pss_values):
                fh.write(f'{i},2026-11-25T10:00:0{i}+08:00,{elapsed},123,{pss},20000,30000,4000,12.5,36.5\n')

    def test_pss_growth_and_slope(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "samples.csv")
            self._write_csv(path, [(0, 100000), (300, 130000), (600, 160000)])
            result = soak.load_samples(path)
            self.assertTrue(result["available"])
            self.assertEqual(result["pss_growth_kb"], 60000)
            self.assertAlmostEqual(result["pss_growth_mb"], 58.59, places=2)
            self.assertAlmostEqual(result["last10_slope_kb_per_min"], 6000.0, places=1)
            self.assertTrue(result["checks"]["pss_growth_le_80mb"])
            self.assertFalse(result["checks"]["last10_slope_le_2mb_min"])

    def test_missing_pss_is_unavailable(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "samples.csv")
            with open(path, "w", encoding="utf-8") as fh:
                fh.write("sample,elapsed_s,pss_total_kb\n0,0,\n")
            result = soak.load_samples(path)
            self.assertFalse(result["available"])

    def test_build_summary_checks(self):
        with tempfile.TemporaryDirectory() as tmp:
            samples = os.path.join(tmp, "samples.csv")
            self._write_csv(samples, [(0, 100000), (300, 102000), (600, 104000)])
            perf = os.path.join(tmp, "perf.jsonl")
            with open(perf, "w", encoding="utf-8") as fh:
                fh.write(json.dumps({
                    "s": "s2", "t": "1000", "e": "session_start", "v": {"encrypted": "true"},
                }) + "\n")
                fh.write(json.dumps({
                    "s": "s2", "t": "1001", "e": "first_frame", "v": {"auth_ms": "1800"},
                }) + "\n")
                fh.write(json.dumps({
                    "s": "s2", "t": "1002", "e": "metrics",
                    "v": {"p95_ms": "180", "received": "1000", "dropped": "5"},
                }) + "\n")
            summary = soak.build_summary(samples, perf, "smoke")
            self.assertEqual(summary["verdict"], "metrics_collected")
            self.assertTrue(summary["checks"]["first_frame_le_3s"])
            self.assertTrue(summary["checks"]["p95_le_200ms"])
            self.assertTrue(summary["checks"]["drop_rate_le_1pct"])
            self.assertIn("Android Soak 摘要", soak.to_markdown(summary))


if __name__ == "__main__":
    unittest.main()
