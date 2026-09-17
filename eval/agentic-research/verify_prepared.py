#!/usr/bin/env python3
"""Verify prepared data, label isolation, source references and frozen samples."""

from __future__ import annotations

import argparse
import json
import sqlite3
import tempfile
from pathlib import Path
from typing import Any

from datasetkit import (CORPUS_KEYS, CORPUS_SCHEMA, MANIFEST_SCHEMA, METADATA_KEYS, OUTPUT_FILES,
                        QUESTION_KEYS, QUESTION_SCHEMA, QUERY_KEYS, QUERY_SCHEMA, SOURCES,
                        canonical, digest_text, iter_jsonl, required_text, sha256_file, stable_id, text_list)


def check(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def validate_corpus(row: dict[str, Any], dataset: str, split: str) -> None:
    check(set(row) == CORPUS_KEYS, "unexpected corpus fields; labels must never enter corpus")
    check(row["schema_version"] == CORPUS_SCHEMA, "unsupported corpus schema")
    check(row["dataset"] == dataset and row["split"] == split, "corpus dataset/split mismatch")
    for key in ("id", "document_id", "title", "text"):
        required_text(row[key], key)
    check(row["content_hash"] == digest_text(row["text"]), "corpus content hash mismatch")
    metadata = row["metadata"]
    check(isinstance(metadata, dict) and set(metadata) <= METADATA_KEYS, "unexpected corpus metadata; possible label leakage")
    check(metadata.get("dataset") == dataset and metadata.get("split") == split, "metadata dataset/split mismatch")
    check(metadata.get("document_version") == f"{dataset}-v{SOURCES[dataset]['version']}", "source version mismatch")
    check(metadata.get("source_paragraph_id") == row["id"], "paragraph identity mismatch")
    extent = "AVAILABLE_EXCERPT" if dataset == "musique" else "CHUNK"
    check(row["source_extent"] == extent and metadata.get("source_extent") == extent.lower(), "source extent mismatch")
    if dataset == "musique":
        check(row["id"] == stable_id("mp", "musique-v1.0", row["title"], row["text"])
              and row["document_id"] == row["id"], "MuSiQue must identify individual title/content excerpts")
        check(set(metadata) == {"dataset", "split", "document_version", "source_extent", "source_paragraph_id",
                                "block_type", "source_field"}, "unexpected MuSiQue provenance")
        check(metadata["block_type"] == "paragraph" and metadata["source_field"] == "paragraph_text", "MuSiQue source must be an available paragraph")
    else:
        paper = required_text(metadata.get("paper_id"), "paper_id")
        field = metadata.get("source_field")
        index = metadata.get("paragraph_index")
        check(type(index) is int and index >= 0, "invalid original paragraph index")
        check(field in ("abstract", "full_text"), "unsupported paper source field")
        section = metadata.get("section_index", -1)
        check(field != "full_text" or (type(section) is int and section >= 0), "invalid original section index")
        if "section_path" in metadata:
            check(len(text_list(metadata["section_path"], "section_path")) == 1
                  and bool(metadata["section_path"][0].strip()), "section path must retain the supplied section name")
        check(row["id"] == stable_id("qp", "qasper-v0.3", paper, field, section, index, row["text"])
              and row["document_id"] == "qasper:" + paper, "paper paragraph identity mismatch")


def expected_query(question: dict[str, Any]) -> dict[str, Any]:
    query = {key: question[key] for key in ("id", "dataset", "split", "question", "retrieval_mode")}
    documents = (["qasper:" + question["metadata"]["paper_id"]] if question["dataset"] == "qasper"
                 else question["candidate_ids"] if question["retrieval_mode"] == "distractor" else [])
    return {**query, "schema_version": QUERY_SCHEMA, "document_ids": documents}


def validate_question(row: dict[str, Any], dataset: str, split: str, mode: str, db: sqlite3.Connection) -> None:
    check(set(row) == QUESTION_KEYS and row["schema_version"] == QUESTION_SCHEMA, "unsupported question schema")
    check(row["dataset"] == dataset and row["split"] == split and row["retrieval_mode"] == mode, "question scope mismatch")
    for key in ("id", "source_question_id", "question"):
        required_text(row[key], key)
    candidates = text_list(row["candidate_ids"], "candidate_ids")
    check(bool(candidates) and len(candidates) == len(set(candidates)), "candidate IDs must be non-empty and unique")
    documents = set()
    for identifier in candidates:
        found = db.execute("SELECT document_id FROM corpus WHERE id = ?", (identifier,)).fetchone()
        check(found is not None, "candidate references missing corpus text")
        documents.add(found[0])
    metadata = row["metadata"]
    check(isinstance(metadata, dict), "question metadata must be an object")
    if dataset == "qasper":
        check(set(metadata) == {"paper_id"}, "unexpected paper question metadata")
        check(documents == {"qasper:" + metadata["paper_id"]}, "paper question crosses documents")
        identifier = stable_id("qq", "qasper-v0.3", metadata["paper_id"], row["source_question_id"])
    else:
        check(set(metadata) == {"variant", "source_candidates"} and metadata["variant"] in ("ans", "full"), "invalid MuSiQue metadata")
        mapping = metadata["source_candidates"]
        check(isinstance(mapping, list) and bool(mapping), "missing original candidate mapping")
        check(all(isinstance(item, dict) and set(item) == {"paragraph_idx", "corpus_id"}
                  and type(item["paragraph_idx"]) is int and item["paragraph_idx"] >= 0 for item in mapping), "invalid candidate mapping")
        indexes = [item["paragraph_idx"] for item in mapping]
        check(indexes == sorted(set(indexes)), "candidate indexes must be unique and ordered")
        check(list(dict.fromkeys(item["corpus_id"] for item in mapping)) == candidates, "candidate mapping changed")
        identifier = stable_id("mq", "musique-v1.0", row["source_question_id"],
                               [(item["paragraph_idx"], item["corpus_id"]) for item in mapping])
    check(row["id"] == identifier, "question/context identity mismatch")
    gold = row["gold"]
    if gold is None:
        return
    check(isinstance(gold, dict), "gold must be an object or null")
    if dataset == "qasper":
        check(set(gold) == {"annotations"} and isinstance(gold["annotations"], list), "invalid paper annotations")
        for annotation in gold["annotations"]:
            check(isinstance(annotation, dict) and set(annotation) == {"annotation_id", "unanswerable", "yes_no", "extractive_spans", "free_form_answer", "evidence"}, "invalid annotation fields")
            check(type(annotation["unanswerable"]) is bool, "unanswerable must be boolean")
            check(annotation["yes_no"] is None or type(annotation["yes_no"]) is bool, "invalid yes/no label")
            text_list(annotation["extractive_spans"], "extractive_spans")
            check(isinstance(annotation["evidence"], list), "evidence must be a list")
            for evidence in annotation["evidence"]:
                check(isinstance(evidence, dict) and set(evidence) == {"text", "corpus_ids", "status"}, "invalid evidence mapping")
                ids = text_list(evidence["corpus_ids"], "evidence corpus_ids")
                check(evidence["status"] == ("resolved" if ids else "unresolved") and set(ids) <= set(candidates), "gold evidence has invalid references")
                check(isinstance(evidence["text"], str), "evidence text must be a string")
                for identifier in ids:
                    text = db.execute("SELECT text FROM corpus WHERE id = ?", (identifier,)).fetchone()[0]
                    check(" ".join(text.split()) == " ".join(evidence["text"].split()), "gold evidence does not match the referenced paragraph")
    else:
        check(set(gold) == {"answerable", "answer", "answer_aliases", "support_ids", "decomposition"}, "invalid MuSiQue gold fields")
        check(type(gold["answerable"]) is bool and isinstance(gold["answer"], str), "invalid MuSiQue answer label")
        text_list(gold["answer_aliases"], "answer_aliases")
        check(set(text_list(gold["support_ids"], "support_ids")) <= set(candidates), "support references missing context")
        check(isinstance(gold["decomposition"], list) and all(isinstance(step, dict) for step in gold["decomposition"]), "invalid gold decomposition")


def validate_dataset(prepared: Path, data_root: Path | None = None) -> dict[str, Any]:
    manifest = json.loads((prepared / "manifest.json").read_text(encoding="utf-8"))
    check(manifest["schema_version"] == MANIFEST_SCHEMA, "unsupported manifest schema")
    dataset, split, mode = manifest["dataset"], manifest["split"], manifest["retrieval_mode"]
    check(dataset in SOURCES and mode in (("paper",) if dataset == "qasper" else ("pooled-context", "distractor")), "invalid dataset/mode")
    check(split in (("train", "validation", "test") if dataset == "qasper" else ("train", "dev", "test")), "invalid original split")
    check(manifest["source"] == SOURCES[dataset], "dataset source/version/attribution mismatch")
    check(isinstance(manifest["inputs"], list) and bool(manifest["inputs"]), "missing raw input inventory")
    check(type(manifest["sampling"]["seed"]) is int, "sampling seed must be an integer")
    check(set(manifest["outputs"]) == set(OUTPUT_FILES), "unexpected prepared output files")
    for name, fingerprint in manifest["outputs"].items():
        path = prepared / name
        check(path.stat().st_size == fingerprint["size_bytes"] and sha256_file(path) == fingerprint["sha256"], f"output fingerprint mismatch: {name}")
    if data_root is not None:
        raw = (data_root / "raw").resolve()
        for item in manifest["inputs"]:
            path = (raw / item["path"]).resolve()
            check(raw in path.parents, "input path escapes raw data root")
            check(path.stat().st_size == item["size_bytes"] and sha256_file(path) == item["sha256"], "raw input fingerprint mismatch")
    with tempfile.TemporaryDirectory(prefix="research-verify-") as scratch:
        db = sqlite3.connect(str(Path(scratch) / "index.sqlite"))
        try:
            db.executescript("""
                CREATE TABLE corpus (id TEXT PRIMARY KEY, document_id TEXT NOT NULL, text TEXT NOT NULL);
                CREATE TABLE questions (id TEXT PRIMARY KEY, source_id TEXT NOT NULL, priority TEXT NOT NULL, payload TEXT NOT NULL, query TEXT NOT NULL);
                CREATE TABLE delivered_queries (id TEXT PRIMARY KEY);
            """)
            for row in iter_jsonl(prepared / "corpus.jsonl"):
                validate_corpus(row, dataset, split)
                db.execute("INSERT INTO corpus VALUES (?, ?, ?)", (row["id"], row["document_id"], row["text"]))
            seed = manifest["sampling"]["seed"]
            for row in iter_jsonl(prepared / "questions.jsonl"):
                validate_question(row, dataset, split, mode, db)
                db.execute("INSERT INTO questions VALUES (?, ?, ?, ?, ?)",
                           (row["id"], row["source_question_id"], stable_id("sample", seed, row["id"]), canonical(row), canonical(expected_query(row))))
            for row in iter_jsonl(prepared / "queries.jsonl"):
                check(set(row) == QUERY_KEYS, "unexpected query fields; labels must never enter planner input")
                found = db.execute("SELECT query FROM questions WHERE id = ?", (row["id"],)).fetchone()
                check(found is not None and canonical(row) == found[0], "query is not the gold-free question projection")
                db.execute("INSERT INTO delivered_queries VALUES (?)", (row["id"],))
            counts = {"corpus_units": db.execute("SELECT count(*) FROM corpus").fetchone()[0],
                      "documents": db.execute("SELECT count(DISTINCT document_id) FROM corpus").fetchone()[0],
                      "questions": db.execute("SELECT count(*) FROM questions").fetchone()[0],
                      "distinct_source_question_ids": db.execute("SELECT count(DISTINCT source_id) FROM questions").fetchone()[0]}
            for key, value in counts.items():
                check(value == manifest["counts"][key], f"manifest count mismatch: {key}")
            check(counts["questions"] == db.execute("SELECT count(*) FROM delivered_queries").fetchone()[0], "queries omit questions")
            profiles = {}
            for profile in ("smoke", "regression"):
                spec = manifest["counts"]["profiles"][profile]
                check(type(spec["requested"]) is int and spec["requested"] >= 1, "invalid sample size")
                selected = list(db.execute("SELECT id, payload, query FROM questions ORDER BY priority, id LIMIT ?",
                                           (spec["requested"],)))
                check(spec["questions"] == len(selected) and spec["question_ids_hash"] == digest_text(canonical([item[0] for item in selected])), "frozen sample identity mismatch")
                for column, name in ((1, "questions"), (2, "queries")):
                    rows = iter_jsonl(prepared / f"{name}.{profile}.jsonl")
                    for item in selected:
                        row = next(rows, None)
                        check(row is not None and canonical(row) == item[column], "sample changed or gold-free projection failed")
                    check(next(rows, None) is None, "sample has extra rows")
                profiles[profile] = len(selected)
            return {"dataset": dataset, "split": split, "retrieval_mode": mode, "counts": counts,
                    "profiles": profiles, "raw_inputs_verified": data_root is not None,
                    "label_field_isolation": "passed", "source_references": "passed", "model_api_calls": 0}
        finally:
            db.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepared", required=True, type=Path)
    parser.add_argument("--data-root", type=Path, help="also verify the original file hashes")
    args = parser.parse_args()
    try:
        report = validate_dataset(args.prepared, args.data_root)
    except (ValueError, KeyError, TypeError, OSError, sqlite3.Error) as exc:
        parser.exit(1, f"Validation failed: {exc}\n")
    print(canonical(report))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
