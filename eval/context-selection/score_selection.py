#!/usr/bin/env python3
"""Score selected candidate IDs against separately loaded evidence labels."""

from __future__ import annotations

import argparse
import itertools
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable

from cs_evalkit import load_jsonl, sha256_file, write_json


def parse_arm(value: str) -> tuple[str, Path]:
    if "=" not in value:
        raise argparse.ArgumentTypeError("arms must use LABEL=PATH")
    label, path = value.split("=", 1)
    if not label or not path:
        raise argparse.ArgumentTypeError("arms must use LABEL=PATH")
    return label, Path(path)


def requirement_satisfied(requirement: dict[str, Any], selected: set[str]) -> bool:
    return any(
        all(str(item["candidate_id"]) in selected for item in alternative["all_of"])
        for alternative in requirement["alternatives"]
    )


def complete(requirements: list[dict[str, Any]], selected: set[str]) -> bool:
    return bool(requirements) and all(requirement_satisfied(requirement, selected) for requirement in requirements)


def feasible_gold_combination(
    requirements: list[dict[str, Any]],
    candidate_tokens: dict[str, int],
    token_budget: int,
    max_chunks: int,
    state_limit: int = 100_000,
) -> tuple[bool, int | None, int | None]:
    states: set[frozenset[str]] = {frozenset()}
    for requirement in requirements:
        next_states: set[frozenset[str]] = set()
        for state, alternative in itertools.product(states, requirement["alternatives"]):
            added = frozenset(str(item["candidate_id"]) for item in alternative["all_of"])
            combined = state | added
            if all(identifier in candidate_tokens for identifier in combined):
                next_states.add(combined)
        if not next_states:
            return False, None, None
        ordered = sorted(
            next_states,
            key=lambda state: (sum(candidate_tokens[identifier] for identifier in state), len(state), sorted(state)),
        )
        states = set(ordered[:state_limit])
    best = min(
        states,
        key=lambda state: (sum(candidate_tokens[identifier] for identifier in state), len(state), sorted(state)),
    )
    tokens = sum(candidate_tokens[identifier] for identifier in best)
    return tokens <= token_budget and len(best) <= max_chunks, tokens, len(best)


def mean(values: Iterable[float]) -> float | None:
    values = list(values)
    return sum(values) / len(values) if values else None


def score_arm(
    label: str,
    run_rows: list[dict[str, Any]],
    datasets: dict[str, dict[str, Any]],
    snapshots: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    details: list[dict[str, Any]] = []
    errors: list[str] = []
    seen: set[str] = set()
    for run_row in run_rows:
        identifier = str(run_row.get("example_id", ""))
        if identifier in seen:
            errors.append(f"duplicate run example: {identifier}")
            continue
        seen.add(identifier)
        dataset = datasets.get(identifier)
        snapshot = snapshots.get(identifier)
        if dataset is None or snapshot is None:
            errors.append(f"missing dataset or snapshot row: {identifier}")
            continue
        selected = set(map(str, run_row.get("selected_ids", [])))
        snapshot_ids = {str(candidate["id"]) for candidate in snapshot["candidates"]}
        if not selected <= snapshot_ids:
            errors.append(f"{identifier}: selected IDs are outside the candidate snapshot")
            continue
        requirements = list(dataset["evidence_requirements"])
        candidate_complete = complete(requirements, snapshot_ids)
        selected_complete = complete(requirements, selected)
        satisfied = sum(requirement_satisfied(requirement, selected) for requirement in requirements)
        candidate_tokens = {
            str(candidate["id"]): int(candidate["render_token_count"]) for candidate in snapshot["candidates"]
        }
        feasible, minimum_gold_tokens, minimum_gold_chunks = feasible_gold_combination(
            requirements,
            candidate_tokens,
            int(run_row["token_budget"]),
            int(run_row["max_chunks"]),
        )
        details.append({
            "id": identifier,
            "split": dataset["split"],
            "group_id": dataset["group_id"],
            "task_type": dataset["task_type"],
            "question": dataset["question"],
            "candidate_complete": candidate_complete,
            "gold_budget_feasible": feasible,
            "minimum_gold_tokens": minimum_gold_tokens,
            "minimum_gold_chunks": minimum_gold_chunks,
            "selected_complete": selected_complete,
            "evidence_recall": satisfied / len(requirements) if requirements else None,
            "selected_tokens": int(run_row["selected_tokens"]),
            "selected_chunks": len(selected),
            "selected_ids": list(run_row.get("selected_ids", [])),
            "target_failure": bool(candidate_complete and feasible and not selected_complete),
        })
    by_type: dict[str, dict[str, Any]] = {}
    for task_type in sorted({row["task_type"] for row in details}):
        group = [row for row in details if row["task_type"] == task_type]
        by_type[task_type] = summarize(group)
    summary = summarize(details)
    summary["by_task_type"] = by_type
    target_cases = [row for row in details if row["target_failure"]]
    return {
        "label": label,
        "summary": summary,
        "target_failure_gate": {
            "count": len(target_cases),
            "rate_among_answerable": len(target_cases) / len(details) if details else None,
            "minimum_count": 10,
            "minimum_rate": 0.05,
            "passed": len(target_cases) >= 10 and len(target_cases) >= 0.05 * len(details),
        },
        "examples": {
            "target_failures": target_cases[:10],
            "complete_successes": [row for row in details if row["selected_complete"]][:3],
            "other_failures": [row for row in details if not row["selected_complete"] and not row["target_failure"]][:3],
        },
        "details": details,
        "errors": errors,
    }


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    conditions = [row for row in rows if row["candidate_complete"] and row["gold_budget_feasible"]]
    return {
        "n": len(rows),
        "complete_coverage_count": sum(row["selected_complete"] for row in rows),
        "complete_coverage_rate": mean(float(row["selected_complete"]) for row in rows),
        "candidate_complete_count": sum(row["candidate_complete"] for row in rows),
        "candidate_complete_rate": mean(float(row["candidate_complete"]) for row in rows),
        "condition_n": len(conditions),
        "conditional_complete_coverage_rate": mean(float(row["selected_complete"]) for row in conditions),
        "evidence_recall": mean(row["evidence_recall"] for row in rows if row["evidence_recall"] is not None),
        "mean_selected_tokens": mean(float(row["selected_tokens"]) for row in rows),
        "mean_selected_chunks": mean(float(row["selected_chunks"]) for row in rows),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--snapshots", type=Path, required=True)
    parser.add_argument("--arm", action="append", type=parse_arm, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(f"refusing to overwrite {args.output}")
    datasets = {str(row["id"]): row for row in load_jsonl(args.dataset)}
    snapshots = {str(row["example_id"]): row for row in load_jsonl(args.snapshots)}
    arms = {
        label: score_arm(label, load_jsonl(path), datasets, snapshots)
        for label, path in args.arm
    }
    report = {
        "schema_version": "context-selection-score-v1",
        "dataset": str(args.dataset),
        "dataset_sha256": sha256_file(args.dataset),
        "snapshots": str(args.snapshots),
        "snapshots_sha256": sha256_file(args.snapshots),
        "arms": arms,
    }
    write_json(args.output, report)
    for label, arm in arms.items():
        print(label, arm["summary"], arm["target_failure_gate"])
    if any(arm["errors"] for arm in arms.values()):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
