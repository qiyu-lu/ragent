import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import stage3_sensitivity as ss  # noqa: E402

STEMS = {"si": "doc-si", "tfe": "doc-tfe"}


def detail(qid, chunks, anchors=("400°C",), reference=("doc-si",), timed_out=False, qtype="numeric"):
    """chunks: (doc stem, text) in final order."""

    candidates = [{"id": f"c{i}", "docName": f"{doc}.pdf", "text": text, "finalRank": i + 1, "finalSelected": True}
                  for i, (doc, text) in enumerate(chunks)]
    return {"id": qid, "type": qtype, "answerable": True, "anchors": list(anchors), "reference_docs": list(reference),
            "raw_response": {"results": [{"candidates": candidates}], "finalChunkIds": [c["id"] for c in candidates],
                             "stages": [{"stage": "channel-VectorSearch", "chunkCount": 0 if timed_out else 20}]}}


class QuestionScoreTest(unittest.TestCase):
    def test_doc_scoping_keeps_the_rank_but_drops_hits_from_other_documents(self):
        item = detail("q", [("tfe", "灼烧 400 ℃"), ("si", "温度控制在 400 ℃")])
        self.assertEqual(ss.question_score(item, STEMS, False)["mrr"], 1.0)
        self.assertEqual(ss.question_score(item, STEMS, True)["mrr"], 0.5)
        only_other = detail("q", [("tfe", "灼烧 400 ℃")])
        self.assertEqual(ss.question_score(only_other, STEMS, True)["hit@5"], 0.0)

    def test_a_timed_out_vector_channel_is_recognised(self):
        self.assertTrue(ss.vector_timed_out(detail("q", [], timed_out=True)["raw_response"]))
        self.assertFalse(ss.vector_timed_out(detail("q", [])["raw_response"]))


class ChangedQuestionsTest(unittest.TestCase):
    def test_added_timeout_and_lost(self):
        hit, miss = [("si", "温度控制在 400 ℃")], [("si", "称量")]
        base = [{"details": [detail("added", miss), detail("timeout", hit), detail("lost", hit)]},
                {"details": [detail("added", miss), detail("timeout", miss, timed_out=True), detail("lost", hit)]}]
        arm = [{"details": [detail("added", hit), detail("timeout", hit), detail("lost", hit)]},
               {"details": [detail("added", hit), detail("timeout", hit), detail("lost", miss)]}]
        out = ss.changed_questions(base, arm, STEMS)
        self.assertEqual(out["added"], ["added"])
        self.assertEqual(out["timeout"], ["timeout"])
        self.assertEqual(out["lost"], ["lost"])

    def test_healthy_view_drops_timed_out_runs_per_question(self):
        reports = [{"details": [detail("q", [("si", "称量")], timed_out=True)]},
                   {"details": [detail("q", [("si", "温度控制在 400 ℃")])]}]
        table = ss.per_question(reports, STEMS, doc_scoped=False, healthy_only=True)
        self.assertEqual(len(table["q"]), 1)
        self.assertEqual(table["q"][0]["mrr"], 1.0)


if __name__ == "__main__":
    unittest.main()
