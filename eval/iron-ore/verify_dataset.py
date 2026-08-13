#!/usr/bin/env python3
"""Validate the frozen 24-question dataset against the three local sources."""

from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path

from evalkit import extract_source_text, load_corpus_manifest, load_jsonl, normalize, sha256_file


HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
DEFAULT_DATASET = REPO_ROOT / "local-data/eval/dataset-v1.jsonl"
DEFAULT_CORPUS = REPO_ROOT / "local-data/eval/corpus-v1.json"
DEFAULT_PARSE_ANCHORS = REPO_ROOT / "local-data/eval/parse-anchors-v1.jsonl"
VALID_TIERS = {"direct", "hard", "scope_trap", "unanswerable"}
EXPECTED_FAMILIES = {
    "xlsx": 6,
    "native_pdf": 6,
    "scan_pdf": 6,
    "cross_domain": 3,
    "negative": 3,
}
EXPECTED_INTENTS = {"iron": "eval-iron-ore", "titanium": "eval-titanium-ore"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--parse-anchors", type=Path, default=DEFAULT_PARSE_ANCHORS)
    parser.add_argument(
        "--skip-source-extraction",
        action="store_true",
        help="validate schema and hashes only; do not open XLSX or run pdftotext",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    errors = []
    try:
        manifest, documents = load_corpus_manifest(args.corpus, REPO_ROOT)
        rows = load_jsonl(args.dataset)
        parse_anchors = load_jsonl(args.parse_anchors)
    except (OSError, ValueError, RuntimeError) as exc:
        print(f"加载失败: {exc}")
        return 1

    kb_names = set((manifest.get("knowledge_bases") or {}).keys())
    source_text = {}
    for doc_id, document in documents.items():
        path = Path(document["resolved_path"])
        if not path.is_file():
            errors.append(f"[{doc_id}] source file missing: {path}")
            continue
        actual_hash = sha256_file(path)
        if actual_hash != document.get("sha256"):
            errors.append(f"[{doc_id}] sha256 mismatch: {actual_hash}")
        if document.get("kb") not in kb_names:
            errors.append(f"[{doc_id}] unknown kb: {document.get('kb')!r}")
        if not args.skip_source_extraction:
            try:
                source_text[doc_id] = extract_source_text(document)
            except RuntimeError as exc:
                errors.append(str(exc))

    seen = set()
    for row in rows:
        row_id = row.get("id") or "<missing-id>"
        if row_id in seen:
            errors.append(f"[{row_id}] duplicate id")
        seen.add(row_id)
        if row.get("tier") not in VALID_TIERS:
            errors.append(f"[{row_id}] invalid tier: {row.get('tier')!r}")
        if not str(row.get("question") or "").strip():
            errors.append(f"[{row_id}] empty question")
        if not isinstance(row.get("answerable"), bool):
            errors.append(f"[{row_id}] answerable must be boolean")
        if not row.get("expected_facts"):
            errors.append(f"[{row_id}] expected_facts is empty")
        if not row.get("forbidden_claims"):
            errors.append(f"[{row_id}] forbidden_claims is empty")

        refs = row.get("reference_docs") or []
        anchors = row.get("reference_anchors") or []
        if row.get("answerable") and (not refs or not anchors):
            errors.append(f"[{row_id}] answerable rows require reference_docs and reference_anchors")
        if not row.get("answerable") and (refs or anchors):
            errors.append(f"[{row_id}] unanswerable rows must not have references or anchors")
        for ref in refs:
            if ref not in documents:
                errors.append(f"[{row_id}] unknown reference doc: {ref!r}")
        for kb in row.get("expected_kbs") or []:
            if kb not in kb_names:
                errors.append(f"[{row_id}] unknown expected kb: {kb!r}")
        if row.get("intent_scored") and len(row.get("expected_intent_ids") or []) != 1:
            errors.append(f"[{row_id}] intent-scored rows require exactly one expected intent")
        unknown_intents = set(row.get("expected_intent_ids") or []) - set(EXPECTED_INTENTS.values())
        if unknown_intents:
            errors.append(f"[{row_id}] unknown intent codes: {sorted(unknown_intents)}")
        if row.get("intent_scored"):
            expected_for_kb = [EXPECTED_INTENTS[kb] for kb in row.get("expected_kbs") or [] if kb in EXPECTED_INTENTS]
            if row.get("expected_intent_ids") != expected_for_kb:
                errors.append(
                    f"[{row_id}] intent codes must match expected KBs: {expected_for_kb}"
                )

        if source_text and refs:
            searchable = [source_text[ref] for ref in refs if ref in source_text]
            for anchor in anchors:
                target = normalize(anchor)
                if not target or not any(target in normalize(text) for text in searchable):
                    errors.append(f"[{row_id}] anchor not found in {refs}: {anchor!r}")

    families = Counter(row.get("family") for row in rows)
    if len(rows) != 24:
        errors.append(f"dataset must contain 24 rows, got {len(rows)}")
    if dict(families) != EXPECTED_FAMILIES:
        errors.append(f"family distribution must be {EXPECTED_FAMILIES}, got {dict(families)}")
    if sum(1 for row in rows if row.get("intent_scored")) != 18:
        errors.append("exactly the 18 single-document positive questions must score intent")
    if sum(1 for row in rows if row.get("routing_scored")) != 18:
        errors.append("exactly the 18 single-document positive questions must score routing")

    anchor_counts = Counter()
    parse_seen = set()
    for row in parse_anchors:
        anchor_id = row.get("id") or "<missing-id>"
        if anchor_id in parse_seen:
            errors.append(f"[{anchor_id}] duplicate parse anchor id")
        parse_seen.add(anchor_id)
        doc_id = row.get("doc_id")
        if doc_id not in documents:
            errors.append(f"[{anchor_id}] unknown parse-anchor doc: {doc_id!r}")
            continue
        anchor_counts[doc_id] += 1
        if source_text and normalize(row.get("anchor") or "") not in normalize(source_text.get(doc_id, "")):
            errors.append(f"[{anchor_id}] parse anchor not found in source: {row.get('anchor')!r}")
    for doc_id in documents:
        if anchor_counts[doc_id] != 5:
            errors.append(f"[{doc_id}] expected 5 parse anchors, got {anchor_counts[doc_id]}")

    print(f"dataset: {len(rows)} rows, sha256={sha256_file(args.dataset)}")
    print("families: " + ", ".join(f"{key}={families[key]}" for key in EXPECTED_FAMILIES))
    print(f"parse anchors: {len(parse_anchors)} (5 per document)")
    if errors:
        print(f"\n发现 {len(errors)} 个问题:")
        for error in errors:
            print("  -", error)
        return 1
    print("校验通过：结构、文档哈希、24题分布和全部文本锚点均有效。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
