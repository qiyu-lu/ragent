#!/usr/bin/env python3
"""Create gold-free, versioned offline candidate feature snapshots."""

from __future__ import annotations

import argparse
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from cs_evalkit import (
    SNAPSHOT_SCHEMA_VERSION,
    bm25_scores,
    explicit_aspects,
    hashing_vector,
    heuristic_token_count,
    load_jsonl,
    sha256_file,
    stable_hash,
    write_jsonl,
)


def git_head(repo: Path) -> str:
    completed = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=repo, check=True, text=True, capture_output=True
    )
    return completed.stdout.strip()


def snapshot(row: dict[str, Any], dataset_sha256: str, code_commit: str) -> dict[str, Any]:
    question = str(row["question"])
    aspects = explicit_aspects(question, str(row.get("task_type", "")))[:4]
    candidates = list(row["candidates"])
    ranking_texts = [f"{candidate.get('title', '')}\n{candidate['text']}" for candidate in candidates]
    score_started = time.perf_counter_ns()
    original_scores = bm25_scores(question, ranking_texts)
    original_scoring_ms = (time.perf_counter_ns() - score_started) / 1_000_000
    aspect_started = time.perf_counter_ns()
    aspect_matrix = [bm25_scores(aspect, ranking_texts) for aspect in aspects]
    aspect_scoring_ms = (time.perf_counter_ns() - aspect_started) / 1_000_000
    embedding_started = time.perf_counter_ns()
    captured_candidates: list[dict[str, Any]] = []
    for index, candidate in enumerate(candidates):
        text = str(candidate["text"])
        ranking_text = ranking_texts[index]
        captured_candidates.append({
            "id": candidate["id"],
            "document_id": candidate.get("document_id"),
            "version": candidate.get("version"),
            "location": candidate.get("location", {}),
            "text": text,
            "text_sha256": stable_hash(text),
            "ranking_text_sha256": stable_hash(ranking_text),
            "source_order": index,
            "source_ranks": {"provided_context": index + 1},
            "source_scores": {},
            "original_question_score": {
                "value": original_scores[index], "type": "offline-bm25-v1", "executed": True
            },
            "aspect_scores": [
                {"aspect_index": aspect_index, "value": scores[index], "type": "offline-bm25-v1", "executed": True}
                for aspect_index, scores in enumerate(aspect_matrix)
            ],
            "embedding": hashing_vector(ranking_text),
            "embedding_type": "offline-signed-hashing-v1-256",
            "render_token_count": heuristic_token_count(f"Source: {candidate.get('title', '')}\n{text}"),
        })
    embedding_ms = (time.perf_counter_ns() - embedding_started) / 1_000_000
    return {
        "schema_version": SNAPSHOT_SCHEMA_VERSION,
        "example_id": row["id"],
        "split": row["split"],
        "language": row["language"],
        "task_type": row["task_type"],
        "original_question": question,
        "predicted_aspects": aspects,
        "candidates": captured_candidates,
        "provenance": {
            "dataset_sha256": dataset_sha256,
            "code_commit": code_commit,
            "index_identity": "hotpotqa-provided-distractor-context-v1",
            "config_identity": "offline-fixture-features-v1",
            "original_score_model": "offline-bm25-v1",
            "aspect_score_model": "offline-bm25-v1",
            "embedding_model": "offline-signed-hashing-v1-256",
            "tokenizer": "heuristic-word-cjk-punctuation-v1",
            "prompt_sha256": None,
            "provider": "offline",
            "random_seed": 20260905,
            "fallbacks": [
                "bridge questions use the original question as their only aspect",
                "token counts are local estimates rather than provider usage",
                "hashing vectors are a redundancy proxy rather than model embeddings",
            ],
            "captured_at": datetime.now(timezone.utc).isoformat(),
        },
        "timings_ms": {
            "retrieval": 0,
            "original_scoring": round(original_scoring_ms, 6),
            "aspect_scoring": round(aspect_scoring_ms, 6),
            "embedding_and_local_token_counting": round(embedding_ms, 6),
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--split", action="append")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(f"refusing to overwrite {args.output}; use a new versioned path")
    rows = load_jsonl(args.dataset)
    if args.split:
        rows = [row for row in rows if row.get("split") in set(args.split)]
    if args.limit is not None:
        rows = rows[: args.limit]
    dataset_hash = sha256_file(args.dataset)
    code_commit = git_head(args.repo)
    write_jsonl(args.output, (snapshot(row, dataset_hash, code_commit) for row in rows))
    print(f"wrote {len(rows)} gold-free snapshots to {args.output}")


if __name__ == "__main__":
    main()
