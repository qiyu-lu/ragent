#!/usr/bin/env python3
"""Create the two evaluation KBs and ingest the three frozen documents.

The command is deliberately non-destructive.  It reuses an exact, empty KB but
refuses to overwrite or delete an existing populated evaluation KB.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from evalkit import ApiClient, ApiError, load_corpus_manifest, sha256_file, write_json


HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
DEFAULT_CORPUS = REPO_ROOT / "local-data/eval/corpus-v1.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True, help="e.g. http://127.0.0.1:9091/api/ragent")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--token")
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--output", type=Path, required=True, help="write setup manifest here")
    parser.add_argument("--parse-profile", default="fast", choices=["fast", "fidelity"])
    parser.add_argument("--max-chars", type=int, default=1024)
    parser.add_argument("--overlap-chars", type=int, default=128)
    parser.add_argument("--rows-per-chunk", type=int, default=50)
    parser.add_argument("--tolerance-factor", type=int, default=3)
    parser.add_argument("--poll-seconds", type=int, default=5)
    parser.add_argument("--ingest-timeout", type=int, default=1200)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def exact_kb(client: ApiClient, name: str):
    page = client.request_json(
        "/knowledge-base", query={"current": 1, "size": 100, "name": name}
    )
    records = (page or {}).get("records") or []
    exact = [row for row in records if row.get("name") == name]
    if len(exact) > 1:
        raise RuntimeError(f"more than one active KB named {name!r}")
    return exact[0] if exact else None


def create_or_reuse_kb(client: ApiClient, key: str, spec: dict) -> dict:
    existing = exact_kb(client, spec["name"])
    if existing:
        if existing.get("collectionName") != spec["collection_name"]:
            raise RuntimeError(
                f"KB {spec['name']!r} uses collection {existing.get('collectionName')!r}, "
                f"expected {spec['collection_name']!r}"
            )
        if int(existing.get("documentCount") or 0) != 0:
            raise RuntimeError(
                f"KB {spec['name']!r} already contains documents; restore the clean seed instead of overwriting"
            )
        return {
            "key": key,
            "id": str(existing["id"]),
            "name": spec["name"],
            "collection_name": spec["collection_name"],
            "reused": True,
        }
    kb_id = client.request_json(
        "/knowledge-base",
        method="POST",
        body={
            "name": spec["name"],
            "embeddingModel": spec["embedding_model"],
            "collectionName": spec["collection_name"],
        },
    )
    return {
        "key": key,
        "id": str(kb_id),
        "name": spec["name"],
        "collection_name": spec["collection_name"],
        "reused": False,
    }


def wait_for_ingestion(client: ApiClient, doc_id: str, timeout: int, poll_seconds: int) -> dict:
    deadline = time.monotonic() + timeout
    while True:
        document = client.request_json(f"/knowledge-base/docs/{doc_id}")
        status = document.get("status")
        if status == "success":
            return document
        if status == "failed":
            raise RuntimeError(f"document {document.get('docName')} ingestion failed")
        if time.monotonic() >= deadline:
            raise TimeoutError(f"document {document.get('docName')} did not finish in {timeout}s")
        time.sleep(poll_seconds)


def main() -> int:
    args = parse_args()
    if args.output.exists() and not args.dry_run:
        print(f"refusing to overwrite {args.output}")
        return 1
    try:
        corpus, documents = load_corpus_manifest(args.corpus, REPO_ROOT)
    except (OSError, ValueError) as exc:
        print(f"corpus manifest error: {exc}")
        return 1

    for document in documents.values():
        path = Path(document["resolved_path"])
        if not path.is_file():
            print(f"missing source: {path}")
            return 1
        actual_hash = sha256_file(path)
        if actual_hash != document["sha256"]:
            print(f"sha256 mismatch: {path}: {actual_hash}")
            return 1

    ingestion_spec = {
        "parseProfile": args.parse_profile,
        "maxChars": args.max_chars,
        "overlapChars": args.overlap_chars,
        "rowsPerChunk": args.rows_per_chunk,
        "toleranceFactor": args.tolerance_factor,
    }
    if args.dry_run:
        print(json.dumps({"knowledge_bases": corpus["knowledge_bases"], "ingestion_spec": ingestion_spec},
                         ensure_ascii=False, indent=2))
        for document in documents.values():
            print(f"would ingest {document['resolved_path']} -> {document['kb']}")
        return 0

    client = ApiClient(args.base, token=args.token, timeout=max(180, args.ingest_timeout))
    try:
        if not client.token:
            client.login(args.username, args.password)
        kb_state = {
            key: create_or_reuse_kb(client, key, spec)
            for key, spec in corpus["knowledge_bases"].items()
        }
        doc_state = []
        for document in documents.values():
            source = Path(document["resolved_path"])
            kb = kb_state[document["kb"]]
            print(f"uploading {source.name} -> {kb['name']}")
            uploaded = client.upload_file(
                f"/knowledge-base/{kb['id']}/docs/upload",
                {
                    "sourceType": "file",
                    "processMode": "chunk",
                    "ingestionSpec": json.dumps(ingestion_spec, ensure_ascii=False),
                },
                source,
            )
            doc_id = str(uploaded["id"])
            client.request_json(f"/knowledge-base/docs/{doc_id}/chunk", method="POST")
            completed = wait_for_ingestion(client, doc_id, args.ingest_timeout, args.poll_seconds)
            print(f"  success: {completed.get('chunkCount')} chunks")
            doc_state.append(
                {
                    "id": doc_id,
                    "doc_name": completed.get("docName"),
                    "kb": document["kb"],
                    "kb_id": kb["id"],
                    "sha256": document["sha256"],
                    "status": completed.get("status"),
                    "chunk_count": completed.get("chunkCount"),
                    "ingestion_spec": completed.get("ingestionSpec"),
                }
            )
    except (ApiError, OSError, RuntimeError, TimeoutError) as exc:
        print(f"setup failed: {exc}")
        return 1

    write_json(
        args.output,
        {
            "schema_version": 1,
            "created_at": datetime.now(timezone.utc).isoformat(),
            "base": args.base,
            "corpus_version": corpus.get("version"),
            "corpus_sha256": sha256_file(args.corpus),
            "ingestion_spec": ingestion_spec,
            "knowledge_bases": list(kb_state.values()),
            "documents": doc_state,
        },
    )
    print(f"setup manifest: {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
