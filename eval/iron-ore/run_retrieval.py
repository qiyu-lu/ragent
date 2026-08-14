#!/usr/bin/env python3
"""Run the frozen question set against live or fixed-rewrite eval retrieval."""

from __future__ import annotations

import argparse
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Mapping, Sequence, Tuple

from evalkit import (
    ApiClient,
    ApiError,
    aggregate_retrieval,
    load_corpus_manifest,
    load_jsonl,
    score_retrieval,
    sha256_file,
    validate_retrieval_diagnostics,
    validate_label,
    write_json,
)


HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
DEFAULT_DATASET = REPO_ROOT / "local-data/eval/dataset-v1.jsonl"
DEFAULT_CORPUS = REPO_ROOT / "local-data/eval/corpus-v1.json"
DEFAULT_RUNS = REPO_ROOT / "local-data/eval/runs"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--variant", required=True, choices=["baseline", "current"])
    parser.add_argument("--server-commit", required=True)
    parser.add_argument("--intent-mode", required=True, choices=["off", "on"])
    parser.add_argument("--ocr", required=True, choices=["off", "on"])
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--setup-manifest", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--token")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--concurrency", type=int, default=1)
    parser.add_argument("--family", action="append")
    parser.add_argument("--id", action="append", dest="ids")
    parser.add_argument("--max-chars", type=int, default=1024)
    parser.add_argument("--overlap-chars", type=int, default=128)
    parser.add_argument("--rows-per-chunk", type=int, default=50)
    parser.add_argument("--tolerance-factor", type=int, default=3)
    parser.add_argument("--compare", type=Path)
    parser.add_argument(
        "--fixed-rewrites-from",
        type=Path,
        help="Replay raw_response.subIntents from a compatible retrieval report",
    )
    parser.add_argument("--refill-mode", choices=["off", "on"])
    parser.add_argument("--repeat-index", type=int, choices=[1, 2, 3])
    return parser.parse_args()


def load_fixed_rewrites(
    path: Path,
    rows: Sequence[Mapping[str, object]],
    *,
    dataset_sha256: str,
    corpus_sha256: str,
    setup_manifest_sha256: str,
) -> Tuple[Dict[str, list[str]], dict]:
    """Load and strictly validate replay queries from an immutable retrieval report."""

    try:
        report = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"cannot read fixed rewrite source {path}: {exc}") from exc
    if not isinstance(report, dict) or report.get("kind") != "retrieval":
        raise ValueError(f"{path}: fixed rewrite source must have kind=retrieval")
    expected_hashes = {
        "dataset_sha256": dataset_sha256,
        "corpus_sha256": corpus_sha256,
        "setup_manifest_sha256": setup_manifest_sha256,
    }
    for field, expected in expected_hashes.items():
        actual = report.get(field)
        if actual != expected:
            raise ValueError(f"{path}: {field} mismatch: expected {expected}, got {actual}")
    if report.get("failures"):
        raise ValueError(f"{path}: fixed rewrite source contains failures")

    details = report.get("details")
    if not isinstance(details, list):
        raise ValueError(f"{path}: details must be a list")
    expected_rows: Dict[str, Mapping[str, object]] = {}
    for row in rows:
        question_id = row.get("id")
        if not isinstance(question_id, str) or not question_id:
            raise ValueError("selected dataset contains an invalid question id")
        if question_id in expected_rows:
            raise ValueError(f"selected dataset contains duplicate id: {question_id}")
        expected_rows[question_id] = row

    rewrites: Dict[str, list[str]] = {}
    for index, detail in enumerate(details):
        if not isinstance(detail, dict):
            raise ValueError(f"{path}: details[{index}] must be an object")
        question_id = detail.get("id")
        if not isinstance(question_id, str) or not question_id:
            raise ValueError(f"{path}: details[{index}].id must be a non-empty string")
        if question_id in rewrites:
            raise ValueError(f"{path}: duplicate detail id: {question_id}")
        if question_id not in expected_rows:
            raise ValueError(f"{path}: unexpected detail id: {question_id}")
        expected_question = expected_rows[question_id].get("question")
        if detail.get("question") != expected_question:
            raise ValueError(f"{path}: question text mismatch for id {question_id}")
        raw_response = detail.get("raw_response")
        if not isinstance(raw_response, dict):
            raise ValueError(f"{path}: {question_id} is missing raw_response")
        raw_sub_questions = raw_response.get("subIntents")
        if not isinstance(raw_sub_questions, list) or not raw_sub_questions:
            raise ValueError(f"{path}: {question_id}.raw_response.subIntents must be non-empty")
        sub_questions: list[str] = []
        seen = set()
        for sub_index, value in enumerate(raw_sub_questions):
            if not isinstance(value, str) or not value.strip():
                raise ValueError(
                    f"{path}: {question_id}.subIntents[{sub_index}] must be a non-empty string"
                )
            normalized = value.strip()
            if normalized != value:
                raise ValueError(f"{path}: {question_id}.subIntents[{sub_index}] is not trimmed")
            if normalized in seen:
                raise ValueError(f"{path}: {question_id}.subIntents contains a duplicate")
            seen.add(normalized)
            sub_questions.append(normalized)
        rewrites[question_id] = sub_questions

    missing = sorted(set(expected_rows) - set(rewrites))
    if missing:
        raise ValueError(f"{path}: missing detail ids: {', '.join(missing)}")
    if len(rewrites) != len(expected_rows):
        raise ValueError(f"{path}: fixed rewrite ids do not exactly match the selected dataset")

    selection = report.get("selection")
    if not isinstance(selection, dict) or selection.get("n") != len(details):
        raise ValueError(f"{path}: selection.n must match details length")
    metadata = {
        "source_sha256": sha256_file(path),
        "source_label": report.get("label"),
        "source_server_commit": report.get("server_commit"),
    }
    return rewrites, metadata


def print_summary(summary: dict) -> None:
    overall = summary.get("overall_answerable") or {}
    print("\nanswerable overall")
    for key in (
        "anchor_hit@5_any",
        "anchor_hit@5_all",
        "anchor_recall",
        "anchor_mrr",
        "context_precision",
        "doc_recall",
        "routing_purity",
        "intent_top1_correct",
    ):
        if key in overall:
            print(f"  {key:26s} {overall[key]:.3f}")
    print("\nby family")
    for family, block in summary.get("by_family", {}).items():
        if not block:
            continue
        hit = block.get("anchor_hit@5_any")
        hit_text = "-" if hit is None else f"{hit:.3f}"
        print(f"  {family:14s} n={block['n']:2d} hit@5(any)={hit_text}")
    latency = summary.get("latency_ms") or {}
    if latency:
        print(f"latency: p50={latency['p50']}ms p95={latency['p95']}ms max={latency['max']}ms")


def print_compare(current: dict, previous: dict) -> None:
    left = (previous.get("summary") or {}).get("overall_answerable") or {}
    right = (current.get("summary") or {}).get("overall_answerable") or {}
    print("\ncomparison")
    for key in ("anchor_hit@5_any", "anchor_hit@5_all", "anchor_recall", "context_precision"):
        if key in left and key in right:
            print(f"  {key:26s} {left[key]:.3f} -> {right[key]:.3f} ({right[key] - left[key]:+.3f})")


def main() -> int:
    args = parse_args()
    try:
        validate_label(args.label)
        rows = load_jsonl(args.dataset)
        _, documents = load_corpus_manifest(args.corpus, REPO_ROOT)
    except (OSError, ValueError) as exc:
        print(f"configuration error: {exc}")
        return 1

    if args.family:
        wanted = set(args.family)
        rows = [row for row in rows if row.get("family") in wanted]
    if args.ids:
        wanted_ids = set(args.ids)
        rows = [row for row in rows if row.get("id") in wanted_ids]
    if not rows:
        print("no matching questions")
        return 1
    if args.concurrency < 1:
        print("concurrency must be >= 1")
        return 1
    if args.setup_manifest and not args.setup_manifest.is_file():
        print(f"setup manifest does not exist: {args.setup_manifest}")
        return 1
    if args.fixed_rewrites_from and not args.setup_manifest:
        print("configuration error: --fixed-rewrites-from requires --setup-manifest")
        return 1
    if args.fixed_rewrites_from and (args.refill_mode is None or args.repeat_index is None):
        print(
            "configuration error: fixed replay requires both --refill-mode and --repeat-index"
        )
        return 1
    if args.fixed_rewrites_from and args.concurrency != 1:
        print("configuration error: fixed replay requires --concurrency 1")
        return 1
    if args.repeat_index is not None and args.refill_mode is None:
        print("configuration error: --repeat-index requires --refill-mode")
        return 1

    dataset_sha256 = sha256_file(args.dataset)
    corpus_sha256 = sha256_file(args.corpus)
    setup_manifest_sha256 = sha256_file(args.setup_manifest) if args.setup_manifest else None
    fixed_rewrites = None
    fixed_rewrite_metadata = None
    if args.fixed_rewrites_from:
        try:
            fixed_rewrites, fixed_rewrite_metadata = load_fixed_rewrites(
                args.fixed_rewrites_from,
                rows,
                dataset_sha256=dataset_sha256,
                corpus_sha256=corpus_sha256,
                setup_manifest_sha256=setup_manifest_sha256,
            )
        except ValueError as exc:
            print(f"configuration error: {exc}")
            return 1

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    output = args.output or DEFAULT_RUNS / f"{stamp}-{args.label}" / "retrieval.json"
    if output.exists():
        print(f"refusing to overwrite {output}")
        return 1

    client = ApiClient(args.base, token=args.token, timeout=args.timeout)
    try:
        if not client.token:
            client.login(args.username, args.password)
    except ApiError as exc:
        print(f"login failed: {exc}")
        return 1

    doc_to_kb = {doc_id: document["kb"] for doc_id, document in documents.items()}
    details = [None] * len(rows)
    failures = []

    def run_one(item):
        index, row = item
        try:
            expected_sub_intents = fixed_rewrites.get(row["id"]) if fixed_rewrites else None
            response, wall_ms = client.query_eval(row["question"], expected_sub_intents)
            if expected_sub_intents is not None:
                actual_sub_intents = response.get("subIntents")
                if actual_sub_intents != expected_sub_intents:
                    raise ApiError(
                        "replay response subIntents mismatch for "
                        f"{row['id']}: expected {expected_sub_intents}, got {actual_sub_intents}"
                    )
            if args.refill_mode is not None:
                try:
                    validate_retrieval_diagnostics(response, args.refill_mode)
                except ValueError as exc:
                    raise ApiError(f"invalid retrieval diagnostics for {row['id']}: {exc}") from exc
            score = score_retrieval(row, response, doc_to_kb, args.intent_mode)
            return index, {
                "id": row["id"],
                "tier": row["tier"],
                "family": row["family"],
                "question": row["question"],
                "answerable": row["answerable"],
                "reference_docs": row["reference_docs"],
                "reference_anchors": row["reference_anchors"],
                "wall_ms": wall_ms,
                "score": score,
                "raw_response": response,
            }, None
        except (ApiError, OSError) as exc:
            return index, None, {"id": row["id"], "error": str(exc)}

    started_at = datetime.now(timezone.utc).isoformat()
    started = time.monotonic()
    with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        for completed, (index, detail, error) in enumerate(executor.map(run_one, enumerate(rows)), 1):
            if error:
                failures.append(error)
                print(f"[{completed}/{len(rows)}] failed {error['id']}: {error['error']}")
            else:
                details[index] = detail
                print(f"[{completed}/{len(rows)}] {detail['id']}")
    details = [detail for detail in details if detail is not None]
    summary = aggregate_retrieval(details)
    report = {
        "schema_version": 1,
        "kind": "retrieval",
        "label": args.label,
        "variant": args.variant,
        "server_commit": args.server_commit,
        "started_at": started_at,
        "elapsed_seconds": round(time.monotonic() - started, 3),
        "base": args.base,
        "dataset_sha256": dataset_sha256,
        "corpus_sha256": corpus_sha256,
        "setup_manifest_sha256": setup_manifest_sha256,
        "selection": {"families": args.family, "ids": args.ids, "n": len(rows)},
        "configuration": {
            "intent_mode": args.intent_mode,
            "ocr": args.ocr,
            "concurrency": args.concurrency,
            "rewrite": {
                "mode": "replay" if fixed_rewrites is not None else "live",
                **(fixed_rewrite_metadata or {}),
            },
            "refill_mode": args.refill_mode,
            "repeat_index": args.repeat_index,
            "parse_profile": "fast",
            "chunk_budget": {
                "max_chars": args.max_chars,
                "overlap_chars": args.overlap_chars,
                "rows_per_chunk": args.rows_per_chunk,
                "tolerance_factor": args.tolerance_factor,
            },
            "retrieval": {
                "default_top_k": 10,
                "recall_budget": 20,
                "rerank_candidate_limit": 40,
                "rrf_k": 20,
                "scope_fallback_mode": "global",
                "scope_min_intent_score": 0.4,
                "scope_confidence_threshold": 0.6,
                "scope_supplement_ratio": 0.25,
            },
            "models": {
                "chat": "qwen3-max",
                "embedding": "qwen-emb-8b",
                "rerank": "qwen3-rerank",
            },
        },
        "failures": failures,
        "summary": summary,
        "details": details,
    }
    write_json(output, report)
    print_summary(summary)
    print(f"\nreport: {output}")
    if args.compare:
        previous = json.loads(args.compare.read_text(encoding="utf-8"))
        print_compare(report, previous)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
