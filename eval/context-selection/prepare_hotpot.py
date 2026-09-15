#!/usr/bin/env python3
"""Create deterministic HotpotQA development and local frozen-test subsets."""

from __future__ import annotations

import argparse
import heapq
from collections import Counter
from datetime import date
from pathlib import Path
from typing import Any, Callable, Iterable, Sequence

from cs_evalkit import SCHEMA_VERSION, iter_source_rows, sha256_file, stable_hash, write_json, write_jsonl


OFFICIAL_HOME = "https://hotpotqa.github.io/"
OFFICIAL_REPOSITORY = "https://github.com/hotpotqa/hotpot"
LICENSE = "CC-BY-SA-4.0"


def row_id(row: dict[str, Any]) -> str:
    return str(row.get("_id") or row.get("id") or "").strip()


def supporting_facts(row: dict[str, Any]) -> list[tuple[str, int]]:
    value = row.get("supporting_facts", [])
    if isinstance(value, dict):
        return [(str(title), int(sentence_id)) for title, sentence_id in zip(value["title"], value["sent_id"])]
    return [(str(title), int(sentence_id)) for title, sentence_id in value]


def contexts(row: dict[str, Any]) -> list[tuple[str, list[str]]]:
    value = row.get("context", [])
    if isinstance(value, dict):
        return [(str(title), list(sentences)) for title, sentences in zip(value["title"], value["sentences"])]
    return [(str(title), list(sentences)) for title, sentences in value]


def has_resolvable_support(row: dict[str, Any]) -> bool:
    sentence_counts: dict[str, list[int]] = {}
    for title, sentences in contexts(row):
        sentence_counts.setdefault(title, []).append(len(sentences))
    return all(
        title in sentence_counts and any(0 <= sentence_id < count for count in sentence_counts[title])
        for title, sentence_id in supporting_facts(row)
    )


def count_unresolvable(paths: Sequence[Path]) -> int:
    return sum(1 for row in source_rows(paths) if not has_resolvable_support(row))


def quotas(counts: Counter[str], target: int) -> dict[str, int]:
    total = sum(counts.values())
    if target > total:
        raise ValueError(f"requested {target} rows from only {total} eligible rows")
    exact = {key: target * count / total for key, count in counts.items()}
    allocated = {key: int(value) for key, value in exact.items()}
    remainder = target - sum(allocated.values())
    order = sorted(counts, key=lambda key: (-(exact[key] - allocated[key]), key))
    for key in order[:remainder]:
        allocated[key] += 1
    return allocated


def priority(seed: int, source_split: str, identifier: str) -> int:
    return int(stable_hash(seed, source_split, identifier), 16)


def source_rows(paths: Sequence[Path]) -> Iterable[dict[str, Any]]:
    for path in paths:
        yield from iter_source_rows(path)


def select_rows(
    paths: Sequence[Path],
    source_split: str,
    target: int,
    seed: int,
    eligible: Callable[[dict[str, Any]], bool] = lambda _: True,
) -> tuple[list[dict[str, Any]], Counter[str], dict[str, int]]:
    counts: Counter[str] = Counter()
    for row in source_rows(paths):
        if row_id(row) and eligible(row):
            counts[str(row.get("type", "unknown"))] += 1
    allocation = quotas(counts, target)
    heaps: dict[str, list[tuple[int, str, dict[str, Any]]]] = {key: [] for key in allocation}
    for row in source_rows(paths):
        identifier = row_id(row)
        row_type = str(row.get("type", "unknown"))
        if not identifier or row_type not in heaps or not eligible(row):
            continue
        item = (-priority(seed, source_split, identifier), identifier, row)
        heap = heaps[row_type]
        if len(heap) < allocation[row_type]:
            heapq.heappush(heap, item)
        elif item > heap[0]:
            heapq.heapreplace(heap, item)
    selected = [item[2] for heap in heaps.values() for item in heap]
    selected.sort(key=lambda row: (priority(seed, source_split, row_id(row)), row_id(row)))
    return selected, counts, allocation


def convert(row: dict[str, Any], split: str) -> dict[str, Any]:
    identifier = row_id(row)
    paragraph_rows = contexts(row)
    candidates: list[dict[str, Any]] = []
    candidates_by_title: dict[str, list[dict[str, Any]]] = {}
    for index, (title, sentences) in enumerate(paragraph_rows):
        candidate_id = f"hp-{identifier}-p{index:02d}-{stable_hash(title)[:8]}"
        candidate = {
            "id": candidate_id,
            "document_id": title,
            "version": "hotpotqa-v1",
            "location": {"title": title, "paragraph_index": index},
            "title": title,
            "sentences": sentences,
            "text": "".join(sentences),
        }
        candidates.append(candidate)
        candidates_by_title.setdefault(title, []).append(candidate)

    requirements: list[dict[str, Any]] = []
    expected_facts: list[str] = []
    for fact_index, (title, sentence_id) in enumerate(supporting_facts(row)):
        candidate = next(
            (value for value in candidates_by_title.get(title, []) if 0 <= sentence_id < len(value["sentences"])),
            None,
        )
        if candidate is None:
            raise ValueError(f"{identifier}: supporting fact {title!r}/{sentence_id} is missing from context")
        expected_facts.append(candidate["sentences"][sentence_id])
        requirements.append({
            "id": f"sf-{fact_index:02d}",
            "alternatives": [{
                "all_of": [{
                    "candidate_id": candidate["id"],
                    "document_id": title,
                    "location": {"sentence_id": sentence_id},
                }]
            }],
        })
    support_titles = sorted({title for title, _ in supporting_facts(row)})
    return {
        "schema_version": SCHEMA_VERSION,
        "id": f"hotpot-{split}-{identifier}",
        "source_id": identifier,
        "source": "hotpotqa-distractor",
        "split": split,
        "group_id": f"hp-docset-{stable_hash(*support_titles)[:16]}",
        "language": "en",
        "question": str(row["question"]),
        "answerable": True,
        "answer": str(row["answer"]),
        "task_type": str(row.get("type", "unknown")),
        "level": str(row.get("level", "unknown")),
        "expected_facts": expected_facts,
        "forbidden_claims": [],
        "evidence_requirements": requirements,
        "candidates": candidates,
    }


def build(args: argparse.Namespace) -> dict[str, Any]:
    if args.output.exists() or args.manifest.exists():
        raise FileExistsError(f"refusing to overwrite {args.output} or {args.manifest}; use a new versioned path")
    source_quality = {
        "train_unresolvable_support_rows": count_unresolvable(args.train_source),
        "validation_unresolvable_support_rows": count_unresolvable(args.validation_source),
    }
    test_rows, test_counts, test_quotas = select_rows(
        args.validation_source, "official-dev", args.test_size, args.seed, has_resolvable_support
    )
    test_questions = {str(row["question"]).strip().casefold() for row in test_rows}
    test_support_titles = {title for row in test_rows for title, _ in supporting_facts(row)}

    exclusion_counts: Counter[str] = Counter()

    def train_eligible(row: dict[str, Any]) -> bool:
        if not has_resolvable_support(row):
            return False
        question = str(row.get("question", "")).strip().casefold()
        if question in test_questions:
            exclusion_counts["duplicate_question"] += 1
            return False
        titles = {title for title, _ in supporting_facts(row)}
        if titles & test_support_titles:
            exclusion_counts["support_document_overlap"] += 1
            return False
        return True

    development_rows, development_counts, development_quotas = select_rows(
        args.train_source, "official-train", args.dev_size, args.seed, train_eligible
    )
    converted = [convert(row, "public_dev") for row in development_rows]
    converted.extend(convert(row, "public_test") for row in test_rows)
    write_jsonl(args.output, converted)
    manifest = {
        "schema_version": "context-selection-source-manifest-v1",
        "created_on": date.today().isoformat(),
        "seed": args.seed,
        "official_home": OFFICIAL_HOME,
        "official_repository": OFFICIAL_REPOSITORY,
        "license": LICENSE,
        "source_record": "eval/context-selection/sources.json",
        "sources": [
            *[
                {"role": "development", "path": str(path), "sha256": sha256_file(path)}
                for path in args.train_source
            ],
            *[
                {"role": "local_frozen_test", "path": str(path), "sha256": sha256_file(path)}
                for path in args.validation_source
            ],
        ],
        "selection": {
            "development": {"target": args.dev_size, "eligible_by_type": development_counts, "selected_by_type": development_quotas},
            "local_frozen_test": {"target": args.test_size, "eligible_by_type": test_counts, "selected_by_type": test_quotas},
            "train_exclusions_observed_across_two_passes": exclusion_counts,
            "cross_split_support_document_overlap": 0,
            "source_quality": source_quality,
        },
        "dataset": {"path": str(args.output), "sha256": sha256_file(args.output), "rows": len(converted)},
    }
    write_json(args.manifest, manifest)
    return manifest


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--train-source", type=Path, action="append", required=True)
    parser.add_argument("--validation-source", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--seed", type=int, default=20260905)
    parser.add_argument("--dev-size", type=int, default=200)
    parser.add_argument("--test-size", type=int, default=400)
    return parser.parse_args()


if __name__ == "__main__":
    manifest = build(parse_args())
    print(f"wrote {manifest['dataset']['rows']} rows: {manifest['dataset']['path']}")
