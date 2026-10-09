#!/usr/bin/env python3
"""Validate the kq question set and the numeric-fact list against the source documents.

Checks structure, document ids, the type × split layout, and that every anchor can be located in the
reference document's own text (``pdftotext -layout`` for PDFs, cells for XLSX). The scanned
GB/T 16574 has no usable text layer, so its anchors must come from the numeric-fact list, whose rows
for that document are marked ``source: manual`` and verified by hand.

With ``--write-manifest`` the hashes and counts (never the question text) are written for the repo.
"""

from __future__ import annotations

import argparse
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Dict, List, Mapping, Optional, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))

from evalkit import (  # noqa: E402
    QUESTION_TYPES,
    SPLITS,
    contains_anchor,
    corpus_documents,
    extract_source_text,
    load_corpus,
    load_jsonl,
    normalize,
    pymupdf_text,
    sha256_file,
    utc_now_iso,
    write_json,
)

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
DEFAULT_CORPUS = REPO_ROOT / "local-data/kq-eval/corpus-kq-s1.json"
DEFAULT_QUESTIONS = REPO_ROOT / "local-data/kq-eval/questions/questions-draft.jsonl"
DEFAULT_FACTS = REPO_ROOT / "local-data/kq-eval/questions/numeric-facts-draft.jsonl"
DEFAULT_TEXTLAYER = REPO_ROOT / "local-data/kq-eval/textlayer"

# Plan §4.3 targets for the whole set (both splits together).
TARGET_BY_TYPE = {"numeric": 45, "procedure": 25, "survey": 20, "confusion": 15, "unanswerable": 15}
MAX_ANCHORS = 3
FACT_KINDS = {"temperature", "time", "concentration", "mass", "volume", "particle_size", "limit", "precision",
              "count", "ratio", "other"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--questions", type=Path, default=DEFAULT_QUESTIONS)
    parser.add_argument("--facts", type=Path, default=DEFAULT_FACTS)
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--textlayer-dir", type=Path, default=DEFAULT_TEXTLAYER,
                        help="reuse <stem>.txt written by pdf_textlayer.py instead of re-running pdftotext")
    parser.add_argument("--skip-source", action="store_true", help="structure and hashes only; do not open sources")
    parser.add_argument("--write-manifest", type=Path, help="write hashes and counts here (repo-safe, no question text)")
    parser.add_argument("--strict", action="store_true", help="also fail when counts differ from the plan targets")
    return parser.parse_args()


def validate_questions(rows: Sequence[Mapping], documents: Mapping[str, Mapping]) -> List[str]:
    errors: List[str] = []
    seen_ids = set()
    seen_text: Dict[str, str] = {}
    for row in rows:
        rid = str(row.get("id") or "<missing-id>")
        if rid in seen_ids:
            errors.append(f"[{rid}] duplicate id")
        seen_ids.add(rid)
        if row.get("type") not in QUESTION_TYPES:
            errors.append(f"[{rid}] invalid type: {row.get('type')!r}")
        if row.get("split") not in SPLITS:
            errors.append(f"[{rid}] invalid split: {row.get('split')!r}")
        question = str(row.get("question") or "").strip()
        if not question:
            errors.append(f"[{rid}] empty question")
        else:
            key = normalize(question)
            if key in seen_text:
                errors.append(f"[{rid}] duplicate question text of {seen_text[key]}")
            seen_text.setdefault(key, rid)
        if not isinstance(row.get("answerable"), bool):
            errors.append(f"[{rid}] answerable must be boolean")
        refs = list(row.get("reference_docs") or [])
        anchors = list(row.get("anchors") or [])
        if row.get("type") == "unanswerable" and row.get("answerable"):
            errors.append(f"[{rid}] unanswerable type must have answerable=false")
        if row.get("answerable"):
            if not refs:
                errors.append(f"[{rid}] answerable rows need reference_docs")
            if not 1 <= len(anchors) <= MAX_ANCHORS:
                errors.append(f"[{rid}] answerable rows need 1..{MAX_ANCHORS} anchors, got {len(anchors)}")
            if any(not str(anchor).strip() for anchor in anchors):
                errors.append(f"[{rid}] blank anchor")
        else:
            if refs or anchors:
                errors.append(f"[{rid}] unanswerable rows must not carry reference_docs or anchors")
        for ref in refs:
            if ref not in documents:
                errors.append(f"[{rid}] unknown reference doc: {ref!r}")
        if row.get("type") == "confusion" and len(refs) < 1:
            errors.append(f"[{rid}] confusion rows need the intended reference doc")
    return errors


def validate_facts(rows: Sequence[Mapping], documents: Mapping[str, Mapping]) -> List[str]:
    errors: List[str] = []
    seen = set()
    for row in rows:
        fid = str(row.get("id") or "<missing-id>")
        if fid in seen:
            errors.append(f"[{fid}] duplicate fact id")
        seen.add(fid)
        doc = row.get("doc")
        if doc not in documents:
            errors.append(f"[{fid}] unknown doc: {doc!r}")
        if not str(row.get("anchor") or "").strip():
            errors.append(f"[{fid}] empty anchor")
        if not str(row.get("clause") or "").strip():
            errors.append(f"[{fid}] empty clause")
        if row.get("page") is not None and not isinstance(row.get("page"), int):
            errors.append(f"[{fid}] page must be an integer when given")
        if row.get("kind") not in FACT_KINDS:
            errors.append(f"[{fid}] invalid kind: {row.get('kind')!r}")
        if row.get("source") not in (None, "text", "manual"):
            errors.append(f"[{fid}] source must be text or manual")
    return errors


def distribution(rows: Sequence[Mapping]) -> dict:
    by_type_split: Dict[str, Counter] = defaultdict(Counter)
    by_doc: Counter = Counter()
    for row in rows:
        by_type_split[str(row.get("type"))][str(row.get("split"))] += 1
        for ref in row.get("reference_docs") or []:
            by_doc[ref] += 1
    return {
        "total": len(rows),
        "by_type": {qtype: dict(counts) for qtype, counts in sorted(by_type_split.items())},
        "by_split": dict(Counter(str(row.get("split")) for row in rows)),
        "by_doc": dict(sorted(by_doc.items())),
        "paraphrased": sum(1 for row in rows if row.get("paraphrased")),
    }


def balance_errors(rows: Sequence[Mapping], strict: bool) -> List[str]:
    errors: List[str] = []
    dist = distribution(rows)
    for qtype, counts in dist["by_type"].items():
        tune, test = counts.get("tune", 0), counts.get("test", 0)
        if abs(tune - test) > 1:
            errors.append(f"type {qtype}: tune={tune} test={test} differ by more than 1 (plan: 对半分)")
    if strict:
        for qtype, target in TARGET_BY_TYPE.items():
            actual = sum(dist["by_type"].get(qtype, {}).values())
            if abs(actual - target) > 3:
                errors.append(f"type {qtype}: {actual} questions, plan target about {target}")
        if dist["paraphrased"] * 2 < dist["total"]:
            errors.append(f"only {dist['paraphrased']}/{dist['total']} questions marked paraphrased; plan wants at least half")
    return errors


def load_source_texts(documents: Mapping[str, Mapping], textlayer_dir: Optional[Path]) -> Dict[str, str]:
    texts: Dict[str, str] = {}
    for doc_id, doc in documents.items():
        path = Path(doc["path"])
        cached = textlayer_dir / (path.stem + ".txt") if textlayer_dir else None
        if cached and cached.is_file() and path.suffix.lower() == ".pdf":
            plain = pymupdf_text(path)
            texts[doc_id] = cached.read_text(encoding="utf-8") + ("\n\n" + plain if plain else "")
        else:
            texts[doc_id] = extract_source_text(path)
    return texts


def anchor_errors(questions: Sequence[Mapping], facts: Sequence[Mapping], documents: Mapping[str, Mapping],
                  texts: Mapping[str, str]) -> List[str]:
    errors: List[str] = []
    scan_docs = {doc_id for doc_id, doc in documents.items() if doc.get("text_layer") == "scan"}
    facts_by_doc: Dict[str, List[str]] = defaultdict(list)
    for fact in facts:
        facts_by_doc[str(fact.get("doc"))].append(str(fact.get("anchor") or ""))
        if fact.get("doc") in scan_docs:
            if fact.get("source") != "manual":
                errors.append(f"[{fact.get('id')}] scan document facts must be source=manual")
            continue
        text = texts.get(str(fact.get("doc")), "")
        if not contains_anchor(str(fact.get("anchor") or ""), text):
            errors.append(f"[{fact.get('id')}] fact anchor not in source text: {fact.get('anchor')!r}")
    for row in questions:
        refs = list(row.get("reference_docs") or [])
        for anchor in row.get("anchors") or []:
            found = False
            for ref in refs:
                if ref in scan_docs:
                    if any(normalize(anchor) and normalize(anchor) in normalize(fact) for fact in facts_by_doc.get(ref, [])):
                        found = True
                        break
                elif contains_anchor(str(anchor), texts.get(ref, "")):
                    found = True
                    break
            if not found:
                errors.append(f"[{row.get('id')}] anchor not found in {refs}: {anchor!r}")
    return errors


def main() -> int:
    args = parse_args()
    try:
        manifest = load_corpus(args.corpus)
        documents = corpus_documents(manifest)
        questions = load_jsonl(args.questions)
        facts = load_jsonl(args.facts) if args.facts.is_file() else []
    except (OSError, ValueError) as exc:
        print(f"加载失败: {exc}")
        return 1
    errors = validate_questions(questions, documents) + validate_facts(facts, documents)
    errors += balance_errors(questions, args.strict)
    for doc_id, doc in documents.items():
        path = Path(doc["path"])
        if not path.is_file():
            errors.append(f"[{doc_id}] source missing: {path}")
        elif doc.get("sha256") and sha256_file(path) != doc["sha256"]:
            errors.append(f"[{doc_id}] sha256 mismatch for {path.name}")
    if not args.skip_source and not errors:
        try:
            texts = load_source_texts(documents, args.textlayer_dir)
        except RuntimeError as exc:
            errors.append(str(exc))
        else:
            errors += anchor_errors(questions, facts, documents, texts)

    dist = distribution(questions)
    print(f"questions: {dist['total']} rows, sha256={sha256_file(args.questions)}")
    for qtype in QUESTION_TYPES:
        counts = dist["by_type"].get(qtype, {})
        print(f"  {qtype:13s} tune={counts.get('tune', 0):3d} test={counts.get('test', 0):3d}  (plan ~{TARGET_BY_TYPE[qtype]})")
    print(f"  paraphrased: {dist['paraphrased']}/{dist['total']}")
    print("  by doc: " + ", ".join(f"{doc}={count}" for doc, count in dist["by_doc"].items()))
    fact_counts = Counter(str(fact.get("doc")) for fact in facts)
    print(f"facts: {len(facts)} rows" + (f", sha256={sha256_file(args.facts)}" if args.facts.is_file() else "")
          + "; " + ", ".join(f"{doc}={count}" for doc, count in sorted(fact_counts.items())))
    if errors:
        print(f"\n发现 {len(errors)} 个问题:")
        for error in errors:
            print("  -", error)
        return 1
    if args.write_manifest:
        write_json(args.write_manifest, {
            "schema_version": 1,
            "kind": "kq-dataset-manifest",
            "created_at": utc_now_iso(),
            "questions": {"path": str(args.questions.relative_to(REPO_ROOT)) if args.questions.is_absolute() else str(args.questions),
                          "sha256": sha256_file(args.questions), "distribution": dist},
            "facts": {"path": str(args.facts), "sha256": sha256_file(args.facts) if args.facts.is_file() else None,
                      "count": len(facts), "by_doc": dict(sorted(fact_counts.items()))},
            "corpus": {"path": str(args.corpus), "sha256": sha256_file(args.corpus), "version": manifest.get("version")},
        })
        print(f"manifest: {args.write_manifest}")
    print("校验通过：结构、文档哈希、分层分布和全部锚点均有效。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
