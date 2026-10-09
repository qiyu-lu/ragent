import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import compare_retrieval_repeats as cmp  # noqa: E402


def report(label, arm, repeat, hit5, mrr, n_chunks=10.0, split="tune"):
    return {"kind": "kq-retrieval", "label": label, "arm": arm, "repeat_index": repeat, "split": split,
            "server_commit": "abc", "questions_sha256": "q", "sub_questions_sha256": "s", "failures": [],
            "summary": {"overall_answerable": {"n": 60, "hit@5": hit5, "mrr": mrr, "n_chunks": n_chunks},
                        "by_type": {"numeric": {"n": 22, "hit@5": hit5, "mrr": mrr}},
                        "unanswerable": {"n": 8, "n_chunks": n_chunks, "max_rerank_score": 0.3},
                        "latency_ms": {"p50": 100, "p95": 200}}}


class CompareTest(unittest.TestCase):
    def test_arm_summary_and_verdicts_use_baseline_range(self):
        base = [report("b1", "S1-base", 1, 0.80, 0.60), report("b2", "S1-base", 2, 0.84, 0.62), report("b3", "S1-base", 3, 0.82, 0.61)]
        cand = [report("c1", "S2", 1, 0.90, 0.61), report("c2", "S2", 2, 0.91, 0.60), report("c3", "S2", 3, 0.92, 0.50)]
        base_summary = cmp.summarize_arm(base)
        self.assertAlmostEqual(base_summary["overall_answerable"]["hit@5"]["mean"], 0.82)
        self.assertAlmostEqual(base_summary["overall_answerable"]["hit@5"]["range"], 0.04)
        comparison = cmp.compare_arms(base_summary, cmp.summarize_arm(cand))
        self.assertEqual(comparison["overall_answerable"]["hit@5"]["verdict"], "improved")
        self.assertEqual(comparison["overall_answerable"]["mrr"]["verdict"], "regressed")
        self.assertEqual(comparison["overall_answerable"]["n_chunks"]["verdict"], "unproven")
        self.assertIn("numeric", comparison["by_type"])
        self.assertEqual(comparison["unanswerable"]["max_rerank_score"]["verdict"], "unproven")

    def test_lower_is_better_for_returned_chunks(self):
        self.assertEqual(cmp.verdict("n_chunks", -2.0, 0.5), "improved")
        self.assertEqual(cmp.verdict("n_chunks", 2.0, 0.5), "regressed")
        self.assertEqual(cmp.verdict("hit@5", 0.01, 0.04), "unproven")

    def test_compatibility_checks(self):
        arms = {"a": [report("a1", "a", 1, 0.8, 0.5), report("a2", "a", 1, 0.8, 0.5)],
                "b": [report("b1", "b", 1, 0.8, 0.5, split="test")]}
        problems = "\n".join(cmp.check_compatible(arms))
        self.assertIn("differ in split", problems)
        self.assertIn("duplicate repeat_index", problems)
        self.assertIn("needs at least 2 repeats", problems)


if __name__ == "__main__":
    unittest.main()
