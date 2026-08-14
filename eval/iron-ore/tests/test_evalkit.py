import sys
import unittest
from pathlib import Path
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from evalkit import (
    ApiClient,
    aggregate_retrieval,
    anchor_rank,
    normalize,
    percentile,
    score_retrieval,
    validate_retrieval_diagnostics,
)


class EvalKitTest(unittest.TestCase):
    def test_normalize_handles_full_width_and_layout_spaces(self):
        self.assertEqual(normalize("１．００％ ～ ４０．００％"), "1.00%~40.00%")
        self.assertEqual(normalize("**1050 ℃ ± 20 ℃**"), "1050°C±20°C")

    def test_anchor_rank_is_stable_across_whitespace(self):
        self.assertEqual(anchor_rank("称取 0.100 g 试样", ["称取\n0.100ｇ试样，精确至0.0001g"]), 0)
        self.assertEqual(anchor_rank("不存在", ["正文"]), -1)

    def test_score_retrieval_includes_intent_and_routing(self):
        row = {
            "answerable": True,
            "reference_docs": ["doc-a"],
            "reference_anchors": ["关键事实"],
            "expected_kbs": ["iron"],
            "expected_intent_ids": ["intent-iron"],
            "routing_scored": True,
            "intent_scored": True,
        }
        response = {
            "retrievedDocIds": ["doc-a", "doc-b"],
            "retrievedContextDocIds": ["doc-a", "doc-b"],
            "retrievedContexts": ["这里有关键事实", "噪声"],
            "intentLeafIds": ["intent-iron"],
            "hasKb": True,
            "latencyMs": 120,
        }
        score = score_retrieval(row, response, {"doc-a": "iron", "doc-b": "titanium"}, "on")
        self.assertEqual(score["anchor_hit@5_any"], 1.0)
        self.assertEqual(score["anchor_hit@5_all"], 1.0)
        self.assertEqual(score["doc_recall"], 1.0)
        self.assertEqual(score["doc_precision"], 0.5)
        self.assertEqual(score["routing_purity"], 0.5)
        self.assertEqual(score["intent_top1_correct"], 1.0)

    def test_aggregate_keeps_families_separate(self):
        details = [
            {
                "answerable": True,
                "family": "xlsx",
                "tier": "direct",
                "score": {"anchor_hit@5_any": 1.0, "latency_ms": 10},
            },
            {
                "answerable": True,
                "family": "scan_pdf",
                "tier": "hard",
                "score": {"anchor_hit@5_any": 0.0, "latency_ms": 30},
            },
        ]
        summary = aggregate_retrieval(details)
        self.assertEqual(summary["overall_answerable"]["anchor_hit@5_any"], 0.5)
        self.assertEqual(summary["by_family"]["xlsx"]["anchor_hit@5_any"], 1.0)
        self.assertEqual(summary["latency_ms"]["p95"], 30)
        self.assertEqual(percentile([1, 2, 3, 4], 0.5), 2)

    def test_query_eval_posts_replayed_sub_questions(self):
        client = ApiClient("http://127.0.0.1:9090")
        with patch.object(client, "request_json", return_value={"subIntents": ["一", "二"]}) as request:
            response, _ = client.query_eval("原问题", ["一", "二"])
        self.assertEqual(response["subIntents"], ["一", "二"])
        request.assert_called_once_with(
            "/rag/eval/replay",
            method="POST",
            body={"question": "原问题", "subQuestions": ["一", "二"]},
        )

    def test_query_eval_keeps_live_get_behavior(self):
        client = ApiClient("http://127.0.0.1:9090")
        with patch.object(client, "request_json", return_value={"subIntents": ["原问题"]}) as request:
            client.query_eval("原问题")
        request.assert_called_once_with("/rag/eval", query={"question": "原问题"})

    def test_query_eval_rejects_non_object_response(self):
        client = ApiClient("http://127.0.0.1:9090")
        with patch.object(client, "request_json", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "non-object"):
                client.query_eval("原问题", ["固定子问题"])

    def test_validate_retrieval_diagnostics_checks_mode_and_counts(self):
        response = {
            "retrievedChunkIds": ["a", "b", "c"],
            "retrievedContexts": ["A", "B", "C"],
            "retrievalDiagnostics": {
                "fairRefillEnabled": True,
                "requestTopK": 3,
                "initialBudgets": [2, 1],
                "candidateCount": 5,
                "candidateUniqueCount": 4,
                "uniqueBeforeRefill": 2,
                "refillAdded": 1,
                "finalUniqueCount": 3,
                "unfilledSlots": 0,
            },
        }
        diagnostics = validate_retrieval_diagnostics(response, "on")
        self.assertEqual(diagnostics["refillAdded"], 1)
        with self.assertRaisesRegex(ValueError, "does not match"):
            validate_retrieval_diagnostics(response, "off")


if __name__ == "__main__":
    unittest.main()
