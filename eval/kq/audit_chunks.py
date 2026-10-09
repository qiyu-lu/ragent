#!/usr/bin/env python3
"""Export the ingested chunks of the evaluation knowledge base and summarize their shape.

Chunks are fetched through the normal API (``/knowledge-base/{kb}/docs`` and ``/knowledge-base/docs/{doc}/chunks``)
and written as JSONL so ``parse_metrics.py`` can score them offline. Per document the summary reports
chunk count, characters, Unicode digits, empty slots, noise lines, chunks containing ``$`` (LaTeX) and
exact duplicates, plus the latest chunk log (status, parse timings) when available.
"""

from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path
from typing import Dict, List, Optional

sys.path.insert(0, str(Path(__file__).resolve().parent))

from evalkit import (  # noqa: E402
    ApiClient,
    ApiError,
    count_digits,
    count_empty_slots,
    count_noise_lines,
    count_non_space,
    doc_stem,
    load_corpus,
    normalize,
    percentile,
    sha256_file,
    stem_to_doc_id,
    utc_now_iso,
    write_json,
    write_jsonl,
)

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
DEFAULT_CORPUS = REPO_ROOT / "local-data/kq-eval/corpus-kq-s1.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", required=True)
    parser.add_argument("--kb-id", help="knowledge base id; default: look up --kb-name")
    parser.add_argument("--kb-name", default="kq-s1")
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--output", type=Path, required=True, help="chunks JSONL; summary goes next to it as <stem>-summary.json")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--token")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--max-chars", type=int, default=1024)
    parser.add_argument("--tolerance-factor", type=int, default=3)
    return parser.parse_args()


def fetch_pages(client: ApiClient, path: str, size: int = 100) -> List[dict]:
    rows: List[dict] = []
    page = 1
    while True:
        payload = client.request_json(path, query={"current": page, "size": size})
        records = (payload or {}).get("records") or []
        rows.extend(records)
        if len(records) < size:
            return rows
        page += 1


def find_kb_id(client: ApiClient, name: str) -> str:
    for kb in fetch_pages(client, "/knowledge-base"):
        if kb.get("name") == name:
            return str(kb["id"])
    raise ApiError(f"knowledge base {name!r} not found")


def latest_chunk_log(client: ApiClient, doc_id: str) -> Optional[dict]:
    try:
        payload = client.request_json(f"/knowledge-base/docs/{doc_id}/chunk-logs", query={"current": 1, "size": 1})
    except ApiError:
        return None
    records = (payload or {}).get("records") or []
    return records[0] if records else None


def summarize_document(doc: dict, chunks: List[dict], corpus_id: Optional[str], log: Optional[dict],
                       max_chars: int, tolerance: int) -> dict:
    texts = [str(chunk.get("content") or "") for chunk in chunks]
    normalized = [normalize(text) for text in texts]
    lengths = [len(text) for text in texts]
    counts = Counter(normalized)
    duplicates = sum(count - 1 for text, count in counts.items() if text and count > 1)
    joined = "\n".join(texts)
    return {
        "corpus_doc_id": corpus_id,
        "server_doc_id": str(doc.get("id")),
        "doc_name": doc.get("docName"),
        "status": doc.get("status"),
        "reported_chunk_count": doc.get("chunkCount"),
        "chunk_count": len(chunks),
        "chars": sum(lengths),
        "non_space_chars": count_non_space(joined),
        "digits_nd": count_digits(joined),
        "empty_slots": count_empty_slots(joined),
        "noise_lines": count_noise_lines(joined),
        "chunks_with_latex": sum(1 for text in texts if "$" in text),
        "exact_duplicate_chunks": duplicates,
        "chunk_chars": {
            "mean": round(sum(lengths) / len(lengths), 1) if lengths else None,
            "p95": percentile(lengths, 0.95),
            "max": max(lengths) if lengths else None,
            "over_target": sum(1 for value in lengths if value > max_chars),
            "over_tolerance": sum(1 for value in lengths if value > max_chars * tolerance),
        },
        "ingestion": None if not log else {
            "status": log.get("status"),
            "parse_profile": log.get("parseProfile"),
            "started_at": log.get("startTime"),
            "ended_at": log.get("endTime"),
            "timing_ms": {key: log.get(field) for key, field in (
                ("extract", "extractDuration"), ("chunk", "chunkDuration"), ("embed", "embedDuration"),
                ("persist", "persistDuration"), ("total", "totalDuration"))},
        },
    }


def main() -> int:
    args = parse_args()
    if args.output.exists():
        print(f"refusing to overwrite {args.output}")
        return 1
    try:
        manifest = load_corpus(args.corpus)
    except (OSError, ValueError) as exc:
        print(f"corpus error: {exc}")
        return 1
    stems = stem_to_doc_id(manifest)
    client = ApiClient(args.base, token=args.token, timeout=args.timeout)
    rows: List[dict] = []
    summaries: List[dict] = []
    try:
        if not client.token:
            client.login(args.username, args.password)
        kb_id = args.kb_id or find_kb_id(client, args.kb_name)
        docs = fetch_pages(client, f"/knowledge-base/{kb_id}/docs")
        for doc in docs:
            server_id = str(doc["id"])
            corpus_id = stems.get(doc_stem(doc.get("docName")))
            chunks = fetch_pages(client, f"/knowledge-base/docs/{server_id}/chunks")
            chunks.sort(key=lambda chunk: (chunk.get("chunkIndex") is None, chunk.get("chunkIndex") or 0))
            for chunk in chunks:
                rows.append({
                    "doc": corpus_id, "server_doc_id": server_id, "doc_name": doc.get("docName"),
                    "chunk_id": str(chunk.get("id")), "chunk_index": chunk.get("chunkIndex"),
                    "content": chunk.get("content") or "", "char_count": chunk.get("charCount"),
                    "enabled": chunk.get("enabled"),
                })
            summaries.append(summarize_document(doc, chunks, corpus_id, latest_chunk_log(client, server_id),
                                                args.max_chars, args.tolerance_factor))
            item = summaries[-1]
            print(f"{(corpus_id or item['doc_name'])[:28]:30s} chunks={item['chunk_count']:>4} digits={item['digits_nd']:>6} "
                  f"slots={item['empty_slots']:>3} noise={item['noise_lines']:>3} latex_chunks={item['chunks_with_latex']:>3} "
                  f"status={item['status']}")
    except (ApiError, OSError) as exc:
        print(f"audit failed: {exc}")
        return 1
    write_jsonl(args.output, rows)
    summary_path = args.output.with_name(args.output.stem + "-summary.json")
    write_json(summary_path, {
        "schema_version": 1,
        "kind": "kq-chunk-audit",
        "created_at": utc_now_iso(),
        "base": args.base,
        "kb_id": kb_id,
        "corpus_sha256": sha256_file(args.corpus),
        "chunks_path": str(args.output),
        "chunks_sha256": sha256_file(args.output),
        "documents": summaries,
        "totals": {
            "documents": len(summaries),
            "chunks": len(rows),
            "digits_nd": sum(item["digits_nd"] for item in summaries),
            "empty_slots": sum(item["empty_slots"] for item in summaries),
            "noise_lines": sum(item["noise_lines"] for item in summaries),
        },
    })
    print(f"chunks: {args.output}\nsummary: {summary_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
