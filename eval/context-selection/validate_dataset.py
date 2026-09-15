#!/usr/bin/env python3
"""Validate context-selection labels, evidence locations, and split isolation."""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

import jsonschema

from cs_evalkit import load_jsonl, sha256_file, write_json


def validate(dataset: Path, schema_path: Path) -> dict[str, Any]:
    rows = load_jsonl(dataset)
    schema = __import__("json").loads(schema_path.read_text(encoding="utf-8"))
    validator = jsonschema.Draft202012Validator(schema)
    errors: list[str] = []
    ids: set[str] = set()
    questions_by_split: dict[str, set[str]] = defaultdict(set)
    evidence_documents_by_split: dict[str, set[str]] = defaultdict(set)
    counts: Counter[tuple[str, str]] = Counter()
    for row_number, row in enumerate(rows, 1):
        for error in validator.iter_errors(row):
            errors.append(f"row {row_number} {list(error.absolute_path)}: {error.message}")
        identifier = str(row.get("id", ""))
        if identifier in ids:
            errors.append(f"row {row_number}: duplicate id {identifier}")
        ids.add(identifier)
        split = str(row.get("split", ""))
        question_key = " ".join(str(row.get("question", "")).split()).casefold()
        if any(question_key in values for key, values in questions_by_split.items() if key != split):
            errors.append(f"row {row_number}: question duplicated across splits")
        questions_by_split[split].add(question_key)
        counts[(split, str(row.get("task_type", "unknown")))] += 1
        candidates = {str(candidate.get("id")): candidate for candidate in row.get("candidates", [])}
        for requirement in row.get("evidence_requirements", []):
            for alternative in requirement.get("alternatives", []):
                for item in alternative.get("all_of", []):
                    candidate = candidates.get(str(item.get("candidate_id")))
                    if candidate is None:
                        errors.append(f"row {row_number}: evidence references unknown candidate {item.get('candidate_id')}")
                        continue
                    expected_document = item.get("document_id")
                    if expected_document != candidate.get("document_id"):
                        errors.append(f"row {row_number}: evidence document does not match candidate")
                    evidence_documents_by_split[split].add(str(expected_document))
                    sentence_id = item.get("location", {}).get("sentence_id")
                    if sentence_id is not None and not 0 <= sentence_id < len(candidate.get("sentences", [])):
                        errors.append(f"row {row_number}: sentence_id {sentence_id} is out of range")
    split_names = sorted(evidence_documents_by_split)
    overlaps: dict[str, int] = {}
    for index, left in enumerate(split_names):
        for right in split_names[index + 1:]:
            overlap = evidence_documents_by_split[left] & evidence_documents_by_split[right]
            overlaps[f"{left}|{right}"] = len(overlap)
            if overlap:
                errors.append(f"evidence document overlap {left}/{right}: {len(overlap)}")
    report = {
        "schema_version": "context-selection-validation-v1",
        "dataset": str(dataset),
        "dataset_sha256": sha256_file(dataset),
        "rows": len(rows),
        "counts": {f"{split}/{task_type}": count for (split, task_type), count in sorted(counts.items())},
        "evidence_document_overlap": overlaps,
        "valid": not errors,
        "errors": errors,
    }
    return report


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--schema", type=Path, default=Path(__file__).with_name("dataset.schema.json"))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = validate(args.dataset, args.schema)
    if args.output:
        write_json(args.output, report)
    print(f"rows={report['rows']} valid={report['valid']} errors={len(report['errors'])}")
    if not report["valid"]:
        for error in report["errors"][:20]:
            print(error)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
