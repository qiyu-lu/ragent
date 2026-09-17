import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scoring import answer_f1, aggregate, prediction, score_one, source_ids
from evaluate_research import full_execution_boundaries, prepare_cases, resource_summary


class ScoringTest(unittest.TestCase):
    def predicted(self, answer="Mercury", status="COMPLETED", ids=("p1",)):
        return {"questionId": "q", "mode": "B", "status": status, "artifact": {"sections": []},
                "answer": answer, "answer_format_error": False, "predicted_answerable": True,
                "citation_source_ids": list(ids), "read_source_ids": list(ids)}

    def qasper(self):
        return {"id": "q", "dataset": "qasper", "source_question_id": "source-q",
                "gold": {"annotations": [{"unanswerable": False, "yes_no": None, "extractive_spans": ["Mercury"],
                         "free_form_answer": "", "evidence": [{"text": "source text", "corpus_ids": ["p1"], "status": "resolved"}]}]}}

    def test_author_normalization_and_different_empty_answer_conventions(self):
        self.assertEqual(answer_f1("The Mercury.", "Mercury"), 1)
        self.assertEqual(answer_f1("", ""), 0)  # QASPER's official token scorer
        self.assertEqual(answer_f1("", "", True), 1)  # MuSiQue's author scorer

    def test_qasper_best_annotation_and_unresolved_evidence_remains_in_denominator(self):
        question = self.qasper()
        question["gold"]["annotations"][0]["evidence"].append({"text": "unresolved original text", "corpus_ids": [], "status": "unresolved"})
        score = score_one(question, self.predicted(), {"p1": "source text"})
        self.assertEqual(score["answer_f1"], 1)
        self.assertAlmostEqual(score["evidence_f1"], 2 / 3)
        self.assertEqual(score["unresolved_gold"], 1)
        other = copy.deepcopy(question["gold"]["annotations"][0])
        other["extractive_spans"] = ["Venus"]
        question["gold"]["annotations"].append(other)
        self.assertEqual(score_one(question, self.predicted("Venus"), {"p1": "source text"})["answer_f1"], 1)

    def test_failed_unanswerable_is_zero_including_evidence(self):
        question = self.qasper()
        question["gold"]["annotations"][0]["unanswerable"] = True
        question["gold"]["annotations"][0]["evidence"] = []
        score = score_one(question, self.predicted("Unanswerable", "FAILED", ()), {})
        self.assertEqual(score["answer_f1"], 0)
        self.assertEqual(score["evidence_f1"], 0)
        self.assertEqual(score["answerability_correct"], 0)

    def test_musique_metrics_use_answerable_rows_and_no_paired_score(self):
        question = {"id": "q", "dataset": "musique", "source_question_id": "2hop-q", "retrieval_mode": "distractor",
                    "gold": {"answer": "Mercury", "answer_aliases": ["The Mercury"], "support_ids": ["p1"],
                             "decomposition": [{}, {}], "answerable": True}}
        scores = [score_one(question, self.predicted("The Mercury"), {})]
        question["gold"]["answerable"] = False
        scores.append(score_one(question, self.predicted("", "FAILED"), {}))
        group = aggregate(scores)["musique/B"]
        self.assertEqual(group["records"], 2)
        self.assertEqual(group["answer_scored_records"], 1)
        self.assertEqual(group["answer_f1"], 1)
        self.assertEqual(group["answerability_accuracy"], .5)
        self.assertIsNone(group["paired_sufficiency_metrics"])
        question["retrieval_mode"] = "pooled-context"
        with self.assertRaises(ValueError):
            score_one(question, self.predicted(), {})

    def test_snapshot_locations_expand_real_paragraph_ids_only(self):
        self.assertEqual(source_ids({"chunks": [{"source_paragraph_id": "p1"}, {"sourceParagraphId": "p2"}], "chunk_index": 5}), {"p1", "p2"})
        self.assertEqual(source_ids({"section_path": ["fictional source"], "chunk_index": 1}), set())

    def test_prediction_rejects_answer_format_and_failure_as_abstention(self):
        envelope = {"mode": "C", "elapsedMillis": 15, "sources": [],
                    "run": {"id": "run", "status": "COMPLETED", "usage": {}, "artifact": {"sections": [
                        {"heading": "Discussion", "text": "Mercury"}], "citations": []}}}
        query = {"id": "q", "dataset": "qasper", "split": "validation"}
        self.assertTrue(prediction(envelope, query)["answer_format_error"])
        envelope["run"].update(status="FAILED", artifact=None)
        self.assertIsNone(prediction(envelope, query)["predicted_answerable"])

    def test_gold_fields_cannot_enter_evaluation_requests(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = {"scope": {"qasper": "paper", "musique": "distractor"}, "generation_instruction": "English answer"}
            for dataset, scope, mode in (("qasper", "qasper-validation", "paper"), ("musique", "musique-dev", "distractor")):
                directory = root / scope
                directory.mkdir()
                query = {"schema_version": "research-queries-v1", "dataset": dataset, "split": "validation", "question": "What is X?",
                         "id": dataset + "-q", "document_ids": ["doc"], "retrieval_mode": mode}
                (directory / "queries.smoke.jsonl").write_text(json.dumps(query) + "\n")
            cases, queries = prepare_cases(root, "smoke", config)
            self.assertEqual(len(cases), 2)
            self.assertNotIn("gold", json.dumps(cases))
            query["answer"] = "leaked label"
            (root / "musique-dev/queries.smoke.jsonl").write_text(json.dumps(query) + "\n")
            with self.assertRaises(ValueError):
                prepare_cases(root, "smoke", config)

    def test_usage_merge_keeps_unknown_and_counts_paid_failures(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            attempt = root / "attempts/0000_A"
            attempt.mkdir(parents=True)
            (attempt / "usage.jsonl").write_text(json.dumps({"call": {"callId": "c", "model": "flash", "status": "FAILED", "usageStatus": "provider", "inputTokens": 1000, "outputTokens": 100}}) + "\n"
                + json.dumps({"call": {"callId": "d", "model": "flash", "status": "CANCELLED", "usageStatus": "unknown"}}) + "\n")
            (attempt / "embedding-usage.jsonl").write_text(json.dumps({"call_id": "e", "usage": None, "usage_status": "unknown"}) + "\n"
                + json.dumps({"call_id": "e", "usage": {"total_tokens": 7}, "usage_status": "provider"}) + "\n")
            summary = resource_summary(root, {"prices": {"chat_tiers": [[32000, .2, .8]]}})
            self.assertEqual(summary["model_requests"], 2)
            self.assertEqual(summary["model_usage_unknown"], 1)
            self.assertEqual(summary["embedding_requests"], 1)
            self.assertEqual(summary["embedding_known_total_tokens"], 7)
            self.assertGreater(summary["budget_reserve_cny"], summary["known_generation_cost_estimate_cny"])

    def test_partial_or_limited_full_profile_cannot_claim_full_execution(self):
        self.assertFalse(full_execution_boundaries("full", {"q1", "q2"}, {"q1", "q2"}, list("ABC"), 5)["full_split_executed"])
        self.assertFalse(full_execution_boundaries("full", {"q1"}, {"q1", "q2"}, list("ABC"), 3)["full_split_executed"])
        completed = full_execution_boundaries("full", {"q1", "q2"}, {"q1", "q2"}, list("ABC"), 6)
        self.assertTrue(completed["full_abc_executed"])
        self.assertFalse(full_execution_boundaries("regression", {"q1", "q2"}, {"q1", "q2"}, list("ABC"), 6)["full_split_executed"])


if __name__ == "__main__":
    unittest.main()
