import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import prepare_kb  # noqa: E402


class PrepareKbPlanTest(unittest.TestCase):
    docs = [{"id": "si", "doc_name": "si.pdf"}, {"id": "tfe", "doc_name": "tfe.pdf"},
            {"id": "feo", "doc_name": "feo.pdf"}, {"id": "smp", "doc_name": "smp.pdf"}]

    def test_resume_plan_skips_success_rechunks_failed_waits_running_uploads_missing(self):
        existing = {
            "si.pdf": {"id": "1", "status": "success", "chunkCount": 16},
            "tfe.pdf": {"id": "2", "status": "failed", "chunkCount": 0},
            "feo.pdf": {"id": "3", "status": "running", "chunkCount": 0},
        }
        plan = prepare_kb.plan_actions(self.docs, existing)
        self.assertEqual([(doc["id"], action) for doc, action, _ in plan],
                         [("si", "skip"), ("tfe", "rechunk"), ("feo", "wait"), ("smp", "upload")])
        self.assertEqual(plan[0][2]["chunkCount"], 16)
        self.assertIsNone(plan[3][2])

    def test_fresh_kb_uploads_everything(self):
        plan = prepare_kb.plan_actions(self.docs, {})
        self.assertTrue(all(action == "upload" for _, action, _ in plan))


if __name__ == "__main__":
    unittest.main()
