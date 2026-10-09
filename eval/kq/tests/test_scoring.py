import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import evalkit  # noqa: E402


def candidate(cid, text, doc, final_rank=None, rerank=None, channel=0.5):
    return {"id": cid, "docId": "d-" + cid, "docName": doc + ".pdf", "text": text, "channelScore": channel,
            "rerankScore": rerank, "rerankHead": rerank is not None, "finalSelected": final_rank is not None,
            "finalRank": final_rank}


def response(results, final_ids):
    return {"question": "q", "mode": "replay", "subQuestions": [r["subQuestion"] for r in results],
            "results": results, "finalChunkIds": final_ids, "latencyMs": 120}


STEMS = {"硅含量": "si", "全铁": "tfe"}


class ScoringTest(unittest.TestCase):
    def test_final_chunks_follow_request_order_and_dedup(self):
        resp = response([
            {"subQuestion": "a", "rerankHeadSize": 2, "candidates": [
                candidate("c2", "第二", "硅含量", final_rank=1, rerank=0.7),
                candidate("c1", "第一", "硅含量", final_rank=0, rerank=0.9),
                candidate("c9", "落选", "硅含量")]},
            {"subQuestion": "b", "rerankHeadSize": 1, "candidates": [candidate("c1", "第一", "硅含量", final_rank=0, rerank=0.8)]},
        ], ["c1", "c2"])
        chunks = evalkit.final_chunks(resp)
        self.assertEqual([c["id"] for c in chunks], ["c1", "c2"])
        self.assertEqual(evalkit.max_rerank_score(resp), 0.9)

    def test_score_answerable_question(self):
        row = {"answerable": True, "reference_docs": ["si"], "anchors": ["400 ℃±20 ℃", "1min~2min"]}
        resp = response([{"subQuestion": "a", "rerankHeadSize": 3, "candidates": [
            candidate("c1", "噪声", "全铁", final_rank=0, rerank=0.9),
            candidate("c2", "温度控制在 400 ℃ ± 20 ℃", "硅含量", final_rank=1, rerank=0.8),
            candidate("c3", "其他", "硅含量", final_rank=2, rerank=0.1),
        ]}], ["c1", "c2", "c3"])
        score = evalkit.score_question(row, resp, STEMS)
        self.assertEqual(score["n_chunks"], 3)
        self.assertEqual(score["hit@1"], 0.0)
        self.assertEqual(score["hit@5"], 1.0)
        self.assertEqual(score["hit@5_all"], 0.0)
        self.assertEqual(score["anchor_recall"], 0.5)
        self.assertEqual(score["mrr"], 0.5)
        self.assertEqual(score["missed_anchors"], ["1min~2min"])
        self.assertAlmostEqual(score["context_precision"], 1 / 3)
        self.assertEqual(score["doc_recall"], 1.0)
        self.assertEqual(score["retrieved_doc_count"], 2)
        self.assertEqual(score["top_final_rerank"], 0.9)

    def test_score_unanswerable_question_reports_returned_chunks_and_top_score(self):
        row = {"answerable": False}
        resp = response([{"subQuestion": "a", "rerankHeadSize": 1, "candidates": [
            candidate("c1", "x", "硅含量", final_rank=0, rerank=0.42), candidate("c2", "y", "硅含量")]}], ["c1"])
        score = evalkit.score_question(row, resp, STEMS)
        self.assertEqual(score["n_chunks"], 1)
        self.assertEqual(score["max_rerank_score"], 0.42)
        self.assertNotIn("hit@5", score)

    def test_aggregate_by_type_and_unanswerable(self):
        details = [
            {"answerable": True, "type": "numeric", "reference_docs": ["si"], "score": {"hit@5": 1.0, "mrr": 1.0, "n_chunks": 10, "latency_ms": 100}},
            {"answerable": True, "type": "numeric", "reference_docs": ["si"], "score": {"hit@5": 0.0, "mrr": 0.0, "n_chunks": 10, "latency_ms": 300}},
            {"answerable": True, "type": "survey", "reference_docs": ["survey"], "score": {"hit@5": 1.0, "mrr": 0.5, "n_chunks": 10, "latency_ms": 200}},
            {"answerable": False, "type": "unanswerable", "score": {"n_chunks": 10, "max_rerank_score": 0.3, "latency_ms": 50}},
        ]
        summary = evalkit.aggregate(details)
        self.assertEqual(summary["overall_answerable"]["n"], 3)
        self.assertAlmostEqual(summary["overall_answerable"]["hit@5"], 2 / 3)
        self.assertEqual(summary["by_type"]["numeric"]["hit@5"], 0.5)
        self.assertEqual(summary["by_doc"]["si"]["n"], 2)
        self.assertEqual(summary["unanswerable"]["max_rerank_score"], 0.3)
        self.assertEqual(summary["latency_ms"]["p95"], 300)

    def test_summarize_repeats_gives_mean_and_range(self):
        blocks = [{"hit@5": 0.8, "mrr": 0.5}, {"hit@5": 0.9, "mrr": 0.5}, {"hit@5": 0.7, "mrr": 0.5}]
        stats = evalkit.summarize_repeats(blocks, ("hit@5", "mrr", "missing"))
        self.assertAlmostEqual(stats["hit@5"]["mean"], 0.8)
        self.assertAlmostEqual(stats["hit@5"]["range"], 0.2)
        self.assertEqual(stats["mrr"]["range"], 0.0)
        self.assertNotIn("missing", stats)

    def test_rerank_noop_detection(self):
        live = response([{"subQuestion": "a", "rerankHeadSize": 2, "candidates": [
            candidate("c1", "x", "硅含量", final_rank=0, rerank=0.91, channel=0.80),
            candidate("c2", "y", "硅含量", final_rank=1, rerank=0.40, channel=0.79)]}], ["c1", "c2"])
        noop = response([{"subQuestion": "a", "rerankHeadSize": 2, "candidates": [
            candidate("c1", "x", "硅含量", final_rank=0, rerank=0.80, channel=0.80),
            candidate("c2", "y", "硅含量", final_rank=1, rerank=0.79, channel=0.79)]}], ["c1", "c2"])
        single = response([{"subQuestion": "a", "rerankHeadSize": 1, "candidates": [
            candidate("c1", "x", "硅含量", final_rank=0, rerank=0.80, channel=0.80)]}], ["c1"])
        self.assertFalse(evalkit.rerank_looks_noop(live))
        self.assertTrue(evalkit.rerank_looks_noop(noop))
        self.assertIsNone(evalkit.rerank_looks_noop(single))

    def test_rewrite_fallback_detection(self):
        q = "碱熔那一步，坩埚先在炉口放多久、进炉后又要放多久？"
        self.assertTrue(evalkit.looks_like_rewrite_fallback(q, [q]))
        self.assertTrue(evalkit.looks_like_rewrite_fallback(q, ["碱熔那一步，坩埚先在炉口放多久", "进炉后又要放多久？"]))
        self.assertFalse(evalkit.looks_like_rewrite_fallback(q, ["铁矿石硅含量测定碱融法坩埚在马弗炉入口放置时间", "碱融法入炉后放置时间"]))
        self.assertFalse(evalkit.looks_like_rewrite_fallback(q, []))

    def test_replay_posts_sub_questions_only_when_given(self):
        client = evalkit.ApiClient("http://127.0.0.1:9093/api/ragent")
        with patch.object(client, "request_json", return_value={"subQuestions": ["一"]}) as request:
            client.replay("原问题", ["一"])
            request.assert_called_once_with("/rag/eval/replay", method="POST", body={"question": "原问题", "subQuestions": ["一"]})
        with patch.object(client, "request_json", return_value={"subQuestions": ["原问题"]}) as request:
            client.replay("原问题")
            request.assert_called_once_with("/rag/eval/replay", method="POST", body={"question": "原问题"})
        with patch.object(client, "request_json", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "non-object"):
                client.replay("原问题", ["一"])


if __name__ == "__main__":
    unittest.main()
