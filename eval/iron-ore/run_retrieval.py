#!/usr/bin/env python3
"""Run the frozen question set against ``GET /rag/eval`` and score retrieval."""

from __future__ import annotations

import argparse
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

from evalkit import (
    ApiClient,
    ApiError,
    aggregate_retrieval,
    load_corpus_manifest,
    load_jsonl,
    score_retrieval,
    sha256_file,
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
    return parser.parse_args()


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
            response, wall_ms = client.query_eval(row["question"])
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
        "dataset_sha256": sha256_file(args.dataset),
        "corpus_sha256": sha256_file(args.corpus),
        "setup_manifest_sha256": sha256_file(args.setup_manifest) if args.setup_manifest else None,
        "selection": {"families": args.family, "ids": args.ids, "n": len(rows)},
        "configuration": {
            "intent_mode": args.intent_mode,
            "ocr": args.ocr,
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
