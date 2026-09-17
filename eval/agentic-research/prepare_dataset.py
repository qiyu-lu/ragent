#!/usr/bin/env python3
"""Prepare QASPER/MuSiQue corpus, scorer-only labels and gold-free queries."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import tempfile
from collections import Counter
from pathlib import Path
from typing import Any

from datasetkit import (CONVERTER_VERSION, MANIFEST_SCHEMA, OUTPUT_FILES, QUESTION_SCHEMA, SOURCES,
                        PreparedWriter, canonical, iter_jsonl, iter_source_rows, records, required_text,
                        sha256_file, source_unit, stable_id, text_list)


def qasper_paper(row: dict[str, Any], split: str, writer: PreparedWriter, stats: Counter) -> None:
    paper_id = required_text(row.get("id"), "paper id")
    title = required_text(row.get("title"), "paper title")
    document_id = "qasper:" + paper_id
    candidate_ids = []
    evidence_map = {}

    def paragraph(text: str, field: str, section_index: int, paragraph_index: int, section_name: Any = None):
        if not isinstance(text, str):
            raise ValueError("paper paragraph must be a string")
        if not text.strip():
            stats["empty_paragraphs_skipped"] += 1
            return
        identifier = stable_id("qp", "qasper-v0.3", paper_id, field, section_index, paragraph_index, text)
        metadata = {"paper_id": paper_id, "source_field": field,
                    "paragraph_index": paragraph_index, "block_type": "abstract" if field == "abstract" else "paragraph"}
        if field == "full_text":
            metadata["section_index"] = section_index
            if section_name is not None and not isinstance(section_name, str):
                raise ValueError("section name must be a string or null")
            if isinstance(section_name, str) and section_name.strip():
                metadata["section_path"] = [section_name]
        writer.add_source(source_unit("qasper", split, identifier, document_id, title, text, metadata))
        candidate_ids.append(identifier)
        evidence_map.setdefault(" ".join(text.split()), []).append(identifier)

    abstract = row.get("abstract")
    if abstract is not None:
        paragraph(abstract, "abstract", -1, 0)
    for section_index, section in enumerate(records(row.get("full_text"), "full_text")):
        for paragraph_index, text in enumerate(text_list(section.get("paragraphs"), "paragraphs")):
            paragraph(text, "full_text", section_index, paragraph_index, section.get("section_name"))
    if not candidate_ids:
        raise ValueError("paper has no available text")
    # Image captions are not table bodies. No figures/captions are turned into fake table text.
    stats["papers"] += 1
    for qa in records(row.get("qas"), "qas"):
        source_id = required_text(qa.get("question_id"), "question id")
        annotations = []
        for annotation in records(qa.get("answers", []), "answers"):
            answers = annotation.get("answer")
            if isinstance(answers, dict):
                answers = [answers]
            if not isinstance(answers, list):
                raise ValueError("answer must be an object or list of objects")
            annotation_ids = annotation.get("annotation_id")
            if isinstance(annotation_ids, list) and len(annotation_ids) != len(answers):
                raise ValueError("answer and annotation ID lists have different lengths")
            for index, answer in enumerate(answers):
                if not isinstance(answer, dict) or not isinstance(answer.get("unanswerable"), bool):
                    raise ValueError("answer must declare boolean unanswerable")
                yes_no = answer.get("yes_no")
                if yes_no is not None and not isinstance(yes_no, bool):
                    raise ValueError("yes_no must be boolean or null")
                free_form = answer.get("free_form_answer", "")
                if free_form is not None and not isinstance(free_form, str):
                    raise ValueError("free_form_answer must be string or null")
                evidence = []
                for text in text_list(answer.get("evidence", []), "evidence"):
                    ids = evidence_map.get(" ".join(text.split()), [])
                    status = "resolved" if ids else "unresolved"
                    evidence.append({"text": text, "corpus_ids": ids, "status": status})
                    stats["gold_evidence_" + status] += 1
                annotations.append({"annotation_id": annotation_ids[index] if isinstance(annotation_ids, list) else annotation_ids,
                                    "unanswerable": answer["unanswerable"], "yes_no": yes_no,
                                    "extractive_spans": text_list(answer.get("extractive_spans", []), "extractive_spans"),
                                    "free_form_answer": free_form, "evidence": evidence})
        flags = {annotation["unanswerable"] for annotation in annotations}
        kind = "mixed" if len(flags) == 2 else "unanswerable" if flags == {True} else "answerable" if flags else "unlabelled"
        stats["questions_" + kind] += 1
        writer.add_question({"schema_version": QUESTION_SCHEMA,
                             "id": stable_id("qq", "qasper-v0.3", paper_id, source_id),
                             "dataset": "qasper", "split": split, "source_question_id": source_id,
                             "question": required_text(qa.get("question"), "question"),
                             "retrieval_mode": "paper", "candidate_ids": candidate_ids,
                             "metadata": {"paper_id": paper_id},
                             "gold": {"annotations": annotations} if annotations else None}, [document_id])


def musique_question(row: dict[str, Any], split: str, variant: str, writer: PreparedWriter, stats: Counter,
                     retrieval_mode: str = "pooled-context") -> None:
    source_id = required_text(row.get("id"), "question id")
    paragraphs = row.get("paragraphs")
    if not isinstance(paragraphs, list) or not paragraphs:
        raise ValueError("paragraphs must be a non-empty list")
    mapping = {}
    support = []
    for paragraph in paragraphs:
        if not isinstance(paragraph, dict):
            raise ValueError("paragraph must be an object")
        index = paragraph.get("idx")
        if type(index) is not int or index < 0 or index in mapping:
            raise ValueError("paragraph idx must be a unique non-negative integer")
        title = required_text(paragraph.get("title"), "paragraph title")
        text = required_text(paragraph.get("paragraph_text"), "paragraph text")
        identifier = stable_id("mp", "musique-v1.0", title, text)
        # The data provides a question-local idx, not a global Wikipedia paragraph ID.
        # Keep that mapping in scorer-only questions, and identify the pooled source by title + exact text.
        writer.add_source(source_unit("musique", split, identifier, identifier, title, text,
                                      {"block_type": "paragraph", "source_field": "paragraph_text"}))
        mapping[index] = identifier
        if paragraph.get("is_supporting") is True:
            support.append(identifier)
    gold = None
    if "answer" in row:
        if not isinstance(row.get("answerable"), bool) or not isinstance(row["answer"], str):
            raise ValueError("labelled MuSiQue rows require boolean answerable and string answer")
        if any(type(paragraph.get("is_supporting")) is not bool for paragraph in paragraphs):
            raise ValueError("labelled paragraphs require boolean is_supporting")
        decomposition = row.get("question_decomposition", [])
        if not isinstance(decomposition, list) or any(not isinstance(step, dict) for step in decomposition):
            raise ValueError("question_decomposition must be a list of objects")
        gold = {"answerable": row["answerable"], "answer": row["answer"],
                "answer_aliases": text_list(row.get("answer_aliases", []), "answer_aliases"),
                "support_ids": list(dict.fromkeys(support)), "decomposition": decomposition}
        stats["questions_answerable" if row["answerable"] else "questions_unanswerable"] += 1
    else:
        stats["questions_unlabelled"] += 1
    ordered_mapping = sorted(mapping.items())
    candidates = list(dict.fromkeys(identifier for _, identifier in ordered_mapping))
    writer.add_question({"schema_version": QUESTION_SCHEMA,
                         "id": stable_id("mq", "musique-v1.0", source_id, ordered_mapping), "dataset": "musique", "split": split,
                         "source_question_id": source_id, "question": required_text(row.get("question"), "question"),
                         "retrieval_mode": retrieval_mode, "candidate_ids": candidates,
                         "metadata": {"variant": variant,
                                      "source_candidates": [{"paragraph_idx": index, "corpus_id": identifier}
                                                            for index, identifier in ordered_mapping]},
                         "gold": gold}, candidates if retrieval_mode == "distractor" else [])


def prepare_dataset(dataset: str, split: str, data_root: Path, output: Path, variant: str = "full",
                    seed: int = 20260917, smoke: int = 20, regression: int = 200,
                    allow_test: bool = False, retrieval_mode: str = "pooled-context") -> dict[str, Any]:
    if dataset not in SOURCES or variant not in ("ans", "full"):
        raise ValueError("unsupported dataset or MuSiQue variant")
    if retrieval_mode not in ("pooled-context", "distractor"):
        raise ValueError("unsupported MuSiQue retrieval mode")
    allowed_splits = ("train", "validation", "test") if dataset == "qasper" else ("train", "dev", "test")
    if split not in allowed_splits or (split == "test" and not allow_test):
        raise ValueError("unsupported split; test requires --allow-test after final configuration is fixed")
    if smoke < 1 or regression < smoke:
        raise ValueError("profile sizes must satisfy 1 <= smoke <= regression")
    if output.exists():
        raise FileExistsError("output already exists; use a new directory")
    raw = data_root / "raw"
    paths = sorted((raw / "qasper" / split).glob("*.parquet")) if dataset == "qasper" else [
        raw / "musique" / "data" / f"musique_{variant}_v1.0_{split}.jsonl"]
    if not paths or any(not path.is_file() for path in paths):
        raise FileNotFoundError("raw dataset files are missing")
    inputs = [{"path": str(path.relative_to(raw)), "size_bytes": path.stat().st_size,
               "sha256": sha256_file(path)} for path in paths]
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".research-prepare-", dir=str(output.parent)))
    stats = Counter()
    writer = None
    try:
        writer = PreparedWriter(staging / "working.sqlite", seed)
        papers = set()
        for path in paths:
            rows = iter_source_rows(path) if dataset == "qasper" else iter_jsonl(path)
            for row_index, row in enumerate(rows, 1):
                if dataset == "qasper":
                    if row.get("id") in papers:
                        raise ValueError("duplicate paper ID across source shards")
                    qasper_paper(row, split, writer, stats)
                    papers.add(row["id"])
                else:
                    musique_question(row, split, variant, writer, stats, retrieval_mode)
                if row_index % 256 == 0:
                    writer.checkpoint()
        counts = writer.export(staging, smoke, regression)
        if counts["questions"] == 0:
            raise ValueError("source contains no questions")
        writer.close()
        writer = None
        (staging / "working.sqlite").unlink()
        if any(path.stat().st_size != item["size_bytes"] or sha256_file(path) != item["sha256"]
               for path, item in zip(paths, inputs)):
            raise ValueError("raw source changed during preparation")
        code = [Path(__file__), Path(__file__).with_name("datasetkit.py"),
                Path(__file__).resolve().parents[1] / "context-selection" / "cs_evalkit.py"]
        manifest = {"schema_version": MANIFEST_SCHEMA, "dataset": dataset, "split": split,
                    "variant": variant if dataset == "musique" else None,
                    "source": SOURCES[dataset], "inputs": inputs,
                    "converter": {"version": CONVERTER_VERSION, "source_hashes": {path.name: sha256_file(path) for path in code}},
                    "sampling": {"seed": seed, "algorithm": "SHA256(seed, question_id), ascending; smoke is a regression prefix"},
                    "retrieval_mode": "paper" if dataset == "qasper" else retrieval_mode,
                    "counts": counts, "statistics": dict(sorted(stats.items())),
                    "outputs": {name: {"size_bytes": (staging / name).stat().st_size, "sha256": sha256_file(staging / name)}
                                for name in OUTPUT_FILES},
                    "boundaries": {"corpus_imported": False, "model_api_calls": 0, "quality_evaluation_executed": False,
                                   "qasper_figure_table_images_and_captions_imported": False,
                                   "musique_fullwiki": False,
                                   "musique_answerability_scope": "original per-question candidates; pooled context may restore omitted support"}}
        (staging / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        if output.exists():
            raise FileExistsError("output appeared during conversion; refusing to overwrite it")
        staging.rename(output)
        return manifest
    finally:
        if writer is not None:
            writer.close()
        shutil.rmtree(staging, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True, choices=tuple(SOURCES))
    parser.add_argument("--split", required=True)
    parser.add_argument("--data-root", type=Path, default=Path(os.environ.get("AGENTIC_DATA_ROOT", "local-data/agentic-research")))
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--variant", choices=("ans", "full"), default="full")
    parser.add_argument("--seed", type=int, default=20260917)
    parser.add_argument("--smoke", type=int, default=20)
    parser.add_argument("--regression", type=int, default=200)
    parser.add_argument("--allow-test", action="store_true")
    parser.add_argument("--retrieval-mode", choices=("pooled-context", "distractor"), default="pooled-context")
    args = parser.parse_args()
    try:
        manifest = prepare_dataset(args.dataset, args.split, args.data_root, args.output,
                                   args.variant, args.seed, args.smoke, args.regression, args.allow_test, args.retrieval_mode)
    except (ValueError, OSError, RuntimeError) as exc:
        parser.exit(1, f"Preparation failed: {exc}\n")
    print(canonical({"dataset": manifest["dataset"], "split": manifest["split"],
                     "counts": manifest["counts"], "statistics": manifest["statistics"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
