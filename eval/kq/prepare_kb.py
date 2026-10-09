#!/usr/bin/env python3
"""Create the stage-1 evaluation knowledge base and ingest the corpus through the normal API.

Non-destructive: an existing empty KB with the same name and collection is reused, a populated one is
refused unless ``--resume`` is given, in which case documents that already succeeded are skipped, failed
or pending ones are re-chunked and missing ones uploaded. Documents are uploaded with the plan's ingestion
spec (fast profile, 1024/128/50/3) and chunked one at a time; the setup manifest records server ids,
chunk counts and timings for later audits. Ingestion goes through RocketMQ and MinerU, so the broker must
be up and MINERU_API_KEY must be in the environment of the server process (a missing key shows up in the
document's chunk log as "MinerU api-key 未配置").
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Dict, List, Mapping, Optional, Sequence, Tuple

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
    parser.add_argument("--kb-name", help="override the corpus KB name (stage 2 ingests the same corpus as kq-s2)")
    parser.add_argument("--collection-name", help="override the corpus collection name")
    parser.add_argument("--poll-seconds", type=int, default=10)
    parser.add_argument("--ingest-timeout", type=int, default=1800, help="per document")
    parser.add_argument("--only", nargs="*", help="corpus doc ids to ingest (default all)")
    parser.add_argument("--resume", action="store_true",
                        help="reuse a populated KB: skip successful documents, re-chunk failed ones, upload missing ones")
    parser.add_argument("--continue-on-failure", action="store_true",
                        help="keep ingesting the other documents when one fails; exit 1 at the end")
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def exact_kb(client: ApiClient, name: str) -> Optional[dict]:
    page = client.request_json("/knowledge-base", query={"current": 1, "size": 100, "name": name})
    exact = [row for row in (page or {}).get("records") or [] if row.get("name") == name]
    if len(exact) > 1:
        raise RuntimeError(f"more than one active KB named {name!r}")
    return exact[0] if exact else None


def create_or_reuse_kb(client: ApiClient, spec: dict, visibility: str, resume: bool = False) -> dict:
    existing = exact_kb(client, spec["name"])
    if existing:
        if existing.get("collectionName") != spec["collection_name"]:
            raise RuntimeError(f"KB {spec['name']!r} uses collection {existing.get('collectionName')!r}, expected {spec['collection_name']!r}")
        if int(existing.get("documentCount") or 0) != 0 and not resume:
            raise RuntimeError(f"KB {spec['name']!r} already has documents; re-run with --resume to continue, "
                               "or use a fresh database / a new KB name")
        return {"id": str(existing["id"]), "name": spec["name"], "collection_name": spec["collection_name"], "reused": True}
    created = client.request_json("/knowledge-base", method="POST", body={
        "name": spec["name"], "embeddingModel": spec["embedding_model"],
        "collectionName": spec["collection_name"], "visibility": visibility,
    })
    kb_id = created["id"] if isinstance(created, dict) else created
    return {"id": str(kb_id), "name": spec["name"], "collection_name": spec["collection_name"], "reused": False}


def existing_documents(client: ApiClient, kb_id: str) -> Dict[str, dict]:
    """Server documents of the KB keyed by docName (first page of 100 is enough for this corpus)."""

    found: Dict[str, dict] = {}
    page = 1
    while True:
        payload = client.request_json(f"/knowledge-base/{kb_id}/docs", query={"current": page, "size": 100})
        records = (payload or {}).get("records") or []
        for record in records:
            found.setdefault(str(record.get("docName")), record)
        if len(records) < 100:
            return found
        page += 1


def plan_actions(documents: Sequence[Mapping], existing_by_name: Mapping[str, Mapping]) -> List[Tuple[dict, str, Optional[dict]]]:
    """Decide per corpus document: skip (already success), wait (running), rechunk (failed/pending) or upload."""

    plan: List[Tuple[dict, str, Optional[dict]]] = []
    for doc in documents:
        current = existing_by_name.get(doc["doc_name"])
        if current is None:
            plan.append((dict(doc), "upload", None))
            continue
        status = str(current.get("status") or "").lower()
        if status == "success":
            plan.append((dict(doc), "skip", dict(current)))
        elif status == "running":
            plan.append((dict(doc), "wait", dict(current)))
        else:
            plan.append((dict(doc), "rechunk", dict(current)))
    return plan


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
    kb_spec = dict(manifest["knowledge_base"])
    if args.kb_name:
        kb_spec["name"] = args.kb_name
    if args.collection_name:
        kb_spec["collection_name"] = args.collection_name
    if args.dry_run:
        print(json.dumps({"knowledge_base": kb_spec, "ingestion_spec": spec,
                          "documents": [doc["doc_name"] for doc in documents]}, ensure_ascii=False, indent=2))
        return 0

    client = ApiClient(args.base, token=args.token, timeout=max(180, args.ingest_timeout))
    started = time.monotonic()
    doc_state = []
    failures = []
    try:
        if not client.token:
            client.login(args.username, args.password)
        kb = create_or_reuse_kb(client, kb_spec, args.visibility, args.resume)
        print(f"kb {kb['name']} id={kb['id']} ({'reused' if kb['reused'] else 'created'})")
        existing = existing_documents(client, kb["id"]) if kb["reused"] else {}
        for doc, action, current in plan_actions(documents, existing):
            source = Path(doc["path"])
            doc_started = time.monotonic()
            try:
                if action == "skip":
                    print(f"skip {source.name}: already success with {current.get('chunkCount')} chunks")
                    completed = current
                    doc_id = str(current["id"])
                else:
                    if action == "upload":
                        print(f"uploading {source.name}")
                        uploaded = client.upload_file(f"/knowledge-base/{kb['id']}/docs/upload", {
                            "sourceType": "file", "processMode": "chunk", "ingestionSpec": json.dumps(spec, ensure_ascii=False),
                        }, source)
                        doc_id = str(uploaded["id"])
                        client.request_json(f"/knowledge-base/docs/{doc_id}/chunk", method="POST")
                    elif action == "rechunk":
                        doc_id = str(current["id"])
                        print(f"re-chunking {source.name} (previous status {current.get('status')})")
                        client.request_json(f"/knowledge-base/docs/{doc_id}/chunk", method="POST")
                    else:
                        doc_id = str(current["id"])
                        print(f"waiting for {source.name} (still running)")
                    completed = wait_for_ingestion(client, doc_id, args.ingest_timeout, args.poll_seconds)
                    print(f"  success: {completed.get('chunkCount')} chunks in {round(time.monotonic() - doc_started, 1)}s")
                doc_state.append({
                    "corpus_doc_id": doc["id"], "server_doc_id": doc_id, "doc_name": completed.get("docName"),
                    "sha256": doc["sha256"], "status": completed.get("status"), "chunk_count": completed.get("chunkCount"),
                    "ingestion_spec": completed.get("ingestionSpec"), "action": action,
                    "ingest_seconds": None if action == "skip" else round(time.monotonic() - doc_started, 1),
                })
            except (ApiError, RuntimeError, TimeoutError) as exc:
                if not args.continue_on_failure:
                    raise
                print(f"  failed: {exc}")
                failures.append({"corpus_doc_id": doc["id"], "doc_name": source.name, "action": action, "error": str(exc)})
    except (ApiError, OSError, RuntimeError, TimeoutError) as exc:
        print(f"setup failed: {exc}")
        if doc_state:
            write_json(args.output.with_suffix(".partial.json"), {"documents": doc_state, "failures": failures, "error": str(exc)})
        print("re-run with --resume once the cause is fixed; successful documents are skipped")
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
        "failures": failures,
        "elapsed_seconds": round(time.monotonic() - started, 1),
    })
    print(f"setup manifest: {args.output}" + (f" ({len(failures)} failed; re-run with --resume)" if failures else ""))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
