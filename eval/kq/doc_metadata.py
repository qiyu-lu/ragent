#!/usr/bin/env python3
"""Export the document metadata of an evaluation KB for review, then confirm the reviewed values.

Stage 2 (plan §5.3): ingestion extracts a draft per document (standard number, replaced standards,
detection object, component, method) and records the parse-quality audit next to it. ``export`` writes
one reviewable JSON keyed by document name; the reviewer edits only the five governance fields and runs
``confirm``, which PUTs them back (``source`` becomes ``confirmed``; later re-ingestion keeps them).

    python3 eval/kq/doc_metadata.py export  --base $B --setup local-data/kq-eval/runs/setup-s2.json \\
        --output local-data/kq-eval/metadata/doc-metadata-s2.json
    python3 eval/kq/doc_metadata.py confirm --base $B --setup local-data/kq-eval/runs/setup-s2.json \\
        --input local-data/kq-eval/metadata/doc-metadata-s2.json
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Any, Dict, Mapping

sys.path.insert(0, str(Path(__file__).resolve().parent))

from evalkit import ApiClient, ApiError, read_json, sha256_file, utc_now_iso, write_json  # noqa: E402

GOVERNANCE_FIELDS = ("standardNo", "replaces", "objects", "components", "methods")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=["export", "confirm"])
    parser.add_argument("--base", required=True, help="e.g. http://127.0.0.1:9094/api/ragent")
    parser.add_argument("--setup", type=Path, required=True, help="setup manifest written by prepare_kb.py")
    parser.add_argument("--output", type=Path, help="export: review file to write")
    parser.add_argument("--input", type=Path, help="confirm: reviewed file")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin")
    return parser.parse_args()


def review_row(server_doc_id: str, metadata: Mapping[str, Any]) -> Dict[str, Any]:
    """Governance fields to review plus a read-only digest of the parse audit."""

    row: Dict[str, Any] = {"server_doc_id": server_doc_id}
    for field in GOVERNANCE_FIELDS:
        row[field] = metadata.get(field)
    row["source"] = metadata.get("source")
    audit = metadata.get("parseAudit") or {}
    attempts = audit.get("attempts") or []
    chosen = attempts[audit.get("chosenAttempt", 0)] if attempts else {}
    row["_parse"] = {
        "verdict": audit.get("verdict"),
        "textLayerClass": audit.get("textLayerClass"),
        "attempts": len(attempts),
        "chosenParams": chosen.get("params"),
        "digitRetention": chosen.get("digitRetention"),
        "strictSlotsPer1000": chosen.get("strictSlotsPer1000"),
        "firstAttemptFailures": attempts[0].get("failures") if attempts else None,
        "repeatedLinesRemoved": (metadata.get("normalization") or {}).get("repeatedLinesRemoved"),
    }
    return row


def confirm_body(row: Mapping[str, Any]) -> Dict[str, Any]:
    body = {field: row.get(field) for field in GOVERNANCE_FIELDS}
    for field in ("replaces", "objects", "components", "methods"):
        body[field] = list(body.get(field) or [])
    return body


def main() -> int:
    args = parse_args()
    setup = read_json(args.setup)
    documents = {doc["doc_name"]: str(doc["server_doc_id"]) for doc in setup.get("documents") or []}
    client = ApiClient(args.base)
    try:
        client.login(args.username, args.password)
        if args.command == "export":
            if args.output is None or args.output.exists():
                print("export needs a new --output path (refusing to overwrite)")
                return 1
            rows = {name: review_row(doc_id, client.request_json(f"/knowledge-base/docs/{doc_id}/metadata") or {})
                    for name, doc_id in documents.items()}
            write_json(args.output, {"kind": "kq-doc-metadata-review", "created_at": utc_now_iso(),
                                     "setup": str(args.setup), "documents": rows})
            for name, row in rows.items():
                parse = row["_parse"]
                print(f"{name[:30]:32s} {row['standardNo'] or '-':20s} objects={row['objects']} components={row['components']} "
                      f"methods={row['methods']} parse={parse['verdict'] or '-'} retention={parse['digitRetention']} "
                      f"slots/1k={parse['strictSlotsPer1000']} removed={parse['repeatedLinesRemoved']}")
            print(f"review file: {args.output} (edit {', '.join(GOVERNANCE_FIELDS)}, then run confirm)")
            return 0
        if args.input is None:
            print("confirm needs --input")
            return 1
        reviewed = read_json(args.input).get("documents") or {}
        confirmed = {}
        for name, row in reviewed.items():
            doc_id = documents.get(name) or str(row.get("server_doc_id"))
            confirmed[name] = client.request_json(f"/knowledge-base/docs/{doc_id}/metadata", method="PUT",
                                                  body=confirm_body(row))
            print(f"confirmed {name}")
        write_json(args.input.with_name(args.input.stem + "-confirmed.json"),
                   {"kind": "kq-doc-metadata-confirmed", "created_at": utc_now_iso(),
                    "input_sha256": sha256_file(args.input), "documents": confirmed})
        return 0
    except ApiError as exc:
        print(f"api error: {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
