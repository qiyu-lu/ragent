#!/usr/bin/env python3
"""Summarize repeated runs per arm and compare arms against the baseline range.

Each arm is three ``run_retrieval.py`` reports on the same split. For every metric the arm summary
gives the three values, their mean and range (max − min). Plan §4.5: the baseline range on the tuning
split is the threshold; a candidate arm whose mean improves by less than that range is "unproven",
one that falls by more than it is "regressed", otherwise "improved".

``--thresholds eval/kq/manifests/kq-thresholds.json`` replaces the range by the values the stage-2
session fixed in the status file (overall answerable and numeric questions); other blocks keep the
baseline range. The same file sets the run-validity rule: a report with more empty-channel
sub-questions (the 15 s channel timeout) than allowed is rejected unless ``--allow-invalid-runs``.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Mapping, Optional, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))

from evalkit import ANSWERABLE_METRICS, UNANSWERABLE_METRICS, read_json, sha256_file, summarize_repeats, utc_now_iso, write_json  # noqa: E402

HIGHER_IS_BETTER = {"hit@5", "hit@5_all", "anchor_recall", "mrr", "context_precision", "doc_recall"}
LOWER_IS_BETTER = {"n_chunks", "retrieved_doc_count", "max_rerank_score", "top_final_rerank"}
# A gain of exactly one question (1/n) must reach a threshold of 1/n despite float rounding of the means.
EPSILON = 1e-9


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--arm", action="append", nargs="+", metavar=("NAME", "REPORT"), required=True,
                        help="arm name followed by its report files; repeat per arm")
    parser.add_argument("--baseline", help="arm name whose range is the threshold (default: first --arm)")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--allow-mismatch", action="store_true", help="do not require identical question/sub-question hashes")
    parser.add_argument("--thresholds", type=Path, help="fixed thresholds and validity rule (eval/kq/manifests/kq-thresholds.json)")
    parser.add_argument("--allow-invalid-runs", action="store_true",
                        help="keep reports that break the validity rule of --thresholds")
    parser.add_argument("--max-empty-channel", type=int,
                        help="run-validity limit on its own: the test split is judged against its baseline range, "
                             "not the fixed thresholds, but invalid runs must still be caught")
    return parser.parse_args()


def load_reports(paths: Sequence[Path]) -> List[dict]:
    reports = []
    for path in paths:
        report = read_json(path)
        if not isinstance(report, dict) or report.get("kind") != "kq-retrieval":
            raise ValueError(f"{path}: expected kind=kq-retrieval")
        if report.get("failures"):
            raise ValueError(f"{path}: report contains failures")
        report["_path"] = str(path)
        report["_sha256"] = sha256_file(path)
        reports.append(report)
    return reports


def empty_channel_sub_questions(report: Mapping[str, Any]) -> int:
    """Sub-questions whose retrieval channel came back empty; in the S1 baseline all were 15 s channel timeouts."""

    count = 0
    for item in report.get("details") or []:
        for stage in (item.get("raw_response") or {}).get("stages") or []:
            if str(stage.get("stage", "")).startswith("channel-") and not stage.get("chunkCount"):
                count += 1
    return count


def summarize_arm(reports: Sequence[Mapping[str, Any]]) -> dict:
    """Per-metric values / mean / range over the repeats of one arm, overall, by type and for unanswerable."""

    overall = summarize_repeats([(r.get("summary") or {}).get("overall_answerable") for r in reports], ANSWERABLE_METRICS)
    by_type: Dict[str, dict] = {}
    types = sorted({key for r in reports for key in ((r.get("summary") or {}).get("by_type") or {})})
    for qtype in types:
        by_type[qtype] = summarize_repeats([((r.get("summary") or {}).get("by_type") or {}).get(qtype) for r in reports],
                                           ANSWERABLE_METRICS)
    unanswerable = summarize_repeats([(r.get("summary") or {}).get("unanswerable") for r in reports], UNANSWERABLE_METRICS)
    latency = summarize_repeats([(r.get("summary") or {}).get("latency_ms") for r in reports], ("p50", "p95"))
    return {
        "repeats": len(reports),
        "labels": [r.get("label") for r in reports],
        "server_commits": sorted({str(r.get("server_commit")) for r in reports}),
        "empty_channel_sub_questions": [empty_channel_sub_questions(r) for r in reports],
        "overall_answerable": overall,
        "by_type": by_type,
        "unanswerable": unanswerable,
        "latency_ms": latency,
    }


def verdict(metric: str, delta: float, threshold: float) -> str:
    """Improved when the gain reaches the threshold, regressed when the loss exceeds it."""

    better = delta if metric in HIGHER_IS_BETTER else -delta
    if better >= threshold - EPSILON and better > 0:
        return "improved"
    if better < -threshold - EPSILON:
        return "regressed"
    return "unproven"


def compare_arms(baseline: Mapping[str, Any], candidate: Mapping[str, Any],
                 fixed: Optional[Mapping[str, Any]] = None) -> dict:
    """Mean deltas candidate − baseline, overall and per type.

    The threshold is the baseline range unless ``fixed`` (the ``retrieval`` section of the thresholds
    file) names a value for that block and metric.
    """

    fixed = fixed or {}

    def block(name: str, base_block: Mapping[str, Any], cand_block: Mapping[str, Any], keys: Sequence[str]) -> dict:
        out: Dict[str, Any] = {}
        for key in keys:
            if key not in base_block or key not in cand_block:
                continue
            delta = cand_block[key]["mean"] - base_block[key]["mean"]
            fixed_value = (fixed.get(name) or {}).get(key)
            threshold = float(fixed_value) if fixed_value is not None else base_block[key]["range"]
            out[key] = {"baseline_mean": base_block[key]["mean"], "candidate_mean": cand_block[key]["mean"],
                        "delta": delta, "threshold": threshold,
                        "threshold_source": "fixed" if fixed_value is not None else "baseline_range",
                        "verdict": verdict(key, delta, threshold)}
        return out

    comparison: Dict[str, Any] = {
        "overall_answerable": block("overall_answerable", baseline["overall_answerable"],
                                    candidate["overall_answerable"], ANSWERABLE_METRICS),
        "by_type": {},
        "unanswerable": block("unanswerable", baseline.get("unanswerable") or {}, candidate.get("unanswerable") or {},
                              UNANSWERABLE_METRICS),
    }
    for qtype in sorted(set(baseline.get("by_type") or {}) | set(candidate.get("by_type") or {})):
        comparison["by_type"][qtype] = block(qtype, (baseline.get("by_type") or {}).get(qtype) or {},
                                             (candidate.get("by_type") or {}).get(qtype) or {}, ANSWERABLE_METRICS)
    return comparison


def check_validity(arms: Mapping[str, Sequence[Mapping[str, Any]]], max_empty: Optional[int]) -> List[str]:
    if max_empty is None:
        return []
    problems = []
    for reports in arms.values():
        for report in reports:
            empty = empty_channel_sub_questions(report)
            if empty > max_empty:
                problems.append(f"{report.get('label')}: {empty} empty-channel sub-questions > {max_empty}; rerun it")
    return problems


def replayed_sub_questions(report: Mapping[str, Any]) -> str:
    """Fingerprint of the sub-questions each question was actually retrieved with.

    The sub-questions file is shared by both splits and grows when a split is recorded, so its hash
    changes although the replayed sub-questions of the other split do not; arms are compared on this.
    """

    used = {str(item.get("id")): (item.get("raw_response") or {}).get("subQuestions")
            for item in report.get("details") or []}
    return hashlib.sha256(json.dumps(used, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()


def check_compatible(arms: Mapping[str, Sequence[Mapping[str, Any]]]) -> List[str]:
    problems: List[str] = []
    all_reports = [r for reports in arms.values() for r in reports]
    for field in ("split", "questions_sha256"):
        values = {str(r.get(field)) for r in all_reports}
        if len(values) > 1:
            problems.append(f"reports differ in {field}: {sorted(values)}")
    fingerprints = {replayed_sub_questions(r) for r in all_reports}
    if len(fingerprints) > 1:
        problems.append(f"reports replayed different sub-questions: {sorted(f[:12] for f in fingerprints)}")
    for name, reports in arms.items():
        indexes = sorted(r.get("repeat_index") for r in reports)
        if len(indexes) != len(set(indexes)):
            problems.append(f"arm {name}: duplicate repeat_index {indexes}")
        if len(reports) < 2:
            problems.append(f"arm {name}: needs at least 2 repeats to measure a range")
    return problems


def main() -> int:
    args = parse_args()
    if args.output.exists():
        print(f"refusing to overwrite {args.output}")
        return 1
    try:
        arms = {}
        for group in args.arm:
            name, paths = group[0], [Path(p) for p in group[1:]]
            if not paths:
                raise ValueError(f"arm {name} has no reports")
            arms[name] = load_reports(paths)
    except (OSError, ValueError) as exc:
        print(f"input error: {exc}")
        return 1
    problems = check_compatible(arms)
    if problems and not args.allow_mismatch:
        print("input error:\n  - " + "\n  - ".join(problems))
        return 1
    file_hashes = {str(r.get("sub_questions_sha256")) for reports in arms.values() for r in reports}
    if len(file_hashes) > 1 and not problems:
        problems.append(f"sub-questions file hash differs across reports ({len(file_hashes)} values) but every report "
                        "replayed the same sub-questions; the file grew when another split was recorded")
    thresholds = read_json(args.thresholds) if args.thresholds else {}
    limit = args.max_empty_channel if args.max_empty_channel is not None \
        else (thresholds.get("validity") or {}).get("max_empty_channel_sub_questions")
    invalid = check_validity(arms, limit)
    if invalid and not args.allow_invalid_runs:
        print("invalid runs:\n  - " + "\n  - ".join(invalid))
        return 1
    problems += invalid
    summaries = {name: summarize_arm(reports) for name, reports in arms.items()}
    baseline_name = args.baseline or next(iter(arms))
    if baseline_name not in summaries:
        print(f"unknown baseline arm {baseline_name}")
        return 1
    comparisons = {name: compare_arms(summaries[baseline_name], summary, thresholds.get("retrieval"))
                   for name, summary in summaries.items() if name != baseline_name}
    write_json(args.output, {
        "schema_version": 1,
        "kind": "kq-repeat-comparison",
        "created_at": utc_now_iso(),
        "baseline": baseline_name,
        "thresholds": {"path": str(args.thresholds), "sha256": sha256_file(args.thresholds)} if args.thresholds else None,
        "warnings": problems,
        "inputs": {name: [{"path": r["_path"], "sha256": r["_sha256"], "label": r.get("label")} for r in reports]
                   for name, reports in arms.items()},
        "arms": summaries,
        "comparisons": comparisons,
    })
    for name, summary in summaries.items():
        print(f"\narm {name} ({summary['repeats']} repeats, empty-channel sub-questions {summary['empty_channel_sub_questions']})")
        for key, stats in summary["overall_answerable"].items():
            if isinstance(stats, dict):
                print(f"  {key:20s} mean={stats['mean']:.3f} range={stats['range']:.3f} values={[round(v, 3) for v in stats['values']]}")
    for name, comparison in comparisons.items():
        print(f"\n{name} vs {baseline_name}")
        rows = [("overall", key, row) for key, row in comparison["overall_answerable"].items()]
        rows += [("numeric", key, row) for key, row in (comparison["by_type"].get("numeric") or {}).items()
                 if key in ("hit@5", "mrr")]
        rows += [("confusion", key, row) for key, row in (comparison["by_type"].get("confusion") or {}).items()
                 if key in ("hit@5", "mrr")]
        for scope, key, row in rows:
            print(f"  {scope:9s} {key:18s} {row['baseline_mean']:.3f} -> {row['candidate_mean']:.3f} "
                  f"delta={row['delta']:+.3f} threshold={row['threshold']:.3f} ({row['threshold_source']}) {row['verdict']}")
    print(f"\ncomparison: {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
