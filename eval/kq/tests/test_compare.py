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

    def test_fixed_thresholds_replace_the_range_and_one_question_counts(self):
        base = [report("b1", "S1-base", 1, 34 / 53, 0.60), report("b2", "S1-base", 2, 34 / 53, 0.60),
                report("b3", "S1-base", 3, 34 / 53, 0.60)]
        cand = [report("c1", "S2", 1, 35 / 53, 0.61), report("c2", "S2", 2, 35 / 53, 0.61),
                report("c3", "S2", 3, 35 / 53, 0.61)]
        fixed = {"overall_answerable": {"hit@5": 1 / 53, "mrr": 0.022}}
        comparison = cmp.compare_arms(cmp.summarize_arm(base), cmp.summarize_arm(cand), fixed)
        self.assertEqual(comparison["overall_answerable"]["hit@5"]["verdict"], "improved")
        self.assertEqual(comparison["overall_answerable"]["hit@5"]["threshold_source"], "fixed")
        self.assertEqual(comparison["overall_answerable"]["mrr"]["verdict"], "unproven")
        self.assertEqual(comparison["by_type"]["numeric"]["hit@5"]["threshold_source"], "baseline_range")

    def test_fixed_threshold_is_honoured_to_its_stored_precision(self):
        # 门槛文件把 1/22 存成 0.045455：恰好一道题的提升（1/22）按写定规则 max(极差, 1/n) 算过
        self.assertEqual(cmp.verdict("mrr", 1 / 22, 0.045455, cmp.FIXED_PRECISION), "improved")
        self.assertEqual(cmp.verdict("mrr", 1 / 22, 0.045455), "unproven", "the old exact comparison")
        self.assertEqual(cmp.verdict("hit@5", -1 / 53, 0.018868, cmp.FIXED_PRECISION), "unproven",
                         "a loss of exactly the threshold does not exceed it")
        self.assertEqual(cmp.verdict("mrr", 0.0450, 0.045455, cmp.FIXED_PRECISION), "unproven")

    def test_zero_range_never_turns_no_change_into_improved(self):
        self.assertEqual(cmp.verdict("hit@5", 0.0, 0.0), "unproven")

    def test_validity_counts_empty_channel_sub_questions(self):
        bad = report("r1", "S2", 1, 0.8, 0.5)
        bad["details"] = [{"raw_response": {"stages": [{"stage": "channel-VectorSearch", "chunkCount": 0},
                                                       {"stage": "post-Rerank", "chunkCount": 0}]}}] * 9
        good = report("r2", "S2", 2, 0.8, 0.5)
        good["details"] = [{"raw_response": {"stages": [{"stage": "channel-VectorSearch", "chunkCount": 20}]}}]
        self.assertEqual(cmp.empty_channel_sub_questions(bad), 9)
        problems = cmp.check_validity({"S2": [bad, good]}, 8)
        self.assertEqual(len(problems), 1)
        self.assertIn("r1", problems[0])
        self.assertEqual(cmp.check_validity({"S2": [bad]}, None), [])

    def test_an_empty_full_text_channel_does_not_invalidate_a_run(self):
        # 全文通道查不到词是正常结果；作废规则只看向量通道的超时
        run = report("r3", "S3", 1, 0.8, 0.5)
        run["details"] = [{"raw_response": {"stages": [{"stage": "channel-VectorSearch", "chunkCount": 20},
                                                       {"stage": "channel-FullTextSearch", "chunkCount": 0}]}}] * 9
        self.assertEqual(cmp.empty_channel_sub_questions(run), 0)

    def test_sub_questions_are_compared_by_what_was_replayed(self):
        details = [{"id": "q1", "raw_response": {"subQuestions": ["a", "b"]}}]
        first = report("a1", "a", 1, 0.8, 0.5)
        first["details"] = details
        second = report("b1", "b", 1, 0.8, 0.5)
        second["details"] = details
        second["sub_questions_sha256"] = "file-grew-later"
        self.assertEqual(cmp.check_compatible({"a": [first], "b": [second]})[:1],
                         ["arm a: needs at least 2 repeats to measure a range"])
        other = report("c1", "c", 1, 0.8, 0.5)
        other["details"] = [{"id": "q1", "raw_response": {"subQuestions": ["a", "c"]}}]
        problems = "\n".join(cmp.check_compatible({"a": [first], "c": [other]}))
        self.assertIn("replayed different sub-questions", problems)

    def test_compatibility_checks(self):
        arms = {"a": [report("a1", "a", 1, 0.8, 0.5), report("a2", "a", 1, 0.8, 0.5)],
                "b": [report("b1", "b", 1, 0.8, 0.5, split="test")]}
        problems = "\n".join(cmp.check_compatible(arms))
        self.assertIn("differ in split", problems)
        self.assertIn("duplicate repeat_index", problems)
        self.assertIn("needs at least 2 repeats", problems)


if __name__ == "__main__":
    unittest.main()
