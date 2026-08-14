import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))

from compare_retrieval_repeats import (
    TARGET_MISSING_ANCHOR,
    assert_compatible,
    build_comparison,
    organize,
)
from run_retrieval import load_fixed_rewrites


class RetrievalRepeatTest(unittest.TestCase):
    def write_json(self, path, payload):
        path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

    def source_report(self):
        return {
            "kind": "retrieval",
            "label": "D1",
            "server_commit": "source-commit",
            "dataset_sha256": "dataset",
            "corpus_sha256": "corpus",
            "setup_manifest_sha256": "setup",
            "selection": {"families": None, "ids": None, "n": 2},
            "failures": [],
            "details": [
                {"id": "q1", "question": "问题一", "raw_response": {"subIntents": ["子问题一"]}},
                {"id": "q2", "question": "问题二", "raw_response": {"subIntents": ["甲", "乙"]}},
            ],
        }

    def test_load_fixed_rewrites_requires_exact_compatible_ids(self):
        rows = [{"id": "q1", "question": "问题一"}, {"id": "q2", "question": "问题二"}]
        with tempfile.TemporaryDirectory() as raw_tmp:
            path = Path(raw_tmp) / "source.json"
            self.write_json(path, self.source_report())
            rewrites, metadata = load_fixed_rewrites(
                path,
                rows,
                dataset_sha256="dataset",
                corpus_sha256="corpus",
                setup_manifest_sha256="setup",
            )
            self.assertEqual(rewrites, {"q1": ["子问题一"], "q2": ["甲", "乙"]})
            self.assertEqual(metadata["source_label"], "D1")

            invalid = self.source_report()
            invalid["details"][1]["raw_response"]["subIntents"] = ["重复", "重复"]
            self.write_json(path, invalid)
            with self.assertRaisesRegex(ValueError, "duplicate"):
                load_fixed_rewrites(
                    path,
                    rows,
                    dataset_sha256="dataset",
                    corpus_sha256="corpus",
                    setup_manifest_sha256="setup",
                )

            invalid_cases = []
            wrong_kind = copy.deepcopy(self.source_report())
            wrong_kind["kind"] = "answer"
            invalid_cases.append(("kind", wrong_kind, "kind=retrieval"))
            wrong_hash = copy.deepcopy(self.source_report())
            wrong_hash["corpus_sha256"] = "different"
            invalid_cases.append(("hash", wrong_hash, "corpus_sha256 mismatch"))
            missing_id = copy.deepcopy(self.source_report())
            missing_id["details"].pop()
            invalid_cases.append(("id", missing_id, "missing detail ids: q2"))
            empty_rewrite = copy.deepcopy(self.source_report())
            empty_rewrite["details"][0]["raw_response"]["subIntents"] = []
            invalid_cases.append(("empty", empty_rewrite, "must be non-empty"))

            for name, payload, message in invalid_cases:
                with self.subTest(name=name):
                    self.write_json(path, payload)
                    with self.assertRaisesRegex(ValueError, message):
                        load_fixed_rewrites(
                            path,
                            rows,
                            dataset_sha256="dataset",
                            corpus_sha256="corpus",
                            setup_manifest_sha256="setup",
                        )

    def diagnostics(self, enabled, final, refill):
        return {
            "fairRefillEnabled": enabled,
            "requestTopK": 3,
            "initialBudgets": [2, 1],
            "candidateCount": 5,
            "candidateUniqueCount": 4,
            "uniqueBeforeRefill": final - refill,
            "refillAdded": refill,
            "finalUniqueCount": final,
            "unfilledSlots": 3 - final,
        }

    def detail(self, question_id, mode, target=False):
        enabled = mode == "on"
        final = 3 if enabled or not target else 2
        refill = 1 if enabled and target else 0
        anchor_recall = 1.0 if enabled or not target else 0.5
        chunk_ids = [f"{question_id}-{index}" for index in range(final)]
        score = {
            "n_chunks": final,
            "anchor_hit@5_any": 1.0,
            "anchor_hit@5_all": anchor_recall,
            "anchor_recall": anchor_recall,
            "context_precision": 0.6 if enabled else 0.5,
            "doc_recall": 1.0,
            "routing_purity": 1.0,
            "missed_anchors": [] if anchor_recall == 1.0 else [TARGET_MISSING_ANCHOR],
        }
        return {
            "id": question_id,
            "tier": "hard" if target else "direct",
            "family": "xlsx",
            "question": "目标问题" if target else "普通问题",
            "answerable": True,
            "reference_docs": ["doc"],
            "reference_anchors": (
                [TARGET_MISSING_ANCHOR, "另一锚点"] if target else ["普通锚点"]
            ),
            "score": score,
            "raw_response": {
                "retrievedChunkIds": chunk_ids,
                "retrievedContexts": [f"正文-{value}" for value in chunk_ids],
                "subIntents": ["固定子问题一", "固定子问题二"],
                "retrievalDiagnostics": self.diagnostics(enabled, final, refill),
            },
        }

    def repeat_report(self, mode, repeat_index):
        details = [self.detail("xlsx-hard-06", mode, target=True)]
        details.extend(self.detail(f"q{index:02d}", mode) for index in range(1, 24))
        overall = {
            metric: sum(detail["score"][metric] for detail in details) / len(details)
            for metric in (
                "anchor_hit@5_any",
                "anchor_hit@5_all",
                "anchor_recall",
                "context_precision",
                "doc_recall",
                "routing_purity",
            )
        }
        return {
            "kind": "retrieval",
            "variant": "current",
            "server_commit": "same-commit",
            "dataset_sha256": "dataset",
            "corpus_sha256": "corpus",
            "setup_manifest_sha256": "setup",
            "selection": {"families": None, "ids": None, "n": 24},
            "configuration": {
                "intent_mode": "off",
                "ocr": "off",
                "concurrency": 1,
                "rewrite": {"mode": "replay", "source_sha256": "fixed-source"},
                "refill_mode": mode,
                "repeat_index": repeat_index,
                "retrieval": {"default_top_k": 3},
            },
            "failures": [],
            "summary": {
                "overall_answerable": overall,
                "latency_ms": {"p95": 100 + repeat_index},
            },
            "details": details,
        }

    def test_compare_three_repeats_reports_median_directions_and_diagnostics(self):
        with tempfile.TemporaryDirectory() as raw_tmp:
            tmp = Path(raw_tmp)
            off_paths = []
            on_paths = []
            for mode, paths in (("off", off_paths), ("on", on_paths)):
                for repeat_index in (1, 2, 3):
                    path = tmp / f"{mode}-{repeat_index}.json"
                    self.write_json(path, self.repeat_report(mode, repeat_index))
                    paths.append(path)
            output = tmp / "comparison.json"
            command = [sys.executable, str(HERE / "compare_retrieval_repeats.py")]
            for path in off_paths:
                command.extend(["--off", str(path)])
            for path in on_paths:
                command.extend(["--on", str(path)])
            command.extend(["--output", str(output)])
            subprocess.run(command, check=True, stdout=subprocess.PIPE, text=True)

            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(report["kind"], "retrieval-repeat-comparison")
            self.assertAlmostEqual(
                report["metrics"]["anchor_recall"]["median_delta"],
                0.5 / 24,
            )
            self.assertEqual(
                report["xlsx-hard-06"]["metrics"]["anchor_recall"]["median_delta"],
                0.5,
            )
            directions = report["per_question"]["anchor_recall"]["median_direction"]
            self.assertEqual(directions["improved"]["ids"], ["xlsx-hard-06"])
            self.assertEqual(report["diagnostics"]["on"]["runs"][0]["refill_added"], 1)
            self.assertEqual(
                report["diagnostics"]["on"]["runs"][0][
                    "legacy_gap_recovered_questions"
                ],
                1,
            )
            self.assertEqual(report["xlsx-hard-06"]["on"][0]["missed_anchors"], [])
            self.assertEqual(len(report["input_reports"]["off"][0]["sha256"]), 64)
            self.assertEqual(report["gate"]["decision"], "pass")
            self.assertTrue(report["gate"]["passed"])
            self.assertEqual(report["gate"]["failed_criteria"], [])
            self.assertTrue(
                report["gate"]["criteria"]["xlsx_hard_06_missing_anchor_recovered"][
                    "passed"
                ]
            )
            self.assertFalse(report["gate"]["record_only"]["included_in_decision"])

    def test_gate_fails_when_target_recovery_and_anchor_gain_are_not_met(self):
        with tempfile.TemporaryDirectory() as raw_tmp:
            tmp = Path(raw_tmp)
            off_paths = []
            on_paths = []
            for mode, paths in (("off", off_paths), ("on", on_paths)):
                for repeat_index in (1, 2, 3):
                    report = self.repeat_report(mode, repeat_index)
                    if mode == "on" and repeat_index in {2, 3}:
                        target = report["details"][0]
                        target["score"]["anchor_recall"] = 0.5
                        target["score"]["anchor_hit@5_all"] = 0.5
                        target["score"]["missed_anchors"] = [TARGET_MISSING_ANCHOR]
                        for metric in (
                            "anchor_hit@5_any",
                            "anchor_hit@5_all",
                            "anchor_recall",
                            "context_precision",
                            "doc_recall",
                            "routing_purity",
                        ):
                            report["summary"]["overall_answerable"][metric] = sum(
                                detail["score"][metric] for detail in report["details"]
                            ) / len(report["details"])
                    path = tmp / f"{mode}-{repeat_index}.json"
                    self.write_json(path, report)
                    paths.append(path)

            output = tmp / "comparison.json"
            command = [sys.executable, str(HERE / "compare_retrieval_repeats.py")]
            for path in off_paths:
                command.extend(["--off", str(path)])
            for path in on_paths:
                command.extend(["--on", str(path)])
            command.extend(["--output", str(output)])
            subprocess.run(command, check=True, stdout=subprocess.PIPE, text=True)

            gate = json.loads(output.read_text(encoding="utf-8"))["gate"]
            self.assertEqual(gate["decision"], "fail")
            self.assertFalse(gate["passed"])
            self.assertIn("xlsx_hard_06_missing_anchor_recovered", gate["failed_criteria"])
            self.assertIn("overall_anchor_recall_gain", gate["failed_criteria"])
            recovery = gate["criteria"]["xlsx_hard_06_missing_anchor_recovered"]
            self.assertEqual(recovery["observed"]["paired_recovery_count"], 1)

    def test_gate_does_not_call_an_existing_baseline_hit_a_recovery(self):
        with tempfile.TemporaryDirectory() as raw_tmp:
            tmp = Path(raw_tmp)
            off_paths = []
            on_paths = []
            for mode, paths in (("off", off_paths), ("on", on_paths)):
                for repeat_index in (1, 2, 3):
                    report = self.repeat_report(mode, repeat_index)
                    if mode == "off":
                        target = report["details"][0]
                        target["score"]["anchor_recall"] = 1.0
                        target["score"]["anchor_hit@5_all"] = 1.0
                        target["score"]["missed_anchors"] = []
                        for metric in (
                            "anchor_hit@5_any",
                            "anchor_hit@5_all",
                            "anchor_recall",
                            "context_precision",
                            "doc_recall",
                            "routing_purity",
                        ):
                            report["summary"]["overall_answerable"][metric] = sum(
                                detail["score"][metric] for detail in report["details"]
                            ) / len(report["details"])
                    path = tmp / f"{mode}-{repeat_index}.json"
                    self.write_json(path, report)
                    paths.append(path)

            comparison = build_comparison(
                organize(off_paths, "off"), organize(on_paths, "on")
            )
            recovery = comparison["gate"]["criteria"][
                "xlsx_hard_06_missing_anchor_recovered"
            ]
            self.assertFalse(recovery["passed"])
            self.assertEqual(recovery["observed"]["paired_recovery_count"], 0)
            self.assertEqual(
                recovery["observed"]["paired_recovered_by_repeat"],
                [False, False, False],
            )

    def test_compare_rejects_incompatible_server_commit(self):
        with tempfile.TemporaryDirectory() as raw_tmp:
            tmp = Path(raw_tmp)
            off_paths = []
            on_paths = []
            for mode, paths in (("off", off_paths), ("on", on_paths)):
                for repeat_index in (1, 2, 3):
                    report = self.repeat_report(mode, repeat_index)
                    if mode == "on" and repeat_index == 3:
                        report["server_commit"] = "different-commit"
                    path = tmp / f"{mode}-{repeat_index}.json"
                    self.write_json(path, report)
                    paths.append(path)
            off = organize(off_paths, "off")
            on = organize(on_paths, "on")
            with self.assertRaisesRegex(ValueError, "incompatible server_commit"):
                assert_compatible(off, on)

    def test_gate_fails_when_fixed_sub_intents_differ(self):
        with tempfile.TemporaryDirectory() as raw_tmp:
            tmp = Path(raw_tmp)
            paths_by_mode = {"off": [], "on": []}
            for mode in ("off", "on"):
                for repeat_index in (1, 2, 3):
                    report = self.repeat_report(mode, repeat_index)
                    if mode == "on" and repeat_index == 3:
                        report["details"][0]["raw_response"]["subIntents"] = ["漂移的子问题"]
                    path = tmp / f"{mode}-{repeat_index}.json"
                    self.write_json(path, report)
                    paths_by_mode[mode].append(path)

            comparison = build_comparison(
                organize(paths_by_mode["off"], "off"),
                organize(paths_by_mode["on"], "on"),
            )
            gate = comparison["gate"]
            self.assertEqual(gate["decision"], "fail")
            self.assertIn("fixed_sub_intents_exact", gate["failed_criteria"])
            mismatches = gate["criteria"]["fixed_sub_intents_exact"]["observed"][
                "mismatches"
            ]
            self.assertEqual(
                mismatches,
                [{"mode": "on", "repeat_index": 3, "id": "xlsx-hard-06"}],
            )


if __name__ == "__main__":
    unittest.main()
