import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import doc_metadata  # noqa: E402


class DocMetadataTest(unittest.TestCase):
    metadata = {
        "standardNo": "GB/T 6730.10-2014", "replaces": ["GB/T 6730.10-1986"], "objects": ["铁矿石"],
        "components": ["硅"], "methods": ["重量法"], "source": "extracted",
        "parseAudit": {"verdict": "RECOVERED", "textLayerClass": "TEXT", "chosenAttempt": 1, "attempts": [
            {"params": {"isOcr": False}, "digitRetention": 0.47, "strictSlotsPer1000": 7.8, "failures": ["slots"]},
            {"params": {"isOcr": True}, "digitRetention": 1.02, "strictSlotsPer1000": 0.1, "failures": []}]},
        "normalization": {"applied": True, "repeatedLinesRemoved": 10},
    }

    def test_review_row_digests_the_chosen_attempt(self):
        row = doc_metadata.review_row("42", self.metadata)
        self.assertEqual(row["standardNo"], "GB/T 6730.10-2014")
        self.assertEqual(row["_parse"]["verdict"], "RECOVERED")
        self.assertEqual(row["_parse"]["chosenParams"], {"isOcr": True})
        self.assertEqual(row["_parse"]["firstAttemptFailures"], ["slots"])
        self.assertEqual(row["_parse"]["repeatedLinesRemoved"], 10)

    def test_confirm_body_sends_only_governance_fields(self):
        row = doc_metadata.review_row("42", self.metadata)
        row["components"] = None
        body = doc_metadata.confirm_body(row)
        self.assertEqual(set(body), set(doc_metadata.GOVERNANCE_FIELDS))
        self.assertEqual(body["components"], [])


if __name__ == "__main__":
    unittest.main()
