#!/usr/bin/env python3
"""How much of the stage-3 gain survives two corrections (review of 2026-10-10).

The reported numbers count a hit when any final chunk contains an anchor and average every run. Two things
inflate the stage-3 gains on top of that: S1-base ran on a day with more 15 s vector-channel timeouts than the
S3 runs, and the full-text channel filled in for them; and an anchor found in a chunk of another document
counts as a hit. This script recomputes Hit@5 and MRR per question four ways:

- reported: every run, any document (equals compare_retrieval_repeats when every run has every question);
- doc-scoped: only chunks of the question's reference documents can hit;
- healthy: per question, only the runs whose vector channel answered every sub-question;
- both: doc-scoped and healthy.

It also sorts the answerable questions whose answer chunk (doc-scoped) is in the final context of every run of
the arm but not of every baseline run: "added" when no healthy baseline run had it (what the full-text channel
found by itself), "timeout" when the baseline missed it only in runs with an empty vector channel.

    python3 eval/kq/stage3_sensitivity.py
    python3 eval/kq/stage3_sensitivity.py --output local-data/kq-eval/runs/stage3-sensitivity.json
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Dict, List, Mapping, Optional, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))

from evalkit import anchor_rank, doc_stem, final_chunks, load_corpus, read_json, stem_to_doc_id, write_json  # noqa: E402

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
RUNS = REPO_ROOT / "local-data/kq-eval/runs"
CORPUS = REPO_ROOT / "local-data/kq-eval/corpus-kq-s1.json"
REPEATS = (1, 2, 3)
PAIRS = (("tune", "S1-base", "S3-rrf"), ("test", "S1-base", "S3-thr-0.3"))
VIEWS = {"reported": (False, False), "doc-scoped": (True, False), "healthy": (False, True), "both": (True, True)}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--output", type=Path, help="also write the result as JSON")
    return parser.parse_args()


def vector_timed_out(response: Mapping) -> bool:
    return any(stage.get("stage") == "channel-VectorSearch" and not stage.get("chunkCount")
               for stage in response.get("stages") or [])


def question_score(detail: Mapping, stem_to_id: Mapping[str, str], doc_scoped: bool) -> dict:
    chunks = final_chunks(detail.get("raw_response") or {})
    reference = set(detail.get("reference_docs") or [])

    def counts(chunk: Mapping) -> bool:
        stem = doc_stem(chunk.get("docName"))
        return not doc_scoped or stem_to_id.get(stem, stem) in reference

    # a chunk of another document keeps its rank but cannot hit
    contexts = [chunk["text"] if counts(chunk) else "" for chunk in chunks]
    ranks = [rank for rank in (anchor_rank(anchor, contexts) for anchor in detail.get("anchors") or []) if rank >= 0]
    return {"hit@5": 1.0 if any(rank < 5 for rank in ranks) else 0.0,
            "mrr": 1.0 / (min(ranks) + 1) if ranks else 0.0}


def per_question(reports: Sequence[Mapping], stem_to_id: Mapping[str, str], doc_scoped: bool,
                 healthy_only: bool) -> Dict[str, List[dict]]:
    table: Dict[str, List[dict]] = {}
    for report in reports:
        for detail in report.get("details") or []:
            if not detail.get("answerable"):
                continue
            if healthy_only and vector_timed_out(detail.get("raw_response") or {}):
                continue
            table.setdefault(detail["id"], []).append(
                {**question_score(detail, stem_to_id, doc_scoped), "type": detail.get("type")})
    return table


def compare(base: Mapping[str, List[dict]], arm: Mapping[str, List[dict]], answerable: int,
            question_type: Optional[str] = None) -> dict:
    ids = [qid for qid in base if qid in arm and (question_type is None or base[qid][0]["type"] == question_type)]

    def mean(table: Mapping[str, List[dict]], key: str) -> float:
        return sum(sum(score[key] for score in table[qid]) / len(table[qid]) for qid in ids) / len(ids)

    out: Dict[str, object] = {"n": len(ids), "answerable": answerable}
    for key in ("hit@5", "mrr"):
        out[key] = {"baseline": round(mean(base, key), 4), "candidate": round(mean(arm, key), 4),
                    "delta": round(mean(arm, key) - mean(base, key), 4)}
    return out


def changed_questions(base_reports: Sequence[Mapping], arm_reports: Sequence[Mapping],
                      stem_to_id: Mapping[str, str]) -> Dict[str, List[str]]:
    base: Dict[str, List[tuple]] = {}
    for report in base_reports:
        for detail in report.get("details") or []:
            if detail.get("answerable"):
                reached = question_score(detail, stem_to_id, True)["mrr"] > 0
                base.setdefault(detail["id"], []).append((reached, vector_timed_out(detail.get("raw_response") or {})))
    arm: Dict[str, List[bool]] = {}
    for report in arm_reports:
        for detail in report.get("details") or []:
            if detail.get("answerable"):
                arm.setdefault(detail["id"], []).append(question_score(detail, stem_to_id, True)["mrr"] > 0)
    out: Dict[str, List[str]] = {"added": [], "timeout": [], "mixed": [], "lost": []}
    for qid in sorted(set(base) & set(arm)):
        base_runs, arm_runs = base[qid], arm[qid]
        if all(arm_runs) and not all(reached for reached, _ in base_runs):
            healthy = [reached for reached, timed_out in base_runs if not timed_out]
            if all(timed_out for reached, timed_out in base_runs if not reached):
                out["timeout"].append(qid)
            elif not any(healthy):
                out["added"].append(qid)
            else:
                out["mixed"].append(qid)
        elif all(reached for reached, _ in base_runs) and not all(arm_runs):
            out["lost"].append(qid)
    return out


def analyse(split: str, baseline: str, arm: str, stem_to_id: Mapping[str, str]) -> dict:
    base_reports = [read_json(RUNS / f"{baseline}-{split}-r{r}" / "retrieval.json") for r in REPEATS]
    arm_reports = [read_json(RUNS / f"{arm}-{split}-r{r}" / "retrieval.json") for r in REPEATS]
    answerable = sum(1 for detail in base_reports[0].get("details") or [] if detail.get("answerable"))
    views = {}
    for view, (doc_scoped, healthy_only) in VIEWS.items():
        base = per_question(base_reports, stem_to_id, doc_scoped, healthy_only)
        candidate = per_question(arm_reports, stem_to_id, doc_scoped, healthy_only)
        views[view] = {"overall": compare(base, candidate, answerable),
                       "numeric": compare(base, candidate, answerable, "numeric")}
    empty = {name: [sum(vector_timed_out(detail.get("raw_response") or {}) for detail in report.get("details") or [])
                    for report in reports]
             for name, reports in ((baseline, base_reports), (arm, arm_reports))}
    return {"split": split, "baseline": baseline, "arm": arm, "questions_with_empty_vector_channel": empty,
            "views": views, "questions": changed_questions(base_reports, arm_reports, stem_to_id)}


def main() -> int:
    args = parse_args()
    stem_to_id = stem_to_doc_id(load_corpus(CORPUS))
    result = {"kind": "kq-stage3-sensitivity", "pairs": [analyse(*pair, stem_to_id) for pair in PAIRS]}
    for pair in result["pairs"]:
        print(f"{pair['arm']} vs {pair['baseline']} ({pair['split']}), questions with an empty vector channel per run: "
              f"{pair['questions_with_empty_vector_channel']}")
        for view, blocks in pair["views"].items():
            cells = []
            for scope in ("overall", "numeric"):
                block = blocks[scope]
                cells.append(f"{scope} n={block['n']}/{block['answerable'] if scope == 'overall' else '-'} "
                             f"Hit@5 {block['hit@5']['baseline']:.3f}->{block['hit@5']['candidate']:.3f} "
                             f"({block['hit@5']['delta']:+.3f}) MRR {block['mrr']['baseline']:.3f}->"
                             f"{block['mrr']['candidate']:.3f} ({block['mrr']['delta']:+.3f})")
            print(f"  {view:10s} " + " | ".join(cells))
        print("  answer chunk in every arm run, not in every baseline run: "
              + "; ".join(f"{kind} {len(ids)} {ids}" for kind, ids in pair["questions"].items()))
    if args.output:
        write_json(args.output, result)
        print(f"written {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
