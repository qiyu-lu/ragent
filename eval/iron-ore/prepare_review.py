#!/usr/bin/env python3
"""Build a blinded, two-arm human review bundle and a separate answer key."""

from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path

from evalkit import load_jsonl, sha256_file, validate_label, write_json, write_jsonl


HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
DEFAULT_DATASET = REPO_ROOT / "local-data/eval/dataset-v1.jsonl"


def parse_arm(value: str):
    if "=" not in value:
        raise argparse.ArgumentTypeError("arm must use LABEL=/path/to/answers.json")
    label, raw_path = value.split("=", 1)
    try:
        validate_label(label)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(str(exc)) from exc
    return label, Path(raw_path)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--arm", action="append", type=parse_arm, required=True)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--output", type=Path, required=True, help="blinded JSONL to edit")
    parser.add_argument("--key", type=Path, required=True, help="keep hidden until review is complete")
    parser.add_argument("--seed", type=int, default=20260813)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if len(args.arm) != 2:
        print("exactly two --arm arguments are required")
        return 1
    if len({label for label, _ in args.arm}) != 2:
        print("arm labels must be unique")
        return 1
    if args.output.resolve() == args.key.resolve():
        print("review output and hidden key must be different files")
        return 1
    if args.output.exists() or args.key.exists():
        print("refusing to overwrite review output or key")
        return 1

    try:
        dataset = load_jsonl(args.dataset)
        dataset_by_id = {row["id"]: row for row in dataset}
        arms = {}
        arm_meta = {}
        for label, path in args.arm:
            report = json.loads(path.read_text(encoding="utf-8"))
            if report.get("kind") != "answers":
                raise ValueError(f"{path} is not an answer report")
            details = {row["id"]: row for row in report.get("details") or []}
            if set(details) != set(dataset_by_id):
                missing = sorted(set(dataset_by_id) - set(details))
                extra = sorted(set(details) - set(dataset_by_id))
                raise ValueError(f"{label}: answer IDs differ; missing={missing}, extra={extra}")
            arms[label] = details
            arm_meta[label] = {
                "path": str(path.resolve()),
                "sha256": sha256_file(path),
                "variant": report.get("variant"),
                "server_commit": report.get("server_commit"),
                "configuration": report.get("configuration"),
            }
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"input error: {exc}")
        return 1

    rng = random.Random(args.seed)
    review_rows = []
    key_entries = []
    labels = [label for label, _ in args.arm]
    for question_index, dataset_row in enumerate(dataset, 1):
        shuffled = list(labels)
        rng.shuffle(shuffled)
        for option_index, arm_label in enumerate(shuffled):
            option = chr(ord("A") + option_index)
            review_id = f"R{question_index:02d}-{option}"
            answer = arms[arm_label][dataset_row["id"]]
            review_rows.append(
                {
                    "review_id": review_id,
                    "question_id": dataset_row["id"],
                    "question": dataset_row["question"],
                    "answerable": dataset_row["answerable"],
                    "expected_facts": dataset_row["expected_facts"],
                    "forbidden_claims": dataset_row["forbidden_claims"],
                    "reference_docs": dataset_row["reference_docs"],
                    "source": dataset_row["source"],
                    "answer": answer["answer"],
                    "sources": answer.get("sources") or [],
                    "fact_correct": None,
                    "evidence_supported": None,
                    "source_correct": None,
                    "no_forbidden_claim": None,
                    "refusal_correct": None,
                    "notes": "",
                }
            )
            key_entries.append(
                {
                    "review_id": review_id,
                    "arm": arm_label,
                    "question_id": dataset_row["id"],
                    "family": dataset_row["family"],
                    "tier": dataset_row["tier"],
                    "answerable": dataset_row["answerable"],
                    "wall_ms": answer.get("wall_ms"),
                }
            )

    write_jsonl(args.output, review_rows)
    write_json(
        args.key,
        {
            "schema_version": 1,
            "kind": "blinded-review-key",
            "seed": args.seed,
            "dataset_sha256": sha256_file(args.dataset),
            "arms": arm_meta,
            "entries": key_entries,
        },
    )
    print(f"review rows: {len(review_rows)} -> {args.output}")
    print(f"hidden key: {args.key}")
    print("请先完成 review JSONL 中的人工字段，再打开 key 或运行 score_review.py。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
