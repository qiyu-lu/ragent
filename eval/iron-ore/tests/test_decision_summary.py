import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parents[1]


class DecisionAndSummaryTest(unittest.TestCase):
    def write_json(self, path, payload):
        path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

    def retrieval(self, intent=None, routing=0.5):
        overall = {
            "anchor_hit@5_any": 0.5,
            "anchor_hit@5_all": 0.4,
            "anchor_recall": 0.6,
            "context_precision": 0.7,
            "routing_purity": routing,
        }
        if intent is not None:
            overall["intent_top1_correct"] = intent
        return {
            "kind": "retrieval",
            "dataset_sha256": "dataset",
            "summary": {
                "overall_answerable": overall,
                "by_family": {
                    family: {"n": 6, "anchor_hit@5_any": 0.5}
                    for family in ("xlsx", "native_pdf", "scan_pdf", "cross_domain")
                },
            },
        }

    def test_gate_and_sanitized_summary(self):
        with tempfile.TemporaryDirectory() as raw_tmp:
            tmp = Path(raw_tmp)
            c0 = tmp / "c0.json"
            c1 = tmp / "c1.json"
            b0 = tmp / "b0.json"
            cfinal = tmp / "cfinal.json"
            self.write_json(c0, self.retrieval())
            self.write_json(b0, self.retrieval())
            self.write_json(cfinal, self.retrieval())
            self.write_json(c1, self.retrieval(intent=0.95, routing=0.8))

            parse_off = tmp / "off.json"
            parse_on = tmp / "on.json"
            self.write_json(
                parse_off,
                {"kind": "parse-audit", "details": [
                    {"family": "native_pdf", "anchor_recovered": 5},
                    {"family": "scan_pdf", "anchor_recovered": 2},
                ]},
            )
            self.write_json(
                parse_on,
                {"kind": "parse-audit", "details": [
                    {"family": "native_pdf", "anchor_recovered": 5},
                    {"family": "scan_pdf", "anchor_recovered": 3},
                ]},
            )
            decision = tmp / "decision.json"
            subprocess.run(
                [sys.executable, str(HERE / "select_final_config.py"),
                 "--current-default", str(c0), "--current-intent", str(c1),
                 "--parse-ocr-off", str(parse_off), "--parse-ocr-on", str(parse_on),
                 "--output", str(decision)],
                check=True, stdout=subprocess.PIPE, text=True,
            )
            selected = json.loads(decision.read_text(encoding="utf-8"))
            self.assertEqual(selected["final"], {"intent_mode": "on", "ocr": "on"})

            human = tmp / "human.json"
            arm_summary = {
                "overall": {"strict_pass_rate": 0.8, "latency_ms": {"p95": 100}},
                "answerable": {"strict_pass_rate": 0.9},
                "unanswerable": {"refusal_correct": 0.7},
            }
            self.write_json(
                human,
                {"kind": "human-review-score", "summary": {"B0": arm_summary, "C-final": arm_summary}},
            )
            summary = tmp / "summary.md"
            subprocess.run(
                [sys.executable, str(HERE / "render_summary.py"),
                 "--b0-retrieval", str(b0), "--c0-retrieval", str(c0),
                 "--c-final-retrieval", str(cfinal), "--human-score", str(human),
                 "--decision", str(decision), "--output", str(summary)],
                check=True, stdout=subprocess.PIPE, text=True,
            )
            text = summary.read_text(encoding="utf-8")
            self.assertIn("C-final 配置：意图 `on`，OCR `on`", text)
            self.assertNotIn("可回答？", text)


if __name__ == "__main__":
    unittest.main()
