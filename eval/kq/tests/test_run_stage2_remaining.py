import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import run_stage2_remaining as rs  # noqa: E402


def arm(mrr_verdict, mrr_mean, hit_verdict="unproven", numeric_hit_verdict="unproven"):
    return {"overall_answerable": {"mrr": {"verdict": mrr_verdict, "candidate_mean": mrr_mean},
                                   "hit@5": {"verdict": hit_verdict}},
            "by_type": {"numeric": {"hit@5": {"verdict": numeric_hit_verdict}}}}


class ChooseBetaTest(unittest.TestCase):
    def test_best_overall_mrr_among_arms_that_pass(self):
        comparison = {"comparisons": {
            "S2-boost-0.1": arm("improved", 0.70),
            "S2-boost-0.2": arm("improved", 0.73, numeric_hit_verdict="regressed"),
            "S2-boost-0.3": arm("improved", 0.72)}}
        self.assertEqual(rs.choose_beta(comparison), 0.3)

    def test_none_when_no_arm_reaches_the_mrr_threshold(self):
        comparison = {"comparisons": {"S2-boost-0.1": arm("unproven", 0.67),
                                      "S2-boost-0.2": arm("improved", 0.75, hit_verdict="regressed")}}
        self.assertIsNone(rs.choose_beta(comparison))

    def test_labels(self):
        self.assertEqual(rs.boost_arm(0.2), "S2-boost-0.2")
        self.assertEqual(rs.label("S2-gate", "test", 2), "S2-gate-test-r2")


if __name__ == "__main__":
    unittest.main()
