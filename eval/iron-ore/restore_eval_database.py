#!/usr/bin/env python3
"""Restore one allow-listed evaluation database from a custom-format dump.

This is intentionally guarded because it drops and recreates the target DB.
It cannot target the normal ``ragent`` database or any name outside the two
isolated evaluation databases.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path


TARGETS = {
    "ragent_eval_baseline": 14,
    "ragent_eval_current": 15,
}
SAFE_CONTAINER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--database", required=True, choices=sorted(TARGETS))
    parser.add_argument("--dump", type=Path, required=True)
    parser.add_argument("--confirm-database", required=True)
    parser.add_argument("--yes-replace", action="store_true")
    parser.add_argument("--postgres-host", default="127.0.0.1")
    parser.add_argument("--postgres-port", type=int, default=5432)
    parser.add_argument("--postgres-user", default="postgres")
    parser.add_argument(
        "--postgres-container",
        help="run PostgreSQL clients inside this Docker container",
    )
    parser.add_argument("--redis-host", default="127.0.0.1")
    parser.add_argument("--redis-port", type=int, default=6380)
    parser.add_argument(
        "--redis-container",
        help="run redis-cli inside this Docker container using RAGENT_REDIS_PASSWORD",
    )
    return parser.parse_args()


def run(command: list, *, stdin_path: Path | None = None, quiet: bool = False) -> None:
    stdout = subprocess.DEVNULL if quiet else None
    if stdin_path:
        with stdin_path.open("rb") as input_stream:
            completed = subprocess.run(command, check=False, stdin=input_stream, stdout=stdout)
    else:
        completed = subprocess.run(command, check=False, stdout=stdout)
    if completed.returncode != 0:
        raise RuntimeError("command failed: " + " ".join(command))


def docker_exec(container: str, command: list, *, interactive: bool = False) -> list:
    prefix = ["docker", "exec"]
    if interactive:
        prefix.append("-i")
    return [*prefix, container, *command]


def main() -> int:
    args = parse_args()
    if args.confirm_database != args.database or not args.yes_replace:
        print("restore requires both --yes-replace and an exact --confirm-database value")
        return 1
    if not args.dump.is_file():
        print(f"dump does not exist: {args.dump}")
        return 1
    for name, value in (
        ("PostgreSQL", args.postgres_container),
        ("Redis", args.redis_container),
    ):
        if value and not SAFE_CONTAINER.fullmatch(value):
            print(f"invalid {name} container name")
            return 1
    try:
        if args.postgres_container:
            run(
                docker_exec(
                    args.postgres_container,
                    ["pg_restore", "--list"],
                    interactive=True,
                ),
                stdin_path=args.dump,
                quiet=True,
            )
            connection = ["--username", args.postgres_user]
            wrap_postgres = lambda command: docker_exec(args.postgres_container, command)
        else:
            run(["pg_restore", "--list", str(args.dump)], quiet=True)
            connection = [
                "--host",
                args.postgres_host,
                "--port",
                str(args.postgres_port),
                "--username",
                args.postgres_user,
            ]
            wrap_postgres = lambda command: command
        terminate_sql = (
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
            f"WHERE datname = '{args.database}' AND pid <> pg_backend_pid();"
        )
        run(wrap_postgres(["psql", "-X", *connection, "--dbname", "postgres", "--command", terminate_sql]))
        run(wrap_postgres(["dropdb", *connection, "--if-exists", args.database]))
        run(wrap_postgres(["createdb", *connection, args.database]))
        restore_command = [
            "pg_restore",
            *connection,
            "--exit-on-error",
            "--no-owner",
            "--no-acl",
            "--dbname",
            args.database,
        ]
        if args.postgres_container:
            run(
                docker_exec(args.postgres_container, restore_command, interactive=True),
                stdin_path=args.dump,
            )
        else:
            run(
                [
                    *restore_command,
                    str(args.dump),
                ]
            )
        redis_db = TARGETS[args.database]
        if args.redis_container:
            run(
                docker_exec(
                    args.redis_container,
                    [
                        "sh",
                        "-c",
                        'redis-cli --no-auth-warning -a "$RAGENT_REDIS_PASSWORD" -n "$1" FLUSHDB',
                        "redis-flush",
                        str(redis_db),
                    ],
                )
            )
        else:
            run(
                [
                    "redis-cli",
                    "--no-auth-warning",
                    "-h",
                    args.redis_host,
                    "-p",
                    str(args.redis_port),
                    "-n",
                    str(redis_db),
                    "FLUSHDB",
                ]
            )
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        if not args.postgres_container and not os.environ.get("PGPASSWORD"):
            print("PGPASSWORD is not set; PostgreSQL may have rejected authentication", file=sys.stderr)
        if not args.redis_container and not os.environ.get("REDISCLI_AUTH"):
            print("REDISCLI_AUTH is not set; Redis may have rejected authentication", file=sys.stderr)
        return 1
    print(f"restored {args.database} and cleared Redis DB {TARGETS[args.database]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
