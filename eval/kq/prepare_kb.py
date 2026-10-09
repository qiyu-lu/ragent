#!/usr/bin/env python3
"""Create the stage-1 evaluation knowledge base and ingest the corpus through the normal API.

Non-destructive: an existing empty KB with the same name and collection is reused, a populated one is
refused. Documents are uploaded with the plan's ingestion spec (fast profile, 1024/128/50/3) and chunked
one at a time; the setup manifest records server ids, chunk counts and timings for later audits.
Ingestion goes through RocketMQ and MinerU, so the broker must be up and MINERU_API_KEY set on the server.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Optional

sys.path.insert(0, str(Path(__file__).resolve().parent))

from evalkit import ApiClient, ApiError, load_corpus, sha256_file, utc_now_iso, write_json  # noqa: E402

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
DEFAULT_CORPUS = REPO_ROOT / "local-data/kq-eval/corpus-kq-s1.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", required=True, help="e.g. http://127.0.0.1:9093/api/ragent")
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--output", type=Path, required=True, help="setup manifest path")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--token")
    parser.add_argument("--visibility", default="PUBLIC", choices=["PUBLIC", "PRIVATE", "RESTRICTED"])
    parser.add_argument("--poll-seconds", type=int, default=10)
    parser.add_argument("--ingest-timeout", type=int, default=1800, help="per document")
    parser.add_argument("--only", nargs="*", help="corpus doc ids to ingest (default all)")
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def exact_kb(client: ApiClient, name: str) -> Optional[dict]:
    page = client.request_json("/knowledge-base", query={"current": 1, "size": 100, "name": name})
    exact = [row for row in (page or {}).get("records") or [] if row.get("name") == name]
    if len(exact) > 1:
        raise RuntimeError(f"more than one active KB named {name!r}")
    return exact[0] if exact else None


def create_or_reuse_kb(client: ApiClient, spec: dict, visibility: str) -> dict:
    existing = exact_kb(client, spec["name"])
    if existing:
        if existing.get("collectionName") != spec["collection_name"]:
            raise RuntimeError(f"KB {spec['name']!r} uses collection {existing.get('collectionName')!r}, expected {spec['collection_name']!r}")
        if int(existing.get("documentCount") or 0) != 0:
            raise RuntimeError(f"KB {spec['name']!r} already has documents; use a fresh database or a new KB name")
        return {"id": str(existing["id"]), "name": spec["name"], "collection_name": spec["collection_name"], "reused": True}
    created = client.request_json("/knowledge-base", method="POST", body={
        "name": spec["name"], "embeddingModel": spec["embedding_model"],
        "collectionName": spec["collection_name"], "visibility": visibility,
    })
    kb_id = created["id"] if isinstance(created, dict) else created
    return {"id": str(kb_id), "name": spec["name"], "collection_name": spec["collection_name"], "reused": False}


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
            raise TimeoutError(f"document {document.get('docName')} did not finish in {timeout}s (status={status})")
        time.sleep(poll_seconds)


def main() -> int:
    args = parse_args()
    if args.output.exists() and not args.dry_run:
        print(f"refusing to overwrite {args.output}")
        return 1
    try:
        manifest = load_corpus(args.corpus)
    except (OSError, ValueError) as exc:
        print(f"corpus error: {exc}")
        return 1
    documents = [doc for doc in manifest["documents"] if not args.only or doc["id"] in set(args.only)]
    for doc in documents:
        path = Path(doc["path"])
        if not path.is_file():
            print(f"missing source: {path}")
            return 1
        if sha256_file(path) != doc["sha256"]:
            print(f"sha256 mismatch: {path}")
            return 1
    spec = manifest["ingestion_spec"]
    if args.dry_run:
        print(json.dumps({"knowledge_base": manifest["knowledge_base"], "ingestion_spec": spec,
                          "documents": [doc["doc_name"] for doc in documents]}, ensure_ascii=False, indent=2))
        return 0

    client = ApiClient(args.base, token=args.token, timeout=max(180, args.ingest_timeout))
    started = time.monotonic()
    doc_state = []
    try:
        if not client.token:
            client.login(args.username, args.password)
        kb = create_or_reuse_kb(client, manifest["knowledge_base"], args.visibility)
        print(f"kb {kb['name']} id={kb['id']} ({'reused' if kb['reused'] else 'created'})")
        for doc in documents:
            source = Path(doc["path"])
            print(f"uploading {source.name}")
            uploaded = client.upload_file(f"/knowledge-base/{kb['id']}/docs/upload", {
                "sourceType": "file", "processMode": "chunk", "ingestionSpec": json.dumps(spec, ensure_ascii=False),
            }, source)
            doc_id = str(uploaded["id"])
            doc_started = time.monotonic()
            client.request_json(f"/knowledge-base/docs/{doc_id}/chunk", method="POST")
            completed = wait_for_ingestion(client, doc_id, args.ingest_timeout, args.poll_seconds)
            elapsed = round(time.monotonic() - doc_started, 1)
            print(f"  success: {completed.get('chunkCount')} chunks in {elapsed}s")
            doc_state.append({
                "corpus_doc_id": doc["id"], "server_doc_id": doc_id, "doc_name": completed.get("docName"),
                "sha256": doc["sha256"], "status": completed.get("status"), "chunk_count": completed.get("chunkCount"),
                "ingestion_spec": completed.get("ingestionSpec"), "ingest_seconds": elapsed,
            })
    except (ApiError, OSError, RuntimeError, TimeoutError) as exc:
        print(f"setup failed: {exc}")
        if doc_state:
            write_json(args.output.with_suffix(".partial.json"), {"documents": doc_state, "error": str(exc)})
        return 1
    write_json(args.output, {
        "schema_version": 1,
        "kind": "kq-setup",
        "created_at": utc_now_iso(),
        "base": args.base,
        "corpus_version": manifest.get("version"),
        "corpus_sha256": sha256_file(args.corpus),
        "ingestion_spec": spec,
        "knowledge_base": kb,
        "documents": doc_state,
        "elapsed_seconds": round(time.monotonic() - started, 1),
    })
    print(f"setup manifest: {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
