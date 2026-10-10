import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import verify_boost_beta as vb  # noqa: E402

TERMS = vb.Terms([{"term": "钛铁矿精矿", "synonyms": "", "category": "检测对象"},
                  {"term": "全铁", "synonyms": "全铁量", "category": "组分"}])
METADATA = {"YS.pdf": {"objects": ["钛铁矿精矿"], "components": ["全铁"]},
            "GB.pdf": {"objects": ["铁矿石"], "components": ["全铁"]},
            "SV.xlsx": {"objects": ["铁矿石"], "components": ["工序与设备"]}}


def candidate(cid, doc, rerank):
    return {"id": cid, "docName": doc, "rerankScore": rerank, "rerankHead": True}


def report(arm, order, stage=True, empty=0):
    # rerank scores: A 0.60 (match 1), B 0.81 (match 0), C 0.72 (match 0.5)
    # beta 0.1 -> B, C, A; beta 0.2 -> C, B, A; beta 0.3 -> A, C, B
    pool = {"A": candidate("A", "YS.pdf", 0.60), "B": candidate("B", "SV.xlsx", 0.81), "C": candidate("C", "GB.pdf", 0.72)}
    stages = [{"stage": "channel-VectorSearch", "chunkCount": 20}]
    if stage:
        stages.append({"stage": vb.BOOST_STAGE, "chunkCount": 20})
    details = [{"raw_response": {"stages": stages, "results": [
        {"subQuestion": "钛铁矿精矿测全铁量", "candidates": [pool[c] for c in order]}]}}]
    details += [{"raw_response": {"stages": [{"stage": "channel-VectorSearch", "chunkCount": 0}] + stages[1:],
                                  "results": []}} for _ in range(empty)]
    return {"arm": arm, "details": details}


class VerifyBoostBetaTest(unittest.TestCase):
    def check(self, rep, max_empty=8):
        return vb.check_run(rep, TERMS, METADATA, [0.1, 0.2, 0.3], max_empty)

    def test_recovers_the_beta_from_the_boosted_order(self):
        ok, line = self.check(report("S2-boost-0.2", ["C", "B", "A"]))
        self.assertTrue(ok, line)
        self.assertIn("boost=0.2", line)

    def test_flags_an_instance_still_on_another_beta(self):
        ok, line = self.check(report("S2-boost-0.3", ["C", "B", "A"]))
        self.assertFalse(ok)
        self.assertIn("fits beta 0.2", line)

    def test_flags_boost_missing_or_unexpected(self):
        ok, line = self.check(report("S2-boost-0.2", ["B", "C", "A"], stage=False))
        self.assertFalse(ok)
        self.assertIn("boost stage missing", line)
        ok, line = self.check(report("S2-gate", ["B", "C", "A"]))
        self.assertFalse(ok)
        self.assertIn("should run without boost", line)
        ok, _ = self.check(report("S2-gate", ["B", "C", "A"], stage=False))
        self.assertTrue(ok)

    def test_flags_too_many_channel_timeouts(self):
        ok, line = self.check(report("S2-boost-0.1", ["B", "C", "A"], empty=3), max_empty=2)
        self.assertFalse(ok)
        self.assertIn("3 empty-channel sub-questions > 2", line)

    def test_match_share(self):
        self.assertEqual(vb.match(["钛铁矿精矿"], ["全铁"], METADATA["GB.pdf"]), 0.5)
        self.assertEqual(vb.match([], [], METADATA["YS.pdf"]), 0.0)


if __name__ == "__main__":
    unittest.main()
