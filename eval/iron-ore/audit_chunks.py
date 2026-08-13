#!/usr/bin/env python3
"""Audit parser anchor recovery and chunk-shape statistics after ingestion."""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from evalkit import ApiClient, ApiError, load_jsonl, normalize, percentile, sha256_file, write_json


HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
DEFAULT_ANCHORS = REPO_ROOT / "local-data/eval/parse-anchors-v1.jsonl"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--variant", required=True, choices=["baseline", "current"])
    parser.add_argument("--server-commit", required=True)
    parser.add_argument("--ocr", required=True, choices=["off", "on"])
    parser.add_argument("--setup-manifest", type=Path, required=True)
    parser.add_argument("--parse-anchors", type=Path, default=DEFAULT_ANCHORS)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--token")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--max-chars", type=int, default=1024)
    parser.add_argument("--tolerance-factor", type=int, default=3)
    return parser.parse_args()


def fetch_chunks(client: ApiClient, doc_id: str) -> list:
    chunks = []
    page = 1
    while True:
        payload = client.request_json(
            f"/knowledge-base/docs/{doc_id}/chunks", query={"current": page, "size": 100}
        )
        records = (payload or {}).get("records") or []
        chunks.extend(records)
        if len(records) < 100:
            return chunks
        page += 1


def fetch_latest_chunk_log(client: ApiClient, doc_id: str) -> dict | None:
    payload = client.request_json(
        f"/knowledge-base/docs/{doc_id}/chunk-logs", query={"current": 1, "size": 1}
    )
    records = (payload or {}).get("records") or []
    return records[0] if records else None


def main() -> int:
    args = parse_args()
    if args.output.exists():
        print(f"refusing to overwrite {args.output}")
        return 1
    try:
        setup = json.loads(args.setup_manifest.read_text(encoding="utf-8"))
        anchors = load_jsonl(args.parse_anchors)
    except (OSError, ValueError) as exc:
        print(f"input error: {exc}")
        return 1

    setup_docs = {Path(item["doc_name"]).stem: item for item in setup.get("documents", [])}
    anchors_by_doc = {}
    for anchor in anchors:
        anchors_by_doc.setdefault(anchor["doc_id"], []).append(anchor)

    client = ApiClient(args.base, token=args.token, timeout=args.timeout)
    try:
        if not client.token:
            client.login(args.username, args.password)
        details = []
        for doc_name, expected_anchors in anchors_by_doc.items():
            if doc_name not in setup_docs:
                raise RuntimeError(f"setup manifest has no document named {doc_name!r}")
            state = setup_docs[doc_name]
            chunks = fetch_chunks(client, str(state["id"]))
            chunk_log = fetch_latest_chunk_log(client, str(state["id"]))
            texts = [str(chunk.get("content") or "") for chunk in chunks]
            normalized_texts = [normalize(text) for text in texts]
            anchor_results = []
            for anchor in expected_anchors:
                target = normalize(anchor["anchor"])
                indexes = [index for index, text in enumerate(normalized_texts) if target and target in text]
                anchor_results.append(
                    {
                        "id": anchor["id"],
                        "anchor": anchor["anchor"],
                        "source": anchor["source"],
                        "recovered": bool(indexes),
                        "chunk_indexes": indexes,
                    }
                )
            lengths = [len(text) for text in texts]
            content_counts = Counter(normalized_texts)
            duplicate_chunks = sum(count - 1 for text, count in content_counts.items() if text and count > 1)
            details.append(
                {
                    "doc_id": doc_name,
                    "family": expected_anchors[0]["family"] if expected_anchors else None,
                    "document_id": state["id"],
                    "kb": state["kb"],
                    "chunk_count": len(chunks),
                    "ingestion": (
                        {
                            "status": chunk_log.get("status"),
                            "process_mode": chunk_log.get("processMode"),
                            "parse_profile": chunk_log.get("parseProfile"),
                            "started_at": chunk_log.get("startTime"),
                            "ended_at": chunk_log.get("endTime"),
                            "reported_chunk_count": chunk_log.get("chunkCount"),
                            "timing_ms": {
                                "extract": chunk_log.get("extractDuration"),
                                "chunk": chunk_log.get("chunkDuration"),
                                "embed": chunk_log.get("embedDuration"),
                                "persist": chunk_log.get("persistDuration"),
                                "other": chunk_log.get("otherDuration"),
                                "total": chunk_log.get("totalDuration"),
                            },
                        }
                        if chunk_log
                        else None
                    ),
                    "anchor_recovered": sum(1 for row in anchor_results if row["recovered"]),
                    "anchor_total": len(anchor_results),
                    "anchor_recovery": (
                        sum(1 for row in anchor_results if row["recovered"]) / len(anchor_results)
                        if anchor_results
                        else None
                    ),
                    "anchors": anchor_results,
                    "chunk_chars": {
                        "mean": round(statistics.mean(lengths), 1) if lengths else None,
                        "p95": percentile(lengths, 0.95),
                        "max": max(lengths) if lengths else None,
                        "over_target": sum(1 for value in lengths if value > args.max_chars),
                        "over_tolerance": sum(
                            1 for value in lengths if value > args.max_chars * args.tolerance_factor
                        ),
                    },
                    "exact_duplicate_chunks": duplicate_chunks,
                    "exact_duplicate_ratio": duplicate_chunks / len(chunks) if chunks else 0.0,
                }
            )
    except (ApiError, OSError, RuntimeError) as exc:
        print(f"audit failed: {exc}")
        return 1

    total_anchors = sum(item["anchor_total"] for item in details)
    recovered = sum(item["anchor_recovered"] for item in details)
    report = {
        "schema_version": 1,
        "kind": "parse-audit",
        "label": args.label,
        "variant": args.variant,
        "server_commit": args.server_commit,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "base": args.base,
        "ocr": args.ocr,
        "setup_manifest_sha256": sha256_file(args.setup_manifest),
        "parse_anchors_sha256": sha256_file(args.parse_anchors),
        "summary": {
            "documents": len(details),
            "anchors_recovered": recovered,
            "anchors_total": total_anchors,
            "anchor_recovery": recovered / total_anchors if total_anchors else None,
        },
        "details": details,
    }
    write_json(args.output, report)
    for item in details:
        print(
            f"{item['doc_id']}: anchors {item['anchor_recovered']}/{item['anchor_total']}, "
            f"chunks {item['chunk_count']}"
        )
    print(f"report: {args.output}")
    return 0 if recovered == total_anchors else 2


if __name__ == "__main__":
    sys.exit(main())
