#!/usr/bin/env python3
"""Validate candidate replay snapshots and enforce the gold isolation boundary."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import jsonschema

from cs_evalkit import load_jsonl, sha256_file, stable_hash, write_json


PROHIBITED_KEYS = {"answer", "expected_facts", "evidence_requirements", "supporting_facts", "gold"}


def validate(path: Path, schema_path: Path) -> dict[str, Any]:
    rows = load_jsonl(path)
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    validator = jsonschema.Draft202012Validator(schema)
    errors: list[str] = []
    example_ids: set[str] = set()
    embedding_dimensions: set[int] = set()
    for row_number, row in enumerate(rows, 1):
        for error in validator.iter_errors(row):
            errors.append(f"row {row_number} {list(error.absolute_path)}: {error.message}")
        leaked = PROHIBITED_KEYS & set(row)
        if leaked:
            errors.append(f"row {row_number}: prohibited top-level gold fields: {sorted(leaked)}")
        identifier = str(row.get("example_id", ""))
        if identifier in example_ids:
            errors.append(f"row {row_number}: duplicate example_id {identifier}")
        example_ids.add(identifier)
        candidate_ids: set[str] = set()
        aspect_count = len(row.get("predicted_aspects", []))
        for candidate in row.get("candidates", []):
            candidate_id = str(candidate.get("id", ""))
            if candidate_id in candidate_ids:
                errors.append(f"row {row_number}: duplicate candidate id {candidate_id}")
            candidate_ids.add(candidate_id)
            if stable_hash(candidate.get("text", "")) != candidate.get("text_sha256"):
                errors.append(f"row {row_number}: text hash mismatch for {candidate_id}")
            dimensions = len(candidate.get("embedding", []))
            if dimensions:
                embedding_dimensions.add(dimensions)
            seen_aspects: set[int] = set()
            for score in candidate.get("aspect_scores", []):
                aspect_index = score.get("aspect_index")
                if aspect_index in seen_aspects or not isinstance(aspect_index, int) or not 0 <= aspect_index < aspect_count:
                    errors.append(f"row {row_number}: invalid aspect score index for {candidate_id}")
                seen_aspects.add(aspect_index)
    if len(embedding_dimensions) > 1:
        errors.append(f"mixed embedding dimensions: {sorted(embedding_dimensions)}")
    return {
        "schema_version": "context-selection-snapshot-validation-v1",
        "snapshot": str(path),
        "snapshot_sha256": sha256_file(path),
        "rows": len(rows),
        "embedding_dimensions": sorted(embedding_dimensions),
        "gold_boundary": "no prohibited top-level labels",
        "valid": not errors,
        "errors": errors,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--snapshots", type=Path, required=True)
    parser.add_argument("--schema", type=Path, default=Path(__file__).with_name("candidate-snapshot.schema.json"))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = validate(args.snapshots, args.schema)
    if args.output:
        write_json(args.output, report)
    print(f"rows={report['rows']} valid={report['valid']} errors={len(report['errors'])}")
    if not report["valid"]:
        for error in report["errors"][:20]:
            print(error)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
