#!/usr/bin/env python3
"""Create a provenance manifest and optional PostgreSQL custom-format dump."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from evalkit import load_corpus_manifest, sha256_file, write_json


HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
DEFAULT_DATASET = REPO_ROOT / "local-data/eval/dataset-v1.jsonl"
DEFAULT_CORPUS = REPO_ROOT / "local-data/eval/corpus-v1.json"
SAFE_DATABASE = re.compile(r"^ragent_eval_[a-z0-9_]+$")
SAFE_CONTAINER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--checkout", type=Path, required=True)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--config", type=Path, action="append", default=[])
    parser.add_argument("--result", type=Path, action="append", default=[])
    parser.add_argument("--postgres-host", default="127.0.0.1")
    parser.add_argument("--postgres-port", type=int, default=5432)
    parser.add_argument("--postgres-user", default="postgres")
    parser.add_argument(
        "--postgres-container",
        help="run psql/pg_dump inside this Docker container instead of using host clients",
    )
    parser.add_argument("--snapshot-db", action="store_true")
    return parser.parse_args()


def run_text(command: list) -> str:
    completed = subprocess.run(command, check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if completed.returncode != 0:
        raise RuntimeError(completed.stderr.strip() or "command failed: " + " ".join(command))
    return completed.stdout.strip()


def postgres_command(args: list, container: str | None, *, interactive: bool = False) -> list:
    if not container:
        return args
    command = ["docker", "exec"]
    if interactive:
        command.append("-i")
    return [*command, container, *args]


def main() -> int:
    args = parse_args()
    if not SAFE_DATABASE.fullmatch(args.database):
        print("database must be a scoped ragent_eval_* database")
        return 1
    if args.postgres_container and not SAFE_CONTAINER.fullmatch(args.postgres_container):
        print("invalid PostgreSQL container name")
        return 1
    manifest_path = args.output_dir / "manifest.json"
    if manifest_path.exists():
        print(f"refusing to overwrite {manifest_path}")
        return 1
    try:
        _, documents = load_corpus_manifest(args.corpus, REPO_ROOT)
        for required in [args.dataset, args.corpus, args.checkout, *args.config, *args.result]:
            if not required.exists():
                raise RuntimeError(f"missing path: {required}")
        commit = run_text(["git", "-C", str(args.checkout), "rev-parse", "HEAD"])
        status = run_text(["git", "-C", str(args.checkout), "status", "--porcelain=v1"])
        count_sql = (
            "SELECT json_build_object("
            "'knowledge_bases',(SELECT count(*) FROM t_knowledge_base WHERE deleted=0),"
            "'documents',(SELECT count(*) FROM t_knowledge_document WHERE deleted=0),"
            "'chunks',(SELECT count(*) FROM t_knowledge_chunk WHERE deleted=0),"
            "'vectors',(SELECT count(*) FROM t_knowledge_vector),"
            "'intents',(SELECT count(*) FROM t_intent_node WHERE deleted=0)"
            ")::text;"
        )
        psql_connection = ["-U", args.postgres_user]
        if not args.postgres_container:
            psql_connection = [
                "-h",
                args.postgres_host,
                "-p",
                str(args.postgres_port),
                *psql_connection,
            ]
        psql_base = postgres_command([
            "psql",
            "-X",
            "-A",
            "-t",
            "-v",
            "ON_ERROR_STOP=1",
            *psql_connection,
            "-d",
            args.database,
        ], args.postgres_container)
        database_counts = json.loads(run_text(psql_base + ["-c", count_sql]))
    except (OSError, RuntimeError, json.JSONDecodeError) as exc:
        print(f"archive preflight failed: {exc}")
        return 1

    args.output_dir.mkdir(parents=True, exist_ok=True)
    dump = None
    if args.snapshot_db:
        dump_path = args.output_dir / f"{args.database}.dump"
        pg_dump_args = [
            "pg_dump",
            "--format=custom",
            "--no-owner",
            "--no-acl",
        ]
        if args.postgres_container:
            command = postgres_command(
                [*pg_dump_args, "--username", args.postgres_user, args.database],
                args.postgres_container,
            )
            with dump_path.open("wb") as output:
                completed = subprocess.run(command, check=False, stdout=output)
        else:
            command = [
                *pg_dump_args,
                "--host",
                args.postgres_host,
                "--port",
                str(args.postgres_port),
                "--username",
                args.postgres_user,
                "--file",
                str(dump_path),
                args.database,
            ]
            completed = subprocess.run(command, check=False)
        if completed.returncode != 0:
            dump_path.unlink(missing_ok=True)
            print("pg_dump failed; manifest was not written")
            return completed.returncode
        dump = {
            "path": str(dump_path.resolve()),
            "sha256": sha256_file(dump_path),
            "size": dump_path.stat().st_size,
            "format": "postgresql-custom",
        }

    source_files = []
    for document in documents.values():
        path = Path(document["resolved_path"])
        source_files.append(
            {
                "id": document["id"],
                "path": str(path),
                "sha256": sha256_file(path),
                "expected_sha256": document["sha256"],
                "size": path.stat().st_size,
            }
        )
    manifest = {
        "schema_version": 1,
        "kind": "evaluation-archive",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "checkout": {
            "path": str(args.checkout.resolve()),
            "commit": commit,
            "dirty": bool(status),
            "status_porcelain": status.splitlines(),
        },
        "models": {"chat": "qwen3-max", "embedding": "qwen-emb-8b", "rerank": "qwen3-rerank"},
        "dataset": {"path": str(args.dataset.resolve()), "sha256": sha256_file(args.dataset)},
        "corpus_manifest": {"path": str(args.corpus.resolve()), "sha256": sha256_file(args.corpus)},
        "source_files": source_files,
        "configs": [
            {"path": str(path.resolve()), "sha256": sha256_file(path)} for path in args.config
        ],
        "results": [
            {"path": str(path.resolve()), "sha256": sha256_file(path)} for path in args.result
        ],
        "database": {"name": args.database, "counts": database_counts, "dump": dump},
    }
    write_json(manifest_path, manifest)
    print(f"archive manifest: {manifest_path}")
    if dump:
        print(f"database snapshot: {dump['path']} ({dump['sha256']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
