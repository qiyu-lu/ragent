import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))

import evalkit  # noqa: E402


class NormalizeTest(unittest.TestCase):
    cases = json.loads((HERE / "normalization_cases.json").read_text(encoding="utf-8"))

    def test_shared_cases(self):
        for case in self.cases["cases"]:
            with self.subTest(case["name"]):
                self.assertEqual(evalkit.normalize(case["input"]), case["expected"])

    def test_latex_cases(self):
        for case in self.cases["latex_cases"]:
            with self.subTest(case["name"]):
                self.assertEqual(evalkit.normalize_latex(case["input"]), case["expected"])

    def test_latex_is_only_unwrapped_for_the_latex_column(self):
        chunk = "温度控制在 $400 \\pm 20 ^ { \\circ } \\mathrm { C }$"
        self.assertFalse(evalkit.contains_anchor("400±20 ℃", chunk))
        self.assertTrue(evalkit.contains_anchor("400±20 ℃", chunk, latex=True))
        # the standard writes the unit twice; MinerU's math form keeps one, so the exact phrase stays a miss
        self.assertFalse(evalkit.contains_anchor("400 ℃±20 ℃", chunk, latex=True))

    def test_digit_counts_follow_unicode_categories(self):
        text = "１２３ 45 Ⅻ ㉑"
        self.assertEqual(evalkit.count_digits(text), 5)
        self.assertEqual(evalkit.count_fullwidth_digits(text), 3)
        self.assertEqual(evalkit.count_ascii_digits(text), 2)
        self.assertEqual(evalkit.count_pua("a"), 2)

    def test_empty_slots_and_noise(self):
        lost = "温度控制在 ,放置 。硅含量高于 (质量分数)。最左一位数字小于 时。12 个国家的 个实验室"
        intact = "温度控制在 400 ℃±20 ℃,放置 1min~2min。硅含量高于 10%(质量分数)。最左一位数字小于 5 时。12 个国家的 28 个实验室"
        self.assertGreaterEqual(evalkit.count_empty_slots(lost), 5)
        self.assertEqual(evalkit.count_empty_slots(intact), 0)
        text = "东北大学\n中国标准出版社授权北京万方数据股份有限公司在中国境内(不含港澳台地区)推广使用\n7.4 测定\n"
        self.assertEqual(evalkit.count_noise_lines(text), 2)
        self.assertAlmostEqual(evalkit.slots_per_1000("在 ," + "x" * 998), 1.0)

    def test_anchor_rank_ignores_layout_whitespace(self):
        contexts = ["无关", "称取\n0.100ｇ试样，精确至0.000 1 g"]
        self.assertEqual(evalkit.anchor_rank("称取 0.100 g 试样", contexts), 1)
        self.assertEqual(evalkit.anchor_rank("不存在", contexts), -1)
        self.assertEqual(evalkit.anchor_rank("  ", contexts), -1)


if __name__ == "__main__":
    unittest.main()
