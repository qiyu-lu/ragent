import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import verify_dataset  # noqa: E402

DOCS = {
    "si": {"id": "si", "path": "/nonexistent/si.pdf", "text_layer": "text"},
    "scan": {"id": "scan", "path": "/nonexistent/scan.pdf", "text_layer": "scan"},
}


def question(**overrides):
    row = {"id": "q1", "type": "numeric", "split": "tune", "question": "碱融法温度是多少", "answerable": True,
           "reference_docs": ["si"], "anchors": ["400 ℃±20 ℃"], "paraphrased": True}
    row.update(overrides)
    return row


class VerifyDatasetTest(unittest.TestCase):
    def test_valid_rows_pass(self):
        rows = [question(), question(id="q2", split="test", question="碱融要几分钟", anchors=["1min~2min"]),
                question(id="u1", type="unanswerable", answerable=False, reference_docs=[], anchors=[], question="磷含量怎么测")]
        self.assertEqual(verify_dataset.validate_questions(rows, DOCS), [])
        self.assertEqual(verify_dataset.balance_errors(rows, strict=False), [])

    def test_structural_errors_are_reported(self):
        rows = [question(), question(id="q1"), question(id="q3", type="bogus", split="dev", question=" "),
                question(id="q4", reference_docs=["nope"], anchors=[]),
                question(id="u2", type="unanswerable", answerable=False, reference_docs=["si"], anchors=["x"], question="别的问题"),
                question(id="q5", anchors=["a", "b", "c", "d"], question="第五问"),
                question(id="u3", type="unanswerable", answerable=True, question="又一问")]
        errors = "\n".join(verify_dataset.validate_questions(rows, DOCS))
        for fragment in ("duplicate id", "invalid type", "invalid split", "empty question", "unknown reference doc",
                         "1..3 anchors", "must not carry", "unanswerable type must have answerable=false"):
            self.assertIn(fragment, errors)

    def test_split_imbalance_is_an_error(self):
        rows = [question(id=f"q{i}", question=f"问题{i}", split="tune") for i in range(3)]
        self.assertTrue(any("differ by more than 1" in e for e in verify_dataset.balance_errors(rows, strict=False)))

    def test_facts_validation(self):
        facts = [{"id": "f1", "doc": "si", "page": 5, "clause": "7.4.1.1", "anchor": "400 ℃±20 ℃", "kind": "temperature"},
                 {"id": "f1", "doc": "nope", "page": "5", "clause": "", "anchor": "", "kind": "odd", "source": "guess"}]
        errors = "\n".join(verify_dataset.validate_facts(facts, DOCS))
        for fragment in ("duplicate fact id", "unknown doc", "empty anchor", "empty clause", "page must be", "invalid kind", "source must be"):
            self.assertIn(fragment, errors)

    def test_anchor_lookup_uses_text_or_manual_facts(self):
        texts = {"si": "温度控制在 400 ℃±20 ℃ ,放置 1min", "scan": "garbage"}
        facts = [{"id": "s1", "doc": "scan", "anchor": "600 ℃灼烧", "source": "manual", "clause": "3", "kind": "temperature"},
                 {"id": "s2", "doc": "scan", "anchor": "950~1000 ℃", "source": "text", "clause": "6.7", "kind": "temperature"},
                 {"id": "t1", "doc": "si", "anchor": "不存在的事实", "clause": "x", "kind": "other"}]
        rows = [question(), question(id="q2", question="第二", anchors=["缺失锚点"]),
                question(id="q3", question="第三", reference_docs=["scan"], anchors=["600 ℃灼烧"]),
                question(id="q4", question="第四", reference_docs=["scan"], anchors=["没有的"])]
        errors = verify_dataset.anchor_errors(rows, facts, DOCS, texts)
        joined = "\n".join(errors)
        self.assertIn("[q2] anchor not found", joined)
        self.assertIn("[q4] anchor not found", joined)
        self.assertIn("[s2] scan document facts must be source=manual", joined)
        self.assertIn("[t1] fact anchor not in source text", joined)
        self.assertNotIn("[q1]", joined)
        self.assertNotIn("[q3]", joined)


if __name__ == "__main__":
    unittest.main()
