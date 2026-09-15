#!/usr/bin/env python3
"""Choose development winners and apply the preregistered continuation gate."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from cs_evalkit import sha256_file, write_json


def ranking_key(arm: dict[str, Any]) -> tuple[float, float, float]:
    summary = arm["summary"]
    return (
        float(summary["complete_coverage_rate"] or 0.0),
        float(summary["evidence_recall"] or 0.0),
        -float(summary["mean_selected_tokens"] or 0.0),
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--score", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(f"refusing to overwrite {args.output}")
    score = json.loads(args.score.read_text(encoding="utf-8"))
    arms = score["arms"]
    simple_labels = [label for label in arms if label == "CS-P" or label == "CS-R" or label.startswith("CS-M-")]
    coverage_labels = [label for label in arms if label.startswith("CS-C-l")]
    if not simple_labels or not coverage_labels:
        raise ValueError("score report must include simple and coverage arms")
    best_simple_label = max(simple_labels, key=lambda label: (ranking_key(arms[label]), label))
    best_coverage_label = max(coverage_labels, key=lambda label: (ranking_key(arms[label]), label))
    best_simple = arms[best_simple_label]
    best_coverage = arms[best_coverage_label]
    simple_details = {row["id"]: row for row in best_simple["details"]}
    coverage_details = {row["id"]: row for row in best_coverage["details"]}
    common = sorted(simple_details.keys() & coverage_details.keys())
    improved = [identifier for identifier in common
                if coverage_details[identifier]["selected_complete"] and not simple_details[identifier]["selected_complete"]]
    regressed = [identifier for identifier in common
                 if simple_details[identifier]["selected_complete"] and not coverage_details[identifier]["selected_complete"]]
    delta = (
        float(best_coverage["summary"]["complete_coverage_rate"])
        - float(best_simple["summary"]["complete_coverage_rate"])
    )
    gate_passed = delta >= 0.05
    report = {
        "schema_version": "context-selection-development-decision-v1",
        "score": str(args.score),
        "score_sha256": sha256_file(args.score),
        "best_simple_baseline": best_simple_label,
        "best_coverage_arm": best_coverage_label,
        "complete_coverage_delta": delta,
        "paired_improved_count": len(improved),
        "paired_regressed_count": len(regressed),
        "paired_improved_ids": improved,
        "paired_regressed_ids": regressed,
        "continuation_gate": {
            "required_delta": 0.05,
            "observed_delta": delta,
            "single_point_business_regression_check": "not_assessable_without_reviewed_business_dev",
            "passed_for_full_e2e": gate_passed,
        },
        "decision": "continue_to_full_e2e" if gate_passed else "stop_complex_selector_and_keep_default_legacy",
    }
    write_json(args.output, report)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
