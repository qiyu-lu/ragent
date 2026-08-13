import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parents[1]


class ReviewPipelineTest(unittest.TestCase):
    def test_blind_and_score_two_arms(self):
        with tempfile.TemporaryDirectory() as raw_tmp:
            tmp = Path(raw_tmp)
            dataset = tmp / "dataset.jsonl"
            rows = [
                {
                    "id": "q1",
                    "tier": "direct",
                    "family": "xlsx",
                    "question": "可回答？",
                    "answerable": True,
                    "reference_docs": ["doc"],
                    "reference_anchors": ["fact"],
                    "source": "s1",
                    "expected_facts": ["fact"],
                    "forbidden_claims": ["bad"],
                    "expected_kbs": ["iron"],
                    "expected_intent_ids": ["i"],
                    "intent_scored": True,
                    "routing_scored": True,
                },
                {
                    "id": "q2",
                    "tier": "unanswerable",
                    "family": "negative",
                    "question": "不可回答？",
                    "answerable": False,
                    "reference_docs": [],
                    "reference_anchors": [],
                    "source": "none",
                    "expected_facts": ["资料未提供"],
                    "forbidden_claims": ["bad"],
                    "expected_kbs": [],
                    "expected_intent_ids": [],
                    "intent_scored": False,
                    "routing_scored": False,
                },
            ]
            dataset.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")

            arm_paths = []
            for arm in ("B0", "C-final"):
                path = tmp / f"{arm}.json"
                path.write_text(
                    json.dumps(
                        {
                            "kind": "answers",
                            "variant": arm,
                            "server_commit": "abc",
                            "configuration": {},
                            "details": [
                                {"id": "q1", "answer": "fact", "sources": [], "wall_ms": 10},
                                {"id": "q2", "answer": "资料未提供", "sources": [], "wall_ms": 20},
                            ],
                        },
                        ensure_ascii=False,
                    ),
                    encoding="utf-8",
                )
                arm_paths.append((arm, path))

            review = tmp / "review.jsonl"
            key = tmp / "key.json"
            command = [
                sys.executable,
                str(HERE / "prepare_review.py"),
                "--dataset",
                str(dataset),
                "--output",
                str(review),
                "--key",
                str(key),
            ]
            for arm, path in arm_paths:
                command.extend(["--arm", f"{arm}={path}"])
            subprocess.run(command, check=True, stdout=subprocess.PIPE, text=True)

            labeled = []
            for raw in review.read_text(encoding="utf-8").splitlines():
                row = json.loads(raw)
                row["no_forbidden_claim"] = True
                if row["answerable"]:
                    row["fact_correct"] = True
                    row["evidence_supported"] = True
                    row["source_correct"] = True
                else:
                    row["refusal_correct"] = True
                labeled.append(row)
            review.write_text(
                "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in labeled), encoding="utf-8"
            )

            score = tmp / "score.json"
            subprocess.run(
                [
                    sys.executable,
                    str(HERE / "score_review.py"),
                    "--review",
                    str(review),
                    "--key",
                    str(key),
                    "--output",
                    str(score),
                ],
                check=True,
                stdout=subprocess.PIPE,
                text=True,
            )
            report = json.loads(score.read_text(encoding="utf-8"))
            self.assertEqual(report["summary"]["B0"]["overall"]["strict_pass_rate"], 1.0)
            self.assertEqual(report["summary"]["C-final"]["overall"]["strict_pass_rate"], 1.0)


if __name__ == "__main__":
    unittest.main()
