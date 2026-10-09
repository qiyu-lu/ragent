import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import parse_metrics  # noqa: E402


class ParseMetricsTest(unittest.TestCase):
    facts = [
        {"id": "f1", "doc": "si", "anchor": "400 ℃±20 ℃", "alt_anchors": ["400±20 ℃"]},
        {"id": "f2", "doc": "si", "anchor": "1min~2min"},
        {"id": "f3", "doc": "si", "anchor": "1050 ℃±20 ℃"},
        {"id": "f4", "doc": "si", "anchor": "12 个国家的 28 个实验室"},
    ]

    def test_raw_versus_latex_coverage_and_slots(self):
        texts = [
            "温度控制在 $400 \\pm 20 ^ { \\circ } \\mathrm { C }$ ，放置 1min~2min。",
            "马弗炉温度控制在 1050 ℃ ± 20 ℃，加热 15min。",
            "12 个国家的 个实验室对 5 个铁矿石样",
        ]
        item = parse_metrics.doc_metrics("si", texts, self.facts, textlayer_digits=100)
        self.assertEqual(item["facts_found_raw"], 2)            # f2, f3
        self.assertEqual(item["facts_found_after_latex"], 3)    # + f1
        self.assertEqual(item["facts_deformed_latex"], ["f1"])
        self.assertEqual(item["facts_missing"], ["f4"])
        self.assertEqual(item["coverage_raw"], 0.5)
        self.assertEqual(item["coverage_after_latex"], 0.75)
        self.assertGreaterEqual(item["empty_slots"], 1)
        self.assertGreater(item["digit_retention"], 0)
        self.assertLess(item["digit_retention"], 1)

    def test_overall_pools_documents(self):
        a = parse_metrics.doc_metrics("a", ["含 400 ℃±20 ℃"], [{"id": "x", "anchor": "400 ℃±20 ℃"}], 10)
        b = parse_metrics.doc_metrics("b", ["没有"], [{"id": "y", "anchor": "缺失事实"}], 10)
        total = parse_metrics.overall([a, b])
        self.assertEqual(total["facts_total"], 2)
        self.assertEqual(total["coverage_raw"], 0.5)
        self.assertIsNotNone(total["digit_retention"])

    def test_image_references_do_not_count_as_digits(self):
        hashed = "![](images/7bbefdb15db7a398b850af5819e9032c17ab2850429d95bb3bfa4129852b6424.jpg)"
        plain = parse_metrics.doc_metrics("d", ["称取 0.5 g"], [], 10)
        with_image = parse_metrics.doc_metrics("d", ["称取 0.5 g\n" + hashed], [], 10)
        self.assertEqual(plain["digits_nd"], 2)
        self.assertEqual(with_image["digits_nd"], 2)
        self.assertEqual(with_image["non_space_chars"], plain["non_space_chars"])

    def test_strict_slots_skip_the_verb_pattern(self):
        item = parse_metrics.doc_metrics("d", ["继续加热，直至冒烟。温度控制在 ,放置 。"], [], None)
        self.assertEqual(item["empty_slots"], 3)          # 加热， / 在 , / 放置 。
        self.assertEqual(item["empty_slots_strict"], 1)   # only 在 ,

    def test_no_facts_gives_none_coverage(self):
        item = parse_metrics.doc_metrics("empty", ["x"], [], None)
        self.assertIsNone(item["coverage_raw"])
        self.assertIsNone(item["digit_retention"])


if __name__ == "__main__":
    unittest.main()
