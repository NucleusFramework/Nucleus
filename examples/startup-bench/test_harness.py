#!/usr/bin/env python3
"""Parser/unit tests for the startup harness. No GUI."""

from __future__ import annotations

import unittest
from pathlib import Path

import run as harness

HERE = Path(__file__).resolve().parent


class SmapsTests(unittest.TestCase):
    def test_rollup(self) -> None:
        text = (HERE / "testdata" / "smaps_rollup.txt").read_text(encoding="utf-8")
        parsed = harness.parse_smaps_rollup(text)
        self.assertEqual(parsed["rss_kb"], 348112)
        self.assertEqual(parsed["pss_kb"], 301440)
        self.assertEqual(parsed["uss_kb"], 4096 + 240128)
        self.assertEqual(parsed["rss_anon_kb"], 240128)


class AotLogTests(unittest.TestCase):
    def test_ok(self) -> None:
        text = (HERE / "testdata" / "aot_ok.log").read_text(encoding="utf-8")
        parsed = harness.parse_aot_log(text)
        self.assertTrue(parsed["cache_ok"])
        self.assertFalse(parsed["failed"])
        self.assertTrue(parsed["code_loaded"])
        self.assertIsNone(parsed["class_space_reserved_bytes"])

    def test_gc_mismatch(self) -> None:
        text = (HERE / "testdata" / "aot_fail.log").read_text(encoding="utf-8")
        parsed = harness.parse_aot_log(text)
        self.assertTrue(parsed["failed"])
        self.assertFalse(parsed["cache_ok"])


class StatsTests(unittest.TestCase):
    def test_median_and_p90(self) -> None:
        summary = harness.summarize([10.0, 11.0, 12.0, 13.0, 100.0])
        self.assertEqual(summary["n"], 5)
        self.assertEqual(summary["min"], 10.0)
        self.assertEqual(summary["p50"], 12.0)
        self.assertGreater(summary["p90"], summary["p50"])
        self.assertEqual(summary["max"], 100.0)

    def test_empty(self) -> None:
        self.assertEqual(harness.summarize([])["n"], 0)


class CollectorFlagTests(unittest.TestCase):
    def test_gc_flags_cover_serial_and_g1(self) -> None:
        self.assertIn("-XX:+UseSerialGC", harness.GC_FLAGS["serial"])
        self.assertIn("-XX:+UseG1GC", harness.GC_FLAGS["g1"])

    def test_native_variant_ids(self) -> None:
        self.assertTrue(harness.is_native("ni-serial"))
        self.assertTrue(harness.is_native("ni-g1"))
        self.assertFalse(harness.is_native("leyden-serial"))
        self.assertEqual(harness.gc_of("ni-serial"), "serial")
        self.assertEqual(harness.gc_of("ni-g1"), "g1")

    def test_publish_name_has_os_and_arch(self) -> None:
        name = harness.default_run_name()
        self.assertIn("-", name)
        self.assertTrue(name.split("-", 1)[0] in {"linux", "windows", "macos"})


if __name__ == "__main__":
    unittest.main()
