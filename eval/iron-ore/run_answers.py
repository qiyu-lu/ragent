#!/usr/bin/env python3
"""Collect complete SSE answers for the two human-reviewed main arms."""

from __future__ import annotations

import argparse
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from evalkit import ApiClient, ApiError, load_jsonl, percentile, sha256_file, validate_label, write_json


HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
DEFAULT_DATASET = REPO_ROOT / "local-data/eval/dataset-v1.jsonl"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--variant", required=True, choices=["baseline", "current"])
    parser.add_argument("--server-commit", required=True)
    parser.add_argument("--intent-mode", required=True, choices=["off", "on"])
    parser.add_argument("--ocr", required=True, choices=["off", "on"])
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--setup-manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--token")
    parser.add_argument("--timeout", type=int, default=360)
    parser.add_argument("--id", action="append", dest="ids")
    parser.add_argument("--deep-thinking", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        validate_label(args.label)
        rows = load_jsonl(args.dataset)
    except (OSError, ValueError) as exc:
        print(f"input error: {exc}")
        return 1
    if args.ids:
        wanted = set(args.ids)
        rows = [row for row in rows if row["id"] in wanted]
    if not rows:
        print("no matching questions")
        return 1
    if args.output.exists():
        print(f"refusing to overwrite {args.output}")
        return 1
    if args.setup_manifest and not args.setup_manifest.is_file():
        print(f"setup manifest does not exist: {args.setup_manifest}")
        return 1

    client = ApiClient(args.base, token=args.token, timeout=args.timeout)
    try:
        if not client.token:
            client.login(args.username, args.password)
    except ApiError as exc:
        print(f"login failed: {exc}")
        return 1

    started_at = datetime.now(timezone.utc).isoformat()
    started = time.monotonic()
    details = []
    failures = []
    for index, row in enumerate(rows, 1):
        try:
            result = client.query_answer(row["question"], deep_thinking=args.deep_thinking)
            event_names = [event.get("event") for event in result.get("events") or []]
            finish = result.get("finish") or {}
            if not result.get("answer"):
                raise ApiError("empty answer")
            if "done" not in event_names:
                raise ApiError("SSE stream ended without done event")
            details.append(
                {
                    "id": row["id"],
                    "tier": row["tier"],
                    "family": row["family"],
                    "question": row["question"],
                    "answerable": row["answerable"],
                    "answer": result["answer"],
                    "thinking": result["thinking"],
                    "sources": finish.get("sources") or [],
                    "message_status": finish.get("messageStatus"),
                    "wall_ms": result["wall_ms"],
                    "raw_events": result["events"],
                }
            )
            print(f"[{index}/{len(rows)}] {row['id']} ({result['wall_ms']}ms)")
        except (ApiError, OSError) as exc:
            failures.append({"id": row["id"], "error": str(exc)})
            print(f"[{index}/{len(rows)}] failed {row['id']}: {exc}")

    latencies = [float(item["wall_ms"]) for item in details]
    report = {
        "schema_version": 1,
        "kind": "answers",
        "label": args.label,
        "variant": args.variant,
        "server_commit": args.server_commit,
        "started_at": started_at,
        "elapsed_seconds": round(time.monotonic() - started, 3),
        "base": args.base,
        "dataset_sha256": sha256_file(args.dataset),
        "setup_manifest_sha256": sha256_file(args.setup_manifest) if args.setup_manifest else None,
        "configuration": {
            "intent_mode": args.intent_mode,
            "ocr": args.ocr,
            "deep_thinking": args.deep_thinking,
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
        "summary": {
            "requested": len(rows),
            "completed": len(details),
            "failed": len(failures),
            "latency_ms": {
                "p50": round(percentile(latencies, 0.50) or 0),
                "p95": round(percentile(latencies, 0.95) or 0),
                "max": round(max(latencies)) if latencies else None,
            },
        },
        "failures": failures,
        "details": details,
    }
    write_json(args.output, report)
    print(f"report: {args.output}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
