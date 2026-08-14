#!/usr/bin/env python3
"""Compare three fixed-rewrite fair-refill control/candidate retrieval runs."""

from __future__ import annotations

import argparse
import copy
import json
import statistics
import sys
from pathlib import Path
from typing import Any, Dict, Mapping, Sequence

from evalkit import normalize, sha256_file, validate_retrieval_diagnostics, write_json


QUALITY_METRICS = (
    "anchor_hit@5_any",
    "anchor_hit@5_all",
    "anchor_recall",
    "context_precision",
    "doc_recall",
    "routing_purity",
)
TARGET_ID = "xlsx-hard-06"
TARGET_MISSING_ANCHOR = "称取 0.2g 烘干矿样"
EXPECTED_REPORTS_PER_ARM = 3
EXPECTED_QUESTIONS_PER_REPORT = 24
EPSILON = 1e-12


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--off", type=Path, action="append", required=True)
    parser.add_argument("--on", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def load_report(path: Path) -> dict:
    try:
        report = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"cannot read {path}: {exc}") from exc
    if not isinstance(report, dict) or report.get("kind") != "retrieval":
        raise ValueError(f"{path}: expected kind=retrieval")
    if report.get("failures"):
        raise ValueError(f"{path}: report contains failures")
    return report


def details_by_id(report: Mapping[str, Any], source: str) -> Dict[str, dict]:
    details = report.get("details")
    if not isinstance(details, list) or not details:
        raise ValueError(f"{source}: details must be a non-empty list")
    result: Dict[str, dict] = {}
    for index, detail in enumerate(details):
        if not isinstance(detail, dict):
            raise ValueError(f"{source}: details[{index}] must be an object")
        question_id = detail.get("id")
        if not isinstance(question_id, str) or not question_id:
            raise ValueError(f"{source}: details[{index}].id must be non-empty")
        if question_id in result:
            raise ValueError(f"{source}: duplicate detail id {question_id}")
        result[question_id] = detail
    selection = report.get("selection")
    if not isinstance(selection, dict) or selection.get("n") != len(result):
        raise ValueError(f"{source}: selection.n must match details length")
    return result


def config_without_arm(report: Mapping[str, Any]) -> dict:
    configuration = copy.deepcopy(report.get("configuration"))
    if not isinstance(configuration, dict):
        raise ValueError("configuration must be an object")
    configuration.pop("refill_mode", None)
    configuration.pop("repeat_index", None)
    return configuration


def organize(paths: Sequence[Path], expected_mode: str) -> list[dict]:
    if len(paths) != EXPECTED_REPORTS_PER_ARM:
        raise ValueError(
            f"expected exactly {EXPECTED_REPORTS_PER_ARM} --{expected_mode} reports, "
            f"got {len(paths)}"
        )
    by_repeat: Dict[int, dict] = {}
    for path in paths:
        report = load_report(path)
        configuration = report.get("configuration")
        if not isinstance(configuration, dict):
            raise ValueError(f"{path}: configuration must be an object")
        if configuration.get("concurrency") != 1:
            raise ValueError(f"{path}: repeated comparison requires concurrency=1")
        if configuration.get("refill_mode") != expected_mode:
            raise ValueError(
                f"{path}: refill_mode must be {expected_mode}, got {configuration.get('refill_mode')}"
            )
        repeat_index = configuration.get("repeat_index")
        if isinstance(repeat_index, bool) or repeat_index not in {1, 2, 3}:
            raise ValueError(f"{path}: repeat_index must be 1, 2 or 3")
        if repeat_index in by_repeat:
            raise ValueError(f"duplicate {expected_mode} repeat_index {repeat_index}")
        rewrite = configuration.get("rewrite")
        if not isinstance(rewrite, dict) or rewrite.get("mode") != "replay":
            raise ValueError(f"{path}: repeated comparison requires rewrite.mode=replay")
        source_sha = rewrite.get("source_sha256")
        if not isinstance(source_sha, str) or not source_sha:
            raise ValueError(f"{path}: rewrite.source_sha256 must be non-empty")
        detail_map = details_by_id(report, str(path))
        for question_id, detail in detail_map.items():
            response = detail.get("raw_response")
            if not isinstance(response, dict):
                raise ValueError(f"{path}: {question_id} is missing raw_response")
            try:
                validate_retrieval_diagnostics(response, expected_mode)
            except ValueError as exc:
                raise ValueError(f"{path}: {question_id}: {exc}") from exc
        report["_path"] = str(path)
        report["_sha256"] = sha256_file(path)
        report["_details_by_id"] = detail_map
        by_repeat[repeat_index] = report
    if set(by_repeat) != {1, 2, 3}:
        raise ValueError(f"{expected_mode} reports must contain repeat indexes 1, 2 and 3")
    return [by_repeat[index] for index in (1, 2, 3)]


def assert_compatible(off: Sequence[dict], on: Sequence[dict]) -> list[str]:
    reports = list(off) + list(on)
    first = reports[0]
    scalar_fields = (
        "variant",
        "server_commit",
        "dataset_sha256",
        "corpus_sha256",
        "setup_manifest_sha256",
        "selection",
    )
    for field in scalar_fields:
        expected = first.get(field)
        if any(report.get(field) != expected for report in reports[1:]):
            raise ValueError(f"reports have incompatible {field}")
    if not first.get("setup_manifest_sha256"):
        raise ValueError("setup_manifest_sha256 must be non-empty for repeated comparison")
    expected_config = config_without_arm(first)
    for report in reports[1:]:
        if config_without_arm(report) != expected_config:
            raise ValueError("reports have incompatible non-arm configuration")

    expected_ids = list(first["_details_by_id"])
    for report in reports[1:]:
        if list(report["_details_by_id"]) != expected_ids:
            raise ValueError("reports have incompatible detail ids or order")
    if TARGET_ID not in expected_ids:
        raise ValueError(f"reports do not contain required target {TARGET_ID}")

    stable_fields = (
        "tier",
        "family",
        "question",
        "answerable",
        "reference_docs",
        "reference_anchors",
    )
    for question_id in expected_ids:
        baseline = first["_details_by_id"][question_id]
        for report in reports[1:]:
            detail = report["_details_by_id"][question_id]
            if any(detail.get(field) != baseline.get(field) for field in stable_fields):
                raise ValueError(f"reports have incompatible frozen input for {question_id}")
    return expected_ids


def numeric(value: Any, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{label} must be numeric, got {value!r}")
    return float(value)


def series(values: Sequence[float]) -> dict:
    return {
        "values": list(values),
        "median": statistics.median(values),
        "range": [min(values), max(values)],
        "span": max(values) - min(values),
    }


def overall_value(report: Mapping[str, Any], metric: str) -> float:
    block = ((report.get("summary") or {}).get("overall_answerable") or {})
    return numeric(block.get(metric), f"{report.get('_path')}: overall {metric}")


def latency_p95(report: Mapping[str, Any]) -> float:
    block = (report.get("summary") or {}).get("latency_ms") or {}
    return numeric(block.get("p95"), f"{report.get('_path')}: latency p95")


def metric_summary(off: Sequence[dict], on: Sequence[dict]) -> dict:
    result = {}
    for metric in QUALITY_METRICS:
        off_values = [overall_value(report, metric) for report in off]
        on_values = [overall_value(report, metric) for report in on]
        result[metric] = {
            "off": series(off_values),
            "on": series(on_values),
            "median_delta": statistics.median(on_values) - statistics.median(off_values),
        }
    off_latency = [latency_p95(report) for report in off]
    on_latency = [latency_p95(report) for report in on]
    result["latency_p95_ms"] = {
        "off": series(off_latency),
        "on": series(on_latency),
        "median_delta": statistics.median(on_latency) - statistics.median(off_latency),
        "gate": False,
    }
    return result


def detail_metric(report: Mapping[str, Any], question_id: str, metric: str) -> Any:
    score = report["_details_by_id"][question_id].get("score") or {}
    return score.get(metric)


def classify(delta: float, epsilon: float = 1e-12) -> str:
    if delta > epsilon:
        return "improved"
    if delta < -epsilon:
        return "regressed"
    return "tied"


def per_question_summary(off: Sequence[dict], on: Sequence[dict], ids: Sequence[str]) -> dict:
    result = {}
    for metric in QUALITY_METRICS:
        buckets = {"improved": [], "tied": [], "regressed": []}
        paired = []
        for repeat_index, (off_report, on_report) in enumerate(zip(off, on), 1):
            counts = {"improved": 0, "tied": 0, "regressed": 0}
            for question_id in ids:
                left = detail_metric(off_report, question_id, metric)
                right = detail_metric(on_report, question_id, metric)
                if left is None and right is None:
                    continue
                if left is None or right is None:
                    raise ValueError(f"partial {metric} for {question_id} in repeat {repeat_index}")
                counts[classify(numeric(right, metric) - numeric(left, metric))] += 1
            paired.append({"repeat_index": repeat_index, **counts})
        for question_id in ids:
            off_values = [detail_metric(report, question_id, metric) for report in off]
            on_values = [detail_metric(report, question_id, metric) for report in on]
            if all(value is None for value in off_values + on_values):
                continue
            if any(value is None for value in off_values + on_values):
                raise ValueError(f"partial repeated {metric} for {question_id}")
            delta = statistics.median(
                [numeric(value, metric) for value in on_values]
            ) - statistics.median([numeric(value, metric) for value in off_values])
            buckets[classify(delta)].append(question_id)
        result[metric] = {
            "median_direction": {
                name: {"count": len(question_ids), "ids": question_ids}
                for name, question_ids in buckets.items()
            },
            "paired_counts": paired,
        }

    chunk_buckets = {"increased": [], "unchanged": [], "decreased": []}
    for question_id in ids:
        off_values = [
            numeric(detail_metric(report, question_id, "n_chunks"), "n_chunks")
            for report in off
        ]
        on_values = [
            numeric(detail_metric(report, question_id, "n_chunks"), "n_chunks")
            for report in on
        ]
        delta = statistics.median(on_values) - statistics.median(off_values)
        key = "increased" if delta > 0 else "decreased" if delta < 0 else "unchanged"
        chunk_buckets[key].append(question_id)
    result["n_chunks"] = {
        name: {"count": len(question_ids), "ids": question_ids}
        for name, question_ids in chunk_buckets.items()
    }
    return result


def diagnostic_run(report: Mapping[str, Any]) -> dict:
    totals = {
        "candidate_count": 0,
        "candidate_unique_count": 0,
        "unique_before_refill": 0,
        "refill_added": 0,
        "final_unique_count": 0,
        "unfilled_slots": 0,
    }
    legacy_gaps_recovered = 0
    topk_violations = 0
    fillable_but_unfilled = 0
    for detail in report["_details_by_id"].values():
        response = detail["raw_response"]
        diagnostics = response["retrievalDiagnostics"]
        totals["candidate_count"] += diagnostics["candidateCount"]
        totals["candidate_unique_count"] += diagnostics["candidateUniqueCount"]
        totals["unique_before_refill"] += diagnostics["uniqueBeforeRefill"]
        totals["refill_added"] += diagnostics["refillAdded"]
        totals["final_unique_count"] += diagnostics["finalUniqueCount"]
        totals["unfilled_slots"] += diagnostics["unfilledSlots"]
        legacy_gaps_recovered += int(diagnostics["refillAdded"] > 0)
        topk_violations += int(diagnostics["finalUniqueCount"] > diagnostics["requestTopK"])
        fillable_but_unfilled += int(
            diagnostics["candidateUniqueCount"] >= diagnostics["requestTopK"]
            and diagnostics["unfilledSlots"] > 0
        )
    return {
        "repeat_index": report["configuration"]["repeat_index"],
        "questions": len(report["_details_by_id"]),
        **totals,
        "legacy_gap_recovered_questions": legacy_gaps_recovered,
        "topk_violations": topk_violations,
        "fillable_but_unfilled_questions": fillable_but_unfilled,
    }


def diagnostic_summary(reports: Sequence[dict]) -> dict:
    runs = [diagnostic_run(report) for report in reports]
    keys = [key for key in runs[0] if key not in {"repeat_index", "questions"}]
    return {
        "runs": runs,
        "across_repeats": {key: series([run[key] for run in runs]) for key in keys},
    }


def target_run(report: Mapping[str, Any]) -> dict:
    detail = report["_details_by_id"][TARGET_ID]
    score = detail.get("score") or {}
    return {
        "repeat_index": report["configuration"]["repeat_index"],
        "n_chunks": score.get("n_chunks"),
        "anchor_recall": score.get("anchor_recall"),
        "anchor_hit@5_any": score.get("anchor_hit@5_any"),
        "anchor_hit@5_all": score.get("anchor_hit@5_all"),
        "missed_anchors": score.get("missed_anchors"),
        "sub_intents": detail["raw_response"].get("subIntents"),
        "retrieval_diagnostics": detail["raw_response"].get("retrievalDiagnostics"),
    }


def target_metric_summary(off: Sequence[dict], on: Sequence[dict]) -> dict:
    result = {}
    for metric in (*QUALITY_METRICS, "n_chunks"):
        off_values = [numeric(detail_metric(report, TARGET_ID, metric), metric) for report in off]
        on_values = [numeric(detail_metric(report, TARGET_ID, metric), metric) for report in on]
        result[metric] = {
            "off": series(off_values),
            "on": series(on_values),
            "median_delta": statistics.median(on_values) - statistics.median(off_values),
        }
    return result


def gate_criterion(
    passed: bool,
    *,
    operator: str,
    threshold: Any,
    observed: Any,
) -> dict:
    return {
        "passed": bool(passed),
        "operator": operator,
        "threshold": threshold,
        "observed": observed,
    }


def fixed_sub_intents_gate(reports: Sequence[dict], ids: Sequence[str]) -> dict:
    baseline = reports[0]["_details_by_id"]
    mismatches = []
    invalid = []
    for report in reports:
        report_label = {
            "mode": report["configuration"]["refill_mode"],
            "repeat_index": report["configuration"]["repeat_index"],
        }
        for question_id in ids:
            sub_intents = report["_details_by_id"][question_id].get("raw_response", {}).get(
                "subIntents"
            )
            if (
                not isinstance(sub_intents, list)
                or not sub_intents
                or any(not isinstance(value, str) or not value.strip() for value in sub_intents)
            ):
                invalid.append({**report_label, "id": question_id})
                continue
            expected = baseline[question_id].get("raw_response", {}).get("subIntents")
            if sub_intents != expected:
                mismatches.append({**report_label, "id": question_id})
    return gate_criterion(
        not invalid and not mismatches,
        operator="exact_match",
        threshold={"reference": "off-repeat-1", "questions": len(ids)},
        observed={"invalid": invalid, "mismatches": mismatches},
    )


def target_anchor_recovery_gate(off: Sequence[dict], on: Sequence[dict]) -> dict:
    target_anchor = normalize(TARGET_MISSING_ANCHOR)
    declared_anchors = off[0]["_details_by_id"][TARGET_ID].get("reference_anchors") or []
    anchor_declared = any(
        isinstance(anchor, str) and normalize(anchor) == target_anchor
        for anchor in declared_anchors
    )

    def anchor_is_missed(report: Mapping[str, Any]) -> bool:
        missed = (report["_details_by_id"][TARGET_ID].get("score") or {}).get(
            "missed_anchors"
        )
        if not isinstance(missed, list) or any(not isinstance(anchor, str) for anchor in missed):
            raise ValueError(f"{report.get('_path')}: {TARGET_ID} has invalid missed_anchors")
        return any(normalize(anchor) == target_anchor for anchor in missed)

    off_missed = [anchor_is_missed(report) for report in off]
    on_hit = [not anchor_is_missed(report) for report in on]
    paired_recovered = [missed and hit for missed, hit in zip(off_missed, on_hit)]
    recovery_count = sum(paired_recovered)
    return gate_criterion(
        anchor_declared and recovery_count >= 2,
        operator=">=",
        threshold={"recoveries": 2, "repeats": EXPECTED_REPORTS_PER_ARM},
        observed={
            "anchor": TARGET_MISSING_ANCHOR,
            "anchor_declared": anchor_declared,
            "off_missed_by_repeat": off_missed,
            "on_hit_by_repeat": on_hit,
            "paired_recovered_by_repeat": paired_recovered,
            "paired_recovery_count": recovery_count,
        },
    )


def target_non_regression_gate(off: Sequence[dict], on: Sequence[dict]) -> dict:
    off_values = [
        numeric(detail_metric(report, TARGET_ID, "anchor_recall"), "anchor_recall")
        for report in off
    ]
    on_values = [
        numeric(detail_metric(report, TARGET_ID, "anchor_recall"), "anchor_recall")
        for report in on
    ]
    paired_deltas = [right - left for left, right in zip(off_values, on_values)]
    median_delta = statistics.median(on_values) - statistics.median(off_values)
    passed = all(delta >= -EPSILON for delta in paired_deltas) and median_delta >= -EPSILON
    return gate_criterion(
        passed,
        operator=">=",
        threshold={"paired_delta": 0.0, "median_delta": 0.0},
        observed={
            "off": off_values,
            "on": on_values,
            "paired_deltas": paired_deltas,
            "median_delta": median_delta,
        },
    )


def build_gate(
    off: Sequence[dict],
    on: Sequence[dict],
    ids: Sequence[str],
    metrics: Mapping[str, Any],
    diagnostics: Mapping[str, Any],
) -> dict:
    reports = list(off) + list(on)
    question_counts = [len(report["_details_by_id"]) for report in reports]
    criteria = {
        "complete_24_question_replays": gate_criterion(
            len(reports) == EXPECTED_REPORTS_PER_ARM * 2
            and all(count == EXPECTED_QUESTIONS_PER_REPORT for count in question_counts),
            operator="==",
            threshold={
                "reports": EXPECTED_REPORTS_PER_ARM * 2,
                "questions_per_report": EXPECTED_QUESTIONS_PER_REPORT,
            },
            observed={"reports": len(reports), "question_counts": question_counts},
        ),
        "fixed_sub_intents_exact": fixed_sub_intents_gate(reports, ids),
    }

    diagnostic_runs = diagnostics["off"]["runs"] + diagnostics["on"]["runs"]
    topk_violations = sum(run["topk_violations"] for run in diagnostic_runs)
    criteria["final_unique_within_request_top_k"] = gate_criterion(
        topk_violations == 0,
        operator="==",
        threshold=0,
        observed={"violation_count": topk_violations},
    )
    fillable_but_unfilled = sum(
        run["fillable_but_unfilled_questions"] for run in diagnostics["on"]["runs"]
    )
    criteria["on_fillable_candidates_fully_fill_top_k"] = gate_criterion(
        fillable_but_unfilled == 0,
        operator="==",
        threshold=0,
        observed={"violation_count": fillable_but_unfilled},
    )
    criteria["xlsx_hard_06_missing_anchor_recovered"] = target_anchor_recovery_gate(off, on)
    criteria["xlsx_hard_06_anchor_recall_non_regression"] = target_non_regression_gate(
        off, on
    )

    metric_thresholds = (
        ("overall_anchor_recall_gain", "anchor_recall", 0.01),
        ("overall_doc_recall_non_regression", "doc_recall", 0.0),
        ("overall_anchor_hit_at_5_any_non_regression", "anchor_hit@5_any", 0.0),
        ("overall_context_precision_tolerance", "context_precision", -0.02),
        ("overall_routing_purity_tolerance", "routing_purity", -0.02),
    )
    for criterion_name, metric_name, threshold in metric_thresholds:
        delta = numeric(metrics[metric_name]["median_delta"], f"{metric_name} median_delta")
        criteria[criterion_name] = gate_criterion(
            delta + EPSILON >= threshold,
            operator=">=",
            threshold=threshold,
            observed=delta,
        )

    failed = [name for name, criterion in criteria.items() if not criterion["passed"]]
    return {
        "schema_version": 1,
        "decision": "pass" if not failed else "fail",
        "passed": not failed,
        "failed_criteria": failed,
        "criteria": criteria,
        "record_only": {
            "latency_p95_ms": metrics["latency_p95_ms"],
            "included_in_decision": False,
        },
    }


def build_comparison(off: Sequence[dict], on: Sequence[dict]) -> dict:
    ids = assert_compatible(off, on)
    first = off[0]
    rewrite = first["configuration"]["rewrite"]
    metrics = metric_summary(off, on)
    diagnostics = {
        "off": diagnostic_summary(off),
        "on": diagnostic_summary(on),
    }
    comparison = {
        "schema_version": 1,
        "kind": "retrieval-repeat-comparison",
        "server_commit": first.get("server_commit"),
        "variant": first.get("variant"),
        "dataset_sha256": first.get("dataset_sha256"),
        "corpus_sha256": first.get("corpus_sha256"),
        "setup_manifest_sha256": first.get("setup_manifest_sha256"),
        "fixed_rewrites_source_sha256": rewrite.get("source_sha256"),
        "selection": first.get("selection"),
        "input_reports": {
            "off": [
                {"path": report["_path"], "sha256": report["_sha256"]}
                for report in off
            ],
            "on": [
                {"path": report["_path"], "sha256": report["_sha256"]}
                for report in on
            ],
        },
        "metrics": metrics,
        "per_question": per_question_summary(off, on, ids),
        "diagnostics": diagnostics,
        TARGET_ID: {
            "metrics": target_metric_summary(off, on),
            "off": [target_run(report) for report in off],
            "on": [target_run(report) for report in on],
        },
    }
    comparison["gate"] = build_gate(off, on, ids, metrics, diagnostics)
    return comparison


def main() -> int:
    args = parse_args()
    if args.output.exists():
        print(f"refusing to overwrite {args.output}")
        return 1
    try:
        off = organize(args.off, "off")
        on = organize(args.on, "on")
        comparison = build_comparison(off, on)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"input error: {exc}")
        return 1
    write_json(args.output, comparison)
    print(f"comparison: {args.output}")
    print(
        f"gate: {comparison['gate']['decision']}"
        + (
            ""
            if comparison["gate"]["passed"]
            else f" ({', '.join(comparison['gate']['failed_criteria'])})"
        )
    )
    for metric, block in comparison["metrics"].items():
        print(
            f"  {metric:24s} off={block['off']['median']:.3f} "
            f"on={block['on']['median']:.3f} delta={block['median_delta']:+.3f}"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
