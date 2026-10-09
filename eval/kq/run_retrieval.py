#!/usr/bin/env python3
"""Run the kq question set against ``POST /rag/eval/replay`` and score the retrieval.

First run of a question set: ``--record-sub-questions`` lets the server rewrite each question once and
stores the resulting sub-questions in a JSONL file. Every later run (repeats, other arms) replays those
sub-questions so the online rewrite model is out of the comparison. Runs are sequential (concurrency 1)
so the three repeats of an arm are comparable.
"""

from __future__ import annotations

import argparse
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Mapping, Optional

sys.path.insert(0, str(Path(__file__).resolve().parent))

from evalkit import (  # noqa: E402
    ApiClient,
    ApiError,
    aggregate,
    load_corpus,
    load_jsonl,
    score_question,
    sha256_file,
    stem_to_doc_id,
    utc_now_iso,
    write_json,
    write_jsonl,
)

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
DEFAULT_QUESTIONS = REPO_ROOT / "local-data/kq-eval/questions/questions-v1.jsonl"
DEFAULT_CORPUS = REPO_ROOT / "local-data/kq-eval/corpus-kq-s1.json"
DEFAULT_SUBQ = REPO_ROOT / "local-data/kq-eval/questions/sub-questions-v1.jsonl"
DEFAULT_RUNS = REPO_ROOT / "local-data/kq-eval/runs"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", required=True, help="e.g. http://127.0.0.1:9093/api/ragent")
    parser.add_argument("--label", required=True, help="run label, e.g. S1-base-tune-r1")
    parser.add_argument("--arm", required=True, help="experiment arm, e.g. S1-base")
    parser.add_argument("--split", choices=["tune", "test", "all"], required=True)
    parser.add_argument("--repeat-index", type=int, required=True)
    parser.add_argument("--server-commit", required=True)
    parser.add_argument("--questions", type=Path, default=DEFAULT_QUESTIONS)
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--sub-questions", type=Path, default=DEFAULT_SUBQ,
                        help="JSONL of {id, question, subQuestions}; replayed when present")
    parser.add_argument("--record-sub-questions", action="store_true",
                        help="let the server rewrite questions that have no stored sub-questions and store them")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--token")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--ids", nargs="*")
    parser.add_argument("--types", nargs="*")
    parser.add_argument("--note", default="")
    return parser.parse_args()


def load_sub_questions(path: Path) -> Dict[str, dict]:
    if not path.is_file():
        return {}
    stored: Dict[str, dict] = {}
    for row in load_jsonl(path):
        stored[str(row["id"])] = row
    return stored


def select_rows(rows: List[dict], args: argparse.Namespace) -> List[dict]:
    selected = rows
    if args.split != "all":
        selected = [row for row in selected if row.get("split") == args.split]
    if args.types:
        selected = [row for row in selected if row.get("type") in set(args.types)]
    if args.ids:
        wanted = set(args.ids)
        selected = [row for row in selected if row.get("id") in wanted]
    return selected


def print_summary(summary: Mapping) -> None:
    overall = summary.get("overall_answerable") or {}
    print("\nanswerable overall (n=%s)" % overall.get("n"))
    for key in ("hit@5", "anchor_recall", "mrr", "context_precision", "doc_recall", "n_chunks"):
        if key in overall:
            print(f"  {key:20s} {overall[key]:.3f}")
    print("by type")
    for qtype, block in (summary.get("by_type") or {}).items():
        if block:
            print(f"  {qtype:13s} n={block['n']:3d} hit@5={block.get('hit@5', 0):.3f} mrr={block.get('mrr', 0):.3f} "
                  f"ctx_prec={block.get('context_precision', 0):.3f}")
    unanswerable = summary.get("unanswerable")
    if unanswerable:
        print(f"unanswerable n={unanswerable['n']} n_chunks={unanswerable.get('n_chunks', 0):.2f} "
              f"max_rerank={unanswerable.get('max_rerank_score')}")
    latency = summary.get("latency_ms") or {}
    if latency:
        print(f"latency: p50={latency['p50']}ms p95={latency['p95']}ms max={latency['max']}ms")


def main() -> int:
    args = parse_args()
    try:
        rows = select_rows(load_jsonl(args.questions), args)
        manifest = load_corpus(args.corpus)
    except (OSError, ValueError) as exc:
        print(f"configuration error: {exc}")
        return 1
    if not rows:
        print("no matching questions")
        return 1
    stored = load_sub_questions(args.sub_questions)
    missing = [row["id"] for row in rows if row["id"] not in stored]
    if missing and not args.record_sub_questions:
        print(f"{len(missing)} questions have no stored sub-questions (e.g. {missing[:3]}); "
              f"run once with --record-sub-questions first")
        return 1

    output = args.output or DEFAULT_RUNS / args.label / "retrieval.json"
    if output.exists():
        print(f"refusing to overwrite {output}")
        return 1

    client = ApiClient(args.base, token=args.token, timeout=args.timeout)
    try:
        if not client.token:
            client.login(args.username, args.password)
    except ApiError as exc:
        print(f"login failed: {exc}")
        return 1

    stems = stem_to_doc_id(manifest)
    details: List[dict] = []
    failures: List[dict] = []
    recorded = 0
    started_at = utc_now_iso()
    started = time.monotonic()
    for index, row in enumerate(rows, 1):
        entry = stored.get(row["id"])
        expected = list(entry["subQuestions"]) if entry else None
        try:
            response, wall_ms = client.replay(row["question"], expected)
            actual = response.get("subQuestions")
            if expected is not None and actual != expected:
                raise ApiError(f"replay returned different subQuestions for {row['id']}: {actual} != {expected}")
            if expected is None:
                if not actual:
                    raise ApiError(f"server returned no subQuestions for {row['id']}")
                stored[row["id"]] = {"id": row["id"], "question": row["question"], "subQuestions": actual,
                                     "rewrittenQuestion": response.get("rewrittenQuestion"),
                                     "recordedAt": utc_now_iso(), "serverCommit": args.server_commit}
                recorded += 1
            score = score_question(row, response, stems)
            details.append({
                "id": row["id"], "type": row.get("type"), "split": row.get("split"),
                "answerable": row.get("answerable"), "reference_docs": row.get("reference_docs") or [],
                "anchors": row.get("anchors") or [], "question": row["question"],
                "mode": response.get("mode"), "wall_ms": wall_ms, "score": score, "raw_response": response,
            })
            hit = score.get("hit@5")
            print(f"[{index}/{len(rows)}] {row['id']} chunks={score['n_chunks']}"
                  + (f" hit@5={hit:.0f} mrr={score.get('mrr', 0):.2f}" if hit is not None else
                     f" max_rerank={score.get('max_rerank_score')}"))
        except (ApiError, OSError) as exc:
            failures.append({"id": row["id"], "error": str(exc)})
            print(f"[{index}/{len(rows)}] failed {row['id']}: {exc}")
    if recorded:
        write_jsonl(args.sub_questions, [stored[key] for key in sorted(stored)])
        print(f"recorded {recorded} sub-question sets -> {args.sub_questions}")

    summary = aggregate(details)
    report = {
        "schema_version": 1,
        "kind": "kq-retrieval",
        "label": args.label,
        "arm": args.arm,
        "split": args.split,
        "repeat_index": args.repeat_index,
        "server_commit": args.server_commit,
        "note": args.note,
        "base": args.base,
        "started_at": started_at,
        "elapsed_seconds": round(time.monotonic() - started, 3),
        "questions_path": str(args.questions),
        "questions_sha256": sha256_file(args.questions),
        "corpus_sha256": sha256_file(args.corpus),
        "sub_questions_sha256": sha256_file(args.sub_questions) if args.sub_questions.is_file() else None,
        "rewrite_mode": "live-and-record" if recorded else "replay",
        "selection": {"split": args.split, "types": args.types, "ids": args.ids, "n": len(rows)},
        "failures": failures,
        "summary": summary,
        "details": details,
    }
    write_json(output, report)
    print_summary(summary)
    print(f"\nreport: {output}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
