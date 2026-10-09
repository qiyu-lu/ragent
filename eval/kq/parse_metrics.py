#!/usr/bin/env python3
"""Parse-layer metrics (plan §4.4), deterministic and offline.

* numeric-fact coverage: share of facts whose anchor is found in the document's text, reported twice —
  against the raw text (missing = "丢失") and after LaTeX unwrapping (missing only there = "变形");
* digit retention: Unicode digits in the parsed text / digits in the ``pdftotext -layout`` text layer;
* empty slots per 1000 non-space characters;
* noise lines (publisher watermark, header/footer).

Input is either the chunk export of ``audit_chunks.py`` (``--chunks``) or one MinerU ``full.md``
(``--full-md`` with ``--doc``).
"""

from __future__ import annotations

import argparse
import sys
from collections import defaultdict
from pathlib import Path
from typing import Dict, List, Mapping, Optional, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))

from evalkit import (  # noqa: E402
    contains_anchor,
    count_digits,
    count_empty_slots,
    count_noise_lines,
    count_non_space,
    load_jsonl,
    read_json,
    sha256_file,
    utc_now_iso,
    write_json,
)

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
DEFAULT_FACTS = REPO_ROOT / "local-data/kq-eval/questions/numeric-facts-v1.jsonl"
DEFAULT_TEXTLAYER = REPO_ROOT / "local-data/kq-eval/textlayer/textlayer-summary.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--facts", type=Path, default=DEFAULT_FACTS)
    parser.add_argument("--textlayer-summary", type=Path, default=DEFAULT_TEXTLAYER)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--chunks", type=Path, help="JSONL from audit_chunks.py")
    source.add_argument("--full-md", type=Path, help="one MinerU full.md")
    parser.add_argument("--doc", help="corpus doc id of --full-md")
    parser.add_argument("--label", default="")
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def fact_anchors(fact: Mapping) -> List[str]:
    """The canonical anchor plus accepted variants (``alt_anchors``), e.g. ``400±20℃`` for ``400 ℃±20 ℃``.

    Variants cover a value whose surface form legitimately changes in the parsed text (unit written
    once instead of twice); they do not loosen the match to the number alone.
    """

    anchors = [str(fact.get("anchor") or "")]
    anchors += [str(value) for value in fact.get("alt_anchors") or [] if str(value).strip()]
    return [anchor for anchor in anchors if anchor.strip()]


def doc_metrics(doc_id: str, texts: Sequence[str], facts: Sequence[Mapping], textlayer_digits: Optional[int]) -> dict:
    """Score one document given its chunk texts (or a single full text)."""

    found_raw: List[str] = []
    found_latex_only: List[str] = []
    missing: List[str] = []
    for fact in facts:
        anchors = fact_anchors(fact)
        if any(contains_anchor(anchor, text) for anchor in anchors for text in texts):
            found_raw.append(str(fact.get("id")))
        elif any(contains_anchor(anchor, text, latex=True) for anchor in anchors for text in texts):
            found_latex_only.append(str(fact.get("id")))
        else:
            missing.append(str(fact.get("id")))
    joined = "\n".join(texts)
    digits = count_digits(joined)
    non_space = count_non_space(joined)
    slots = count_empty_slots(joined)
    total = len(facts)
    return {
        "doc": doc_id,
        "texts": len(texts),
        "facts_total": total,
        "facts_found_raw": len(found_raw),
        "facts_found_after_latex": len(found_raw) + len(found_latex_only),
        "coverage_raw": len(found_raw) / total if total else None,
        "coverage_after_latex": (len(found_raw) + len(found_latex_only)) / total if total else None,
        "facts_deformed_latex": found_latex_only,
        "facts_missing": missing,
        "digits_nd": digits,
        "textlayer_digits_nd": textlayer_digits,
        "digit_retention": (digits / textlayer_digits) if textlayer_digits else None,
        "non_space_chars": non_space,
        "empty_slots": slots,
        "slots_per_1000": (slots * 1000.0 / non_space) if non_space else None,
        "noise_lines": count_noise_lines(joined),
    }


def overall(per_doc: Sequence[Mapping]) -> dict:
    total = sum(item["facts_total"] for item in per_doc)
    raw = sum(item["facts_found_raw"] for item in per_doc)
    latex = sum(item["facts_found_after_latex"] for item in per_doc)
    digits = sum(item["digits_nd"] for item in per_doc)
    textlayer = sum(item["textlayer_digits_nd"] or 0 for item in per_doc if item.get("textlayer_digits_nd"))
    non_space = sum(item["non_space_chars"] for item in per_doc)
    slots = sum(item["empty_slots"] for item in per_doc)
    return {
        "documents": len(per_doc),
        "facts_total": total,
        "coverage_raw": raw / total if total else None,
        "coverage_after_latex": latex / total if total else None,
        "digit_retention": digits / textlayer if textlayer else None,
        "slots_per_1000": slots * 1000.0 / non_space if non_space else None,
        "noise_lines": sum(item["noise_lines"] for item in per_doc),
    }


def textlayer_digits(path: Path) -> Dict[str, int]:
    if not path.is_file():
        return {}
    summary = read_json(path)
    return {item["id"]: int(item["digits_nd"]) for item in summary.get("documents", []) if item.get("digits_nd") is not None}


def main() -> int:
    args = parse_args()
    if args.output.exists():
        print(f"refusing to overwrite {args.output}")
        return 1
    facts = load_jsonl(args.facts) if args.facts.is_file() else []
    facts_by_doc: Dict[str, List[dict]] = defaultdict(list)
    for fact in facts:
        facts_by_doc[str(fact.get("doc"))].append(fact)
    layer = textlayer_digits(args.textlayer_summary)

    texts_by_doc: Dict[str, List[str]] = defaultdict(list)
    if args.chunks:
        for row in load_jsonl(args.chunks):
            texts_by_doc[str(row.get("doc") or row.get("doc_name"))].append(str(row.get("content") or ""))
        source = {"chunks": str(args.chunks), "sha256": sha256_file(args.chunks)}
    else:
        if not args.doc:
            print("--full-md needs --doc <corpus doc id>")
            return 1
        texts_by_doc[args.doc].append(args.full_md.read_text(encoding="utf-8"))
        source = {"full_md": str(args.full_md), "sha256": sha256_file(args.full_md)}

    per_doc = []
    for doc_id in sorted(set(texts_by_doc) | ({args.doc} if args.doc else set())):
        item = doc_metrics(doc_id, texts_by_doc.get(doc_id, []), facts_by_doc.get(doc_id, []), layer.get(doc_id))
        per_doc.append(item)
        retention = item["digit_retention"]
        print(f"{doc_id[:28]:30s} facts={item['facts_found_raw']}/{item['facts_found_after_latex']}/{item['facts_total']} "
              f"(raw/latex/total) digits={item['digits_nd']}/{item['textlayer_digits_nd']} "
              f"retention={'-' if retention is None else format(retention, '.2f')} "
              f"slots/1k={'-' if item['slots_per_1000'] is None else format(item['slots_per_1000'], '.2f')} noise={item['noise_lines']}")
    summary = overall(per_doc)
    write_json(args.output, {
        "schema_version": 1,
        "kind": "kq-parse-metrics",
        "label": args.label,
        "created_at": utc_now_iso(),
        "source": source,
        "facts_sha256": sha256_file(args.facts) if args.facts.is_file() else None,
        "textlayer_summary": str(args.textlayer_summary) if args.textlayer_summary.is_file() else None,
        "overall": summary,
        "documents": per_doc,
    })
    print(f"overall: coverage raw={summary['coverage_raw']} after-latex={summary['coverage_after_latex']} "
          f"digit_retention={summary['digit_retention']} slots/1k={summary['slots_per_1000']}")
    print(f"report: {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
