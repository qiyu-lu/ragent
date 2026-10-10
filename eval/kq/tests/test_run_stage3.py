import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import run_stage3 as rs  # noqa: E402


def ranking_block(numeric_mrr_verdict, numeric_mrr, overall_hit="unproven", overall_mrr="unproven", overall_mrr_mean=0.6):
    return {"overall_answerable": {"hit@5": {"verdict": overall_hit},
                                   "mrr": {"verdict": overall_mrr, "candidate_mean": overall_mrr_mean}},
            "by_type": {"numeric": {"mrr": {"verdict": numeric_mrr_verdict, "candidate_mean": numeric_mrr}}}}


def threshold_block(cp_verdict, cp, hit="unproven"):
    return {"overall_answerable": {"context_precision": {"verdict": cp_verdict, "candidate_mean": cp},
                                   "hit@5": {"verdict": hit}}}


class ChooseArmTest(unittest.TestCase):
    def test_rrf_with_a_passing_threshold_goes_to_the_test_split_as_that_threshold(self):
        ranking = {"comparisons": {"S1-base": {}, "S3-rrf": ranking_block("improved", 0.52),
                                   "S3-blend-0.5": ranking_block("unproven", 0.55)}}
        thresholds = {"comparisons": {"S3-thr-0.1": threshold_block("improved", 0.11),
                                      "S3-thr-0.2": threshold_block("improved", 0.13),
                                      "S3-thr-0.3": threshold_block("improved", 0.15, hit="regressed")}}
        decision = rs.choose_arm(ranking, thresholds)
        self.assertEqual(decision["chosen"], "S3-thr-0.2")
        self.assertEqual(decision["ranking_passed"], ["S3-rrf"])

    def test_the_best_passing_blend_beats_rrf_on_numeric_mrr_and_then_takes_no_threshold(self):
        ranking = {"comparisons": {"S3-rrf": ranking_block("improved", 0.52),
                                   "S3-blend-0.3": ranking_block("improved", 0.56),
                                   "S3-blend-0.5": ranking_block("improved", 0.58),
                                   "S3-blend-0.7": ranking_block("improved", 0.60, overall_hit="regressed")}}
        thresholds = {"comparisons": {"S3-thr-0.1": threshold_block("improved", 0.11)}}
        decision = rs.choose_arm(ranking, thresholds)
        self.assertEqual(decision["best_blend"], "S3-blend-0.5")
        self.assertEqual(decision["chosen"], "S3-blend-0.5")

    def test_a_tie_on_numeric_mrr_goes_to_rrf(self):
        ranking = {"comparisons": {"S3-rrf": ranking_block("improved", 0.52),
                                   "S3-blend-0.3": ranking_block("improved", 0.52)}}
        self.assertEqual(rs.choose_arm(ranking, {"comparisons": {}})["chosen"], "S3-rrf")

    def test_nothing_goes_to_the_test_split_when_no_ranking_arm_passes(self):
        ranking = {"comparisons": {"S3-rrf": ranking_block("unproven", 0.50),
                                   "S3-blend-0.5": ranking_block("improved", 0.56, overall_mrr="regressed")}}
        thresholds = {"comparisons": {"S3-thr-0.2": threshold_block("improved", 0.13)}}
        decision = rs.choose_arm(ranking, thresholds)
        self.assertIsNone(decision["chosen"])
        self.assertEqual(decision["best_threshold"], "S3-thr-0.2")


def candidate(cid, rerank, bm25=None, channel=0.6):
    scores = {"VectorSearch": channel}
    if bm25 is not None:
        scores["FullTextSearch"] = bm25
    return {"id": cid, "rerankScore": rerank, "channelScores": scores, "rerankHead": True}


def report(results, stages):
    return {"details": [{"raw_response": {"results": results, "stages": [{"stage": s, "chunkCount": 20} for s in stages]}}]}


RRF_STAGES = ["channel-VectorSearch", "channel-FullTextSearch", "post-Rerank"]


class CheckReportTest(unittest.TestCase):
    def test_rrf_run_needs_the_full_text_channel_and_no_extra_stage(self):
        ok = report([{"rerankScored": 2, "candidates": [candidate("a", 0.9), candidate("b", 0.3)]}], RRF_STAGES)
        self.assertEqual(rs.check_report(ok, "S3-rrf")[0], "ok")
        vector_only = report([{"candidates": []}], ["channel-VectorSearch", "post-Rerank"])
        self.assertEqual(rs.check_report(vector_only, "S3-rrf")[0], "config")
        with_threshold = report([{"candidates": []}], RRF_STAGES + ["post-RerankThreshold"])
        self.assertEqual(rs.check_report(with_threshold, "S3-rrf")[0], "config")

    def test_threshold_run_must_not_keep_chunks_under_its_threshold(self):
        stages = RRF_STAGES + ["post-RerankThreshold"]
        kept = [{"rerankScored": 2, "candidates": [candidate("a", 0.9), candidate("b", 0.25)]}]
        self.assertEqual(rs.check_report(report(kept, stages), "S3-thr-0.2")[0], "ok")
        self.assertEqual(rs.check_report(report(kept, stages), "S3-thr-0.3")[0], "config")
        noop = [{"rerankScored": 0, "candidates": [candidate("a", 0.05), candidate("b", 0.04)]}]
        self.assertEqual(rs.check_report(report(noop, stages), "S3-thr-0.3")[0], "ok",
                         "a sub-question whose rerank fell back is skipped by the threshold on purpose")

    def test_blend_order_identifies_alpha(self):
        # a：重排 0.95、BM25 0；b：重排 0.5、BM25 本题最高。a 排在 b 前只在 alpha ≤ 0.31 时成立
        stages = RRF_STAGES + ["post-ScoreBlend"]
        a_first = {"rerankScored": 2, "candidates": [candidate("a", 0.95, bm25=0.0), candidate("b", 0.5, bm25=10.0)]}
        inferred = rs.infer_alpha(report([a_first], stages))
        self.assertEqual((inferred["decisive"], inferred["alpha"]), (1, 0.3))
        self.assertEqual(rs.check_report(report([a_first], stages), "S3-blend-0.3")[0], "ok")
        self.assertEqual(rs.check_report(report([a_first], stages), "S3-blend-0.7")[0], "config")

    def test_an_order_that_fits_several_alphas_only_rules_out_the_others(self):
        # b 在前：0.5 与 0.7 都成立、0.3 不成立；分不出 0.5 还是 0.7，但能排除 0.3
        stages = RRF_STAGES + ["post-ScoreBlend"]
        b_first = {"rerankScored": 2, "candidates": [candidate("b", 0.5, bm25=10.0), candidate("a", 0.95, bm25=0.0)]}
        inferred = rs.infer_alpha(report([b_first], stages))
        self.assertEqual(inferred["agreement"], {0.3: 0, 0.5: 1, 0.7: 1})
        self.assertIsNone(inferred["alpha"])
        self.assertEqual(rs.check_report(report([b_first], stages), "S3-blend-0.5")[0], "ok")
        self.assertEqual(rs.check_report(report([b_first], stages), "S3-blend-0.7")[0], "ok")
        self.assertEqual(rs.check_report(report([b_first], stages), "S3-blend-0.3")[0], "config")

    def test_vector_channel_down_is_dead_and_many_timeouts_are_empty(self):
        dead = {"details": [{"raw_response": {"results": [{"candidates": []}],
                                              "stages": [{"stage": "channel-VectorSearch", "chunkCount": 0},
                                                         {"stage": "channel-FullTextSearch", "chunkCount": 20}]}}] * 3}
        self.assertEqual(rs.check_report(dead, "S3-rrf")[0], "dead")

    def test_arm_flags(self):
        self.assertEqual(rs.arm_kind("S3-thr-0.2"), ("thr", 0.2))
        self.assertEqual(rs.arm_kind("S3-blend-0.7"), ("blend", 0.7))
        self.assertEqual(rs.arm_kind("S3-rrf"), ("rrf", None))
        self.assertIn("--rag.search.rerank-threshold.min-score=0.3", rs.ARMS["S3-thr-0.3"])
        self.assertIn("--rag.search.score-blend.alpha=0.5", rs.ARMS["S3-blend-0.5"])
        self.assertTrue(all(rs.FULL_TEXT in flags for flags in rs.ARMS.values()))
        self.assertEqual(len(rs.ARMS), 7)


if __name__ == "__main__":
    unittest.main()
