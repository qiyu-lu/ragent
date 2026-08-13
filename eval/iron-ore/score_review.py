#!/usr/bin/env python3
"""Unblind completed human labels and calculate strict grounded-answer scores."""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

from evalkit import load_jsonl, mean, percentile, sha256_file, write_json


ANSWERABLE_FIELDS = ("fact_correct", "evidence_supported", "source_correct", "no_forbidden_claim")
UNANSWERABLE_FIELDS = ("refusal_correct", "no_forbidden_claim")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--review", type=Path, required=True)
    parser.add_argument("--key", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--allow-incomplete", action="store_true")
    return parser.parse_args()


def metric_block(rows: list) -> dict:
    if not rows:
        return {"n": 0}
    block = {"n": len(rows), "strict_pass_rate": mean(row["strict_pass"] for row in rows)}
    for field in ANSWERABLE_FIELDS + ("refusal_correct",):
        value = mean(float(row[field]) if isinstance(row.get(field), bool) else None for row in rows)
        if value is not None:
            block[field] = value
    latencies = [float(row["wall_ms"]) for row in rows if row.get("wall_ms") is not None]
    if latencies:
        block["latency_ms"] = {
            "p50": round(percentile(latencies, 0.50) or 0),
            "p95": round(percentile(latencies, 0.95) or 0),
            "max": round(max(latencies)),
        }
    return block


def main() -> int:
    args = parse_args()
    if args.output.exists():
        print(f"refusing to overwrite {args.output}")
        return 1
    try:
        review = load_jsonl(args.review)
        key = json.loads(args.key.read_text(encoding="utf-8"))
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"input error: {exc}")
        return 1

    key_by_id = {row["review_id"]: row for row in key.get("entries") or []}
    review_by_id = {row["review_id"]: row for row in review}
    if len(key_by_id) != len(key.get("entries") or []) or len(review_by_id) != len(review):
        print("duplicate review IDs detected")
        return 1
    if set(key_by_id) != set(review_by_id):
        print("review IDs differ from the hidden key")
        return 1

    incomplete = []
    scored = []
    for review_id, row in review_by_id.items():
        meta = key_by_id[review_id]
        required = ANSWERABLE_FIELDS if meta["answerable"] else UNANSWERABLE_FIELDS
        missing = [field for field in required if not isinstance(row.get(field), bool)]
        if missing:
            incomplete.append({"review_id": review_id, "missing": missing})
            if not args.allow_incomplete:
                continue
        strict = bool(not missing and all(row.get(field) is True for field in required))
        scored.append({**meta, **{field: row.get(field) for field in set(ANSWERABLE_FIELDS + UNANSWERABLE_FIELDS)},
                       "strict_pass": float(strict), "notes": row.get("notes") or ""})

    if incomplete and not args.allow_incomplete:
        print(f"{len(incomplete)} review rows are incomplete; no score was written")
        for item in incomplete[:10]:
            print(f"  {item['review_id']}: {', '.join(item['missing'])}")
        return 2

    by_arm = defaultdict(list)
    for row in scored:
        by_arm[row["arm"]].append(row)
    summary = {}
    for arm, rows in sorted(by_arm.items()):
        summary[arm] = {
            "overall": metric_block(rows),
            "answerable": metric_block([row for row in rows if row["answerable"]]),
            "unanswerable": metric_block([row for row in rows if not row["answerable"]]),
            "by_family": {
                family: metric_block([row for row in rows if row["family"] == family])
                for family in sorted({row["family"] for row in rows})
            },
        }

    report = {
        "schema_version": 1,
        "kind": "human-review-score",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "review_sha256": sha256_file(args.review),
        "key_sha256": sha256_file(args.key),
        "incomplete": incomplete,
        "arms": key.get("arms"),
        "summary": summary,
        "details": scored,
    }
    write_json(args.output, report)
    for arm, arm_summary in summary.items():
        overall = arm_summary["overall"]
        print(f"{arm}: strict pass {overall['strict_pass_rate']:.3f} ({overall['n']} rows)")
    print(f"report: {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
