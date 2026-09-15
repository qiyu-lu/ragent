from __future__ import annotations

import argparse
import json
import tempfile
import unittest
from pathlib import Path

import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from capture_candidates import snapshot
from cs_evalkit import explicit_aspects, load_jsonl, sha256_file
from prepare_hotpot import build
from validate_dataset import validate
from validate_snapshots import validate as validate_snapshots
from score_selection import feasible_gold_combination


class ContextSelectionToolsTest(unittest.TestCase):
    def test_prepare_validate_and_snapshot_do_not_leak_gold(self) -> None:
        fixtures = Path(__file__).with_name("fixtures")
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "dataset.jsonl"
            manifest = Path(directory) / "manifest.json"
            build(argparse.Namespace(
                train_source=[fixtures / "hotpot-mini-train.json"],
                validation_source=[fixtures / "hotpot-mini-validation.json"],
                output=output,
                manifest=manifest,
                seed=20260905,
                dev_size=2,
                test_size=2,
            ))
            report = validate(output, ROOT / "dataset.schema.json")
            self.assertTrue(report["valid"], report["errors"])
            rows = load_jsonl(output)
            captured = snapshot(rows[0], sha256_file(output), "test-commit")
            serialized = json.dumps(captured)
            self.assertNotIn("evidence_requirements", serialized)
            self.assertNotIn("expected_facts", serialized)
            self.assertNotIn('"answer"', serialized)
            self.assertEqual(len(rows), 4)
            snapshot_path = Path(directory) / "snapshot.jsonl"
            snapshot_path.write_text(json.dumps(captured) + "\n", encoding="utf-8")
            snapshot_report = validate_snapshots(snapshot_path, ROOT / "candidate-snapshot.schema.json")
            self.assertTrue(snapshot_report["valid"], snapshot_report["errors"])

    def test_comparison_aspects_use_question_text_only(self) -> None:
        aspects = explicit_aspects("Which was founded first, Alpha or Beta?", "comparison")
        self.assertEqual(2, len(aspects))
        self.assertTrue(all("Alpha" in aspect or "Beta" in aspect for aspect in aspects))

    def test_unresolvable_support_is_excluded_before_sampling(self) -> None:
        from prepare_hotpot import has_resolvable_support
        row = {
            "supporting_facts": [["Doc", 2]],
            "context": [["Doc", ["only sentence"]]],
        }
        self.assertFalse(has_resolvable_support(row))

    def test_gold_budget_feasibility_uses_alternative_combinations(self) -> None:
        requirements = [
            {"alternatives": [{"all_of": [{"candidate_id": "large"}]}, {"all_of": [{"candidate_id": "a"}]}]},
            {"alternatives": [{"all_of": [{"candidate_id": "b"}]}]},
        ]
        feasible, tokens, chunks = feasible_gold_combination(
            requirements, {"large": 20, "a": 4, "b": 5}, 10, 2
        )
        self.assertTrue(feasible)
        self.assertEqual((9, 2), (tokens, chunks))


if __name__ == "__main__":
    unittest.main()
